package com.carddemo.equivalence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the Kubernetes event-bus source of truth, durability and workload isolation. */
@DisplayName("Kubernetes deployment contracts")
class KubernetesDeploymentContractTest {

    private static final List<String> TOPIC_KEYS = List.of(
            "TOPIC_TRANSACTION_AUTHORIZED",
            "TOPIC_TRANSACTION_DECLINED",
            "TOPIC_TRANSACTION_POSTED",
            "TOPIC_FRAUD_ASSESSED",
            "TOPIC_ACCOUNT_STATE_CHANGED",
            "TOPIC_CUSTOMER_CONTEXT_CHANGED",
            "TOPIC_CARD_UPDATED",
            "TOPIC_DEAD_LETTER");

    private static final Map<String, String> SERVICE_TLS_SECRETS = Map.of(
            "authorization-service", "carddemo-authorization-tls-secret",
            "ledger-posting-service", "carddemo-ledger-tls-secret",
            "fraud-detection-service", "carddemo-fraud-tls-secret",
            "notification-service", "carddemo-notification-tls-secret",
            "account-service", "carddemo-account-tls-secret",
            "card-service", "carddemo-card-tls-secret");

    private static final List<String> SERVICE_MANIFESTS = List.of(
            "40-authorization-service.yaml",
            "41-ledger-posting-service.yaml",
            "42-fraud-detection-service.yaml",
            "43-notification-service.yaml",
            "44-account-service.yaml",
            "45-card-service.yaml");

    private static final Pattern PLATFORM_VERSION = Pattern.compile(
            "<artifactId>card-platform</artifactId>\\s*<version>([^<]+)</version>");

    @Test
    @DisplayName("Kafka provisioning consumes every topic and sizing value from the ConfigMap")
    void kafkaProvisioningConsumesEveryTopicAndSizingValueFromTheConfigMap() {
        Map<String, Object> deployment = resource("10-kafka.yaml", "Deployment", "kafka");
        Map<String, Object> container = container(deployment, "kafka");
        Map<String, Map<String, Object>> environment = environmentOf(container);
        String command = String.valueOf(list(container.get("command")).get(2));

        for (String key : TOPIC_KEYS) {
            assertConfigMapBinding(environment.get(key), key);
            assertThat(occurrences(command, "${" + key + "}"))
                    .as("%s controls topic creation and at least one ACL", key)
                    .isGreaterThanOrEqualTo(2);
        }
        assertConfigMapBinding(environment.get("KAFKA_TOPIC_PARTITIONS"),
                "KAFKA_TOPIC_PARTITIONS");
        assertConfigMapBinding(environment.get("KAFKA_TOPIC_REPLICATION_FACTOR"),
                "KAFKA_TOPIC_REPLICATION_FACTOR");
        assertThat(command)
                .contains("--partitions \"${KAFKA_TOPIC_PARTITIONS}\"",
                        "--replication-factor \"${KAFKA_TOPIC_REPLICATION_FACTOR}\"")
                .doesNotContain("--partitions 3", "--replication-factor 1");
    }

    @Test
    @DisplayName("notification customer-context routing has a group, consumer ACL and dead letter")
    void notificationCustomerContextRoutingHasAGroupConsumerAclAndDeadLetter() {
        Map<String, Object> kafka = resource("10-kafka.yaml", "Deployment", "kafka");
        Map<String, Object> broker = container(kafka, "kafka");
        Map<String, Map<String, Object>> brokerEnvironment = environmentOf(broker);
        String command = String.valueOf(list(broker.get("command")).get(2));

        assertConfigMapBinding(brokerEnvironment.get("GROUP_NOTIFICATION_CUSTOMER"),
                "GROUP_NOTIFICATION_CUSTOMER");
        assertThat(command)
                .contains(
                        "grant_consumer \"${NOTIFICATION_KAFKA_USER}\" "
                                + "\"${TOPIC_CUSTOMER_CONTEXT_CHANGED}\"",
                        "\"${GROUP_NOTIFICATION_CUSTOMER}\" || return 1",
                        "\"${TOPIC_CUSTOMER_CONTEXT_CHANGED}${TOPIC_DEAD_LETTER_SUFFIX}\"");

        Map<String, Object> notification =
                resource("43-notification-service.yaml", "Deployment", "notification-service");
        Map<String, Map<String, Object>> notificationEnvironment =
                environmentOf(container(notification, "notification-service"));
        assertConfigMapBinding(notificationEnvironment.get("GROUP_NOTIFICATION_CUSTOMER"),
                "GROUP_NOTIFICATION_CUSTOMER");
    }

