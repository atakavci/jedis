package redis.clients.jedis.providers;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.*;
import redis.clients.jedis.MultiClusterClientConfig.ClusterConfig;
import redis.clients.jedis.annots.Experimental;
import redis.clients.jedis.annots.VisibleForTesting;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.exceptions.JedisValidationException;
import redis.clients.jedis.mcf.HealthStatus;
import redis.clients.jedis.mcf.HealthStatusChangeEvent;
import redis.clients.jedis.mcf.HealthStatusManager;
import redis.clients.jedis.MultiClusterClientConfig.StrategySupplier;

import redis.clients.jedis.util.Pool;
import redis.clients.jedis.mcf.Endpoint;

import redis.clients.jedis.mcf.HealthCheckStrategy;

/**
 * @author Allen Terleto (aterleto)
 *         <p>
 *         ConnectionProvider which supports multiple cluster/database endpoints each with their own isolated connection
 *         pool. With this ConnectionProvider users can seamlessly failover to Disaster Recovery (DR), Backup, and
 *         Active-Active cluster(s) by using simple configuration which is passed through from Resilience4j -
 *         https://resilience4j.readme.io/docs
 *         <p>
 *         Support for manual failback is provided by way of {@link #setActiveCluster(Endpoint)}
 *         <p>
 */
// TODO: move?
@Experimental
public class MultiClusterPooledConnectionProvider implements ConnectionProvider {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Ordered map of cluster/database endpoints which were provided at startup via the MultiClusterClientConfig. Users
     * can move down (failover) or (up) failback the map depending on their availability and order.
     */
    private final Map<Endpoint, Cluster> multiClusterMap = new ConcurrentHashMap<>();

    /**
     * Indicates the actively used cluster/database endpoint (connection pool) amongst the pre-configured list which
     * were provided at startup via the MultiClusterClientConfig. All traffic will be routed with this cluster/database
     */
    private volatile Cluster activeCluster;

    private final Lock activeClusterIndexLock = new ReentrantLock(true);

    /**
     * Functional interface typically used for activeMultiClusterIndex persistence or custom logging after a successful
     * failover of a cluster/database endpoint (connection pool). Cluster/database endpoint info is passed as the sole
     * parameter Example: cluster:redis-smart-cache.demo.com:12000
     */
    private Consumer<String> clusterFailoverPostProcessor;

    private List<Class<? extends Throwable>> fallbackExceptionList;

    private HealthStatusManager healthStatusManager = new HealthStatusManager();

    // Store retry and circuit breaker configs for dynamic cluster addition/removal
    private RetryConfig retryConfig;
    private CircuitBreakerConfig circuitBreakerConfig;
    private MultiClusterClientConfig multiClusterClientConfig;

