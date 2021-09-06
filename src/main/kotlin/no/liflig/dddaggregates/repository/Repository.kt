package no.liflig.dddaggregates.repository

import arrow.core.Either
import arrow.core.getOrHandle
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import no.liflig.dddaggregates.entity.AggregateRoot
import no.liflig.dddaggregates.entity.EntityId
import no.liflig.dddaggregates.entity.Version
import no.liflig.dddaggregates.entity.VersionedAggregate
import org.jdbi.v3.core.CloseException
import org.jdbi.v3.core.ConnectionException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinMapper
import org.jdbi.v3.core.mapper.RowMapper
import org.jdbi.v3.core.statement.Query
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
interface CrudRepository<I : EntityId, A : AggregateRoot> : Repository {
  fun toJson(aggregate: A): String

  fun fromJson(value: String): A

  suspend fun <A2 : A> create(aggregate: A2): Response<VersionedAggregate<A2>>

  suspend fun getByIdList(ids: List<I>): Response<List<VersionedAggregate<A>>>

  suspend fun get(id: I): Response<VersionedAggregate<A>?> =
    getByIdList(listOf(id)).map { it.firstOrNull() }

  suspend fun <A2 : A> update(aggregate: A2, previousVersion: Version): Response<VersionedAggregate<A2>>

  suspend fun delete(id: I, previousVersion: Version): Response<Unit>
}

/**
 * An abstract Repository to hold common logic we share.
 */
abstract class AbstractCrudRepository<I, A>(
  protected val jdbi: Jdbi,
  protected val sqlTableName: String,
  protected val serializer: KSerializer<A>,
) : CrudRepository<I, A>
  where I : EntityId,
        A : AggregateRoot {

  /**
   * The JSON instance used to serialize/deserialize.
   */
  open val json: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  override fun toJson(aggregate: A): String = json.encodeToString(serializer, aggregate)
  override fun fromJson(value: String): A = json.decodeFromString(serializer, value)

  protected open val rowMapper = createRowMapper(createRowParser(::fromJson))

  /**
   * Extension point to extend the coroutine context used when switching
   * dispatcher, e.g. to add MDC context.
   */
  protected open val coroutineContext: CoroutineContext = EmptyCoroutineContext

  protected open suspend fun getByPredicate(
    sqlWhere: String = "TRUE",
    bind: Query.() -> Query = { this }
  ): Response<List<VersionedAggregate<A>>> = mapExceptionsToResponse {
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

  override suspend fun getByIdList(
    ids: List<I>
  ): Response<List<VersionedAggregate<A>>> =
    getByPredicate("id = ANY (:ids)") {
      bindArray("ids", EntityId::class.java, ids)
    }

  override suspend fun delete(
    id: I,
    previousVersion: Version
  ): Response<Unit> = mapExceptionsToResponse {
    withContext(Dispatchers.IO + coroutineContext) {
      jdbi.open().use { handle ->
        val deleted = handle
          .createUpdate(
            """
            DELETE FROM "$sqlTableName"
            WHERE id = :id AND version = :previousVersion
            """.trimIndent()
          )
          .bind("id", id)
          .bind("previousVersion", previousVersion)
          .execute()

        if (deleted == 0) RepositoryDeviation.Conflict.left()
        else Unit.right()
      }
    }
  }

  /**
   * Default implementation for create. Note that some repositories might need to
   * implement its own version if there are special columns that needs to be
   * kept in sync e.g. for indexing purposes.
   */
  override suspend fun <A2 : A> create(
    aggregate: A2
  ): Response<VersionedAggregate<A2>> = mapExceptionsToResponse {
    withContext(Dispatchers.IO + coroutineContext) {
      VersionedAggregate(aggregate, Version.initial()).also {
        jdbi.open().use { handle ->
          val now = Instant.now()
          handle
            .createUpdate(
              """
              INSERT INTO "$sqlTableName" (id, version, data, modified_at, created_at)
              VALUES (:id, :version, :data::jsonb, :modifiedAt, :createdAt)
              """.trimIndent()
            )
            .bind("id", aggregate.id)
            .bind("version", it.version)
            .bind("data", toJson(aggregate))
            .bind("modifiedAt", now)
            .bind("createdAt", now)
            .execute()
        }
      }
    }.right()
  }

  /**
   * Default implementation for update. Note that some repositories might need to
   * implement its own version if there are special columns that needs to be
   * kept in sync e.g. for indexing purposes.
   */
  override suspend fun <A2 : A> update(
    aggregate: A2,
    previousVersion: Version
  ): Response<VersionedAggregate<A2>> = mapExceptionsToResponse {
    withContext(Dispatchers.IO + coroutineContext) {
      jdbi.open().use { handle ->
        val result = VersionedAggregate(aggregate, previousVersion.next())
        val updated =
          handle
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

        if (updated == 0) RepositoryDeviation.Conflict.left()
        else result.right()
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
