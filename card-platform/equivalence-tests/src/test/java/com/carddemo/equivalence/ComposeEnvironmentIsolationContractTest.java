package com.carddemo.equivalence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the Compose application-service environment at the private-database and broker boundary.
 */
@DisplayName("Compose application-service environment isolation")
class ComposeEnvironmentIsolationContractTest {

    private static final List<String> SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service",
            "card-service");

    private static final List<String> DATABASE_PASSWORD_SOURCES = List.of(
            "AUTHORIZATION_DB_PASSWORD",
            "LEDGER_DB_PASSWORD",
            "FRAUD_DB_PASSWORD",
            "NOTIFICATION_DB_PASSWORD",
            "ACCOUNT_DB_PASSWORD",
            "CARD_DB_PASSWORD");

    private static final List<String> KAFKA_PASSWORD_SOURCES = List.of(
            "AUTHORIZATION_KAFKA_PASSWORD",
            "LEDGER_KAFKA_PASSWORD",
            "FRAUD_KAFKA_PASSWORD",
            "NOTIFICATION_KAFKA_PASSWORD",
            "ACCOUNT_KAFKA_PASSWORD",
            "CARD_KAFKA_PASSWORD");

    private static final Map<String, String> OWN_DATABASE_PASSWORD = Map.of(
            "authorization-service", "AUTHORIZATION_DB_PASSWORD",
            "ledger-posting-service", "LEDGER_DB_PASSWORD",
            "fraud-detection-service", "FRAUD_DB_PASSWORD",
            "notification-service", "NOTIFICATION_DB_PASSWORD",
            "account-service", "ACCOUNT_DB_PASSWORD",
            "card-service", "CARD_DB_PASSWORD");

    private static final Map<String, String> OWN_KAFKA_PASSWORD = Map.of(
            "authorization-service", "AUTHORIZATION_KAFKA_PASSWORD",
            "ledger-posting-service", "LEDGER_KAFKA_PASSWORD",
            "fraud-detection-service", "FRAUD_KAFKA_PASSWORD",
            "notification-service", "NOTIFICATION_KAFKA_PASSWORD",
            "account-service", "ACCOUNT_KAFKA_PASSWORD",
            "card-service", "CARD_KAFKA_PASSWORD");

    private static final Map<String, Set<String>> REQUIRED_SERVICE_KEYS = requiredServiceKeys();

    /** The six service Deployments, in the order their file names number them. */
    private static final List<String> DEPLOYMENT_MANIFESTS = List.of(
            "40-authorization-service.yaml",
            "41-ledger-posting-service.yaml",
            "42-fraud-detection-service.yaml",
            "43-notification-service.yaml",
            "44-account-service.yaml",
            "45-card-service.yaml");

    private static final Pattern ENVIRONMENT_KEY =
            Pattern.compile("(?m)^ {6}([A-Z][A-Z0-9_]*):");

    /** A published port mapping, with the part before the container port captured. */
    private static final Pattern PUBLISHED_PORT =
            Pattern.compile("(?m)^ {6}- \"([^\"]+)\"\\s*$");

    /** The address every mapping has to name. */
    private static final String LOOPBACK = "127.0.0.1";

    /**
     * Fewest published mappings the scan must find before its verdict means anything.
     *
     * <p>Fourteen ship: the database, the broker, and a service port and a management port for each
     * of the six services.
     */
    private static final int PUBLISHED_PORT_FLOOR = 14;

    private static final Pattern QUOTED_BCRYPT = Pattern.compile(
            "(?m)^(ADMIN|ACQUIRER|USER|MONITORING)_PASSWORD_HASH="
                    + "'(\\{bcrypt}\\$2a\\$10\\$REPLACE-[^']+)'$");

    @Test
    @DisplayName("each application receives only its own database and broker secret sources")
    void eachApplicationReceivesOnlyItsOwnDatabaseAndBrokerSecretSources() {
        String compose = read(repositoryRoot().resolve("card-platform/docker-compose.yml"));

        for (String service : SERVICES) {
            String block = serviceBlock(compose, service);
            assertThat(block)
                    .as("%s must not import the aggregate dotenv file", service)
                    .doesNotContain("\n    env_file:");
            assertThat(block)
                    .as("%s aggregate credentials", service)
                    .doesNotContain("${POSTGRES_PASSWORD", "${KAFKA_ADMIN_PASSWORD");

            assertOnlyOwnSecretSource(service, block, DATABASE_PASSWORD_SOURCES,
                    OWN_DATABASE_PASSWORD.get(service));
            assertOnlyOwnSecretSource(service, block, KAFKA_PASSWORD_SOURCES,
                    OWN_KAFKA_PASSWORD.get(service));
        }
    }

    /**
     * The acquirer verifier reaches the one service that verifies a password against it.
     *
     * <p>ACQUIRER is the machine identity a point-of-sale network presents. It is the one role
     * besides ADMIN that reaches {@code POST /authorizations}, and that route names its card in
     * the request body, so no path variable carries an identifier an ownership scope could be
     * compared against: the identity reaches every card the platform holds.
     * {@code services/authorization-service/src/main/resources/application.yml} is the only file
     * on the platform that reads either the username or the hash.
     *
     * <p>Both deployment paths are asserted, because the value travels differently on each. In
     * Compose a shared YAML anchor injects into every container, so the pair belongs in the
     * authorization service's own block. In Kubernetes a Secret is pulled whole through
     * {@code envFrom.secretRef}, so the hash belongs in a Secret of its own. A bcrypt digest
     * mounted into a service with no code that checks it is a value an attacker who reaches any
     * one of six containers can take away and grind offline at leisure.
     */
    @Test
    @DisplayName("the acquirer verifier reaches the authorization service and no other")
    void theAcquirerVerifierReachesTheAuthorizationServiceAndNoOther() {
        Path root = repositoryRoot();
        String compose = read(root.resolve("card-platform/docker-compose.yml"));

        for (String service : SERVICES) {
            String block = serviceBlock(compose, service);
            boolean carriesTheVerifier = block.contains("ACQUIRER_PASSWORD_HASH");
            boolean carriesTheUsername = block.contains("ACQUIRER_USERNAME");
            if ("authorization-service".equals(service)) {
                assertThat(carriesTheVerifier)
                        .as("the authorization service verifies against the acquirer hash, so its"
                                + " own block has to bind it")
                        .isTrue();
                assertThat(carriesTheUsername)
                        .as("and the username it is verified against")
                        .isTrue();
            } else {
                assertThat(carriesTheVerifier)
                        .as("%s has no code that reads the acquirer hash", service)
                        .isFalse();
                assertThat(carriesTheUsername)
                        .as("%s has no code that reads the acquirer username", service)
                        .isFalse();
            }
        }

        String sharedAnchor = compose.substring(0, compose.indexOf("\nservices:"));
        assertThat(sharedAnchor)
                .as("the shared anchor injects into all six containers, so the acquirer pair"
                        + " cannot sit in it")
                .doesNotContain("ACQUIRER_PASSWORD_HASH", "ACQUIRER_USERNAME");

        String secrets = read(root.resolve("card-platform/deploy/k8s/31-secret.example.yaml"));
        assertThat(secrets)
                .as("the acquirer hash is a Secret of its own in the cluster path")
                .contains("name: carddemo-authorization-identity-secret");
        int sharedIdentity = secrets.indexOf("name: carddemo-identity-secret");
        int authorizationIdentity = secrets.indexOf("name: carddemo-authorization-identity-secret");
        String sharedIdentityDocument =
                secrets.substring(sharedIdentity, authorizationIdentity);
        assertThat(sharedIdentityDocument)
                .as("the Secret all six Deployments pull carries the three shared hashes only")
                .contains("ADMIN_PASSWORD_HASH", "USER_PASSWORD_HASH", "MONITORING_PASSWORD_HASH")
                .doesNotContain("ACQUIRER_PASSWORD_HASH:");

        for (int manifest = 40; manifest <= 45; manifest++) {
            String name = DEPLOYMENT_MANIFESTS.get(manifest - 40);
            String deployment = read(root.resolve("card-platform/deploy/k8s/" + name));
            boolean pullsIt = deployment.contains("name: carddemo-authorization-identity-secret");
            assertThat(pullsIt)
                    .as("%s pulls the acquirer Secret", name)
                    .isEqualTo(manifest == 40);
        }
    }

    @Test
    @DisplayName("every active relay, consumer and retention policy remains explicitly configurable")
    void everyActiveRelayConsumerAndRetentionPolicyRemainsExplicitlyConfigurable() {
        String compose = read(repositoryRoot().resolve("card-platform/docker-compose.yml"));

        for (String service : SERVICES) {
            String block = serviceBlock(compose, service);
            Set<String> keys = environmentKeys(block);
            assertThat(keys)
                    .as("%s explicit environment keys", service)
                    .containsAll(REQUIRED_SERVICE_KEYS.get(service));
            assertThat(keys)
                    .as("%s derives the relay identity from its container hostname", service)
                    .doesNotContain("OUTBOX_RELAY_INSTANCE_ID");
        }
    }

    @Test
    @DisplayName("bcrypt examples are single quoted so dollar signs remain literal")
    void bcryptExamplesAreSingleQuotedSoDollarSignsRemainLiteral() {
        String example = read(repositoryRoot().resolve("card-platform/.env.example"));
        Matcher hashes = QUOTED_BCRYPT.matcher(example);
        List<String> identities = new ArrayList<>();
        while (hashes.find()) {
            identities.add(hashes.group(1));
            assertThat(hashes.group(2)).contains("REPLACE-THIS-PLACEHOLDER");
        }

        assertThat(identities).containsExactly("ADMIN", "ACQUIRER", "USER", "MONITORING");
    }

    @Test
    @DisplayName("runtime guidance describes the listeners and relays that actually run")
    void runtimeGuidanceDescribesTheListenersAndRelaysThatActuallyRun() {
        Path root = repositoryRoot();
        String guidance = String.join("\n",
                read(root.resolve("card-platform/.env.example")),
                read(root.resolve("card-platform/docker-compose.yml")),
                read(root.resolve("card-platform/deploy/k8s/30-configmap.yaml")));

        assertThat(guidance).doesNotContain(
                "No module registers a Kafka listener yet",
                "No module runs a relay",
                "planned listener",
                "planned relay",
                "planned and unauthored",
                "is to register a listener");
    }

    /**
     * Holds the transport posture of the local stack: one operator, one machine, synthetic fixtures.
     *
     * <p><b>What this stands over.</b> Three transports here carry a credential without encrypting
     * it, or encrypt without authenticating the peer: HTTP Basic over HTTP on the six service
     * ports, {@code SASL_PLAINTEXT} on the broker, and {@code sslmode=require} against a server
     * that signs its own certificate. Two routes answer that, and this platform takes the second:
     * state the posture and enforce it.
     *
     * <p>Enforcement is what this class adds. A posture stated in a comment is a promise, and the
     * binding is the thing that makes it true, so every published port is read here and a mapping
     * that reaches beyond this machine fails the build. The three transports are read too, because
     * the posture only holds while they are the set it describes.
     */
    @Test
    @DisplayName("every published port binds the loopback address and nothing else")
    void everyPublishedPortBindsTheLoopbackAddress() {
        String compose = read(repositoryRoot().resolve("card-platform/docker-compose.yml"));

        List<String> mappings = new ArrayList<>();
        Matcher published = PUBLISHED_PORT.matcher(compose);
        while (published.find()) {
            mappings.add(published.group(1));
        }

        assertThat(mappings)
                .as("the eight containers publish fourteen mappings: one each for the database and"
                        + " the broker, and a service port and a management port for each of the six"
                        + " services. A count below this floor means the scan stopped reading and"
                        + " its verdict would mean nothing")
                .hasSizeGreaterThanOrEqualTo(PUBLISHED_PORT_FLOOR);

        assertThat(mappings)
                .as("every mapping names the loopback address. A mapping written as \"8081:8080\""
                        + " or \"0.0.0.0:8081:8080\" listens on every interface of the host, which"
                        + " puts an HTTP Basic credential and a SASL_PLAINTEXT password on whatever"
                        + " network that host is attached to. The posture this stack states depends"
                        + " on the binding rather than on the comment beside it")
                .allSatisfy(mapping -> {
                    assertThat(mapping).startsWith(LOOPBACK + ":");
                    assertThat(mapping.chars().filter(character -> character == ':').count())
                            .as("a host-address form names three parts: the address, the host port"
                                    + " and the container port")
                            .isGreaterThanOrEqualTo(2);
                });

        assertThat(compose)
                .as("no mapping names the wildcard address, in any form")
                .doesNotContain("0.0.0.0:", "\"::\"", "[::]:");
    }

    /**
     * Asserts the stack states the posture where an operator meets it, and states its boundary.
     *
     * <p>Three places are read, because an operator arrives at one of the three. The composition is
     * where the transports are configured, the environment template is where the credentials are
     * generated, and the platform guide is where the stack is described. Each has to name the
     * posture and the case it does not cover, and the composition additionally has to name the
     * encrypted path that answers that case.
     */
    @Test
    @DisplayName("the composition, the template and the guide each state the posture and its bound")
    void theCompositionTheTemplateAndTheGuideEachStateThePosture() {
        Path root = repositoryRoot();
        Map<String, String> stated = Map.of(
                "docker-compose.yml", read(root.resolve("card-platform/docker-compose.yml")),
                ".env.example", read(root.resolve("card-platform/.env.example")),
                "README.md", read(root.resolve("card-platform/README.md")));

        stated.forEach((name, text) -> {
            assertThat(text)
                    .as("%s states the posture in the words the others use, so an operator meets"
                            + " one statement rather than three", name)
                    .containsIgnoringCase("one operator, one machine, synthetic fixtures");
            assertThat(text)
                    .as("%s names the loopback binding that enforces it", name)
                    .contains(LOOPBACK);
        });

        String compose = stated.get("docker-compose.yml");
        assertThat(compose)
                .as("the composition names each of the three transports the posture covers, so a"
                        + " fourth added later has no statement standing over it")
                .contains("SASL_PLAINTEXT", "sslmode=require", "SERVER_SSL_ENABLED");
        assertThat(compose)
                .as("and names the deployed path that answers the case this one does not, whose three"
                        + " transports are encrypted where these three are not")
                .contains("deploy/k8s/30-configmap.yaml", "SASL_SSL", "sslmode=verify-full");
        assertThat(compose)
                .as("the profile that would encrypt this path is designed and not delivered, and"
                        + " the composition says where the procedure is rather than implying one"
                        + " exists")
                .contains("card-platform/docs/suggested-next-tasks.md");

        assertThat(read(root.resolve("card-platform/README.md")))
                .as("the guide states what the posture does not cover, because a reader who assumes"
                        + " it covers a shared host has been misled by a document that only said"
                        + " what it does cover")
                .contains("untrusted account");
    }

    /**
     * Asserts the encrypted values stay out of the local composition and in the manifests.
     *
     * <p>The two paths are alternatives rather than layers, and the four values below are what
     * differ. Reading them from both sides keeps the comparison in the platform guide honest, and
     * catches the half-migration where a composition claims an encrypted transport it does not
     * configure.
     */
    @Test
    @DisplayName("the encrypted transport values are configured in the manifests, not the composition")
    void theEncryptedTransportValuesAreConfiguredInTheManifests() {
        Path root = repositoryRoot();
        String compose = read(root.resolve("card-platform/docker-compose.yml"));
        String configuration = read(root.resolve("card-platform/deploy/k8s/30-configmap.yaml"));

        assertThat(compose)
                .as("the composition configures the unencrypted forms, as the posture states")
                .contains("KAFKA_SECURITY_PROTOCOL: ${KAFKA_SECURITY_PROTOCOL:-SASL_PLAINTEXT}",
                        "SERVER_SSL_ENABLED: ${SERVER_SSL_ENABLED:-false}",
                        "MANAGEMENT_SSL_ENABLED: ${MANAGEMENT_SSL_ENABLED:-false}");
        assertThat(compose)
                .as("and configures no encrypted form, because a value set in one place and"
                        + " defaulted in another is the shape a half-migration takes")
                .doesNotContain("KAFKA_SECURITY_PROTOCOL: SASL_SSL", "sslmode=verify-full&");

        assertThat(configuration)
                .as("the manifests configure the encrypted forms of all four")
                .contains("KAFKA_SECURITY_PROTOCOL: \"SASL_SSL\"",
                        "SERVER_SSL_ENABLED: \"true\"",
                        "MANAGEMENT_SSL_ENABLED: \"true\"",
                        "sslmode=verify-full");
    }

    private static void assertOnlyOwnSecretSource(
            String service, String block, List<String> allSources, String ownSource) {
        assertThat(block).as("%s own secret source", service).contains("${" + ownSource + ":?");
        for (String source : allSources) {
            if (!source.equals(ownSource)) {
                assertThat(block)
                        .as("%s must not receive %s", service, source)
                        .doesNotContain("${" + source);
            }
        }
    }

    private static Set<String> environmentKeys(String serviceBlock) {
        Matcher keys = ENVIRONMENT_KEY.matcher(serviceBlock);
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        while (keys.find()) {
            values.add(keys.group(1));
        }
        return Set.copyOf(values);
    }

    private static String serviceBlock(String compose, String service) {
        String marker = "\n  " + service + ":\n";
        int start = compose.indexOf(marker);
        assertThat(start).as("%s service block", service).isGreaterThanOrEqualTo(0);
        int bodyStart = start + marker.length();
        int end = compose.length();
        Matcher nextService = Pattern.compile(
                        "(?m)^(?:  [a-z][a-z0-9-]*|[a-z][a-z0-9-]*):\\s*$")
                .matcher(compose);
        if (nextService.find(bodyStart)) {
            end = nextService.start();
        }
        return compose.substring(start, end);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null
                && !Files.isRegularFile(current.resolve("card-platform/docker-compose.yml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("repository root was not found");
        }
        return current;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    private static Map<String, Set<String>> requiredServiceKeys() {
        // PROCESSED_EVENT_RETENTION_HOURS is absent by design. It would set a horizon after which a
        // duplicate-delivery claim is deleted, and every effect a claim guards outlives such a
        // horizon, so a claim is permanent and no service binds the key.
        Set<String> producerRetention = Set.of(
                "OUTBOX_PUBLISHED_RETENTION_HOURS",
                "RETENTION_SWEEP_INTERVAL_MS");
        Set<String> consumerRetry = Set.of(
                "CONSUMER_MAX_RETRY_ATTEMPTS",
                "CONSUMER_RETRY_BACKOFF_MS");
        Map<String, Set<String>> required = new LinkedHashMap<>();
        required.put("authorization-service", union(producerRetention, consumerRetry, Set.of(
                "TOPIC_ACCOUNT_STATE_CHANGED", "TOPIC_CARD_UPDATED", "TOPIC_DEAD_LETTER")));
        required.put("ledger-posting-service", producerRetention);
        required.put("fraud-detection-service", producerRetention);
        required.put("notification-service", Set.of(
                "GROUP_NOTIFICATION_POSTED",
                "GROUP_NOTIFICATION_FRAUD",
                "GROUP_NOTIFICATION_CUSTOMER",
                "TOPIC_TRANSACTION_POSTED",
                "TOPIC_FRAUD_ASSESSED",
                "TOPIC_CUSTOMER_CONTEXT_CHANGED",
                "TOPIC_DEAD_LETTER",
                "RETENTION_SWEEP_INTERVAL_MS"));
        required.put("account-service", union(producerRetention, Set.of("TOPIC_DEAD_LETTER")));
        required.put("card-service", union(producerRetention, Set.of("TOPIC_DEAD_LETTER")));
        return Map.copyOf(required);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }
}