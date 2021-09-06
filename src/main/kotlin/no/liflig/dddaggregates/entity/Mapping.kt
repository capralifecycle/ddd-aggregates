package no.liflig.dddaggregates.entity

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import java.util.UUID

typealias StringMapper<T> = (String) -> Either<IllegalArgumentException, T>

/**
 * Evaluate the block and map an [IllegalArgumentException] into left side of [Either].
 */
inline fun <T> catchArgument(block: () -> T): Either<IllegalArgumentException, T> =
  try {
    block().right()
  } catch (e: IllegalArgumentException) {
    e.left()
  }

/**
 * Wrap a function so any [IllegalArgumentException] thrown will be returned into left side of [Either].
 */
inline fun <T, R> handleIllegalArgument(crossinline block: (T) -> R): (T) -> Either<IllegalArgumentException, R> =
  {
    catchArgument {
      block(it)
    }
  }

/**
 * Parse a [String] into [UUID] with error handling.
 */
fun parseUuid(value: String): Either<IllegalArgumentException, UUID> =
  catchArgument {
    UUID.fromString(value)
  }

/**
 * Create a mapper function to convert a [String] holding an [UUID] into a known [T].
 *
 * [IllegalArgumentException] in the conversion will be handled.
 */
fun <T> createUuidMapper(factory: (UUID) -> T): StringMapper<T> =
  {
    parseUuid(it).flatMap(handleIllegalArgument(factory))
  }

/**
 * Create a pair representing the mapping of a specific [T] from an [UUID] stored
 * in a [String] by using the provided [factory] function.
 *
 * [IllegalArgumentException] in the conversion will be handled.
 */
@JvmName("createMapperPairForUuid")
inline fun <reified T> createMapperPair(
  noinline factory: (UUID) -> T
): Pair<Class<T>, StringMapper<T>> =
  T::class.java to createUuidMapper(factory)

/**
 * Create a pair representing the mapping of a specific [T] from a [String]
 * by using the provided [factory] function.
 *
 * [IllegalArgumentException] in the conversion will be handled.
 */
@JvmName("createMapperPairForString")
inline fun <reified T> createMapperPair(
  noinline factory: (String) -> T
): Pair<Class<T>, StringMapper<T>> =
  T::class.java to handleIllegalArgument(factory)

/**
 * Create a pair representing the mapping of a specific [T] from a [String]
 * by using the provided [factory] function.
 */
@JvmName("createMapperPairForStringMapper")
inline fun <reified T> createMapperPair(
  noinline factory: StringMapper<T>
): Pair<Class<T>, StringMapper<T>> =
  T::class.java to factory

/**
 * Modify a list so an item is replaced if it exists or added at the end if
 * it is not known before.
 */
fun <I, T> List<T>.replaceOrAdd(
  item: T,
  extractId: (T) -> I,
): List<T> {
  val id = extractId(item)
  return when {
    // Replace.
    any { extractId(it) == id } -> map {
      if (extractId(it) == id) item
      else it
    }
    // Add.
    else -> plus(item)
  }
}

/**
 * Update an existing entity in a list. Verifies the entity exists.
 */
inline fun <reified T : Entity> List<T>.updateEntity(entity: T, transform: (T) -> T): List<T> {
  check(any { it == entity }) {
    "${T::class.simpleName} with ID ${entity.id} was not found"
  }
  return map { item ->
    if (item == entity) transform(item)
    else item
  }
}

/**
 * Remove an existing entity from the list. Verifies the entity did exist.
 */
inline fun <reified T : Entity> List<T>.deleteExistingEntity(entity: T): List<T> {
  check(any { it == entity }) {
    "${T::class.simpleName} with ID ${entity.id} was not found"
  }
  return mapNotNull { item ->
    if (item == entity) null
    else item
  }
}

/**
 * Add entity to a list. Verifies the entity did not exist before.
 */
inline fun <reified T : Entity> List<T>.addEntityAtEnd(entity: T): List<T> {
  check(none { it.id == entity.id }) {
    "${T::class.simpleName} with ID ${entity.id} was already present"
  }
  return this + entity
}

/**
 * Update a list that should contain a specific element.
 *
 * Prefer using [updateEntity] if [T] is an [Entity].
 */
fun <T> List<T>.updateItem(item: T, transform: (T) -> T): List<T> {
  val matchedItems = filter { it == item }
  check(matchedItems.size <= 1) {
    // This might happen when comparing by value (e.g. two similar value objects).
    "Multiple items (${matchedItems.size}) found matching the object to update"
  }
  check(matchedItems.isNotEmpty()) {
    "Item to update not found"
  }

  return map {
    if (it == item) transform(it)
    else it
  }
}

/**
 * Transform a list, and if the resulting list contains multiple elements for
 * the same group, apply the merge method to merge to one element.
 *
 * Typically used for functionality that modifies references so that two
 * elements end up pointing to the same reference.
 */
fun <T, K> List<T>.mapAndMerge(transform: (T) -> T, groupBy: (T) -> K, mergeMultiple: (List<T>) -> T): List<T> {
  return map(transform).groupBy(groupBy).values.map {
    if (it.size == 1) it.first()
    else mergeMultiple(it)
  }
}
