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
>   - Turns each final status into a typed failure naming a row of the CS-05-002 code table, `ErrorCode` in `card-servicing-service/src/main/java/com/example/cardservicing/config/error/`, by the rules below. The standard error handler renders it with the `traceId` that `TraceIdFilter` or `GrpcErrorInterceptor` issued at receipt.
>   - Holds the gRPC Netty transport's client frame logger, `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler`, at INFO through Spring Boot's `LoggingSystem` when it starts, whatever the root level, because that logger writes every outbound header value, `authorization` and `x-api-key` included, at DEBUG. The logger is shared by every client channel in the process.
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
> - AC9: No caching: two identical `ValidateToken` calls and two identical `AuthorizeAccountAccess` calls produce two stub request-log entries each, and no constructor parameter of `AuthClient` or `AuthCredentialsInterceptor` is a cache or Redis type.
> - AC10: With the root log level at DEBUG, the log output captured across the success, 401, 503 and 504 paths of AC2, AC4 and AC6 contains neither the token string nor the service key value. The capture covers every logger except the stub server's transport frame logger, which the stub hosting of CS-11-002 governs.
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

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: both credentials on both calls with a set and an empty service key, the fixture answers, the 300 ms and 200 ms attempt bounds, the retry cap and fit rule under injected failure and latency, the one-attempt statuses, the 401, 503 and 504 mapping with the receipt `traceId`, the 2,000 ms deadline case, the settings override, the uncached repeat calls and the DEBUG log scan.
- Integration: Applies through the Auth integration suite (CS-12-005), which runs this client against the Auth stub behind the account access guard. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — Auth latency is measured through the endpoint SLA scenarios; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the client is exercised through the CS-01 end-to-end steps. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — one client-side metadata interceptor adds the bearer token and the service key header to every Auth call.

Rationale: one interceptor on the Auth channel attaches both credentials in one place, so no Auth call leaves without them and only one place ever handles their values.

- Rejected: Option B, credentials attached at each call site, because two call sites must stay in step and each one is a place a credential could leak into a log.
- Rejected: caching validation answers for a short time, because a revoked token would keep passing until its entry expired.

Shared by both options:

- Topology: one long-lived remote procedure channel per service instance to the Auth service, shared by all concurrent requests.
- Naming: calls go to the Auth service name of the contract, and log entries name the operation and outcome, never a credential.
- Addressing: one configured target address, which together with the credential source is the only value the real-service swap changes.
- Isolation: the client holds no cache and no store, so every token check and every access question reaches the Auth service.
- Fallback: none, because the client fails closed and Auth has no degraded answer.
- Provisioning: the service key comes from the environment locally and from the secret vault when deployed.
- Cut-over: the real Auth service replaces the stub by a change of address and credential source only.
- Failures: each attempt is cut to the lesser of the timeout setting and the time left, retries stay within the call policy's cap and fit rule, the cut-off time ends the wait, and every failure maps to 401, 503 or 504.

### Design Option A

A client-side metadata interceptor sits on the one Auth channel. For every call it adds the caller's bearer token, which the client hands over as a per-call option, and the service key header from the typed configuration. The client itself only builds requests, runs each attempt through the call policy and turns final statuses into the standard failures.

How it behaves:

- Both credentials ride on every token check and every access question, and no call site can skip them.
- An empty service key is still sent as an empty header, so the stub phase runs with no secret.
- The unauthenticated status and the invalid-argument status return after one attempt, and only the unavailable and deadline-exceeded statuses retry.
- An invalid-argument answer to a token check means a missing token and maps to 401, and any other unexpected status maps to 503.
- A denial comes back as an ordinary answer for the access guard story to turn into 403.
- The trace identifier issued at receipt reaches every failure through the standard error object.
- Neither credential value reaches a log line, and the transport's header logging is held above debug level.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One metadata interceptor on the channel (vs headers set per call) | credentials are attached in one place | credential handling is hidden from the call site reader |
| 2 | No retry on the unauthenticated status (vs uniform retry) | a rejected token is never retried into a pass | a transient fault reported as unauthenticated is not retried |
| 3 | Never cache Auth answers (vs a short-lived cache) | a revoked token fails on the next request | every request pays for two Auth calls |
| 4 | Empty service key still sent (vs call refused) | the stub phase runs with no secret | a missing deployed key shows only as rejection by the real service |

