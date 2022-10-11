package no.liflig.dddaggregates.event

import arrow.core.right
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.serialization.json.Json
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

@OptIn(ExperimentalCoroutinesApi::class)
object SqsEventWorkerSpec : Spek({
  describe("polling events from sqs") {
    it("should invoke event handler and delete message afterwards") {
      val eventSerializer = createTestEventSerializer()
      val testId = TestId(UUID.fromString("7f09d31b-7771-4763-9320-834c8765daf6"))
      val event = TestUpdatedEvent(
        UUID.fromString("bc016518-959a-4dd8-ae54-42460ea54bee"),
        Instant.parse("2021-08-31T16:56:14.603282Z"),
        testId,
        "hello world",
      )

      val dispatcher = TestCoroutineDispatcher()

      val message = Message
        .builder()
        .messageId("abc")
        .body(
          Json.encodeToString(
            SqsEventWorker.SnsMessage.serializer(),
            SqsEventWorker.SnsMessage(eventSerializer.encodeToString(event), "Notification"),
          ),
        )
        .receiptHandle("something")
        .build()

      val itemsToDrain = mutableListOf(message)

      val eventHandler = mockk<suspend (Event) -> EventHandlerResult> {
        coEvery { this@mockk(any()) } returns Unit.right()
      }

      val sqsClient = mockk<SqsAsyncClient> {
        // Return list of messages on first call.
        // Cancel on second call to terminate the job.
        every { receiveMessage(any<ReceiveMessageRequest>()) } answers {
          if (itemsToDrain.isEmpty()) {
            CompletableFuture.failedFuture(CancellationException())
          } else {
            val response = mockk<ReceiveMessageResponse> {
              val messages = itemsToDrain.toList()
              itemsToDrain.clear()
              every { messages() } answers { messages }
            }

            CompletableFuture.completedFuture(response)
          }
        }

        every { deleteMessage(any<DeleteMessageRequest>()) } returns CompletableFuture.completedFuture(mockk())
      }

      val sqsEventWorker = SqsEventWorker(sqsClient, "dummy", eventSerializer, eventHandler, dispatcher)

      sqsEventWorker.runBackground()

      dispatcher.advanceUntilIdle()

      coVerifyOrder {
        eventHandler(event)
        sqsClient.deleteMessage(any<DeleteMessageRequest>())
      }
    }
  }
},)
