# CS-12-001: Auth stub with CardDemo-derived fixture identities

Parent epic: [CS-12 Auth Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want a mocked Auth service whose identities and entitlements come from the CardDemo user list, so that authorization is testable with deterministic users.
>
> Trace: CARD_REQ_TBD · Use Cases · COACTVWC
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Anchor basis: Use Cases · COACTVWC is the account view that this epic's access control protects. This story supplies the identities and per-account entitlements against which that access control is decided in every suite.
>
> Capability: a gRPC stub of `cardservicing.auth.v1.AuthService` answers `ValidateToken(token)` → `{subject, roles}` and `AuthorizeAccountAccess(subject, account_id)` → `{allowed}` only from a fixture identity file derived from the CardDemo user security seed: the DUSRSECJ job, whose records follow Data Layouts · CSUSR01Y with user ID X(08), first and last name X(20) each, an 8-character password X(08) and user type X(01), `A` for admin and `U` for user. The stub hosting of CS-11-002 runs it on a loopback port inside the test JVM, or alone in its own container in the Compose stack. Target paths:
>
> - `card-servicing-service/src/main/resources/fixtures/auth-identities.yaml`:
>   - One identity entry per CardDemo user: `subject` is the user ID, `roles` holds `REGULAR` for user type `U` and `ADMIN` for user type `A`, and `accounts` lists inclusive ranges of 11-digit account identifiers. Names and passwords are not carried, because no contract field returns them, so the file declares no password field.
>   - One token entry per fixture token: `token`, `subject` and `state`, one of `valid`, `expired` or `malformed`. Expiry and malformation are fixture states, so the stub needs no clock and no token parser.
>   - A comment above the administrator entries labels their empty entitlement as a fixture policy.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/auth/AuthStubService.java`:
>   - Extends `AuthServiceGrpc.AuthServiceImplBase`, the base grpc-java generates for `cardservicing.auth.v1.AuthService` in the Java package `com.example.cardservicing.contract.auth.v1` from `card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto`.
>   - Reads the fixture file once at stub start from a Spring `Resource`, `classpath:fixtures/auth-identities.yaml` by default, with the SnakeYAML parser that Spring Boot already brings, so the build gains no dependency. It holds no store connection, no cache and no client of any other service.
>   - A nested configuration class declares the `StubServerHost.HostedStub` bean named `auth` only when `card-servicing.stub.serve` is `auth`, so `StubServerHost` (CS-11-002, `card-servicing-service/src/main/java/com/example/cardservicing/integration/stub/`) hosts the stub in its own container and the Card Servicing service process never builds it.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/auth/AuthStubRegistration.java`: the `StubRegistration` (CS-11-002) of stub name `auth`, building `AuthStubService` over the packaged fixture file, so `StubHarness` starts it on a loopback port inside the test JVM. It sits in the test sources because `StubRegistration` and `StubHarness` are test-source types.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/auth/AuthStubServiceTest.java`: the tests of section 2, run through `StubHarness.of(new AuthStubRegistration())` with a generated gRPC client on the port the harness reports.
> - Together these are the 4 files touched.
>
> Fixture identities, derived from the ten users of the CardDemo user security seed:
>
> - `USER0001`–`USER0005`, role `REGULAR`, each entitled to ten sample accounts in ID order and a 2,000-account slice of the synthetic range:
>   - `USER0001`, token `fixture-token-user0001`: `00000000001`–`00000000010` and `10000000001`–`10000002000`.
>   - `USER0002`, token `fixture-token-user0002`: `00000000011`–`00000000020` and `10000002001`–`10000004000`.
>   - `USER0003`, token `fixture-token-user0003`: `00000000021`–`00000000030` and `10000004001`–`10000006000`.
>   - `USER0004`, token `fixture-token-user0004`: `00000000031`–`00000000040` and `10000006001`–`10000008000`.
>   - `USER0005`, token `fixture-token-user0005`: `00000000041`–`00000000050` and `10000008001`–`10000010000`.
> - `ADMIN001`–`ADMIN005`, role `ADMIN`, tokens `fixture-token-admin001`–`fixture-token-admin005`, valid with no entitled account.
> - `fixture-token-expired`, state `expired`, subject `USER0001`; `fixture-token-malformed`, state `malformed`, no subject.
> - `fixture-token-user0001` and its answer `{"subject":"USER0001","roles":["REGULAR"]}` are the contract example; every other token follows the same pattern.
>
> Admin fixture policy: the empty administrator entitlement is a chosen fixture policy, not an inherited rule. The CardDemo README states that the Admin users can only perform Admin functions. The main menu program (Use Cases · COMEN01C) blocks only regular users from admin-only options, the account view program (Use Cases · COACTVWC) carries no admin prohibition, and CardDemo has no user-to-account entitlement at all. The plan follows the README statement as the application-level rule and the least-privilege reading. Changing the policy is an edit to the fixture file with no code change.
>
> Rules:
>
> - `ValidateToken`: a `valid` fixture token returns its `subject` and `roles`. An `expired`, `malformed` or unknown token returns `UNAUTHENTICATED`. An empty token returns `INVALID_ARGUMENT`, the status the contract's `Errors:` block names for a missing token.
> - `AuthorizeAccountAccess`: `allowed=true` when `account_id` lies inside one of the subject's ranges, both ends included, and `allowed=false` otherwise, an unknown subject included. `allowed=false` is an `OK` answer, a 200 in the HTTP twin, never an error. A subject outside 1–64 characters, or an `account_id` that does not match `^[0-9]{11}$` or is all zeros, returns `INVALID_ARGUMENT`, as the contract's `Errors:` block names it.
> - Every non-OK answer the stub itself gives packs `cardservicing.common.v1.ErrorObject` into `google.rpc.Status` details, with `code` set to the status name, a `message` naming the failed rule and a `traceId` the stub generates per call.
> - The stub ignores the `x-api-key` service key and the forwarded `authorization` metadata and does not re-authorize, so it never answers `UNAUTHENTICATED` from `AuthorizeAccountAccess`. That status in the contract's `Errors:` block belongs to the real service.
> - `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` apply through `StubFailureInjector`, and every call lands in `StubRequestLog` with its operation, outcome and duration (CS-11-002).
> - No token value reaches the request log or any stub log line, at any log level.
>
> In scope:
>
> - The 4 files above.
> - The fixture identities, the fixture tokens and the admin fixture policy.
> - The stub service with its container-mode declaration.
> - Its test registration.
> - The stub unit suite.
>
> Not in this story:
>
> - The Auth client and the `x-api-key` service key it sends (CS-12-002).
> - The account access guard and its 401 and 403 mapping (CS-12-003).
> - The Auth integration suite (CS-12-005).
> - The Compose stack and the stub's container in it (CS-01-001).
> - Any real identity provider.
> - Admin functions.
> - A user-to-account entitlement source.
>
> ### 2. Acceptance Criteria
>
> - AC1: For each regular user `USER0001`–`USER0005`, `AuthorizeAccountAccess` returns `allowed=true` for the first and the last account of its sample range and of its synthetic slice, 20 calls in all (for `USER0001`: `00000000001`, `00000000010`, `10000000001` and `10000002000`), and `allowed=false` for the account one above the end of each range, 10 calls in all (for `USER0001`: `00000000011` and `10000002001`).
> - AC2: For each administrator `ADMIN001`–`ADMIN005`, `ValidateToken` on its fixture token returns that subject with roles exactly `["ADMIN"]`, and `AuthorizeAccountAccess` returns `allowed=false` for `00000000001`, `00000000050`, `10000000001` and `10000010000`.
> - AC3: Each of the ten fixture user tokens, `fixture-token-user0001`–`fixture-token-user0005` and `fixture-token-admin001`–`fixture-token-admin005`, validates to its own subject with roles exactly `["REGULAR"]` or `["ADMIN"]` respectively, and `fixture-token-user0001` returns `{"subject":"USER0001","roles":["REGULAR"]}`, the contract example.
> - AC4: `ValidateToken` returns `UNAUTHENTICATED` for `fixture-token-expired`, for `fixture-token-malformed` and for the unknown token `fixture-token-unknown`, and `INVALID_ARGUMENT` for an empty token. `AuthorizeAccountAccess` returns `INVALID_ARGUMENT` for subject `USER0001` with account `0000000001`, `0000000001A` or `00000000000`, and for an empty subject with account `00000000001`. Each of these answers carries an `ErrorObject` in its `google.rpc.Status` details whose `code` equals the status name.
> - AC5: The answers of AC1–AC4 are identical with the `x-api-key` metadata absent, empty and set to `wrong`, and with the forwarded `authorization` metadata absent.
> - AC6: `auth-identities.yaml` declares no password field, a case-sensitive search of it for the password value that the DUSRSECJ seed carries returns zero matches, and it carries the comment labelling the empty administrator entitlement as a fixture policy.
> - AC7: Restarting the stub over a copy of `auth-identities.yaml` in which the sample range of `USER0001` ends at `00000000009` instead of `00000000010` makes `AuthorizeAccountAccess(USER0001, 00000000010)` return `allowed=false` and `AuthorizeAccountAccess(USER0001, 00000000009)` return `allowed=true`, with no Java source change.
> - AC8: With a per-test failure-rate override of 1 (`STUB_FAILURE_RATE`), 10 of 10 calls to each operation return `UNAVAILABLE`. With a per-test latency override of 400 ms (`STUB_LATENCY_MS`), each answer arrives no earlier than 400 ms after the call. Every call adds exactly one request-log entry holding the full operation name, the outcome status name and the duration in ms, and neither the request log nor the stub's log output captured at DEBUG level contains any token value sent in the test.
>
> ### 3. Dependencies
>
> - CS-11-002 — stub hosting, failure injection, request log and stub registration the Auth stub runs on.
>
> Risk: fixture token literals drift from the contract examples, breaking tests that copy them.

## 4. Design

- [Security architecture — S5 and S6 Auth edges and access-control rows](../../../../architecture/security-architecture.mmd)
- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Data model — account](../../../../data-model.md#account)
- [Data model — synthetic volume](../../../../data-model.md#synthetic-volume)
- [TRD — component catalogue](../../../../technical-requirements.md#component-catalogue)
- [TRD — external systems](../../../../technical-requirements.md#external-systems)
- [TRD — security](../../../../technical-requirements.md#security)
- [TRD — security control table](../../../../technical-requirements.md#security-control-table)
- [TRD — access-control matrix](../../../../technical-requirements.md#access-control-matrix)
- [TRD — admin fixture policy](../../../../technical-requirements.md#admin-fixture-policy)
- [TRD — mock-to-real swap and credential management](../../../../technical-requirements.md#mock-to-real-swap-and-credential-management)
- [Contract — auth_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto)
- [Contract — auth-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/auth-service.yaml)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: range ends and the account above each range for the five regular users, the administrator denials, the ten fixture tokens, the expired, malformed, unknown and empty tokens, the malformed account identifiers and empty subject, service-key indifference, the password and policy-comment scan of the fixture file, the restart over a changed fixture copy, and injected failure, injected latency and request-log entries.
- Integration: Applies through the Auth integration suite (CS-12-005), which runs this stub behind the Auth client and the account access guard. Command `./gradlew :card-servicing-service:integrationTest`; all pass, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — stub-only story with no UI-facing endpoint; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — the stub is exercised through the CS-01 end-to-end steps. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — identities and entitlement ranges held in one identity fixture file read at stub start.

Rationale: the access policy every suite depends on is data that a reviewer reads and changes in one place, with no code change and no deploy.

- Rejected: Option B, entitlements computed from the user number by rules in code, because a policy change would need a code change and a deploy and the administrator policy would hide in code.
- Rejected: a stub that accepts every token and allows every account, because denial and the unauthenticated status would then go untested.

Shared by both options:

- Topology: the stub runs inside the test runtime on a loopback port, and alone in its own container in the compose stack.
- Naming: the stub answers under the Auth service name of the contract, and each log entry carries the full operation name.
- Addressing: the hosting harness hands out the loopback port, and the compose stack gives the container its address.
- Isolation: the stub holds no store, no cache and no link to any other stub.
- Fallback: none, since only failure injection makes the stub fail.
- Provisioning: the identity fixture file is packaged with the service and read once at stub start.
- Cut-over: the real Auth service replaces the stub by changing only the client address and credential source.
- Failures: injected unavailable answers and latency come from the stub hosting story and land in the request log.

### Design Option A

Ten identities, their roles and their entitlement ranges sit in one identity fixture file that the stub reads once when it starts. Each token is listed with its owner and a state of valid, expired or malformed, so every token check is a lookup with no clock and no parser. An account request is allowed when the account number falls inside one of the owner's ranges, both ends included.

How it behaves:

- A valid token returns its owner and role, and any other token returns the unauthenticated status.
- An empty token, an empty owner, or an account number that is not eleven digits or is all zeros returns the invalid argument status.
- A denial is an ordinary answer, never an error.
- The service key header and the forwarded caller token are ignored, and the stub never re-checks access.
- A change to a range or to the administrator policy is an edit to the file followed by a stub restart.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Inclusive account ranges (vs per-account lists) | 2,010 entitled accounts per user fit in two lines | a range edit can silently overlap a neighbouring range |
| 2 | Empty administrator entitlement (vs full access for administrators) | the least-privilege reading of the source user rule holds | no administrator test path reaches account data |
| 3 | Fixed fixture tokens with listed states (vs signed tokens with expiry times) | every answer is the same on every run | token parsing stays unexercised until the real swap |
| 4 | One file read at start (vs a read on every call) | answers never change during a test | a file change needs a stub restart |

Effort and risk: one fixture file, one stub service, one registration and one test class, with the risk in token values drifting from the contract examples.

### Design Option B

Entitlements are computed from the number at the end of each user name by rules in code, for example ten sample accounts per regular user counted from the user number. The identity fixture file keeps only tokens and roles, and the administrator rule is a branch in the code. Answers match Option A for the packaged users, but every policy change is a code change.

How it differs:

- The entitlement of a regular user follows from its user number by arithmetic.
- The administrator policy is a code branch rather than an entry in the file.
- A range change needs a code change, a new build and a deploy of the stub.
- A reviewer reads the policy in code rather than in the identity fixture file.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Ranges computed from the user number (vs ranges listed in the file) | the identity fixture file stays short | a policy change needs a code change and a deploy |
| 2 | Administrator rule as a code branch (vs an empty entitlement in the file) | the rule covers any new administrator | the fixture policy is hidden from data reviewers |
| 3 | Tokens and roles still listed in the file (vs tokens derived in code) | token values stay visible beside the contract examples | identity data is split across file and code |

Effort and risk: a shorter file and one rule set in code, with the risk in a policy edit that silently needs a release.

### Recommendation

- Option: Option A.
- Why: the fixture policy is data, so every suite reads its users and ranges from one file and a policy change needs no code change.
- Strongest argument against: token values in the identity fixture file and in the contract examples are copied by hand and can drift apart.
- Shared items that do not decide the choice: the loopback and container hosting, the ignored service key header, the injected failures, the request log and the address-only cut-over.
- Flip conditions: entitlements must follow a computed rule over far more accounts than a file can list, or the real identity provider defines entitlements by rule.

## Design Validation

### Validation of Option A

Settled assumptions:

- The ten users come from the mainframe security seed, five administrators and five regular users.
- The mainframe source application holds no user-to-account entitlement source, so the ranges are a fixture policy.
- The five synthetic slices of 2,000 accounts are disjoint and together cover the synthetic range.
- The contract names the invalid argument status for an empty token and for a malformed account number.

Open assumptions:

- Open → the contract check and the stub unit suite settle that the token values in the identity fixture file match the contract examples.
- Open → a requirements entry from the requirements author settles whether the empty administrator entitlement stays the policy.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | each range check includes both ends |
| AC2 | Holds | the administrator entries list no range |
| AC3 | Conditional | the token values in the file must equal the contract example |
| AC4 | Holds | token states and input checks are lookups with no clock |
| AC5 | Holds | the stub reads neither the service key header nor the caller token |
| AC6 | Holds | the file carries no password field |
| AC7 | Holds | ranges are data read at stub start |
| AC8 | Holds | injection and the request log come from the stub hosting story |

### Validation of Option B

Settled assumptions:

- The ten users come from the mainframe security seed, five administrators and five regular users.
- The mainframe source application holds no user-to-account entitlement source.
- The five synthetic slices of 2,000 accounts are disjoint.

Open assumptions:

- Open → the stub unit suite settles that the computed ranges equal the listed ranges for all five regular users.
- Open → the contract check settles that the token values in the identity fixture file match the contract examples.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Conditional | the arithmetic must reproduce both range ends for every regular user |
| AC2 | Holds | the administrator branch denies every account |
| AC3 | Conditional | the token values in the file must equal the contract example |
| AC4 | Holds | token states and input checks are unchanged |
| AC5 | Holds | the stub reads neither the service key header nor the caller token |
| AC6 | Holds | the file carries no password field |
| AC7 | Breaks | a range change needs a code change and a deploy |
| AC8 | Holds | injection and the request log come from the stub hosting story |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-11-002 |
| Status | TBD |
| Estimated Files Touched | 4 |
| Issue Type | Story |
| Epic Name | Auth Service Integration |
| Epic Link | CS-12 |
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
