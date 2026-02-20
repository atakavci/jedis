package redis.clients.jedis.mcf;

import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.decorators.Decorators.DecorateSupplier;

import redis.clients.jedis.CommandObject;
import redis.clients.jedis.Connection;
import redis.clients.jedis.annots.Experimental;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.executors.CommandExecutor;
import redis.clients.jedis.mcf.MultiDbConnectionProvider.Database;

/**
 * @author Allen Terleto (aterleto)
 *         <p>
 *         CommandExecutor with built-in retry, circuit-breaker, and failover to another database
 *         endpoint. With this executor users can seamlessly failover to Disaster Recovery (DR),
 *         Backup, and Active-Active cluster(s) by using simple configuration which is passed
 *         through from Resilience4j - https://resilience4j.readme.io/docs
 *         <p>
 */
@Experimental
public class MultiDbCommandExecutor extends MultiDbFailoverBase implements CommandExecutor {

  public MultiDbCommandExecutor(MultiDbConnectionProvider provider) {
    super(provider);
  }

  @Override
  public <T> T executeCommand(CommandObject<T> commandObject) {
    Database database = provider.getDatabase(); // Pass this by reference for thread safety

    DecorateSupplier<T> supplier = Decorators
        .ofSupplier(() -> this.handleExecuteCommand(commandObject, database));

    supplier.withCircuitBreaker(database.getCircuitBreaker());
    supplier.withRetry(database.getRetry());
    supplier.withFallback(provider.getFallbackExceptionList(),
      e -> this.handleDatabaseFailover(commandObject, database));
    try {
      return supplier.decorate().get();
    } catch (Exception e) {
      if (database.getCircuitBreaker().getState() == State.OPEN && isActiveDatabase(database)) {
        databaseFailover(database);
      }
      throw e;
    }
  }

  /**
   * Functional interface wrapped in retry and circuit breaker logic to handle happy path scenarios
   */
  private <T> T handleExecuteCommand(CommandObject<T> commandObject, Database database) {
    Connection connection = acquireConnection(database);
    Exception initialException = null;
    try {
      return connection.executeCommand(commandObject);
    } catch (Exception e) {
      initialException = e;
      if (isFailDuringFailover(e, database)) {
        initialException = new ConnectionFailoverException(
            "Command failed during failover: " + database.getCircuitBreaker().getName(), e);
      }
      // IMPORTANT:
      // throwing 'e' below does not hold any value since closeAndSuppress() will capture and
      // rethrow the 'initialException' in favor of suppressing any potential exception from
      // connection.close()
      // This act of suppressing exception is due to the change in
      // org.apache.commons.pool2.impl.GenericObjectPool#invalidateObject() at version 2.13.1.
      // This version attempts to replace the invalidated instance,
      // and fails when ConnectionFactory#makeObject() fails.
      throw e;
    } finally {
      closeAndSuppress(connection, initialException);
    }
  }

  private Connection acquireConnection(Database database) {
    try {
      return database.getConnection();
    } catch (JedisConnectionException e) {
      provider.assertOperability();
      throw e;
    }
  }

  private boolean isFailDuringFailover(Exception e, Database database) {
    return database.retryOnFailover() && !isActiveDatabase(database)
        && isCircuitBreakerTrackedException(e, database);
  }

  private void closeAndSuppress(Connection connection, Exception initialException) {
    try {
      connection.close();
    } catch (Exception e) {
      if (initialException != null) {
        initialException.addSuppressed(e);
      } else {
        throw e;
      }
    }
    if (initialException instanceof RuntimeException) throw (RuntimeException) initialException;
    if (initialException != null) throw new JedisException(initialException);
  }

  /**
   * Functional interface wrapped in retry and circuit breaker logic to handle open circuit breaker
   * failure scenarios
   */
  private <T> T handleDatabaseFailover(CommandObject<T> commandObject, Database database) {

    databaseFailover(database);

    // Recursive call to the initiating method so the operation can be retried on the next database
    // connection
    return executeCommand(commandObject);
  }

}