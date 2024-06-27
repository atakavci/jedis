package redis.clients.jedis;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.annots.Experimental;
import redis.clients.jedis.csc.ClientSideCache;
import redis.clients.jedis.exceptions.JedisException;

/**
 * PoolableObjectFactory custom impl.
 */
public class ConnectionFactory implements PooledObjectFactory<Connection> {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionFactory.class);

  private final JedisSocketFactory jedisSocketFactory;
  private final JedisClientConfig clientConfig;
  private ClientSideCache clientSideCache = null;

  public ConnectionFactory(final HostAndPort hostAndPort) {
    this.clientConfig = DefaultJedisClientConfig.builder().build();
    this.jedisSocketFactory = new DefaultJedisSocketFactory(hostAndPort);
  }

  public ConnectionFactory(final HostAndPort hostAndPort, final JedisClientConfig clientConfig) {
    this.clientConfig = clientConfig;
    this.jedisSocketFactory = new DefaultJedisSocketFactory(hostAndPort, this.clientConfig);
  }

  @Experimental
  public ConnectionFactory(final HostAndPort hostAndPort, final JedisClientConfig clientConfig, ClientSideCache csCache) {
    this.clientConfig = clientConfig;
    this.jedisSocketFactory = new DefaultJedisSocketFactory(hostAndPort, this.clientConfig);
    this.clientSideCache = csCache;
  }

  public ConnectionFactory(final JedisSocketFactory jedisSocketFactory, final JedisClientConfig clientConfig) {
    this.clientConfig = clientConfig;
    this.jedisSocketFactory = jedisSocketFactory;
  }

  @Override
  public void activateObject(PooledObject<Connection> pooledConnection) throws Exception {
    // what to do ??
  }

  @Override
  public void destroyObject(PooledObject<Connection> pooledConnection) throws Exception {
    final Connection jedis = pooledConnection.getObject();
    if (jedis.isConnected()) {
      try {
        pooledConnections.remove(jedis);
        jedis.close();
      } catch (RuntimeException e) {
        logger.debug("Error while close", e);
      }
    }
  }

  @Override
  public PooledObject<Connection> makeObject() throws Exception {
    try {
      Connection jedis = new Connection(jedisSocketFactory, clientConfig, clientSideCache);
      DefaultPooledObject pooled = new DefaultPooledObject<>(jedis);
      pooledConnections.put(jedis, pooled);
      return pooled;
    } catch (JedisException je) {
      logger.debug("Error while makeObject", je);
      throw je;
    }
  }

  @Override
  public void passivateObject(PooledObject<Connection> pooledConnection) throws Exception {
    // TODO maybe should select db 0? Not sure right now.
  }

  @Override
  public boolean validateObject(PooledObject<Connection> pooledConnection) {
    final Connection jedis = pooledConnection.getObject();
    try {
      // check HostAndPort ??
      return jedis.isConnected() && jedis.ping();
    } catch (final Exception e) {
      logger.warn("Error while validating pooled Connection object.", e);
      return false;
    }
  }

  public static Map<Connection, PooledObject> pooledConnections = new HashMap<Connection, PooledObject>();

  private static Future executor = start();

  private static Future start() {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    return scheduler.scheduleAtFixedRate(() -> consume(), 3, 5, TimeUnit.SECONDS);
  }

  public static void consume() {
    for (Connection conn : pooledConnections.keySet()) {
      try {
        conn.readInvalidationsWithCheckingBroken();
      } catch (Exception e) {
        System.out.println(e.toString());
      }
    }
  }

}
