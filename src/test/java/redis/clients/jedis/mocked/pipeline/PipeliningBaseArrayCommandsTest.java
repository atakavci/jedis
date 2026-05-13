package redis.clients.jedis.mocked.pipeline;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Response;

public class PipeliningBaseArrayCommandsTest extends PipeliningBaseMockedTestBase {

  @Test
  public void testArcount() {
    when(commandObjects.arcount("key")).thenReturn(longCommandObject);

    Response<Long> response = pipeliningBase.arcount("key");

    assertThat(commands, contains(longCommandObject));
    assertThat(response, is(predefinedResponse));
  }

  @Test
  public void testArcountBinary() {
    byte[] key = "key".getBytes();

    when(commandObjects.arcount(key)).thenReturn(longCommandObject);

    Response<Long> response = pipeliningBase.arcount(key);

    assertThat(commands, contains(longCommandObject));
    assertThat(response, is(predefinedResponse));
  }
}
