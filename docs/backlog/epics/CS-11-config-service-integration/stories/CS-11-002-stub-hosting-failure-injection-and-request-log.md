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
> > SCOPE-QUESTION (Resolved): Does a lock on the guard class's own monitor keep every guard installed? No. Every frame-log guard of the process replaces the one process-wide default factory of the shaded transport by reading it, wrapping it and publishing the wrapper, and each guard class has a monitor of its own, so separate monitors do not exclude each other: a server install and a client install running at the same time can both read the same factory and each publish a wrapper around it, which loses one wrapper and lets new connections of that end log credential frames once a later context resets the frame logger to DEBUG. Resolution: the synchronization of the "synchronized static `install()`" above is one shared monitor, not the guard class's. (a) One lock: every frame-log guard of the process, the server guard `GrpcServerConfiguration.ServerFrameLogGuard` and the client guards `AuthClient.ClientFrameLogGuard` (CS-12-002), `ConfigClient.ClientFrameLogGuard` (CS-11-006), `ReferenceDataClientConfiguration.ClientFrameLogGuard` (CS-13-003), `CardDataChannelConfiguration.ClientFrameLogGuard` (CS-09-005) and `TransactionActivityClient.ClientFrameLogGuard` (CS-10-006), runs its whole `install()` while holding the monitor of the shaded logger-factory class itself, `synchronized (InternalLoggerFactory.class)` on `io.grpc.netty.shaded.io.netty.util.internal.logging.InternalLoggerFactory`. The guard class's own monitor plays no part. That class object exists once per class loader, the same scope as the default-factory field every guard replaces, so every installer able to replace that factory shares it, and it needs no shared class, no new file and no story edge. (b) Sequence inside the lock: `install()` reads the guard's installed flag and returns at once when it is set. Otherwise it reads the current default factory with `InternalLoggerFactory.getDefaultFactory()`, builds its wrapper around exactly that factory, publishes the wrapper with `InternalLoggerFactory.setDefaultFactory`, and sets the installed flag only after that call returns. (c) Preservation: because the read and the publication happen under the same lock, no installer publishes over a factory it has not read. Each wrapper delegates to the factory it found, the wrappers of earlier guards included, so the chain keeps every guard installed before it, whatever the start order and the thread. No code of the service or its tests other than a guard that follows (a) and (b), the test-local client-side installer of AC7 included, replaces the default factory. `GrpcServerConfiguration`'s call and every call `StubServerHost` makes before it starts a server, in loopback mode and container mode alike, go through this same locked `install()`, so a stub start on one thread and an application context start or a client channel build on another never lose a wrapper. (d) Unchanged: the wrapper behaviour, the rule that the guard is installed before any server of the JVM accepts its first connection, the rule that nothing removes it, the level hold, and the statement that the guard adds no file, no dependency and no environment variable, so the 8 files touched stand.
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
>     > SCOPE-QUESTION (Resolved): Can a reader tell a call the stub has received but not yet listed from a call that never came? Not from `entries` alone, because a `sequence` is assigned at receipt while its entry is listed only when the call closes. Resolution: the route body also carries `lastSequence`, the highest `sequence` the log has assigned at receipt, calls still open included, and `0` before the first call, read together with `entries` under the log's one lock, for example `{"stub":"config","lastSequence":2,"entries":[{"sequence":1,"operation":"cardservicing.config.v1.ConfigService/GetConfig","outcome":"OK","durationMs":3}]}` while call 2 is open. `StubRequestLog` exposes the same value beside its entries, read atomically with them, so loopback suites see it too. No file, dependency or story edge is added, so the 8 files touched stand. CS-01-002 and CS-01-003 rely on it to close their windows only once every received call is listed.
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
>
> > SCOPE-QUESTION (Resolved): Each entry also carries the account identifier of the call's request when that request is a protobuf message with a string field named `account_id` that is non-empty. `StubFailureInjector`, which wraps every call, reads that field from the request message when the message arrives and adds its value to the call's entry. An entry carries no account when the request has no such field, as the probe's string request has none, or when the call closes before its request message arrives. In container mode the JSON entry adds the value as `accountId` only on entries that have one, for example `{"sequence":1,"operation":"cardservicing.transactionactivity.v1.TransactionActivityService/ListTransactions","outcome":"OK","durationMs":3,"accountId":"10000000001"}`, and readers that do not use it ignore it: `E2eClient` (CS-01-002) and the performance harness (CS-01-003). An account identifier is not a credential, and no token, key or other call metadata enters an entry. The account lets the transaction list SLA scenario CS-03-003 take the durations of one account's calls from a window shared with other accounts. `StubFailureInjector` and `StubRequestLog` are already among the 8 files touched, so this story gains no file and the 8 files touched stand.
>
> > SCOPE-QUESTION (Resolved): Does a non-empty `account_id` field make its value safe to keep? No. A field name does not validate its contents, and the injector reads the field before the stub validates anything, so the rule above would keep a card number or a secret sent as `account_id` and return it from `GET /internal/stub/request-log`. Resolution: `StubFailureInjector` adds the field's value to the call's entry only when the value is a well-formed account number, exactly 11 ASCII digits and not all zeros (the pattern `^[0-9]{11}$` without `00000000000`, the rule every contract applies to `account_id`), which the injector checks itself before it adds the value. Any other value, for example the sample card number `9680294154603697`, a value carrying a carriage return or a line feed, a token or a key, a 10- or 12-digit value or `00000000000`, is never copied into the entry, held by the log or logged. The call's entry then keeps the sequence number, the operation, the outcome and the duration with no account, and its container-mode JSON entry has no `accountId` property. Retention never depends on call metadata. The stub still validates no token and authorizes no caller, and the stub's own answer to the call is unchanged. No file, dependency or story edge is added, so the 8 files touched stand.
>
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
>
> > SCOPE-QUESTION (Resolved): The criterion also covers the account. The probe's request is a string with no `account_id` field, so none of the 5 entries carries an account. The account-bearing case is proven by CS-10-004 AC10.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers which account values an entry keeps. `StubHarnessTest` nests a second test-only `StubRegistration`, `AccountProbeStub`, named `account-probe` and declared in `com.example.cardservicing.support`, so `StubHarness.allRegistered()` never discovers it. Its one unary method `probe.v1.ProbeService/EchoAccount` takes the generated Card Data request message `GetAccountRequest` (Java package `com.example.cardservicing.contract.carddata.v1`, string field `account_id`), generated from `card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto` by the CS-05-001 build of both proto roots, and answers each request unchanged, so no `.proto` file, dependency or other file is added and the 8 files touched stand. The calls run against a second harness built with only that registration, so the probe-only harness of AC9 is unchanged. A call with `account_id` `10000000001` produces an entry carrying that account. Calls with `9680294154603697`, with `10000000001` followed by a line feed and `forged`, with `10000000001` followed by a carriage return, a line feed and `forged`, with `00000000000`, with the 10-digit `1234567890` and with a bearer-token-shaped value generated at runtime are each answered `OK`, and each produces an entry with its sequence number, operation, outcome and duration and no account.
>
> - AC7: With no Redis server reachable and no Auth stub registered, a call with no `x-api-key` metadata and a call with `x-api-key` set to `wrong` are both answered `OK`, and the probe's request log records both with outcome `OK`.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers the transport frame loggers. With the root log level and the `io.grpc` log level at DEBUG, probe calls whose `authorization` metadata carries `Bearer ` and a token and whose `x-api-key` metadata carries a key, both generated at runtime so no credential literal is committed and `CommittedSecretScanTest` (CS-11-001) stays green, are answered `OK`, neither value appears in the captured log output, and the effective level of `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` is INFO. The assertion runs in loopback mode, and again after the container-mode Spring Boot test context, which sets `logging.level.root`, `logging.level.io.grpc` and `logging.level.io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` to DEBUG, has started and re-applied its logging settings, against both the container-mode probe and the loopback probe. The capture covers every logger except the client frame logger `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` of the test's own probe channel, whose hold each client story owns (CS-11-006, CS-12-002, CS-13-003), so this test proves the server-side hold alone and never masks a client story's proof of its own.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers servers already running while logger levels change. Two more cases run against the loopback probe that `StubHarness` started, each sending probe calls whose `authorization` and `x-api-key` values are generated at runtime, with output captured by Spring Boot's `OutputCaptureExtension`, which records standard output and standard error and so keeps recording across a logging reset, and with the client frame logger left out as above. First, with no application context running, the test sets the effective level of `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` to DEBUG through `LoggingSystem`, the state a logging reset leaves before any re-application, sends 5 probe calls that are all answered `OK`, and then sets the level back to INFO. Second, the test starts a container-mode application for the probe through `SpringApplicationBuilder`, with the properties and nested test configuration of the container-mode test context, and adds a test listener ordered at `LoggingApplicationListener.DEFAULT_ORDER + 1`, directly after Spring Boot's logging listener. On `ApplicationEnvironmentPreparedEvent`, after that application has initialised logging and applied its DEBUG settings and before any of its beans exists, the listener reads the effective level of `NettyServerHandler` as DEBUG and then sends 5 probe calls to the already-running loopback probe, each with newly generated values, all answered `OK` before the listener returns. The application then reports ready and is closed. None of the values generated in either case appears in the captured output.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers concurrent guard installation. Two more cases in `StubHarnessTest` prove the shared lock of the frame-log guard, so the 8 files touched stand. First, the shared-lock check runs in the test JVM: while the test thread holds the monitor of `io.grpc.netty.shaded.io.netty.util.internal.logging.InternalLoggerFactory`, a second thread calling `GrpcServerConfiguration.ServerFrameLogGuard.install()` reaches thread state `BLOCKED` within 1 s, and `InternalLoggerFactory.getDefaultFactory()` returns the same object as before. Once the test releases the monitor, the call returns within 1 s. The check holds whether or not the guard is already installed, because the installed flag is read inside the lock. Second, concurrent installation with later DEBUG resets runs in fresh JVMs: the test starts 5 child JVMs one after another through the JDK process API, `ProcessBuilder` with the running JVM's `java` command and the test class path, each running a `main` nested in `StubHarnessTest`, so no guard is installed when a child starts. In each child, two threads released together by one `CountDownLatch` call `GrpcServerConfiguration.ServerFrameLogGuard.install()` and the client-side installer, a test-local installer nested in `StubHarnessTest` that follows the same lock, sequence and preservation for `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` and stands in for the client guards that CS-12-002, CS-11-006, CS-13-003, CS-09-005 and CS-10-006 add. The child then starts a new probe server in loopback mode through `StubServerHost` and opens a new plain loopback channel to it, and the `install()` call that `StubServerHost` makes finds the guard already installed and changes nothing. Because `StubServerHost` applies its INFO hold when it starts a server, the child only then sets the root level, `io.grpc`, `io.grpc.netty.shaded.io.grpc.netty.NettyServerHandler` and `io.grpc.netty.shaded.io.grpc.netty.NettyClientHandler` to DEBUG through `LoggingSystem.get(classLoader)`, the state a later context's logging reset leaves; a reset made before that start would be undone by the hold and would hide a lost server wrapper. It then sends 5 probe `Echo` calls, each answered `OK`, whose `authorization` metadata carries `Bearer ` and a token and whose `x-api-key` metadata carries a service key. The parent generates the token and the key at runtime and writes them to the child's standard input, so no credential literal is committed, `CommittedSecretScanTest` (CS-11-001) stays green and neither value appears on a command line. With both frame loggers still at DEBUG, the child prints that `InternalLoggerFactory.getInstance` reports DEBUG disabled for both frame-logger names and exits 0. The parent reads the child's merged standard output and standard error and finds neither value.
>
> - AC8: With the host started in container mode for the probe and 3 gRPC calls made, a `GET /internal/stub/request-log` from the JDK `java.net.http` client on the HTTP port, as `E2eClient` and the performance harness will use, returns status 200 and a JSON body naming stub `probe` with exactly 3 entries whose sequence numbers, operations and outcomes match the calls made.
>   > SCOPE-QUESTION (Resolved): The criterion also covers the received mark: the same body carries `lastSequence` 3.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers the account property. The probe's request names no account, so none of the 3 JSON entries has an `accountId` property. The account-bearing case is proven by CS-10-004 AC10.
>
> > SCOPE-QUESTION (Resolved): The criterion also covers malformed account values over HTTP. A second container-mode test context, with `card-servicing.stub.serve=account-probe`, `card-servicing.grpc.port=0` and a random HTTP port, whose nested test configuration declares the account probe of the AC6 note as a hosted stub, receives the seven `EchoAccount` calls of that note, each answered `OK`. `GET /internal/stub/request-log` then returns status 200 and 7 entries in call order, and only the entry of the `10000000001` call has an `accountId` property, with that value. Neither the response body nor any parsed entry contains any of the six malformed values.
>
> - AC9: A harness built with only the probe registration starts exactly one stub, and after the last test every port it bound is released: a new server socket binds to each recorded port on `127.0.0.1`.
> - AC10: A call with a 100 ms client deadline under a per-test latency override of 300 ms ends with `DEADLINE_EXCEEDED` at the client. Once the 300 ms delay has elapsed, the request log holds exactly one entry for it, with outcome `CANCELLED`, and the probe's service is invoked 0 times.
>   > SCOPE-QUESTION (Resolved): The criterion also covers the received mark: during the 300 ms delay the log reports `lastSequence` 1 and no entry, and once the delay has elapsed it reports `lastSequence` 1 and exactly the one `CANCELLED` entry.
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

