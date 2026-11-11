# CS-10-003: Page token codec

Parent epic: [CS-10 Transaction Activity Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a card servicing user, I want an opaque page token that cannot be replayed against another account or filter, so that paging always continues the list I started.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Capability: `PageTokenCodec` encodes and decodes the opaque page token of the transaction listing. The payload carries the version `v` = 1, the last returned row's cursor (processed timestamp at microsecond precision and transaction ID) and a fingerprint that binds the token to `accountId`, `typeCode` and `categoryCode`, with an absent filter encoded distinctly from every value. The fingerprint is a keyed digest, HMAC-SHA-256 from the JDK, computed over the version, the cursor and those three values; its key is a built-in codec constant, so the codec adds no dependency, environment variable or configuration key. The token is encoded in the URL-safe alphabet without padding (A–Z, a–z, 0–9, `-`, `_`), and decoding accepts only that exact encoded form. An empty or absent token means the first page and is never decoded. Decoding fails with `INVALID_ARGUMENT`, rendered as 400 with a `details` entry whose field is `pageToken`, when the value does not decode, when `v` is not 1, or when the fingerprint does not match the presented account and filters. The codec raises the typed failure of the CS-05-002 code table, which `ApiErrorHandler` renders for REST and `GrpcErrorInterceptor` for gRPC; it builds no error body itself. Use Cases · COTRN00C pages its list forward and back (PF7/PF8); the baseline keyset token carries that navigation, and backward paging replays earlier tokens the caller keeps. The Transaction Activity stub (CS-10-004) encodes each next token and decodes the cursor it receives, and the transaction list validation (CS-07-001) decodes the token at Stage 0 before any upstream call. Target paths (3 files):
>
> > SCOPE-QUESTION (Resolved): The payload above fixes no byte layout and no length, so a codec built to it could issue a token longer than the page-token contracts accept. The version 1 payload is the layout those contracts state, with each field at a fixed position and no separator or length field: the 1-byte version marker, then the cursor's processed timestamp as the 27 bytes of its RFC 3339 microsecond form (for example `2022-06-10T19:27:53.000000Z`), then the cursor's transaction ID as its UTF-8 bytes, at most 64 for its 16 characters, then the 32 raw bytes of the HMAC-SHA-256 fingerprint. Decoding reads each field back from the same position, the transaction ID being the bytes between the timestamp and the fingerprint. The payload is therefore at most 124 bytes (1 + 27 + 64 + 32), and its URL-safe encoding without padding is at most 166 characters, the bound that `page_token` and `next_page_token` state in `card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto` and `card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto`, and that `pageToken` and `nextPageToken` state in their twins `card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml` and `card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml`.
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/transactionactivity/PageTokenCodec.java` — encoding, decoding, the fingerprint check and the version check.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/transactionactivity/PageCursor.java` — the cursor value: processed timestamp and transaction ID.
> - `card-servicing-service/src/test/java/com/example/cardservicing/integration/transactionactivity/PageTokenCodecTest.java` — the unit suite for AC1–AC9.
>
> In scope:
>
> - Encoding.
> - Decoding.
> - The fingerprint check.
> - The version check.
> - The field-level error.
>
> Not in this story:
>
> - The keyset query (CS-10-004).
> - Stage 0 wiring (CS-07-001).
> - Token pass-through and REST omission of the last-page token (CS-07-002).
> - Encryption of cursor contents.
>
> ### 2. Acceptance Criteria
>
> - AC1: Encoding the cursor (`2022-06-10T19:27:53.000000Z`, `0000000755736377`) for account `00000000001` with no filters, then decoding the token with the same account and no filters, returns the same timestamp, the same transaction ID and version 1.
> - AC2: Changing any single character of a valid token makes decoding fail with `INVALID_ARGUMENT`, rendered as 400 with a `details` entry whose field is `pageToken`.
> - AC3: A token issued for account `00000000001` and presented with account `00000000002` fails with 400 and a `pageToken` details entry.
> - AC4: A token issued with type filter `01` and presented with type filter `03`, or with no type filter, fails with 400 and a `pageToken` details entry.
> - AC5: A token issued with category filter `0001` and presented with no category filter fails with 400 and a `pageToken` details entry.
> - AC6: A well-formed token carrying version 2 fails with 400 and a `pageToken` details entry.
> - AC7: A value that does not decode, such as `***`, fails with 400 and a `pageToken` details entry.
> - AC8: Every encoded token contains only the characters A–Z, a–z, 0–9, `-` and `_`.
>   > SCOPE-QUESTION (Resolved): AC8 also bounds the length: every encoded token is at most 166 characters, the page-token contract bound, the worst case included. The worst case is the cursor (`2022-06-10T19:27:53.000000Z`, a transaction ID of 16 copies of U+1F600, each a four-byte UTF-8 character) for account `00000000001` with no filters, whose 124-byte version 1 payload encodes to exactly 166 characters, and that token decodes, with the same account and no filters, to the same cursor.
> - AC9: The fingerprint key is a constant built into the codec, and the service declares no environment variable beyond the 14 in `card-servicing-service/src/main/resources/application.yml`.
>
> ### 3. Dependencies
>
> - CS-05-002 — provides the standard error object and status mapping that render the 400 with a field details entry.
>
> Risk: a weak fingerprint lets a token cross accounts.

## 4. Design

- Diagram: [Sequence — transaction list](../../../../architecture/sequence-transaction-list.mmd)
- Data model: [transaction_record](../../../../data-model.md#transaction_record)
- TRD: [Request path — stages](../../../../technical-requirements.md#stages)
- Contracts: [transaction_activity_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto), [transaction-activity-service.yaml](../../../../../card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml), [card_servicing_service.proto](../../../../../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto), [card-servicing-api.yaml](../../../../../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml), [common.proto](../../../../../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto)

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit — Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass; at least 80% line coverage per module. Cases: the AC1 round trip, a single-character change at every position of a valid token, the account, type-filter and category-filter mismatches, version 2, the undecodable value `***`, the alphabet of every token the suite encodes, the length of every token the suite encodes at no more than 166 characters, with the AC1 token at exactly 102 and the worst-case token of the note beneath AC8, whose transaction ID is 16 four-byte UTF-8 characters, at exactly 166, the absence of any new environment variable, and each rejection rendered through `ApiErrorHandler` as the 400 body.
- Integration — Not applicable — a pure function with no upstream call; CS-10-007 exercises it through the stub. Command `./gradlew :card-servicing-service:integrationTest`; one suite per external epic (CS-10-007 for this epic) covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance — Not applicable — no input or output beyond memory. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:performanceTest`, `docker compose -f tests/docker-compose.yml down`; p50, p95 and p99 below 1,000 ms over successful requests with failure injection off at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions; injected failures proven by a logged `cutoffMs` at most 900 and a client-measured response below 1,000 ms; applies to balance-retrieval and drill-down stories.
- End-to-end — Not applicable — paging navigation is proven by CS-03-002. `docker compose -f tests/docker-compose.yml up -d --wait`, `./gradlew :card-servicing-service:e2eTest`, `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — keyed fingerprint.

Rationale: a keyed fingerprint binds each token to the account and filters it was issued for.

- Rejected: Design Option B, a plain encoded cursor — a token from one account or filter would page another list.

### Shared by both options

- Topology: one codec, issuing and reading tokens for the listing stub and checking them for the transaction list validation before any upstream call.
- Naming: a version marker leads every token, and only version 1 passes.
- Addressing: the cursor, the last row's microsecond-precision processed timestamp and transaction identifier, follows the version marker at fixed positions with no separator or length field.
- Isolation: the token carries no caller identity, credential or entitlement, so per-account authorization alone decides account access.
- Fallback: an absent or empty token means the first page, and an undecodable one is refused, never silently restarted from page one.
- Provisioning: nothing, because the codec adds no environment setting.
- Cut-over: the real service uses the same version 1 form, so the swap changes only the target address and credential source.
- Failures: a malformed value, an inexact encoding and a foreign version each raise one invalid-argument error naming the page token field.

### Design Option A — keyed fingerprint

Each token ends with a raw 32-byte keyed digest of the version, cursor, account and both filters. Its payload of at most 124 bytes encodes to at most 166 URL-safe characters, the page-token contract bound.

How it behaves:

- Reading accepts only the exact encoded form, so an altered unused trailing bit still fails.
- Reading checks the version, then recomputes the digest from the presented account and filters, raising the same field-level error on any difference.
- An absent filter enters the digest as a marker no filter value can take.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Keyed fingerprint (vs a plain digest) | edits without the built-in key fail | a key change invalidates live tokens |
| 2 | Digest also over version and cursor (vs account and filters alone) | an altered cursor fails | each decode hashes the whole token |
| 3 | Version marker first (vs unversioned) | unambiguous format changes | one extra byte |
| 4 | URL-safe alphabet without padding (vs standard) | no query-string escaping | a non-default encoder |

Effort and risk: small, one codec and its tests, low risk.

### Design Option B — plain encoded cursor

The token holds only the version marker and the cursor. Decoding checks the version and returns the cursor.

How it differs:

- A replayed token pages the wrong list, and a tampered one that still decodes goes unnoticed.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Version and cursor only (vs a fingerprint) | the shortest token | a replayed token pages another list |
| 2 | Version marker first (vs unversioned) | unambiguous format changes | one extra byte |
| 3 | URL-safe alphabet without padding (vs standard) | no query-string escaping | a non-default encoder |

Effort and risk: smallest, high risk of cross-list paging.

### Recommendation

- Option: Design Option A
- Why: a token presented with another account or filter fails before any read.
- Strongest argument against: one built-in key serves every environment, so a reader of the source defeats the fingerprint.
- Shared items that do not decide the choice: the cursor layout, version marker, URL-safe alphabet and error rendering.
- Flip conditions: a token contract revision dropping the account and filter binding.

## Design Validation

### Option A

Settled assumptions:

- The error mapping renders a field-level entry for the invalid-argument status.
- No new environment variable is allowed, so the key is built in.
- The platform runtime provides the keyed digest, so no library is added.
- The real service binds tokens the same way, with the same built-in key.

Open assumptions:

- Whether a key change may refuse earlier tokens kept for backward paging Open → the deferred UI run's paging design.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the cursor round-trips through both checks |
| AC2 | Holds | the digest and exact-form checks cover every character |
| AC3 | Holds | the account enters the digest |
| AC4 | Holds | the type filter and its absence enter the digest distinctly |
| AC5 | Holds | an absent category filter differs from every category value |
| AC6 | Holds | only version 1 passes |
| AC7 | Holds | a value outside the alphabet fails to decode |
| AC8 | Holds | at most 124 bytes encode to at most 166 URL-safe characters |
| AC9 | Holds | the key is a codec constant |

### Option B

Settled assumptions:

- The error mapping renders a field-level entry for the invalid-argument status.
- No new environment variable is allowed.

Open assumptions:

- Whether the token contract drops its account and filter binding Open → a transaction activity contract revision.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the cursor round-trips through the version check |
| AC2 | Breaks | a changed cursor character can yield another valid cursor |
| AC3 | Breaks | nothing binds the account |
| AC4 | Breaks | nothing binds the type filter |
| AC5 | Breaks | nothing binds the category filter |
| AC6 | Holds | only version 1 passes |
| AC7 | Holds | a value outside the alphabet fails to decode |
| AC8 | Breaks | the worst case encodes 92 bytes to 123 characters, not 124 bytes to exactly 166 |
| AC9 | Breaks | no fingerprint means no built-in key |

## Metadata

| Field | Value |
|---|---|
| Build wave | W1 |
| Depends on | CS-05-002 |
| Status | TBD |
| Estimated Files Touched | 3 |
| Issue Type | Story |
| Epic Name | Transaction Activity Integration |
| Epic Link | CS-10 |
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