    /**
     * The account service reads {@code transaction.posted}, so three things have to line up.
     *
     * <p>An access control entry the authorizer can match, a group of its own, and the same group
     * name bound into the workload that joins it. Miss the entry and every delivery is refused; miss
     * the group and the listener either fails to start or silently shares another service's offsets.
     *
     * <p>The group must be the account service's own rather than the notification group already
     * reading this topic. Two services in one group split the partitions between them, so each would
     * see roughly half the postings and neither would raise anything.
     *
     * <p>What the chain carries is the amount the account record is missing. The balance the account
     * service reports and the accumulators the credit-limit rule reads both move only when a posting
     * reaches this listener.
     */
    @Test
    @DisplayName("account posted-transaction routing has a group, a consumer ACL and the same group "
            + "on the workload")
    void accountPostedTransactionRoutingHasAGroupConsumerAclAndWorkloadBinding() {
        Map<String, Object> kafka = resource("10-kafka.yaml", "Deployment", "kafka");
        Map<String, Object> broker = container(kafka, "kafka");
        Map<String, Map<String, Object>> brokerEnvironment = environmentOf(broker);
        String command = String.valueOf(list(broker.get("command")).get(2));

        assertConfigMapBinding(brokerEnvironment.get("GROUP_ACCOUNT_POSTED"),
                "GROUP_ACCOUNT_POSTED");
        assertThat(command)
                .contains(
                        "grant_consumer \"${ACCOUNT_KAFKA_USER}\" \"${TOPIC_TRANSACTION_POSTED}\"",
                        "\"${GROUP_ACCOUNT_POSTED}\" || return 1",
                        "grant_producer \"${ACCOUNT_KAFKA_USER}\" \"${TOPIC_DEAD_LETTER}\"");

        Map<String, Object> account =
                resource("44-account-service.yaml", "Deployment", "account-service");
        Map<String, Object> accountContainer = container(account, "account-service");
        Map<String, Map<String, Object>> accountEnvironment = environmentOf(accountContainer);
        assertConfigMapBinding(accountEnvironment.get("GROUP_ACCOUNT_POSTED"),
                "GROUP_ACCOUNT_POSTED");
        assertConfigMapBinding(accountEnvironment.get("SPRING_KAFKA_CONSUMER_GROUP_ID"),
                "GROUP_ACCOUNT_POSTED");
        assertThat(list(accountContainer.get("envFrom")).stream()
                .map(KubernetesDeploymentContractTest::map)
                .filter(source -> source.containsKey("configMapRef"))
                .map(source -> at(source, "configMapRef", "name"))
                .toList())
                .as("the topic name reaches this container through the whole-ConfigMap pull, which "
                        + "is how every service reads a renamed topic")
                .contains("carddemo-config");

        Map<String, Object> notification =
                resource("43-notification-service.yaml", "Deployment", "notification-service");
        Map<String, Map<String, Object>> notificationEnvironment =
                environmentOf(container(notification, "notification-service"));
        assertThat(at(notificationEnvironment.get("GROUP_NOTIFICATION_POSTED"),
                "valueFrom", "configMapKeyRef", "key"))
                .as("the notification service must keep its own group on this topic, or the two "
                        + "consumers split the partitions and each sees half the postings")
                .isEqualTo("GROUP_NOTIFICATION_POSTED");
    }

