# CS-12: Auth Service Integration

## Goal

Authenticate every caller and authorize every account-data request through the mocked Auth service before any account data is read.

## Business Justification

The Card Servicing guardrail requires per-account authorization on every account-data path, decided before any account data is read. CardDemo has no user-to-account entitlement, so the Auth stub's fixture identities are derived deterministically from the ten users in CardDemo user security seed · DUSRSECJ · user records, whose layout is Data Layouts · CSUSR01Y. The stub phase also needs a service credential path, an API key that comes from Key Vault when deployed, with zero live external calls. Replacing the stub with the real Auth service changes only the client's target address and credential source.

## Requirements

- Auth stub answering `ValidateToken` and `AuthorizeAccountAccess` only from `card-servicing-service/src/main/resources/fixtures/auth-identities.yaml`: `USER0001`–`USER0005` (role `REGULAR`) each entitled to ten sample accounts in ID order (`USER0001` → `00000000001`–`00000000010` … `USER0005` → `00000000041`–`00000000050`) and a 2,000-account synthetic slice (`USER0001` → `10000000001`–`10000002000` … `USER0005` → `10000008001`–`10000010000`); `ADMIN001`–`ADMIN005` (role `ADMIN`) valid with no accounts; one expired and one malformed token; no password copied from CardDemo user security seed · DUSRSECJ · user records — CS-12-001. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Auth client that forwards the caller's bearer token in `authorization` metadata, sends `x-api-key` from `AUTH_SERVICE_API_KEY`, never caches Auth results and fails closed — CS-12-002. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Account access guard on REST and gRPC that calls `ValidateToken` then `AuthorizeAccountAccess(subject, path accountId)` before any upstream or cache read of account data, takes subject and roles only from `ValidateToken`, ignores an inbound `x-api-key`, writes one access-log line per decision (subject, accountId, outcome, traceId), and on an allow calls `UpstreamSettings.accessAllowed(token)` exactly once, the launch point of the background Config refresh (CS-11-006) — CS-12-003. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Key Vault secret source that resolves `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` and `DB_PASSWORD` from Key Vault only when `AZURE_KEYVAULT_URI` is set — CS-12-004. Directive: secret management. Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Auth integration suite proving the authentication and authorization path against containers on `IntegrationTestSupport` — CS-12-005. Directive: testing requirements (integration suite). Trace: CARD_REQ_TBD · Use Cases · COACTVWC
- Empty administrator entitlement as a labelled fixture policy, not an inherited rule: CardDemo README · Application Details states that Admin users perform Admin functions only, the main menu program (Use Cases · COMEN01C) blocks only regular users from admin options, and the account view (Use Cases · COACTVWC) carries no admin prohibition; the plan takes the least-privilege reading, and changing it is a fixture-file edit with no code change — CS-12-001. Trace: CARD_REQ_TBD · Use Cases · COACTVWC

## Acceptance Criteria

