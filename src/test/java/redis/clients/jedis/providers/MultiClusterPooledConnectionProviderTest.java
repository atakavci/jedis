package redis.clients.jedis.providers;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.*;
import redis.clients.jedis.MultiClusterClientConfig.ClusterConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisValidationException;
import redis.clients.jedis.mcf.Endpoint;
import redis.clients.jedis.providers.MultiClusterPooledConnectionProvider.Cluster;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @see MultiClusterPooledConnectionProvider
 */
public class MultiClusterPooledConnectionProviderTest {

    private final EndpointConfig endpointStandalone0 = HostAndPorts.getRedisEndpoint("standalone0");
    private final EndpointConfig endpointStandalone1 = HostAndPorts.getRedisEndpoint("standalone1");

    private volatile MultiClusterPooledConnectionProvider provider;

    @BeforeEach
    public void setUp() {

        ClusterConfig[] clusterConfigs = new ClusterConfig[2];
        clusterConfigs[0] = ClusterConfig
            .builder(endpointStandalone0.getHostAndPort(), endpointStandalone0.getClientConfigBuilder().build())
            .weight(0.5f).build();
        clusterConfigs[1] = ClusterConfig
            .builder(endpointStandalone1.getHostAndPort(), endpointStandalone1.getClientConfigBuilder().build())
            .weight(0.3f).build();

        provider = new MultiClusterPooledConnectionProvider(
            new MultiClusterClientConfig.Builder(clusterConfigs).build());
    }

    @AfterEach
    public void destroy() {
        provider.close();
        provider = null;
    }

    @Test
    public void testCircuitBreakerForcedTransitions() {

        CircuitBreaker circuitBreaker = provider.getClusterCircuitBreaker();
        circuitBreaker.getState();

        if (CircuitBreaker.State.FORCED_OPEN.equals(circuitBreaker.getState()))
            circuitBreaker.transitionToClosedState();

        circuitBreaker.transitionToForcedOpenState();
        assertEquals(CircuitBreaker.State.FORCED_OPEN, circuitBreaker.getState());

        circuitBreaker.transitionToClosedState();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    public void testIterateActiveCluster() {
        Cluster c1 = provider.getCluster(endpointStandalone0.getHostAndPort());
        assertTrue(c1.isHealthy());
        Cluster c2 = provider.getCluster(endpointStandalone1.getHostAndPort());
        assertTrue(c2.isHealthy());

        Endpoint e2 = provider.iterateActiveCluster();
        assertEquals(endpointStandalone1.getHostAndPort(), e2);
    }

    @Test
    public void testIterateActiveClusterOutOfRange() {
        provider.setActiveCluster(endpointStandalone0.getHostAndPort());
        provider.getCluster().setDisabled(true);

        Endpoint e2 = provider.iterateActiveCluster();
        provider.getCluster().setDisabled(true);

        assertEquals(endpointStandalone1.getHostAndPort(), e2);

        assertThrows(JedisConnectionException.class, () -> provider.iterateActiveCluster()); // Should throw an
                                                                                             // exception
    }

    @Test
    public void testCanIterateOnceMore() {
        provider.setActiveCluster(endpointStandalone0.getHostAndPort());
        Cluster c1 = provider.getCluster(endpointStandalone0.getHostAndPort());
        assertTrue(c1.isHealthy());
        Cluster c2 = provider.getCluster(endpointStandalone1.getHostAndPort());
        assertTrue(c2.isHealthy());
        provider.getCluster().setDisabled(true);
        assertFalse(c1.isHealthy());
        assertTrue(c2.isHealthy());
        provider.iterateActiveCluster();

        assertFalse(provider.canIterateOnceMore());
    }

    @Test
    public void testRunClusterFailoverPostProcessor() {
        ClusterConfig[] clusterConfigs = new ClusterConfig[2];
        clusterConfigs[0] = ClusterConfig
            .builder(new HostAndPort("purposefully-incorrect", 0000), DefaultJedisClientConfig.builder().build())
            .weight(0.5f).healthCheckEnabled(false).build();
        clusterConfigs[1] = ClusterConfig
            .builder(new HostAndPort("purposefully-incorrect", 0001), DefaultJedisClientConfig.builder().build())
            .weight(0.4f).healthCheckEnabled(false).build();

        MultiClusterClientConfig.Builder builder = new MultiClusterClientConfig.Builder(clusterConfigs);

        // Configures a single failed command to trigger an open circuit on the next subsequent failure
        builder.circuitBreakerSlidingWindowSize(1);
        builder.circuitBreakerSlidingWindowMinCalls(1);

        AtomicBoolean isValidTest = new AtomicBoolean(false);

        MultiClusterPooledConnectionProvider localProvider = new MultiClusterPooledConnectionProvider(builder.build());
        localProvider.setClusterFailoverPostProcessor(a -> {
            isValidTest.set(true);
        });

        try (UnifiedJedis jedis = new UnifiedJedis(localProvider)) {

            // This will fail due to unable to connect and open the circuit which will trigger the post processor
            try {
                jedis.get("foo");
            } catch (Exception e) {
            }

        }

        assertTrue(isValidTest.get());
    }

    @Test
    public void testSetActiveMultiClusterIndexEqualsZero() {
        assertThrows(JedisValidationException.class, () -> provider.setActiveCluster(null)); // Should throw an
                                                                                             // exception
    }

    @Test
    public void testSetActiveMultiClusterIndexLessThanZero() {
        assertThrows(JedisValidationException.class, () -> provider.setActiveCluster(null)); // Should throw an
                                                                                             // exception
    }

    @Test
    public void testSetActiveMultiClusterIndexOutOfRange() {
        assertThrows(JedisValidationException.class, () -> provider.setActiveCluster(new Endpoint() {
            @Override
            public String getHost() {
                return "purposefully-incorrect";
            }

            @Override
            public int getPort() {
                return 0000;
            }
        })); // Should throw an exception
    }

    @Test
    public void testConnectionPoolConfigApplied() {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        ClusterConfig[] clusterConfigs = new ClusterConfig[2];
        clusterConfigs[0] = new ClusterConfig(endpointStandalone0.getHostAndPort(),
            endpointStandalone0.getClientConfigBuilder().build(), poolConfig);
        clusterConfigs[1] = new ClusterConfig(endpointStandalone1.getHostAndPort(),
            endpointStandalone0.getClientConfigBuilder().build(), poolConfig);
        try (MultiClusterPooledConnectionProvider customProvider = new MultiClusterPooledConnectionProvider(
            new MultiClusterClientConfig.Builder(clusterConfigs).build())) {
            MultiClusterPooledConnectionProvider.Cluster activeCluster = customProvider.getCluster();
            ConnectionPool connectionPool = activeCluster.getConnectionPool();
            assertEquals(8, connectionPool.getMaxTotal());
            assertEquals(4, connectionPool.getMaxIdle());
            assertEquals(1, connectionPool.getMinIdle());
        }
    }
}
