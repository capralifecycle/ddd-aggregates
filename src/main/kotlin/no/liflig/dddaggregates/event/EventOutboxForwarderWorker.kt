package no.liflig.dddaggregates.event

import arrow.core.getOrHandle
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.kotlin.retry.decorateSuspendFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/**
 * Worker for reading events from the database and publishing them
 * on the external topic.
 *
 * To guarantee we forward events in the correct order we have chosen
 * to use an advisory lock so that only one worker can retrieve and
 * forwards events at the same time. Any number of workers can still
 * attempt this to handle, but will wait for their turn.
 *
 * To avoid waiting for a schedule before forwarding events,
 * the writer operation can call [triggerRecheck] to cause an
 * immediate forward of events.
 */
class EventOutboxForwarderWorker(
  private val jdbi: Jdbi,
  private val tableName: String,
  private val eventPublisher: EventPublisher,
  private val eventSerializer: EventSerializer,
  workerDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
  // Note: Not used for the worker itself, only for other methods.
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val scope = CoroutineScope(workerDispatcher)

  private var job: Job? = null
  private val trigger = Channel<Unit>(CONFLATED)
  private var shouldStopOnDrained: Boolean = false

  // Randomly selected number. Advisory locks are global for the database.
  private val advisoryLockId: Long = 40440267

  private suspend fun checkForItemsAndForward() {
    jdbi.open().use { handle ->
      // Lock the data so that we can ensure ordered output of events.
      // This is done session level to avoid transactions, so that we can
      // delete events one by one (autocommit) when being forwarded.
      lock(handle)

      try {
        while (true) {
          val items = getItemsToProcess(handle)
          if (items.isEmpty()) break

          for (item in items) {
            withContext(NonCancellable) {
              val event = eventSerializer.decodeFromString(item.data)
              logger.debug("Forwarding event ${event::class.simpleName} with ID ${event.eventId}")
              eventPublisher.publish(event).getOrHandle {
                throw it
              }
              deleteItem(handle, item.id)
            }
          }
        }
      } finally {
        unlock(handle)
      }
    }
  }

  private suspend fun checkAndHandle() = coroutineScope {
    // Schedule a trigger in the future.
    // Normally new events will be processed the moment they are added,
    // due to the call to triggerRecheck. We still want to check for events
    // outside that action, to handle any previous failures.
    val scheduledTrigger = launch {
      delay(10000)
      trigger.send(Unit)
    }

    // Wait for trigger.
    trigger.receive()

    // Cancel any schedule in case it was triggered outside schedule.
    scheduledTrigger.cancelAndJoin()

    checkForItemsAndForward()
  }

  private fun lock(handle: Handle) {
    handle.execute("SELECT pg_advisory_lock(?)", advisoryLockId)
  }

  private fun unlock(handle: Handle) {
    handle.execute("SELECT pg_advisory_unlock(?)", advisoryLockId)
  }

  private fun getItemsToProcess(handle: Handle): List<RowData> {
    return handle
      .createQuery("SELECT id, data FROM $tableName ORDER BY id LIMIT 50")
      .mapTo<RowData>()
      .list()
  }

  private fun deleteItem(handle: Handle, id: Long) {
    handle
      .createUpdate("DELETE FROM $tableName WHERE id = :id")
      .bind("id", id)
      .execute()
  }

  private data class RowData(val id: Long, val data: String)

  fun start() {
    check(job == null) {
      "Job already started"
    }

    job = scope.launch {
      logger.info("Running OutboxForwarderWorker")

      val retryConfig = RetryConfig.custom<Unit>()
        .maxAttempts(500)
        .ignoreExceptions(CancellationException::class.java)
        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(5_000))
        .build()

      while (isActive && !shouldStopOnDrained) {
        val retry = Retry.of("OutboxForwarderWorker", retryConfig)

        retry.eventPublisher.onRetry {
          val logFn: (format: String, arg: Any?) -> Unit =
            if (it.numberOfRetryAttempts > 5) logger::error else logger::warn

          logFn(
            "OutboxForwarderWorker failed at attempt ${it.numberOfRetryAttempts} - retrying in ${it.waitInterval}",
            it.lastThrowable,
          )
        }

        val retryFn = retry.decorateSuspendFunction { checkAndHandle() }

        try {
          retryFn()
        } catch (e: CancellationException) {
          logger.debug("Propagating cancellation")
          throw e
        } catch (e: Exception) {
          // Failures should be handled by Retry. This should only happen if
          // maxAttempts was reached.
          logger.error("Unexpected failure of OutboxForwarderWorker", e)
          delay(60_000)
        }
      }

      job = null
      shouldStopOnDrained = false
    }
  }

  suspend fun stop() {
    job?.let {
      logger.info("Stopping OutboxForwarderWorker")
      it.cancelAndJoin()
      job = null
      shouldStopOnDrained = false
    }
  }

  /**
   * Trigger an immediate check for events, instead of waiting for the next poll.
   */
  suspend fun triggerRecheck() {
    trigger.send(Unit)
  }

  /**
   * Process all events and wait for completion. To be used in tests.
   * This will stop the job.
   */
  suspend fun completeAllAndStop() {
    val job1 = job
    check(job1 != null) {
      "Job not running"
    }

    shouldStopOnDrained = true

    triggerRecheck()
    job1.join()
  }

  suspend fun countQueued(): Int = withContext(ioDispatcher) {
    jdbi.open().use { handle ->
      handle.createQuery("SELECT count(1) FROM $tableName").mapTo<Int>().first()
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(EventOutboxForwarderWorker::class.java)
  }
}