- Unit: Applies. Command `./gradlew :card-servicing-service:test jacocoTestCoverageVerification`; all tests pass and line coverage is at least 80% per module. Cases: loopback start and answer, latency and failure-rate overrides at their boundaries, override isolation and rejection, request-log entries and order, the request-log account kept only for a well-formed account number and left out, in loopback entries and in the container-mode log read, for a card number, line-feed and carriage-return values, the all-zero and 10-digit values and a runtime-generated token-shaped value, the cancelled call, service-key indifference, the server frame-logger guard with runtime-generated credentials at root and `io.grpc` DEBUG in loopback mode, with the server frame logger forced to DEBUG on the running loopback probe, during a later container-mode application's logging initialisation against the already-running loopback probe, and after a container-mode context start, the shared installation lock of the frame-log guard, and its concurrent installation beside the other-end guard in five fresh JVMs with both frame loggers reset to DEBUG, the container-mode log read over HTTP, port release, discovery, the unknown stub name, and the packed `ErrorObject` with code `UNAVAILABLE`, a distinct 32-character lower-case hexadecimal `traceId` and empty details on every injected failure.
- Integration: Not applicable — this story ships the hosting the external integration suites use, not an external suite. Threshold when it applies: `./gradlew :card-servicing-service:integrationTest` passes, with one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS` and the `UPSTREAM_MAX_RETRIES` cap.
- Performance: Not applicable — no request path; it applies to balance-retrieval and drill-down stories. Threshold when it applies:
  - Commands: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down`.
  - Pass: p50, p95 and p99 below 1,000 ms over successful requests, with failure injection off, at 10 users × 100 sequential calls, 100,000 category balances and 1,000,000 transactions.
  - Injected failures are proven by a logged `cutoffMs` ≤ 900 and a client-measured response below 1,000 ms.
