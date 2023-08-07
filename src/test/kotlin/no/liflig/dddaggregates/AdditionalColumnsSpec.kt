package no.liflig.dddaggregates

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import no.liflig.dddaggregates.event.Event
import no.liflig.dddaggregates.event.NoopEventOutboxWriter
import no.liflig.dddaggregates.repository.AbstractCrudRepository
import no.liflig.dddaggregates.repository.AdditionalColumn
import no.liflig.dddaggregates.repository.unsafe
import org.jdbi.v3.core.Jdbi
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

private const val sqlTableName = "additional_columns_example"

class AdditionalColumnsSpec : Spek({
  fun ExampleId.getData(): List<Map<String, Any>> {
    return jdbiForTests.open().use { handle ->
      handle.select("SELECT * FROM $sqlTableName WHERE id = ?::uuid", this.toString()).mapToMap().list()
    }
  }

  describe("a repository with additional columns") {
    describe("upon creating the aggregate") {
      it("can write to one column") {
        // GIVEN
        val repo = AdditionalColumns1Repository(jdbiForTests)
        val agg = ExampleAggregate.create("hello world", moreText = "more1")

        // WHEN
        runBlocking { repo.create(agg).unsafe() }

        // THEN
        val data = agg.id.getData().first()
        data["extra1"] shouldBe "more1"
        data["extra2"].shouldBeNull()
      }

      it("can write to multiple columns") {
        // GIVEN
        val repo = AdditionalColumns2Repository(jdbiForTests)
        val agg = ExampleAggregate.create("hello world", moreText = "more1")

        // WHEN
        runBlocking { repo.create(agg).unsafe() }

        // THEN
        val data = agg.id.getData().first()
        data["extra1"] shouldBe "more1"
        data["extra2"] shouldBe "hello world"
      }
    }

    describe("upon updating an existing aggregate") {
      it("can write to one column") {
        // GIVEN
        val repo = AdditionalColumns1Repository(jdbiForTests)
        val agg = ExampleAggregate.create("hello world", moreText = "more1")
        val (_, version) = runBlocking { repo.create(agg).unsafe() }

        // WHEN
        val aggUpdated = agg.updateText(text = "hello world 2", moreText = "more2")
        runBlocking { repo.update(aggUpdated, version).unsafe() }

        // THEN
        val data = agg.id.getData().first()
        data["extra1"] shouldBe "more2"
        data["extra2"].shouldBeNull()
      }

      it("can write to multiple columns") {
        // GIVEN
        val repo = AdditionalColumns2Repository(jdbiForTests)
        val agg = ExampleAggregate.create("hello world", moreText = "more1")
        val (_, version) = runBlocking { repo.create(agg).unsafe() }

        // WHEN
        val aggUpdated = agg.updateText(text = "hello world 2", moreText = "more2")
        runBlocking { repo.update(aggUpdated, version).unsafe() }

        // THEN
        val data = agg.id.getData().first()
        data["extra1"] shouldBe "more2"
        data["extra2"] shouldBe "hello world 2"
      }
    }
  }
})

private class AdditionalColumns1Repository(
  jdbi: Jdbi,
) : AbstractCrudRepository<ExampleId, ExampleAggregate, Event>(
  jdbi,
  sqlTableName,
  ExampleAggregate.serializer(),
  NoopEventOutboxWriter,
) {
  override val additionalColumns: List<AdditionalColumn<ExampleAggregate>> =
    listOf(
      AdditionalColumn("extra1", ":extra1") { agg ->
        bind("extra1", agg.moreText)
      },
    )
}

private class AdditionalColumns2Repository(
  jdbi: Jdbi,
) : AbstractCrudRepository<ExampleId, ExampleAggregate, Event>(
  jdbi,
  sqlTableName,
  ExampleAggregate.serializer(),
  NoopEventOutboxWriter,
) {
  override val additionalColumns: List<AdditionalColumn<ExampleAggregate>> =
    listOf(
      AdditionalColumn("extra1", ":extra1") { agg ->
        bind("extra1", agg.moreText)
      },
      AdditionalColumn("extra2", ":extra2") { agg ->
        bind("extra2", agg.text)
      },
    )
}
