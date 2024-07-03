package redis.clients.jedis.csc;

import java.util.LinkedHashMap;
import org.junit.Test;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.RedisProtocol;

public class InvalidationMessageConsumerTest {

  @Test
  public void consumeInvalidationMessages() {
    LinkedHashMap<Long, Object> map = new LinkedHashMap<>();
    ClientSideCache clientSideCache = new MapClientSideCache(map);
    JedisPool pool = new JedisPool("localhost", 6379);
    HostAndPort hnp = HostAndPort.from("localhost:6379");
    JedisClientConfig config = DefaultJedisClientConfig.builder().protocol(RedisProtocol.RESP3).build();
    
    try (JedisPooled jedis = new JedisPooled(hnp, config, clientSideCache)) {
      for (int i = 0; i < 50; i++) {
        String field = "field" + i;
        String value = "value" + i;
        jedis.hset("mykey", field, value);
      }
    }

    JedisPooled jedis1 = new JedisPooled(hnp, config, clientSideCache);
    String value = jedis1.hget("mykey", "field1");

    JedisPooled jedis2 = new JedisPooled(hnp, config, clientSideCache);
    jedis2.hset("mykey", "field2", "value2");

    try {
      Thread.currentThread().sleep(15000);
      System.out.println("");
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
    }

  }
}
