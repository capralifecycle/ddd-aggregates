package no.liflig.dddaggregates.event

import arrow.core.Either

interface EventHandler {
  suspend fun handleEvent(event: Event): EventHandlerResult
}

typealias EventHandlerResult = Either<EventHandlerDeviation, Unit>

sealed class EventHandlerDeviation : RuntimeException() {
  data class Unavailable(override val cause: Throwable?) : EventHandlerDeviation()
  data class Unknown(override val cause: Throwable?) : EventHandlerDeviation()
}
