package no.liflig.dddaggregates.repository

import arrow.core.Either
import arrow.core.computations.either
import arrow.core.flatMap
import arrow.core.getOrHandle
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import no.liflig.dddaggregates.entity.AResult
import no.liflig.dddaggregates.entity.AggregateRoot
import no.liflig.dddaggregates.entity.EntityId
import no.liflig.dddaggregates.entity.Version
import no.liflig.dddaggregates.entity.VersionedAggregate
import no.liflig.dddaggregates.event.Event
import no.liflig.dddaggregates.event.EventOutboxWriter
import no.liflig.dddaggregates.event.OutboxStagedResult
import no.liflig.dddaggregates.event.asRepositoryDeviation
import org.jdbi.v3.core.CloseException
import org.jdbi.v3.core.ConnectionException
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.HandleCallback
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinMapper
import org.jdbi.v3.core.mapper.RowMapper
import org.jdbi.v3.core.statement.Query
import org.jdbi.v3.core.statement.Update
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InterruptedIOException
import java.sql.SQLTransientException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

typealias Response<T> = Either<RepositoryDeviation, T>

/**
 * A Repository holds the logic for persistence of an [AggregateRoot],
 * including lookup methods.
 *
 * The [Repository] owns how an [AggregateRoot] is serialized, and it is
 * free to make optimizations so that later lookups will be less costly,
 * e.g. by putting some fields from the [AggregateRoot] into specific columns
 * in the persisted storage, which can then be indexed and used for lookup
 * by other [Repository] methods.
 *
 * We want all our methods in the [Repository] to return a [Response] type.
 *
 * For this to work properly, all methods should be wrapped using the
 * [mapExceptionsToResponse] method, which takes care of the exceptions
 * and converts them into proper [Either] types. Note we have no way
 * of consistently guarantee this, so it is left for the developer to
 * remember it.
 */
interface Repository

/**
 * A base for a CRUD-like Repository.
 *
 * Note that this does not mean we cannot have more methods, just that we expect
 * these methods for managing persistence of an aggregate in a consistent way.
 */
interface CrudRepository<I : EntityId, A : AggregateRoot, E : Event> : Repository {
  fun toJson(aggregate: A): String

  fun fromJson(value: String): A

  /**
   * Create an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> create(
    aggregate: A2,
    events: List<E>,
  ): Response<VersionedAggregate<A2>>

  /**
   * Create an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> create(
    result: AResult<A2, E>,
  ): Response<VersionedAggregate<A2>> =
    create(result.aggregate, result.events)

  suspend fun <A2 : A> create(aggregate: A2): Response<VersionedAggregate<A2>> =
    create(aggregate, emptyList())

  suspend fun getByIdList(ids: List<I>): Response<List<VersionedAggregate<A>>>

  suspend fun get(id: I): Response<VersionedAggregate<A>?> =
    getByIdList(listOf(id)).map { it.firstOrNull() }

  /**
   * Update an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> update(
    aggregate: A2,
    events: List<E>,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>>

  /**
   * Update an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> update(
    result: AResult<A2, E>,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>> =
    update(result.aggregate, result.events, previousVersion)

  suspend fun <A2 : A> update(aggregate: A2, previousVersion: Version): Response<VersionedAggregate<A2>> =
    update(aggregate, emptyList(), previousVersion)

  /**
   * Delete an aggregate while also transactionally storing the events.
   */
  suspend fun delete(
    id: I,
    events: List<E>,
    previousVersion: Version,
  ): Response<Unit>

  /**
   * Update an aggregate while also transactionally storing the events.
   */
  suspend fun <A2 : A> delete(result: AResult<A2, E>, previousVersion: Version): Response<Unit> =
    @Suppress("UNCHECKED_CAST")
    delete(result.aggregate.id as I, result.events, previousVersion)

  suspend fun delete(id: I, previousVersion: Version): Response<Unit> =
    delete(id, emptyList(), previousVersion)
}

/**
 * An abstract Repository to hold common logic we share.
 */