Effort and risk: one client, one interceptor and one test class, with the risk in a credential value leaking through transport debug logging.

### Design Option B

Each of the two client operations builds its call metadata itself, adding the caller's bearer token and the service key header before the call policy runs the attempt. No interceptor sits on the channel. Behaviour toward the Auth service is the same as in Option A, but credential handling is repeated at each call site.

How it differs:

- Each call site copies the bearer token and the service key into its own metadata.
- A new Auth operation must repeat the same two headers by hand.
- Credential values pass through two code paths, each of which could write them to a log.
- Tests check both credentials at each call site rather than once on the channel.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Headers set at each call site (vs one channel interceptor) | credential handling is visible where each call is made | two call sites must stay in step |
| 2 | No retry on the unauthenticated status (vs uniform retry) | a rejected token is never retried into a pass | a transient fault reported as unauthenticated is not retried |
| 3 | Never cache Auth answers (vs a short-lived cache) | a revoked token fails on the next request | every request pays for two Auth calls |

Effort and risk: one client and one test class with no interceptor, with the risk in a call site that omits or logs a credential.

### Recommendation

- Option: Option A.
- Why: one interceptor guarantees both credentials on every Auth call and keeps their values in one place that never logs them.
- Strongest argument against: an interceptor hides credential handling from a reader of the call site.
- Shared items that do not decide the choice: the channel and its target address, the call policy with its timeout, retries and cut-off, the fail-closed mapping, the absence of a cache and the address-only cut-over.
- Flip conditions: the two Auth operations need different credentials, or the real Auth service requires a per-call signature built from the request body.

## Design Validation

### Validation of Option A

Settled assumptions:

- The stub ignores the service key header and the forwarded bearer token and never re-checks access.
- The call policy story supplies the timeout setting, the retry rule and the request deadline with its cut-off time.
- The fixture tokens and their answers are fixed by the Auth stub story.
- The error-object story maps the unauthenticated status to 401, the unavailable status to 503 and the deadline-exceeded status to 504, each with the receipt trace identifier.

Open assumptions:

- Open → the real-service swap settles that the real Auth service accepts the forwarded bearer token together with the service key header.
- Open → the Auth integration suite settles retry and cut-off timing on a loaded runner.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the interceptor adds both headers to every call |
| AC2 | Holds | the client passes the stub's answers through unchanged |
| AC3 | Holds | the call policy cuts each attempt to the time left |
| AC4 | Conditional | attempt counts under latency depend on thread scheduling on the runner |
| AC5 | Holds | only two statuses enter the retry path |
| AC6 | Holds | every non-success status maps to a failure with no identity or permission |
| AC7 | Conditional | the real-clock case depends on thread scheduling on the runner |
| AC8 | Holds | the client reads its timing only through the settings provider |
| AC9 | Holds | the client holds no cache |
| AC10 | Conditional | the transport's header logging must stay held above debug level |

### Validation of Option B

Settled assumptions:

- The stub ignores the service key header and the forwarded bearer token and never re-checks access.
- The call policy story supplies the timeout setting, the retry rule and the request deadline with its cut-off time.
- The fixture tokens and their answers are fixed by the Auth stub story.

Open assumptions:

- Open → the real-service swap settles that the real Auth service accepts the forwarded bearer token together with the service key header.
- Open → a review of both call sites settles that neither writes a credential value to a log.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | each call site must add both credentials |
| AC2 | Holds | the client passes the stub's answers through unchanged |
| AC3 | Holds | the call policy cuts each attempt to the time left |
| AC4 | Conditional | attempt counts under latency depend on thread scheduling on the runner |
| AC5 | Holds | only two statuses enter the retry path |
| AC6 | Holds | every non-success status maps to a failure with no identity or permission |
| AC7 | Conditional | the real-clock case depends on thread scheduling on the runner |
| AC8 | Holds | the client reads its timing only through the settings provider |
| AC9 | Holds | the client holds no cache |
| AC10 | At risk | two call sites could each log the token |

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
