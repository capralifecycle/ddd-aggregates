@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package no.liflig.dddaggregates.event

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import no.liflig.dddaggregates.InstantSerializer
import no.liflig.dddaggregates.entity.UuidEntityId
import no.liflig.dddaggregates.entity.UuidEntityIdSerializer
import java.time.Instant
import java.util.UUID

private object UUIDSerializer : KSerializer<UUID> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("UUIDSerializer", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: UUID): Unit =
    encoder.encodeString(value.toString())

  override fun deserialize(decoder: Decoder): UUID {
    return UUID.fromString(decoder.decodeString())
  }
}

@Serializable(with = TestIdSerializer::class)
data class TestId(
  override val id: UUID = UUID.randomUUID()
) : UuidEntityId {
  override fun toString(): String = id.toString()
}

object TestIdSerializer : UuidEntityIdSerializer<TestId>(::TestId)

@Serializable
sealed class TestEvent : Event {
  abstract val testId: TestId
  override val eventGroupId: String get() = "Test:$testId"
}

@Serializable
@SerialName("TestUpdatedEvent")
data class TestUpdatedEvent(
  override val eventId: UUID,
  override val eventTimestamp: Instant,
  override val testId: TestId,
  val updatedName: String
) : TestEvent()

fun createTestEventSerializer() = object : EventSerializer {
  private val json = Json {
    serializersModule = SerializersModule {
      polymorphic(Event::class) {
        subclass(TestUpdatedEvent::class)
      }
    }
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  override fun encodeToString(value: Event): String {
    return json.encodeToString(json.serializersModule.serializer(), value)
  }

  override fun decodeFromString(value: String): Event {
    return json.decodeFromString(json.serializersModule.serializer(), value)
  }
}
