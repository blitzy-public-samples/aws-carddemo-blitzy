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
- Performance — Applies, consumed by CS-01-005 and CS-02-002: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`. Passes when p50/p95/p99 are below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions. Injected failures are proven by `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end — Not applicable, because the end-to-end suite reads only sample accounts. When applicable: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`, running the four-step flow for account `00000000001`.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A, batched set-based inserts from a deterministic sequence.

Rationale: rows derive from sequence positions, so every run writes the same rows.

- Rejected: Option B, pre-generated fixture files, because a new shape forces regeneration.

Shared by both options:

- Topology: a one-shot loader step runs after the sample load, outside every request path.
- Naming: identifiers derive from the account position and avoid the sample's range.
- Addressing: only the loader's connection settings reach the store.
- Isolation: each entity commits in one transaction before its report entry is written.
- Fallback: none exists, because a failure fails the loader and the stack's readiness wait.
- Provisioning: the step always runs, and both suites share one stack and one store.
- Cut-over: the step retires with the stubs, because the card data owner holds real volume.
- Failures: a failed write rolls back its entity, and no later loader step starts.

### Design Option A — deterministic generator

Positions 1 to 10,000 and the pair list yield each account, its card and ten balances. Accounts copy the first sample account, and rows pass the parent-key check in 1,000-row batches, one transaction per entity.

How it behaves:

- Steps run account, card, then category balance, each above every sample step.
- Card and customer numbers are position offsets the transaction volume story derives.
- Balances derive from account and pair positions, so rows are distinct and reproducible.
- A balance whose pair the seed lacks is skipped and counted.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Sequence identifiers (vs random) | every run matches | rows look artificial |
| 2 | Batched inserts (vs single rows) | round trips drop | a failed batch voids the entity |
| 3 | Always run (vs data-set switch) | every stack matches | every start pays the load |
| 4 | Fixed pairs (vs varied pairs) | accounts share pairs | the spread never varies |

Effort and risk: low effort, and added start time is the only risk.

### Design Option B — pre-generated fixture files

A separate tool writes the same rows once into three checked-in files beside the sample copies. The loader reads them like the sample, in the same order, range and pair set.

How it differs:

- The 120,000 checked-in lines must be regenerated on any shape, range or pair change.
- The file loaders write one row at a time unless they gain the batched writer.
- Determinism rests on the regeneration tool staying unchanged.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Checked-in files (vs generation) | reviewers see every row | files lag shape changes |
| 2 | Sample parser (vs a generator) | no parser is added | starts parse every line |
| 3 | Always run (vs data-set switch) | every stack matches | every start pays the load |
| 4 | Fixed pairs (vs varied pairs) | accounts share pairs | the spread never varies |

Effort and risk: moderate effort for a tool, and regeneration can alter rows unnoticed.

### Recommendation

- Option: Option A.
- Why: every run writes the same rows without checked-in bulk data.
- Strongest argument against: a reviewer sees only the generation rule, never the rows.
- Shared items that do not decide the choice: the identifier range, pair set and load order.
- Flip conditions: the volume must be hand-curated, or rows are needed outside the loader.

## Design Validation

### Option A

Settled assumptions:

- The sample has 50 single-card accounts, with no identifier in the synthetic ranges.
- At least ten category seed rows exist, the first ten forming the pair list.
- The planning baseline is 100,000 balances on 10,000 synthetic accounts.
- The loader core runs steps in ascending order, stopping at the first failure.

Open assumptions:

- Open → added start time → settled by the transaction volume story's load timing.
- Open → whether 1,000-row batches fit the stack's loader limit → settled by that timing.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | each of 10,000 positions yields ten balances |
| AC2 | Holds | every identifier is a fixed offset |
| AC3 | Holds | accounts copy the first sample account's values |
| AC4 | Holds | the pair list keeps ascending key order |
| AC5 | Holds | no random, clock or environment value enters |
| AC6 | Holds | synthetic ranges share no sample value |
| AC7 | Holds | parents commit before child steps start |
| AC8 | Holds | every pair is checked against the seed |
| AC9 | Holds | synthetic orders exceed every sample order |
| AC10 | Holds | a failed batch rolls back the entity |
| AC11 | Holds | the writer saves 1,000 rows per batch |

### Option B

Settled assumptions:

- Option A's first three settled assumptions hold.
- The generation tool's output is checked in once beside the sample copies.

Open assumptions:

- Open → added start time → settled by the same timing as Option A.
- Open → whether the tool is versioned with the files → settled by checked-in data review.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the files hold exactly the generated counts |
| AC2 | Holds | the files carry the same identifier range |
| AC3 | Holds | files copy the first sample account's values |
| AC4 | Holds | the files keep the same pair order |
| AC5 | At risk | a regenerated file can change rows unnoticed |
| AC6 | Holds | the files carry the same disjoint ranges |
| AC7 | Holds | parent files commit before child files load |
| AC8 | Holds | every pair is checked against the seed |
| AC9 | Holds | file steps keep Option A's orders |
| AC10 | At risk | batch rollback needs a batched file loader |
| AC11 | At risk | file loaders write row by row |

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
