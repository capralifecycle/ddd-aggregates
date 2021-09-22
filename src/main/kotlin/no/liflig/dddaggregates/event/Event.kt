package no.liflig.dddaggregates.event

import java.time.Instant
import java.util.UUID

interface Event {
  val eventId: UUID
  val eventTimestamp: Instant

  /**
   * Group used to bucket FIFO property. This should typically consist of
   * name of the aggregate root and the identifier of the aggregate root.
   *
   * This field does not need to be persisted.
   */
  val eventGroupId: String
}
