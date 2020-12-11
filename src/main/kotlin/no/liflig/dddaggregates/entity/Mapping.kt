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
