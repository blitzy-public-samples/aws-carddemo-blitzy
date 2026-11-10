# CS-10-005: Transaction Activity stub single transaction lookup

Parent epic: [CS-10 Transaction Activity Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want the mocked Transaction Activity service to return one transaction by identifier, so that the detail drill-down is testable.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN01C
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: the Transaction Activity stub of CS-10-004 answers `GetTransaction` of `cardservicing.transactionactivity.v1.TransactionActivityService` from `card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto`, the operation CS-10-004 leaves at the generated `UNIMPLEMENTED` answer. The stub looks the transaction up by `transaction_id` alone on the `transaction_record` primary key `(transaction_id)`: through Spring Data JPA over `transaction_record` in a process hosting the stub, or over the in-memory fixture set in unit suites, as `Use Cases · COTRN01C` reads the transaction file by TRAN-ID alone. It returns all 13 `Transaction` fields of `Data Layouts · CVTRA05Y` in the listing's string forms: amounts as scale-2 decimal strings matching `^-?[0-9]{1,9}\.[0-9]{2}$`, both timestamps in RFC 3339 with microseconds and `Z`, identifiers, codes and the card number with their leading zeros, and free text with trailing padding removed. An unknown identifier returns `NOT_FOUND`. The stub performs no ownership check, following `Use Cases · COTRN01C`: the request carries no account, and the detail ownership story CS-08-002 decides in the Card Servicing service whether the transaction's card belongs to the path account. Failure injection and request-log entries reach the lookup through the stub hosting of CS-11-002 exactly as they reach the listing of CS-10-004. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/transactionactivity/TransactionActivityStub.java` (edit: add the lookup operation):
>   - Implements `GetTransaction`. Before any read it checks `transaction_id` against the contract pattern `^[\s\S]{16}$`, exactly 16 characters of any kind. A failed check answers `INVALID_ARGUMENT` with the message `Transaction ID must be exactly 16 characters` and exactly one `details` entry with field `transactionId` and description `must be exactly 16 characters`.
>   - Reads through a one-method lookup source nested in the stub, `TransactionActivityStub.TransactionLookup`, which takes the transaction ID, returns the row whose primary key equals it in the listing provider's row form or no row, and raises the listing provider's read failure when the store fails. The stub's constructor takes the lookup source beside the listing provider, and the CS-10-004 registration and the stub's nested configuration class are its only callers.
>   - A found row answers `OK` with `transaction` set, rendered by the row rendering the listing already uses. No row answers `NOT_FOUND` with the message `Transaction not found`. A read failure, the store unreachable or a query error, answers `UNAVAILABLE` with the message `Transaction activity store unavailable`; the stub logs the cause with the call's `traceId`, and no exception text reaches the caller.
>   - Every non-OK answer packs exactly one `cardservicing.common.v1.ErrorObject` into the `google.rpc.Status` details, as the listing does: the status name as `code`, the message, the `traceId` the stub issues at receipt as a 32-character lower-case hexadecimal value, and the `details` entry above or an empty list.
>   - The nested configuration class, active only when `card-servicing.stub.serve` is `transaction-activity`, also builds the relational lookup source: one primary-key read of the `TransactionRecordEntity` of CS-10-001 through the same JPA `EntityManager` the relational listing provider uses, mapping the entity to the row form and turning any persistence or connection exception into the read failure. Using that `EntityManager`, not a further repository bean, keeps the CS-10-004 container-mode test context, which replaces the `EntityManager` with a test double, starting unchanged. With `card-servicing.stub.serve` empty, no lookup source is built and no store connection is opened for it.
>   - Reads the lookup source on every call and keeps no copy of the row between calls.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/transactionactivity/TransactionActivityStubRegistration.java` (edit: give the in-memory fixture set the lookup): the in-memory provider nested in the CS-10-004 registration also implements the lookup source over the rows it already serves. By default these are the 300 sample transactions. When a test supplies an explicit row list it serves that list, and when a test makes it fail every read every lookup raises the read failure. The registration passes that one object to the stub as both listing provider and lookup source. Its stub name `transaction-activity` and its absent per-stub latency and failure rate stay unchanged, so `StubHarness.allRegistered()` serves the lookup with no further registration file.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/transactionactivity/TransactionActivityStubLookupTest.java` (new): the tests of section 2. Loopback cases run with `StubHarness.of(new TransactionActivityStubRegistration())`, or with `StubHarness.of(...)` over a registration whose in-memory provider fails every read, and a client of the generated `TransactionActivityServiceGrpc` stub on the port the harness reports. The relational case starts a Spring Boot test context with `card-servicing.stub.serve=transaction-activity`, `card-servicing.grpc.port=0` and the JPA `EntityManager` replaced by a test double that returns the AC1 entity for `0000000776200014` and no entity for any other identifier.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/transactionactivity/TransactionMockDataProvider.java` stays unchanged: the in-memory provider lives in the registration file, so a lookup method on the provider abstraction would also reopen this file and its relational listing query, one file more than this story's estimate, for a read the listing never makes.
> - Together these are the 3 files touched.
>
> Rules:
>
> - Answers follow `transaction_activity_service.proto` and `transaction-activity-service.yaml` exactly: every field name, every pattern, every string form and the messages above.
> - The stub ignores the `x-api-key` service key and the forwarded `authorization` metadata and never re-authorizes, so it never answers `UNAUTHENTICATED`, although the contract lists that status for the real service. `DEADLINE_EXCEEDED` arises at the client, never from the stub. No token value reaches the request log or any stub log line.
> - The lookup reads only `transaction_record`. It reads no `card_cross_reference` row, never opens a Redis connection, never calls Auth, Card Data or any other service, and makes zero live external calls.
> - `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` apply through `StubFailureInjector`, which `StubServerHost` places in front of the stub, and every call lands in `StubRequestLog` with the full operation name `cardservicing.transactionactivity.v1.TransactionActivityService/GetTransaction`, its outcome and its duration in ms (CS-11-002). The stub adds no interceptor of its own.
>
> In scope:
>
> - The `GetTransaction` operation with its identifier check, its `NOT_FOUND` answer and its store-failure answer, each with the packed error object.
> - The lookup source with its relational primary-key read and its in-memory twin over the registration's rows.
> - Failure injection and request-log entries for the lookup, inherited from the stub hosting of CS-11-002.
> - The lookup tests.
>
> Not in this story:
>
> - Ownership of the transaction against the path account (CS-08-002).
> - The Transaction Activity client, its call policy and its cache (CS-10-006).
> - `ListTransactions`, the listing provider and its keyset query (CS-10-004).
> - The endpoint's `transactionId` validation (CS-08-001).
> - The `transaction_record` table, its entity and its repository (CS-10-001), and the sample load (CS-10-002).
> - The container-backed Transaction Activity integration suite (CS-10-007) and the stub container in `tests/docker-compose.yml` (CS-01-001).
> - Stub hosting, failure injection, the request log and the harness mechanics (CS-11-002).
>
> ### 2. Acceptance Criteria
>
> - AC1: Looking up `0000000776200014` returns type `01`, category `0001`, source `POS TERM`, description `Purchase at Lebsack and Sons`, amount `"985.22"`, merchant ID `800000000`, merchant name `Lebsack and Sons`, merchant city `Otisbury`, merchant zip `32545`, card number `9680294154603697`, and original and processed timestamps both `"2022-06-10T19:27:53.000000Z"`. The answer equals, field for field, the `fixtureTransaction` 200 example of `transaction-activity-service.yaml`, which the test reads from the classpath, both from the registration's small fixture set and through the relational lookup source in a context started with `card-servicing.stub.serve=transaction-activity`.
> - AC2: Looking up `0000000532120892` returns amount `"-70.77"` with type `03` and category `0001`.
> - AC3: Looking up `0000000824152956`, which belongs to account `00000000002`, returns that record with card number `0923877193247330` and amount `"594.77"`, because the request carries no account and the stub performs no ownership check.
> - AC4: Looking up `9999999999999999` returns `NOT_FOUND` with one packed `ErrorObject`: `code` `NOT_FOUND`, `message` `Transaction not found`, a 32-character lower-case hexadecimal `traceId` and an empty `details` list. Looking up the 15-character `000000077620001` returns `INVALID_ARGUMENT` with one packed `ErrorObject` holding exactly one `details` entry whose `field` is `transactionId`, and the lookup source is read 0 times for that call.
> - AC5: With a per-test failure-rate override of 1 (`STUB_FAILURE_RATE`) on stub `transaction-activity`, 20 of 20 lookups of `0000000776200014` return `UNAVAILABLE` and the lookup source is read 0 times. With the in-memory provider failing every read, a lookup of `0000000776200014` returns `UNAVAILABLE` with one packed `ErrorObject` whose `message` is `Transaction activity store unavailable`, and no exception text reaches the client.
> - AC6: Every lookup adds exactly one request-log entry holding the operation `cardservicing.transactionactivity.v1.TransactionActivityService/GetTransaction`, the outcome status name and the call duration in milliseconds. The AC1 lookup followed by the `9999999999999999` lookup of AC4 produces exactly 2 entries with outcomes `OK` and `NOT_FOUND` in call order.
>
> ### 3. Dependencies
>
> - CS-10-004 — provides the stub, the mock-data provider and the registration this story extends.
>
> Risk: adding an account check here would duplicate the ownership rule.

## 4. Design

- Diagram: [Sequence — transaction detail](../../../../architecture/sequence-transaction-detail.mmd)
- Data model: [transaction_record](../../../../data-model.md#transaction_record)
- TRD: [D3 Stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point), [External systems](../../../../technical-requirements.md#external-systems), [Resilience: failure injection and request log](../../../../technical-requirements.md#resilience)
- Contracts: [transaction_activity_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto), [transaction-activity-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml), [common.proto](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies: `TransactionActivityStubLookupTest` covers AC1–AC6 on the loopback harness over the small fixture set and in a test context over the relational lookup source: the sample purchase against the contract example, the signed return amount, the other account's transaction, the unknown and the malformed identifier, failure rate 1 and the failing store, and request-log entries in call order. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module.
- Integration — Applies, through the Transaction Activity integration suite CS-10-007, which runs this lookup's primary-key read over the sample rows in the `postgres:16.6` container database. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic (CS-10-007 for this epic) covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — detail latency is measured at the endpoint by the transaction detail SLA scenario CS-04-002. Where it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms; applies to balance-retrieval and drill-down stories.
- End-to-end — Not applicable — the detail step is owned by CS-04-001, which reaches this lookup in the Compose stack. Where it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — primary-key lookup through its own lookup source.

Rationale: the transaction identifier is the table's primary key, so one key read answers every lookup whatever the volume, and a one-question lookup source beside the listing provider leaves the listing query and its tests as they stand.

- Rejected: Design Option B, serving the lookup from the listing query — it reaches the same primary-key read only by widening the tested listing query and skipping its account check.
- Rejected: a lookup question added to the listing provider itself — it reopens the tested listing query for a read the listing never makes.

### Shared by both options

- Topology: the lookup runs in the same stub as the listing, on a loopback port inside the test process for unit and integration suites and alone as a container in the shared stack.
- Naming: the operation and its fields follow the remote procedure interface, and the request log names each call by its full operation name.
- Addressing: each lookup names the transaction identifier alone, with no account.
- Isolation: the stub performs no ownership check, keeps no cache and never re-authorizes, so the service key and the caller token change no answer.
- Fallback: none inside the stub, so a store failure surfaces to the client as the unavailable status.
- Provisioning: rows come from the fixture loader in the shared stack and from the small fixture set in unit suites.
- Cut-over: the real service replaces the stub by target address and credential source only.
- Failures: injected latency and unavailable answers come from the shared failure injector, a malformed identifier answers the invalid-argument status, an unknown identifier answers the not-found status, and every call lands in the request log.

### Design Option A — primary-key lookup through its own lookup source

The stub checks the identifier against the contract, then asks a one-question lookup source for the row whose primary key equals it. In a process hosting the stub the source reads the transaction table by primary key on every call, and in unit suites the in-memory fixture set answers the same question over the same rows. A found row is rendered in the listing's string forms, and an absent row answers the not-found status.

How it behaves:

- Every lookup costs one primary-key read, whatever the table volume.
- A transaction of any account is returned, because the request names no account.
- The answer carries all thirteen record fields, as the detail screen shows them.
- The listing provider and its query stay as the listing stub story left them.
- A store failure becomes the unavailable status with the standard error object, and no exception text reaches the caller.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Lookup by primary key (vs through the listing query) | the read asks only the lookup's question | a second read path beside the listing |
| 2 | Lookup source beside the listing provider (vs a question added to the listing provider) | the tested listing query stays untouched | relational read logic sits in two places |
| 3 | No ownership check in the stub (vs checking the card's account) | one owner for the ownership rule | the stub returns any account's transaction |
| 4 | All record fields returned (vs a summary subset) | the detail view needs every field | a larger message |

Effort and risk: small effort, low risk.

### Design Option B — listing query filtered to one identifier

The lookup runs the account listing query with one more equality on the transaction identifier and a page size of one, and drops the account condition because the lookup request carries no account. The identifier equality still reaches the primary key, so each lookup costs the same one key read as Option A. The cost falls on the listing instead, whose relational query, in-memory twin and account check all change for a read the listing never makes. The identifier check, the field set, the not-found rule and the failure answers stay as in Option A.

How it differs:

- One query path serves both operations, and a lookup still costs one primary-key read.
- The listing provider's contract widens to accept a missing account, in the relational query and in its in-memory twin alike.
- The listing's account check against the cross-reference record is skipped for a lookup, because the lookup reads only the transaction table.
- The newest-first order, the lookahead row and the page token do nothing for a one-row read.
- An empty result answers the not-found status for a lookup, while the same empty result answers an empty page for a listing.
- The tested listing query and the provider reopen, one file more than this story otherwise touches.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Listing query filtered to one identifier (vs a primary-key read of its own) | one relational read path to maintain | the tested listing query and its provider reopen for a one-row read |
| 2 | Account condition and account check skipped when the account is absent (vs an account-scoped listing only) | the lookup still reads only the transaction table | the listing provider widens to accept a missing account in both the query and its in-memory twin |
| 3 | No ownership check in the stub (vs checking the card's account) | one owner for the ownership rule | the stub returns any account's transaction |

Effort and risk: small effort, medium risk to the tested listing query, its in-memory twin and its account check, which all change for a one-row read.

### Recommendation

- Option: Design Option A
- Why: both options cost one primary-key read, and Option A gets it without widening the listing query, skipping the listing's account check or reopening the provider.
- Strongest argument against: the stub grows a second relational read path, with its own row mapping, to maintain beside the listing provider.
- Shared items that do not decide the choice: the one-key-read cost, failure injection, the request log, the field set, the identifier check and the not-found rule.
- Flip conditions: a later story making the listing query, its in-memory twin and its account check accept a missing account for its own use, which leaves Option B's single read path as the only difference.

## Design Validation

### Option A

Settled assumptions:

- The transaction identifier is the transaction table's primary key, so one key read finds at most one row.
- The detail ownership story decides whether the transaction belongs to the path account.
- Stubs never re-authorize, so no caller identity reaches the lookup.
- The stub hosting story places the failure injector and the request log in front of every stub.
- The fixture load sets the processed timestamp to the original one when the source value is blank.

Open assumptions:

- Whether the real service enforces ownership itself Open → the swap to the real Transaction Activity service.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the fixture set and the stored row carry the sample values |
| AC2 | Holds | the amount renders at scale two with its sign |
| AC3 | Holds | no ownership check stands between the lookup and another account's row |
| AC4 | Holds | the identifier check precedes a key read with no match |
| AC5 | Holds | the shared failure injector and the read-failure mapping both answer the unavailable status |
| AC6 | Holds | the shared request log records every call |

### Option B

Settled assumptions:

- The transaction identifier is the transaction table's primary key, so the identifier equality finds at most one row in one key read with or without the account condition.
- The lookup request carries no account, so the lookup drops the listing's account condition and skips its account check.
- The listing's in-memory twin applies the same conditions as its relational query, so both widen together.
- The stub hosting story places the failure injector and the request log in front of every stub.

Open assumptions:

- Whether the listing tests pass unchanged once its query, its in-memory twin and its account check accept a missing account Open → the listing story's tests run over the widened provider.
- Whether the real service enforces ownership itself Open → the swap to the real Transaction Activity service.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | its relational half needs the test context to answer the widened listing query, not a key read |
| AC2 | Holds | the listing's row rendering signs the amount at scale two |
| AC3 | Holds | the dropped account condition reaches another account's row |
| AC4 | Holds | the identifier check precedes an empty result mapped to the not-found status |
| AC5 | Holds | the shared failure injector and the read-failure mapping both answer the unavailable status |
| AC6 | Holds | the shared request log records every call |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-10-004 |
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
