package no.liflig.dddaggregates.event

/**
 * Name of the database table used as outbox for events.
 *
 * This table must be created by the application, and should use the following migration
 * using event_outbox as example name of the table:
 *
 * ```sql
 * CREATE TABLE event_outbox (
 *   id bigserial PRIMARY KEY,
 *   data jsonb NOT NULL
 * );
 * ```
 */
@JvmInline
value class OutboxTableName(val value: String)
