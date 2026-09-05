package redis.clients.jedis;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.TimeoutSource.TimeoutInfo;

/**
 * Cluster maintenance coordinator — one per cluster client, the single dedup/apply point for the
 * per-node SMIGRATING/SMIGRATED broadcast. Owns the seq-keyed operations table that (a) folds the
 * N-connection broadcast into one client-wide operation and (b) drives the shared relax gate:
 * timeouts stay relaxed while ANY operation is open, so overlapping migrations unrelax only when
 * the last one closes, and a connection created mid-event relaxes from the moment its overlay is
 * installed. On SMIGRATED it applies the slot delta atomically through
 * {@link JedisClusterInfoCache#applyMigrationDelta} — queued and drained after completion when a
 * full topology refresh is in flight, never blocking a read thread — and retires the connections
 * of nodes left without slots via each pool's own {@link MaintenanceEventController}, resolved at
 * event time (no node map here). The pools remain their controllers' sole owners; this class only
 * calls.
 */
final class ClusterMaintenanceCoordinator implements MaintenanceEventListener {

  private static final Logger logger = LoggerFactory.getLogger(ClusterMaintenanceCoordinator.class);

  private final JedisClusterInfoCache cache;
  /** Backstop for an SMIGRATING whose SMIGRATED is lost, and retention of closed entries. */
  private final long maxRelaxedDurationNanos;
  private final Supplier<TimeoutInfo> timeoutSupplier;

  /** Seq-keyed operations: open entries gate the relax; closed entries absorb late duplicates. */
  private final ConcurrentHashMap<Object, MigrationOperation> operations = new ConcurrentHashMap<>();

  /** SMIGRATED deltas received while a full refresh was in flight; drained post-refresh. */
  private final ConcurrentLinkedQueue<SMigratedEvent> pendingDeltas = new ConcurrentLinkedQueue<>();

  ClusterMaintenanceCoordinator(JedisClusterInfoCache cache,
      MaintenanceNotificationsConfig config) {
    this.cache = cache;
    this.maxRelaxedDurationNanos = config.getRelaxedWindowMaxDuration().toNanos();
    TimeoutInfo relaxedTimeoutInfo = new TimeoutInfo(config.getRelaxedTimeout(),
        config.getRelaxedBlockingTimeout());
    this.timeoutSupplier = () -> hasActiveMigration() ? relaxedTimeoutInfo : null;
    cache.setPostRefreshHook(this::drainPendingDeltas);
  }

  /** The client-wide relax gate consulted by every cluster connection's timeout overlay. */
  Supplier<TimeoutInfo> getTimeoutSupplier() {
    return timeoutSupplier;
  }

  /**
   * True while any migration window is open (SMIGRATING seen, SMIGRATED not yet, TTL unexpired).
   */
  boolean hasActiveMigration() {
    if (operations.isEmpty()) {
      return false;
    }
    boolean active = false;
    for (MigrationOperation op : operations.values()) {
      if (op.isExpired()) {
        operations.remove(op.id, op);
      } else if (!op.closed) {
        active = true;
      }
    }
    return active;
  }

  @Override
  public void onSMigrating(SMigratingEvent e, Connection c) {
    if (logger.isDebugEnabled()) {
      logger.debug("Slot migration starting: {} (seq={}) conn={}", e.slots, e.seq,
        c.toIdentityString());
    }
    long deadline = NanoClock.INSTANCE.getAsLong() + maxRelaxedDurationNanos;
    operations.computeIfAbsent(e.identity(), k -> new MigrationOperation(k, deadline));
    c.applyCurrentTimeout(); // the gate just opened; push the relax to the receiving socket now
  }

