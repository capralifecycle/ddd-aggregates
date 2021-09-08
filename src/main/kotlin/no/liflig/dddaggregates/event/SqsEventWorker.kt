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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.liflig.dddaggregates.withCoroutineMdcContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest

/**
 * Worker for processing domain events.
 */
class SqsEventWorker(
  private val sqsClient: SqsAsyncClient,
  private val sqsQueueUrl: String,
  private val eventSerializer: EventSerializer,
  private val eventHandler: suspend (Event) -> EventHandlerResult,
  defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  private val jsonForSns = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val scope = CoroutineScope(defaultDispatcher)

  private suspend fun delete(message: Message) {
    val request = DeleteMessageRequest.builder()
      .queueUrl(sqsQueueUrl)
      .receiptHandle(message.receiptHandle())
      .build()

    sqsClient.deleteMessage(request).await()
  }

  private suspend fun handle(message: Message) {
    logger.debug("Handling SQS message ID ${message.messageId()}")

    val body = message.body()

    val snsMessage = try {
      jsonForSns.decodeFromString(SnsMessage.serializer(), body)
    } catch (e: SerializationException) {
      throw RuntimeException(
        "Failed to deserialize SQS message as SnsMessage",
        RuntimeException("Body: $body", e),
      )
    }

    if (snsMessage.Type != "Notification") {
      logger.debug("Ignoring SNS message type: ${snsMessage.Type}")
      return
    }

    val event = try {
      // This uses implicit serialization since Event is an interface.
      // All concrete events must be marked with @Serializable.
      eventSerializer.decodeFromString(snsMessage.Message)
    } catch (e: SerializationException) {
      throw RuntimeException(
        "Failed to deserialize SNS message body as event",
        RuntimeException("SNS message body: ${snsMessage.Message}", e),
      )
    }

    withCoroutineMdcContext("eventId" to event.eventId.toString()) {
      logger.debug("Processing event of type ${event::class.simpleName}")

      withContext(NonCancellable) {
        eventHandler(event).getOrHandle {
          // We might want to reconsider how we handle failures.
          // This code causes the worker to log the failure as a warning, backoff and retry.
          throw it
        }
        delete(message)

        logger.debug("Event handling completed - message deleted")
      }
    }
  }

  private suspend fun checkAndHandle() {
    logger.debug("Long-polling for domain events")

    val request = ReceiveMessageRequest.builder()
      .queueUrl(sqsQueueUrl)
      .waitTimeSeconds(20)
      .maxNumberOfMessages(5)
      .build()

    val response = sqsClient.receiveMessage(request).await()
    val messages = response.messages()

    logger.debug("Retrieved ${messages.size} messages")
    messages.forEach {
      handle(it)
    }
  }

  fun runBackground() = scope.launch {
    logger.info("Running domain events worker for queue $sqsQueueUrl")

    val retryConfig = RetryConfig.custom<Unit>()
      .maxAttempts(500)
      .ignoreExceptions(CancellationException::class.java)
      .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(5_000))
      .build()

    while (isActive) {
      val retry = Retry.of("DomainEvents-worker", retryConfig)

      retry.eventPublisher.onRetry {
        val logFn: (format: String, arg: Any?) -> Unit =
          if (it.numberOfRetryAttempts > 5) logger::error else logger::warn

        logFn(
          "DomainEvents worker failed at attempt ${it.numberOfRetryAttempts} - retrying in ${it.waitInterval}",
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
        logger.error("Unexpected failure of DomainEvents worker", e)
        delay(60_000)
      }
    }
  }

  // https://docs.aws.amazon.com/sns/latest/dg/sns-message-and-json-formats.html
  @Serializable
  private data class SnsMessage(
    val Message: String,
    val Type: String,
  )

  companion object {
    private val logger = LoggerFactory.getLogger(SqsEventWorker::class.java)
  }
}
