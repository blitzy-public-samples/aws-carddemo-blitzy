# CS-09-004: Card Data stub service

Parent epic: [CS-09 Card Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want a mocked Card Data service answering its three contract operations, so that the service runs with zero live external calls.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-CURR-BAL
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: one gRPC stub of `cardservicing.carddata.v1.CardDataService` from `card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto` answers `GetAccount`, `GetCardCrossReference` and `ListCategoryBalances` from a mock-data provider over the rows that CS-09-003 loads into `account`, `card_cross_reference` and `category_balance`. An account carries the fields of `Data Layouts · CVACT01Y`, a cross-reference those of `Data Layouts · CVACT03Y` and a category balance those of `Data Layouts · CVTRA01Y`. The shared stub hosting of CS-11-002 serves the stub under the stub name `card-data`: on a `127.0.0.1` loopback port inside the test JVM through its `StubRegistration`, or alone in its own container in the Compose stack (CS-01-001). The stub serves the gRPC operations only; `card-servicing-service/src/main/resources/openapi/card-data-service.yaml` describes the same operations as HTTP/JSON, `GET /v1/card-data/accounts/{accountId}`, `GET /v1/card-data/accounts/{accountId}/card-cross-references` and `GET /v1/card-data/accounts/{accountId}/category-balances`. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/carddata/CardDataStubService.java`:
>   - Extends the generated `CardDataServiceGrpc.CardDataServiceImplBase` in `com.example.cardservicing.contract.carddata.v1`, which is generated under the build directory and never checked in.
>   - Checks `account_id` against the contract pattern `^[0-9]{11}$` and the all-zero rule before any provider read.
>   - `GetAccount` answers `OK` with the one `Account` the provider returns. `GetCardCrossReference` answers `OK` with `card_cross_references` ordered by `card_number` ascending. `ListCategoryBalances` answers `OK` with `category_balances` ordered by `type_code`, then `category_code`, ascending. The stub applies each order itself, comparing codes character by character, so the answer depends neither on the order the provider returns nor on the store's collation.
>   - An account is unknown when the provider holds no `account` row for it, and each of the three operations then answers `NOT_FOUND`. An existing account with no cross-reference or no category balance is answered `OK` with an empty list, never with an error.
>   - Amounts render as scale-2 decimal strings matching `^-?[0-9]+\.[0-9]{2}$`, dates as `YYYY-MM-DD`, and identifiers and codes keep their leading zeros. `group_id` is set only when the stored group ID is present, so a NULL group leaves it unset and its JSON form omits `groupId`.
>   - It reads the provider on every call and keeps no copy of the rows between calls.
>   - A nested configuration class, active only when `card-servicing.stub.serve` is `card-data`, builds the relational provider and declares the `StubServerHost.HostedStub` bean named `card-data` for container mode. With `card-servicing.stub.serve` empty, the Card Servicing service process builds no Card Data stub and no provider, so it opens no store connection for them.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/carddata/CardDataMockDataProvider.java`: the provider abstraction, with one account lookup, one cross-reference read and one category-balance read per account identifier, and its relational implementation nested in the same file. The relational implementation reads through the account, cross-reference and category-balance repositories of CS-09-002 under `card-servicing-service/src/main/java/com/example/cardservicing/integration/carddata/persistence/`, on every request, holds no stub-side cache, and turns any persistence or connection exception into one provider read failure.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/carddata/CardDataStubRegistration.java`: the `StubRegistration` (CS-11-002) for stub name `card-data`, under `com.example.cardservicing.integration` so that `StubHarness.allRegistered()` discovers it. Its service definition is a `CardDataStubService` built over an in-memory provider, nested in the same file, that serves `CardDataFixtureSet` (CS-09-003) with no store connection, with no per-stub latency or failure rate. The in-memory provider also accepts an explicit row list, so a test serves added, reordered or empty rows. It sits in the test sources because `StubRegistration` and `CardDataFixtureSet` are test-source types.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/carddata/CardDataStubServiceTest.java`: the tests of section 2. Loopback cases run with `StubHarness.of(new CardDataStubRegistration())`, or with `StubHarness.of(...)` over a registration whose in-memory provider serves an explicit row list, and a client of the generated `CardDataServiceGrpc` stub on the port the harness reports. Relational cases run the relational provider over test doubles of the three CS-09-002 repositories holding the entity rows the CS-09-003 loaders write, so the unit suite needs no store. Container-mode cases start a Spring Boot test context with `card-servicing.stub.serve=card-data`, `card-servicing.grpc.port=0` and the three repositories replaced by test doubles, or with `card-servicing.stub.serve` empty.
> - Together these are the 4 files touched.
>
> Rules:
>
> - Responses follow `card_data_service.proto` and `card-data-service.yaml` exactly: every field name, every pattern and the omission of an absent `groupId`.
> - An unknown account returns `NOT_FOUND`, and a malformed or all-zero account identifier returns `INVALID_ARGUMENT`, each with one `cardservicing.common.v1.ErrorObject` from `card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto` packed into the `google.rpc.Status` details. The error object carries a `traceId` the stub issues at receipt as a 32-character lower-case hexadecimal value.
> - A provider read failure, the store unreachable or a query error, is answered `UNAVAILABLE` with one packed `ErrorObject`. The stub logs the cause with that `traceId`, and no exception text reaches the caller.
>   > SCOPE-QUESTION (Resolved): What may the stub log about a provider read failure, when a persistence or connection exception's message and causes can carry card numbers, credentials, SQL text or bound values? One diagnostic record and nothing else. For each failed call the stub writes exactly one `WARN` line, `Provider read failed traceId={} operation={} exception={} rootCause={} sqlState={}`, filled only from this allowlist: the call's `traceId`; its full operation name, `cardservicing.carddata.v1.CardDataService/GetAccount`, `cardservicing.carddata.v1.CardDataService/GetCardCrossReference` or `cardservicing.carddata.v1.CardDataService/ListCategoryBalances`; the fully qualified class name of the exception the provider caught; the fully qualified class name of the innermost cause in that exception's chain; and the SQLSTATE of the first `java.sql.SQLException` in the chain when it matches `^[0-9A-Z]{5}$`, otherwise `none`. The line never carries an exception message, a stack trace, a nested cause's text, SQL text, a bound parameter or a request value, the account identifier included, a connection URL, a user name or a password, and the stub passes no exception object to the logger, so no layout can print one. The relational provider writes no log line of its own for the failure and copies no text from the caught exception into its provider read failure, which carries the caught exception only as its cause. The outward answer stays as written: `UNAVAILABLE` with one packed `ErrorObject` and no exception text. No file, dependency or story edge is added, so the 4 files touched stand.
> - The stub ignores the `x-api-key` service key and the forwarded `authorization` metadata and never re-authorizes, so it never answers `UNAUTHENTICATED`, although the contract lists that status for the real service. `DEADLINE_EXCEEDED` arises at the client, never from the stub. No token value reaches the request log or any stub log line.
> - The stub reads only `account`, `card_cross_reference` and `category_balance`. It never opens a Redis connection, never calls Auth or any other service, and makes zero live external calls.
> - `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` apply through `StubFailureInjector`, which `StubServerHost` places in front of the stub, and every call lands in `StubRequestLog` with its full operation name, outcome and duration (CS-11-002). The stub adds no interceptor of its own.
>
> In scope:
>
> - The three operations `GetAccount`, `GetCardCrossReference` and `ListCategoryBalances`, with their ordering and value rendering.
> - Not-found and malformed-identifier behaviour with the packed error object.
> - The provider abstraction with its relational and in-memory implementations.
> - Registration with the test harness and the container-mode hosted stub.
> - Failure injection and request-log entries inherited from the stub hosting of CS-11-002.
>
> Not in this story:
>
> - The Card Data client, its caching and its retries (CS-09-005).
> - The container-backed Card Data integration suite (CS-09-006).
> - The Card Data stub container, its port and its variables in `tests/docker-compose.yml` (CS-01-001).
> - Authorization of the caller (CS-12-003).
> - Stub hosting, failure injection, the request log and the harness mechanics (CS-11-002).
> - The three tables, their repositories and the sample load (CS-09-002, CS-09-003), and the category-balance volume (CS-09-007).
> - Customer-record data (`Data Layouts · CVCUS01Y`), whose screen fields are flagged gaps per the data model.
>
> ### 2. Acceptance Criteria
>
> - AC1: `GetAccount` for `00000000001` returns `OK` with `accountId` `00000000001`, `activeStatus` `Y`, `currentBalance` `194.00`, `creditLimit` `2020.00`, `cashCreditLimit` `1020.00`, `currentCycleCredit` `0.00`, `currentCycleDebit` `0.00`, `openDate` `2014-11-20`, `expirationDate` `2025-05-20`, `reissueDate` `2025-05-20` and `addressZip` `A000000000`. The proto field `group_id` is unset, so the JSON form of the answer carries no `groupId` property.
> - AC2: `GetCardCrossReference` for `00000000001` returns `OK` with exactly one `cardCrossReferences` element: `cardNumber` `9680294154603697`, `customerId` `000000001` and `accountId` `00000000001`.
> - AC3: `ListCategoryBalances` for `00000000001` returns `OK` with exactly one `categoryBalances` element: `accountId` `00000000001`, `typeCode` `01`, `categoryCode` `0001` and `balance` `0.00`.
> - AC4: The three answers for `00000000001`, printed in their JSON form, equal field for field the `fixtureAccount` 200 examples of `card-data-service.yaml`, which the test reads from the classpath and which carry the same values as the `Example response:` blocks of `card_data_service.proto`.
> - AC5: Boundary: `GetCardCrossReference` for `00000000050` returns `OK` with exactly one element, `cardNumber` `0500024453765740` and `customerId` `000000050`, and `GetAccount` for `00000000050` returns `OK` with `accountId` `00000000050`. `GetAccount` for `00000000051` returns `NOT_FOUND`.
> - AC6: For `00000000099`, each of the three operations returns `NOT_FOUND`, and the `google.rpc.Status` details hold exactly one packed `ErrorObject` with `code` `NOT_FOUND`, `message` `Account not found`, a 32-character lower-case hexadecimal `traceId` and an empty `details` list. The request log records each of the three calls with outcome `NOT_FOUND`.
> - AC7: For each of `0000000001A`, `0000000001` (10 digits), `000000000001` (12 digits), the empty string and the all-zero `00000000000`, each of the three operations returns `INVALID_ARGUMENT` with one packed `ErrorObject`: `code` `INVALID_ARGUMENT`, `message` `Account number must be a non zero 11 digit number` and exactly one `details` entry with `field` `accountId` and `description` `must be 11 digits and not all zeros`. The provider is read 0 times for these calls.
> - AC8: With an in-memory provider serving account `00000000077` and, in this order, the category balances `02`/`0001`, `01`/`0002` and `01`/`0001` and the cross-references for cards `9999000000000002` and `9999000000000001`, `ListCategoryBalances` returns `01`/`0001`, `01`/`0002`, `02`/`0001` and `GetCardCrossReference` returns `9999000000000001` then `9999000000000002`. For account `00000000078`, served with an account row and no cross-reference or category-balance row, both operations return `OK` with an empty list.
> - AC9: With an in-memory provider serving account `00000000079` with current balance `-12.5`, credit limit `0` and group ID `GRP0000001`, `GetAccount` returns `currentBalance` `-12.50`, `creditLimit` `0.00` and `group_id` set to `GRP0000001`, and every amount in AC1–AC3 and here matches `^-?[0-9]+\.[0-9]{2}$`.
> - AC10: With a per-test failure-rate override of 1.0 (`STUB_FAILURE_RATE`) on stub `card-data`, 20 of 20 calls of each operation return `UNAVAILABLE` and the provider is read 0 times. The request log holds 60 entries, each with operation `cardservicing.carddata.v1.CardDataService/GetAccount`, `cardservicing.carddata.v1.CardDataService/GetCardCrossReference` or `cardservicing.carddata.v1.CardDataService/ListCategoryBalances`, outcome `UNAVAILABLE` and a duration in ms.
> - AC11: With a per-test latency override of 400 ms (`STUB_LATENCY_MS`) on stub `card-data`, each answer reaches the client no earlier than 400 ms after sending and still carries the AC1, AC2 or AC3 values, and its request-log entry shows a duration of at least 400 ms.
> - AC12: With no Redis server reachable and no Auth stub registered, calls of each operation for `00000000001` with no `x-api-key` and no `authorization` metadata, with `x-api-key` empty, and with `x-api-key` set to `wrong` receive identical answers: `OK` with the AC1, AC2 and AC3 values. No call answers `UNAUTHENTICATED`.
> - AC13: With the relational provider's store unreachable, each operation for `00000000001` returns `UNAVAILABLE`, and the `google.rpc.Status` details hold exactly one packed `ErrorObject` with `code` `UNAVAILABLE`, `message` `Card data store unavailable`, a 32-character lower-case hexadecimal `traceId` and an empty `details` list. The request log records each call with outcome `UNAVAILABLE`, and no exception text reaches the client.
>   > SCOPE-QUESTION (Resolved): AC13 also proves the logging limit that the note beneath the provider-read-failure rule sets. For each of the three operations, the relational provider runs over repository test doubles that throw a persistence exception whose message carries sentinel values the test generates at runtime: a 16-digit card number, a bearer token, an `x-api-key` value, a password and a SQL-shaped fragment naming account `00000000001`. Its cause is a `java.sql.SQLException` with SQLSTATE `08001` whose message carries the same sentinels again. Every log event the test JVM writes is captured on the root logger at `DEBUG` from the call's start until its answer arrives, each event as its formatted message plus the rendering of any attached exception. The captured text holds none of the sentinels, and exactly one captured line, at `WARN`, holds the call's `traceId`, equal to the `traceId` of the packed `ErrorObject`, together with the operation name, the class names of the thrown exception and of its `java.sql.SQLException` cause, and `08001`. The answer stays as AC13 states.
> - AC14: For `00000000001`, `00000000050` and `00000000099`, the in-memory provider over `CardDataFixtureSet` and the relational provider over the rows the CS-09-003 loaders write give identical answers to each of the three operations, field for field and status for status.
>   > SCOPE-QUESTION (Resolved): The `00000000099` answers are `NOT_FOUND` errors whose packed `ErrorObject` carries the `traceId` the stub issues at that call's receipt, so two separate calls never share one and a literal comparison of the error objects cannot pass. AC14 therefore reads as follows. For `00000000001` and `00000000050`, each operation's two answers, one through each provider, have the same status, `OK`, and equal response messages field for field, presence of `group_id` included. For `00000000099`, each operation's two answers have the same status, `NOT_FOUND`, and their packed `ErrorObject` values are equal in `code`, `message` and `details`. The `traceId` values are left out of the equality comparison: each is checked on its own as a 32-character lower-case hexadecimal value, as AC6 requires, and the two values differ, because each call receives its own. No `traceId` is reused or fixed in advance to make the comparison pass.
> - AC15: `StubHarness.allRegistered()` starts the Card Data stub under stub name `card-data` on a `127.0.0.1` port it reports, and a client on that port receives the AC1 answer. A context started with `card-servicing.stub.serve` empty holds no `CardDataStubService` bean, no provider bean and no hosted stub named `card-data`.
> - AC16: A context started with `card-servicing.stub.serve=card-data` holds the hosted stub named `card-data`, served through the relational provider: account `10000000001` with one cross-reference and ten category balances, present only in the three repositories and absent from `CardDataFixtureSet`, is returned by the next calls of the three operations with no stub restart, the ten balances ordered by `typeCode`, then `categoryCode`, ascending.
>
> ### 3. Dependencies
>
> - CS-09-003 — loads the sample accounts, cross-references and category balances and supplies the in-memory fixture set the stub serves.
> - CS-11-002 — provides stub hosting, failure injection, the request log and the stub registration lifecycle.
>
> Risk: A stub that drifts from its contract hides client defects, so every answer is compared with the contract examples.

## 4. Design

- Diagrams: [Logical architecture](../../../../architecture/logical-architecture.mmd), [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- Data model: [Account](../../../../data-model.md#account), [Card cross reference](../../../../data-model.md#card_cross_reference), [Category balance](../../../../data-model.md#category_balance)
- Technical requirements: [D3 Stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point), [External systems](../../../../technical-requirements.md#external-systems), [Resilience: failure injection and request log](../../../../technical-requirements.md#resilience)
- Contracts: [Card Data proto](../../../../../card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto), [Card Data OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/card-data-service.yaml), [Shared error contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; pass when all tests pass with ≥80% line coverage per module. `CardDataStubServiceTest` covers AC1–AC16 on the loopback harness and in test contexts: the three operations and their contract examples, the boundary accounts, not-found, malformed identifiers, ordering and empty lists, value rendering, failure rate 1.0 and the 400 ms latency with their request-log entries, service-key indifference, the unreachable store with the sentinel log capture that the note beneath AC13 sets, provider equality as the note beneath AC14 compares it, with each `traceId` checked on its own, discovery and the container-mode relational path.
- Integration: Applies, through the Card Data integration suite CS-09-006, which runs the Card Data client against this stub over the sample rows in the `postgres:16.6` container and repeats the AC14 comparison, as the note beneath AC14 sets it, against the stored rows, with each `traceId` checked on its own. Command `./gradlew :card-servicing-service:integrationTest`; pass when all suites pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap, each injected on this stub for CS-09-006.
- Performance: Not applicable — the stub adds no UI-facing endpoint; it is measured through the endpoint scenarios CS-01-005 and CS-02-002. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable here — this story owns no UI interaction step; the end-to-end step CS-01-004 reads this stub's request log in the Compose stack. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A, one provider contract with relational and in-memory implementations.

Rationale: unit suites need no database while containers serve loaded rows.

- Rejected: Option B, in-memory data everywhere, which never serves volume rows.
- Rejected: direct provider calls, because the contract goes unexercised.

### Shared by both options

- Topology: the stub runs on a loopback port or in its own container.
- Naming: the operations keep their contract names.
- Addressing: the host assigns and reports the port.
- Isolation: only account, cross-reference and category balance data; no cache or access check.
- Fallback: none, because the stub is the source of record.
- Provisioning: the in-memory fixture set comes from the sample files.
- Cut-over: replacing the stub changes only the client's address and credential source.
- Failures: unknown accounts answer not found, and the hosting story injects faults.

### Design Option A — two providers

One provider contract feeds the stub. Stub hosts read the loaded tables; unit suites read the fixtures.

How it behaves:

- Malformed identifiers fail before any provider read.
- The account lookup decides not found, so empty accounts answer empty lists.
- A store read failure logs only the trace identifier, operation and failure kind, never its text.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Provider contract (vs two stubs) | one stub on every host | providers must match |
| 2 | Tables in containers (vs fixtures) | volume rows arrive | containers need a store |
| 3 | Shared fixtures (vs hand-written) | answers match the load | fixture edits move tests |
| 4 | Not found by lookup (vs empty list) | empty lists stay valid | list reads add a lookup |

Effort and risk: low effort, with provider drift as the risk.

### Design Option B — in-memory everywhere

The stub serves the in-memory fixture set on every host. No relational provider exists.

How it differs:

- Volume rows never reach the stub.
- Test data ships in the service.
- A store outage cannot be reproduced.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Fixtures everywhere (vs tables) | no store connection | no volume rows |
| 2 | Fixtures in the service (vs tests) | one data path | service carries test data |
| 3 | Not found by lookup (vs empty list) | empty lists stay valid | only sample rows test it |

Effort and risk: lower effort, with high risk of sample-only measurement.

### Recommendation

- Option: Option A.
- Why: containers serve whatever the fixture load wrote.
- Strongest argument against: only the equality test keeps the providers equal.
- Shared items that do not decide the choice: the contract, not-found rule and failure injection.
- Flip conditions: no scenario reads volume rows through the stub.

## Design Validation

### Option A validation

Settled assumptions: the contract fixes every field and status, each sample account has one card and one balance, and the hosting story supplies injection and the request log.

- Open → whether malformed identifiers reach the stub → settled by the client story.
- Open → relational read time at volume → settled by the integration suite.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | fixture values match |
| AC2 | Holds | sample holds this card |
| AC3 | Holds | sample holds this balance |
| AC4 | Holds | examples carry sample values |
| AC5 | Holds | fixture holds fifty accounts |
| AC6 | Holds | lookup finds no row |
| AC7 | Holds | validation precedes provider reads |
| AC8 | Holds | stub sorts rows itself |
| AC9 | Holds | stub writes two decimals |
| AC10 | Holds | injector answers first |
| AC11 | Holds | injector delays the answer |
| AC12 | Holds | no metadata or cache is read |
| AC13 | Holds | read failures answer unavailable without logged text |
| AC14 | Conditional | equality rests on shared sample files |
| AC15 | Holds | harness finds the registration |
| AC16 | Holds | provider reads tables per call |

### Option B validation

Settled assumptions: the contract fixes every field and status, and the hosting story supplies injection and the request log.

- Open → whether scenarios need non-sample rows → settled by the volume scenario.
- Open → shipping test data in the service → settled by design review.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | fixture values match |
| AC2 | Holds | sample holds this card |
| AC3 | Holds | sample holds this balance |
| AC4 | Holds | examples carry sample values |
| AC5 | Holds | fixture holds fifty accounts |
| AC6 | Holds | lookup finds no row |
| AC7 | Holds | validation precedes provider reads |
| AC8 | Holds | stub sorts rows itself |
| AC9 | Holds | stub writes two decimals |
| AC10 | Holds | injector answers first |
| AC11 | Holds | injector delays the answer |
| AC12 | Holds | no metadata or cache is read |
| AC13 | Breaks | no store read exists |
| AC14 | Breaks | no relational provider exists |
| AC15 | Holds | registration ignores the source |
| AC16 | Breaks | relational-only rows never arrive |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-09-003, CS-11-002 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Card Data Integration |
| Epic Link | CS-09 |
| Story Points | 3 |
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
