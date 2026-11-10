# CS-09-007: Category balance performance volume

Parent epic: [CS-09 Card Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a performance engineer, I want 100,000 synthetic category balances loaded after the sample, so that latency is measured at the planning baseline.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA01Y · TRAN-CAT-BAL
>
> Directive: performance planning baseline
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: a deterministic generator adds 10,000 synthetic accounts, one card cross-reference each and ten category balances each to the `account`, `card_cross_reference` and `category_balance` tables, always after the CardDemo sample load and in an identifier range disjoint from it. The record structures are those of the sample rows: `Data Layouts · CVACT01Y` (account), `Data Layouts · CVACT03Y` (card cross-reference) and `Data Layouts · CVTRA01Y` (category balance). The generator registers through the `FixtureLoader` entity-loader extension point of CS-09-001, writes through the entities and repositories of CS-09-002 under `card-servicing-service/src/main/java/com/example/cardservicing/integration/carddata/persistence/`, and adds its counts to the same `LoadReport`, each entry after its entity's load transaction commits. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/SyntheticCardDataGenerator.java`: the deterministic generator. It supplies three entity loads, one transaction per entity, and writes their rows in batched set-based inserts:
>   - `synthetic_account`, load order 101, writing `account`.
>   - `synthetic_card_cross_reference`, load order 102, writing `card_cross_reference`, with the parent-key check against loaded accounts.
>   - `synthetic_category_balance`, load order 103, writing `category_balance`, with parent-key checks against loaded accounts and seeded type and category pairs.
>   - Rows are produced lazily in write order and saved in batches of 1,000 rows through the CS-09-002 repositories. The session's JDBC batch size is set to 1,000, so each batch reaches the store as one batched insert, and the persistence context is flushed and cleared after each batch, so at most one batch is held in memory: 10 account batches, 10 cross-reference batches and 100 category-balance batches.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/fixture/FixtureLoader.java`: an edit that registers the generator's three entity loads as the loader step ordered after the sample Card Data loaders, at load orders 101, 102 and 103.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/fixture/SyntheticCardDataGeneratorTest.java`: unit tests for AC1–AC11. They run the sample loaders of CS-13-001 and CS-09-003 and then the generator through the recording transaction manager of CS-09-001 with an in-memory row writer, so the unit suite needs no store.
> - Together these are the 3 files touched.
>
> Rules, for n = 1 … 10,000:
>
> - It always runs after the sample load. Every sample loader declares an order below 101: reference data 1–2 (CS-13-001), sample Card Data 3–5 (CS-09-003) and the sample transaction loader of CS-10-002, which follows the Card Data loaders. No profile, setting or environment variable switches the generator off, and there is no data-set switch. The synthetic transaction load of CS-10-008 takes an order above 103.
> - Account ID: 10000000000 + n, 11 digits, `10000000001`–`10000010000`.
> - Card number: 7000000000000000 + n, 16 digits, `7000000000000001`–`7000000000010000`. CS-10-008 derives each synthetic account's card by the same formula when it joins synthetic transactions through the cross-reference.
> - Customer ID: 100000000 + n, 9 digits, `100000001`–`100010000`.
> - Card numbers and customer IDs are disjoint from the sample: no sample card number starts with `7000`, and the sample customer IDs run `000000001`–`000000050`.
> - Account fields: every synthetic account carries the non-key values that CS-09-003 stores for sample account `00000000001`: active status `Y`, current balance `194.00`, credit limit `2020.00`, cash credit limit `1020.00`, current cycle credit `0.00`, current cycle debit `0.00`, open date `2014-11-20`, expiration date `2025-05-20`, reissue date `2025-05-20`, address zip `A000000000` and a NULL group ID.
> - The ten pairs are used in a fixed order, pair positions p = 1 … 10: `01/0001`, `01/0002`, `01/0003`, `01/0004`, `01/0005`, `02/0001`, `02/0002`, `02/0003`, `03/0001`, `03/0002`. The data model's Synthetic volume section names no pair list, so these are the first ten category seed rows in file order (`Reference Tables · TRANCATG file`). The list ascends by type and category, so key order equals pair order.
> - Category balance at pair position p of account n: (10 × n + p) / 100 at scale 2, so account `10000000001` carries `0.11`–`0.20` and account `10000010000` carries `1000.01`–`1000.10`.
> - Write order: accounts by ascending n, then cross-references by ascending n, then category balances by ascending n and, within one account, by ascending p.
> - Output is deterministic and FK-valid: no random source, clock or environment value enters a row, and every child row names a parent row loaded before it.
> - The rows match the sample shape, with the same columns filled at the same widths and the same NULL group ID, so latency is representative.
>
> In scope:
>
> - The generator.
> - Its ordering after the sample.
> - The ID formulas.
> - The pair order.
> - The report counts.
>
> Not in this story:
>
> - Synthetic transactions (CS-10-008).
> - The Compose stack and its health timeout (CS-01-001).
>
> > SCOPE-QUESTION (Resolved): The stack bounds the loader that runs this generator with a process time limit, not a health timeout. CS-01-001 disables the fixture loader's health check and runs it under a 600 s process timeout, because Compose 2.29.7 applies no `--wait-timeout` to the wait on a `service_completed_successfully` dependency, while the stack's health checks cover its long-running containers. The stack and that loader limit stay with CS-01-001.
>
> - The latency scenarios (CS-01-005, CS-02-002).
> - Auth entitlements for synthetic accounts (CS-12-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: After the sample and synthetic load, the load report holds the entries `synthetic_account` 10,000 loaded / 0 skipped, `synthetic_card_cross_reference` 10,000 loaded / 0 skipped and `synthetic_category_balance` 100,000 loaded / 0 skipped, each with 0 derived timestamps. The generator writes exactly 10,000 accounts, 10,000 cross-references and 100,000 category balances.
> - AC2: Boundary: accounts `10000000001` and `10000010000` exist, and no `account`, `card_cross_reference` or `category_balance` row exists for `10000000000` or `10000010001`. Account `10000000001` has exactly one cross-reference, card `7000000000000001` with customer `100000001`, and account `10000010000` has exactly one, card `7000000000010000` with customer `100010000`.
> - AC3: Accounts `10000000001` and `10000010000` each store active status `Y`, current balance `194.00`, credit limit `2020.00`, cash credit limit `1020.00`, current cycle credit `0.00`, current cycle debit `0.00`, open date `2014-11-20`, expiration date `2025-05-20`, reissue date `2025-05-20`, address zip `A000000000` and a NULL group ID.
> - AC4: Every synthetic account has exactly ten category balances, one per listed pair, and read in key order they run `01/0001`, `01/0002`, `01/0003`, `01/0004`, `01/0005`, `02/0001`, `02/0002`, `02/0003`, `03/0001`, `03/0002`. Account `10000000001` stores `0.11` for `01/0001` through `0.20` for `03/0002`, and account `10000010000` stores `1000.01` through `1000.10`. No synthetic category balance carries a pair outside the list.
> - AC5: Two runs, each starting from an empty store, write row-for-row identical `account`, `card_cross_reference` and `category_balance` tables, with equal values in every column and the same write order.
> - AC6: No synthetic account ID falls in `00000000001`–`00000000050`, no synthetic card number equals any of the 50 sample card numbers, and no synthetic customer ID equals any sample customer ID `000000001`–`000000050`. After the synthetic load, the 50 sample rows of each of the three tables equal their values before it.
> - AC7: After the sample plus synthetic load, every load-report entry shows 0 skipped rows, and the sample and synthetic entries together total 10,050 accounts, 10,050 cross-references and 100,050 category balances, equal to the row counts of `account`, `card_cross_reference` and `category_balance`.
> - AC8: Every listed pair exists in the `transaction_category` seed of CS-13-001. With a category seed that lacks `03/0002`, the `synthetic_category_balance` entry reports 90,000 loaded / 10,000 skipped, and no stored category balance carries `03/0002`.
> - AC9: The three synthetic loads register at load orders 101 (`synthetic_account`), 102 (`synthetic_card_cross_reference`) and 103 (`synthetic_category_balance`), above every sample loader order. The synthetic account load starts only after every sample loader has committed, and each synthetic load starts only after the one before it commits. When the sample account load fails, the loader exits with a non-zero status, the three tables hold no synthetic row, and the load report holds no synthetic entry.
> - AC10: When the row writer fails on the third batch of synthetic category balances, the synthetic category-balance transaction rolls back: `category_balance` holds only its 50 sample rows, and the load report holds no `synthetic_category_balance` entry. The committed `synthetic_account` and `synthetic_card_cross_reference` entries and their 10,000 rows each remain, no load with a higher order starts, and the loader exits with a non-zero status.
> - AC11: The row writer receives exactly 10 batches of 1,000 accounts, 10 batches of 1,000 cross-references and 100 batches of 1,000 category balances, in write order, and writes no synthetic row outside a batch.
>
> ### 3. Dependencies
>
> - CS-09-003 — loads the sample accounts, cross-references and category balances that synthetic volume must follow and stay disjoint from.
>
> Risk: A slow volume load lengthens every Compose start, so the load runs in batched set-based inserts.

## 4. Design

- Diagram: [Logical architecture](../../../../architecture/logical-architecture.mmd)
- Data model: [Synthetic volume](../../../../data-model.md#synthetic-volume)
- Technical requirements: [D5 One Compose stack for both suites](../../../../technical-requirements.md#d5-one-compose-stack-for-both-suites)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies: counts, determinism, disjointness and pair order (AC1–AC11), run by `./gradlew :card-servicing-service:test jacocoTestCoverageVerification` through the recording transaction manager of CS-09-001 and an in-memory row writer. Passes when all tests pass with ≥80% line coverage per module.
- Integration — Not applicable, because the story makes no upstream call. When applicable: `./gradlew :card-servicing-service:integrationTest`, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Applies, consumed by CS-01-005 and CS-02-002: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`. Passes when p50/p95/p99 are below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions. Injected failures are proven by `cutoffMs` ≤ 900 and a client response below 1,000 ms.
- End-to-end — Not applicable, because the end-to-end suite reads only sample accounts. When applicable: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`, running the four-step flow for account `00000000001`.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A, batched set-based inserts from a deterministic sequence.

Rationale: a generator inside the loader derives every synthetic row from its position in a fixed sequence, so the volume needs no stored data and reads the same on every run.

- Rejected: Option B, pre-generated fixture files, because they put one hundred and twenty thousand rows of checked-in data into the repository and must be regenerated whenever the shape changes.

### Shared by both options

- Topology: the step runs inside the one-shot loader job, after the sample load and outside every request path.
- Naming: synthetic identifiers sit in a range disjoint from the sample's, with each card and customer number derived from its account's position.
- Addressing: the relational store is reached through the loader's connection settings only.
- Isolation: one transaction per entity, with each load report entry written after its commit.
- Fallback: none, because a failure fails the loader and the stack's readiness wait.
- Provisioning: the step always runs, and both suites share one stack and one store.
- Cut-over: the step retires with the stubs, because the real card data owner holds the real volume.
- Failures: a failed batch rolls back its entity, and no later loader step starts.

### Design Option A — deterministic generator with batched inserts

A generator walks positions one to ten thousand and derives each synthetic account, its one synthetic card and its ten category balances from the position and the fixed seed pair list. Rows stream through the loader core's parent-key check into the row writer, which saves them in batches of one thousand inside one transaction per entity. Every synthetic account copies the field values of the first sample account, so row width and empty fields match the sample.

How it behaves:

- Three loader steps run in the order account, card, category balance, each above every sample loader step.
- Each card number and customer number is a fixed offset from the account's position, so the transaction volume story derives the same cards without reading them.
- Each category balance takes a value from its account position and its pair position, so every row is distinct and reproducible.
- The generator holds at most one batch in memory.
- A rejected batch rolls back its entity, leaves no load report entry for it and stops the loader with a failure status.
- The seed pairs pass the core's parent-key rule, so a pair missing from the seed is skipped and counted.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Sequence-derived identifiers (vs random identifiers) | every run writes the same rows | the identifier pattern is visibly artificial |
| 2 | Batched inserts (vs row-by-row inserts) | the volume load adds far fewer round trips to every stack start | a rejected batch discards the whole entity |
| 3 | Always run (vs a data-set switch) | one start command yields one data set everywhere | every stack start pays the volume load |
| 4 | Fixed pair order (vs per-account variation) | every synthetic account carries the same ten balance rows | the volume never exercises an uneven category spread |

Effort and risk: low effort with one generator and one registration, with the risk confined to the load time added to every stack start.

### Design Option B — pre-generated fixture files

The same rows are generated once by a separate tool and checked in as three fixed-width fixture files beside the sample copies. The loader core reads them through the same parser, record layouts and parent-key rule as the sample. The load order, identifier range and pair set are the same as in Option A.

How it differs:

- One hundred and twenty thousand lines of checked-in data enter the repository.
- Any change to the shape, the range or the pair set needs the files regenerated and checked in again.
- A reviewer can read every row directly.
- The file loaders write rows one at a time unless they gain the batched writer.
- Determinism rests on the regeneration tool staying unchanged.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Checked-in fixture files (vs generation at load time) | every row is open to direct review | the repository carries bulk data that drifts with the shape |
| 2 | Same parser as the sample (vs a dedicated generator) | no new parsing rule is written | every stack start parses one hundred and twenty thousand lines |
| 3 | Always run (vs a data-set switch) | one start command yields one data set everywhere | every stack start pays the volume load |
| 4 | Fixed pair order (vs per-account variation) | every synthetic account carries the same ten balance rows | the volume never exercises an uneven category spread |

Effort and risk: moderate effort with a separate generation tool, with the risk that a regenerated file changes rows unnoticed.

### Recommendation

- Option: Option A.
- Why: deterministic rows with no checked-in bulk data.
- Strongest argument against: the generation rule lives in code, so a reviewer cannot inspect the rows directly.
- Shared items that do not decide the choice: the identifier range, the seed pair set and the load order.
- Flip conditions: the volume shape must be hand-curated, or another consumer needs the rows outside the loader.

## Design Validation

### Option A

Settled assumptions:

- The sample has fifty accounts, each with one card, and no sample identifier falls in the synthetic ranges.
- At least ten category seed rows exist, and the first ten form the seed pair list.
- The planning baseline is one hundred thousand category balances on ten thousand synthetic accounts.
- The loader core runs steps in ascending order and stops at the first failed step.

Open assumptions:

- Open → load time added to the stack start → settled by the transaction performance volume story's measurement.
- Open → whether batches of one thousand rows keep the load inside the loader's time limit → settled by the transaction performance volume story's load-time measurement against the test stack story's loader time limit.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the generator walks exactly ten thousand positions with ten pairs each |
| AC2 | Holds | every identifier is a fixed offset from a position between one and ten thousand |
| AC3 | Holds | every synthetic account copies the first sample account's field values |
| AC4 | Holds | the pair list is fixed in ascending key order with one value per pair position |
| AC5 | Holds | no random source, clock or environment value enters a row |
| AC6 | Holds | the synthetic ranges share no value with the sample's |
| AC7 | Holds | every parent row commits before its child step starts |
| AC8 | Holds | the core's parent-key rule checks every pair against the seed |
| AC9 | Holds | the synthetic steps carry orders above every sample step |
| AC10 | Holds | the core rolls back the entity at its first failed batch |
| AC11 | Holds | the row writer saves exactly one thousand rows per batch |

### Option B

Settled assumptions:

- The sample has fifty accounts, each with one card, and no sample identifier falls in the synthetic ranges.
- At least ten category seed rows exist, and the first ten form the seed pair list.
- The planning baseline is one hundred thousand category balances on ten thousand synthetic accounts.
- The generation tool's output is checked in once beside the sample copies.

Open assumptions:

- Open → load time added to the stack start → settled by the transaction performance volume story's measurement.
- Open → whether the generation tool is kept and versioned with the files → settled by the review of the checked-in data.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the files hold exactly the generated counts |
| AC2 | Holds | the files carry the same identifier range |
| AC3 | Holds | every file line carries the first sample account's field values |
| AC4 | Holds | the files list the ten pairs in the same order |
| AC5 | At risk | a regenerated file can change rows unnoticed |
| AC6 | Holds | the files carry the same disjoint ranges |
| AC7 | Holds | every parent file commits before its child file loads |
| AC8 | Holds | the core's parent-key rule checks every pair against the seed |
| AC9 | Holds | the file steps carry the same orders above every sample step |
| AC10 | At risk | a rejected batch needs the file loaders to gain the batched writer |
| AC11 | At risk | the file loaders write row by row unless they gain the batched writer |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-09-003 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Card Data Integration |
| Epic Link | CS-09 |
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
