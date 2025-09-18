package redis.clients.jedis.mcf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.MultiClusterClientConfig;

public class DefaultValuesTest {

        HostAndPort endpoint = new HostAndPort("localhost", 6379);
        JedisClientConfig config = DefaultJedisClientConfig.builder().build();

        @Test
        void testDefaultValuesInConfig() {

                MultiClusterClientConfig.ClusterConfig clusterConfig = MultiClusterClientConfig.ClusterConfig
                                .builder(endpoint, config).build();
                MultiClusterClientConfig multiConfig = new MultiClusterClientConfig.Builder(
                                new MultiClusterClientConfig.ClusterConfig[] { clusterConfig })
                                                .build();

                // check for grace period
                assertEquals(60000, multiConfig.getGracePeriod());

                // check for cluster config
                assertEquals(clusterConfig, multiConfig.getClusterConfigs()[0]);

                // check healthchecks enabled
                assertNotNull(clusterConfig.getHealthCheckStrategySupplier());

                // check default healthcheck strategy is echo
                assertEquals(EchoStrategy.DEFAULT, clusterConfig.getHealthCheckStrategySupplier());

                // TODO: this will be a check for number of probes
                // check number of consecutive successes
                assertEquals(3, clusterConfig.getHealthCheckStrategySupplier().get(endpoint, config)
                                .minConsecutiveSuccessCount());

                // TODO: check delayInBetweenProbes
                // assertEquals(500,
                // clusterConfig.getHealthCheckStrategySupplier().get(testEndpoint,
                // testConfig).getDelayInBetweenProbes());

                // TODO: check for probing policy
                // assertEquals(ProbingPolicy.ALL_SUCCESS,
                // clusterConfig.getHealthCheckStrategySupplier().get(testEndpoint,
                // testConfig).getPolicy());

                // check health check interval
                assertEquals(5000, clusterConfig.getHealthCheckStrategySupplier()
                                .get(endpoint, config).getInterval());

                // check lag aware tolerance
                LagAwareStrategy.Config lagAwareConfig = LagAwareStrategy.Config
                                .builder(endpoint, config.getCredentialsProvider()).build();
                assertEquals(Duration.ofMillis(5000), lagAwareConfig.getAvailabilityLagTolerance());

                // TODO: check CB number of failures threshold -- 1000

                // TODO: check CB duration -- 2000 ms

                // check failback check interval
                assertEquals(120000, multiConfig.getFailbackCheckInterval());

                // TODO: check failover max attempts before give up -- 10

                // TODO: check delay between failover attempts -- 12000 ms

        }
}
