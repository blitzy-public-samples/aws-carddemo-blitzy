# CS-10-008: Transaction performance volume

Parent epic: [CS-10 Transaction Activity Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a performance engineer, I want 1,000,000 synthetic transactions with a large-history account and a sparse filter pair, so that paging latency is measured at the planning baseline.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA05Y · TRAN-ID
>
> Directive: performance planning baseline
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: `SyntheticTransactionGenerator` produces 1,000,000 transaction rows from a fixed seed and inserts them in set-based batches, wired into the fixture-load sequence after the sample load and after the synthetic category-balance volume. The record structure is that of the sample rows, `Data Layouts · CVTRA05Y`. The generator registers through the `FixtureLoader` entity-loader extension point of CS-09-001 as entity `synthetic_transaction_record` with load order 104, above the sample transaction load at order 6 (CS-10-002) and the synthetic category-balance load at order 103 (CS-09-007). It writes `transaction_record` through the `TransactionRecordRepository` of CS-10-001 and adds its counts to the same `LoadReport` after its load transaction commits, without re-implementing `CardDemoRecordParser`, `FixtureLoader` or `LoadReport`. Shape: 100,000 rows on `10000000001`, exactly 20 of them with type `05` and category `0001` and the rest on other pairs from the 18 category reference rows; 100 rows on each account from `10000000002` to `10000009001`; none on `10000009002` to `10000010000`. Each row uses its account's synthetic card from CS-09-007, sets `account_id` from that card's cross-reference, carries a transaction ID outside the sample range and processed timestamps and IDs derived from the seed, so two runs yield identical rows. The load report records the synthetic count, zero skips and the load time. Target paths (3 files):
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/SyntheticTransactionGenerator.java`: the deterministic generator and its entity registration. It produces rows lazily in write order, applies the rules below, and ends its step with the query-plan check below.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/FixtureLoader.java` (edit: place the synthetic transaction step in the load sequence): the edit places the generator's entity load at load order 104, after the synthetic category-balance load at 103, and logs that step's load time, the elapsed milliseconds from its start to its commit, on its load-report line when the job exits.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/fixture/SyntheticTransactionGeneratorTest.java`: unit tests for AC1–AC8. They run the sample loaders of CS-13-001, CS-09-003 and CS-10-002, the synthetic loads of CS-09-007 and then the generator through the recording transaction manager of CS-09-001, with a counting row writer that keeps per-account and per-pair counts and a running digest of every row instead of the rows, so the unit suite needs no store. Two runs are compared row for row over the first 200,000 rows and by digest over all 1,000,000, the digest of all 1,000,000 rows and the first and last row of each account range are pinned to their expected values, and the query-plan check is driven with fixed plan texts, one passing and one holding a sort step.
> - Together these are the 3 files touched.
>
> Rules, for row position k = 1 … 1,000,000 in write order:
>
> - Fixed seed: the generator's constants are the transaction ID base 9000000000000000, the card base 7000000000000000 of CS-09-007, the base instant `2024-01-01T00:00:00.000000Z`, the sparse interval 5,000 and the pair list below. No random source, clock or environment value enters a row, and a change to any constant changes the pinned row digest, so it fails a unit test.
> - Account: positions 1–100,000 belong to account n = 1, `10000000001`. A position k from 100,001 to 1,000,000 belongs to account n = 2 + ⌊(k − 100,001) / 100⌋, so accounts `10000000002`–`10000009001` hold 100 rows each, and accounts `10000009002`–`10000010000` hold none, the empty-list path. j is the row's 1-based position within its account.
> - Card number: 7000000000000000 + n, the card CS-09-007 stores for account 10000000000 + n. `account_id` is the account of that card's loaded `card_cross_reference` row, so it equals 10000000000 + n.
> - Transaction ID: 9000000000000000 + k, 16 digits, `9000000000000001`–`9000000001000000`. Every sample transaction ID starts with `0000000`, so no synthetic ID equals a sample ID.
> - Type and category pair: on account `10000000001`, a row whose j is a multiple of 5,000 (j = 5,000, 10,000, …, 100,000) carries `05`/`0001`, which gives exactly 20 rows. Every other row carries position ((j − 1) mod 17) + 1 of the 17 other category seed rows in file order (`Reference Tables · TRANCATG file`): `01`/`0001`–`0005`, `02`/`0001`–`0003`, `03`/`0001`–`0003`, `04`/`0001`–`0003`, `06`/`0001`–`0002` and `07`/`0001`. No other account and no sample row carries `05`/`0001`.
> - Timestamps: the processed timestamp is the base instant plus k minutes, so the rows of `10000000001` run from `2024-01-01T00:01:00.000000Z` to `2024-03-10T10:40:00.000000Z` and the last row carries `2025-11-25T10:40:00.000000Z`. Every row's processed timestamp is distinct, so each account's newest-first order is decided by the timestamp alone. The original timestamp equals the processed timestamp, and the generator counts no derived timestamp.
> - Amount: magnitude (k mod 100,000) / 100 + 1.00 at scale 2, from `1.00` to `1000.99`, negative on type `03` rows, the credit type that carries all 50 negative sample amounts.
> - Other fields: source `POS TERM`, description `Purchase at Lebsack and Sons`, merchant ID `800000000`, merchant name `Lebsack and Sons`, merchant city `Otisbury` and merchant zip `32545`, the values CS-10-002 stores for sample transaction `0000000776200014`, so every column is filled at the sample's widths.
> - Write order and batches: ascending k, saved in batches of 1,000 rows through the CS-10-001 repository, 1,000 batches in all. The session's JDBC batch size is 1,000, so each batch reaches the store as one batched insert, and the persistence context is flushed and cleared after each batch, so at most one batch is held in memory.
> - Parent keys: each row passes the loader core's parent-key check against the loaded cross-reference cards and category rows before its write. A row failing it is skipped and counted, and generated rows pass it, so the entry reports 0 skipped.
> - Query-plan check: after the last batch and before the step commits, the generator runs `ANALYZE transaction_record` and reads `EXPLAIN (FORMAT JSON)` of the unfiltered first page for `10000000001`: equality on `account_id`, order `processed_timestamp desc, transaction_id desc`, limit 21, the default page size 20 plus one. A plan whose scan of `transaction_record` reads any index other than `(account_id, processed_timestamp desc, transaction_id desc)`, or that holds a Sort node, fails the step with an error naming the plan's top node. The step then rolls back under the loader core's rules, no later loader starts and the job exits with a non-zero status.
>
> In scope:
>
> - The generator.
> - Its shape and determinism.
> - Load ordering.
> - Load-time reporting.
> - The query-plan check.
>
> Not in this story:
>
> - Synthetic accounts, cards and category balances (CS-09-007).
> - The Compose stack and its health timeout (CS-01-001).
>
> > SCOPE-QUESTION (Resolved): The stack bounds the loader that runs this step with a process time limit, not a health timeout. CS-01-001 disables the fixture loader's health check and runs it under a 600 s process timeout, because Compose 2.29.7 applies no `--wait-timeout` to the wait on a `service_completed_successfully` dependency, while the stack's health checks cover its long-running containers. The stack and that loader limit stay with CS-01-001.
>
> - The latency scenarios (CS-03-003, CS-04-002).
> - The filter-leading indexes (Recommendations).
>
> ### 2. Acceptance Criteria
>
> - AC1: After the load the synthetic rows total 1,000,000, the load report holds `synthetic_transaction_record` with 1,000,000 loaded, and `transaction_record` holds 1,000,300 rows.
> - AC2: Account `10000000001` holds 100,000 rows, each account from `10000000002` to `10000009001` holds exactly 100, and each account from `10000009002` to `10000010000` holds none.
> - AC3: Exactly 20 rows on `10000000001` carry type `05` and category `0001`.
> - AC4: Two generator runs with the same seed produce identical rows, compared by transaction ID, account, card, type, category, amount and timestamps.
> - AC5: Every synthetic row references an existing account, cross-reference card and category pair, and the load report shows 0 skipped for the synthetic set.
> - AC6: No synthetic transaction ID equals a sample transaction ID.
> - AC7: The synthetic transaction load runs after the sample load and after the synthetic category-balance volume, and the load report records its load time.
> - AC8: On the Compose PostgreSQL after the load, the query plan of the unfiltered first page for `10000000001` reads the `(account_id, processed_timestamp desc, transaction_id desc)` index with no sort step.
>
> ### 3. Dependencies
>
> - CS-10-002 — the sample transaction load precedes the synthetic set.
> - CS-09-007 — creates the synthetic accounts and cards the rows reference.
>
> Risk: a slow generator stretches stack start past the health timeout.
>
> > SCOPE-QUESTION (Resolved): The bound this Risk names is the fixture loader's 600 s process timeout from CS-01-001, not a loader health timeout. A generator slow enough to reach it makes the loader exit non-zero, so the five stubs and the Card Servicing service never start and `docker compose -f tests/docker-compose.yml up -d --wait` fails. The load time this story records (AC7) is measured against that limit.
>

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [Synthetic volume](../../../../data-model.md#synthetic-volume)
- TRD: [D4 Transaction-to-account access](../../../../technical-requirements.md#d4-transaction-to-account-access), [D5 One Compose stack for both suites](../../../../technical-requirements.md#d5-one-compose-stack-for-both-suites)
- Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies: `SyntheticTransactionGeneratorTest` checks shape and determinism on a reduced count, two runs compared row for row over the first 200,000 rows, plus the full-count totals, disjointness, load order, load-time reporting and the plan check's pass and fail outcomes behind AC1–AC8, through the recording transaction manager of CS-09-001 and a counting row writer. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module.
- Integration — Applies indirectly — the CS-10-007 suite's fixture loader run executes this generator, because no setting switches the synthetic loads off, yet that suite asserts only sample rows and the `transaction_record` load-report entry, so this story adds no integration assertion and its volume assertions stay in the unit and performance suites. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic (CS-10-007 for this epic) covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Applies, consumed by CS-03-003 and CS-04-002, and every stack start runs the AC8 plan check on the Compose PostgreSQL. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms; applies to balance-retrieval and drill-down stories.
- End-to-end — Not applicable — the end-to-end suite reads only sample accounts `00000000001` to `00000000050`. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — set-based batched inserts from a deterministic sequence.

Rationale: a generator inside the fixture loader derives every row from a fixed seed, so no bulk data enters the repository and every stack start loads the same rows.

- Rejected: Design Option B, pre-generated fixture files — they bloat the repository and slow stack starts.

### Shared by both options

- Topology: a one-shot fixture loader step at stack start, after the sample load, outside request paths.
- Naming: a disjoint synthetic identifier range under one load-report entry.
- Addressing: each row names a fixed-range synthetic account and its card, whose cross-reference supplies the account.
- Isolation: with no shared identifiers, end-to-end tests see only the sample.
- Fallback: none, as a failed or timed-out load exits non-zero, failing the stack start.
- Provisioning: every stack start, after the category balance volume, with no switch.
- Cut-over: retires with the stubs.
- Failures: a non-zero skip count fails the shape test, and a failed batch or sorting plan rolls back the step, stopping the loader.

### Design Option A — set-based batched inserts from a deterministic sequence

A generator maps one million positions to rows from the fixed seed. It writes thousand-row batches in one transaction and checks the query plan before commit.

How it behaves:

- Its load-report line shows count, zero skips and load time.
- Exactly twenty large-history rows carry type 05 with category 0001.
- Cards come from a fixed offset, not a card list.
- A sorting or other-index plan fails the step with a named error.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Fixed seed (vs random source) | identical rows every run | seed changes must be deliberate |
| 2 | Set-based batches (vs row-by-row) | load fits the time limit | batches hold memory |
| 3 | Sparse pair on one account (vs spread) | exact worst case | other accounts never exercise it |
| 4 | Plan check at stack start (vs manual) | regressions fail ahead of the latency runs | one planner choice can block both suites |

Effort and risk: small effort, low risk limited to the load time added to every stack start.

### Design Option B — pre-generated fixture files

A separate tool writes the same rows once into files beside the sample copy. The loader parses them like the sample.

How it differs:

- About 350 megabytes enter the repository.
- Every stack start parses one million lines.
- Shape changes regenerate the files.
- Determinism rests on unchanged files.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Checked-in files (vs generation) | every row is reviewable | about 350 megabytes of bulk |
| 2 | Sample parser (vs generator) | no loader generation rule | a million-line parse per start |
| 3 | Sparse pair on one account (vs spread) | exact worst case | other accounts never exercise it |

Effort and risk: small effort, at risk on repository size and stack start time.

### Recommendation

- Option: Design Option A
- Why: a few rules replace a large file.
- Strongest argument against: a generator defect repeats on every run, so determinism does not prove correctness.
- Shared items that do not decide the choice: shape, ranges, ordering, load report and plan check.
- Flip conditions: data no rule describes, such as captured production-like history.

## Design Validation

### Option A

Settled assumptions:

- The category balance volume story supplies accounts and cards.
- Reference data holds type 05 with category 0001, unused by the sample.
- The schema story supplies the keyset index.
- Sample identifiers start with seven zeros.
- The loader runs steps in order, stopping at the first failure.

Open assumptions:

- Pull-request runner load time Open → the compose stack story's first measured start.
- Planner's keyset index choice at volume Open → the post-load plan check.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | one million positions follow the sample |
| AC2 | Holds | a fixed range rule places each position |
| AC3 | Holds | only every five-thousandth large-history row qualifies |
| AC4 | Holds | no random, clock or environment input enters |
| AC5 | Holds | every referenced row loads first |
| AC6 | Holds | no synthetic identifier starts with seven zeros |
| AC7 | Holds | the step follows the sample and volume loads |
| AC8 | Conditional | the plan rests on fresh statistics |

### Option B

Settled assumptions:

- The generation tool's output is checked in once beside the sample copy.
- Other prerequisites match Option A.

Open assumptions:

- Stack start parse time Open → the compose stack story's first measured start.
- Planner's keyset index choice at volume Open → the post-load plan check.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the files hold exactly one million lines |
| AC2 | Holds | the files carry the same account ranges |
| AC3 | Holds | the files carry the same twenty sparse rows |
| AC4 | Holds | unchanged files are read every run |
| AC5 | Holds | every line references rows loaded first |
| AC6 | Holds | the files carry the same disjoint identifier range |
| AC7 | At risk | file parsing lengthens the load |
| AC8 | Conditional | the plan rests on fresh statistics |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-10-002, CS-09-007 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Transaction Activity Integration |
| Epic Link | CS-10 |
| Story Points | 2 |
| Priority | High |
| Components | TBD |
| Fix Version/s | TBD |
| Team | TBD |
| Assignee | TBD |
| Reporter | TBD |
| Labels | TBD |
| Sprint | TBD |
| Due Date | TBD |
| Environment | TBD |
| Persona | TBD |
| Estimated Effort | TBD |
