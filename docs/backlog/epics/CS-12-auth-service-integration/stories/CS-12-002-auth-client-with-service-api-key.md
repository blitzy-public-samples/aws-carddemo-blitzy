# CS-12-002: Auth client with service API key

Parent epic: [CS-12 Auth Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want my token checked through one Auth client that fails closed, so that no request proceeds on an unverified identity.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Account-data access path: No
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Use Cases · COACTVWC is the account view that this epic's access control protects. This story supplies the client through which that access control validates the caller's token and asks for each account decision.
>
> Capability: one gRPC client of `cardservicing.auth.v1.AuthService` validates the caller's bearer token and asks for per-account access decisions. It sends the caller's token and the service credential on every call, runs every call through the upstream call policy inside the request deadline, and fails closed. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/auth/AuthClient.java`:
>   - Wraps the stub that grpc-java generates for `cardservicing.auth.v1.AuthService` in the Java package `com.example.cardservicing.contract.auth.v1` from `card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto`.
>   - Builds one channel per service instance to the Auth target address, read from `card-servicing.auth.target` through its own placeholder with default `localhost:9090`, and shuts the channel down when the application context closes. The address is not a variable of the TRD environment-variable table, so `card-servicing-service/src/main/resources/application.yml` (CS-11-001) stays unchanged. The Compose stack (CS-01-001) and deployed environments set it, and the unit suite hands the client the loopback port that `StubHarness` reports.
>   - `validateToken(token, deadline)` returns the subject and roles of a valid token. `authorizeAccountAccess(token, subject, accountId, deadline)` returns the `allowed` value, `false` included, as a normal result.
>   - Runs every attempt through `UpstreamCallPolicy` (CS-11-003, `card-servicing-service/src/main/java/com/example/cardservicing/integration/support/`) inside the request's `RequestDeadline`. The policy reads the per-attempt timeout and the retry cap from `UpstreamSettings` at the start of every call: the built-in defaults 300 ms and 2 until the configuration snapshot of CS-11-006 backs the provider.
>
> > SCOPE-QUESTION (Resolved): The values reach the Auth calls from the request rather than from a live read. Every attempt of `validateToken` and `authorizeAccountAccess`, retries included, takes the per-attempt timeout and the retry cap from the immutable `UpstreamSettings.Capture` that the request's `RequestDeadline` took with one `capture()` call at receipt, as CS-11-003 resolves for the policy, the deadline and the provider. Neither `AuthClient` nor `UpstreamCallPolicy` calls the provider during the request: the client hands the request's `RequestDeadline` to the policy on every call and holds no reference to the `UpstreamSettings` provider. Both Auth calls of one request therefore use one published set of values, the built-in 300 ms and 2 until the first successful refresh of the configuration snapshot of CS-11-006, and a refresh published during or between them reaches only requests whose `RequestDeadline` starts after it. This story gains no file, dependency edge or environment variable, so the 3 files touched stand.
>
>   - Turns each final status into a typed failure naming a row of the CS-05-002 code table, `ErrorCode` in `card-servicing-service/src/main/java/com/example/cardservicing/config/error/`, by the rules below. The standard error handler renders it with the `traceId` that `TraceIdFilter` or `GrpcErrorInterceptor` issued at receipt.
>   - Holds the gRPC Netty transport's client frame logger, `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler`, at INFO through Spring Boot's `LoggingSystem` when it starts, whatever the root level, because that logger writes every outbound header value, `authorization` and `x-api-key` included, at DEBUG. The logger is shared by every client channel in the process.
>
> > SCOPE-QUESTION (Resolved): The level hold above does not by itself keep the credentials out of the log. A later application context in the same JVM, such as a test context started while the client's channel is live, initialises logging and applies its own `logging.level` settings when its environment is prepared. Until a client of that context re-applies the hold, which a context that starts no client never does, the frame logger of every live client connection reads DEBUG. `AuthClient` therefore also owns a credential guard that no logger level can lift: its nested class `AuthClient.ClientFrameLogGuard`, built the same way as the server guard `GrpcServerConfiguration.ServerFrameLogGuard` of CS-11-002, whose idempotent, synchronized static `install()` replaces the default logger factory of the shaded Netty transport, through `io.grpc.netty.shaded.io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory`, with a wrapper around the factory it replaces. The wrapper hands every logger name to that factory unchanged except `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler`, for which it returns a logger that reports TRACE and DEBUG as disabled, discards every TRACE and DEBUG event and passes INFO and above to the delegate. The transport builds the frame logger of each connection the channel opens from that factory and asks the logger whether DEBUG is enabled before it writes a frame, so no frame of a guarded connection reaches a log line, whatever level `LoggingSystem`, a `logging.level` setting, a later context's logging reset or a runtime level change gives the logger. `AuthClient` calls `install()` before it builds its channel, so the guard is in place before the channel opens its first connection. The guard records in a static field that it has installed its wrapper, so a second call changes nothing, and nothing removes the wrapper, so it holds for as long as the JVM runs, across every later context start and close. The Config client (CS-11-006) and the Reference Data client (CS-13-003) each install a guard of their own in the same way, as a nested class of the class that applies their hold and builds their channel, and a guard installed after another wraps it and changes nothing observable, because both drop the same events. The level hold stays, but the guard, not the level, is the never-log protection AC10 relies on. The guard adds no file, no dependency, since the factory ships in the pinned `grpc-netty-shaded`, and no environment variable, so the 3 files touched stand.
>
> > SCOPE-QUESTION (Resolved): The guard stays in place only while no other installer publishes over it, and the class monitor of `AuthClient.ClientFrameLogGuard` cannot ensure that. Every frame-log guard of the process replaces the same process-wide default factory of the shaded transport by reading the factory, wrapping it and publishing the wrapper, so a server install and a client install running at the same time under separate class monitors could both read one factory and both publish a wrapper around it, and the wrapper published first would be lost. One lock: every frame-log guard of the process, the server guard `GrpcServerConfiguration.ServerFrameLogGuard` (CS-11-002) and the client guards `AuthClient.ClientFrameLogGuard` (this story), `ConfigClient.ClientFrameLogGuard` (CS-11-006), `ReferenceDataClientConfiguration.ClientFrameLogGuard` (CS-13-003), `CardDataChannelConfiguration.ClientFrameLogGuard` (CS-09-005) and `TransactionActivityClient.ClientFrameLogGuard` (CS-10-006), runs its whole `install()` while holding the monitor of the shaded logger-factory class itself, `synchronized (InternalLoggerFactory.class)` on `io.grpc.netty.shaded.io.netty.util.internal.logging.InternalLoggerFactory`. The synchronization of the synchronized static `install()` above, of this guard and of the server guard it is built like, is this shared monitor, not the guard class's. The guard class's own monitor plays no part, because each guard class has a monitor of its own and separate monitors do not exclude each other. That class object exists once per class loader, the same scope as the default-factory field every guard replaces, so every installer able to replace that factory shares it, and the lock needs no shared class, no new file and no story edge. Sequence inside the lock: `install()` reads the guard's installed flag and returns at once when it is set. Otherwise it reads the current default factory with `InternalLoggerFactory.getDefaultFactory()`, builds its wrapper around exactly that factory, publishes the wrapper with `InternalLoggerFactory.setDefaultFactory`, and sets the installed flag only after that call returns. Preservation: because the read and the publication happen under the same lock, no installer publishes over a factory it has not read. Each wrapper delegates to the factory it found, the wrappers of earlier guards included, so the chain keeps every guard installed before it, whatever the start order and the thread. No code of the service or its tests replaces the default factory except an installer that holds this lock and follows this sequence, such as the test-local client-side installer of CS-11-002. The statement above that a guard installed after another wraps it and changes nothing observable therefore holds in any order and on any thread, the server guard included, because every guard installs under the shared lock. The four sibling client guards named above, of the Config client (CS-11-006), the Reference Data client (CS-13-003), the Card Data client (CS-09-005) and the Transaction Activity client (CS-10-006), are built the same way as this one and take the same lock in the same sequence. Unchanged: the wrapper behaviour, the rule that `AuthClient` calls `install()` before it builds its channel, the rule that nothing removes the wrapper, the level hold, and the statement that the guard adds no file, no dependency and no environment variable, so the 3 files touched stand.
>
>   - Holds no cache, no store connection and no Redis client.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/auth/AuthCredentialsInterceptor.java`:
>   - A gRPC `ClientInterceptor` on the Auth channel that adds `authorization: Bearer <caller token>` to every call, taking the token from a per-call `CallOptions` key that `AuthClient` sets.
>   - Adds `x-api-key` with the value of `card-servicing.auth.api-key` from `CardServicingProperties` (CS-11-001, `card-servicing-service/src/main/java/com/example/cardservicing/config/CardServicingProperties.java`), bound from `AUTH_SERVICE_API_KEY`: an empty placeholder in the stub phase and a Key Vault secret when deployed (CS-12-004). An empty key is sent as an empty `x-api-key` value and the call proceeds.
>   - Holds no per-call state, so one instance serves concurrent requests, and never logs a header value.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/auth/AuthClientTest.java`:
>   - The tests of section 2, against the CS-12-001 Auth stub on a loopback port inside the test JVM through `StubHarness` (`card-servicing-service/src/test/java/com/example/cardservicing/support/StubHarness.java`, CS-11-002).
>   - A test-local `StubRegistration` wraps the service definition of `AuthStubRegistration` (CS-12-001) with a header-capturing server interceptor, because the stub itself ignores call metadata. Per-test latency and failure-rate overrides go through `StubHarness.inject`, and attempts are counted in the stub request log.
>   - It builds its service key value at runtime, so the committed-secret scan of CS-11-001 finds no literal credential.
> - Together these are the 3 files touched.
>
> Rules:
>
> - `UNAUTHENTICATED` from either call, and `INVALID_ARGUMENT` from `ValidateToken`, which the contract names for a missing token, become `UNAUTHENTICATED` (401).
>   > SCOPE-QUESTION (Resolved): The contract also names `INVALID_ARGUMENT` from `ValidateToken` for a token longer than 8,192 characters. That answer maps the same way, to `UNAUTHENTICATED` (401) after exactly one attempt, so the mapping above is unchanged and the 3 files touched stand.
> - `UNAVAILABLE` after the last permitted retry, and any other non-OK status, `INVALID_ARGUMENT` from `AuthorizeAccountAccess` included, become `UNAVAILABLE` (503), because the TRD degradation table allows the Auth calls no outcome other than 401, 403, 503 or 504.
> - `DEADLINE_EXCEEDED`, or a budget exhausted before or during the call, becomes `DEADLINE_EXCEEDED` (504).
> - Only `UNAVAILABLE` and `DEADLINE_EXCEEDED` are retried, by the call policy, so `UNAUTHENTICATED` and `INVALID_ARGUMENT` get exactly one attempt.
> - No failure yields a subject, roles or `allowed=true`. `allowed=false` is a normal result, and its 403 mapping belongs to CS-12-003.
> - Auth answers are never cached: every call reaches the Auth service, and the client writes no Redis key.
> - No token and no service key value appears in any log line at any level, failure and debug paths included.
>
> In scope:
>
> - The 3 files above.
> - The Auth channel and its target address.
> - The credentials interceptor.
> - The fail-closed status mapping.
> - The client unit suite.
>
> Not in this story:
>
> - The access decision, its 403 mapping and the access-decision log line (CS-12-003).
> - The Key Vault secret source (CS-12-004).
> - The Auth integration suite (CS-12-005).
> - Changes to the call policy, the request deadline or the settings provider (CS-11-003).
> - The Auth stub and its fixture identities (CS-12-001).
> - The code table and the error rendering (CS-05-002).
> - Inbound header logging by the gRPC server transport of the API and the stubs (CS-11-002).
> - The Compose value of the Auth target address (CS-01-001).
>
> ### 2. Acceptance Criteria
>
> - AC1: On every `ValidateToken` and `AuthorizeAccountAccess` call made with the caller token `fixture-token-user0001`, the header-capturing interceptor in front of the Auth stub records `authorization` equal to `Bearer fixture-token-user0001` and `x-api-key` equal to the configured `AUTH_SERVICE_API_KEY` value. With that value empty, `x-api-key` arrives with an empty value and both calls return the stub's answers of AC2.
> - AC2: `ValidateToken` with `fixture-token-user0001` returns subject `USER0001` and roles exactly `["REGULAR"]`. `AuthorizeAccountAccess` returns `allowed=true` for `USER0001` and account `00000000001`, and `allowed=false` for `USER0001` and account `00000000011` as a normal result with no failure raised.
> - AC3: Timeout: each attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the remaining budget of the request deadline. With a per-test stub latency of 400 ms (`STUB_LATENCY_MS`) and a fresh 900 ms budget, the first attempt ends between 300 and 330 ms after it starts. With the same latency and 200 ms of budget left at call start, the attempt ends no later than 230 ms after it starts.
> - AC4: Retry: at most two retries (`UPSTREAM_MAX_RETRIES` = 2) with 50 ms then 100 ms backoff, only on `UNAVAILABLE` or `DEADLINE_EXCEEDED`, and a retry starts only if its backoff plus a full 300 ms attempt fits the remaining budget. With a per-test failure rate of 1 (`STUB_FAILURE_RATE`), the stub request log shows exactly 3 attempts for one call and the outcome is `UNAVAILABLE`. With a stub latency of 400 ms and a 900 ms budget, it shows exactly 2 attempts and the outcome is `DEADLINE_EXCEEDED`.
> - AC5: `ValidateToken` with `fixture-token-expired` (`UNAUTHENTICATED`), `ValidateToken` with an empty token (`INVALID_ARGUMENT`) and `AuthorizeAccountAccess` for `USER0001` and account `0000000001` (`INVALID_ARGUMENT`) each produce exactly one entry in the stub request log.
> - AC6: Fail closed, a fail-hard outcome because the TRD degradation table gives Auth no degraded answer: rendered by the standard error handler of CS-05-002, the expired-token and empty-token answers of AC5 give 401 with `code` `UNAUTHENTICATED`, the `INVALID_ARGUMENT` answer to `AuthorizeAccountAccess` and the AC4 `UNAVAILABLE` outcome give 503 with `code` `UNAVAILABLE`, and the AC4 `DEADLINE_EXCEEDED` outcome gives 504 with `code` `DEADLINE_EXCEEDED`. Every body carries the `traceId` placed in the logging context at receipt, and no failure path returns a subject, roles or `allowed=true`.
> - AC7: Deadline: the 900 ms deadline (`REQUEST_DEADLINE_MS`) runs from receipt to the cut-off. With a stub latency of 2,000 ms, `ValidateToken` ends in `DEADLINE_EXCEEDED` after exactly 2 attempts. The test marks the cut-off when the client returns, the completion line of `RequestDeadline` logs `cutoffMs` ≤ 900, and the time the test measures from the deadline's start to the client's return is below 1,000 ms.
> - AC8: Settings: with no configuration snapshot loaded, both calls use the built-in defaults from `UpstreamSettings`, 300 ms and two retries. With a test `UpstreamSettings` supplying `upstream_timeout_ms` 200 and `upstream_max_retries` 1, a stub latency of 400 ms ends each attempt no later than 230 ms after it starts, and a failure rate of 1 gives exactly 2 attempts in the stub request log.
>
> > SCOPE-QUESTION (Resolved): The criterion reads the settings through the request's capture. The test starts each request's `RequestDeadline` from the provider under test, and the deadline calls that provider's `capture()` once: the built-in provider's capture holds 20, 100, 300 and 2, and the test `UpstreamSettings` capture holds 20, 100, 200 and 1, so both calls take the attempt timeout and the retry cap from that capture. Refresh between the Auth calls of one request, in `AuthClientTest`: with a per-test failure rate of 1 and a counting test provider whose capture changes from 300 and 2 to 200 and 1 after the request's `ValidateToken` call returns, that request's `AuthorizeAccountAccess` call still receives exactly 3 attempts in the stub request log. The same call in a request whose `RequestDeadline` starts after the change receives exactly 2, and the provider records exactly one `capture()` call per request.
>
> - AC9: No caching: two identical `ValidateToken` calls and two identical `AuthorizeAccountAccess` calls produce two stub request-log entries each, and no constructor parameter of `AuthClient` or `AuthCredentialsInterceptor` is a cache or Redis type.
> - AC10: With the root log level at DEBUG, the log output captured across the success, 401, 503 and 504 paths of AC2, AC4 and AC6 contains neither the token string nor the service key value. The capture covers every logger except the stub server's transport frame logger, which the stub hosting of CS-11-002 governs.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers a live client channel while logger levels change. Two more cases run in `AuthClientTest` against the Auth stub that `StubHarness` started, on an `AuthClient` built with a service key generated at runtime, whose channel a first call has opened. Each case sends `authorizeAccountAccess` calls for subject `USER0001` and account `00000000001`, each with a bearer token generated at runtime, so no credential literal is committed and `CommittedSecretScanTest` (CS-11-001) stays green, with output captured by Spring Boot's `OutputCaptureExtension`, which records standard output and standard error and so keeps recording across a logging reset, and with the stub server's transport frame logger left out as above. First, the test sets the effective level of `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` to DEBUG through `LoggingSystem`, the state a logging reset leaves before any re-application, sends 5 calls that each return `allowed=true`, since the stub ignores the forwarded token, and then sets the level back to INFO. Second, the test starts a further application through `SpringApplicationBuilder` from a test configuration that declares a second `AuthClient` and its collaborators, with `card-servicing.auth.target` set to the same stub's loopback port and `logging.level.root`, `logging.level.io.grpc` and `logging.level.io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` set to DEBUG, and adds a test listener ordered at `LoggingApplicationListener.DEFAULT_ORDER + 1`, directly after Spring Boot's logging listener. On `ApplicationEnvironmentPreparedEvent`, after that application has initialised logging and applied its DEBUG settings and before any of its beans exists, the listener reads the effective level of `NettyClientHandler` as DEBUG and then sends 5 calls on the already-live client of the first case, each with a newly generated token, all returning `allowed=true` before the listener returns. The application then reports ready, with the effective level of `NettyClientHandler` back at INFO once its client has started, and is closed. None of the values generated in either case appears in the captured output.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers concurrent guard installation. Two more cases run in `AuthClientTest`. Shared-lock check, in the test JVM: while the test thread holds the monitor of `InternalLoggerFactory`, a second thread calling `AuthClient.ClientFrameLogGuard.install()` reaches thread state `BLOCKED` within 1 s, and `InternalLoggerFactory.getDefaultFactory()` returns the same object as before. Once the test releases the monitor, the call returns within 1 s. The check holds whether or not the guard is already installed, because the installed flag is read inside the lock. Concurrent installation with later DEBUG resets, in fresh JVMs: the test starts 5 child JVMs one after another through the JDK process API, `ProcessBuilder` with the running JVM's `java` command and the test class path, each running a `main` nested in `AuthClientTest`, so no guard is installed when a child starts. In each child, two threads released together by one `CountDownLatch` call `GrpcServerConfiguration.ServerFrameLogGuard.install()` and `AuthClient.ClientFrameLogGuard.install()`. The child then starts a new loopback server for the Auth stub of `AuthStubRegistration` (CS-12-001) through `StubServerHost`, and builds and starts an `AuthClient` with that server's bound port as its target and the parent's service key, so the client opens a new channel to it. That channel has opened no connection yet, and the `install()` calls that `StubServerHost` and that `AuthClient` make find their guards already installed and change nothing. Because `StubServerHost` and the client each apply their INFO hold when they start, the child only then sets the root level, `io.grpc`, `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` and `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` to DEBUG through `LoggingSystem.get(classLoader)`, the state a later context's logging reset leaves. A reset made before the starts would be undone by the holds and would hide a lost wrapper. It then sends 5 `authorizeAccountAccess` calls for subject `USER0001` and account `00000000001`, each carrying the parent's bearer token and each returning `allowed=true`, the first one opening the channel's first connection after the reset. The parent generates the bearer token and the service key at runtime and writes them to the child's standard input, so no credential literal is committed, `CommittedSecretScanTest` (CS-11-001) stays green and neither value appears on a command line. With both frame loggers still at DEBUG, the child prints that `InternalLoggerFactory.getInstance` reports DEBUG disabled for both frame-logger names and exits 0. The parent reads the child's merged standard output and standard error, sees that line and exit status 0, and finds neither value. Because the child installs the server guard as well, this case also covers the stub server's transport frame logger, and the other cases of the criterion keep leaving that logger to the stub hosting of CS-11-002.
>
> ### 3. Dependencies
>
> - CS-12-001 — the Auth stub, its test registration and the fixture tokens the client calls.
> - CS-11-003 — upstream call policy, request deadline and settings provider.
>
> Risk: a retry on an unauthenticated answer would mask a rejected token.

## 4. Design

- [Security architecture — S5 and S6 Auth edges](../../../../architecture/security-architecture.mmd)
- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Category balances sequence](../../../../architecture/sequence-category-balances.mmd)
- [Transaction list sequence](../../../../architecture/sequence-transaction-list.mmd)
- [Transaction detail sequence](../../../../architecture/sequence-transaction-detail.mmd)
- [Data model — account](../../../../data-model.md#account)
- [TRD — external systems](../../../../technical-requirements.md#external-systems)
- [TRD — resilience](../../../../technical-requirements.md#resilience)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — stage budget on a cold path](../../../../technical-requirements.md#stage-budget-on-a-cold-path)
- [TRD — security](../../../../technical-requirements.md#security)
- [TRD — security control table](../../../../technical-requirements.md#security-control-table)
- [TRD — mock-to-real swap and credential management](../../../../technical-requirements.md#mock-to-real-swap-and-credential-management)
- [TRD — environment variables](../../../../technical-requirements.md#environment-variables)
- [Contract — auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto)
- [Contract — auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: both credentials on both calls with a set and an empty service key, the fixture answers, the 300 ms and 200 ms attempt bounds, the retry cap and fit rule under injected failure and latency, the one-attempt statuses, the 401, 503 and 504 mapping with the receipt `traceId`, the 2,000 ms deadline case, the settings override, a provider change between the two Auth calls of one request, which leaves that request on the settings it captured at receipt while the next request uses the new ones, the uncached repeat calls, the DEBUG log scan, the client frame-logger guard with the client frame logger forced to DEBUG on a live channel and during a later application's logging initialisation against the already-live client, and the shared installation lock of the frame-log guard, with its concurrent installation beside the server guard in five fresh JVMs with both frame loggers reset to DEBUG.
- Integration: Applies through the Auth integration suite (CS-12-005), which runs this client against the Auth stub behind the account access guard. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — Auth latency is measured through the endpoint SLA scenarios; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the client is exercised through the CS-01 end-to-end steps. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — one channel interceptor adds both credentials to every Auth call.

Rationale: one interceptor attaches both credentials in one place.

- Rejected: Option B, per-call-site credentials, because two sites must stay in step.
- Rejected: a short-lived answer cache, because a revoked token would keep passing.

Shared by both options:

- Topology: one long-lived channel per service instance, shared by all requests.
- Naming: logs name operation and outcome, never a credential.
- Addressing: one configured target address.
- Isolation: no cache or store, so every check reaches Auth.
- Fallback: none, because the client fails closed.
- Provisioning: the key comes from the environment locally and the secret vault when deployed.
- Cut-over: only address and credential source change.
- Failures: attempts use the receipt settings and time left, and failures map to 401, 503 or 504.

### Design Option A

An interceptor on the Auth channel adds the caller's token and the service key to every call. The client only builds requests and maps final statuses.

How it behaves:

- No call site can skip a credential, and an empty key is still sent.
- Only unavailable and deadline-exceeded statuses retry.
- An invalid-argument token answer, for a missing or over-long token, maps to 401, other unexpected statuses to 503, and a denial is an ordinary answer.
- Before its channel opens, the client fits a process-wide header-logging guard, which installs under one lock shared by every such guard and keeps the guards installed before it.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One channel interceptor (vs per-call headers) | one attachment point | credential handling hidden from call sites |
| 2 | No retry on unauthenticated (vs uniform retry) | rejected tokens stay rejected | transient unauthenticated faults stay failed |
| 3 | Never cache answers (vs a short-lived cache) | revocation applies from the next request | two Auth calls per request |
| 4 | Empty key still sent (vs call refused) | the stub phase needs no secret | a missing deployed key shows only as rejection |

Effort and risk: one client, one interceptor and one test class, risking a connection opened before the guard.

### Design Option B

Each client operation adds both credentials to its own call metadata, with no channel interceptor. Auth behaviour matches Option A.

How it differs:

- Each call site copies both credentials.
- A new operation must repeat both headers.
- Credentials pass through two code paths, each able to log them.
- Tests check credentials at each call site.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Per-call-site headers (vs one channel interceptor) | credential handling visible at each call | two call sites to keep in step |
| 2 | No retry on unauthenticated (vs uniform retry) | rejected tokens stay rejected | transient unauthenticated faults stay failed |
| 3 | Never cache answers (vs a short-lived cache) | revocation applies from the next request | two Auth calls per request |

Effort and risk: one client and one test class, risking a call site omitting or logging a credential.

### Recommendation

- Option: Option A.
- Why: one interceptor puts both credentials on every call.
- Strongest argument against: the interceptor hides credential handling from call sites.
- Shared items that do not decide the choice: the channel, call policy, fail-closed mapping, no cache and cut-over.
- Flip conditions: the operations need different credentials, or the real service requires a per-call request signature.

## Design Validation

### Validation of Option A

Settled assumptions:

- The stub ignores both credentials and never re-checks access.
- The call policy story supplies the timeout, retry rule and a deadline holding the receipt settings.
- The stub story fixes the fixture answers, and the error-object story the status mapping.
- The transport takes each connection's header logger from a replaceable factory.

Open assumptions:

- Open → the real-service swap settles that real Auth accepts the forwarded token with the key.
- Open → the Auth integration suite settles retry and cut-off timing on a loaded runner.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the interceptor adds both headers |
| AC2 | Holds | stub answers pass through unchanged |
| AC3 | Holds | attempts stop at the time left |
| AC4 | Conditional | attempt counts depend on runner scheduling |
| AC5 | Holds | only two statuses retry |
| AC6 | Holds | no failure carries identity or permission |
| AC7 | Conditional | real-clock timing depends on runner scheduling |
| AC8 | Holds | both calls use the receipt settings |
| AC9 | Holds | the client holds no cache |
| AC10 | Holds | a process-wide guard blocks header logging |

### Validation of Option B

Settled assumptions:

- Option A's settled assumptions hold.

Open assumptions:

- Open → the real-service swap settles that real Auth accepts the forwarded token with the key.
- Open → a review of both call sites settles that neither logs a credential.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | each call site must add both credentials |
| AC2 | Holds | stub answers pass through unchanged |
| AC3 | Holds | attempts stop at the time left |
| AC4 | Conditional | attempt counts depend on runner scheduling |
| AC5 | Holds | only two statuses retry |
| AC6 | Holds | no failure carries identity or permission |
| AC7 | Conditional | real-clock timing depends on runner scheduling |
| AC8 | Holds | both calls use the receipt settings |
| AC9 | Holds | the client holds no cache |
| AC10 | At risk | either call site could log the token outside the guard |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-12-001, CS-11-003 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Auth Service Integration |
| Epic Link | CS-12 |
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
