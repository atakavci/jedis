package redis.clients.jedis.mocked.unified;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

public class UnifiedJedisArrayCommandsTest extends UnifiedJedisMockedTestBase {

  @Test
  public void testArcount() {
    String key = "array-key";
    long expectedCount = 7L;

    when(commandObjects.arcount(key)).thenReturn(longCommandObject);
    when(commandExecutor.executeCommand(longCommandObject)).thenReturn(expectedCount);

    long result = jedis.arcount(key);

    assertThat(result, equalTo(expectedCount));

    verify(commandExecutor).executeCommand(longCommandObject);
    verify(commandObjects).arcount(key);
  }

  @Test
  public void testArcountBinary() {
    byte[] key = "array-key".getBytes();
    long expectedCount = 7L;

    when(commandObjects.arcount(key)).thenReturn(longCommandObject);
    when(commandExecutor.executeCommand(longCommandObject)).thenReturn(expectedCount);

    long result = jedis.arcount(key);

    assertThat(result, equalTo(expectedCount));

    verify(commandExecutor).executeCommand(longCommandObject);
    verify(commandObjects).arcount(key);
  }
}
