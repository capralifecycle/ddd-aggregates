package no.liflig.dddaggregates.entity

import java.time.Instant

interface EntityTimestamps {
  /**
   * Timestamp created.
   */
  val createdAt: Instant
  /**
   * Timestamp last modified, including the initial creation timestamp.
   */
  val modifiedAt: Instant
}
