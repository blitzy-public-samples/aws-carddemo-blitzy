package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds every service to the same request-surface controls, in code and in shipped configuration.
 *
 * <p>Two controls answer one finding each. The first is that HTTP Basic is replayable by a browser:
 * it attaches a cached credential to a request a foreign page caused, and the bodyless
 * {@code POST /accounts/{accountId}/cycle-close} is submittable by an HTML form, so a page an
 * administrator visited could zero both billing-cycle accumulators that
 * {@code app/cbl/CBTRN02C.cbl:L403-L413} authorizes against. The second is that nothing bounded a
 * request rate anywhere, so a caller could spend a service's processor on bcrypt verifications for
 * as long as it liked.
 *
 * <p>A third finding concerned where the two controls run rather than what they test. Neither
 * declared an order, so both ran after the security chain and every refusal was paid for with the
 * bcrypt verification of a request the service was about to turn away. The ladder in
 * {@code FILTER_ORDER_OFFSETS} moves all four filters ahead of the chain, and the deferral in
 * {@code DEFERRING_FILTERS} is what keeps that move from changing any answer a caller sees.
 *
 * <p>Six copies of a control drift unless something reads all six. Each assertion below reads the
 * shipped files, so a service configured unlike its peers fails the unit-test phase rather than
 * being noticed in a review.
 */
@DisplayName("Request-surface controls across the six services")
class RequestSurfaceControlContractTest {

