# ddd-aggregates

Kotlin library holding some concepts around DDD Aggregates
for reuse in Liflig.

* Basic structure for Entity, Entity IDs, Aggregates and versions
* CRUD-like Repository with optimistic locking using JDBI,
  Kotlinx Serialization, Arrow and Kotlin Coroutines
* Event handling using transactional outbox pattern

This library is currently only distributed in Liflig
internal repositories.

## Event handling

This library implements the transactional outbox pattern by storing events
in a database table in the same transaction as an aggregate modification and
forwarding them in a separate operation.

The event implementation gives an at-least once guarantee with ordering
as defined by the individual events.

### Setting up event handling

See the documentation for `OutboxTableName` for the database table that
must be created in an application specific migration.

The event handling should be used with an AWS SNS Topic and an AWS SQS Queue both
in FIFO mode. During a repository operation events are stored within the
same transaction in an outbox table. The application must use
`EventOutboxForwarderWorker` to publish events to the SNS Topic by help
of `SnsEventPublisher`.

To process incoming events the `SqsEventWorker` class can be used to process events
from the SQS Queue owned by the application. Internally in the application
an `EventTopic` is used to assign handlers and integrate with `SqsEventWorker`.

Domain events should inherit `Event` interface and define a getter for the
`eventGroupId` field. All events with the same value will be ordered.
The name and ID of the DDD Aggregate is a good fit for this.

All event handlers should be idempotent. If adding multiple event handlers
to the same `EventTopic` do note that any failures will cause event handlers running
before the failure to be rerun on retry (since the point of retry is the SQS
Queue itself).

### Limit on throughput

AWS SNS with FIFO only supports up to 300 API-calls per second, which will
limit the throughput in the application. Due to the outbox table we can _generate_ more
than 300 events per second, but can only _publish_ within the limits of AWS SNS.
Attempting to publish more events will cause backoff and delay for the events.

### Skipping event handling

It is possible to avoid implementing event handling by using the
`Event` interface as generic type in combination with `NoopEventOutboxWriter`
when creating the repository.

## Contributing

This project follows
https://confluence.capraconsulting.no/x/fckBC

To check build before pushing:

```bash
mvn verify
```

The CI server will automatically release new version for builds on master.
