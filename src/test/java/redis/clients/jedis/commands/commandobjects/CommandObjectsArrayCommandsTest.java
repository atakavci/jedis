package redis.clients.jedis.commands.commandobjects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.redis.test.annotations.SinceRedisVersion;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisProtocol;

/**
 * Tests related to <a href="https://redis.io/commands/?group=array">Array</a> commands.
 */
@SinceRedisVersion("8.7.225")
public class CommandObjectsArrayCommandsTest extends CommandObjectsStandaloneTestBase {

  public CommandObjectsArrayCommandsTest(RedisProtocol protocol) {
    super(protocol);
  }

  @Test
  public void testArcountMissingKey() {
    Long count = exec(commandObjects.arcount("nonexistent-array"));
    assertThat(count, equalTo(0L));
  }

  @Test
  public void testArcountMissingKeyBinary() {
    Long count = exec(commandObjects.arcount("nonexistent-array".getBytes()));
    assertThat(count, equalTo(0L));
  }
}
