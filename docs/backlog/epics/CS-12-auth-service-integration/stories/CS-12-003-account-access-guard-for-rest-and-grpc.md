# CS-12-003: Account access guard for REST and gRPC

Parent epic: [CS-12 Auth Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As an account holder, I want every account-data request checked against my entitlements before any data is read, so that no one sees an account they are not entitled to.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Account-data access path: Yes
>
> External integration or SLA-bound: Yes
>
> Anchor basis: Use Cases · COACTVWC is the account view that this epic's access control protects. This story is that access control: for every request on an account path it decides, before any account data is read, whether the caller may see the account.
>
> Capability: one account access guard runs Stage 1 authentication and Stage 2 authorization of the request path for all four Card Servicing endpoints on both protocols. It takes the bearer token only from the caller's `authorization` header or gRPC metadata, takes the subject and roles only from `ValidateToken`, and decides access only by `AuthorizeAccountAccess(subject, path accountId).allowed`, through the Auth client of CS-12-002 inside the request's 900 ms deadline. It fails closed. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/auth/AccountAccessGuard.java`:
>   - The protocol-neutral decision that the REST filter and the gRPC interceptor share. It extracts the bearer token from the `authorization` value, which must be the scheme `Bearer`, compared without regard to case, one space and a non-empty token. Any other value is rejected with no Auth call.
>   - Calls `AuthClient.validateToken(token, deadline)` and then `AuthClient.authorizeAccountAccess(token, subject, accountId, deadline)` (CS-12-002) with the request's `RequestDeadline` (CS-11-003), the one started at receipt and carried on the request. When no deadline is attached it starts one of `REQUEST_DEADLINE_MS` at its own entry, so no Auth call is ever unbounded. Per-attempt timeouts, retries and backoff come from `UpstreamCallPolicy` inside the client, never from the guard.
>   - Maps each outcome by the rules below into a typed failure naming a row of the CS-05-002 code table `ErrorCode` (`card-servicing-service/src/main/java/com/example/cardservicing/config/error/`). It builds no error body itself.
>   - On `allowed=true` it calls `UpstreamSettings.accessAllowed(token)` (CS-11-003) exactly once, with the caller's token, and then binds the validated subject and roles to the request for the endpoint handler. The hook never blocks the request; it is a no-op until `ConfigSnapshot` (CS-11-006) implements it with the background Config refresh.
>   - On every rejection it marks the cut-off on the request's `RequestDeadline` when the deciding answer arrives and completes it with outcome `error` and the response status, so the completion line carries `cutoffMs`. After an allow, the endpoint handler owns the cut-off.
>   - Writes exactly one structured access-log line per decision with `subject` (absent when no subject was validated), `accountId`, `outcome` (`ALLOWED` or the code-table row of the rejection) and `traceId`, taken from the logging context that CS-05-002 fills at receipt. It is an access log, not a write audit, and it carries no token, no `x-api-key` value and no other call metadata.
>   - Reads no account data, holds no cache and no Redis client, and opens no store connection.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/auth/AccountAccessFilter.java`:
>   - A REST request filter on `/v1/accounts/{accountId}/**`, the path family of all four UI-facing operations. It reads `accountId` from the path segment only, never from a query parameter, a header or the body.
>   - Registered after `TraceIdFilter` (CS-05-002) at an order that leaves the Stage 0 slot between them for `RequestValidationStage`, the validation stage that the request-parameter validator story CS-05-003 fills, so a request that fails Stage 0 never reaches the guard. Its unit suite stands a test validation filter in that slot.
>   - Writes each rejection through the filter error writer of `ApiErrorHandler` (CS-05-002), so a rejection ahead of any controller returns the same status and `ErrorObject` body as one raised in a controller. On an allow it sets the subject as a request attribute and continues the chain.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/auth/AccountAccessGrpcInterceptor.java`:
>   - A gRPC server interceptor applied to the four methods of `cardservicing.api.v1.CardServicingService` only. Stub servers carry no guard (CS-11-002).
>   - Reads `authorization` from the call metadata and `account_id` from the single request message, after the Stage 0 validation interceptor slot. It holds the message back from the service until the decision is made, and runs the Auth calls on the guard's own executor, never on the transport thread, carrying the call's `traceId` into that thread's logging context.
>   - Closes a rejected call with the gRPC status of the code-table row and exactly one packed `cardservicing.common.v1.ErrorObject` in `google.rpc.Status` details, through the rendering of `GrpcErrorInterceptor` (CS-05-002). On an allow it binds the subject in the gRPC `Context` and releases the message to the service.
>   - Declared as an `io.grpc.ServerInterceptor` bean ordered after the error interceptor and the Stage 0 slot, so `card-servicing-service/src/main/java/com/example/cardservicing/config/GrpcServerConfiguration.java` (CS-11-002) hosts it in that order for the Card Servicing API.
> - Unit suites in `card-servicing-service/src/test/java/com/example/cardservicing/integration/auth/`:
>   - `AccountAccessGuardTest.java`: the decision rules, the hook calls, the access-log line and the deadline cases, with the Auth client of CS-12-002 against the CS-12-001 Auth stub, which `StubHarness` (CS-11-002) starts on a loopback port inside the test JVM.
>   - `AccountAccessFilterTest.java`: the REST path, in a Spring web test context holding `TraceIdFilter`, a test Stage 0 filter that starts the request deadline and rejects account `00000000000`, the guard filter and a test handler on `/v1/accounts/{accountId}/balance-summary`.
>   - `AccountAccessGrpcInterceptorTest.java`: the gRPC path, with a test `CardServicingService` implementation hosted through `GrpcServerConfiguration` on port 0 behind the error interceptor, a test Stage 0 interceptor that starts the request deadline and rejects `account_id` `00000000000`, and the guard interceptor.
>   - Shared harness: the test handler reads account data only from the test-only probe stub of CS-11-002 and a test-local counting cache stand-in, so "no account data read" is a probe stub request log with zero entries and zero counted cache reads. On completion it marks the cut-off and completes the request deadline. A test-local `StubRegistration` wraps the CS-12-001 Auth stub with a request-capturing server interceptor that records the subject, the account, the answer and the `x-api-key` value of each Auth call. A recording `UpstreamSettings` double counts `accessAllowed(token)` calls and their order against the captured Auth answers. The service key value is built at runtime, so the committed-secret scan of CS-11-001 finds no literal credential.
> - Together these are the 6 files touched.
>
> Rules:
>
> - No `authorization` value, a scheme other than `Bearer`, or an empty token: 401 `UNAUTHENTICATED` with no Auth call.
> - `ValidateToken` failing with `UNAUTHENTICATED`, which the Auth client of CS-12-002 also returns for the contract's `INVALID_ARGUMENT` on a missing token: 401 `UNAUTHENTICATED` with no `AuthorizeAccountAccess` call.
> - `allowed=false`: 403 `PERMISSION_DENIED`. This guard is the only place that mapping is made.
> - Either Auth call ending in `UNAVAILABLE` after the last permitted retry: 503 `UNAVAILABLE`.
> - The deadline exhausted in Stage 1 or Stage 2: 504 `DEADLINE_EXCEEDED`, with no account data read from upstream or cache.
> - Every rejection renders the CS-05-002 error object with the `traceId` issued at receipt: REST 401, 403, 503 or 504, and gRPC `UNAUTHENTICATED`, `PERMISSION_DENIED`, `UNAVAILABLE` or `DEADLINE_EXCEEDED` with `ErrorObject` packed in `google.rpc.Status` details.
> - No upstream call and no cache read of account data happens before an allow, because the endpoint handler runs only after it.
> - `UpstreamSettings.accessAllowed(token)` runs exactly once per allowed request and never on a 400, 401, 403, 503 or 504.
>   > SCOPE-QUESTION (Resolved): The zero-call part of this rule covers only requests that end before an allow: a Stage 0 400, which never reaches the guard, and the guard's own 401, 403, 503 and 504 rejections in Stage 1 and Stage 2, the outcomes AC7 lists. Once `AuthorizeAccountAccess` answers `allowed=true`, the guard calls `UpstreamSettings.accessAllowed(token)` exactly once, before the endpoint handler runs, and that call stands whatever the request then returns. A later Stage 3 outcome, such as the 400 for an unknown `typeCode` on category balances, a 404, or a 503 or 504 from a data read or the cut-off, neither undoes nor suppresses it, and the guard makes no second call. The refresh the hook may launch runs under its own deadline, independent of the triggering request (CS-11-006).
> - Identity comes only from the token: a caller-supplied subject, role or entitlement in any header, query parameter or metadata entry is ignored, and an inbound `x-api-key` is ignored, so the Auth client always sends the service's own key.
> - The account checked is the path `accountId` on REST or the request's `account_id` on gRPC, the same value Stage 0 accepted.
> - No token and no service key value appears in any log line at any level.
>
> In scope:
>
> - The 6 files above.
> - Stage 1 authentication and Stage 2 authorization on both protocols.
> - The guard's 401, 403, 503 and 504 outcomes and their cut-off.
> - The access-allowed hook call after an allow.
> - The access-decision log line.
> - The three unit suites.
>
> Not in this story:
>
> - Stage 0 parameter rules and the validation stage that fills the slot ahead of the guard (CS-05-003 and the endpoint validation stories CS-06-001, CS-07-001 and CS-08-001).
> - Starting the request deadline at receipt in the service, and the cut-off after an allow (CS-05-005, CS-06-002, CS-07-002 and CS-08-002).
> - The Config refresh behind the access-allowed hook (CS-11-006).
> - The endpoint handlers (CS-05-006, CS-06-003, CS-07-003 and CS-08-003).
> - The Auth client, its retries and its status mapping (CS-12-002).
> - The Auth stub and its fixture identities (CS-12-001).
> - The Auth integration suite (CS-12-005).
>
> ### 2. Acceptance Criteria
>
> - AC1: Authorization: a REST `GET /v1/accounts/00000000011/balance-summary` and a gRPC `GetBalanceSummary` with `account_id` `00000000011`, each sent with `authorization: Bearer fixture-token-user0001` for an account outside the entitlements of `USER0001`, return 403 with `code` `PERMISSION_DENIED` (gRPC status `PERMISSION_DENIED` with the packed `ErrorObject`) and the request's receipt `traceId`. The same holds for `fixture-token-admin001` on account `00000000001`. No account data is read from upstream or cache before access is allowed: the probe stub request log holds zero entries and the counting cache stand-in records zero reads.
> - AC2: Allow: the AC1 requests for account `00000000001` with `fixture-token-user0001` reach the test handler on both protocols with subject `USER0001` bound to the request. The Auth stub request log holds exactly two entries, `cardservicing.auth.v1.AuthService/ValidateToken` followed by `cardservicing.auth.v1.AuthService/AuthorizeAccountAccess`, both with outcome `OK`, and the capturing interceptor records subject `USER0001` and account `00000000001` on the second.
> - AC3: Missing credential: a request with no `authorization` header on REST or metadata entry on gRPC, one with `authorization: Basic fixture-token-user0001`, and one whose value is `Bearer` followed by no token each return 401 with `code` `UNAUTHENTICATED` (gRPC `UNAUTHENTICATED`), and the Auth stub request log holds zero entries.
> - AC4: Rejected token: `fixture-token-expired` and `fixture-token-malformed` each return 401 with `code` `UNAUTHENTICATED` on both protocols. For each, the Auth stub request log holds exactly one `ValidateToken` entry with outcome `UNAUTHENTICATED` and no `AuthorizeAccountAccess` entry.
> - AC5: Caller input cannot change identity: a request with `fixture-token-user0001` for account `00000000011`, an account of `USER0002`, that also carries `x-api-key: wrong`, a header or metadata entry `x-subject: USER0002` and, on REST, the query parameter `subject=USER0002`, returns 403 `PERMISSION_DENIED`. The capturing interceptor records subject `USER0001` on `AuthorizeAccountAccess` and, on both Auth calls, the service's configured key and never `wrong`.
> - AC6: Stage 0 first: a request for account `00000000000`, which the Stage 0 stage in the slot ahead of the guard rejects, returns 400 `INVALID_ARGUMENT` on both protocols, also when sent with `fixture-token-expired`, and the Auth stub request log holds zero entries.
> - AC7: Access-allowed hook: on each AC2 request `UpstreamSettings.accessAllowed(token)` is called exactly once, with `fixture-token-user0001`, after the `AuthorizeAccountAccess` answer `allowed=true` and before the test handler runs. It is called zero times on the 400, 401, 403, 503 and 504 outcomes of AC1, AC3, AC4, AC6, AC8, AC10 and AC11.
> - AC8: Timeout and retry: each Auth attempt times out at the lesser of 300 ms (`UPSTREAM_TIMEOUT_MS`) and the budget left on the request's 900 ms deadline (`REQUEST_DEADLINE_MS`), with at most two retries (`UPSTREAM_MAX_RETRIES`) after 50 ms then 100 ms backoff, only on `UNAVAILABLE` or `DEADLINE_EXCEEDED`, and a retry starts only if its backoff plus a full 300 ms attempt fits the remaining budget. With an Auth stub latency of 400 ms (`STUB_LATENCY_MS`) the Auth stub request log holds exactly 2 `ValidateToken` entries and no `AuthorizeAccountAccess` entry, and the request returns 504 `DEADLINE_EXCEEDED` with a logged `cutoffMs` of at least 650 and at most 900. With the same latency and the test Stage 0 stage holding the request for 700 ms before the guard, the log holds exactly 1 `ValidateToken` entry and the request returns 504 with a logged `cutoffMs` of at most 900. With `fixture-token-expired` and no latency, the log holds exactly 1 `ValidateToken` entry.
> - AC9: Slow success: with an Auth stub latency of 250 ms, both Auth calls answer `OK` and the request completes with 200 from the test handler, a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms, on both protocols.
> - AC10: Deadline exhaustion: with an Auth stub latency of 2,000 ms the request returns 504 with `code` `DEADLINE_EXCEEDED` (gRPC `DEADLINE_EXCEEDED`), a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms. The Auth stub request log holds no `AuthorizeAccountAccess` entry, the probe stub request log holds zero entries and the counting cache stand-in records zero reads.
> - AC11: Fail hard: Auth has no degraded answer, so the guard fails closed. With an Auth stub failure rate of 1 (`STUB_FAILURE_RATE`) the request returns 503 with `code` `UNAVAILABLE` (gRPC `UNAVAILABLE`) after exactly 3 `ValidateToken` attempts, with a logged `cutoffMs` ≤ 900, a client-measured response below 1,000 ms, no `AuthorizeAccountAccess` entry and zero entries in the probe stub request log.
> - AC12: Trace identifier: every 401, 403, 503 and 504 of AC1, AC3, AC4, AC5, AC8, AC10 and AC11 carries the `traceId` issued at receipt, in the REST body and in the packed gRPC `ErrorObject`, equal to the `traceId` on that decision's access-log line and on the request's completion line.
> - AC13: Access log: each request of AC1–AC5 and AC8–AC11 writes exactly one structured access-log line holding `subject`, `accountId`, `outcome` and `traceId`, with `subject` absent when no subject was validated (AC3, AC4 and the `ValidateToken` failures of AC8, AC10 and AC11). A request rejected at Stage 0 (AC6) writes none. With the root log level at DEBUG, the log output captured across all these requests contains neither any fixture token string nor the service key value. The capture covers every logger except the gRPC server transports' frame loggers of the API and the stubs, which the stub hosting of CS-11-002 governs.
>
> ### 3. Dependencies
>
> - CS-12-002 — the Auth client the guard calls.
> - CS-05-002 — standard error object and status mapping for 401, 403, 503 and 504.
>
> Risk: a filter ordered ahead of parameter validation would call Auth for requests that must fail with 400.

## 4. Design

- [Security architecture — S5 token validation and S6 account authorization on the guard to Auth edges](../../../../architecture/security-architecture.mmd)
- [Balance summary sequence](../../../../architecture/sequence-balance-summary.mmd)
- [Category balances sequence](../../../../architecture/sequence-category-balances.mmd)
- [Transaction list sequence](../../../../architecture/sequence-transaction-list.mmd)
- [Transaction detail sequence](../../../../architecture/sequence-transaction-detail.mmd)
- [Data model — account](../../../../data-model.md#account)
- [TRD — security](../../../../technical-requirements.md#security)
- [TRD — security control table](../../../../technical-requirements.md#security-control-table)
- [TRD — access-control matrix](../../../../technical-requirements.md#access-control-matrix)
- [TRD — mock-to-real swap and credential management](../../../../technical-requirements.md#mock-to-real-swap-and-credential-management)
- [TRD — resilience](../../../../technical-requirements.md#resilience)
- [TRD — request path stages](../../../../technical-requirements.md#stages)
- [TRD — deadline boundary](../../../../technical-requirements.md#deadline-boundary)
- [TRD — configuration snapshot](../../../../technical-requirements.md#configuration-snapshot)
- [TRD — stage budget on a cold path](../../../../technical-requirements.md#stage-budget-on-a-cold-path)
- [Contract — auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto)
- [Contract — auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml)
- [Contract — card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto)
- [Contract — card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: denial for a regular user and an administrator on both protocols, the allow with its bound subject and Auth call order, missing and non-bearer credentials, expired and malformed tokens, ignored caller-supplied identity and service key, the Stage 0 order, the single access-allowed hook call, the 400 ms, residual-budget, 250 ms and 2,000 ms latency cases, the injected failure rate, the receipt `traceId` on every rejection, and the access-log line with the DEBUG log scan.
- Integration: Applies through the Auth integration suite (CS-12-005), which runs the guard against the Auth stub on a real channel. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Applies through the account balance summary SLA scenario (CS-01-005), in which every measured request passes the guard.
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Applies through the account balance summary end-to-end step (CS-01-004), whose fixture token passes the guard. Commands `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — one access guard shared by an HTTP request filter and a remote procedure server interceptor, placed right after the parameter check.

Rationale: one guard in the request chain ahead of every account endpoint makes the order of checks a property of the chain rather than of each endpoint, so no account read can start before an allow.

- Rejected: Option B, checks inside each endpoint, because four copies of the same order of checks can drift apart.
- Rejected: an authorization step at the API gateway, because the gateway sees no entitlements and the remote procedure path would bypass it.

```mermaid
sequenceDiagram
    participant Caller
    participant Check as Parameter check
    participant Guard as Access guard
    participant Auth as Auth service
    participant Settings as Settings provider
    participant Data as Account data
    Caller->>Check: account request with bearer token
    Check->>Guard: parameters valid
    Guard->>Auth: validate token
    Auth-->>Guard: subject and roles
    Guard->>Auth: authorize account for subject
    alt access denied
        Auth-->>Guard: not allowed
        Guard-->>Caller: permission-denied status
    else access allowed
        Auth-->>Guard: allowed
        Guard->>Settings: access-allowed notification
        Guard->>Data: endpoint reads account data
    end
```

Shared by both options:

- Topology: the guard runs inside the service, with one token check and one account question to the Auth service per request.
- Naming: rejections use the unauthenticated and permission-denied statuses of the standard error object, and the access log names each outcome.
- Addressing: the account checked is taken only from the request path or the request message, the same value the parameter check accepted.
- Isolation: no account data and no cache entry is read before an allow.
- Fallback: none, because the guard fails closed and the Auth service has no degraded answer.
- Provisioning: one access-log line per decision carries the subject, the account, the outcome and the trace identifier.
- Cut-over: the real Auth service replaces the stub by a change of address and credential source only.
- Failures: every rejection returns 401, 403, 503 or 504 with the trace identifier issued at receipt, and the cut-off falls at or before 900 ms.

### Design Option A

One protocol-neutral guard decides every request on an account path. A thin HTTP request filter and a thin remote procedure server interceptor hand it the bearer token and the account identifier, each placed right after the slot of the parameter check. On an allow the guard sends the single access-allowed notification and binds the subject to the request, and on any other answer it ends the request with the standard error object.

How it behaves:

- The filter and the interceptor sit after the parameter check slot, so a malformed request never reaches the Auth service.
- The guard validates the token first and asks about the account only for a validated subject.
- A denial, an unavailable Auth service or a spent deadline ends the request before the endpoint runs.
- The access-allowed notification is sent once per allowed request and never on a rejection.
- The subject from the token check is bound to the request, and identity supplied by the caller is ignored.
- The remote procedure interceptor holds the request message until the decision and runs the Auth calls away from the transport thread.
- Each decision writes one access-log line with no token and no service key header value.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | One shared guard (vs per-endpoint checks) | every endpoint gets the same order of checks | one defect affects all four endpoints |
| 2 | Filter slot after the parameter check (vs first in the chain) | malformed requests never reach the Auth service | the slot order must be held by registration |
| 3 | Access-allowed notification from the guard (vs from each endpoint) | the notification fires exactly once per allow | the guard depends on the settings provider |
| 4 | Access log in the guard (vs in each endpoint) | each decision is logged once with one field set | the log shows no endpoint outcome after the allow |

Effort and risk: one guard, one filter, one interceptor and three test classes, with the risk in the order of the request chain.

### Design Option B

Each of the four endpoints calls the parameter check, the token check and the account question itself before its own data reads. No filter or interceptor sits in the request chain, and each endpoint sends its own access-allowed notification and writes its own access-log line. The Auth calls and the error object stay the same as in Option A.

How it differs:

- Each endpoint repeats the order of checks on both protocols, eight call sites in all.
- An endpoint that skips or reorders a step can read account data before an allow.
- Each endpoint sends the access-allowed notification itself.
- Four endpoints write access-log lines, whose fields can drift apart.
- A new endpoint must copy the checks by hand.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Checks inside each endpoint (vs one shared guard) | each endpoint can tailor its own rule | four copies of the order can drift |
| 2 | Notification from each endpoint (vs from the guard) | the notification sits beside the data read | a missed call leaves the configuration unrefreshed |
| 3 | Access log in each endpoint (vs in the guard) | the log line can carry endpoint detail | four log writers can drift apart |

Effort and risk: no filter or interceptor but eight repeated check blocks, with the risk in one endpoint that reads data before its check.

### Recommendation

- Option: Option A.
- Why: one guard in the request chain makes the order of checks, the single notification and the single log line hold for every endpoint at once.
- Strongest argument against: one ordering mistake in the filter chain exposes every endpoint at once.
- Shared items that do not decide the choice: the Auth client with its timeout, retries and cut-off, the error object statuses, the fail-closed outcomes, the trace identifier and the address-only cut-over.
- Flip conditions: endpoints need different authorization rules per operation, or an endpoint must authorize on a value that only its own data read reveals.

## Design Validation

### Validation of Option A

Settled assumptions:

- The Auth client story fails closed and maps every Auth failure to the unauthenticated, unavailable or deadline-exceeded status.
- The error object story supplies the statuses, the filter error writer and the trace identifier issued at receipt.
- The parameter check runs first in the request chain, ahead of the guard.
- The fixture tokens and entitlements are fixed by the Auth stub story.

Open assumptions:

- Open → the validation story's order test settles that the request-parameter validator fills the slot ahead of the guard filter and interceptor.
- Open → the interceptor unit suite settles that the account identifier is read from the remote procedure message before any Auth call.
- Open → the Auth integration suite settles retry and cut-off timing on a loaded runner.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a denial ends the request before the endpoint runs |
| AC2 | Holds | an allow binds the validated subject and passes the request on |
| AC3 | Holds | a missing or non-bearer credential is rejected before any Auth call |
| AC4 | Holds | a rejected token ends the request before the account question |
| AC5 | Holds | the subject comes only from the token check |
| AC6 | Conditional | the parameter check must fill the slot ahead of the guard |
| AC7 | Holds | only the guard sends the notification and only after an allow |
| AC8 | Holds | the Auth client applies the call policy with the request deadline |
| AC9 | Conditional | the real-clock case depends on thread scheduling on the runner |
| AC10 | Conditional | the real-clock case depends on thread scheduling on the runner |
| AC11 | Holds | an unavailable Auth service ends the request with no data read |
| AC12 | Holds | every rejection passes through the error object with the receipt trace identifier |
| AC13 | Holds | the guard writes one line per decision and never a credential |

### Validation of Option B

Settled assumptions:

- The Auth client story fails closed and maps every Auth failure to the unauthenticated, unavailable or deadline-exceeded status.
- The error object story supplies the statuses and the trace identifier issued at receipt.
- The fixture tokens and entitlements are fixed by the Auth stub story.

Open assumptions:

- Open → a review of all eight call sites settles that each runs the parameter check, the token check and the account question in that order.
- Open → a test per endpoint settles that each sends the notification once and only after an allow.

| AC | Verdict | Why |
|---|---|---|
| AC1 | At risk | each endpoint must repeat the check before its data read |
| AC2 | Holds | an allow lets the endpoint continue with the validated subject |
| AC3 | Conditional | each endpoint must reject a missing credential before any Auth call |
| AC4 | Holds | the Auth client rejects the token before the account question |
| AC5 | Conditional | each endpoint must ignore identity supplied by the caller |
| AC6 | At risk | the order of checks is set per endpoint |
| AC7 | Conditional | each endpoint must send the single notification |
| AC8 | Holds | the Auth client applies the call policy with the request deadline |
| AC9 | Conditional | the real-clock case depends on thread scheduling on the runner |
| AC10 | Conditional | the real-clock case depends on thread scheduling on the runner |
| AC11 | Conditional | each endpoint must stop before its data read on an unavailable Auth service |
| AC12 | Holds | the error object carries the receipt trace identifier on every path |
| AC13 | At risk | four log writers can each miss a field or log a credential |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-12-002, CS-05-002 |
| Status | TBD |
| Estimated Files Touched | 6 |
| Issue Type | Story |
| Epic Name | Auth Service Integration |
| Epic Link | CS-12 |
| Story Points | 5 |
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