    @Test
    @DisplayName("Kafka data is backed by a persistent claim")
    void kafkaDataIsBackedByAPersistentClaim() {
        Map<String, Object> claim =
                resource("10-kafka.yaml", "PersistentVolumeClaim", "kafka-data");
        assertThat(at(claim, "spec", "accessModes")).isEqualTo(List.of("ReadWriteOnce"));
        assertThat(at(claim, "spec", "resources", "requests", "storage")).isEqualTo("2Gi");

        Map<String, Object> deployment = resource("10-kafka.yaml", "Deployment", "kafka");
        Map<String, Object> dataVolume = volume(deployment, "kafka-data");
        assertThat(at(dataVolume, "persistentVolumeClaim", "claimName"))
                .isEqualTo("kafka-data");
        assertThat(dataVolume).doesNotContainKey("emptyDir");
    }

    @Test
    @DisplayName("each service mounts one private keystore and the shared trust material")
    void eachServiceMountsOnePrivateKeystoreAndTheSharedTrustMaterial() {
        Map<String, Object> trust =
                resource("31-secret.example.yaml", "Secret", "carddemo-trust-secret");
        assertThat(map(trust.get("data"))).containsOnlyKeys("truststore.p12");
        assertThat(map(trust.get("stringData")))
                .containsKeys("KAFKA_SSL_TRUSTSTORE_PASSWORD", "ca.crt")
                .doesNotContainKey("SERVER_SSL_KEYSTORE_PASSWORD");
        assertThat(resourceNames("31-secret.example.yaml")).doesNotContain("carddemo-tls-secret");

        Set<Object> privateKeyPlaceholders = new LinkedHashSet<>();
        Set<Object> privateKeyPasswords = new LinkedHashSet<>();
        for (Map.Entry<String, String> service : SERVICE_TLS_SECRETS.entrySet()) {
            Map<String, Object> secret =
                    resource("31-secret.example.yaml", "Secret", service.getValue());
            privateKeyPlaceholders.add(at(secret, "data", "keystore.p12"));
            privateKeyPasswords.add(at(secret, "stringData", "SERVER_SSL_KEYSTORE_PASSWORD"));

            String manifest = manifestFor(service.getKey());
            Map<String, Object> deployment =
                    resource(manifest, "Deployment", service.getKey());
            Map<String, Object> container = container(deployment, service.getKey());
            Map<String, Map<String, Object>> environment = environmentOf(container);
            assertSecretBinding(environment.get("SERVER_SSL_KEYSTORE_PASSWORD"),
                    service.getValue(), "SERVER_SSL_KEYSTORE_PASSWORD");
            assertSecretBinding(environment.get(
                            "SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_PASSWORD"),
                    "carddemo-trust-secret", "KAFKA_SSL_TRUSTSTORE_PASSWORD");

            Map<String, Object> projected = map(volume(deployment, "transport-security")
                    .get("projected"));
            assertThat(projected).containsEntry("defaultMode", 416);
            Map<String, List<String>> mountedKeys = projectedSecretKeys(projected);
            assertThat(mountedKeys)
                    .containsEntry(service.getValue(), List.of("keystore.p12"))
                    .containsEntry("carddemo-trust-secret", List.of("truststore.p12", "ca.crt"))
                    .hasSize(2);
        }
        assertThat(privateKeyPlaceholders).hasSize(6);
        assertThat(privateKeyPasswords).hasSize(6);
    }

    @Test
    @DisplayName("local service images match the Maven version and can never be pulled")
    void localServiceImagesMatchTheMavenVersionAndCanNeverBePulled() {
        String pom = read(repositoryRoot().resolve("card-platform/pom.xml"));
        Matcher version = PLATFORM_VERSION.matcher(pom);
        assertThat(version.find()).isTrue();
        String expectedVersion = version.group(1);

        for (String manifest : SERVICE_MANIFESTS) {
            String service = manifest.substring(3, manifest.length() - ".yaml".length());
            Map<String, Object> deployment = resource(manifest, "Deployment", service);
            Map<String, Object> container = container(deployment, service);
            assertThat(container.get("image"))
                    .isEqualTo("carddemo/" + service + ":" + expectedVersion);
            assertThat(container.get("imagePullPolicy")).isEqualTo("Never");
        }
    }

