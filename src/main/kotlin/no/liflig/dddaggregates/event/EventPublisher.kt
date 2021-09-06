package no.liflig.dddaggregates.event

import arrow.core.Either
import arrow.core.computations.either
import arrow.core.right
import kotlinx.coroutines.future.await
import no.liflig.dddaggregates.repository.RepositoryDeviation
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishRequest

interface EventPublisher {
  suspend fun publish(event: Event): EventPublishResult

  suspend fun publishAll(events: List<Event>): EventPublishResult = either {
    for (event in events) {
      publish(event).bind()
    }
  }
}

typealias EventPublishResult = Either<EventPublishDeviation, Unit>

sealed class EventPublishDeviation : RuntimeException() {
  data class Unavailable(override val cause: Throwable?) : EventPublishDeviation()
  data class Unknown(override val cause: Throwable?) : EventPublishDeviation()
}

fun <T> Either<EventPublishDeviation, T>.asRepositoryDeviation(): Either<RepositoryDeviation, T> {
  return mapLeft {
    when (it) {
      is EventPublishDeviation.Unavailable -> RepositoryDeviation.Unavailable(it)
      is EventPublishDeviation.Unknown -> RepositoryDeviation.Unknown(it)
    }
  }
}

class SnsEventPublisher(
  private val snsClient: SnsAsyncClient,
  private val topicArn: String,
  private val eventSerializer: EventSerializer,
) : EventPublisher {
  override suspend fun publish(event: Event): EventPublishResult {
    val serializedEvent = eventSerializer.encodeToString(event)

    return handleAwsExceptions {
      logger.info("Publishing event ${event::class.simpleName} with ID ${event.eventId} to SNS")
      logger.debug("Data of event with ID ${event.eventId}: $serializedEvent")
      snsClient.publish(
        PublishRequest.builder()
          .message(serializedEvent)
          .messageGroupId(event.eventGroupId)
          .messageDeduplicationId(event.eventId.toString())
          .topicArn(topicArn)
          .build()
      ).await()
    }.map { result ->
      val messageId = result.messageId()
      logger.info(
        "Event ${event::class.simpleName} with ID ${event.eventId} published to SNS having message ID $messageId"
      )
      Unit.right()
    }
  }

  private inline fun <T> handleAwsExceptions(block: () -> T): Either<EventPublishDeviation, T> =
    Either.catch { block() }.mapLeft {
      when (it) {
        is SdkClientException -> EventPublishDeviation.Unavailable(it)
        is SdkServiceException -> EventPublishDeviation.Unknown(it)
        else -> EventPublishDeviation.Unknown(it)
      }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(SnsEventPublisher::class.java)
  }
}

class LocalIsolatedEventPublisher(private val eventTopic: WritableEventTopic) : EventPublisher {
  override suspend fun publish(event: Event): EventPublishResult {
    // Do not propagate errors, since normally events are handled asynchronous by
    // a separate worker process.
    // We try-catch to catch all situations (in case not only left side is returned).
    try {
      logger.debug("Sending event ${event::class.simpleName} with ID ${event.eventId} to local event topic")
      eventTopic.handleEvent(event).mapLeft {
        throw it
      }
    } catch (e: Throwable) {
      logger.error("Event processing failed for ${event::class.simpleName} with ID ${event.eventId}", e)
    }
    return Unit.right()
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LocalIsolatedEventPublisher::class.java)
  }
}

object NoopEventPublisher : EventPublisher {
  override suspend fun publish(event: Event): EventPublishResult = Unit.right()
}
