package no.liflig.dddaggregates.entity

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import java.sql.Types
import java.util.UUID
import kotlin.reflect.KFunction1

/**
 * EntityId represents the ID pointing to a specific [Entity].
 */
interface EntityId

/**
 * UUID-version of an EntityId.
 */
interface UuidEntityId : EntityId {
  val id: UUID
}

typealias StringMapper<T> = (String) -> Either<IllegalArgumentException, T>

/**
 * Create a mapper function to convert a [String] holding an UUID into a known [T].
 */
fun <T> createUuidMapper(factory: KFunction1<UUID, T>): StringMapper<T> =
  { it: String ->
    try {
      factory(UUID.fromString(it)).right()
    } catch (e: IllegalArgumentException) {
      e.left()
    }
  }

/**
 * Create a pair representing the mapping of a specific [T] from an UUID stored
 * in a [String] by using the provided [factory] function.
 */
inline fun <reified T> createMapperPair(
  factory: KFunction1<UUID, T>
): Pair<Class<T>, StringMapper<T>> =
  T::class.java to createUuidMapper(factory)

/**
 * Create a pair representing the mapping of a specific [T] from a [String]
 * by using the provided [factory] function.
 */
inline fun <reified T> createMapperPair(
  noinline factory: StringMapper<T>
): Pair<Class<T>, StringMapper<T>> =
  T::class.java to factory

/**
 * Abstract class to simplify creating serializers for [UuidEntityId] implementations.
 */
abstract class UuidEntityIdSerializer<T : UuidEntityId>(
  val factory: (UUID) -> T
) : KSerializer<T> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("UuidEntityId", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: T) =
    encoder.encodeString(value.id.toString())

  override fun deserialize(decoder: Decoder): T =
    factory(UUID.fromString(decoder.decodeString()))
}

/**
 * An argument factory for JDBI so that we can use a [UuidEntityId] as a bind argument.
 */
class UuidEntityIdArgumentFactory : AbstractArgumentFactory<UuidEntityId>(Types.OTHER) {
  override fun build(value: UuidEntityId, config: ConfigRegistry?): Argument =
    Argument { position, statement, _ ->
      statement.setObject(position, value.id)
    }
}