    /**
     * Holds the two upstream images to a digest, which is the only reference a node verifies.
     *
     * <p>Six of the eight images here cannot carry a digest as this repository ships, because a
     * locally built image has no manifest digest until it is pushed, so each is named by a mutable
     * tag. The two that are pulled from a registry can carry one, both already do, and this
     * assertion is what keeps a later edit from replacing either with a bare tag. A digest is
     * content-addressed: the pull yields exactly those bytes or it fails.
     */
    @Test
    @DisplayName("both upstream images are pinned by digest and never by tag alone")
    void bothUpstreamImagesArePinnedByDigest() {
        Map<String, String> upstream = Map.of(
                "10-kafka.yaml", "kafka",
                "20-postgres.yaml", "postgres");
        upstream.forEach((manifest, name) -> {
            Map<String, Object> container =
                    container(resource(manifest, "Deployment", name), name);
            String image = String.valueOf(container.get("image"));
            assertThat(image)
                    .as("%s is pulled from a registry, so its bytes can be named", name)
                    .matches("[^@]+:[^@:]+@sha256:[0-9a-f]{64}")
                    .doesNotContain(":latest");
            assertThat(container.get("imagePullPolicy"))
                    .as("a digest that is never pulled is never verified")
                    .isEqualTo("IfNotPresent");
        });
    }

    /**
     * Holds the six mutable references to one editable place, with the pinning procedure beside it.
     *
     * <p>This is the answer to the review's finding that is available without a registry. The tag
     * stays mutable and the manifests now say so; what changes is that pinning the six by digest is
     * one edit to {@code kustomization.yaml} rather than six edits spread across six Deployments,
     * and that the trade-off is written down where an operator will read it. Signing and
     * admission-time verification need a registry, a key and an admission controller, and section
     * 0.2.2 of the plan places production hardening out of scope.
     */
    @Test
    @DisplayName("the six mutable references live in one place with the digest procedure beside them")
    void theSixMutableReferencesLiveInOnePlace() {
        Path folder = repositoryRoot().resolve("card-platform/deploy/k8s");
        String kustomization = read(folder.resolve("kustomization.yaml"));
        String readme = read(folder.resolve("README.md"));

        for (String manifest : SERVICE_MANIFESTS) {
            String service = manifest.substring(3, manifest.length() - ".yaml".length());
            assertThat(kustomization)
                    .as("kustomization.yaml must name %s so its reference is editable there",
                            service)
                    .contains("- name: carddemo/" + service);
            assertThat(kustomization)
                    .as("and must list the manifest it rewrites")
                    .contains("  - " + manifest);
            assertThat(read(folder.resolve(manifest)))
                    .as("%s must point a reader at the one place the reference can be pinned",
                            service)
                    .contains("deploy/k8s/kustomization.yaml");
        }

        assertThat(kustomization)
                .as("the template of refused values must not be applied by the entry point")
                .doesNotContain("  - 31-secret.example.yaml");
        assertThat(kustomization)
                .as("the pinning edit has to be recorded beside the values it changes")
                .contains("kustomize edit set image")
                .contains("@sha256:<digest>");
        assertThat(readme)
                .as("the residual risk is that the tag stays mutable, and a reader has to be "
                        + "told rather than left to infer it")
                .contains("MUTABLE")
                .contains("kustomize edit set image");
    }