  @Override
  public void onSMigrated(SMigratedEvent e, Connection c) {
    long deadline = NanoClock.INSTANCE.getAsLong() + maxRelaxedDurationNanos;
    boolean[] firstDelivery = { false };
    operations.compute(e.identity(), (k, cur) -> { // atomic per identity
      if (cur == null) {
        // SMIGRATED without a preceding SMIGRATING (e.g. connected mid-event): still applied
        firstDelivery[0] = true;
        return MigrationOperation.closed(k, deadline);
      }
      if (!cur.closed) {
        firstDelivery[0] = true;
        return cur.close();
      }
      return cur; // duplicate broadcast delivery; retained to absorb the rest
    });
    c.applyCurrentTimeout(); // unrelax the receiving socket if the gate just shut
    if (!firstDelivery[0]) {
      return;
    }
    logger.debug("Slot migration done (seq={}, entries={})", e.seq, e.migrations.size());
    if (cache.isRenewInFlight()) {
      // Never block the read thread on a running full refresh; apply after it completes. If the
      // refresh finished between the check and the enqueue, drain immediately — poll() makes a
      // double drain harmless.
      pendingDeltas.add(e);
      if (!cache.isRenewInFlight()) {
        drainPendingDeltas();
      }
      return;
    }
    applyDelta(e);
  }

  private void drainPendingDeltas() {
    SMigratedEvent e;
    while ((e = pendingDeltas.poll()) != null) {
      applyDelta(e);
    }
  }

  /**
   * Applies the delta and retires the connections of nodes left without slots (Case 2). A node
   * still owning slots after the delta keeps its connections untouched (Case 1). Retirement goes
   * through the node pool's own controller, resolved now — a pool destroyed by a racing refresh
   * simply no longer resolves.
   */
  private void applyDelta(SMigratedEvent e) {
    Set<HostAndPort> slotless = cache.applyMigrationDelta(e.migrations);
    if (slotless.isEmpty()) {
      return;
    }
    long now = NanoClock.INSTANCE.getAsLong();
    Map<String, ConnectionPool> nodes = cache.getNodes();
    for (HostAndPort node : slotless) {
      ConnectionPool pool = nodes.get(JedisClusterInfoCache.getNodeKey(node));
      MaintenanceEventController controller = pool == null ? null : pool.getMaintenanceController();
      if (controller != null) {
        logger.debug("Node {} owns no slots after migration (seq={}); retiring its connections",
          node, e.seq);
        controller.retireAll(now);
      }
    }
  }

  /** One client-wide migration operation, folded from the per-node broadcast; keyed by seq. */
  private static final class MigrationOperation {
    final Object id;
    final long deadlineNanos;
    final boolean closed;

    private MigrationOperation(Object id, long deadlineNanos, boolean closed) {
      this.id = id;
      this.deadlineNanos = deadlineNanos;
      this.closed = closed;
    }

    MigrationOperation(Object id, long deadlineNanos) {
      this(id, deadlineNanos, false);
    }

    static MigrationOperation closed(Object id, long deadlineNanos) {
      return new MigrationOperation(id, deadlineNanos, true);
    }

    MigrationOperation close() {
      return new MigrationOperation(id, deadlineNanos, true);
    }

    boolean isExpired() {
      return deadlineNanos - NanoClock.INSTANCE.getAsLong() <= 0;
    }
  }

  @Override
  public void onMoving(MovingEvent e, Connection c) {
    logger.warn("Standalone maintenance events are not supported by this controller: {} conn={}", e,
      c);
  }

  @Override
  public void onMigrating(MigratingEvent e, Connection c) {
    logger.warn("Standalone maintenance events are not supported by this controller: {} conn={}", e,
      c);
  }

  @Override
  public void onMigrated(MigratedEvent e, Connection c) {
    logger.warn("Standalone maintenance events are not supported by this controller: {} conn={}", e,
      c);
  }

  @Override
  public void onFailingOver(FailingOverEvent e, Connection c) {
    logger.warn("Standalone maintenance events are not supported by this controller: {} conn={}", e,
      c);
  }

  @Override
  public void onFailedOver(FailedOverEvent e, Connection c) {
    logger.warn("Standalone maintenance events are not supported by this controller: {} conn={}", e,
      c);
  }
}
