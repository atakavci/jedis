package redis.clients.jedis.commands.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;

import io.redis.test.annotations.SinceRedisVersion;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.util.SafeEncoder;

@SinceRedisVersion("8.7.225")
@Tag("integration")
public abstract class ArrayCommandsTestBase extends UnifiedJedisCommandsTestBase {

  public ArrayCommandsTestBase(RedisProtocol protocol) {
    super(protocol);
  }

  @Test
  public void arcountMissingKey() {
    long count = jedis.arcount("missing-array");
    assertEquals(0, count);
  }

  @Test
  public void arcountMissingKeyBinary() {
    byte[] bKey = SafeEncoder.encode("missing-array");
    long count = jedis.arcount(bKey);
    assertEquals(0, count);
  }
}
