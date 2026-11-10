# CS-11: Config Service Integration

## Goal

Supply page-size and upstream-call settings from the mocked Config service without ever delaying a request, on the shared stub hosting, call policy and cache this epic owns.

## Business Justification

Use Cases · COTRN00C PROCESS-PAGE-FORWARD pages the transaction list in fixed ten-row pages, with the page size set inside the program. The Card Servicing transaction list needs a configurable default and maximum page size, and every upstream call needs a configurable timeout and retry budget, all served without adding latency to a request. Every external epic (Card Data, Transaction Activity, Config, Reference Data, Auth) needs one hosting, call-policy and cache foundation, so failure behaviour is identical across them and implemented once.

## Requirements

- Typed configuration for all 14 environment variables with safe defaults and no committed secret — CS-11-001 (directive: environment variables). Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Stub hosting for all five mocked upstreams with injectable latency and failure rate and a per-stub request log (operation, outcome, duration) — CS-11-002 (directive: stub all five external calls). Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Per-attempt timeout, capped retries with backoff, and one request deadline measured to the cut-off — CS-11-003 (directive: error handling, timeouts and retries). Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Read-through cache with stale fallback, the only Redis access path in the service — CS-11-004 (directive: read-through cache). Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Config stub answering `ConfigService.GetConfig` for the four known keys — CS-11-005. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Config client with an in-memory configuration snapshot, built-in defaults and a single-flight background refresh launched only after access is allowed — CS-11-006. Trace: CARD_REQ_TBD · Use Cases · COTRN00C
- Config integration suite against real containers, the shared integration test base, and the Integration CI stage — CS-11-007 (directive: testing requirements). Trace: CARD_REQ_TBD · Use Cases · COTRN00C

## Acceptance Criteria

1. Each of the 14 environment variables binds with its default — `AUTH_SERVICE_API_KEY` empty, `REDIS_PORT` 6379, `REDIS_PASSWORD` empty, `REDIS_SSL_ENABLED` false, `AZURE_KEYVAULT_URI` empty with the Key Vault property source disabled, `UPSTREAM_TIMEOUT_MS` 300, `UPSTREAM_MAX_RETRIES` 2, `REQUEST_DEADLINE_MS` 900, `STUB_LATENCY_MS` 0, `STUB_FAILURE_RATE` 0, with `REDIS_HOST`, `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` set to local-container values — and an environment override replaces the default of its own property only.
2. A scan of the committed files finds no credential value other than an empty placeholder or a throwaway local-container value, and the same scan over a planted file holding a non-empty `AUTH_SERVICE_API_KEY` value fails and names that file.
3. A stub registered through the shared harness answers on a `127.0.0.1` loopback port; an injected 200 ms latency yields a logged duration of at least 200 ms; a failure rate of 1.0 answers 20 of 20 calls `UNAVAILABLE` and a failure rate of 0 answers 0 of 20.
4. Every upstream attempt times out at the lesser of 300 ms and the remaining request budget; at most two retries run, with 50 ms then 100 ms backoff, on `UNAVAILABLE` or `DEADLINE_EXCEEDED` only, and only when backoff plus a full 300 ms attempt fits in the remaining budget; the logged `cutoffMs` is at most 900 and the client-measured response arrives below 1,000 ms.
5. A fresh cache hit makes no upstream call; an upstream `UNAVAILABLE` or `DEADLINE_EXCEEDED` with an entry within its max stale age returns the value flagged `STALE` with its original `cachedAt`; a Redis failure is treated as a cache miss.
6. The Config stub answers `GetConfig` with `default_page_size` "20", `max_page_size` "100", `upstream_timeout_ms` "300" and `upstream_max_retries` "2", and never serves `REQUEST_DEADLINE_MS`: a request for that key returns an empty `values` map.
7. An injected 2,000 ms Config stub latency adds no latency to a request: `cutoffMs` stays at most 900, the client response arrives below 1,000 ms, and the outcome equals that of the same run with the latency at 0.
8. With a stale snapshot, no refresh in flight, the `cs:v1:config` mirror entry absent or stale (past its 300 s fresh TTL) and the Config stub answering `OK` with no failure injection, 10 concurrent allowed requests launch exactly one refresh, which records exactly one `GetConfig` in the Config stub request log; with a fresh mirror entry (`cachedAt` within the last 300 s) instead, that one refresh applies the mirror values and records zero `GetConfig`; with the mirror entry absent or stale and the Config stub answering `UNAVAILABLE` to every attempt, the refresh records exactly three `GetConfig` attempts at the `UPSTREAM_MAX_RETRIES` cap, keeps the held values and leaves `dataStatus` unchanged.
9. The Integration CI stage runs after Unit on every pull request, and a failing integration suite fails the gate; the Config suite records exactly three `GetConfig` attempts at the `UPSTREAM_MAX_RETRIES` cap; the shared integration test base starts one `postgres:16.6` and one `redis:7.4.2` container per test JVM, and a missing Docker daemon fails the suite instead of skipping it.

## Dependencies

- CS-05 Balance Summary Endpoint: CS-11-001 → CS-05-001, CS-11-003 → CS-05-002

## Stories

| Story | Title | Points | Estimated files touched | Build wave |
|---|---|---|---|---|
| [CS-11-001](stories/CS-11-001-service-configuration-properties-and-environment-variables.md) | Service configuration properties and environment variables | 2 | 4 | W0 |
| [CS-11-002](stories/CS-11-002-stub-hosting-failure-injection-and-request-log.md) | Stub hosting, failure injection and request log | 5 | 8 | W0 |
| [CS-11-003](stories/CS-11-003-upstream-call-policy-with-request-deadline.md) | Upstream call policy with request deadline | 5 | 7 | W0 |
| [CS-11-004](stories/CS-11-004-read-through-cache-with-stale-fallback.md) | Read-through cache with stale fallback | 5 | 7 | W0 |
| [CS-11-005](stories/CS-11-005-config-stub-service.md) | Config stub service | 2 | 3 | W1 |
| [CS-11-006](stories/CS-11-006-config-client-with-cached-values-and-built-in-defaults.md) | Config client with cached values and built-in defaults | 5 | 5 | W1 |
| [CS-11-007](stories/CS-11-007-config-integration-suite.md) | Config integration suite | 3 | 5 | W1 |
| Total | 7 stories | 27 | 39 | W0, W1 |

Owner: TBD

Status: TBD
