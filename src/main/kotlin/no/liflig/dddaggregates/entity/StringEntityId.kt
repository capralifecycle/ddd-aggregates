package no.liflig.dddaggregates.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

interface StringEntityId : EntityId {
  val id: String
}

abstract class StringEntityIdSerializer<T : StringEntityId>(
  val factory: (String) -> T,
) : KSerializer<T> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("StringEntityId", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: T) =
    encoder.encodeString(value.id)

  override fun deserialize(decoder: Decoder): T =
    factory(decoder.decodeString())
}
