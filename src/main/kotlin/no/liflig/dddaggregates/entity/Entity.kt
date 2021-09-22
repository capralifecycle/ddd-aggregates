package no.liflig.dddaggregates.entity

interface Entity<I : EntityId> {
  val id: I
}

interface AggregateRoot<I : EntityId> : Entity<I>

/**
 * Base class for an Entity.
 *
 * Two Entities are considered equal if they have the same type and ID.
 */
abstract class AbstractEntity<I : EntityId> : Entity<I> {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as AbstractEntity<*>

    if (id != other.id) return false

    return true
  }

  override fun hashCode(): Int = id.hashCode()
}

/**
 * Base class for the root Entity of an Aggregate.
 */
abstract class AbstractAggregateRoot<I : EntityId> : AbstractEntity<I>(), AggregateRoot<I>