    /** The six service modules, in the order the plan lists them. */
    private static final List<String> SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service",
            "card-service");

    /** The Java package each module owns, keyed by module. */
    private static final Map<String, String> PACKAGES = Map.of(
            "authorization-service", "authorization",
            "ledger-posting-service", "ledger",
            "fraud-detection-service", "fraud",
            "notification-service", "notification",
            "account-service", "account",
            "card-service", "card");

    /** The header a state-changing request has to carry, and the shipped default. */
    private static final String CROSS_SITE_HEADER = "X-CardDemo-Request";

    /** Environment keys the two controls read, each with the value every shipped file carries. */
    private static final Map<String, String> SHIPPED_DEFAULTS = Map.of(
            "API_CROSS_SITE_HEADER", CROSS_SITE_HEADER,
            "API_RATE_WINDOW_SECONDS", "60",
            "API_RATE_REQUESTS_PER_WINDOW", "600",
            "API_RATE_WRITE_REQUESTS_PER_WINDOW", "120",
            "API_RATE_AUTHENTICATION_FAILURES_PER_WINDOW", "20",
            "API_RATE_CONCURRENT_REQUESTS", "64");

    /** The property each environment key reaches, keyed by key. */
    private static final Map<String, String> PROPERTY_OF_KEY = Map.of(
            "API_CROSS_SITE_HEADER", "carddemo.api.cross-site.required-header",
            "API_RATE_WINDOW_SECONDS", "carddemo.api.rate-limit.window-seconds",
            "API_RATE_REQUESTS_PER_WINDOW", "carddemo.api.rate-limit.requests-per-window",
            "API_RATE_WRITE_REQUESTS_PER_WINDOW",
            "carddemo.api.rate-limit.write-requests-per-window",
            "API_RATE_AUTHENTICATION_FAILURES_PER_WINDOW",
            "carddemo.api.rate-limit.authentication-failures-per-window",
            "API_RATE_CONCURRENT_REQUESTS", "carddemo.api.rate-limit.concurrent-requests");

    /** The methods the cross-site control tests, and the ones it lets past. */
    private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

    /** The methods that change nothing, so no cross-site test applies. */
    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS", "TRACE");

    /**
     * How far ahead of the security chain each filter runs, keyed by filter, cheapest refusal
     * first.
     *
     * <p>A number here is subtracted from {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER},
     * so a larger number runs earlier. The ladder is ordered by what a refusal costs: attaching a
     * correlation identity is a map write, counting a request against a ceiling is an increment,
     * measuring a declared body length is a header read, and comparing an origin is two header
     * reads. All four are cheaper than the bcrypt verification the chain performs, which is the
     * reason the ladder exists rather than an aesthetic ordering.
     */
    private static final Map<String, Integer> FILTER_ORDER_OFFSETS = Map.of(
            "CorrelationContextFilter", 4,
            "RequestRateCeilingFilter", 3,
            "RequestBodyCeilingFilter", 2,
            "CrossSiteRequestFilter", 1);

    /** Where the cross-site refusal sits on the ladder. */
    private static final int CROSS_SITE_ORDER_OFFSET =
            FILTER_ORDER_OFFSETS.get("CrossSiteRequestFilter");

    /** Where the rate refusal sits on the ladder. */
    private static final int RATE_ORDER_OFFSET =
            FILTER_ORDER_OFFSETS.get("RequestRateCeilingFilter");

    /** The filters every service installs. */
    private static final List<String> UNIVERSAL_FILTERS = List.of(
            "CorrelationContextFilter", "RequestRateCeilingFilter", "CrossSiteRequestFilter");

    /** The modules that accept a request body, and therefore measure one. */
    private static final List<String> BODY_CEILING_SERVICES = List.of(
            "authorization-service", "account-service", "card-service");

    /**
     * The filters whose refusal is only correct because they defer to an unauthenticated request.
     *
     * <p>Ordering a refusal ahead of the chain moves the answer as well as the cost unless the
     * filter stands aside for a request carrying no credential. Both filters below therefore let
     * such a request through to be answered 401 by the chain, with no password to verify. Neither
     * the correlation filter nor the rate filter needs the rule: the first refuses nothing, and the
     * second has to count an unauthenticated request precisely because a flood of them is the
     * attack it bounds.
     */
    private static final List<String> DEFERRING_FILTERS = List.of(
            "RequestBodyCeilingFilter", "CrossSiteRequestFilter");

    @Nested
    @DisplayName("the cross-site control")
    class CrossSiteControl {

        @Test
        @DisplayName("every service installs the filter and tests the same three conditions")
        void everyServiceInstallsTheFilterAndTestsTheSameThreeConditions() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "CrossSiteRequestFilter");

                assertThat(source)
                        .as("%s cross-site filter", module)
                        .contains("@Component")
                        .contains("extends OncePerRequestFilter")
                        .contains("\"" + CROSS_SITE_HEADER + "\"")
                        .contains("Sec-Fetch-Site")
                        .contains("HttpHeaders.ORIGIN")
                        .contains("HttpStatus.FORBIDDEN");
                assertThat(source)
                        .as("%s must run ahead of the security chain, so a forged request "
                                + "presenting a cached credential is refused before the bcrypt "
                                + "verification it was always going to fail", module)
                        .contains("@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - "
                                + CROSS_SITE_ORDER_OFFSET + ")");
                assertThat(source)
                        .as("%s must defer when the request carries no credential, because an "
                                + "unauthenticated request has to keep reaching the chain and "
                                + "keep being answered 401 rather than 403", module)
                        .contains("request.getHeader(HttpHeaders.AUTHORIZATION) == null");
                for (String method : WRITE_METHODS) {
                    assertThat(source)
                            .as("%s cross-site filter has to reach %s", module, method)
                            .doesNotContain("\"" + method + "\"");
                }
                assertThat(source)
                        .as("%s cross-site filter names the safe methods it lets past", module)
                        .contains(SAFE_METHODS.stream()
                                .map(method -> "\"" + method + "\"")
                                .reduce((first, second) -> first + ", " + second)
                                .orElseThrow());
            }
        }

        @Test
        @DisplayName("the refusal carries no route, identifier or header value")
        void theRefusalCarriesNoRouteIdentifierOrHeaderValue() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "CrossSiteRequestFilter");
                String body = literalOf(source, "REFUSAL_BODY");

                assertThat(body)
                        .as("%s cross-site refusal", module)
                        .contains("\\\"status\\\":403")
                        .doesNotContain("/accounts")
                        .doesNotContain("/cards")
                        .doesNotContain("/authorizations")
                        .doesNotContain(CROSS_SITE_HEADER);
            }
        }

        @Test
        @DisplayName("every ordinary chain names the filter where it turns the token off")
        void everyOrdinaryChainNamesTheFilterWhereItTurnsTheTokenOff() {
            for (String module : SERVICES) {
                String config = read(securityConfigOf(module));
                int disable = config.indexOf(".csrf(csrf -> csrf.disable())");

                assertThat(disable)
                        .as("%s declares no chain turning the forgery token off", module)
                        .isGreaterThan(0);
                assertThat(config)
                        .as("%s turns the forgery token off without naming the control that "
                                + "replaces it", module)
                        .contains("config/CrossSiteRequestFilter is the compensating control");
            }
        }
    }

    @Nested
    @DisplayName("the request-rate control")
    class RequestRateControl {

        @Test
        @DisplayName("every service installs the filter ahead of the security chain")
        void everyServiceInstallsTheFilterAheadOfTheSecurityChain() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "RequestRateCeilingFilter");

                assertThat(source)
                        .as("%s rate filter", module)
                        .contains("@Component")
                        .contains("extends OncePerRequestFilter")
                        .contains("@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - "
                                + RATE_ORDER_OFFSET + ")")
                        .contains("HttpStatus.TOO_MANY_REQUESTS")
                        .contains("HttpHeaders.RETRY_AFTER");
            }
        }

        @Test
        @DisplayName("every service counts the same five ceilings under its own meter name")
        void everyServiceCountsTheSameFiveCeilings() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "RequestRateCeilingFilter");

                assertThat(literalOf(source, "THROTTLED_METER"))
                        .as("%s rate meter", module)
                        .isEqualTo("carddemo." + PACKAGES.get(module) + ".requests.throttled");
                assertThat(source)
                        .as("%s rate ceilings", module)
                        .contains("AUTHENTICATION_STAGE = \"authentication\"")
                        .contains("SOURCE_STAGE = \"source\"")
                        .contains("IDENTITY_STAGE = \"identity\"")
                        .contains("WRITE_STAGE = \"write\"")
                        .contains("CONCURRENCY_STAGE = \"concurrency\"");
            }
        }

        @Test
        @DisplayName("no service reads a forwarding header a caller could write")
        void noServiceReadsAForwardingHeaderACallerCouldWrite() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "RequestRateCeilingFilter");

                assertThat(source)
                        .as("%s must key its ceilings on the address the container resolved, "
                                + "because a header the caller writes cannot bound that caller",
                                module)
                        .doesNotContain("X-Forwarded-For")
                        .doesNotContain("X-Real-IP");
                assertThat(source)
                        .as("%s keys its source ceiling on the resolved remote address", module)
                        .contains("request.getRemoteAddr()");
            }
        }

        @Test
        @DisplayName("the counter map is bounded, so a varied source cannot grow it without limit")
        void theCounterMapIsBounded() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "RequestRateCeilingFilter");

                assertThat(source)
                        .as("%s rate filter bound", module)
                        .contains("MAX_TRACKED_KEYS")
                        .contains("OVERFLOW_KEY")
                        .contains("removeIf");
            }
        }

        /**
         * The sweep that keeps the map bounded must not run for every key that finds it full.
         *
         * <p>A performance review found the walk running once per arriving key once the map was at
         * capacity, which turned a run of unseen sources into one walk of ten thousand entries each.
         * Two guards answer it, and both are asserted here rather than in six service test classes:
         * the interval that stops a second walk following the first, and the compare-and-set that
         * stops two threads walking at once. {@code RequestRateCeilingFilterTest} of the
         * authorization service proves the behaviour the two guards produce.
         */
        @Test
        @DisplayName("the expiry sweep is throttled and single-flight in every service")
        void theExpirySweepIsThrottledAndSingleFlight() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "RequestRateCeilingFilter");

                assertThat(source)
                        .as("%s rate filter sweep throttle", module)
                        .contains("expirySweepIntervalMillis")
                        .contains("lastExpirySweepMillis")
                        .contains("compareAndSet");
                assertThat(source)
                        .as("%s sweeps through the throttled method alone", module)
                        .contains("removeExpiredWindows(now)");
                assertThat(occurrences(source, "entrySet().removeIf"))
                        .as("%s holds one walk of the counter map", module)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the refusal carries no address, identity or route")
        void theRefusalCarriesNoAddressIdentityOrRoute() {
            for (String module : SERVICES) {
                String body = literalOf(filterSourceOf(module, "RequestRateCeilingFilter"),
                        "REFUSAL_BODY");

                assertThat(body)
                        .as("%s rate refusal", module)
                        .contains("\\\"status\\\":429")
                        .doesNotContain("/accounts")
                        .doesNotContain("/authorizations");
            }
        }
    }

    @Nested
    @DisplayName("the order the filters run in")
    class FilterOrderLadder {

        @Test
        @DisplayName("every filter declares its rung, and every rung is ahead of the chain")
        void everyFilterDeclaresItsRungAheadOfTheChain() {
            for (String module : SERVICES) {
                for (String filter : filtersOf(module)) {
                    String source = filterSourceOf(module, filter);
                    int offset = FILTER_ORDER_OFFSETS.get(filter);

                    assertThat(source)
                            .as("%s %s must declare the rung the ladder gives it, because a filter "
                                    + "without an order runs after the security chain and its "
                                    + "refusal then costs a bcrypt verification", module, filter)
                            .contains("@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - "
                                    + offset + ")")
                            .contains("import org.springframework.boot.security.autoconfigure.web"
                                    + ".servlet.SecurityFilterProperties;");
                    assertThat(offset)
                            .as("%s %s has to run ahead of the chain", module, filter)
                            .isPositive();
                }
            }
        }

        @Test
        @DisplayName("no two filters of one service claim the same rung")
        void noTwoFiltersOfOneServiceClaimTheSameRung() {
            for (String module : SERVICES) {
                List<Integer> rungs = new ArrayList<>();
                for (String filter : filtersOf(module)) {
                    rungs.add(declaredOrderOffsetOf(module, filter));
                }

                assertThat(rungs)
                        .as("%s installs %s and each needs a rung of its own, because two filters "
                                + "sharing an order run in an unspecified sequence",
                                module, filtersOf(module))
                        .doesNotHaveDuplicates()
                        .allSatisfy(rung -> assertThat(rung).isPositive());
            }
        }

        @Test
        @DisplayName("the two refusals ordered ahead of the chain defer to an unauthenticated call")
        void theTwoRefusalsAheadOfTheChainDeferToAnUnauthenticatedCall() {
            for (String module : SERVICES) {
                for (String filter : filtersOf(module)) {
                    String source = filterSourceOf(module, filter);

                    if (!DEFERRING_FILTERS.contains(filter)) {
                        continue;
                    }
                    assertThat(source)
                            .as("%s %s answers a status of its own, so ordering it ahead of the "
                                    + "chain would answer that status where 401 belongs unless it "
                                    + "stands aside for a request carrying no credential",
                                    module, filter)
                            .contains("request.getHeader(HttpHeaders.AUTHORIZATION) == null")
                            .contains("import org.springframework.http.HttpHeaders;");
                }
            }
        }

        @Test
        @DisplayName("the rate filter counts an unauthenticated call rather than deferring")
        void theRateFilterCountsAnUnauthenticatedCallRatherThanDeferring() {
            for (String module : SERVICES) {
                String source = filterSourceOf(module, "RequestRateCeilingFilter");

                assertThat(source)
                        .as("%s must count a request presenting no credential, because a flood of "
                                + "unauthenticated requests is the cost this ceiling bounds",
                                module)
                        .doesNotContain("request.getHeader(HttpHeaders.AUTHORIZATION) == null")
                        .contains("AUTHENTICATION_STAGE");
            }
        }

        @Test
        @DisplayName("only the three services accepting a body measure one")
        void onlyTheThreeServicesAcceptingABodyMeasureOne() {
            for (String module : SERVICES) {
                Path filter = filterPathOf(module, "RequestBodyCeilingFilter");

                assertThat(Files.exists(filter))
                        .as("%s body ceiling filter present", module)
                        .isEqualTo(BODY_CEILING_SERVICES.contains(module));
            }
        }
    }

    /**
     * Where each service stands on a header a caller can write.
     *
     * <p>Two of the controls above read a value a proxy would rewrite.
     * {@code RequestRateCeilingFilter} keys its ceilings on the peer address, and
     * {@code CrossSiteRequestFilter} compares {@code Origin} against the host the request arrived
     * on. Honouring a forwarded header on a container a caller can reach directly hands that caller
     * both: every request looks like one source, and a forged origin compares equal.
     *
     * <p>The shipped answer is to trust nothing, and the profile below is how a deployment behind a
     * terminating proxy changes that without editing a shipped file.
     */
    @Nested
    @DisplayName("the forwarded-header posture")
    class ForwardedHeaderPosture {

        /** The profile file each service ships beside its own configuration. */
        private static final String PROFILE_FILE = "application-trusted-proxy.yml";

        /** The variable the profile reads, and deliberately gives no default. */
        private static final String PROXY_VARIABLE = "TRUSTED_PROXY_ADDRESSES";

        /** The heading of the decision every profile file points a reader at. */
        private static final String DECISION_HEADING =
                "Forwarded headers are trusted in one profile, and only behind a named proxy";

        @Test
        @DisplayName("every shipped configuration states that it trusts no forwarded header")
        void everyShippedConfigurationStatesThatItTrustsNoForwardedHeader() {
            for (String module : SERVICES) {
                assertThat(applicationOf(module))
                        .as("%s must state the posture rather than inherit it, because the value a "
                                + "review cannot find is the value nobody chose", module)
                        .contains("forward-headers-strategy: none")
                        .contains(PROFILE_FILE);
            }
        }

        @Test
        @DisplayName("every service ships the profile, and every copy says the same thing")
        void everyServiceShipsTheProfileAndEveryCopySaysTheSameThing() {
            for (String module : SERVICES) {
                Path profile = platformDirectory().resolve("services").resolve(module)
                        .resolve("src/main/resources").resolve(PROFILE_FILE);

                assertThat(Files.exists(profile))
                        .as("%s must ship %s", module, PROFILE_FILE)
                        .isTrue();
                assertThat(read(profile))
                        .as("%s trusted-proxy profile", module)
                        .contains("forward-headers-strategy: native")
                        .contains("internal-proxies: \"${" + PROXY_VARIABLE + "}\"")
                        .contains("remote-ip-header: x-forwarded-for")
                        .contains("protocol-header: x-forwarded-proto")
                        .contains("host-header: x-forwarded-host")
                        .contains("card-platform/docs/decision-log.md")
                        .contains(DECISION_HEADING);
            }
        }

        /**
         * Asserts the profile trusts Tomcat's own valve rather than the framework's blanket strategy.
         *
         * <p>The framework strategy honours the headers on every request whoever sent them, so a
         * caller reaching the container directly can claim any address. The native strategy rewrites
         * only for a request whose immediate peer matches the expression, which is the property that
         * makes the trust conditional.
         */
        @Test
        @DisplayName("the profile uses the conditional strategy, not the blanket one")
        void theProfileUsesTheConditionalStrategyNotTheBlanketOne() {
            for (String module : SERVICES) {
                Path profile = platformDirectory().resolve("services").resolve(module)
                        .resolve("src/main/resources").resolve(PROFILE_FILE);

                assertThat(read(profile))
                        .as("%s must not honour a forwarded header from an unnamed peer", module)
                        .doesNotContain("forward-headers-strategy: framework");
            }
        }

        /**
         * Asserts the proxy expression has no default anywhere, in the profile or around it.
         *
         * <p>A default matching the private address ranges trusts every workload sharing the
         * network, which on a cluster is every pod. A default matching nothing activates a profile
         * that silently does nothing. The absence is the decision, so it is asserted in all four
         * places a default could appear.
         */
        @Test
        @DisplayName("the proxy expression carries no default in any shipped file")
        void theProxyExpressionCarriesNoDefaultInAnyShippedFile() {
            for (String module : SERVICES) {
                Path profile = platformDirectory().resolve("services").resolve(module)
                        .resolve("src/main/resources").resolve(PROFILE_FILE);

                assertThat(read(profile))
                        .as("%s must leave the expression undefaulted, so an activated profile with "
                                + "no value stops start-up naming the variable", module)
                        .doesNotContain("${" + PROXY_VARIABLE + ":");
            }
            assertThat(read(platformDirectory().resolve(".env.example")))
                    .as("a documented default is the thing being refused")
                    .doesNotContain(PROXY_VARIABLE + "=");
            assertThat(read(platformDirectory().resolve("docker-compose.yml")))
                    .as("the composition publishes a loopback port and has no proxy")
                    .doesNotContain(PROXY_VARIABLE + ":");
            assertThat(read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml")))
                    .as("the ConfigMap names the profile and the variable in a comment, and sets "
                            + "neither, because a value here would trust a range nobody chose")
                    .doesNotContain(PROXY_VARIABLE + ": \"");
        }

        @Test
        @DisplayName("the cluster configuration names the profile a deployment activates")
        void theClusterConfigurationNamesTheProfileADeploymentActivates() {
            assertThat(read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml")))
                    .as("an operator adding an Ingress reads the ConfigMap, so the route out of the "
                            + "shipped posture belongs there")
                    .contains("trusted-proxy")
                    .contains(PROXY_VARIABLE)
                    .contains(PROFILE_FILE);
        }

        @Test
        @DisplayName("the decision every profile points at resolves in the log")
        void theDecisionEveryProfilePointsAtResolves() {
            assertThat(read(platformDirectory().resolve("docs/decision-log.md")))
                    .as("a pointer to a heading the log does not carry is not a pointer")
                    .contains("### " + DECISION_HEADING);
        }
    }

    @Nested
    @DisplayName("the shipped configuration of the request-surface controls")
    class ShippedConfiguration {

        @Test
        @DisplayName("every service reads every setting with the documented default")
        void everyServiceReadsEverySettingWithTheDocumentedDefault() {
            for (String module : SERVICES) {
                String application = read(platformDirectory().resolve("services")
                        .resolve(module)
                        .resolve("src/main/resources/application.yml"));

                for (Map.Entry<String, String> shipped : SHIPPED_DEFAULTS.entrySet()) {
                    assertThat(application)
                            .as("%s must read %s", module, shipped.getKey())
                            .contains("${" + shipped.getKey() + ":" + shipped.getValue() + "}");
                }
            }
        }

        @Test
        @DisplayName("the dotenv example documents every setting with the same value")
        void theDotenvExampleDocumentsEverySetting() {
            String example = read(platformDirectory().resolve(".env.example"));

            for (Map.Entry<String, String> shipped : SHIPPED_DEFAULTS.entrySet()) {
                assertThat(example.lines().toList())
                        .as("%s must be documented in .env.example", shipped.getKey())
                        .contains(shipped.getKey() + "=" + shipped.getValue());
            }
        }

        @Test
        @DisplayName("the composition passes each setting once, through the shared block")
        void theCompositionPassesEachSettingOnceThroughTheSharedBlock() {
            String compose = read(platformDirectory().resolve("docker-compose.yml"));

            for (Map.Entry<String, String> shipped : SHIPPED_DEFAULTS.entrySet()) {
                String entry = shipped.getKey() + ": ${" + shipped.getKey() + ":-"
                        + shipped.getValue() + "}";
                assertThat(occurrences(compose, entry))
                        .as("%s belongs in the shared environment block exactly once, because the "
                                + "policy is the same in all six services", shipped.getKey())
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the Kubernetes configuration carries the same six quoted strings")
        void theKubernetesConfigurationCarriesTheSameSixQuotedStrings() {
            String configMap = read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));

            for (Map.Entry<String, String> shipped : SHIPPED_DEFAULTS.entrySet()) {
                Pattern quoted = Pattern.compile("(?m)^  " + Pattern.quote(shipped.getKey())
                        + ": \"" + Pattern.quote(shipped.getValue()) + "\"$");
                assertThat(quoted.matcher(configMap).find())
                        .as("%s must be a quoted ConfigMap string carrying %s",
                                shipped.getKey(), shipped.getValue())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("every setting reaches the property the filters read")
        void everySettingReachesThePropertyTheFiltersRead() {
            for (String module : SERVICES) {
                String cross = filterSourceOf(module, "CrossSiteRequestFilter");
                String rate = filterSourceOf(module, "RequestRateCeilingFilter");
                String filters = cross + rate;

                for (Map.Entry<String, String> key : PROPERTY_OF_KEY.entrySet()) {
                    assertThat(filters)
                            .as("%s must read %s, the property %s reaches",
                                    module, key.getValue(), key.getKey())
                            .contains("${" + key.getValue() + ":");
                }
            }
        }
    }

    /** Reads the source of one filter of one module. */
    private static String filterSourceOf(String module, String filter) {
        return read(filterPathOf(module, filter));
    }

    /** Path of one filter of one module, whether or not that module installs it. */
    private static Path filterPathOf(String module, String filter) {
        return platformDirectory().resolve("services")
                .resolve(module)
                .resolve("src/main/java/com/carddemo")
                .resolve(PACKAGES.get(module))
                .resolve("config")
                .resolve(filter + ".java");
    }

    /** The filters one module installs, cheapest refusal first. */
    private static List<String> filtersOf(String module) {
        List<String> installed = new ArrayList<>(UNIVERSAL_FILTERS);
        if (BODY_CEILING_SERVICES.contains(module)) {
            installed.add("RequestBodyCeilingFilter");
        }
        return installed;
    }

    /**
     * Reads the rung one filter declares, as the number subtracted from the chain's own order.
     *
     * @param module the service module
     * @param filter simple name of the filter class
     * @return the offset the {@code @Order} annotation subtracts
     */
    private static int declaredOrderOffsetOf(String module, String filter) {
        Matcher declared = Pattern
                .compile("@Order\\(SecurityFilterProperties\\.DEFAULT_FILTER_ORDER - (\\d+)\\)")
                .matcher(filterSourceOf(module, filter));

        assertThat(declared.find()).as("%s %s declares an order", module, filter).isTrue();
        return Integer.parseInt(declared.group(1));
    }

    /** Reads the shipped configuration of one module. */
    private static String applicationOf(String module) {
        return read(platformDirectory().resolve("services")
                .resolve(module)
                .resolve("src/main/resources/application.yml"));
    }

    /** Path of the security configuration of one module. */
    private static Path securityConfigOf(String module) {
        return platformDirectory().resolve("services")
                .resolve(module)
                .resolve("src/main/java/com/carddemo")
                .resolve(PACKAGES.get(module))
                .resolve("config/SecurityConfig.java");
    }

    /**
     * Reads one string constant of one source, joining a concatenated declaration.
     *
     * @param source     the Java source to read
     * @param constant   name of the constant
     * @return the joined literal text, with the quoting of the source left in place
     */
    private static String literalOf(String source, String constant) {
        int declaration = source.indexOf(constant + " = ");
        assertThat(declaration).as("constant %s", constant).isGreaterThan(0);
        int end = source.indexOf(';', declaration);
        assertThat(end).as("constant %s ends", constant).isGreaterThan(declaration);

        String declared = source.substring(declaration, end);
        Matcher pieces = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(declared);
        List<String> literal = new ArrayList<>();
        while (pieces.find()) {
            literal.add(pieces.group(1));
        }
        assertThat(literal).as("constant %s holds a literal", constant).isNotEmpty();
        return String.join("", literal);
    }

    /** Counts non-overlapping occurrences of one token. */
    private static int occurrences(String text, String token) {
        int found = 0;
        int offset = text.indexOf(token);
        while (offset >= 0) {
            found++;
            offset = text.indexOf(token, offset + token.length());
        }
        return found;
    }

    /** Reads one shipped file. */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unreadable: " + file, unreadable);
        }
    }

    /** The {@code card-platform} directory, resolved the way every sibling contract resolves it. */
    private static Path platformDirectory() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent()
                .resolve("card-platform");
    }
}
