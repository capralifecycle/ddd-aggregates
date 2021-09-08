package no.liflig.dddaggregates.repository

import arrow.core.computations.either
import arrow.core.left
import arrow.core.right
import no.liflig.dddaggregates.entity.AggregateRoot
import no.liflig.dddaggregates.entity.EntityId
import no.liflig.dddaggregates.entity.Version
import no.liflig.dddaggregates.entity.VersionedAggregate
import no.liflig.dddaggregates.event.Event
import no.liflig.dddaggregates.event.EventPublisher
import no.liflig.dddaggregates.event.asRepositoryDeviation

/**
 * Memory implementation for a repository.
 *
 * To be used in transient environments such as tests.
 *
 * Note that this does not preserve transactional handling of events,
 * which is OK since this is never used in a real environment.
 */
abstract class MemoryCrudRepository<I : EntityId, A : AggregateRoot<I>, E : Event>(
  private val eventPublisher: EventPublisher,
) : CrudRepository<I, A, E> {
  // Public so it can be read and modified directly in tests.
  val items = mutableMapOf<EntityId, VersionedAggregate<A>>()

  override fun fromJson(value: String): A {
    TODO("Not yet implemented")
  }

  override fun toJson(aggregate: A): String {
    TODO("Not yet implemented")
  }

  protected open fun getByIdList(ids: List<I>): Response<List<VersionedAggregate<A>>> =
    items.filterKeys { it in ids }.values.toList().right()

  protected open fun get(id: I): Response<VersionedAggregate<A>?> =
    getByIdList(listOf(id)).map { it.firstOrNull() }

  override suspend fun <A2 : A> create(aggregate: A2): Response<VersionedAggregate<A2>> =
    if (aggregate.id in items) RepositoryDeviation.Conflict.left()
    else VersionedAggregate(aggregate, Version.initial()).also {
      items[aggregate.id] = it
    }.right()

  override suspend fun <A2 : A> create(
    aggregate: A2,
    events: List<E>,
  ): Response<VersionedAggregate<A2>> = either {
    create(aggregate).bind().also {
      eventPublisher.publishAll(events).asRepositoryDeviation().bind()
    }
  }

  override suspend fun <A2 : A> update(aggregate: A2, previousVersion: Version): Response<VersionedAggregate<A2>> =
    if (items[aggregate.id]?.version != previousVersion)
      RepositoryDeviation.Conflict.left()
    else
      VersionedAggregate(aggregate, previousVersion.next()).also {
        items[aggregate.id] = it
      }.right()

  override suspend fun <A2 : A> update(
    aggregate: A2,
    events: List<E>,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>> = either {
    update(aggregate, previousVersion).bind().also {
      eventPublisher.publishAll(events).asRepositoryDeviation().bind()
    }
  }

  override suspend fun delete(id: I, previousVersion: Version): Response<Unit> =
    if (items[id]?.version != previousVersion) RepositoryDeviation.Conflict.left()
    else {
      items.remove(id)
      Unit.right()
    }

  override suspend fun delete(
    id: I,
    events: List<E>,
    previousVersion: Version,
  ): Response<Unit> = either {
    delete(id, previousVersion).also {
      eventPublisher.publishAll(events).asRepositoryDeviation().bind()
    }
  }
}
