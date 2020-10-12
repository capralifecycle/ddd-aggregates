package no.liflig.dddaggregates.entity

import java.time.OffsetDateTime

interface EntityTimestamps {
  /**
   * Timestamp created.
   */
  val createdAt: OffsetDateTime
  /**
   * Timestamp last modified, including the initial creation timestamp.
   */
  val modifiedAt: OffsetDateTime
}