- End-to-end: Not applicable — no UI interaction step; the end-to-end client reads the request log this story exposes from CS-01-002 on. Threshold when it applies: `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down`; the four-step flow for account `00000000001` returns the expected fixture values, and each stub's request log shows at least one call.

## 7. Validation Results

Completed during implementation.

## Implementation Design

Recommended: Design Option A — real servers on loopback ports with injection as a server interceptor.

Rationale: real ports exercise the contract code and deadline handling of a real outage.

- Rejected: Option B, the in-process transport, for its unlisted library and missing network.
- Rejected: mock providers (decision D3), which leave the contracts unexercised.

Shared by both options:

- Topology: one server per stub, in tests or the shared stack.
- Naming: stubs carry the mocked system's name.
- Addressing: each client receives its stub's address.
- Isolation: overrides and request logs reset around each test.
- Fallback: none, since a stopped stub answers unavailable.
- Provisioning: the harness starts and stops stubs around each test class.
- Cut-over: a real-service swap changes only client addresses and credentials.
- Failures: injected failures carry the standard error object and are logged.

### Design Option A

Each stub is a real server on a loopback port or in its own container. One interceptor per stub delays calls and fails a configured share.

How it behaves:

- Per-test overrides beat per-stub values and settings, and out-of-range overrides are refused.
- An injected failure names no credential and carries its own trace identifier.
- A call abandoned during the delay is logged as cancelled.
- A log entry keeps an account number only when it is well formed.
- A header-logging guard no log level lifts is fitted before the first server, under one lock shared by every such guard of the process, and keeps the guards installed before it.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | Loopback servers (vs in-process transport) | timeouts act on a real channel | each stub takes a runner port |
| 2 | Interceptor injection (vs per-stub injection) | stubs need no failure code | the interceptor must watch for cancellation |
| 3 | Log read over HTTP (vs shared-volume log files) | the existing web layer serves it | the log page stays outside the contracts |
| 4 | Process-wide header-logging guard (vs a resettable level hold) | header logging stays off for every server's life | server frame traces are lost to debugging |

