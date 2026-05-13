package redis.clients.jedis.commands;

import redis.clients.jedis.Response;

/**
 * Pipeline and transaction commands for the Redis <b>array</b> data type.
 */
public interface ArrayPipelineCommands {

  /**
   * <b><a href="https://redis.io/commands/arcount">ARCOUNT Command</a></b>
   * Returns the number of non-empty elements in an array.
   * <p>
   * Time complexity: O(1)
   * @param key the name of the key that holds the array
   * @return a {@link Response} that resolves to the number of non-empty elements,
   *         or {@code 0} if {@code key} does not exist
   * @since 8.0
   */
  Response<Long> arcount(String key);

}
