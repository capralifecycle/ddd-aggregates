package no.liflig.dddaggregates.repository

import no.liflig.dddaggregates.entity.AResult
import no.liflig.dddaggregates.entity.AggregateRoot
import no.liflig.dddaggregates.entity.EntityId
import no.liflig.dddaggregates.entity.Version
import no.liflig.dddaggregates.entity.VersionedAggregate
import no.liflig.dddaggregates.event.Event

interface EventedCrudRepository<I : EntityId, A : AggregateRoot, E : Event> : CrudRepository<I, A> {
  /**
   * Create an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> create(
    aggregate: A2,
    events: List<E>,
  ): Response<VersionedAggregate<A2>>

  /**
   * Create an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> create(
    result: AResult<A2, E>,
  ): Response<VersionedAggregate<A2>> =
    create(result.aggregate, result.events)

  /**
   * Update an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> update(
    aggregate: A2,
    events: List<E>,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>>

  /**
   * Update an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> update(
    result: AResult<A2, E>,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>> =
    update(result.aggregate, result.events, previousVersion)
}