Effort and risk: a hosting layer, an interceptor, a log and a test harness, with timing assertions at risk on loaded runners.

### Design Option B

Each stub runs on the in-process transport with the same interceptor and log, binding no port. Container mode keeps a separate network server.

How it differs:

- Clients receive an in-process name instead of a port.
- Cancellation travels by in-process signalling.
- The container path needs its own header-logging guard.
- The build gains an unlisted library.

| No. | Decision (vs rejected) | Pro | Con |
|---|---|---|---|
| 1 | In-process transport (vs loopback servers) | no runner port is taken | real outages never reach the channel |
| 2 | Same interceptor point (vs a fault proxy) | injection code is shared | two hosting paths must stay in step |
| 3 | Separate container network path (vs one hosting path) | unit tests avoid the network | the unit stage never proves containers |

Effort and risk: a smaller harness but two hosting paths, with container drift at risk.

### Recommendation

- Option: Option A.
- Why: one hosting path serves tests and containers on the real client channel.
- Strongest argument against: loopback stubs compete for ports on shared runners.
- Shared items that do not decide the choice: the interceptor, the request log, test isolation and the cut-over.
- Flip condition: the in-process library is listed, with a unit-proven container path.

## Design Validation

### Option A validation

Settled assumptions:

- The pinned library and existing web layer host stubs and log pages.
- The configuration story range-checks the zero-default delay and failure-rate settings.
- The transport takes each connection's header logger from a replaceable factory.