abstract class AbstractCrudRepository<I, A, E>(
  protected val jdbi: Jdbi,
  protected val sqlTableName: String,
  protected val serializer: KSerializer<A>,
  private val eventOutboxWriter: EventOutboxWriter,
) : CrudRepository<I, A, E>
  where I : EntityId,
        A : AggregateRoot,
        E : Event {

  /**
   * The JSON instance used to serialize/deserialize.
   */
  open val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  open val logger: Logger = LoggerFactory.getLogger(AbstractCrudRepository::class.java)

  /**
   * Additional columns written during create/update.
   */
  protected open val additionalColumns: List<AdditionalColumn<A>> = emptyList()

  override fun toJson(aggregate: A): String = json.encodeToString(serializer, aggregate)
  override fun fromJson(value: String): A = json.decodeFromString(serializer, value)

  protected open val rowMapper = createRowMapper(createRowParser(::fromJson))

  /**
   * Extension point to extend the coroutine context used when switching
   * dispatcher, e.g. to add MDC context.
   */
  protected open val coroutineContext: CoroutineContext = EmptyCoroutineContext

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

    logger.debug("query took $durationMs ms ($details): $info")
    return result
  }

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

  protected open suspend fun getByPredicate(
    sqlWhere: String = "TRUE",
    bind: Query.() -> Query = { this }
  ): Response<List<VersionedAggregate<A>>> = logDuration("getByPredicate ($sqlWhere)") {
    mapExceptionsToResponse {
      withContext(Dispatchers.IO + coroutineContext) {
        jdbi.open().use { handle ->
          handle
            .select(
              """
              SELECT id, data, version, created_at, modified_at
              FROM "$sqlTableName"
              WHERE ($sqlWhere)
              ORDER BY created_at
              """.trimIndent()
            )
            .bind()
            .map(rowMapper)
            .list()
        }
      }.right()
    }
  }

  override suspend fun getByIdList(
    ids: List<I>
  ): Response<List<VersionedAggregate<A>>> =
    getByPredicate("id = ANY (:ids)") {
      bindArray("ids", EntityId::class.java, ids)
    }

  private fun Update.bindAdditionalColumns(aggregate: A): Update {
    return additionalColumns.fold(this) { acc, cur ->
      cur.bind(acc, aggregate)
    }
  }

  private fun <A2 : A> Handle.executeCreate(aggregate: A2): Response<VersionedAggregate<A2>> {
    val result = VersionedAggregate(aggregate, Version.initial())
    val now = Instant.now()

    val columns = listOf("id", "created_at", "modified_at", "version", "data") +
      additionalColumns.map { it.columnName }

    val values = listOf(":id", ":createdAt", ":modifiedAt", ":version", ":data::jsonb") +
      additionalColumns.map { it.sqlValue }

    this
      .createUpdate(
        """
        INSERT INTO "$sqlTableName" (${columns.joinToString()})
        VALUES (${values.joinToString()})
        """.trimIndent()
      )
      .bind("id", aggregate.id)
      .bind("createdAt", now)
      .bind("modifiedAt", now)
      .bind("version", result.version)
      .bind("data", toJson(aggregate))
      .bindAdditionalColumns(aggregate)
      .execute()

    return result.right()
  }

  private fun <A2 : A> Handle.executeUpdate(
    aggregate: A2,
    previousVersion: Version,
  ): Response<VersionedAggregate<A2>> {
    val result = VersionedAggregate(aggregate, previousVersion.next())

    val columns = listOf("modified_at", "version", "data") + additionalColumns.map { it.columnName }
    val values = listOf(":modifiedAt", ":nextVersion", ":data::jsonb") + additionalColumns.map { it.sqlValue }

    val updated = this
      .createUpdate(
        """
        UPDATE "$sqlTableName"
        SET
          ${columns.zip(values).joinToString { (column, value) -> "$column = $value" }}
        WHERE
          id = :id AND
          version = :previousVersion
        """.trimIndent()
      )
      .bind("modifiedAt", Instant.now())
      .bind("nextVersion", result.version)
      .bind("data", toJson(aggregate))
      .bind("id", aggregate.id)
      .bind("previousVersion", previousVersion)
      .bindAdditionalColumns(aggregate)
      .execute()

    return if (updated == 0) RepositoryDeviation.Conflict.left()
    else result.right()
  }

  private fun Handle.executeDelete(
    id: I,
    previousVersion: Version,
  ): Response<Unit> {
    val deleted = this
      .createUpdate(
        """
        DELETE FROM "$sqlTableName"
        WHERE id = :id AND version = :previousVersion
        """.trimIndent()
      )
      .bind("id", id)
      .bind("previousVersion", previousVersion)
      .execute()

    return if (deleted == 0) RepositoryDeviation.Conflict.left()
    else Unit.right()
  }

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

  override suspend fun delete(
    id: I,
    events: List<E>,
    previousVersion: Version
  ): Response<Unit> = mapExceptionsToResponse {
    withContext(Dispatchers.IO + coroutineContext) {
      jdbi.open().use { handle ->
        handle.inTransactionUnchecked {
          either.eager<RepositoryDeviation, OutboxStagedResult> {
            handle.executeDelete(id, previousVersion).bind()
            eventOutboxWriter.stage(handle, events).bind()
          }
        }.flatMap { stagedEvents ->
          stagedEvents.onTransactionSuccess().asRepositoryDeviation()
        }
      }
    }
  }
}

