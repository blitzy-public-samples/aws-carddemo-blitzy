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
 * Holds every service to the same two request-surface controls, in code and in shipped
 * configuration.
 *
 * <p>Both controls answer one finding each. The first is that HTTP Basic is replayable by a browser:
 * it attaches a cached credential to a request a foreign page caused, and the bodyless
 * {@code POST /accounts/{accountId}/cycle-close} is submittable by an HTML form, so a page an
 * administrator visited could zero both billing-cycle accumulators that
 * {@code app/cbl/CBTRN02C.cbl:L403-L413} authorizes against. The second is that nothing bounded a
 * request rate anywhere, so a caller could spend a service's processor on bcrypt verifications for
 * as long as it liked.
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
                        .as("%s must not carry an order, which would move the refusal ahead of "
                                + "authentication and answer 403 where 401 belongs", module)
                        .doesNotContain("@Order");
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
                        .contains("@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)")
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
    @DisplayName("the shipped configuration of both controls")
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
        return read(platformDirectory().resolve("services")
                .resolve(module)
                .resolve("src/main/java/com/carddemo")
                .resolve(PACKAGES.get(module))
                .resolve("config")
                .resolve(filter + ".java"));
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
