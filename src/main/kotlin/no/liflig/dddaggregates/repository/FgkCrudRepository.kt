package no.liflig.dddaggregates.repository

import arrow.core.Either
import arrow.core.computations.either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import no.liflig.dddaggregates.entity.AggregateRoot
import no.liflig.dddaggregates.entity.EntityId
import no.liflig.dddaggregates.entity.Version
import no.liflig.dddaggregates.entity.VersionedAggregate
import no.liflig.dddaggregates.event.Event
import no.liflig.dddaggregates.event.EventOutboxWriter
import no.liflig.dddaggregates.event.OutboxStagedResult
import no.liflig.dddaggregates.event.asRepositoryDeviation
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.HandleCallback
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.Query
import org.slf4j.Logger
import java.time.Instant
import kotlin.coroutines.CoroutineContext

abstract class FgkCrudRepository<I, A, E>(
  jdbi: Jdbi,
  sqlTableName: String,
  serializer: KSerializer<A>,
  protected val logger: Logger,
  private val eventOutboxWriter: EventOutboxWriter,
) :
  AbstractCrudRepository<I, A>(jdbi, sqlTableName, serializer),
  EventedCrudRepository<I, A, E>
  where I : EntityId,
        A : AggregateRoot,
        E : Event {

  // Keep MDC context.
  override val coroutineContext: CoroutineContext
    get() = MDCContext()

  protected suspend fun getByQuery(
    queryName: String,
    sqlQuery: String,
    bind: Query.() -> Query = { this },
  ): Response<List<VersionedAggregate<A>>> = logDuration(queryName) {
    mapExceptionsToResponse {
      withContext(Dispatchers.IO) {
        jdbi.open().use { handle ->
          handle
            .select(sqlQuery.trimIndent())
            .bind()
            .map(rowMapper)
            .list()
        }
      }.right()
    }
  }

  // Extend with logging.
  override suspend fun getByPredicate(
    sqlWhere: String,
    bind: Query.() -> Query,
  ): Response<List<VersionedAggregate<A>>> =
    logDuration("getByPredicate ($sqlWhere)") {
      super.getByPredicate(sqlWhere, bind)
    }

  protected inline fun <T> logDuration(
    info: String,
    block: () -> Response<List<T>>,
  ): Response<List<T>> {
    val start = System.nanoTime()
    val result = block()
    val durationMs = (System.nanoTime() - start) / 1000000.0

    val details = when (result) {
      is Either.Left -> "errored"
      is Either.Right -> "${result.value.size} items"
    }

    logger.info("query took $durationMs ms ($details): $info")
    return result
  }

  private fun <A2 : A> Handle.executeCreate(aggregate: A2): Response<VersionedAggregate<A2>> {
    val result = VersionedAggregate(aggregate, Version.initial())
    val now = Instant.now()

    this
      .createUpdate(
        """
        INSERT INTO "$sqlTableName" (id, version, data, modified_at, created_at)
        VALUES (:id, :version, :data::jsonb, :modifiedAt, :createdAt)
        """.trimIndent()
      )
      .bind("id", aggregate.id)
      .bind("version", result.version)
      .bind("data", toJson(aggregate))
      .bind("modifiedAt", now)
      .bind("createdAt", now)
      .execute()

    return result.right()
  }

  // Overrides default impl to add transactional events support.
  override suspend fun <A2 : A> create(aggregate: A2): Response<VersionedAggregate<A2>> =
    create(aggregate, emptyList())

  override suspend fun <A2 : A> create(
    aggregate: A2,
    events: List<E>,
  ): Response<VersionedAggregate<A2>> = mapExceptionsToResponse {
    withContext(Dispatchers.IO + coroutineContext) {
      jdbi.open().use { handle ->
        handle.inTransactionUnchecked {
          either.eager<RepositoryDeviation, Pair<VersionedAggregate<A2>, OutboxStagedResult>> {
            val result = handle.executeCreate(aggregate).bind()
            val stagedEvents = eventOutboxWriter.stage(handle, events).bind()
            result to stagedEvents
          }
        }.flatMap { (result, stagedEvents) ->
          either {
            stagedEvents.onTransactionSuccess().asRepositoryDeviation().bind()
            result
          }
        }
      }
    }
  }

  private fun <A2 : A> Handle.executeUpdate(
    aggregate: A2,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>> {
    val result = VersionedAggregate(aggregate, previousVersion.next())

    val updated = this
      .createUpdate(
        """
        UPDATE "$sqlTableName"
        SET
          version = :nextVersion,
          data = :data::jsonb,
          modified_at = :modifiedAt
        WHERE
          id = :id AND
          version = :previousVersion
        """.trimIndent()
      )
      .bind("nextVersion", result.version)
      .bind("data", toJson(aggregate))
      .bind("id", aggregate.id)
      .bind("modifiedAt", Instant.now())
      .bind("previousVersion", previousVersion)
      .execute()

    return if (updated == 0) RepositoryDeviation.Conflict.left()
    else result.right()
  }

  // Overrides default impl to add transactional events support.
  override suspend fun <A2 : A> update(
    aggregate: A2,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>> = update(aggregate, emptyList(), previousVersion)

  override suspend fun <A2 : A> update(
    aggregate: A2,
    events: List<E>,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>> = mapExceptionsToResponse {
    withContext(Dispatchers.IO + coroutineContext) {
      jdbi.open().use { handle ->
        handle.inTransactionUnchecked {
          either.eager<RepositoryDeviation, Pair<VersionedAggregate<A2>, OutboxStagedResult>> {
            val result = handle.executeUpdate(aggregate, previousVersion).bind()
            val stagedEvents = eventOutboxWriter.stage(handle, events).bind()
            result to stagedEvents
          }
        }.flatMap { (result, stagedEvents) ->
          either {
            stagedEvents.onTransactionSuccess().asRepositoryDeviation().bind()
            result
          }
        }
      }
    }
  }
}

private inline fun <R> Handle.inTransactionUnchecked(crossinline block: (Handle) -> R): R {
  return inTransaction(
    HandleCallback<R, RuntimeException> { handle ->
      block(handle)
    }
  )
}
