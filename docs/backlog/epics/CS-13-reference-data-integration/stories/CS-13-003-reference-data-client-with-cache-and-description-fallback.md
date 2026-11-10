# CS-13-003: Reference Data client with cache and description fallback

Parent epic: [CS-13 Reference Data Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want balances shown even when descriptions cannot be fetched, so that missing labels never block my balances.
>
> Trace: CARD_REQ_TBD · Data Layouts · CVTRA03Y · TRAN-TYPE-DESC
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Data Layouts · CVTRA03Y · TRAN-TYPE-DESC X(50) and Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC X(50) are the descriptions that label type-group and category balances. This story fetches both through one client and keeps balances served when the descriptions cannot be fetched.
>
> Capability: one Reference Data client resolves the transaction-type list and the transaction-category list for the Stage 3 reads of the request path: `ListTransactionTypes` runs concurrently with the Card Data reads of the balance summary, and `ListTransactionCategories` concurrently with `ListCategoryBalances` on category balances. Each list is one whole-list entry of the read-through cache, and a Reference Data failure degrades to stale or partial descriptions, never to an error. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/referencedata/ReferenceDataClient.java`:
>   - Wraps the blocking stub that grpc-java generates for `cardservicing.referencedata.v1.ReferenceDataService` in the Java package `com.example.cardservicing.contract.referencedata.v1` from `card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto`, generated under the build directory and never checked in.
>   - `transactionTypes(token, deadline)` and `transactionCategories(token, deadline)` resolve the type list and the category list independently, each returning a `ReferenceDataResult`. The two share no state and no call, so an endpoint runs either one concurrently with its other Stage 3 reads, and one instance serves concurrent requests.
>   - Each list is a read-through of `ReadThroughCache` (CS-11-004, `card-servicing-service/src/main/java/com/example/cardservicing/integration/support/ReadThroughCache.java`) with no resource identifier: key family `reftypes` (key `cs:v1:reftypes`) for types and `refcats` (key `cs:v1:refcats`) for categories. Each family has a fresh TTL of 3,600 s and a max stale age of 86,400 s, which the cache also writes as the entry's Redis expiry. The entry holds the whole list with its `cachedAt`. Its codec writes the generated `ListTransactionTypesResponse` or `ListTransactionCategoriesResponse` as protobuf bytes, and an entry that does not parse counts as a miss.
>   - On a miss or a stale entry, the upstream read is one `ListTransactionTypes` or `ListTransactionCategories` call through `UpstreamCallPolicy` (CS-11-003) inside the request's `RequestDeadline`, with the per-attempt timeout and the retry cap that the policy reads from `UpstreamSettings`. The client adds no attempt, timeout, retry or cache rule of its own.
>   - Every call carries `authorization: Bearer <caller token>` and `x-api-key` with the value of `card-servicing.auth.api-key` from `CardServicingProperties` (CS-11-001, `card-servicing-service/src/main/java/com/example/cardservicing/config/CardServicingProperties.java`), bound from `AUTH_SERVICE_API_KEY`: an empty placeholder in the stub phase, sent as an empty value, and a Key Vault secret when deployed (CS-12-004). The headers are attached per call, so the client holds no token between calls.
>   - Catches every `io.grpc.StatusRuntimeException` that leaves the cache read and returns the partial result in its place, so no Reference Data status reaches the endpoint as an error.
>   - Writes one warning line per stale or partial result naming the list, the result status and the final gRPC status code. No line carries the token, the service credential or any other call metadata.
>   - Holds no store connection and no Redis client of its own: Redis is reached only through `ReadThroughCache`, and reference data only through this gRPC client.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/referencedata/ReferenceDataResult.java`:
>   - The immutable per-list result consumed by the balance summary assembly (CS-05-005), the category-balance validation (CS-06-001) and retrieval (CS-06-002), and the shared `DataStatusResolver` (CS-05-005). The client builds it through factories for a type list, a category list and the partial result.
>   - Holds the descriptions by code: by type code for the type list, and by type code plus category code for the category list, each the stored text with no padding.
>   - `usable()` is true when the descriptions come from an upstream read, a fresh entry or a stale entry within the 86,400 s max stale age, and false otherwise.
>   - `status()` is the nested enum value `FRESH`, `STALE` or `PARTIAL`. `asOf()` is the entry's `cachedAt` when a cache entry served the list, the read time when an upstream read did, and empty for `PARTIAL`, so the resolver takes `asOf` from the request's other reads.
>   - `typeDescription(typeCode)` and `categoryDescription(typeCode, categoryCode)` return an `Optional`: empty for every code when `usable()` is false, and empty for a code the list does not hold.
>   - `isKnownTypeCode(typeCode)` is true when the type list holds the code, or when at least one category of the category list carries it. CS-06-001 consults it only when `usable()` is true, and a partial result answers false for every code.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/referencedata/ReferenceDataClientConfiguration.java`:
>   - Builds one plaintext gRPC channel per service instance to the Reference Data target address, and the blocking stub over it, and shuts the channel down when the application context closes. The channel connects on first use, so a context starts with no Reference Data service reachable.
>   - The target is bound from `card-servicing.clients.reference-data.target` by a `@ConfigurationProperties` record nested in this file, with the placeholder default `localhost:9090`. It is not a variable of the TRD environment-variable table, so `card-servicing-service/src/main/resources/application.yml` and `CardServicingProperties` from CS-11-001 stay unchanged and no environment variable is added. The unit suite sets it to the `127.0.0.1` port that `StubHarness` reports, the Compose stack (CS-01-001) sets the stub container address, and the real-service swap changes only this value and the credential source.
>   - Builds the two `ReadThroughCache.KeyFamily` values with their fresh TTL, max stale age and codec.
>   - Holds the gRPC Netty transport's client frame logger `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` at INFO through Spring Boot's `LoggingSystem` when it starts, whatever the root level, as the Auth client does (CS-12-002), because at DEBUG that shared logger writes every outbound header value, `authorization` and `x-api-key` included.
>
> > SCOPE-QUESTION (Resolved): The level hold above does not by itself keep the credentials out of the log. A later application context in the same JVM, such as a test context started while the client's channel is live, initialises logging and applies its own `logging.level` settings when its environment is prepared. Until a client of that context re-applies the hold, which a context that starts no client never does, the frame logger of every live client connection reads DEBUG. `ReferenceDataClientConfiguration` therefore also owns a credential guard that no logger level can lift: its nested class `ReferenceDataClientConfiguration.ClientFrameLogGuard`, built the same way as the server guard `GrpcServerConfiguration.ServerFrameLogGuard` of CS-11-002, whose idempotent, synchronized static `install()` replaces the default logger factory of the shaded Netty transport, through `io.grpc.netty.shaded.io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory`, with a wrapper around the factory it replaces. The wrapper hands every logger name to that factory unchanged except `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler`, for which it returns a logger that reports TRACE and DEBUG as disabled, discards every TRACE and DEBUG event and passes INFO and above to the delegate. The transport builds the frame logger of each connection the channel opens from that factory and asks the logger whether DEBUG is enabled before it writes a frame, so no frame of a guarded connection reaches a log line, whatever level `LoggingSystem`, a `logging.level` setting, a later context's logging reset or a runtime level change gives the logger. `ReferenceDataClientConfiguration` calls `install()` before it builds its channel, so the guard is in place before the channel opens its first connection. The guard records in a static field that it has installed its wrapper, so a second call changes nothing, and nothing removes the wrapper, so it holds for as long as the JVM runs, across every later context start and close. The Config client (CS-11-006) and the Auth client (CS-12-002) each install a guard of their own in the same way, as a nested class of the class that applies their hold and builds their channel, and a guard installed after another wraps it and changes nothing observable, because both drop the same events. The level hold stays, but the guard, not the level, is the never-log protection AC9 relies on. The guard adds no file, no dependency, since the factory ships in the pinned `grpc-netty-shaded`, and no environment variable, so the 5 files touched stand.
>
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/referencedata/ReferenceDataClientTest.java`: the client tests of section 2 on the loopback stub harness of CS-11-002 (`card-servicing-service/src/test/java/com/example/cardservicing/support/StubHarness.java`):
>   - `StubHarness.of(new ReferenceDataStubRegistration())` (CS-13-002) serves the stub answers, with per-test latency and failure-rate overrides through `StubHarness.inject` on stub `reference-data` and attempts counted in the stub request log.
>   - A test-local anonymous `StubRegistration` under stub name `reference-data`, which `StubHarness.allRegistered()` never discovers, wraps the stub's service definition with a header-capturing server interceptor, fails one operation while answering the other, and serves the `UNAUTHENTICATED` and `INTERNAL` answers the stub never gives.
>   - The cache sits on the in-memory recording `EntryStore` and the failing `EntryStore` that the CS-11-004 tests use, with one controllable test clock for entry ages. Latency and deadline cases run on the real clock.
>   - It builds its token and service key values at runtime, so the committed-secret scan of CS-11-001 finds no literal credential.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/referencedata/ReferenceDataResultTest.java`: the result-shape tests: descriptions by code for both lists, the empty lookup for an unknown code, the known-type-code answer for both lists, the partial result with no description and no `asOf`, and the `usable` flag of each status.
> - Together these are the 5 files touched.
>
> Degradation rule: Reference Data unusable gives `PARTIAL` with null descriptions and never an error, and an entry within the 86,400 s max stale age gives `STALE`. The endpoint renders a missing description as JSON `null` in REST and as an unset field in gRPC.
>
> Category-balance type label (TRD request path, open item settled with CS-06-002): the type list is tied to no endpoint, so the category-balance retrieval can resolve it concurrently with the category list and add no serialized call. Which source labels that endpoint's types stays with CS-06-002.
>
> In scope:
>
> - The 5 files above.
> - Two whole-list cache entries, one per list, with the 3,600 s fresh TTL and the 86,400 s max stale age.
> - The call policy applied to both operations inside the request deadline.
> - Stale and partial fallback.
> - The result shape.
> - Credential and token forwarding.
> - The channel configuration and its target address.
>
> Not in this story:
>
> - Endpoint `dataStatus` precedence and the REST `null` / gRPC unset serialization (CS-05-005, CS-06-002, asserted by CS-05-006, CS-06-003).
> - The unknown-`typeCode` check (CS-06-001).
> - Call-policy and cache mechanics: attempts, backoff, the request deadline, freshness, stale fallback and Redis failure handling (CS-11-003, CS-11-004).
> - The Reference Data stub (CS-13-002).
> - The container-backed Reference Data integration suite (CS-13-004).
> - The Reference Data target address in `tests/docker-compose.yml` (CS-01-001).
> - Service-to-service mTLS inside AKS (security control S4).
>
> ### 2. Acceptance Criteria
>
> - AC1: With a fresh `cs:v1:reftypes` entry, the client returns its descriptions with status `FRESH` and `asOf` equal to the entry's `cachedAt`, and the stub request log records no call. `transactionTypes` returns the 7 descriptions with `usable` true, and a fresh `cs:v1:refcats` entry gives `transactionCategories` its 18 descriptions in the same way.
> - AC2: On a cache miss, the client calls the stub once, writes the whole list to the cache entry, and returns `FRESH` with `asOf` equal to the read time. The written entry carries `cachedAt` equal to that read time and a Redis expiry of 86,400 s, type `01` reads `Purchase`, and type `01` with category `0001` reads `Regular Sales Draft`.
> - AC3: Each stub attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining request budget. With a per-test stub latency of 400 ms (`STUB_LATENCY_MS`) and a fresh 900 ms budget, the first attempt ends between 300 and 330 ms after it starts. With the same latency and 200 ms of budget left at call start, the attempt ends no later than 230 ms after it starts.
> - AC4: On `UNAVAILABLE` or `DEADLINE_EXCEEDED`, the client retries at most 2 times (`UPSTREAM_MAX_RETRIES`), after 50 ms then 100 ms backoff. A retry starts only if its backoff plus a full 300 ms attempt fits in the remaining deadline. With a per-test failure rate of 1.0 (`STUB_FAILURE_RATE`), the stub request log shows exactly 3 attempts for one list, and with a stub latency of 400 ms and a 900 ms budget it shows exactly 2. Any other status is not retried: a test-local server answering `UNAUTHENTICATED` or `INTERNAL` receives exactly 1 attempt, and the result follows AC7, also when a stale entry is held, because the cache falls back to a stale entry only on `UNAVAILABLE` and `DEADLINE_EXCEEDED`.
> - AC5: The 900 ms deadline (`REQUEST_DEADLINE_MS`) runs from receipt to the cut-off. In every failure case, the logged `cutoffMs` is at most 900 and the caller-measured response is below 1,000 ms. With a stub latency of 2,000 ms, a test request starts a 900 ms `RequestDeadline`, calls `transactionTypes` and marks the cut-off when the client returns: the stub request log shows exactly 2 attempts, the result is `STALE` with a stale entry held and `PARTIAL` without one, the completion line logs `cutoffMs` ≤ 900, and the time measured from the deadline's start to the client's return is below 1,000 ms.
> - AC6: When the stub fails and the entry is past its 3,600 s fresh TTL but within the max stale age of 86,400 s, the client returns the entry's descriptions with `usable` true, status `STALE` and `asOf` equal to `cachedAt`. The stub failure is `UNAVAILABLE` or `DEADLINE_EXCEEDED` after the last permitted attempt or at the cut-off, the entry is not rewritten, and on the test clock this holds for entries aged 3,600 s and 86,399 s.
> - AC7: When the stub fails and no usable entry exists, the client returns `usable` false, all descriptions null and status `PARTIAL`, and raises no error. This holds with no entry, with an entry aged 86,400 s on the test clock, and after every non-retried status of AC4. Every description lookup is empty, `isKnownTypeCode` answers false for every code, and `asOf` is empty.
> - AC8: A Redis read or write failure is treated as a cache miss, and the stub answer is still returned. With every entry-store command failing, each call reaches the stub exactly once and returns `FRESH` with the stub's list, and with the stub failing as well the result is `PARTIAL` with no error.
> - AC9: The caller's bearer token is forwarded in `authorization` metadata and `x-api-key` carries `AUTH_SERVICE_API_KEY`. Neither value appears in any log line. Cache keys contain no caller identity. On both operations the header-capturing interceptor records `authorization` equal to `Bearer ` followed by the test token, and `x-api-key` equal to the configured value, or an empty value when the key is empty. With the root log level at DEBUG, the log output captured across the AC2, AC4, AC6 and AC7 paths contains neither value, and calls made with two different tokens write only the keys `cs:v1:reftypes` and `cs:v1:refcats`.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers a live client channel while logger levels change. Two more cases run in `ReferenceDataClientTest` against the Reference Data stub that `StubHarness` started, with every entry-store command failing so that each call reaches the stub, on a client built with a service key generated at runtime, whose channel a first call has opened. Each case sends `transactionTypes` calls, each with a bearer token generated at runtime, so no credential literal is committed and `CommittedSecretScanTest` (CS-11-001) stays green, with output captured by Spring Boot's `OutputCaptureExtension`, which records standard output and standard error and so keeps recording across a logging reset. First, the test sets the effective level of `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` to DEBUG through `LoggingSystem`, the state a logging reset leaves before any re-application, sends 5 calls that each return `FRESH` with the 7 type descriptions, and then sets the level back to INFO. Second, the test starts a further application through `SpringApplicationBuilder` from a test configuration that imports `ReferenceDataClientConfiguration` and declares the client's collaborators, with `card-servicing.clients.reference-data.target` set to the same stub's loopback port and `logging.level.root`, `logging.level.io.grpc` and `logging.level.io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` set to DEBUG, and adds a test listener ordered at `LoggingApplicationListener.DEFAULT_ORDER + 1`, directly after Spring Boot's logging listener. On `ApplicationEnvironmentPreparedEvent`, after that application has initialised logging and applied its DEBUG settings and before any of its beans exists, the listener reads the effective level of `NettyClientHandler` as DEBUG and then sends 5 calls on the already-live client of the first case, each with a newly generated token, all returning `FRESH` with the 7 type descriptions before the listener returns. The application then reports ready, with the effective level of `NettyClientHandler` back at INFO once its client configuration has started, and is closed. None of the values generated in either case appears in the captured output.
>
> - AC10: Types and categories resolve independently. A failing categories call leaves a fresh types result `FRESH`, and the reverse holds too. With the test-local server failing only `ListTransactionCategories`, `transactionTypes` returns `FRESH` from the stub while `transactionCategories` returns `PARTIAL`, the outcomes swap when only `ListTransactionTypes` fails, and a held `cs:v1:refcats` entry never answers a types call.
>
> ### 3. Dependencies
>
> - CS-13-002 — Reference Data stub the client calls.
> - CS-11-003 — upstream call policy and request deadline.
> - CS-11-004 — read-through cache with stale fallback.
>
> Risk: stale descriptions shown for up to the 86,400 s max stale age after a data change while the Reference Data service fails.

## 4. Design

- [Sequence — balance summary](../../../../architecture/sequence-balance-summary.mmd)
- [Sequence — category balances](../../../../architecture/sequence-category-balances.mmd)
- [Data model — reference data as data](../../../../data-model.md#reference-data-as-data)
- [TRD — resilience, degradation table and cache rules](../../../../technical-requirements.md#resilience)
- [TRD — request path](../../../../technical-requirements.md#request-path)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — external systems](../../../../technical-requirements.md#external-systems)
- [TRD — mock-to-real swap and credential management](../../../../technical-requirements.md#mock-to-real-swap-and-credential-management)
- Contracts: [Reference Data service proto](../../../../../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto)
- Contracts: [Reference Data service OpenAPI](../../../../../card-servicing-service/src/main/resources/openapi/reference-data-service.yaml)

Design detail: completed during implementation.

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass with ≥80% line coverage per module. `ReferenceDataClientTest` and `ReferenceDataResultTest` cover AC1–AC10 on the loopback stub harness with per-test latency and failure overrides: fresh hit, miss and write-back, the attempt timeout, the retry counts and the non-retried statuses, the 2,000 ms deadline case, stale fallback at 3,600 s and 86,399 s, partial fallback at 86,400 s and with no entry, Redis failure, the forwarded credentials and their absence from logs, the client frame-logger guard with the client frame logger forced to DEBUG on a live channel and during a later application's logging initialisation against the already-live client, and independent lists.
- Integration: Applies. This story adds no integration suite; the Reference Data integration suite (CS-13-004) runs this client against the Reference Data stub on a real gRPC channel with Testcontainers `postgres:16.6` and `redis:7.4.2`. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Applies through the balance summary SLA scenario (CS-01-005) and the category balance SLA scenario (CS-02-002), whose endpoints call this client:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — this story owns no UI interaction step. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest` (CS-01-002 and its steps), then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Option A — two whole-list cache entries.

Rationale: the client caches the full type list and the full category list as two entries, so each list carries one freshness and one as-of time.

- Rejected: Option B, one cache entry per code, because one response needs up to 25 lookups and can mix freshness within itself.
- Rejected: no cache with a stub call on every request, because every request then spends budget on reference data.

### Shared by both options

- Topology: the client runs inside the service, the stub sits behind the call policy, and the entries live in the shared cache.
- Naming: every entry name is versioned and carries no caller identity.
- Addressing: the stub is reached by its configured target address.
- Isolation: types and categories resolve independently.
- Fallback: an unavailable or deadline-exceeded failure falls back to a stale entry within the max stale age, and any other failed status, or no usable entry, gives the partial status with descriptions absent and no error.
- Provisioning: the channel is built at service start, after the transport is fitted with a header-logging guard that stays in place for the life of the process.
- Cut-over: the real service swap changes only the target address and the credential source.
- Failures: unavailable and deadline-exceeded statuses are retried inside the budget, and a cache failure is treated as a miss.

### Design Option A — two whole-list cache entries

The client keeps one cache entry for the full type list and one for the full category list. A request reads at most one entry per list and calls the stub only when that entry is missing or past its freshness window. An unavailable or deadline-exceeded failure falls back to the held list while it is within the max stale age, and any other failed status, or no usable list, gives the partial status with no descriptions and no error.

How it behaves:

- Fresh hit: the held list answers with its stored time, and the stub sees no call.
- Miss: one stub call fills the whole list and its entry in one step.
- Stale fallback: an unavailable or deadline-exceeded failure with the held list within the max stale age returns that list with the stale status.
- Partial fallback: a failure with no usable list, or any other failed status even with a list held, returns the partial status with every description absent and no error.
- Independent lists: a failure on one list leaves the other list's status unchanged.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Whole list per entry (vs one entry per code) | one cache read and one as-of time per list | every cache read carries the whole list even for one label |
| 2 | Degrade to the partial status (vs fail the request) | balances stay visible during a reference outage | labels stay missing until the service recovers |
| 3 | Independent lists (vs one combined entry) | one failing operation never hides the other list | the two entries age apart |

Effort and risk: low effort on top of the call policy and cache stories, with the risk in day-old labels during a long reference outage.

### Design Option B — one cache entry per code

The client keeps one cache entry per type code and one per type-and-category pair. A response looks up the entry of each code it labels, and when any of them is missing or past its freshness window it calls the stub for the full list and writes one entry per code. An unavailable or deadline-exceeded failure falls back code by code to held entries within the max stale age, and any other failed status gives the partial status with every description absent and no error.

How it differs:

- A balance summary or category balance response reads up to 25 entries instead of one.
- One miss fills up to 25 entries in one pass.
- Entries written at different times give one response mixed ages, so one as-of time stands for the oldest of them.
- An unavailable or deadline-exceeded failure can leave some codes labelled and others absent within one response.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One entry per code (vs whole list per entry) | each cache read carries only one label | each response pays one cache read per code |
| 2 | Code-by-code fallback (vs list-level fallback) | a partial outage keeps the labels still held | one response can mix fresh, stale and absent labels |

Effort and risk: more effort, with the risk of mixed freshness within one response.

### Recommendation

- Option: Option A.
- Why: each list resolves in one cache read with one freshness and one as-of time, which leaves the budget to the balance reads.
- Strongest argument against: every cache read carries the whole list even when a response needs few labels.
- Shared items that do not decide the choice: the call policy, the deadline, the credentials, the fallback rule and the freshness window.
- Flip conditions: the lists grow to thousands of codes, so a whole-list read costs more than per-code lookups.

## Design Validation

### Option A validation

Settled assumptions:

- The 7 types and 18 categories fit one entry each.
- Reference data carries no account data, so the shared entries are safe across callers.
- The call policy and cache stories supply the timeout, retry and stale mechanics.
- The transport writes header values only through its frame logger, which it takes from a replaceable logger factory for each new connection and which checks whether debug output is enabled before each frame.

Open assumptions:

- The one-day max stale age suits the data owner's change rhythm. Open → settled by the data owner at sign-off.
- The cold-path budget leaves room for one full reference attempt. Open → settled by the integration suite's deadline scenario.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a fresh list entry answers with no stub call |
| AC2 | Holds | one stub call fills the whole list entry |
| AC3 | Holds | the call policy cuts each attempt to the remaining budget |
| AC4 | Holds | the call policy retries only the two retryable statuses |
| AC5 | Holds | one cache read per list leaves the budget to the stub attempts |
| AC6 | Holds | the held list carries one stored time for the as-of value |
| AC7 | Holds | a failure with no permitted stale fallback yields the partial status |
| AC8 | Holds | the cache treats its own failure as a miss |
| AC9 | Holds | no log line or entry name carries the credentials, with the transport's header logging shut off by a process-wide guard |
| AC10 | Holds | each list has its own entry and its own call |

### Option B validation

Settled assumptions:

- The 7 types and 18 categories give at most 25 entries.
- Reference data carries no account data, so the shared entries are safe across callers.
- The call policy and cache stories supply the timeout, retry and stale mechanics.
- The transport writes header values only through its frame logger, which it takes from a replaceable logger factory for each new connection and which checks whether debug output is enabled before each frame.

Open assumptions:

- Per-code cache reads stay inside the stage budget at the planning load. Open → settled by the balance summary and category balance service-level scenarios.
- Mixed label ages within one response suit the data owner. Open → settled by the data owner at sign-off.

| AC | Verdict | Why |
|---|---|---|
| AC1 | At risk | a response needs up to 25 entry reads |
| AC2 | At risk | one miss fills many entries |
| AC3 | Holds | the call policy cuts each attempt to the remaining budget |
| AC4 | Holds | the call policy retries only the two retryable statuses |
| AC5 | At risk | per-code lookups eat into the budget |
| AC6 | Conditional | mixed entry ages blur one as-of time |
| AC7 | Holds | codes with no permitted stale fallback yield the partial status |
| AC8 | Holds | the cache treats its own failure as a miss |
| AC9 | Holds | no log line or entry name carries the credentials, with the transport's header logging shut off by a process-wide guard |
| AC10 | Holds | type entries and category entries stay separate |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-13-002, CS-11-003, CS-11-004 |
| Status | TBD |
| Estimated Files Touched | 5 |
| Issue Type | Story |
| Epic Name | Reference Data Integration |
| Epic Link | CS-13 |
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
