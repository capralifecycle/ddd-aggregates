package no.liflig.dddaggregates.event

import arrow.core.Either
import arrow.core.computations.either
import arrow.core.right
import no.liflig.dddaggregates.repository.RepositoryDeviation
import no.liflig.dddaggregates.repository.mapExceptionsToResponse
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory

/**
 * Publishing events as part of a database transaction.
 */
interface EventOutboxWriter {
  fun stage(handle: Handle, events: List<Event>): Either<RepositoryDeviation, OutboxStagedResult>
}

interface OutboxStagedResult {
  /**
   * Hook that should be called when the transaction that covers the
   * events has completed.
   *
   * Used for the special outbox implementations that only runs in memory,
   * and as such must defer handling of the events until the updates are visible
   * (transaction commited).
   */
  suspend fun onTransactionSuccess(): EventPublishResult
}

/**
 * Non-transactional implementation of [EventOutboxWriter] to be
 * used in tests without a database instance.
 *
 * This does not write to a intermediate storage (such as a database table)
 * but instead forward the event directly to the event publisher.
 */
class LocalEventOutboxWriter(
  private val eventPublisher: EventPublisher,
) : EventOutboxWriter {
  override fun stage(handle: Handle, events: List<Event>): Either<RepositoryDeviation, OutboxStagedResult> {
    // Defer handling events until transaction completed,
    // or else the handling would see old version of the data.
    return object : OutboxStagedResult {
      override suspend fun onTransactionSuccess(): EventPublishResult = either {
        for (event in events) {
          logger.debug("Forwarding event ${event::class.simpleName} with ID ${event.eventId}")
          eventPublisher.publish(event).mapLeft {
            logger.error("Forwarding event ${event::class.simpleName} with ID ${event.eventId} failed", it)
            it
          }.bind()
        }
      }
    }.right()
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LocalEventOutboxWriter::class.java)
  }
}

/**
 * Transactional implementation of [EventOutboxWriter] to be used
 * with the real database.
 *
 * When the transaction has completed, we will signal the worker
 * that processes the outbox table, to speed up forwarding of the
 * events.
 */
class TransactionalOutboxWriter(
  private val tableName: String,
  private val eventOutboxForwarderWorker: EventOutboxForwarderWorker,
  private val eventSerializer: EventSerializer,
) : EventOutboxWriter {
  override fun stage(handle: Handle, events: List<Event>): Either<RepositoryDeviation, OutboxStagedResult> {
    return mapExceptionsToResponse {
      for (event in events) {
        handle
          .createUpdate("INSERT INTO $tableName (data) VALUES (:data::jsonb)")
          .bind("data", eventSerializer.encodeToString(event))
          .execute()
      }
      Unit.right()
    }.map {
      object : OutboxStagedResult {
        override suspend fun onTransactionSuccess(): EventPublishResult = either {
          eventOutboxForwarderWorker.triggerRecheck()
        }
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(TransactionalOutboxWriter::class.java)
  }
}

object NoopEventOutboxWriter : EventOutboxWriter {
  override fun stage(handle: Handle, events: List<Event>): Either<RepositoryDeviation, OutboxStagedResult> {
    return object : OutboxStagedResult {
      override suspend fun onTransactionSuccess(): EventPublishResult {
        return Unit.right()
      }
    }.right()
  }
}