    /**
     * A port name is metadata, and other tooling acts on it. Service meshes, ingress controllers
     * and scrapers read the name to decide how to speak to the port, so a port carrying Transport
     * Layer Security under the name {@code http} tells them the wrong thing and the failure lands
     * somewhere else entirely. {@code 30-configmap.yaml} sets {@code SERVER_SSL_ENABLED}, so the
     * business port is encrypted on all six and every one of them must say so.
     *
     * <p>The Service targets the port by name rather than by number for a second reason: the
     * number then lives in the Deployment alone, so the two cannot drift. {@code 10-kafka.yaml}
     * already targets {@code internal} that way.
     */
    @Test
    @DisplayName("the encrypted business port is named https and the Service targets it by name")
    void theEncryptedBusinessPortIsNamedHttpsAndTargetedByName() {
        assertThat(read(kubernetesDirectory().resolve("30-configmap.yaml")))
                .as("this test only holds while the business port really is encrypted")
                .contains("SERVER_SSL_ENABLED: \"true\"");

        for (String manifest : SERVICE_MANIFESTS) {
            String service = manifest.substring(3, manifest.length() - ".yaml".length());

            Map<String, Object> deployment = resource(manifest, "Deployment", service);
            List<Object> containerPorts = list(at(container(deployment, service), "ports"));
            Map<String, Object> business = containerPorts.stream()
                    .map(KubernetesDeploymentContractTest::map)
                    .filter(port -> Long.valueOf(8080L).equals(
                            Long.valueOf(((Number) port.get("containerPort")).longValue())))
                    .findFirst()
                    .orElseThrow(() ->
                            new AssertionError(manifest + " declares no container port 8080"));
            assertThat(business.get("name"))
                    .as("%s must name container port 8080 for the protocol it speaks", service)
                    .isEqualTo("https");

            Map<String, Object> declared = resource(manifest, "Service", service);
            List<Object> servicePorts = list(at(declared, "spec", "ports"));
            assertThat(servicePorts)
                    .as("%s exposes the business port alone; /actuator is on 9080, which no "
                            + "Service publishes", service)
                    .hasSize(1);
            Map<String, Object> exposed = map(servicePorts.getFirst());
            assertThat(exposed.get("name"))
                    .as("the Service port carries the same name")
                    .isEqualTo("https");
            assertThat(exposed.get("targetPort"))
                    .as("and targets the container port by name, so the number lives in the "
                            + "Deployment alone")
                    .isEqualTo("https");
            assertThat(exposed.get("appProtocol"))
                    .as("appProtocol is where protocol-aware tooling reads the application "
                            + "protocol; protocol: TCP only states the transport")
                    .isEqualTo("https");
            assertThat(exposed.get("protocol")).isEqualTo("TCP");
        }
    }

    /**
     * {@code latest} on a Pod Security version label means whatever the running control plane
     * currently defines the profile to be. A cluster upgrade can then tighten the policy under a
     * manifest nobody edited, and the six Deployments start failing admission for a reason that is
     * not in this repository. Pinning the version is what makes the applied policy a property of
     * these files.
     */
    @Test
    @DisplayName("the Pod Security profile is pinned to a version rather than tracking latest")
    void thePodSecurityProfileIsPinnedToAVersion() {
        Map<String, Object> namespace = resource("00-namespace.yaml", "Namespace", "carddemo");
        Map<String, Object> labels = map(at(namespace, "metadata", "labels"));

        Set<String> pinned = new LinkedHashSet<>();
        for (String mode : List.of("enforce", "audit", "warn")) {
            assertThat(labels.get("pod-security.kubernetes.io/" + mode))
                    .as("%s must name the restricted profile", mode)
                    .isEqualTo("restricted");
            Object version = labels.get("pod-security.kubernetes.io/" + mode + "-version");
            assertThat(version)
                    .as("%s-version must be pinned, not left tracking latest", mode)
                    .isNotNull()
                    .isNotEqualTo("latest");
            assertThat(version.toString())
                    .as("%s-version must be a Kubernetes minor version", mode)
                    .matches("v1\\.\\d+");
            pinned.add(version.toString());
        }
        assertThat(pinned)
                .as("the three modes must judge against the same policy content, or audit and warn "
                        + "stop predicting what enforce will refuse")
                .hasSize(1);

        assertThat(read(kubernetesDirectory().resolve("00-namespace.yaml")))
                .as("and the pin has to say why it is a pin, or the next reader restores latest")
                .contains(".spec.os.name");
    }

