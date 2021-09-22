package no.liflig.dddaggregates.entity

import no.liflig.dddaggregates.event.Event

/**
 * Result of a command for an aggregate.
 */
data class AResult<out A : AggregateRoot<*>, E : Event>(
  val aggregate: A,
  val events: List<E>,
)

class AResultBuilder<E : Event> {
  private val _events = mutableListOf<E>()

  val events: List<E> get() = _events

  fun publish(event: E) {
    _events.add(event)
  }

  fun publish(eventList: List<E>) {
    _events.addAll(eventList)
  }

  fun <A : AggregateRoot<*>> AResult<A, E>.bind(): A {
    publish(this.events)
    return aggregate
  }

  companion object {
    inline fun <A : AggregateRoot<*>, E : Event> buildResult(block: AResultBuilder<E>.() -> A): AResult<A, E> {
      val accumulator = AResultBuilder<E>()
      val result = accumulator.block()
      return AResult(result, accumulator.events)
    }
  }
}
