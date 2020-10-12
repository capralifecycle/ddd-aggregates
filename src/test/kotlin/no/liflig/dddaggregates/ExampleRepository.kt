package no.liflig.dddaggregates

import no.liflig.dddaggregates.repository.AbstractCrudRepository
import org.jdbi.v3.core.Jdbi

class ExampleRepository(
  jdbi: Jdbi
) : AbstractCrudRepository<ExampleId, ExampleAggregate>(
  jdbi,
  "example",
  ExampleAggregate.serializer()
)
