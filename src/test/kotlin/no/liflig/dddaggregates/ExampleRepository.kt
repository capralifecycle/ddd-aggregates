package no.liflig.dddaggregates

import no.liflig.dddaggregates.event.Event
import no.liflig.dddaggregates.event.NoopEventOutboxWriter
import no.liflig.dddaggregates.repository.AbstractCrudRepository
import org.jdbi.v3.core.Jdbi

// This example does not cover events for the example.

class ExampleRepository(
  jdbi: Jdbi
) : AbstractCrudRepository<ExampleId, ExampleAggregate, Event>(
  jdbi,
  "example",
  ExampleAggregate.serializer(),
  NoopEventOutboxWriter
) {
  public override suspend fun getByIdList(ids: List<ExampleId>) = super.getByIdList(ids)
  public override suspend fun get(id: ExampleId) = super.get(id)
}
