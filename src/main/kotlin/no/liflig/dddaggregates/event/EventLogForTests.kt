package no.liflig.dddaggregates.event

import arrow.core.right

class EventLogForTests(eventTopic: EventTopic) {
  private val eventLog = mutableListOf<Event>()

  val events: List<Event>
    get() = eventLog

  init {
    eventTopic.subscribe { event ->
      eventLog.add(event)
      Unit.right()
    }
  }
}