    public MultiClusterPooledConnectionProvider(MultiClusterClientConfig multiClusterClientConfig) {

        if (multiClusterClientConfig == null) throw new JedisValidationException(
            "MultiClusterClientConfig must not be NULL for MultiClusterPooledConnectionProvider");

        ////////////// Configure Retry ////////////////////

        RetryConfig.Builder retryConfigBuilder = RetryConfig.custom();
        retryConfigBuilder.maxAttempts(multiClusterClientConfig.getRetryMaxAttempts());
        retryConfigBuilder
            .intervalFunction(IntervalFunction.ofExponentialBackoff(multiClusterClientConfig.getRetryWaitDuration(),
                multiClusterClientConfig.getRetryWaitDurationExponentialBackoffMultiplier()));
        retryConfigBuilder.failAfterMaxAttempts(false); // JedisConnectionException will be thrown
        retryConfigBuilder
            .retryExceptions(multiClusterClientConfig.getRetryIncludedExceptionList().stream().toArray(Class[]::new));

        List<Class> retryIgnoreExceptionList = multiClusterClientConfig.getRetryIgnoreExceptionList();
        if (retryIgnoreExceptionList != null)
            retryConfigBuilder.ignoreExceptions(retryIgnoreExceptionList.stream().toArray(Class[]::new));

        this.retryConfig = retryConfigBuilder.build();

        ////////////// Configure Circuit Breaker ////////////////////

        CircuitBreakerConfig.Builder circuitBreakerConfigBuilder = CircuitBreakerConfig.custom();
        circuitBreakerConfigBuilder
            .failureRateThreshold(multiClusterClientConfig.getCircuitBreakerFailureRateThreshold());
        circuitBreakerConfigBuilder
            .slowCallRateThreshold(multiClusterClientConfig.getCircuitBreakerSlowCallRateThreshold());
        circuitBreakerConfigBuilder
            .slowCallDurationThreshold(multiClusterClientConfig.getCircuitBreakerSlowCallDurationThreshold());
        circuitBreakerConfigBuilder
            .minimumNumberOfCalls(multiClusterClientConfig.getCircuitBreakerSlidingWindowMinCalls());
        circuitBreakerConfigBuilder.slidingWindowType(multiClusterClientConfig.getCircuitBreakerSlidingWindowType());
        circuitBreakerConfigBuilder.slidingWindowSize(multiClusterClientConfig.getCircuitBreakerSlidingWindowSize());
        circuitBreakerConfigBuilder.recordExceptions(
            multiClusterClientConfig.getCircuitBreakerIncludedExceptionList().stream().toArray(Class[]::new));
        circuitBreakerConfigBuilder.automaticTransitionFromOpenToHalfOpenEnabled(false); // State transitions are
                                                                                         // forced. No half open states
                                                                                         // are used

        List<Class> circuitBreakerIgnoreExceptionList = multiClusterClientConfig.getCircuitBreakerIgnoreExceptionList();
        if (circuitBreakerIgnoreExceptionList != null) circuitBreakerConfigBuilder
            .ignoreExceptions(circuitBreakerIgnoreExceptionList.stream().toArray(Class[]::new));

        this.circuitBreakerConfig = circuitBreakerConfigBuilder.build();

        ////////////// Configure Cluster Map ////////////////////

        ClusterConfig[] clusterConfigs = multiClusterClientConfig.getClusterConfigs();
        for (ClusterConfig config : clusterConfigs) {
            addClusterInternal(multiClusterClientConfig, config);
        }

        // selecting activeCluster with configuration values.
        // all health status would be HEALTHY at this point
        activeCluster = findWeightedHealthyClusterToIterate().getValue();

        for (Endpoint endpoint : multiClusterMap.keySet()) {
            healthStatusManager.registerListener(endpoint, this::handleStatusChange);
        }
        /// --- ///

        this.fallbackExceptionList = multiClusterClientConfig.getFallbackExceptionList();
    }

    /**
     * Adds a new cluster endpoint to the provider.
     * @param clusterConfig the configuration for the new cluster
     * @throws JedisValidationException if the endpoint already exists
     */
    public void add(ClusterConfig clusterConfig) {
        if (clusterConfig == null) {
            throw new JedisValidationException("ClusterConfig must not be null");
        }

        Endpoint endpoint = clusterConfig.getHostAndPort();
        if (multiClusterMap.containsKey(endpoint)) {
            throw new JedisValidationException("Endpoint " + endpoint + " already exists in the provider");
        }

        activeClusterIndexLock.lock();
        try {
            addClusterInternal(multiClusterClientConfig, clusterConfig);
            healthStatusManager.registerListener(endpoint, this::handleStatusChange);
        } finally {
            activeClusterIndexLock.unlock();
        }
    }

    /**
     * Removes a cluster endpoint from the provider.
     * @param endpoint the endpoint to remove
     * @throws JedisValidationException if the endpoint doesn't exist or is the last remaining endpoint
     */
    public void remove(Endpoint endpoint) {
        if (endpoint == null) {
            throw new JedisValidationException("Endpoint must not be null");
        }

        if (!multiClusterMap.containsKey(endpoint)) {
            throw new JedisValidationException("Endpoint " + endpoint + " does not exist in the provider");
        }

        if (multiClusterMap.size() < 2) {
            throw new JedisValidationException("Cannot remove the last remaining endpoint");
        }

        activeClusterIndexLock.lock();
        try {
            Cluster clusterToRemove = multiClusterMap.get(endpoint);
            boolean isActiveCluster = (activeCluster == clusterToRemove);

            if (isActiveCluster) {
                log.info("Active cluster is being removed. Finding a new active cluster...");
                Map.Entry<Endpoint, Cluster> candidateCluster = findWeightedHealthyClusterToIterate();
                if (candidateCluster != null) {
                    setActiveCluster(candidateCluster.getValue(), true);
                    log.info("New active cluster set to {}", candidateCluster.getKey());
                } else {
                    throw new JedisException(
                        "Cluster can not be removed due to no healthy cluster available to switch!");
                }
            }

            // Remove from health status manager first
            healthStatusManager.unregisterListener(endpoint, this::handleStatusChange);
            healthStatusManager.remove(endpoint);

            // Remove from cluster map
            multiClusterMap.remove(endpoint);

            // Close the cluster resources
            if (clusterToRemove != null) {
                clusterToRemove.setDisabled(true);
                clusterToRemove.getConnectionPool().close();
            }
        } finally {
            activeClusterIndexLock.unlock();
        }
    }