    /**
     * Every persistent claim declares encryption at rest, and one overlay binds it.
     *
     * <p>Two volumes hold everything this platform stores. One carries all six schemas, including
     * fifty full card numbers, fifty card verification values and the only table describing an
     * identifiable person. The other carries every event published, including the ten cardholder
     * fields {@code CustomerContextChanged} holds. A claim naming no storage class, with no
     * manifest requiring an encrypted one, is bound with whatever default the cluster has.
     *
     * <p>The base still names no class, because the documented demonstration binds on kind, minikube
     * and Docker Desktop with no edit. What it names instead is the requirement, as annotations a
     * person and an admission policy can both read, and the overlay that satisfies it. The sweep
     * below reads every claim in the folder rather than the two by name, so a third claim is held to
     * the same declaration without an edit here.
     */
    @Test
    @DisplayName("every persistent claim declares encryption at rest and names the overlay")
    void everyPersistentClaimDeclaresEncryptionAtRest() {
        List<String> claims = new ArrayList<>();
        for (String manifest : manifestFiles()) {
            for (Map<String, Object> resource : resources(manifest)) {
                if (!"PersistentVolumeClaim".equals(resource.get("kind"))) {
                    continue;
                }
                String name = String.valueOf(at(resource, "metadata", "name"));
                claims.add(name);
                Map<String, Object> annotations = map(at(resource, "metadata", "annotations"));
                assertThat(annotations)
                        .as("%s in %s holds subject data, so its claim has to declare that the"
                                + " storage under it is encrypted", name, manifest)
                        .containsEntry("carddemo.io/requires-encryption-at-rest", "true");
                assertThat(String.valueOf(annotations.get("carddemo.io/data-classification")))
                        .as("%s has to say what the volume holds, or the requirement is decorative",
                                name)
                        .isNotBlank()
                        .isNotEqualTo("null");
                assertThat(String.valueOf(annotations.get("carddemo.io/encryption-overlay")))
                        .as("%s has to name the overlay that satisfies the requirement", name)
                        .contains("overlays/encrypted-storage");
                assertThat(map(at(resource, "spec")))
                        .as("the base names no storage class, so the demonstration binds on any"
                                + " cluster and the overlay is the one place a class is chosen")
                        .doesNotContainKey("storageClassName");
            }
        }
        assertThat(claims)
                .as("the sweep has to reach both volumes, or its verdict means nothing")
                .containsExactlyInAnyOrder("kafka-data", "postgres-data");
    }

    /**
     * The overlay binds an encrypted class to every claim, and cannot go stale.
     *
     * <p>It patches by {@code kind} rather than by name and lists the base folder rather than the
     * eleven manifests, so a claim added or a manifest renamed needs no edit here. The sweep reads
     * the declarations rather than the whole file, because the comment block carries the two apply
     * commands an operator runs and those name manifests on purpose. The base
     * kustomization does not name the overlay, because a base that included its own overlay would
     * apply the patch to itself and leave no unencrypted path for the documented local run.
     */
    @Test
    @DisplayName("the encrypted-storage overlay patches by kind and lists the base, not the manifests")
    void theEncryptedStorageOverlayPatchesByKind() {
        Path overlay = kubernetesDirectory()
                .resolve("overlays/encrypted-storage/kustomization.yaml");
        assertThat(Files.isRegularFile(overlay))
                .as("the annotation on both claims names this file, so it has to exist")
                .isTrue();

        String text = read(overlay);
        Map<String, Object> kustomization = map(new Yaml(
                new SafeConstructor(new LoaderOptions())).load(text));
        assertThat(at(kustomization, "resources"))
                .as("the overlay applies the base folder, so it cannot fall behind a new manifest")
                .isEqualTo(List.of("../.."));
        assertThat(text)
                .as("patching by kind covers a third claim without an edit here")
                .contains("kind: PersistentVolumeClaim")
                .contains("/spec/storageClassName");

        String declarations = text.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (left, right) -> left + "\n" + right);
        for (String manifest : manifestFiles()) {
            assertThat(declarations)
                    .as("the overlay must not declare %s as a resource, or it goes stale when a"
                            + " manifest is renamed", manifest)
                    .doesNotContain(manifest);
        }

