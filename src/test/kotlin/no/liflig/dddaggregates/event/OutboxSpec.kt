package no.liflig.dddaggregates.event

import arrow.core.right
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import no.liflig.dddaggregates.jdbiForTests
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.HandleCallback
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.time.Instant
import java.util.Collections
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
object OutboxSpec : Spek({
  val serializer by memoized { createTestEventSerializer() }

  describe("transactional outbox") {

    it("should forward events through worker") {
      val testDispatcher = TestCoroutineDispatcher()

      testDispatcher.runBlockingTest {
        val publishedEvents = mutableListOf<Event>()
        val testId = TestId()
        val testEvents = listOf(
          TestUpdatedEvent(UUID.randomUUID(), Instant.now(), testId, "hello world 1"),
          TestUpdatedEvent(UUID.randomUUID(), Instant.now(), testId, "hello world 2"),
          TestUpdatedEvent(UUID.randomUUID(), Instant.now(), testId, "hello world 3"),
        )

        val publisher = object : EventPublisher {
          override suspend fun publish(event: Event): EventPublishResult {
            publishedEvents.add(event)
            return Unit.right()
          }
        }

        val outboxForwarderWorker = EventOutboxForwarderWorker(
          jdbiForTests,
          OutboxTableName("event_outbox"),
          publisher,
          serializer,
          testDispatcher, // to advance time
          testDispatcher, // to advance time
        )

        outboxForwarderWorker.withDaemon {
          val outboxWriter = TransactionalOutboxWriter(
            OutboxTableName("event_outbox"),
            outboxForwarderWorker,
            serializer,
          )

          // Simulate an operation in a repository.
          jdbiForTests.open().use { handle ->
            handle.inTransactionUnchecked {
              outboxWriter.stage(handle, testEvents)
            }.map {
              it.onTransactionSuccess()
            }
          }

          outboxForwarderWorker.completeAllAndStop()
          outboxForwarderWorker.countQueued() shouldBe 0

          publishedEvents shouldContainExactly testEvents
        }
      }
    }

    it("multiple workers should not duplicate events") {
      // Cannot use TestCoroutineDispatcher for this test, as it
      // does not cause code to run in parallel.

      runBlocking {
        val publishedEvents = Collections.synchronizedList(mutableListOf<Event>())
        val testId = TestId()
        val testEvents = listOf(
          TestUpdatedEvent(UUID.randomUUID(), Instant.now(), testId, "hello world 1"),
          TestUpdatedEvent(UUID.randomUUID(), Instant.now(), testId, "hello world 2"),
          TestUpdatedEvent(UUID.randomUUID(), Instant.now(), testId, "hello world 3"),
        )

        val publisher = object : EventPublisher {
          override suspend fun publish(event: Event): EventPublishResult {
            publishedEvents.add(event)
            return Unit.right()
          }
        }

        val worker1 = EventOutboxForwarderWorker(jdbiForTests, OutboxTableName("event_outbox"), publisher, serializer)
        val worker2 = EventOutboxForwarderWorker(jdbiForTests, OutboxTableName("event_outbox"), publisher, serializer)
        val worker3 = EventOutboxForwarderWorker(jdbiForTests, OutboxTableName("event_outbox"), publisher, serializer)

        val outboxWriter = TransactionalOutboxWriter(
          OutboxTableName("event_outbox"),
          NoopEventOutboxForwarder(),
          serializer,
        )

        worker1.withDaemon {
          worker2.withDaemon {
            worker3.withDaemon {
              // Simulate an operation in a repository.
              jdbiForTests.open().use { handle ->
                handle.inTransactionUnchecked {
                  outboxWriter.stage(handle, testEvents)
                }.map {
                  it.onTransactionSuccess()
                }
              }

              coroutineScope {
                launch(IO) { worker1.completeAllAndStop() }
                launch(IO) { worker2.completeAllAndStop() }
                launch(IO) { worker3.completeAllAndStop() }
              }
            }
          }
        }

        worker1.countQueued() shouldBe 0
        publishedEvents shouldContainExactly testEvents
      }
    }
  }
})

private suspend fun <T> EventOutboxForwarderWorker.withDaemon(block: suspend () -> T): T {
  start()
  try {
    return block()
  } finally {
    stop()
  }
}

private inline fun <R> Handle.inTransactionUnchecked(crossinline block: (Handle) -> R): R {
  return inTransaction(
    HandleCallback<R, RuntimeException> { handle ->
      block(handle)
    }
  )
}
