# ddd-aggregates

Kotlin library holding some concepts around DDD Aggregates
for reuse in Liflig.

* Basic structure for Entity, Entity IDs, Aggregates and versions
* CRUD-like Repository with optimistic locking using JDBI,
  Kotlinx Serialization, Arrow and Kotlin Coroutines

This library is currently only distributed in Liflig
internal repositories.

## Event handling

This library implements the transactional outbox pattern by storing events
in a database table in the same transaction as an aggregate modification.

## Contributing

This project follows
https://confluence.capraconsulting.no/x/fckBC

To check build before pushing:

```bash
mvn verify
```

The CI server will automatically release new version for builds on master.
