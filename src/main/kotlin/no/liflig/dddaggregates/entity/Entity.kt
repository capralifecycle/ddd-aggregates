package no.liflig.dddaggregates.entity

/**
 * Base class for an Entity.
 *
 * Two Entities are considered equal if they have the same type and ID.
 */
abstract class Entity {
  abstract val id: EntityId

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as Entity

    if (id != other.id) return false

    return true
  }

  override fun hashCode(): Int = id.hashCode()
}

/**
 * Base class for the root Entity of an Aggregate.
 */
abstract class AggregateRoot : Entity()
