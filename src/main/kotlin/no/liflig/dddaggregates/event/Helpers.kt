package no.liflig.dddaggregates.event

import arrow.core.Either
import arrow.core.computations.EitherEffect
import arrow.core.computations.either
import no.liflig.dddaggregates.repository.RepositoryDeviation

fun <T> Either<RepositoryDeviation, T>.orEhDeviation(): Either<EventHandlerDeviation, T> =
  mapLeft {
    when (it) {
      RepositoryDeviation.Conflict ->
        EventHandlerDeviation.Unavailable(RuntimeException("Conflict"))
      is RepositoryDeviation.Unavailable ->
        EventHandlerDeviation.Unavailable(RuntimeException("Repository unavailable", it.e))
      is RepositoryDeviation.Unknown ->
        EventHandlerDeviation.Unknown(RuntimeException("Unknown repository failure", it.e))
    }
  }

/**
 * Allows to bind() also on RepositoryDeviation and having it mapped.
 */
suspend fun <A> mapRepositoryDeviation(
  c: suspend EitherEffect<RepositoryDeviation, *>.() -> A
): Either<EventHandlerDeviation, A> =
  either<RepositoryDeviation, A> { c() }.orEhDeviation()
