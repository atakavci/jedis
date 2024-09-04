package redis.clients.jedis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class HostAndPorts {

  private static HashMap<String, EndpointConfig> endpointConfigs;

  private static List<HostAndPort> sentinelHostAndPortList = new ArrayList<>();
  private static List<HostAndPort> clusterHostAndPortList = new ArrayList<>();
  private static List<HostAndPort> stableClusterHostAndPortList = new ArrayList<>();

  static {
    String endpointsPath = System.getenv().getOrDefault("REDIS_ENDPOINTS_CONFIG_PATH", "src/test/resources/endpoints.json");
    try {
      endpointConfigs = EndpointConfig.loadFromJSON(endpointsPath);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    sentinelHostAndPortList.add(new HostAndPort("3.255.91.17", 8001));
    sentinelHostAndPortList.add(new HostAndPort("3.255.91.17", 8001));
    sentinelHostAndPortList.add(new HostAndPort("54.74.152.148", 8001));
    sentinelHostAndPortList.add(new HostAndPort("54.74.152.148", 8001));
    sentinelHostAndPortList.add(new HostAndPort("3.255.91.17", 8001 ));

    clusterHostAndPortList.add(new HostAndPort("localhost", 7379));
    clusterHostAndPortList.add(new HostAndPort("localhost", 7380));
    clusterHostAndPortList.add(new HostAndPort("localhost", 7381));
    clusterHostAndPortList.add(new HostAndPort("localhost", 7382));
    clusterHostAndPortList.add(new HostAndPort("localhost", 7383));
    clusterHostAndPortList.add(new HostAndPort("localhost", 7384));

    stableClusterHostAndPortList.add(new HostAndPort("3.255.91.17", 12000));
    stableClusterHostAndPortList.add(new HostAndPort("54.74.152.148", 12000));
    stableClusterHostAndPortList.add(new HostAndPort("54.74.186.132", 12000));
  }

  public static EndpointConfig getRedisEndpoint(String endpointName) {
    if (!endpointConfigs.containsKey(endpointName)) {
      throw new IllegalArgumentException("Unknown Redis endpoint: " + endpointName);
    }

    return endpointConfigs.get(endpointName);
  }

  public static List<HostAndPort> getSentinelServers() {
    return sentinelHostAndPortList;
  }

  public static List<HostAndPort> getClusterServers() {
    return clusterHostAndPortList;
  }

  public static List<HostAndPort> getStableClusterServers() {
    return stableClusterHostAndPortList;
  }

  private HostAndPorts() {
    throw new InstantiationError("Must not instantiate this class");
  }
}
