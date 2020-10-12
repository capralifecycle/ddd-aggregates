@file:UseSerializers(OffsetDateTimeSerializer::class)

package no.liflig.dddaggregates

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import no.liflig.dddaggregates.entity.AggregateRoot
import no.liflig.dddaggregates.entity.EntityTimestamps
import no.liflig.dddaggregates.entity.UuidEntityId
import no.liflig.dddaggregates.entity.UuidEntityIdSerializer
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
class ExampleAggregate private constructor(
  override val id: ExampleId,
  val text: String,
  val moreText: String?,
  override val createdAt: OffsetDateTime,
  override val modifiedAt: OffsetDateTime
) : AggregateRoot(), EntityTimestamps {
  private fun update(
    text: String = this.text,
    moreText: String? = this.moreText,
    createdAt: OffsetDateTime = this.createdAt,
    modifiedAt: OffsetDateTime = OffsetDateTime.now()
  ): ExampleAggregate =
    ExampleAggregate(
      id = this.id,
      text = text,
      moreText = moreText,
      createdAt = createdAt,
      modifiedAt = modifiedAt
    )

  fun updateText(
    text: String = this.text,
    moreText: String? = this.moreText,
  ): ExampleAggregate =
    update(
      text = text,
      moreText = moreText
    )

  companion object {
    fun create(
      text: String,
      moreText: String? = null,
      now: OffsetDateTime = OffsetDateTime.now(),
      id: ExampleId = ExampleId()
    ): ExampleAggregate =
      ExampleAggregate(
        id = id,
        text = text,
        moreText = moreText,
        createdAt = now,
        modifiedAt = now
      )
  }
}

object ExampleIdSerializer : UuidEntityIdSerializer<ExampleId>({ ExampleId(it) })

@Serializable(with = ExampleIdSerializer::class)
data class ExampleId(
  override val id: UUID = UUID.randomUUID()
) : UuidEntityId {
  override fun toString(): String = id.toString()
}
