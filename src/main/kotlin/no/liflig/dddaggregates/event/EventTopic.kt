package no.liflig.dddaggregates.event

import arrow.core.computations.either
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

interface EventTopic {
  fun subscribe(handler: EventHandler)
  fun subscribe(handler: suspend (event: Event) -> EventHandlerResult)
}

interface WritableEventTopic : EventTopic {
  suspend fun handleEvent(event: Event): EventHandlerResult
}

class WritableEventTopicImpl : WritableEventTopic {
  private val subscriptions = mutableListOf<suspend (event: Event) -> EventHandlerResult>()

  override fun subscribe(handler: EventHandler) {
    subscriptions.add(handler::handleEvent)
  }

  override fun subscribe(handler: suspend (event: Event) -> EventHandlerResult) {
    subscriptions.add(handler)
  }

  override suspend fun handleEvent(event: Event): EventHandlerResult = either {
    val start = System.currentTimeMillis()

    subscriptions.forEach { subscriber ->
      subscriber(event).bind()
    }

    val thisDuration = System.currentTimeMillis() - start

    // This assumes that clocks are in sync, so that we can compare timestamps across services.
    val totalDuration = Duration.between(event.eventTimestamp, Instant.now()).toMillis()

    logger.debug(
      "Event ${event::class.simpleName} with ID ${event.eventId} handled" +
        " in $thisDuration ms (total $totalDuration ms since creation)"
    )
  }

  companion object {
    private val logger = LoggerFactory.getLogger(WritableEventTopic::class.java)
  }
}

object NoopEventTopic : EventTopic {
  override fun subscribe(handler: EventHandler) = Unit
  override fun subscribe(handler: suspend (event: Event) -> EventHandlerResult) = Unit
}
