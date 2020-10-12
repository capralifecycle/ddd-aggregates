package no.liflig.dddaggregates.entity

import arrow.core.Either
import arrow.core.getOrHandle
import arrow.core.left
import arrow.core.right
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import java.sql.Types

/**
 * Entity ID without known implementation.
 */
@Suppress("DataClassPrivateConstructor")
data class UnmappedEntityId private constructor(val value: String) {
  override fun toString() = value

  companion object {
    private val validPattern = Regex("[a-zA-Z0-9:\\-]+")

    fun fromString(value: String): Either<IllegalArgumentException, UnmappedEntityId> =
      try {
        // Some simple safeguards.
        check(value.length < 60)
        check(validPattern.matches(value))
        UnmappedEntityId(value).right()
      } catch (e: Exception) {
        when (e) {
          is IllegalArgumentException -> e
          else -> IllegalArgumentException("Mapping failed", e)
        }.left()
      }
  }
}

fun EntityId.toUnmapped(): UnmappedEntityId =
  UnmappedEntityId.fromString(toString()).getOrHandle {
    // We don't expect this to ever happen, so simply throw it
    // instead of forcing the caller to handle it.
    throw it
  }

/**
 * An argument factory for JDBI so that we can use a [UnmappedEntityId] as a bind argument.
 */
class UnmappedEntityIdArgumentFactory : AbstractArgumentFactory<UnmappedEntityId>(Types.OTHER) {
  override fun build(value: UnmappedEntityId, config: ConfigRegistry?): Argument =
    Argument { position, statement, _ ->
      statement.setObject(position, value.toString())
    }
}