/**
 * A data class that represents fields for a database row that holds an aggregate instance.
 *
 * Note that the table might include more fields - this is only to read _out_ the aggregate.
 */
data class AggregateRow(
  val id: UUID,
  val data: String,
  val version: Long
)

fun <A : AggregateRoot> createRowMapper(
  fromRow: (row: AggregateRow) -> VersionedAggregate<A>
): RowMapper<VersionedAggregate<A>> {
  val kotlinMapper = KotlinMapper(AggregateRow::class.java)

  return RowMapper { rs, ctx ->
    val simpleRow = kotlinMapper.map(rs, ctx) as AggregateRow
    fromRow(simpleRow)
  }
}

fun <A : AggregateRoot> createRowParser(
  fromJson: (String) -> A
): (row: AggregateRow) -> VersionedAggregate<A> {
  return { row ->
    VersionedAggregate(
      fromJson(row.data),
      Version(row.version)
    )
  }
}

/**
 * A deviation for an operation on a Repository.
 */
sealed class RepositoryDeviation {
  /**
   * A version conflict occurred.
   */
  object Conflict : RepositoryDeviation()

  /**
   * The repository is currently unavailable, e.g. due to the
   * database being unreachable.
   */
  data class Unavailable(val e: Exception) : RepositoryDeviation()

  /**
   * Some unknown error occurred.
   */
  class Unknown(val e: Exception) : RepositoryDeviation()
}

fun RepositoryDeviation.toException(): Exception =
  when (this) {
    RepositoryDeviation.Conflict -> IllegalArgumentException("Version conflict")
    is RepositoryDeviation.Unavailable -> IllegalStateException("Repository currently unavailable")
    is RepositoryDeviation.Unknown -> RuntimeException("Unknown error: ${e.message}", e)
  }

/**
 * Unwrap the result or throw ugly if we have a deviation.
 */
@Deprecated("Use unsafe() instead.", ReplaceWith("unsafe()", "no.liflig.dddaggregates.repository.unsafe"))
fun <T> Either<RepositoryDeviation, T>.leftThrowUnhandled(): T = unsafe()

/**
 * Unwrap the result or throw ugly if we have a deviation.
 */
fun <T> Either<RepositoryDeviation, T>.unsafe(): T =
  getOrHandle {
    throw it.toException()
  }

inline fun <T> mapExceptionsToResponse(block: () -> Response<T>): Response<T> =
  try {
    block()
  } catch (e: Exception) {
    when (e) {
      is SQLTransientException,
      is InterruptedIOException,
      is ConnectionException,
      is CloseException ->
        RepositoryDeviation.Unavailable(e).left()
      else -> RepositoryDeviation.Unknown(e).left()
    }
  }

private inline fun <R> Handle.inTransactionUnchecked(crossinline block: (Handle) -> R): R {
  return inTransaction(
    HandleCallback<R, RuntimeException> { handle ->
      block(handle)
    }
  )
}

/**
 * An additional column to be persisted with an aggregate. This can be used
 * to create lookup fields for queries that can be indexed separately.
 *
 * The value in the additional column will be derived from the aggregate.
 */
class AdditionalColumn<A : AggregateRoot>(
  val columnName: String,
  val sqlValue: String,
  val bind: Update.(aggregate: A) -> Update,
)
