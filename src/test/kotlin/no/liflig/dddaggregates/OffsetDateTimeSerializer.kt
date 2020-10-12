package no.liflig.dddaggregates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.OffsetDateTime

internal object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
    serialName = "OffsetDateTimeSerializer",
    kind = PrimitiveKind.STRING
  )

  override fun serialize(encoder: Encoder, value: OffsetDateTime) =
    encoder.encodeString(value.toString())

  override fun deserialize(decoder: Decoder): OffsetDateTime =
    OffsetDateTime.parse(decoder.decodeString())
}