Open assumptions:

- Open → the Compose stack story settles each stub container's fixed ports.
- Open → the end-to-end and performance stories settle the log page as their only source.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Holds | a real loopback server answers |
| AC2 | Conditional | a loaded runner only lengthens delays |
| AC3 | Holds | injection precedes the stub |
| AC4 | Holds | overrides clear between tests |
| AC5 | Holds | overrides are checked at set time |
| AC6 | Holds | the log numbers calls at receipt |
| AC7 | Holds | a stub carries only its injector behind a lifetime header-logging guard |
| AC8 | Holds | the web layer serves the log page |
| AC9 | Holds | stopping waits for port release |
| AC10 | Conditional | the interceptor must check cancellation at delay end |
| AC11 | Holds | discovery reads only the integration package |
| AC12 | Holds | the name lookup precedes port binding |

### Option B validation

Settled assumptions:

- The in-process library is absent from the dependency table.
- Container mode still needs a network server.
- Option A's web-layer, range-check and header-logger assumptions hold.

Open assumptions:

- Open → a dependency decision settles listing the in-process library.
- Open → the Compose stack story settles how the second path is proven.

| AC | Verdict | Why |
|---|---|---|
| AC1 | Breaks | an in-process stub binds no port |
| AC2 | Holds | the delay point is unchanged |
| AC3 | Holds | the injection point is unchanged |
| AC4 | Holds | override clearing is unchanged |
| AC5 | Holds | the override check is unchanged |
| AC6 | Holds | the request log is unchanged |
| AC7 | Conditional | the separate container path needs its own header-logging guard |
| AC8 | At risk | the unit stage skips the container network path |
| AC9 | Conditional | only the container path binds ports |
| AC10 | Conditional | cancellation must reach the delayed interceptor in process |
| AC11 | Holds | discovery is transport-independent |
| AC12 | Holds | the name lookup is transport-independent |

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
