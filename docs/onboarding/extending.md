# Extending the Application

This guide shows how to add functionality **the idiomatic way**, following the
package-by-layer design in [architecture.md](../architecture.md), and lists
**suggested next tasks** discovered during the migration review.

> **Golden rules.**
> 1. Every change must trace to a legacy construct — no new business features
>    (see [decision log](../decision-log.md) and the
>    [traceability matrix](../traceability-matrix.md)).
> 2. Monetary values are always `BigDecimal` at scale 2 with an explicit
>    `RoundingMode` — never `double`/`float`.
> 3. Jakarta namespace only (`jakarta.*`), no wildcard imports, constructor
>    injection only.
> 4. Never edit anything under [`legacy/`](../../legacy) (see [Pitfalls](./pitfalls.md)).
> 5. Keep the build at **zero warnings** and coverage at **≥ 80%**.

---

## 1. The layers

```
web/         @RestController per online program   (HTTP surface)
  ↓ mapper/  hand-written DTO ⇄ entity
service/     business logic ported from COBOL paragraphs
  service/rule/  one component per COBOL edit/validation paragraph
repository/  Spring Data JPA (replaces VSAM file I/O)
domain/      JPA entities (from copybook record layouts)
  domain/type/  Money value object
batch/       Spring Batch Job/Step/chunk (replaces JCL/batch programs)
exception/   typed FILE STATUS / CICS RESP / reject-code translation
common/, observability/, security/, config/   cross-cutting
```

Data flows top-to-bottom online; batch enters at `batch/` and reuses `service/`
and `repository/`.

---

## 2. Add a new online screen (controller)

Work from the BMS map and its symbolic copybook, then the online program:

1. **DTOs** — from the symbolic copybook in
   [`legacy/cpy-bms/`](../../legacy/cpy-bms), create a `*Request` and `*Response`
   pair under `dto/`. Preserve **every** field name, maximum length, PIC-derived
   type, and edit rule. Encode PF-key actions (Enter/PF3/PF4/PF5/PF7/PF8/PF12) as
   explicit action fields, not a rendered terminal.
2. **Mapper** — add a hand-written mapper under `mapper/` for DTO ⇄ entity. Do
   **not** introduce an annotation-processor mapper (see the mapper decision in the
   [decision log](../decision-log.md)); explicit mapping preserves field-level
   traceability.
3. **Service** — port the online program's business paragraphs into a method on
   the matching `service/` class, preserving control flow and evaluation order.
   Each COBOL edit/validation paragraph becomes a component under `service/rule/`.
4. **Controller** — add a `@RestController` under `web/` that binds the request
   DTO, calls the service, and returns the response DTO. Model the
   first-entry-vs-re-entry flag (`CDEMO-PGM-CONTEXT`) explicitly so screen
   initialization matches the legacy.
5. **Exceptions** — surface `FILE STATUS` / CICS `RESP` outcomes through the typed
   hierarchy in `exception/` so the `GlobalExceptionHandler` maps them to the
   correct HTTP status.
6. **Tests** — unit-test the service/rules; add a controller slice test; assert
   field contracts against the BMS map.
7. **Traceability** — add the program's paragraphs and every DTO field / PF-key to
   the [traceability matrix](../traceability-matrix.md).

---

## 3. Add a new batch job

Work from the batch program and its JCL trigger:

1. **Reader/Processor/Writer** — under `batch/reader|processor|writer/`, model the
   sequential flow as a chunk-oriented step. External fixed-width files use
   `FlatFileItemReader`/`Writer` with the `FixedWidthCodec` so column positions and
   record lengths match the legacy layout exactly (e.g. the 430-byte DALYREJS
   reject record).
2. **Job config** — under `batch/`, compose the step(s) into a `Job`. Map JCL
   step ordering and DD dependencies to step/flow ordering; map SORT utilities to a
   Java `Comparator` or `ORDER BY`; map return codes to batch exit codes
   **0 / 4 / 8** (e.g. posting returns **RC=4** when rejects occur).
3. **Service reuse** — put business logic in `service/`, not in the batch
   components, so online and batch share one implementation.
4. **Tests** — add **golden-file** tests comparing output row-for-row against
   fixtures derived from the legacy layouts and seed data. Interest must match the
   COBOL formula to the cent.
5. **Scheduling** — batch scheduling moves to the CI/CD workflow, not an in-app
   scheduler.

---

## 4. Add or change a domain entity

1. Derive fields from the copybook in [`legacy/cpy/`](../../legacy/cpy); monetary
   fields → `BigDecimal` + `DECIMAL(x,2)`.
2. Add a Flyway migration under `src/main/resources/db/migration/` (never edit an
   applied migration; add a new `V*__*.sql`).
3. Add the Spring Data repository under `repository/`. Reproduce VSAM browse
   patterns as sorted/paged queries; reproduce alternate-index browses as indexed
   `ORDER BY` queries — remember the transaction chronological index is on
   **`proc_ts`** (see [Pitfalls](./pitfalls.md)).
4. Use `@Version` optimistic locking to reproduce the READ-UPDATE-REWRITE
   integrity (a documented intentional improvement).

---

## 5. Before you open a pull request

- `./mvnw -B clean verify` is green: **zero warnings**, tests pass, **JaCoCo ≥ 80%**.
- `mvn -B dependency-check:check` reports **zero critical/high** CVEs.
- No hardcoded secrets; the CVV is never logged or returned in full; passwords are
  never logged.
- New constructs are reflected in the [traceability matrix](../traceability-matrix.md);
  any non-trivial decision or deviation has a [decision log](../decision-log.md) entry.

---

## 6. Suggested next tasks

Discovered during the migration review; ordered roughly by priority.

1. **Land the application modules.** Materialize `src/main/java/**`,
   `src/main/resources/**`, and `src/test/**` per the target structure in
   [architecture.md](../architecture.md), starting with `domain/` + `repository/`
   and the Flyway schema, then the core posting/interest batch jobs (highest
   parity risk).
2. **Golden-file parity harness.** Build fixtures from the legacy seed data and
   assert row-for-row parity for posting, the 430-byte reject file, interest,
   statements, and reports — this is the primary defense against decimal/ordering
   drift.
3. **Reject-path coverage.** A dedicated test per reject code (100/101/102/103)
   reproducing each legacy trigger condition in the exact validation order.
4. **Field-contract tests.** Assert each screen DTO preserves the BMS field names,
   lengths, types, edit rules, and PF-key actions (including PF4/PF5/PF12 where the
   source program uses them).
5. **Stand up the observability stack locally.** Add `docker-compose.yml`
   (PostgreSQL + Prometheus + Tempo + Grafana), then verify correlation-id
   propagation, traces, metrics, and the [Grafana dashboard template](../observability/grafana-dashboard.json)
   against the running stack — at which point the observability docs can move from
   "planned/designed" to "verified locally".
6. **CI workflow.** Add `.github/workflows/ci.yml` to run `clean verify`, publish
   JaCoCo, and run OWASP dependency-check with an NVD API key so the security gate
   is fast and deterministic.
7. **Maven wrapper.** Generate `mvnw`/`mvnw.cmd`/`.mvn/` so the exact Maven version
   is reproducible.
8. **Spring Boot lifecycle.** Track the Boot 3.5.x support status (OSS support has
   ended for this line — see [decision log](../decision-log.md)); plan a supported
   upgrade path before any production use.
9. **Data anomalies.** Carry the classified legacy source anomalies (see
   [Pitfalls](./pitfalls.md) §7) into fixtures/tests as known conditions —
   **classify, never edit** the legacy bytes.