    /**
     * Internal method to add a cluster configuration. This method is not thread-safe and should be called within
     * appropriate locks.
     */
    private void addClusterInternal(MultiClusterClientConfig multiClusterClientConfig, ClusterConfig config) {
        GenericObjectPoolConfig<Connection> poolConfig = config.getConnectionPoolConfig();

        String clusterId = "cluster:" + config.getHostAndPort();

        Retry retry = RetryRegistry.of(retryConfig).retry(clusterId);

        Retry.EventPublisher retryPublisher = retry.getEventPublisher();
        retryPublisher.onRetry(event -> log.warn(String.valueOf(event)));
        retryPublisher.onError(event -> log.error(String.valueOf(event)));

        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(circuitBreakerConfig).circuitBreaker(clusterId);

        CircuitBreaker.EventPublisher circuitBreakerEventPublisher = circuitBreaker.getEventPublisher();
        circuitBreakerEventPublisher.onCallNotPermitted(event -> log.error(String.valueOf(event)));
        circuitBreakerEventPublisher.onError(event -> log.error(String.valueOf(event)));
        circuitBreakerEventPublisher.onFailureRateExceeded(event -> log.error(String.valueOf(event)));
        circuitBreakerEventPublisher.onSlowCallRateExceeded(event -> log.error(String.valueOf(event)));
        circuitBreakerEventPublisher.onStateTransition(event -> log.warn(String.valueOf(event)));

        ConnectionPool pool;
        if (poolConfig != null) {
            pool = new ConnectionPool(config.getHostAndPort(), config.getJedisClientConfig(), poolConfig);
        } else {
            pool = new ConnectionPool(config.getHostAndPort(), config.getJedisClientConfig());
        }
        Cluster cluster = new Cluster(pool, retry, circuitBreaker, config.getWeight(), multiClusterClientConfig);
        multiClusterMap.put(config.getHostAndPort(), cluster);

        StrategySupplier strategySupplier = config.getHealthCheckStrategySupplier();
        if (strategySupplier != null) {
            HealthCheckStrategy hcs = strategySupplier.get(config.getHostAndPort(), config.getJedisClientConfig());
            healthStatusManager.add(config.getHostAndPort(), hcs);
        }
    }

    private void handleStatusChange(HealthStatusChangeEvent eventArgs) {

        Endpoint endpoint = eventArgs.getEndpoint();
        HealthStatus newStatus = eventArgs.getNewStatus();
        log.info("Health status changed for {} from {} to {}", endpoint, eventArgs.getOldStatus(), newStatus);

        Cluster clusterWithHealthChange = multiClusterMap.get(endpoint);

        if (clusterWithHealthChange == null) return;

        clusterWithHealthChange.setHealthStatus(newStatus);

        if (newStatus.isHealthy()) {
            if (clusterWithHealthChange.isFailbackSupported() && activeCluster != clusterWithHealthChange) {
                // lets check if weighted switching is possible
                Map.Entry<Endpoint, Cluster> failbackCluster = findWeightedHealthyClusterToIterate();
                if (failbackCluster == clusterWithHealthChange
                    && clusterWithHealthChange.getWeight() > activeCluster.getWeight()) {
                    setActiveCluster(clusterWithHealthChange, false);
                }
            }
        } else if (clusterWithHealthChange == activeCluster) {
            if (iterateActiveCluster() != null) {
                this.runClusterFailoverPostProcessor(activeCluster);
            }
        }
    }