        assertThat(read(kubernetesDirectory().resolve("kustomization.yaml")))
                .as("the base must not include its own overlay, or there is no unencrypted path for"
                        + " the documented local run")
                .doesNotContain("overlays");
    }

    /** Every manifest of the base folder, which excludes the overlay directory below it. */
    private static List<String> manifestFiles() {
        try (Stream<Path> files = Files.list(kubernetesDirectory())) {
            List<String> manifests = files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".yaml"))
                    .filter(name -> !"kustomization.yaml".equals(name))
                    .sorted()
                    .toList();
            assertThat(manifests)
                    .as("the base folder has to hold the manifests this contract reads")
                    .hasSizeGreaterThanOrEqualTo(11);
            return manifests;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + kubernetesDirectory(), unreadable);
        }
    }

    private static String manifestFor(String service) {
        return SERVICE_MANIFESTS.stream()
                .filter(name -> name.contains(service))
                .findFirst()
                .orElseThrow();
    }

    private static void assertConfigMapBinding(Map<String, Object> environment, String key) {
        assertThat(at(environment, "valueFrom", "configMapKeyRef", "name"))
                .isEqualTo("carddemo-config");
        assertThat(at(environment, "valueFrom", "configMapKeyRef", "key")).isEqualTo(key);
    }

    private static void assertSecretBinding(
            Map<String, Object> environment, String secret, String key) {
        assertThat(at(environment, "valueFrom", "secretKeyRef", "name")).isEqualTo(secret);
        assertThat(at(environment, "valueFrom", "secretKeyRef", "key")).isEqualTo(key);
    }

    private static Map<String, List<String>> projectedSecretKeys(Map<String, Object> projected) {
        Map<String, List<String>> mounted = new LinkedHashMap<>();
        for (Object sourceValue : list(projected.get("sources"))) {
            Map<String, Object> secret = map(map(sourceValue).get("secret"));
            List<String> keys = list(secret.get("items")).stream()
                    .map(KubernetesDeploymentContractTest::map)
                    .map(item -> String.valueOf(item.get("key")))
                    .toList();
            mounted.put(String.valueOf(secret.get("name")), keys);
        }
        return Map.copyOf(mounted);
    }

    private static Map<String, Map<String, Object>> environmentOf(Map<String, Object> container) {
        Map<String, Map<String, Object>> environment = new LinkedHashMap<>();
        for (Object value : list(container.get("env"))) {
            Map<String, Object> entry = map(value);
            environment.put(String.valueOf(entry.get("name")), entry);
        }
        return Map.copyOf(environment);
    }

    private static Map<String, Object> container(Map<String, Object> deployment, String name) {
        return list(at(deployment, "spec", "template", "spec", "containers")).stream()
                .map(KubernetesDeploymentContractTest::map)
                .filter(candidate -> name.equals(candidate.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> volume(Map<String, Object> deployment, String name) {
        return list(at(deployment, "spec", "template", "spec", "volumes")).stream()
                .map(KubernetesDeploymentContractTest::map)
                .filter(candidate -> name.equals(candidate.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> resourceNames(String file) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> resource : resources(file)) {
            names.add(String.valueOf(at(resource, "metadata", "name")));
        }
        return Set.copyOf(names);
    }

    private static Map<String, Object> resource(String file, String kind, String name) {
        return resources(file).stream()
                .filter(candidate -> kind.equals(candidate.get("kind")))
                .filter(candidate -> name.equals(at(candidate, "metadata", "name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        file + " carries no " + kind + " named " + name));
    }

    private static List<Map<String, Object>> resources(String file) {
        String text = read(kubernetesDirectory().resolve(file));
        List<Map<String, Object>> resources = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(text)) {
            if (document != null) {
                resources.add(map(document));
            }
        }
        return List.copyOf(resources);
    }

    private static Object at(Map<String, Object> source, String... path) {
        Object current = source;
        for (String step : path) {
            current = map(current).get(step);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) {
            throw new AssertionError("expected a mapping, found " + value);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        mapping.forEach((key, entry) -> result.put(String.valueOf(key), entry));
        return result;
    }

    private static List<Object> list(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new AssertionError("expected a list, found " + value);
        }
        return new ArrayList<>(values);
    }

    private static long occurrences(String value, String token) {
        return value.split(Pattern.quote(token), -1).length - 1L;
    }

    private static Path kubernetesDirectory() {
        return repositoryRoot().resolve("card-platform/deploy/k8s");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null
                && !Files.isRegularFile(current.resolve("card-platform/pom.xml"))) {
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

    /** A commented command line inside one of the recipes {@code 31-secret.example.yaml} carries. */
    private static final Pattern RECIPE_COMMAND = Pattern.compile("^\\s*#\\s{3,}\\S");

    /**
     * Whether one recipe line continues onto the next, so the two belong to the same command.
     *
     * <p>One recipe carries a subject-alternative-name list too long to indent, and its continuation
     * sits at the left margin. Indentation alone would end the recipe there and leave the commands
     * after it looking like a recipe of their own, which is how a mode set once at the top would
     * appear to be missing from half of it.
     *
     * @param line the line already inside the recipe
     * @param next the line after it
     * @return true when the first ends in a shell continuation and the second is still a comment
     */
    private static boolean continues(String line, String next) {
        return line.endsWith("\\") && next.stripLeading().startsWith("#");
    }

    /** A command that creates a file holding a private key, a keystore or a password. */
    private static final Pattern SECRET_FILE_WRITE = Pattern.compile(
            "-keyout\\s|>\\s*\\.?/?\"?\\$?\\{?[A-Za-z0-9_${}./-]*\\.(?:pass|key|p12)|"
                    + "-keystore\\s+truststore\\.p12|-out\\s+\"\\$\\{service}\\.p12\"");

    /** A mode a recipe may set, either ahead of the write or as part of it. */
    private static final Pattern RECIPE_MODE = Pattern.compile("umask 077|install -m 600");

    /** Fewest secret-writing recipes the scan must find before its verdict means anything. */
    private static final int SECRET_RECIPE_FLOOR = 4;

    /**
     * Every recipe that writes a secret to a file sets the mode as the file is created.
     *
     * <p>{@code openssl req -keyout server.key} and
     * {@code openssl rand … > "${service}.pass"} create their files under the umask in force,
     * 0644 on a default account, and a private key or a keystore password readable by every local
     * account is the same exposure the environment generator carried until it set
     * {@code umask 077}. These are commands a reader copies, so the recipe is where the mode has to
     * appear: a reader who follows the steps exactly must not have to know to add it.
     *
     * <p>A recipe is a run of commented command lines. Each run that creates a {@code .key},
     * {@code .pass} or {@code .p12} file, or imports one into a truststore, has to carry either
     * {@code umask 077} or an {@code install -m 600} that sets the mode itself. The floor below is
     * asserted first, so a parser that stopped recognising recipes fails rather than passing.
     */
    @Test
    @DisplayName("every documented recipe that writes a secret file sets the mode as it creates it")
    void everySecretWritingRecipeSetsTheModeAsItCreatesTheFile() {
        List<String> lines = List.of(read(repositoryRoot()
                .resolve("card-platform/deploy/k8s/31-secret.example.yaml")).split("\\R", -1));

        List<String> unprotected = new ArrayList<>();
        int recipes = 0;
        int from = 0;
        while (from < lines.size()) {
            if (!RECIPE_COMMAND.matcher(lines.get(from)).find()) {
                from++;
                continue;
            }
            int to = from;
            while (to + 1 < lines.size()
                    && (RECIPE_COMMAND.matcher(lines.get(to + 1)).find()
                            || continues(lines.get(to), lines.get(to + 1)))) {
                to++;
            }
            String recipe = String.join("\n", lines.subList(from, to + 1));
            if (SECRET_FILE_WRITE.matcher(recipe).find()) {
                recipes++;
                if (!RECIPE_MODE.matcher(recipe).find()) {
                    unprotected.add("lines " + (from + 1) + "-" + (to + 1) + ": "
                            + lines.get(from).trim());
                }
            }
            from = to + 1;
        }

        assertThat(recipes)
                .as("31-secret.example.yaml documents the certificate and password recipes, so a "
                        + "scan finding fewer than %d of them is reading the file wrongly",
                        SECRET_RECIPE_FLOOR)
                .isGreaterThanOrEqualTo(SECRET_RECIPE_FLOOR);
        assertThat(unprotected)
                .as("a recipe that creates a private key, a keystore or a password file has to set "
                        + "the mode as it creates it, because a mode corrected afterwards leaves the "
                        + "secret readable in between")
                .isEmpty();
    }
}