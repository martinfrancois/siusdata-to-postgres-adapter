# Query Metrics Analysis

This document summarises the PostgreSQL activity that was captured for one day and highlights the optimisations applied to the adapter.

## Captured metrics

| Query (abridged) | Rows | Calls | Min (ms) | Max (ms) | Mean (ms) | Total (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `INSERT INTO siusdata_shots (...)` | 1,435 | 1,435 | 0.0 | 21.7 | 0.1 | 151.2 |
| `SELECT ... FROM file_progress WHERE file_name = $1` | 913 | 915 | 0.0 | 1.1 | 0.0 | 30.5 |
| `INSERT INTO file_progress ... ON CONFLICT ...` | 455 | 455 | 0.0 | 4.1 | 0.1 | 44.5 |
| `SELECT ... FROM siusdata_shots ORDER BY "id"` | 311,120 | 505 | 0.0 | 194.2 | 9.8 | 4,950.7 |
| `SELECT ... pg_catalog.pg_attribute ...` (metadata introspection) | 12,012 | 512 | 0.1 | 2.3 | 0.3 | 152.2 |
| `SELECT key, set_config(...)` | 2,036 | 1,018 | 0.0 | 3.9 | 0.0 | 29.8 |

The remaining entries mainly relate to PostgreSQL replication state checks and schema discovery issued by database clients.

## Observations

* **Insert-heavy workload** – the adapter pushes roughly one `siusdata_shots` row per query. That aligns with the CSV ingestion logic but produces many server round-trips.
* **Frequent progress lookups** – each processing pass fetches and rewrites the `file_progress` row. This is necessary for crash-safety, yet it adds two statements per batch.
* **Expensive downstream reads** – 505 executions of `SELECT ... ORDER BY "id"` returned the entire table (≈311k rows) and consumed the vast majority of runtime. These requests are not issued by the adapter, so they likely come from analytics tooling or dashboards. Introducing pagination or summary views for consumers would dramatically reduce load.
* **Metadata chatter** – more than 500 introspection calls suggest that a client (often a SQL IDE or JDBC metadata lookup) keeps asking PostgreSQL for column definitions. Reusing prepared statements inside the adapter prevents us from adding to this noise.

## Implemented optimisation

The adapter previously created and closed a fresh `PreparedStatement` for every CSV record. That pattern encourages the JDBC driver to re-run metadata lookups and increases GC pressure. The processing loop now prepares the insert statement once and reuses it for all rows in the same transaction, clearing parameters between iterations before executing the statement.【F:src/main/java/ch/fmartin/SiusDataToPostgresAdapter.java†L652-L705】

This change keeps the transactional behaviour identical while reducing per-row overhead on both the application and database sides. Unit tests were extended to cover the helper that prepares the reusable statement and the adjusted insertion workflow.【F:src/test/java/ch/fmartin/SiusDataToPostgresAdapterTest.java†L812-L844】

## Additional tuning ideas

* Consider batching inserts with `PreparedStatement#addBatch` or PostgreSQL `COPY` for larger competitions.
* Create targeted indexes (for example on `(filename, shoot_ordinal)`) once query patterns from downstream consumers stabilise.
* If full-table exports are required, provide a materialised view or scheduled export job to isolate that load from the ingestion pipeline.
