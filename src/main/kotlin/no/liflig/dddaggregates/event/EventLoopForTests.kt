package no.liflig.dddaggregates.event

import arrow.core.right

/**
 * Topic and publisher to be used with tests.
 *
 * This variant propagates errors, so that handlers that are executed during
 * tests are also checked. Code that runs in a live environment should run the
 * handlers in isolation.
 *
 * This also includes dummy serialization/deserialization of events, so that
 * we verify that can work.
 */
class EventLoopForTests(private val eventSerializer: EventSerializer) : EventTopic, EventPublisher {
  @Suppress("RedundantSuspendModifier")
  suspend fun waitForEmpty() {
    // Currently a noop but will change when/if we launch jobs concurrently.
  }

  private val topic = WritableEventTopicImpl()

  private val eventLog = EventLogForTests(topic)

  val events: List<Event>
    get() = eventLog.events

  override suspend fun publish(event: Event): EventPublishResult {
    // Verify serialization works on this event.
    eventSerializer.decodeFromString(eventSerializer.encodeToString(event))

    // Call any subscribers.
    try {
      topic.handleEvent(event).mapLeft {
        throw it
      }
    } catch (e: Throwable) {
      throw RuntimeException("Event processing failed for ${event::class.simpleName}", e)
    }

    return Unit.right()
  }

  override fun subscribe(handler: EventHandler) {
    topic.subscribe(handler)
  }

  override fun subscribe(handler: suspend (event: Event) -> EventHandlerResult) {
    topic.subscribe(handler)
  }
}
