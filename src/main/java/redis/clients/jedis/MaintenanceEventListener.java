package redis.clients.jedis;

import redis.clients.jedis.MaintenanceEvent.FailedOverEvent;
import redis.clients.jedis.MaintenanceEvent.FailingOverEvent;
import redis.clients.jedis.MaintenanceEvent.MigratedEvent;
import redis.clients.jedis.MaintenanceEvent.MigratingEvent;
import redis.clients.jedis.MaintenanceEvent.MovingEvent;

/**
 * Typed listener for server maintenance push events. Registered on a {@link Connection} via
 * {@link Connection#addMaintenanceEventListener}; the connection dispatches each parsed event to
 * the matching method synchronously on its read thread, before the triggering read returns. A
 * listener may mutate the delivering connection (e.g. relax timeouts, request rebind); exceptions
 * propagate to the read loop.
 */
public interface MaintenanceEventListener {

  void onMoving(MovingEvent e, Connection c);

  void onMigrating(MigratingEvent e, Connection c);

  void onMigrated(MigratedEvent e, Connection c);

  void onFailingOver(FailingOverEvent e, Connection c);

  void onFailedOver(FailedOverEvent e, Connection c);
}