    public Endpoint iterateActiveCluster() {
        Map.Entry<Endpoint, Cluster> clusterToIterate = findWeightedHealthyClusterToIterate();
        if (clusterToIterate == null) {
            throw new JedisConnectionException(
                "Cluster/database endpoint could not failover since the MultiClusterClientConfig was not "
                    + "provided with an additional cluster/database endpoint according to its prioritized sequence. "
                    + "If applicable, consider failing back OR restarting with an available cluster/database endpoint");
        }
        Cluster cluster = clusterToIterate.getValue();
        boolean changed = setActiveCluster(cluster, false);
        if (!changed) return null;
        return clusterToIterate.getKey();
    }

    private static Comparator<Map.Entry<Endpoint, Cluster>> maxByWeight = Map.Entry
        .<Endpoint, Cluster> comparingByValue(Comparator.comparing(Cluster::getWeight));

    private static Predicate<Map.Entry<Endpoint, Cluster>> filterByHealth = c -> c.getValue().isHealthy();

    private Map.Entry<Endpoint, Cluster> findWeightedHealthyClusterToIterate() {
        return multiClusterMap.entrySet().stream().filter(filterByHealth)
            .filter(entry -> entry.getValue() != activeCluster).max(maxByWeight).orElse(null);
    }

    /**
     * Design decision was made to defer responsibility for cross-replication validation to the user. Alternatively
     * there was discussion to handle cross-cluster replication validation by setting a key/value pair per hashslot in
     * the active connection (with a TTL) and subsequently reading it from the target connection.
     */
    public void validateTargetConnection(Endpoint endpoint) {
        Cluster cluster = multiClusterMap.get(endpoint);
        validateTargetConnection(cluster);
    }

    private void validateTargetConnection(Cluster cluster) {
        CircuitBreaker circuitBreaker = cluster.getCircuitBreaker();

        State originalState = circuitBreaker.getState();
        try {
            // Transitions the state machine to a CLOSED state, allowing state transition, metrics and event publishing
            // Safe since the activeMultiClusterIndex has not yet been changed and therefore no traffic will be routed
            // yet
            circuitBreaker.transitionToClosedState();

            try (Connection targetConnection = cluster.getConnection()) {
                targetConnection.ping();
            }
        } catch (Exception e) {

            // If the original state was FORCED_OPEN, then transition it back which stops state transition, metrics and
            // event publishing
            if (State.FORCED_OPEN.equals(originalState)) circuitBreaker.transitionToForcedOpenState();

            throw new JedisValidationException(
                circuitBreaker.getName() + " failed to connect. Please check configuration and try again.", e);
        }
    }

    public void setActiveCluster(Endpoint endpoint) {
        if (endpoint == null) {
            throw new JedisValidationException("Provided endpoint is null. Please use one from the configuration");
        }
        Cluster cluster = multiClusterMap.get(endpoint);
        if (cluster == null) {
            throw new JedisValidationException("Provided endpoint: " + endpoint + " is not within "
                + "the configured endpoints. Please use one from the configuration");
        }
        setActiveCluster(cluster, true);
    }

    private boolean setActiveCluster(Cluster cluster, boolean validateConnection) {
        // Cluster cluster = clusterEntry.getValue();
        // Field-level synchronization is used to avoid the edge case in which
        // incrementActiveMultiClusterIndex() is called at the same time
        activeClusterIndexLock.lock();

        try {

            // Allows an attempt to reset the current cluster from a FORCED_OPEN to CLOSED state in the event that no
            // failover is possible
            if (activeCluster == cluster && !cluster.isCBForcedOpen()) return false;

            if (validateConnection) validateTargetConnection(cluster);

            String originalClusterName = getClusterCircuitBreaker().getName();

            if (activeCluster == cluster)
                log.warn("Cluster/database endpoint '{}' successfully closed its circuit breaker", originalClusterName);
            else log.warn("Cluster/database endpoint successfully updated from '{}' to '{}'", originalClusterName,
                cluster.circuitBreaker.getName());
            Cluster temp = activeCluster;
            activeCluster = cluster;
            return temp != activeCluster;
        } finally {
            activeClusterIndexLock.unlock();
        }
    }

    @Override
    public void close() {
        activeCluster.getConnectionPool().close();
    }

