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

Recommended: Design Option A — versioned compact encoding with a keyed fingerprint.

Rationale: a keyed fingerprint binds each page token to the account and filters it was issued for, so a replayed or altered token is refused before any read.

- Rejected: Design Option B, a plain encoded cursor with no fingerprint — a token from one account or filter would silently page another list.

### Shared by both options

- Topology: one codec, used by the listing stub to issue and read tokens and by the transaction list validation to check them before any upstream call.
- Naming: a version marker leads every token, and version 1 is the only accepted value.
- Addressing: the cursor holds the last returned row's processed timestamp at microsecond precision and its transaction identifier, written after the version marker at fixed positions with no separator or length field.
- Isolation: the token carries no caller identity, credential or entitlement, so per-account authorization, never the token, decides which account a caller may read.
- Fallback: any decode failure yields the invalid-argument status, never a silent restart from page one, and an absent or empty token means the first page.
- Provisioning: nothing is provisioned, because the codec adds no environment setting.
- Cut-over: the real service issues and reads tokens in the same version 1 form this codec writes, so the swap changes only the target address and the credential source and leaves the codec unchanged.
- Failures: a malformed value, a value outside its exact encoded form and a foreign version each yield the same field-level error entry naming the page token.

### Design Option A — versioned compact encoding with a keyed fingerprint

Each token carries the version marker, the cursor of the last returned row and a keyed digest computed over the version, the cursor, the account and both filters. The digest follows the transaction identifier as its raw 32 bytes, so the payload is at most 124 bytes and its URL-safe encoding without padding at most 166 characters, the bound the page-token contracts state. Decoding recomputes the digest from the presented account and filters and rejects the token on any difference.

How it behaves:

- Issuing writes the version marker, the timestamp, the transaction identifier and the raw 32-byte keyed digest in that order, then encodes the bytes in the URL-safe alphabet.
- Reading accepts only the exact encoded form, so an altered unused trailing bit still fails.
- Reading checks the version marker before the digest, and only version 1 passes.
- Reading recomputes the digest from the presented account and filters, and any difference rejects the token.
- An absent filter enters the digest as a marker no filter value can take, so a missing filter never matches a present one.
- Every rejection raises the invalid-argument status with one field-level error entry naming the page token.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Keyed fingerprint over account and filters (vs a plain digest) | a token edited without the built-in key fails the consistency check | changing the key invalidates live tokens |
| 2 | Digest that also covers the version and cursor (vs the account and filters alone) | an altered cursor fails the check | every decode recomputes the digest over the whole token |
| 3 | Version marker first (vs unversioned) | the format can change without ambiguity | one extra byte per token |
| 4 | URL-safe alphabet without padding (vs the standard alphabet) | safe in a query string without escaping | differs from the default encoder |

Effort and risk: small effort in one codec and its tests, low risk.

### Design Option B — plain encoded cursor

The token encodes the version marker and the cursor only, in the same URL-safe alphabet. Decoding checks the version and returns the cursor with no binding to the account or filters.

How it differs:

- No fingerprint is written or checked.
- A token replayed on another account or filter decodes and pages the wrong list.
- Tampering that still decodes to a valid cursor goes unnoticed.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Version and cursor only (vs a fingerprinted token) | the shortest token | a replayed token pages another list |
| 2 | Version marker first (vs unversioned) | the format can change without ambiguity | one extra byte per token |
| 3 | URL-safe alphabet without padding (vs the standard alphabet) | safe in a query string without escaping | differs from the default encoder |

Effort and risk: smallest effort, high risk of cross-list paging.

### Recommendation

- Option: Design Option A
- Why: a token presented against another account or filter is rejected before any read.
- Strongest argument against: the built-in key is the same in every environment, so the fingerprint resists tampering but not a reader of the source.
- Shared items that do not decide the choice: the cursor content and its fixed positions, the version marker, the URL-safe alphabet and the error rendering.
- Flip conditions: a revision of the token contract that drops the account and filter binding, which a swap to the real service never makes.

## Design Validation

### Option A

Settled assumptions:

- The error mapping renders a field-level error entry for the invalid-argument status.
- No new environment variable is allowed, so the key is built into the codec.
- The platform runtime provides the keyed digest, so no library is added.
- The real service honours the version 1 form and the account and filter binding the contract states, keyed with the same built-in key, so the swap changes only the target address and the credential source.

Open assumptions:

- Whether a key change between releases may refuse the earlier tokens a caller keeps for backward paging Open → the deferred UI run's paging design.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the cursor round-trips through the version check and the digest check |
| AC2 | Holds | the digest and the exact-form check cover every character |
| AC3 | Holds | the account enters the digest |
| AC4 | Holds | the type filter and its absence enter the digest as distinct values |
| AC5 | Holds | an absent category filter differs from every category value in the digest |
| AC6 | Holds | the version check rejects any marker other than 1 |
| AC7 | Holds | a value outside the URL-safe alphabet fails to decode |
| AC8 | Holds | the encoder writes at most 124 bytes as at most 166 URL-safe characters without padding |
| AC9 | Holds | the key is a constant inside the codec |

### Option B

Settled assumptions:

- The error mapping renders a field-level error entry for the invalid-argument status.
- No new environment variable is allowed.

Open assumptions:

- Whether the token contract drops the account and filter binding it now states Open → a revision of the transaction activity contract.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the cursor round-trips through the version check |
| AC2 | Breaks | a single changed character within the cursor can decode to another valid cursor |
| AC3 | Breaks | the token carries no account binding |
| AC4 | Breaks | the token carries no type filter binding |
| AC5 | Breaks | the token carries no category filter binding |
| AC6 | Holds | the version check rejects any marker other than 1 |
| AC7 | Holds | a value outside the URL-safe alphabet fails to decode |
| AC8 | Breaks | the worst-case token carries 92 bytes in 123 characters instead of the required 124 bytes in exactly 166 characters |
| AC9 | Breaks | the codec has no fingerprint and therefore no built-in fingerprint key |

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
