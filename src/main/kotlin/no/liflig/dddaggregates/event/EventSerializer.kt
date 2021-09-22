package no.liflig.dddaggregates.event

interface EventSerializer {
  fun encodeToString(value: Event): String
  fun decodeFromString(value: String): Event
}
