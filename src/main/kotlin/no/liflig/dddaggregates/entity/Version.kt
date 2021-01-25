package no.liflig.dddaggregates.entity

import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import java.sql.Types

/**
 * A [Version] represents the version for an aggregate and is used
 * to implement optimistic locking.
 */
data class Version(
  val value: Long
) {
  fun next() = Version(value + 1)

  companion object {
    fun initial() = Version(1)
  }
}

data class VersionedAggregate<out T : AggregateRoot>(
  val item: T,
  val version: Version
)

/**
 * An argument factory for JDBI so that we can use a [Version] as a bind argument.
 */
class VersionArgumentFactory : AbstractArgumentFactory<Version>(Types.OTHER) {
  override fun build(value: Version, config: ConfigRegistry?): Argument =
    Argument { position, statement, _ ->
      statement.setObject(position, value.value)
    }
}
