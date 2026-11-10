# CS-09-005: Card Data client with account and category balance caching

Parent epic: [CS-09 Card Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want account and category-balance reads cached with a stale fallback, so that a short Card Data outage still shows my balances.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVACT01Y · ACCT-CURR-BAL
>
> Account-data access path: No
>
> > SCOPE-QUESTION (Resolved): Should this client carry the account-data classification? This client is the service's only path to account, cross-reference and category-balance records, so every account-data read of the request path runs through it, and the line resolves to `Account-data access path: Yes`. The client does not duplicate authorization: the account access guard (CS-12-003) allows access before any caller invokes the client, and AC15 states the constraint.
>
> External integration or SLA-bound: Yes
>
> Capability: one Card Data client is the service's only path to account records (`Data Layouts · CVACT01Y`), card cross-references (`Data Layouts · CVACT03Y`) and category balances (`Data Layouts · CVTRA01Y`). It serves the Stage 3 reads of the request path: `GetAccount` and `ListCategoryBalances` run concurrently for the balance summary, `ListCategoryBalances` serves category balances, and `GetCardCrossReference` serves the ownership check of the transaction detail. Account and category-balance reads are cached with a stale fallback, and the cross-reference read is never cached. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/carddata/CardDataClient.java`:
>   - Wraps the blocking stub that grpc-java generates for `cardservicing.carddata.v1.CardDataService` in the Java package `com.example.cardservicing.contract.carddata.v1` from `card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto`, generated under the build directory and never checked in.
>   - `account(token, deadline, accountId)`, `categoryBalances(token, deadline, accountId)` and `cardCrossReferences(token, deadline, accountId)` each submit one read to the client's bounded worker pool and return a `java.util.concurrent.CompletableFuture`, so an endpoint starts its Stage 3 reads concurrently and one instance serves concurrent requests. A failed read completes the future exceptionally with the final `io.grpc.StatusRuntimeException`, its status and packed `ErrorObject` unchanged.
>   - `GetAccount` and `ListCategoryBalances` run through `UpstreamCallPolicy` and `RequestDeadline` (CS-11-003) inside `ReadThroughCache` (CS-11-004): the upstream read the cache receives is one policy-wrapped call within the request's `RequestDeadline`, and the cache's time-left supplier reports that deadline's remaining budget. `account` completes with `ReadThroughCache.CachedValue<Account>` and `categoryBalances` with `ReadThroughCache.CachedValue<List<CategoryBalance>>`, each holding the value, its `cachedAt` and a freshness of `FRESH` or `STALE`.
>   - `GetCardCrossReference` runs through the call policy only: uncached, with no stale fallback. `cardCrossReferences` completes with the `card_cross_references` list in the stub's order and never sends a cache command.
>   - Each call adds the forwarded bearer token as `authorization: Bearer <caller token>` and `x-api-key` with the value of `card-servicing.auth.api-key` from `card-servicing-service/src/main/java/com/example/cardservicing/config/CardServicingProperties.java` (CS-11-001), bound from `AUTH_SERVICE_API_KEY`: an empty placeholder in the stub phase, sent as an empty value, and a Key Vault secret when deployed (CS-12-004). The headers are attached per call, so the client holds no token between calls.
>   - Adds no attempt, timeout, retry, backoff or freshness rule of its own, and holds no store connection and no Redis client: Redis is reached only through `ReadThroughCache`, and account data only through this gRPC client.
>   - Writes one warning line per stale result or failed read naming the operation, the account identifier and the final gRPC status code. No line carries the token, the service credential or any other call metadata.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/carddata/CardDataChannelConfiguration.java`:
>   - Builds one plaintext gRPC channel per service instance to the Card Data target address, and the blocking stub over it. The channel connects on first use, so a context starts with no Card Data service reachable, and the channel and the worker pool shut down when the application context closes.
>   - Binds the target address from `card-servicing.clients.card-data.target` through a `@ConfigurationProperties` record nested in this file.
>   - Builds the bounded worker pool the blocking calls run on: 32 daemon threads and a queue of 64 waiting reads, both constants of this class and never configuration entries. A read submitted while 32 reads run and 64 wait completes at once with `UNAVAILABLE` and sends no stub call and no cache command.
>   - Builds the two `ReadThroughCache.KeyFamily` values of the rules below, each with a codec that writes the generated `GetAccountResponse` or `ListCategoryBalancesResponse` as protobuf bytes.
>   - Holds the gRPC Netty transport's client frame logger `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` at INFO through Spring Boot's `LoggingSystem` when it starts, and installs, before it builds its channel, a nested `CardDataChannelConfiguration.ClientFrameLogGuard` built the same way as the client frame-log guards of CS-12-002, CS-11-006 and CS-13-003, because at DEBUG that shared logger writes every outbound header value, `authorization` and `x-api-key` included.
> - `card-servicing-service/src/main/resources/application.yml`: adds the Card Data target-address entry `card-servicing.clients.card-data.target` under the service's `card-servicing` configuration (owned by CS-11-001) with the literal loopback default `localhost:9090`. It is not an environment placeholder and not a variable of the TRD environment-variable table, and the 14 bound properties of CS-11-001 stay unchanged. The unit suite sets it to the `127.0.0.1` port that `StubHarness` reports, the Compose stack (CS-01-001) sets the stub container address, and a deployed environment sets the real service address.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/carddata/CardDataClientTest.java`: policy, deadline, credentials, worker-pool, swap-point and status-mapping cases against the registered Card Data stub, `StubHarness.of(new CardDataStubRegistration())` (CS-09-004, `card-servicing-service/src/test/java/com/example/cardservicing/support/StubHarness.java`), with per-test latency and failure-rate overrides through `StubHarness.inject` on stub `card-data` and attempts counted in the stub request log. A test-local `StubRegistration` under stub name `card-data`, which `StubHarness.allRegistered()` never discovers, wraps the stub's service definition with a header-capturing server interceptor that also records each call's arrival time. The cache runs on an empty in-memory `EntryStore` unless a case seeds it. The test builds its token and service-key values at runtime, so the committed-secret scan of CS-11-001 finds no literal credential.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/carddata/CardDataClientCacheTest.java`: fresh hit, miss, stale refresh, stale fallback, expiry beyond max stale age and Redis failure, on the in-memory recording `EntryStore` and the failing `EntryStore` that the CS-11-004 tests use, with one controllable test clock for entry ages and the registered Card Data stub as the upstream.
> - Together these are the 5 files touched.
>
> Rules:
>
> - Cache keys use the TRD forms under `cs:v1:`: key family `account` with key `cs:v1:account:<accountId>`, and key family `category-balances` with key `cs:v1:category-balances:<accountId>`. Neither carries a subject, token or role, so one entry serves every caller of that account once the guard (CS-12-003) has allowed access.
> - Both families have a fresh TTL of 60 s and the 300 s max stale age of the TRD cache rules, which the cache also writes as the entry's Redis expiry. The TRD cache rules leave the fresh TTL to this story, and 60 s keeps a 240 s stale window inside the 300 s max stale age.
> - A Redis failure is treated as a miss, and an entry that does not parse counts as a miss.
> - Card cross-references are not cached, so the ownership check of the transaction detail always reads current data and fails hard when it cannot.
> - Neither credential is logged, and no cache key carries either.
> - The swap point is the target address and credential source only: replacing the stub with the real Card Data service changes `card-servicing.clients.card-data.target` and the source of `AUTH_SERVICE_API_KEY`, and no class.
> - Degradation, per the TRD degradation table: `account` and `categoryBalances` complete `STALE` with the entry's `cachedAt` when the last permitted attempt fails with `UNAVAILABLE` or `DEADLINE_EXCEEDED` and an entry within the 300 s max stale age is held, and otherwise complete exceptionally with that status, which the standard error mapping (CS-05-002) turns into 503 or 504. `cardCrossReferences` fails hard with 503 or 504. `NOT_FOUND` surfaces as 404 and is never retried.
>
> In scope:
>
> - The 5 files above.
> - The three calls `GetAccount`, `ListCategoryBalances` and `GetCardCrossReference`.
> - Caching of the account and category-balance reads.
> - The stale outcome with `cachedAt`.
> - The fail-hard cross-reference read.
> - Forwarding of the bearer token and the service credential.
> - The target address and its channel.
> - The bounded worker pool.
>
> Not in this story:
>
> - `dataStatus` and `asOf` resolution (CS-05-005) and the HTTP status mapping (CS-05-002).
> - The account access guard that authorizes before any read (CS-12-003).
> - Config refresh and the configuration snapshot behind `UpstreamSettings` (CS-11-006).
> - The container-backed Card Data integration suite (CS-09-006).
> - The Card Data stub (CS-09-004) and its container address in `tests/docker-compose.yml` (CS-01-001).
> - Call-policy and cache mechanics: attempts, backoff, the request deadline, freshness, stale fallback and Redis failure handling (CS-11-003, CS-11-004).
> - Service-to-service mTLS inside AKS (security control S4).
>
> ### 2. Acceptance Criteria
>
> - AC1: Timeout: each `GetAccount`, `ListCategoryBalances` and `GetCardCrossReference` attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the time left before the cut-off of the request deadline. With a per-test stub latency of 400 ms (`STUB_LATENCY_MS`) on stub `card-data` and a fresh 900 ms budget, the first attempt of each operation ends with `DEADLINE_EXCEEDED` between 300 and 330 ms after it starts. With the same latency and 200 ms of budget left at call start, the attempt ends with `DEADLINE_EXCEEDED` no later than 230 ms after the call starts.
> - AC2: Retry: on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, the client retries at most 2 times (`UPSTREAM_MAX_RETRIES`), after 50 ms then 100 ms backoff. With a per-test failure rate of 1.0 (`STUB_FAILURE_RATE`) on stub `card-data`, the stub request log shows exactly 3 attempts for one call of each operation. A `NOT_FOUND` answer for account `00000000099` and an `INVALID_ARGUMENT` answer for account `00000000000` each show exactly 1 attempt in the request log, for each operation.
> - AC3: Retry fit: a retry starts only when its backoff plus a full 300 ms attempt fits before the deadline. With a per-test stub latency of 400 ms and a 900 ms budget, the stub request log shows exactly 2 attempts for one call of each operation (300 + 50 + 300 = 650 ms, then 100 + 300 > 250).
> - AC4: Deadline: the 900 ms deadline (`REQUEST_DEADLINE_MS`) runs from receipt to the cut-off, and every failure case resolves at or before it. With a per-test stub latency of 2,000 ms, a test request starts a 900 ms `RequestDeadline`, calls `account` for `00000000001` and marks the cut-off when the future completes: the stub request log shows exactly 2 attempts, the outcome is `STALE` when an entry aged 120 s is held and `DEADLINE_EXCEEDED` when none is, the completion line logs `cutoffMs` ≤ 900, and the time the caller measures from the deadline's start to the future's completion is below 1,000 ms. The same holds for `categoryBalances`, and for `cardCrossReferences` with `DEADLINE_EXCEEDED` as its only failure outcome.
> - AC5: Fresh hit: an account or category-balance entry younger than the 60 s fresh TTL is returned `FRESH` with its stored `cachedAt`, and the stub request log holds zero calls. On the test clock this holds for entries aged 0 s and 59 s. An entry aged 60 s, with the stub answering, causes exactly one stub call and returns the stub's value `FRESH` with a new `cachedAt`.
> - AC6: Miss: with no entry, one `account` read for `00000000001` makes exactly one stub call, returns `FRESH` with `currentBalance` `194.00` and `creditLimit` `2020.00`, and writes exactly one entry, key `cs:v1:account:00000000001`, with `cachedAt` equal to the read time and a Redis expiry of 300 s. One `categoryBalances` read writes `cs:v1:category-balances:00000000001` holding the one balance `01`/`0001` `0.00` in the same way. Neither key contains the caller's token or subject. A second read of each within the fresh TTL with a different token is a fresh hit, and the request log still holds exactly one call per operation.
> - AC7: Degrade, stale: when the last permitted attempt fails with `UNAVAILABLE` (failure rate 1.0) and an entry within its 300 s max stale age is held, `account` and `categoryBalances` degrade to the cached value marked `STALE`, carrying the entry's original `cachedAt`, and the entry is not rewritten. On the test clock this holds for entries aged 60 s and 299 s, and AC4 covers the same degrade after `DEADLINE_EXCEEDED`.
> - AC8: Degrade expiry: when the last permitted attempt fails and the entry is aged 300 s or more on the test clock, or no entry is held, `account` and `categoryBalances` return no cached value. The future completes exceptionally with `UNAVAILABLE` after the failure-rate case, which the standard error mapping turns into 503, and with `DEADLINE_EXCEEDED` after the 2,000 ms latency case, which it turns into 504.
> - AC9: Fail hard, cross-reference: `cardCrossReferences` for `00000000001` returns the one cross-reference with `cardNumber` `9680294154603697` and `customerId` `000000001` when the stub answers, and fails hard otherwise. With failure rate 1.0 it completes exceptionally with `UNAVAILABLE` (503) after exactly 3 attempts, and with a stub latency of 2,000 ms with `DEADLINE_EXCEEDED` (504) after exactly 2. The recording entry store records zero commands across all three cases, so the cross-reference read never reads or writes the cache.
> - AC10: Not found: `NOT_FOUND` from any of the three operations for account `00000000099` completes the future exceptionally with `NOT_FOUND` and its packed `ErrorObject` unchanged, which the standard error mapping turns into 404. It is not retried: each operation shows exactly 1 attempt in the request log. No entry is written, and an entry for that account aged 120 s is never returned in its place.
> - AC11: Redis failure: with every entry-store command failing, each `account` and `categoryBalances` read is treated as a miss: it reaches the stub exactly once and completes `FRESH` with the stub's values, and no Redis error reaches the caller. With the stub failing as well, the stub's `UNAVAILABLE` status is the one returned.
> - AC12: Credentials: every stub call carries the forwarded `authorization` bearer token and the `x-api-key` value. On all three operations the header-capturing interceptor records `authorization` equal to `Bearer ` followed by the test token, and `x-api-key` equal to the configured value, or an empty value when the key is empty. With the root log level and the client frame logger at DEBUG on a live channel, the log output captured across the AC1, AC2, AC6, AC7 and AC9 paths contains neither value, and no written cache key contains either.
> - AC13: Swap point: with `card-servicing.clients.card-data.target` set to the port of a second `StubHarness` serving `CardDataStubRegistration`, every call of the three operations lands in that harness's request log and none in the first harness's log, with no change to any class. With the property not overridden, the bound target address is `localhost:9090`.
> - AC14: Bounded worker pool: with a stub latency of 400 ms, 20 concurrent `account` reads for the accounts `00000000001` to `00000000020` each reach the stub within 50 ms of submission, as the interceptor's arrival times show. With a stub latency of 2,000 ms, 96 concurrent `account` reads for `00000000001` fill the 32 worker threads and the 64 queue places, and a 97th read, for `00000000002`, submitted before any of them completes, ends exceptionally with `UNAVAILABLE` while the recording entry store holds no command for `cs:v1:account:00000000002`.
> > SCOPE-QUESTION (Resolved): Which criterion carries the authorization constraint of this account-data access path? None of AC1–AC14 does. Resolution: AC15 below. The client adds no access check of its own, and its unit suite adds no case for AC15: the account access guard (CS-12-003) decides access before any caller invokes the client, so the proof sits with the guard and with the endpoint stories that call this client.
> >
> > - AC15: Authorization: a request for account `00000000011` with `fixture-token-user0001`, outside the entitlements of `USER0001` (CS-12-001), returns 403 with `code` `PERMISSION_DENIED` from the account access guard before any call reaches this client. For that request the Card Data stub request log holds zero entries and the cache records zero reads of `cs:v1:account:00000000011` and `cs:v1:category-balances:00000000011`, so no account data is read from upstream or cache before access is allowed. The proof is delegated to CS-12-003 AC1, CS-12-005 AC3 and the endpoint stories that call this client: CS-05-005 AC2, CS-06-002 AC4 and CS-08-002 AC1.
>
> ### 3. Dependencies
>
> - CS-09-004 — provides the Card Data stub the client calls and whose request log the tests read.
> - CS-11-003 — provides the upstream call policy, request deadline and upstream settings.
> - CS-11-004 — provides the read-through cache with stale fallback.
>
> Risk: A stale entry served past its max stale age would show outdated balances, so expiry is tested at the boundary.

## 4. Design

- Diagrams: [Logical architecture](../../../../architecture/logical-architecture.mmd), [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd), [Transaction detail sequence](../../../../architecture/sequence-transaction-detail.mmd)
- Data model: [Account](../../../../data-model.md#account), [Category balance](../../../../data-model.md#category_balance), [Card cross reference](../../../../data-model.md#card_cross_reference)
- Technical requirements: [Resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience), [Deadline boundary](../../../../technical-requirements.md#deadline-boundary), [D3 Stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point), [External systems](../../../../technical-requirements.md#external-systems), [Mock-to-real swap and credential management](../../../../technical-requirements.md#mock-to-real-swap-and-credential-management), [Environment variables](../../../../technical-requirements.md#environment-variables)
- Contracts: [Card Data proto](../../../../../card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto), [Card Data OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/card-data-service.yaml), [Shared error contract](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass with ≥80% line coverage per module. `CardDataClientTest` and `CardDataClientCacheTest` cover AC1–AC14 on the loopback stub harness with per-test latency and failure overrides: the attempt timeout, the retry counts and the non-retried statuses, the fit rule, the 2,000 ms deadline case, fresh hit, miss and write-back, stale refresh, stale fallback at 60 s and 299 s, expiry at 300 s and with no entry, the fail-hard cross-reference read, not-found, Redis failure, the forwarded credentials and their absence from logs, the swap point and the worker-pool bound. AC15 is proven where the account access guard runs, by CS-12-003, CS-12-005 and the endpoint stories that call this client.
- Integration: Applies through the Card Data integration suite CS-09-006, which runs this client against the Card Data stub on a real gRPC channel with Testcontainers `postgres:16.6` and `redis:7.4.2`; this story adds no integration suite. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Applies through the balance summary SLA scenario (CS-01-005) and the category balance SLA scenario (CS-02-002), whose endpoints call this client:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the end-to-end step CS-01-004 covers the flow that calls this client. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A, blocking remote calls on a bounded worker pool, wrapped by the call policy and the cache.

Rationale: each read runs as one blocking remote call on a worker thread, so the shared call policy alone decides every attempt's timeout, retry and deadline fit, and the shared cache alone decides freshness and fallback.

- Rejected: Option B, asynchronous future-based remote calls composed per call, because it duplicates the call policy's timing rules in callback chains.

```mermaid
sequenceDiagram
    participant Endpoint as Balance summary endpoint
    participant Client as Card Data client
    participant Cache as Shared cache
    participant Stub as Card Data stub
    Note over Endpoint,Stub: the account read starts with 700 ms left in the request budget
    Endpoint->>Client: account read on a worker thread
    Client->>Cache: read account entry
    Cache-->>Client: entry aged 120 s, past its freshness window
    Client->>Stub: first attempt with a 300 ms timeout
    Stub-->>Client: unavailable status at once
    Client->>Client: wait 50 ms as 50 plus 300 ms fits in 700 ms
    Client->>Stub: retry with a 300 ms timeout
    Stub-->>Client: no answer within 300 ms
    Client->>Client: next retry does not fit as 100 plus 300 ms exceeds the 350 ms left
    Client-->>Endpoint: cached entry marked stale with its cache timestamp
```

Shared by both options:

- Topology: one client per upstream service inside the card servicing service.
- Naming: cache entries named by version and account without caller identity.
- Addressing: one typed target address per upstream.
- Isolation: the cache is reached only through the shared read-through component.
- Fallback: stale entries for account and category-balance reads only.
- Provisioning: credentials from the environment, and from the key vault when deployed.
- Cut-over: change the target address and credential source only.
- Failures: unavailable and deadline outcomes after the retry cap, and the cross-reference read fails hard.

### Design Option A

Each read is one blocking remote call submitted to a worker pool of 32 threads with 64 waiting places, so the endpoint can start its concurrent reads and wait on all of them. The call policy wraps every attempt of that call and gives it the lesser of the 300 ms timeout and the time left before the cut-off. The account read and the category balance list pass that wrapped call to the shared cache, which returns a fresh entry, the new answer or a stale entry with its cache timestamp. The cross-reference read goes to the call policy alone.

How it behaves:

- A fresh entry answers on the worker thread with no remote call.
- A miss or an entry past its freshness window triggers one wrapped remote call, and a successful answer is written back with a new cache timestamp.
- An unavailable or deadline-exceeded status after the last permitted attempt returns an entry within the maximum stale age as a stale result.
- With no usable entry the unavailable or deadline-exceeded status reaches the endpoint unchanged.
- A not-found status passes through after one attempt and never falls back.
- The cross-reference read never touches the shared cache and fails hard on any failed status.
- A read submitted to a full worker pool ends at once with the unavailable status.
- The caller token and the service credential ride on each call and never reach a log line or a cache entry name.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Bounded worker pool (vs unbounded threads) | a burst of reads cannot exhaust the service's threads | a full pool turns a read into an unavailable status |
| 2 | Call policy wraps each attempt (vs one timeout per call) | every attempt is cut to the time left before the cut-off | each attempt computes its own timeout |
| 3 | Cross-reference read uncached (vs cached) | the ownership check always reads current data | every transaction detail pays one remote call |
| 4 | Stale result carries the cache timestamp (vs a plain value) | the endpoint can state how old a stale balance is | each result carries a timestamp beside its value |

Effort and risk: low effort on top of the call policy and cache stories, with the risk in a worker pool sized below the concurrent reads.

### Design Option B

Each read is an asynchronous remote call whose completion runs a chain of callbacks. The client composes per call the attempt timeout, the retry waits, the fit check and the cache fallback in those callbacks instead of handing a blocking call to the shared call policy. No worker pool exists, because the remote procedure interface runs the callbacks on its own transport threads.

How it differs:

- The attempt timeout, retry waits and fit check are written again in callback form beside the shared call policy.
- A retry is scheduled from a callback, so its start time depends on the transport thread that runs it.
- The cache fallback runs in a callback after the last failed attempt, so the cache read and the remote call share no single wrapper.
- Concurrency has no bound unless a separate limit is added.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Callback chains per call (vs blocking calls on a worker pool) | no worker thread waits on a remote call | the timing rules live in two places |
| 2 | Transport threads run the callbacks (vs a dedicated worker pool) | no pool to size | concurrent reads have no bound |
| 3 | Fallback composed in the client (vs inside the shared cache wrapper) | each callback chain sees the final status | stale handling repeats the cache story's rule |

Effort and risk: more effort, with the risk of timing drift between the callback chains and the shared call policy.

### Recommendation

- Option: Option A.
- Why: one timing rule lives in one place, so the call policy and the cache decide every timeout, retry, fit check and fallback for all three reads.
- Strongest argument against: the worker pool caps concurrency and must be sized against the concurrent reads.
- Shared items that do not decide the choice: the cache rules, the retry cap, the credentials and the swap point.
- Flip conditions: the call policy gains native asynchronous support, or pool exhaustion appears in the performance scenarios.

## Design Validation

### Option A

Settled assumptions:

- The cache rules fix a 300-second maximum stale age, and this story fixes a 60-second freshness window for both entries.
- The retry cap is two, with waits of 50 ms and then 100 ms.
- The request deadline runs from receipt to a cut-off no later than 900 ms after receipt.
- Card cross-references are never cached.
- The transport writes header values only through its frame logger, which a process-wide guard silences below the information level.

Open assumptions:

- Open → worker pool size under ten concurrent users → settled by the balance summary performance scenario.
- Open → a stale path against a real shared cache behaves as it does on the in-memory store → settled by the card data integration suite.
- Open → a 60-second freshness window suits the balance owner once real postings replace the stub → settled by the data owner at sign-off.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the call policy cuts each attempt to the time left |
| AC2 | Holds | the call policy retries only the two retryable statuses |
| AC3 | Holds | the call policy checks the fit before each retry |
| AC4 | Holds | the worker thread returns at the cut-off with the policy's final status |
| AC5 | Holds | the shared cache answers a fresh entry with no remote call |
| AC6 | Holds | a miss runs one wrapped call and writes one shared entry |
| AC7 | Holds | the shared cache returns a usable stale entry with its cache timestamp |
| AC8 | Holds | an expired or absent entry lets the final status through |
| AC9 | Holds | the cross-reference read bypasses the shared cache |
| AC10 | Holds | the not-found status ends the call after one attempt |
| AC11 | Holds | the shared cache treats its own failure as a miss |
| AC12 | Holds | both credentials ride on each call with the frame logger silenced |
| AC13 | Holds | the channel reads its target address from configuration |
| AC14 | Holds | the pool bound turns overflow into the unavailable status |
| AC15 | Holds | the account access guard denies the request before any read reaches the client |

### Option B

Settled assumptions:

- The cache rules fix a 300-second maximum stale age, and this story fixes a 60-second freshness window for both entries.
- The retry cap is two, with waits of 50 ms and then 100 ms.
- The request deadline runs from receipt to a cut-off no later than 900 ms after receipt.
- Card cross-references are never cached.

Open assumptions:

- Open → the callback timing matches the call policy's fit rule → settled by repeating the call policy's timing tests against the callback chains.
- Open → unbounded concurrent reads stay inside the transport's capacity → settled by the balance summary performance scenario.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the callback timer must repeat the policy's attempt cut |
| AC2 | Conditional | the retry rule is written again in callbacks |
| AC3 | At risk | the fit check runs on a transport thread with variable start times |
| AC4 | At risk | the cut-off depends on callback timing outside the shared policy |
| AC5 | Holds | the shared cache answers a fresh entry with no remote call |
| AC6 | Holds | a miss runs one call and writes one shared entry |
| AC7 | Conditional | the stale fallback must be composed after the last callback |
| AC8 | Holds | an expired or absent entry lets the final status through |
| AC9 | Holds | the cross-reference read bypasses the shared cache |
| AC10 | Holds | the not-found status ends the chain after one attempt |
| AC11 | Holds | the shared cache treats its own failure as a miss |
| AC12 | Holds | both credentials ride on each call with the frame logger silenced |
| AC13 | Holds | the channel reads its target address from configuration |
| AC14 | Breaks | the option holds no worker pool to fill |
| AC15 | Holds | the account access guard denies the request before any callback chain starts |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-09-004, CS-11-003, CS-11-004 |
| Status | TBD |
| Estimated Files Touched | 5 |
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
