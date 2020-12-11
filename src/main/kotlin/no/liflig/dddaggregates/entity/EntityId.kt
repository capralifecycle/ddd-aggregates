package no.liflig.dddaggregates.entity

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
