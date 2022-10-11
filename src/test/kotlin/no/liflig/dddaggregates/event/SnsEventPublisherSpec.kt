// ktlint-disable max-line-length
package no.liflig.dddaggregates.event

import arrow.core.getOrHandle
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.coVerifyAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

object SnsEventPublisherSpec : Spek({
  val eventSerializer = createTestEventSerializer()
  val testId = TestId(UUID.fromString("7f09d31b-7771-4763-9320-834c8765daf6"))
  val event = TestUpdatedEvent(
    UUID.fromString("bc016518-959a-4dd8-ae54-42460ea54bee"),
    Instant.parse("2021-08-31T16:56:14.603282Z"),
    testId,
    "hello world",
  )

  describe("an sns event publisher") {
    it("should publish as expected") {
      // GIVEN

      val response = mockk<PublishResponse> {
        every { messageId() } returns "message-id"
      }
      val slot = slot<PublishRequest>()
      val snsClient = mockk<SnsAsyncClient> {
        every { publish(capture(slot)) } returns CompletableFuture.completedFuture(response)
      }

      val eventPublisher = SnsEventPublisher(snsClient, "topic-arn", eventSerializer)

      // WHEN

      runBlocking {
        eventPublisher.publish(event).getOrHandle { throw it }
      }

      // THEN

      coVerifyAll {
        snsClient.publish(any<PublishRequest>())
      }

      slot.captured.toString() shouldBe """PublishRequest(TopicArn=topic-arn, Message={"type":"TestUpdatedEvent","eventId":"bc016518-959a-4dd8-ae54-42460ea54bee","eventTimestamp":"2021-08-31T16:56:14.603282Z","testId":"7f09d31b-7771-4763-9320-834c8765daf6","updatedName":"hello world"}, MessageDeduplicationId=bc016518-959a-4dd8-ae54-42460ea54bee, MessageGroupId=Test:7f09d31b-7771-4763-9320-834c8765daf6)"""
    }

    it("should handle aws client exception as unavailable") {
      // GIVEN

      val snsClient = mockk<SnsAsyncClient> {
        every { publish(any<PublishRequest>()) } throws ApiCallTimeoutException.create(123)
      }
      val eventPublisher = SnsEventPublisher(snsClient, "topic-arn", eventSerializer)

      // WHEN

      val result = runBlocking {
        eventPublisher.publish(event)
      }

      // THEN

      result.shouldBeLeft().shouldBeTypeOf<EventPublishDeviation.Unavailable>()

      coVerifyAll {
        snsClient.publish(any<PublishRequest>())
      }
    }
  }
},)
