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
>     > SCOPE-QUESTION (Resolved): What may the stub log about a read failure, when a persistence or connection exception's message and causes can carry card numbers, credentials, SQL text or bound values? One diagnostic record and nothing else: exactly one `WARN` line per failed call, `Provider read failed traceId={} operation={} exception={} rootCause={} sqlState={}`, the template the listing uses (CS-10-004), filled only from this allowlist: the call's `traceId`; the full operation name `cardservicing.transactionactivity.v1.TransactionActivityService/GetTransaction`; the fully qualified class name of the exception the lookup source caught; the fully qualified class name of the innermost cause in that exception's chain; and the SQLSTATE of the first `java.sql.SQLException` in the chain when it matches `^[0-9A-Z]{5}$`, otherwise `none`. When the read failure carries no caught exception, as the in-memory provider's does, both class names are the read failure's own. The line never carries an exception message, a stack trace, a nested cause's text, SQL text, a bound parameter or a request value, the transaction ID included, a connection URL, a user name or a password, and the stub passes no exception object to the logger. The lookup source writes no log line of its own and copies no text from the caught exception into its read failure, which carries the caught exception only as its cause. The outward answer stays as written. No file, dependency or story edge is added, so the 3 files touched stand.
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
>   > SCOPE-QUESTION (Resolved): AC5 also covers what a failing store leaves in the logs. In the relational case the `EntityManager` test double's primary-key find throws a persistence exception whose message carries runtime-generated sentinels (a 16-digit card number, a bearer token, an `x-api-key` value, a password and a SQL-shaped fragment naming transaction `0000000776200014`), with a `java.sql.SQLException` cause of SQLSTATE `08001` that carries them again. The lookup returns `UNAVAILABLE` with the AC5 error object. With every log event captured on the root logger at `DEBUG` from the call's start until its answer arrives, the captured text holds none of the sentinels, and exactly one line holds the call's `traceId`, equal to the packed `ErrorObject` `traceId`, the operation name, both class names and `08001`, and that line holds no `0000000776200014`. The in-memory provider failing every read logs `sqlState=none`.
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

- Unit — Applies: `TransactionActivityStubLookupTest` covers AC1–AC6 on the loopback harness over the small fixture set and in a test context over the relational lookup source: the sample purchase against the contract example, the signed return amount, the other account's transaction, the unknown and the malformed identifier, failure rate 1 and the failing store, the sentinel capture that finds none of the failing store's card, token, key, password or SQL sentinels in the root-logger output and exactly one allowlisted diagnostic line, and request-log entries in call order. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module.
- Integration — Applies, through the Transaction Activity integration suite CS-10-007, which runs this lookup's primary-key read over the sample rows in the `postgres:16.6` container database. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic (CS-10-007 for this epic) covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — detail latency is measured at the endpoint by the transaction detail SLA scenario CS-04-002. Where it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms; applies to balance-retrieval and drill-down stories.
- End-to-end — Not applicable — the detail step is owned by CS-04-001, which reaches this lookup in the Compose stack. Where it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — own primary-key lookup source.

Rationale: one primary-key read answers each lookup and leaves the tested listing query alone.

- Rejected: Design Option B — it widens the tested listing query and skips its account check.
- Rejected: a lookup question on the listing provider — it reopens the tested listing query.

### Shared by both options

- Topology: the listing's stub, in-process in suites and containerized in the shared stack.
- Naming: the request log names each call by its full operation name.
- Addressing: a lookup names the transaction identifier alone.
- Isolation: the stub checks no ownership, caches nothing and never re-authorizes.
- Fallback: none, so a store failure answers unavailable, and the stub logs only the trace identifier, the operation and the failure's kind, never its text.
- Provisioning: the fixture loader in the shared stack, the small fixture set in unit suites.
- Cut-over: the real service replaces the stub by address and credential source only.
- Failures: injected delay or outage, plus the invalid-argument and not-found answers.

### Design Option A — own primary-key lookup source

The stub checks the identifier, then asks its own lookup source for the row with that primary key. The source reads the table on every call, or the fixture set in unit suites.

How it behaves:

- Each lookup costs one key read at any volume.
- Any account's transaction returns with all thirteen fields.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Key read (vs the listing query) | reads only one row | a second read path |
| 2 | Own source (vs a provider question) | listing untouched | two row mappings |
| 3 | No ownership check (vs account check) | one rule owner | any account's row |
| 4 | All fields (vs a subset) | the view needs all | a larger message |

Effort and risk: small effort, low risk.

### Design Option B — filtered listing query

The lookup runs the listing query with an identifier equality, a page size of one and no account condition. The equality still hits the primary key, so a lookup costs one key read.

How it differs:

- Order, lookahead row and page token do nothing for one row.
- An empty result means not found here but an empty page in a listing.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Filtered listing (vs own key read) | one read path | tested query reopens |
| 2 | Account optional (vs required) | one table read | query and twin widen |
| 3 | No ownership check (vs account check) | one rule owner | any account's row |

Effort and risk: small effort, medium risk to the tested listing query.

### Recommendation

- Option: Design Option A
- Why: it gets the same key read without reopening the listing query or skipping its account check.
- Strongest argument against: a second relational read path beside the listing provider.
- Shared items that do not decide the choice: read cost, failures, logs and fields.
- Flip conditions: a later story making the listing accept no account for its own use.

## Design Validation

### Option A

Settled assumptions: the identifier is the primary key, the detail story checks ownership, the hosting story supplies the injector and request log, and the load fills blank processed timestamps.

- Whether the real service enforces ownership Open → the swap to the real service.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | both row sources carry the sample values |
| AC2 | Holds | the amount renders signed at scale two |
| AC3 | Holds | no ownership check blocks another account's row |
| AC4 | Holds | the identifier check precedes an unmatched key read |
| AC5 | Holds | the injector and failure mapping answer unavailable |
| AC6 | Holds | the shared request log records every call |

### Option B

Settled assumptions: the identifier equality finds one row in one key read, the in-memory twin widens with the query, and the hosting story supplies the injector and request log.

- Whether the listing tests pass over the widened provider Open → the listing tests run over it.
- Whether the real service enforces ownership Open → the swap to the real service.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the relational test context must answer the widened query |
| AC2 | Holds | the listing's rendering signs the amount at scale two |
| AC3 | Holds | the dropped account condition reaches any account's row |
| AC4 | Holds | an empty result maps to not found |
| AC5 | Conditional | the failing test double must answer the widened listing query |
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