1. A request with `fixture-token-user0001` for account `00000000001` returns 200 on REST and, on gRPC, status `OK`; the same token for account `00000000011` returns 403 with `code` `PERMISSION_DENIED` on REST and, on gRPC, status `PERMISSION_DENIED` with exactly one `cardservicing.common.v1.ErrorObject` packed in `google.rpc.Status` details, and the data stubs' request logs record no call for that request.
2. A valid `fixture-token-admin001` request for every sample account (`00000000001`–`00000000050`) and every synthetic account (`10000000001`–`10000010000`) returns 403 with `code` `PERMISSION_DENIED` on REST and, on gRPC, status `PERMISSION_DENIED` with the packed `ErrorObject`; the Auth stub answers `AuthorizeAccountAccess` for `ADMIN001` on each of these accounts with `allowed=false`.
3. A request with no token, `fixture-token-expired`, `fixture-token-malformed` or the unknown `fixture-token-unknown` returns 401 with `code` `UNAUTHENTICATED` on REST and, on gRPC, status `UNAUTHENTICATED` with the packed `ErrorObject`, and the Auth stub's request log records no `AuthorizeAccountAccess` call for that request.
4. Auth `UNAVAILABLE` after the two-retry cap returns 503 with `code` `UNAVAILABLE` on REST and, on gRPC, status `UNAVAILABLE` with the packed `ErrorObject`; an Auth deadline exhaustion returns 504 with `code` `DEADLINE_EXCEEDED` on REST and, on gRPC, status `DEADLINE_EXCEEDED` with the packed `ErrorObject`. Each of these outcomes has a logged `cutoffMs` of at most 900 and a client-measured response below 1,000 ms, and none reads account data from upstream or cache. Every REST 401, 403, 503 and 504 body and the packed `ErrorObject` of every gRPC `UNAUTHENTICATED`, `PERMISSION_DENIED`, `UNAVAILABLE` and `DEADLINE_EXCEEDED` carry the `traceId` issued at receipt.
5. A `fixture-token-user0001` request for account `00000000011` that also carries `x-api-key: wrong` and `x-subject: USER0002` returns 403 `PERMISSION_DENIED`, and the Auth stub receives subject `USER0001` and the service's configured key, never `wrong`.
6. Each guard decision writes exactly one access-log line. The line always holds `accountId`, `outcome` and `traceId`, and holds `subject` only once `ValidateToken` has validated one: `subject` is absent for a missing credential (no `authorization` value, a scheme other than `Bearer`, or an empty token) and for a token-validation failure (`ValidateToken` rejects the token or ends in `UNAVAILABLE`, or the request deadline runs out before `ValidateToken` answers). A request rejected at Stage 0 with 400 `INVALID_ARGUMENT` writes none.
7. With the root log level at DEBUG, the log output captured across the Auth client, the guard and the Auth integration suite contains no bearer token, service API key or password value.
8. With `AZURE_KEYVAULT_URI` unset, the service context starts with zero Key Vault calls; with it set to a vault test double, `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` and `DB_PASSWORD` resolve from the double; the committed-secret scan finds no secret value in any committed file.
9. `auth-identities.yaml` declares no password field and holds no password value from CardDemo user security seed · DUSRSECJ · user records.
10. Restarting the Auth stub over a copy of `auth-identities.yaml` whose `USER0001` sample range ends at `00000000009` makes `AuthorizeAccountAccess(USER0001, 00000000010)` return `allowed=false`, with no Java source change.
11. With the `cs:v1:config` mirror entry seeded with a `cachedAt` 400 s old (past the 300 s fresh TTL), the configuration snapshot holding no fresh values, no refresh in flight, and the Config stub answering `OK` with no failure injection and its request log cleared, a Stage 0 400, a 401 and a 403 request leave the Config stub's request log empty 1 s after the last of them, and one allowed request then yields exactly one `GetConfig` in it once the refresh logs its applied line, at most 5 s after the request; with a fresh mirror entry (`cachedAt` within the last 300 s) instead, that refresh applies the mirror values with zero `GetConfig`, as CS-11-006 specifies.
12. `./gradlew :card-servicing-service:integrationTest` passes the Auth suite, which covers a stub exception, latency above `UPSTREAM_TIMEOUT_MS` (300 ms) and the `UPSTREAM_MAX_RETRIES` (2) cap.

## Dependencies

- CS-05 Balance Summary Endpoint: CS-12-003 → CS-05-002
- CS-11 Config Service Integration: CS-12-001 → CS-11-002, CS-12-002 → CS-11-003, CS-12-004 → CS-11-001, CS-12-005 → CS-11-007

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-12-001](stories/CS-12-001-auth-stub-with-carddemo-derived-fixture-identities.md) | Auth stub with CardDemo-derived fixture identities | 3 | 4 | W1 |
| [CS-12-002](stories/CS-12-002-auth-client-with-service-api-key.md) | Auth client with service API key | 2 | 3 | W1 |
| [CS-12-003](stories/CS-12-003-account-access-guard-for-rest-and-grpc.md) | Account access guard for REST and gRPC | 5 | 6 | W1 |
| [CS-12-004](stories/CS-12-004-key-vault-secret-source-for-deployed-environments.md) | Key Vault secret source for deployed environments | 2 | 3 | W1 |
| [CS-12-005](stories/CS-12-005-auth-integration-suite.md) | Auth integration suite | 3 | 2 | W1 |
| Total | 5 stories | 15 | 18 | W1 |

Owner: TBD

Status: TBD
