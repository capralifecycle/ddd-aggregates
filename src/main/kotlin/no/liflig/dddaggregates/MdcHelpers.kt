package no.liflig.dddaggregates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

/**
 * Run a block with an extended MDC context and return data.
 */
internal inline fun <T> withMdcContext(extendedContext: Map<String, String>, block: () -> T): T =
  runUsingContext(block) {
    extendedContext.forEach { (key, value) ->
      MDC.put(key, value)
    }
  }

/**
 * Run a block with an extended MDC context and return data.
 *
 * To be used inside a coroutine so that the context will properly propagate
 * within coroutines.
 */
internal suspend fun <T> withCoroutineMdcContext(
  vararg extendedContext: Pair<String, String>,
  block: suspend CoroutineScope.() -> T,
): T =
  withMdcContext(extendedContext.toMap()) {
    withContext(MDCContext()) {
      block()
    }
  }

/**
 * Run a block by manipulating the MDC context before running.
 */
@PublishedApi
internal inline fun <T> runUsingContext(
  block: () -> T,
  contextSetter: () -> Unit,
): T {
  val existingState = MDC.getCopyOfContextMap()
  try {
    contextSetter()
    return block()
  } finally {
    setMdcState(existingState)
  }
}

@PublishedApi
internal fun setMdcState(state: Map<String, String>?) {
  if (state == null) {
    MDC.clear()
  } else {
    MDC.setContextMap(state)
  }
}
