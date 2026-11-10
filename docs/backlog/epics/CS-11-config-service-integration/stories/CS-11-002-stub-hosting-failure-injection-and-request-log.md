# CS-11-002: Stub hosting, failure injection and request log

Parent epic: [CS-11 Config Service Integration](../README.md)

## Source Story (verbatim)

> ### 1. Details/Requirements
>
> As a test engineer, I want every mocked upstream hosted the same way with injectable latency and failures and a request log, so that each failure mode is reproducible on a real channel.
>
> Trace: CARD_REQ_TBD · Use Cases · COTRN00C
>
> Directive: stub all five external calls
>
> Account-data access path: No
>
> External integration or SLA-bound: No
>
> Anchor basis: Use Cases · COTRN00C fills a fixed ten-row page set inside the program (PROCESS-PAGE-FORWARD); that screen behaviour is what this epic turns into configured page size and call settings. This story hosts the stubs against which those settings, and every upstream failure mode, are exercised.
>
> Capability: one gRPC hosting layer on the pinned `grpc-netty-shaded` serves the Card Servicing API and every stub. A stub runs as a real gRPC server on a loopback port inside the test JVM for unit and integration suites, or alone in its own container in the Compose stack. Every stub call passes through one failure injector that applies the latency and failure-rate settings, then lands in that stub's request log with its operation, outcome status and duration. Target paths:
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/config/GrpcServerConfiguration.java`:
>   - Owns the process's gRPC listening port, read from `card-servicing.grpc.port` through a placeholder whose default `0` asks the operating system for a free port, so concurrent test contexts never collide. The Compose stack (CS-01-001) and deployed environments set a fixed port. This property and `card-servicing.stub.serve` select the process's hosting shape and are not variables of the TRD environment-variable table, so each carries its default in its own placeholder and `card-servicing-service/src/main/resources/application.yml` (CS-11-001) stays unchanged.
>   - Service mode, when `card-servicing.stub.serve` is empty (its default): builds one server on that port that hosts every `io.grpc.BindableService` bean and every `io.grpc.ServerInterceptor` bean that later stories declare for the Card Servicing API, in bean order, and no stub.
>   - Container mode, when `card-servicing.stub.serve` names a stub: builds no server of its own, hosts no Card Servicing API service and hands the port to `StubServerHost`.
>   - Starts the server when the application context is ready, stops it when the context closes, and logs the bound port.
>
> > SCOPE-QUESTION (Resolved): Before any server of the process starts, in service mode and container mode alike, `GrpcServerConfiguration` holds the gRPC Netty transport's server frame logger `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` at INFO through Spring Boot's `LoggingSystem`, whatever the root level or any `logging.level` setting, because at DEBUG that logger writes every inbound header value, `authorization` and `x-api-key` included, and one logger serves every server in the process, the API's and each stub's. Spring Boot resets logger levels when it initialises logging and re-applies the `logging.level` settings on every application context start, test contexts started after loopback stubs included, so `GrpcServerConfiguration` re-applies the hold during each context start, after those settings and before its own server or the container-mode `StubServerHost` server starts, and before the context reports ready. The hold adds no file, no dependency and no environment variable.
>
> > SCOPE-QUESTION (Resolved): The level hold above does not by itself keep credentials out of the log. A later application context in the same JVM, such as the container-mode test context started while loopback stubs run, initialises logging and applies its own `logging.level` settings when its environment is prepared, before `GrpcServerConfiguration` re-applies the hold, and during that interval the frame logger of every server already running reads DEBUG. `GrpcServerConfiguration` therefore also owns a credential guard that no logger level can lift: its nested class `GrpcServerConfiguration.ServerFrameLogGuard`, whose idempotent, synchronized static `install()` replaces the default logger factory of the shaded Netty transport, through `io.grpc.netty.shaded.io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory`, with a wrapper around the factory it replaces. The wrapper hands every logger name to that factory unchanged except `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler`, for which it returns a logger that reports TRACE and DEBUG as disabled, discards every TRACE and DEBUG event and passes INFO and above to the delegate. The transport builds the frame logger of each connection it accepts from that factory and asks the logger whether DEBUG is enabled before it writes a frame, so no frame of a guarded connection reaches a log line, whatever level `LoggingSystem`, a `logging.level` setting, a later context's logging reset or a runtime level change gives the logger. `GrpcServerConfiguration` calls `install()` before its own server or the container-mode `StubServerHost` server starts, and `StubServerHost` calls it before it starts each server, so the guard is in place before any server of the JVM accepts its first connection. A second call finds the wrapper already installed and changes nothing, and nothing removes it, so it holds for as long as any server in the JVM accepts calls, across every later context start and close. The level hold stays, so the effective level AC7 reads is INFO in loopback mode and once a context has started, but the guard, not the level, is the never-log protection. The client frame logger `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` is passed through unchanged, because each client story owns its hold. The guard adds no file, no dependency, since the factory ships in the pinned `grpc-netty-shaded`, and no environment variable.
>
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/stub/StubServerHost.java`:
>   - A hosted stub is a stub name plus its `io.grpc.ServerServiceDefinition`, held in the nested record `StubServerHost.HostedStub`. The five stub names are `card-data`, `transaction-activity`, `config`, `reference-data` and `auth`.
>   - Loopback mode: starts one server per hosted stub bound to `127.0.0.1` on port `0`, and reports each bound port so a test hands it to the client under test.
>   - Container mode: serves the one `HostedStub` bean whose stub name equals `card-servicing.stub.serve` on the configured port on all interfaces. Startup fails with a message naming `card-servicing.stub.serve` when no hosted stub carries that name. Each stub story declares its `HostedStub` bean only when `card-servicing.stub.serve` names that stub, so the Card Servicing service process never builds a stub or opens a store connection.
>   - Each hosted stub gets its own `StubFailureInjector`, its own `StubRequestLog` and no other interceptor, so a stub never validates a token, never reads the `x-api-key` service key, never calls Auth and never opens a Redis connection.
>
> > SCOPE-QUESTION (Resolved): Before it starts each server, in loopback mode and container mode alike, `StubServerHost` holds the server frame logger `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` at INFO through Spring Boot's `LoggingSystem`, whatever the root level, so a stub's transport never logs the bearer token or the `x-api-key` value a call carries. It obtains the logging system with `LoggingSystem.get(classLoader)`, since `StubHarness` starts loopback stubs with no application context. Loopback stubs already running when a later application context resets logger levels are held again by the re-application `GrpcServerConfiguration` makes during that context's start.
>
> > SCOPE-QUESTION (Resolved): Loopback stubs already running when a later application context resets logger levels are protected by the guard, not by that context's re-application of the hold. Before it starts each server, in loopback mode and container mode alike, `StubServerHost` calls `GrpcServerConfiguration.ServerFrameLogGuard.install()`, which needs no application context, so the first stub `StubHarness` starts installs it and every connection of every stub server is guarded from its first call. A credential-bearing call to a running stub therefore writes no frame line between a later context's logging initialisation and its re-application of the hold.
>
>   - `stop()` shuts down every server it started and waits for termination, so every bound port is released before it returns. A stopped stub answers its clients with `UNAVAILABLE`, as a real outage would.
>   - Out-of-process log read: in container mode it serves the read-only route `GET /internal/stub/request-log` on the Spring Boot HTTP port (`server.port`) through the Spring MVC web layer the service already runs for its REST endpoints, so the build gains no dependency. The response is status 200 with a JSON body naming the stub and listing its entries in receipt order, for example `{"stub":"config","entries":[{"sequence":1,"operation":"cardservicing.config.v1.ConfigService/GetConfig","outcome":"OK","durationMs":3}]}`, with an empty `entries` array before the first call. `E2eClient` (CS-01-002) and the performance harness in `tests/performance/` (CS-01-003) read it. It is test infrastructure, not a baseline operation, so it has no `.proto` or OpenAPI file.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/stub/StubFailureInjector.java`:
>   - An `io.grpc.ServerInterceptor`. For each call it takes the effective latency and failure rate, holds the call for the latency, and then either closes it with `UNAVAILABLE` and the description `injected failure`, at the failure rate, or passes it to the stub.
>
> > SCOPE-QUESTION (Resolved): Every stub contract's `Errors:` block requires each non-OK status to pack `cardservicing.common.v1.ErrorObject` into the `google.rpc.Status` details, and an injected failure is a non-OK status, so the injector carries that object too. It issues each call a `traceId` at receipt, before the delay, as a 32-character lower-case hexadecimal value. An injected failure closes the call with `UNAVAILABLE`, the description `injected failure` and exactly one `ErrorObject` packed into the `google.rpc.Status` details through `io.grpc.protobuf.StatusProto` from `grpc-protobuf`, already pinned by CS-05-001. The object carries `code` `UNAVAILABLE`, `message` `Injected stub failure`, which names no token, key or call metadata, that `traceId` and an empty `details` list. Its message class is generated from `card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto` by the CS-05-001 build of both proto roots, so this story gains no dependency, no story edge and no file, and the 8 files touched stand. The delay, the cancellation check, the request-log entry (outcome `UNAVAILABLE`, duration from receipt to close) and the stub's own answers are unchanged.
>
>   - Effective values come from the first one set of: the per-test override, the per-stub override, then `card-servicing.stub.latency-ms` and `card-servicing.stub.failure-rate`, which CS-11-001 binds from `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` and range-checks at startup. In the Compose stack each stub container carries its own `STUB_LATENCY_MS` and `STUB_FAILURE_RATE`, which is its per-stub override.
>   - An override latency below 0, or an override failure rate outside 0 to 1 inclusive, is rejected when set with an `IllegalArgumentException` naming `STUB_LATENCY_MS` or `STUB_FAILURE_RATE`, and the effective values stay unchanged.
>   - When its delay ends it checks whether the client has cancelled the call or let its deadline expire; such a call is recorded with outcome `CANCELLED` and never reaches the stub.
> - `card-servicing-service/src/main/java/com/example/cardservicing/integration/stub/StubRequestLog.java`:
>   - A thread-safe log holding one entry per call: a sequence number assigned at receipt, the full operation name (for example `cardservicing.config.v1.ConfigService/GetConfig`), the outcome status name (`OK`, `UNAVAILABLE`, `CANCELLED` or the stub's own status) and the duration in ms from receipt to close.
>   - Entries are listed in receipt order, and injected answers sit beside real ones.
>   - The log lives as long as its stub's server, so a Compose down clears it. An out-of-process reader takes the entries above the last sequence number it saw.
> - `card-servicing-service/src/test/java/com/example/cardservicing/support/StubHarness.java`:
>   - A JUnit 5 extension. `StubHarness.allRegistered()` discovers every concrete `StubRegistration` class in the test classpath under the package `com.example.cardservicing.integration` through Spring classpath scanning, and `StubHarness.of(StubRegistration...)` takes an explicit list.
>   - Before the first test of a class it starts each registered stub in loopback mode through `StubServerHost`. Before each test it clears every request log. After each test it clears every per-test override. After the last test it stops every stub.
>   - It exposes each stub's bound port, request log and effective latency and failure rate, and the per-test override `inject(stubName, latencyMs, failureRate)`.
> - `card-servicing-service/src/test/java/com/example/cardservicing/support/StubRegistration.java`: the interface each stub story implements once in its test sources under `com.example.cardservicing.integration`. It supplies the stub name, the service definition built over the stub's small in-memory fixture set, and an optional per-stub latency and failure rate.
> - `card-servicing-service/src/test/java/com/example/cardservicing/support/ProbeStub.java`: a test-only `StubRegistration` named `probe` in `com.example.cardservicing.support`, outside the discovered package, so only a harness that names it starts it. Its service definition is hand-built with one unary method `probe.v1.ProbeService/Echo` and a UTF-8 string marshaller, and it answers each request with the request text. It adds no `.proto` file, so the buf modules and the contract checks stay unchanged.
> - `card-servicing-service/src/test/java/com/example/cardservicing/support/StubHarnessTest.java`: the tests of section 2, run with `StubHarness.of(new ProbeStub())`. The container-mode tests start a Spring Boot test context with `card-servicing.stub.serve=probe`, `card-servicing.grpc.port=0` and a random HTTP port, whose nested test configuration declares the probe as a hosted stub.
> - Together these are the 8 files touched.
>
> A suite sees exactly the stubs whose stories have landed: CS-11-005 (Config), CS-12-001 (Auth), CS-13-002 (Reference Data), CS-09-004 (Card Data) and CS-10-004 (Transaction Activity, extended by CS-10-005). Each of those stories registers its own stub through `StubRegistration`.
>
> In scope:
>
> - The 8 files above.
> - gRPC hosting in service mode, loopback mode and container mode.
> - Failure injection with per-stub and per-test overrides.
> - The request log and its out-of-process read in container mode.
> - The harness lifecycle and stub discovery.
> - The probe stub.
>
> Not in this story:
>
> - The individual stubs and their registrations: Config (CS-11-005), Auth (CS-12-001), Reference Data (CS-13-002), Card Data (CS-09-004), Transaction Activity listing (CS-10-004) and single-transaction lookup (CS-10-005).
> - The Compose stack, its stub containers and their fixed ports (CS-01-001).
> - `E2eClient`, the end-to-end reader of the request log (CS-01-002).
> - The performance harness that reads stub durations (CS-01-003).
> - Client timeouts, retries, backoff and the request deadline (CS-11-003).
> - Binding and startup range checks of `STUB_LATENCY_MS` and `STUB_FAILURE_RATE` (CS-11-001).
> - The Card Servicing API services and interceptors the hosting layer serves: the error interceptor (CS-05-002), the account access guard (CS-12-003) and the endpoints (CS-05-006, CS-06-003, CS-07-003, CS-08-003).
>
> ### 2. Acceptance Criteria
>
> - AC1: A probe registered through `StubRegistration` and started by `StubHarness` before the first test answers an `Echo` call with status `OK` and the request text, on a server bound to `127.0.0.1` at the port the harness reports, inside the test JVM.
> - AC2: With a per-test latency override of 200 ms (`STUB_LATENCY_MS`), the probe's request-log entry shows a duration of at least 200 ms, and the client receives the answer no earlier than 200 ms after sending.
> - AC3: With a per-test failure-rate override of 1.0 (`STUB_FAILURE_RATE`), 20 of 20 calls return `UNAVAILABLE` and the probe's service is invoked 0 times. With an override of 0, 0 of 20 calls return `UNAVAILABLE`.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers the injected error object. Each of the 20 `UNAVAILABLE` answers at failure rate 1.0, unpacked at the client with `io.grpc.protobuf.StatusProto.fromThrowable`, holds exactly one `ErrorObject` in the `google.rpc.Status` details with `code` `UNAVAILABLE`, `message` `Injected stub failure`, a 32-character lower-case hexadecimal `traceId` and an empty `details` list. The 20 `traceId` values are distinct, and the probe's request log records each of the 20 calls with outcome `UNAVAILABLE`.
>
> - AC4: An override set in one test does not leak: after a test that sets failure rate 1.0 and latency 200 ms, the next test's 20 calls all return `OK`, and the effective latency and failure rate equal the configured `card-servicing.stub.latency-ms` and `card-servicing.stub.failure-rate` values.
> - AC5: A per-test failure-rate override of 1.5 or -0.1, and a latency override of -1, are each rejected with an `IllegalArgumentException` naming `STUB_FAILURE_RATE` or `STUB_LATENCY_MS` respectively. The effective values stay unchanged and the probe's request log stays empty. Failure-rate overrides of exactly 0 and exactly 1 are accepted.
> - AC6: Every request-log entry carries a sequence number, the full operation name `probe.v1.ProbeService/Echo`, the outcome status name and the duration in ms. Three sequential calls at failure rate 0 followed by two at failure rate 1.0 in one test produce exactly 5 entries, with sequence numbers 1 to 5 and outcomes `OK`, `OK`, `OK`, `UNAVAILABLE`, `UNAVAILABLE` in call order.
> - AC7: With no Redis server reachable and no Auth stub registered, a call with no `x-api-key` metadata and a call with `x-api-key` set to `wrong` are both answered `OK`, and the probe's request log records both with outcome `OK`.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers the transport frame loggers. With the root log level and the `io.grpc` log level at DEBUG, probe calls whose `authorization` metadata carries `Bearer ` and a token and whose `x-api-key` metadata carries a key, both generated at runtime so no credential literal is committed and `CommittedSecretScanTest` (CS-11-001) stays green, are answered `OK`, neither value appears in the captured log output, and the effective level of `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` is INFO. The assertion runs in loopback mode, and again after the container-mode Spring Boot test context, which sets `logging.level.root`, `logging.level.io.grpc` and `logging.level.io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` to DEBUG, has started and re-applied its logging settings, against both the container-mode probe and the loopback probe. The capture covers every logger except the client frame logger `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` of the test's own probe channel, whose hold each client story owns (CS-11-006, CS-12-002, CS-13-003), so this test proves the server-side hold alone and never masks a client story's proof of its own.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers servers already running while logger levels change. Two more cases run against the loopback probe that `StubHarness` started, each sending probe calls whose `authorization` and `x-api-key` values are generated at runtime, with output captured by Spring Boot's `OutputCaptureExtension`, which records standard output and standard error and so keeps recording across a logging reset, and with the client frame logger left out as above. First, with no application context running, the test sets the effective level of `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` to DEBUG through `LoggingSystem`, the state a logging reset leaves before any re-application, sends 5 probe calls that are all answered `OK`, and then sets the level back to INFO. Second, the test starts a container-mode application for the probe through `SpringApplicationBuilder`, with the properties and nested test configuration of the container-mode test context, and adds a test listener ordered at `LoggingApplicationListener.DEFAULT_ORDER + 1`, directly after Spring Boot's logging listener. On `ApplicationEnvironmentPreparedEvent`, after that application has initialised logging and applied its DEBUG settings and before any of its beans exists, the listener reads the effective level of `NettyServerHandler` as DEBUG and then sends 5 probe calls to the already-running loopback probe, each with newly generated values, all answered `OK` before the listener returns. The application then reports ready and is closed. None of the values generated in either case appears in the captured output.
>
> - AC8: With the host started in container mode for the probe and 3 gRPC calls made, a `GET /internal/stub/request-log` from the JDK `java.net.http` client on the HTTP port, as `E2eClient` and the performance harness will use, returns status 200 and a JSON body naming stub `probe` with exactly 3 entries whose sequence numbers, operations and outcomes match the calls made.
> - AC9: A harness built with only the probe registration starts exactly one stub, and after the last test every port it bound is released: a new server socket binds to each recorded port on `127.0.0.1`.
> - AC10: A call with a 100 ms client deadline under a per-test latency override of 300 ms ends with `DEADLINE_EXCEEDED` at the client. Once the 300 ms delay has elapsed, the request log holds exactly one entry for it, with outcome `CANCELLED`, and the probe's service is invoked 0 times.
> - AC11: `StubHarness.allRegistered()` starts exactly the concrete `StubRegistration` classes found under `com.example.cardservicing.integration` in the test classpath, and the probe is never among them.
> - AC12: Container mode with `card-servicing.stub.serve` set to `unknown` and `card-servicing.grpc.port` set to a free port fails application startup with a message naming `card-servicing.stub.serve`, and a new server socket binds to that port after the failure.
>
> ### 3. Dependencies
>
> - CS-11-001 — binds the stub latency and failure-rate settings, with their startup range checks, that the failure injector reads.
>
> Risk: a loaded CI runner can stretch measured durations, so every duration assertion uses a lower bound only and never an upper bound.

## 4. Design

- [Logical architecture](../../../../architecture/logical-architecture.mmd)
- [Physical deployment](../../../../architecture/physical-deployment.mmd)
- [Data model — entity relationship diagram](../../../../data-model.md#entity-relationship-diagram)
- [TRD — D3 stub hosting and swap point](../../../../technical-requirements.md#d3-stub-hosting-and-swap-point)
- [TRD — resilience: failure injection and request log](../../../../technical-requirements.md#resilience)
- [TRD — test and performance harness](../../../../technical-requirements.md#test-and-performance-harness)
- [TRD — environment variables](../../../../technical-requirements.md#environment-variables)

Contracts: N/A

## 5. Documentation Changes

Completed during implementation.

## 6. Test Plan

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: loopback start and answer, latency and failure-rate overrides at their boundaries, override isolation and rejection, request-log entries and order, the cancelled call, service-key indifference, the server frame-logger guard with runtime-generated credentials at root and `io.grpc` DEBUG in loopback mode, with the server frame logger forced to DEBUG on the running loopback probe, during a later container-mode application's logging initialisation against the already-running loopback probe, and after a container-mode context start, the container-mode log read over HTTP, port release, discovery, the unknown stub name, and the packed `ErrorObject` with code `UNAVAILABLE`, a distinct 32-character lower-case hexadecimal `traceId` and empty details on every injected failure.
- Integration: Not applicable — this story ships the hosting the external integration suites use, not an external suite. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — no request path; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI interaction step; the end-to-end client reads the request log this story exposes from CS-01-002 on. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — real remote procedure servers on loopback ports with injection as a server interceptor.

Rationale: a real server on a real port runs the generated contract code, the real serialization and the real deadline handling, so a failure reproduced against a stub is the failure a client meets in an outage.

- Rejected: Option B, the in-process transport, because its library is absent from the dependency table and it carries no real network channel.
- Rejected: mock-provider objects with no remote procedure channel, already set aside in decision D3, because the contracts go unexercised.

Shared by both options:

- Topology: one server per stub in the test process, and one container per stub in the shared stack.
- Naming: each stub carries the name of the external system it mocks, and each log entry carries the full operation name.
- Addressing: the harness hands each client under test the address of its stub when the stub starts, and a stub container listens on the port the shared stack sets.
- Isolation: per-test overrides are cleared after each test, and request logs are cleared before each test.
- Fallback: none, since a stopped stub answers with the unavailable status as a real outage would.
- Provisioning: the test harness starts the registered stubs before the first test and stops them after the last.
- Cut-over: the real-service swap changes only the address and credential source of each client.
- Failures: injected delays and unavailable answers are recorded in the request log beside real answers, and every injected unavailable answer carries the standard error object with a trace identifier issued when the call arrives.

### Design Option A

Each stub runs as a real remote procedure server bound to a loopback port inside the test process, or alone in its own container in the shared stack. One server interceptor per stub holds each call for the configured delay and answers a configured share of calls with the unavailable status before the stub sees them. Every call, injected or answered, lands in that stub's request log, and in container mode the log is readable over HTTP from outside the container.

How it behaves:

- The harness starts each registered stub on a free loopback port before the first test and hands that port to the client under test.
- A per-test override takes precedence over a per-stub value, which takes precedence over the latency setting and the failure-rate setting.
- An override outside its range is refused when set, before any call is answered.
- An injected unavailable answer carries the standard error object, with the unavailable code, a fixed message that names no credential, its own trace identifier issued before the delay and an empty field list, as every contract requires of an error.
- A call the client abandons during the delay is logged as cancelled and never reaches the stub.
- In container mode each stub serves a read-only log page on the web port of its container, which the end-to-end client and the performance harness read.
- Before the first server of the process starts, the hosting layer fits the transport with a header-logging guard that stays in place for the life of the process, so no credential header reaches a log line at any log level, a later application start included.
- After the last test every server shuts down and waits until its port is free.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Real loopback servers (vs in-process transport) | timeouts act on a real network channel | each stub takes one port on the runner |
| 2 | Injection in a server interceptor (vs injection inside each stub) | every stub fails the same way with no stub code | the interceptor must notice cancellation during its delay |
| 3 | Log read over HTTP in container mode (vs log files on a shared volume) | readers need only the web layer already in the build | the log page must stay out of the contracts |
| 4 | Transport header logging switched off by a process-wide guard (vs relying on a logger level hold that an application start resets) | no logging reset or level change can reopen header logging while a server runs | server frame traces are lost to anyone debugging a stub for the life of the process |

Effort and risk: one hosting layer, one interceptor, one log and a four-part test harness, with the risk in timing assertions on loaded runners.

### Design Option B

Each stub runs on the in-process transport inside the test process, with injection at the same interceptor point and the same request log. Calls never leave the process, so no port is bound and no network channel is crossed. Container mode keeps a separate network server path, because the in-process transport cannot serve a client in another container.

How it differs:

- The harness hands clients an in-process name instead of a port.
- Deadlines and cancellation travel through in-process signalling rather than a network stream.
- Container mode keeps a second hosting path with its own network server.
- The build gains the in-process transport library, which the dependency table does not list.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | In-process transport (vs loopback servers) | no port is taken on shared runners | the channel is not the one a real outage breaks |
| 2 | Same interceptor point for injection (vs a separate fault proxy) | injection code is shared with container mode | two hosting paths must stay in step |
| 3 | Separate network path for containers (vs one hosting path) | unit tests avoid the network entirely | container behaviour is never proven by the unit stage |

Effort and risk: a smaller harness but two hosting paths and an unlisted library, with the risk in container behaviour drifting from what the tests prove.

### Recommendation

- Option: Option A.
- Why: one hosting path serves tests and containers alike, and every failure mode is reproduced on the real channel a client uses.
- Strongest argument against: each loopback stub binds a real port on shared build runners, where parallel suites compete for the free port range.
- Shared items that do not decide the choice: the interceptor point, the request log, the per-test isolation, the stopped-stub behaviour and the address-only cut-over.
- Flip condition: the in-process library enters the dependency table together with a separate container-mode path that the unit stage also proves.

## Design Validation

### Option A validation

Settled assumptions:

- The remote procedure library already pinned hosts both the service and every stub, so the build gains no library.
- The latency setting and the failure-rate setting default to zero and are bound and range-checked by the configuration-properties story.
- The web layer already in the build serves the read-only log page.
- A stopped stub answers its clients with the unavailable status.
- The standard error message type is generated by the scaffold build, so packing it into an injected answer adds no library.
- The transport writes header values only through its frame logger, which it takes from a replaceable logger factory for each new connection and which checks whether debug output is enabled before each frame.

Open assumptions:

- Open → the Compose stack story settles the fixed remote procedure port and web port of each stub container.
- Open → the end-to-end client story and the performance harness story settle that they read the log page and no other source.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | the harness binds a real loopback server before the first test |
| AC2 | Conditional | a loaded runner stretches the delay but never shortens it |
| AC3 | Holds | the interceptor answers with the standard error object before the stub at rate one and never at rate zero |
| AC4 | Holds | the harness clears every override after each test |
| AC5 | Holds | each override is checked when set, before any call |
| AC6 | Holds | the log numbers each call at receipt |
| AC7 | Holds | a stub carries only its injector, with a header-logging guard fitted before the first server starts and kept through every later logging reset |
| AC8 | Holds | the web layer serves the log page on the web port of the container |
| AC9 | Holds | stopping waits for each server to release its port |
| AC10 | Conditional | the interceptor must check for cancellation when its delay ends |
| AC11 | Holds | discovery reads only the integration test package |
| AC12 | Holds | container start looks the stub name up before binding a port |

### Option B validation

Settled assumptions:

- The in-process transport library is absent from the dependency table and would have to be added.
- The latency setting and the failure-rate setting default to zero and are bound and range-checked by the configuration-properties story.
- Container mode still needs a network server.
- The standard error message type is generated by the scaffold build, so packing it into an injected answer adds no library.

Open assumptions:

- Open → a dependency decision settles whether the in-process library may join the dependency table.
- Open → the Compose stack story settles how the second hosting path is proven.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | an in-process stub binds no loopback port |
| AC2 | Holds | the delay applies at the same interceptor point |
| AC3 | Holds | the interceptor answers with the standard error object before the stub at the same rates |
| AC4 | Holds | override clearing is unchanged |
| AC5 | Holds | the override check is unchanged |
| AC6 | Holds | the request log is unchanged |
| AC7 | Conditional | the separate container path must fit its own header-logging guard before its server starts |
| AC8 | At risk | container mode needs a separate network path the unit stage does not share |
| AC9 | Conditional | only the separate container path binds ports to release |
| AC10 | Conditional | in-process cancellation must reach the interceptor during its delay |
| AC11 | Holds | discovery does not depend on the transport |
| AC12 | Holds | the stub name lookup does not depend on the transport |

## Metadata

| Field | Value |
|---|---|
| Build wave | W0 |
| Depends on | CS-11-001 |
| Status | TBD |
| Estimated Files Touched | 8 |
| Issue Type | Story |
| Epic Name | Config Service Integration |
| Epic Link | CS-11 |
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