    @Override
    public Connection getConnection() {
        return activeCluster.getConnection();
    }

    public Connection getConnection(Endpoint endpoint) {
        return multiClusterMap.get(endpoint).getConnection();
    }

    @Override
    public Connection getConnection(CommandArguments args) {
        return activeCluster.getConnection();
    }

    @Override
    public Map<?, Pool<Connection>> getConnectionMap() {
        ConnectionPool connectionPool = activeCluster.getConnectionPool();
        return Collections.singletonMap(connectionPool.getFactory(), connectionPool);
    }

    public Cluster getCluster() {
        return activeCluster;
    }

    @VisibleForTesting
    public Cluster getCluster(Endpoint multiClusterIndex) {
        return multiClusterMap.get(multiClusterIndex);
    }

    public CircuitBreaker getClusterCircuitBreaker() {
        return activeCluster.getCircuitBreaker();
    }

    public CircuitBreaker getClusterCircuitBreaker(int multiClusterIndex) {
        return activeCluster.getCircuitBreaker();
    }

    /**
     * Indicates the final cluster/database endpoint (connection pool), according to the pre-configured list provided at
     * startup via the MultiClusterClientConfig, is unavailable and therefore no further failover is possible. Users can
     * manually failback to an available cluster
     */
    public boolean canIterateOnceMore() {
        Map.Entry<Endpoint, Cluster> e = findWeightedHealthyClusterToIterate();
        return e != null;
    }

    public void runClusterFailoverPostProcessor(Cluster cluster) {
        if (clusterFailoverPostProcessor != null)
            clusterFailoverPostProcessor.accept(cluster.getCircuitBreaker().getName());
    }

    public void setClusterFailoverPostProcessor(Consumer<String> clusterFailoverPostProcessor) {
        this.clusterFailoverPostProcessor = clusterFailoverPostProcessor;
    }

    public List<Class<? extends Throwable>> getFallbackExceptionList() {
        return fallbackExceptionList;
    }

    public static class Cluster {

        private final ConnectionPool connectionPool;
        private final Retry retry;
        private final CircuitBreaker circuitBreaker;
        private final float weight;
        // it starts its life with the assumption of being healthy
        private HealthStatus healthStatus = HealthStatus.HEALTHY;
        private MultiClusterClientConfig multiClusterClientConfig;
        private boolean disabled = false;

        public Cluster(ConnectionPool connectionPool, Retry retry, CircuitBreaker circuitBreaker, float weight,
            MultiClusterClientConfig multiClusterClientConfig) {
            this.connectionPool = connectionPool;
            this.retry = retry;
            this.circuitBreaker = circuitBreaker;
            this.weight = weight;
            this.multiClusterClientConfig = multiClusterClientConfig;
        }

        public Connection getConnection() {
            return connectionPool.getResource();
        }

        public ConnectionPool getConnectionPool() {
            return connectionPool;
        }

        public Retry getRetry() {
            return retry;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        public HealthStatus getHealthStatus() {
            return healthStatus;
        }

        public void setHealthStatus(HealthStatus healthStatus) {
            this.healthStatus = healthStatus;
        }

        /**
         * Assigned weight for this cluster
         */
        public float getWeight() {
            return weight;
        }

        public boolean isCBForcedOpen() {
            return circuitBreaker.getState() == CircuitBreaker.State.FORCED_OPEN;
        }

        public boolean isHealthy() {
            return healthStatus.isHealthy() && !isCBForcedOpen() && !disabled;
        }

        public boolean retryOnFailover() {
            return multiClusterClientConfig.isRetryOnFailover();
        }

        public boolean isDisabled() {
            return disabled;
        }

        public void setDisabled(boolean disabled) {
            this.disabled = disabled;
        }

        /**
         * Whether failback is supported by client
         */
        public boolean isFailbackSupported() {
            return multiClusterClientConfig.isFailbackSupported();
        }

        @Override
        public String toString() {
            return circuitBreaker.getName() + "{" + "connectionPool=" + connectionPool + ", retry=" + retry
                + ", circuitBreaker=" + circuitBreaker + ", weight=" + weight + ", healthStatus=" + healthStatus
                + ", multiClusterClientConfig=" + multiClusterClientConfig + '}';
        }
    }

}