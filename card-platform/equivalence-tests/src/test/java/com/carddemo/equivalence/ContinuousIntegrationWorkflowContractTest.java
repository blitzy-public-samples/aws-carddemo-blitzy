package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Protects the continuous-integration stages required by the platform specification. */
class ContinuousIntegrationWorkflowContractTest {

    private static final Set<String> EXPECTED_JOBS =
            Set.of(
                    "compile",
                    "unit-tests",
                    "integration-tests",
                    "equivalence-tests",
                    "schema-compatibility",
                    "container-builds");

    private static final List<String> SERVICES =
            List.of(
                    "authorization-service",
                    "ledger-posting-service",
                    "fraud-detection-service",
                    "notification-service",
                    "account-service",
                    "card-service");

    /**
     * The statement each job uses to assemble the checksum line from the digest Apache publishes.
     *
     * <p>{@code apache-maven-3.9.16-bin.tar.gz.sha512} holds 128 hexadecimal characters, no file
     * name and no closing newline. GNU coreutils reads a checksum line as digest, two spaces, file
     * name, so the digest alone is not a checksum file. {@code awk} takes the first field, which
     * also tolerates the two-field form other projects publish.
     */
    private static final String ARCHIVE_CHECKSUM_LINE =
            "printf '%s  %s\\n' \"$(awk '{print $1}' \"/tmp/${archive}.sha512\")\" \"${archive}\"";

    /** The verification each job runs over the assembled line. */
    private static final String ARCHIVE_CHECKSUM_VERIFICATION =
            "sha512sum --check --strict \"${archive}.sha512sum\"";

    /**
     * The form that reads the published file as if it were a checksum file. It exits 1 with "no
     * properly formatted checksum lines found", and no job may carry it.
     */
    private static final String BARE_DIGEST_VERIFICATION =
            "sha512sum --check \"${archive}.sha512\"";

    /**
     * The statement the compile stage collects class files with.
     *
     * <p>Every class file the reactor wrote is read, because a single sampled file always
     * resolves inside the first module and says nothing about the other eight.
     */
    private static final String CLASS_FILE_DISCOVERY =
            "mapfile -t class_files < <(find libs services -path '*/target/classes/*.class' | sort)";

    private static Path platformRoot;
    private static String workflow;
    private static Map<?, ?> document;
    private static Map<?, ?> jobs;

    @BeforeAll
    static void loadWorkflow() throws IOException {
        platformRoot = locatePlatformRoot();
        Path workflowPath = platformRoot.getParent().resolve(".github/workflows/ci.yml");
        assertTrue(Files.isRegularFile(workflowPath), "The CI workflow is missing");
        workflow = Files.readString(workflowPath);
        document = assertInstanceOf(Map.class, new Yaml().load(workflow));
        jobs = assertInstanceOf(Map.class, document.get("jobs"));
    }

    @Test
    @DisplayName("the workflow is valid YAML with the six required jobs")
    void theWorkflowIsValidYamlWithTheSixRequiredJobs() {
        assertEquals(EXPECTED_JOBS, jobs.keySet());
        EXPECTED_JOBS.forEach(job -> assertInstanceOf(Map.class, jobs.get(job)));
    }

    @Test
    @DisplayName("pull requests, pushes and manual runs execute with read-only repository permission")
    void theWorkflowDeclaresTriggersPermissionsConcurrencyAndWorkingDirectory() {
        assertTrue(workflow.contains("on:\n  pull_request:\n  push:\n  workflow_dispatch:"));
        assertTrue(workflow.contains("permissions:\n  contents: read"));
        assertTrue(workflow.contains("cancel-in-progress: true"));
        assertTrue(workflow.contains("working-directory: card-platform"));
        assertFalse(workflow.contains("permissions: write"));
    }

    @Test
    @DisplayName("every job uses Java 25 and the verified Maven 3.9.16 archive")
    void everyJobUsesThePinnedToolchain() {
        assertTrue(workflow.contains("JAVA_VERSION: \"25\""));
        assertTrue(workflow.contains("MAVEN_VERSION: \"3.9.16\""));
        assertEquals(6, occurrences(workflow, "uses: actions/setup-java@v4"));
        assertEquals(6, occurrences(workflow, "distribution: temurin"));
        assertEquals(6, occurrences(workflow, "cache-dependency-path: card-platform/**/pom.xml"));
        assertEquals(6, occurrences(workflow, "archive.apache.org/dist/maven/maven-3/"));
        assertEquals(6, occurrences(workflow, ARCHIVE_CHECKSUM_LINE));
        assertEquals(6, occurrences(workflow, ARCHIVE_CHECKSUM_VERIFICATION));
        assertFalse(workflow.contains(BARE_DIGEST_VERIFICATION),
                "the published file holds a digest with no file name, which GNU coreutils"
                        + " refuses; verifying it directly fails every job at its second step");
    }

    @Test
    @DisplayName("the compile stage reads the release of every class file, not one sample")
    void theCompileStageReadsEveryClassFile() {
        assertTrue(workflow.contains(CLASS_FILE_DISCOVERY),
                "the check has to collect every class file the reactor wrote");
        assertTrue(workflow.contains("javap -verbose \"${class_files[@]}\""),
                "one javap invocation reads the whole collection");
        assertTrue(workflow.contains("test \"${reported}\" -eq \"${#class_files[@]}\""),
                "javap has to report a release for every file collected");
        assertTrue(workflow.contains("test \"${releases}\" = \"69\""),
                "69 is the class-file version of release 25, and the only value permitted");
        assertFalse(workflow.contains("-print -quit"),
                "a single sampled class file passes while another module is drifted, which is"
                        + " what this step exists to catch");
    }

    @Test
    @DisplayName("actions are versioned and no job weakens a failure")
    void actionsAreVersionedAndFailuresRemainFatal() {
        Matcher action = Pattern.compile("(?m)^\\s*uses:\\s*(\\S+)").matcher(workflow);
        int actionCount = 0;
        while (action.find()) {
            actionCount++;
            assertTrue(
                    action.group(1).matches("actions/(?:checkout|setup-java|upload-artifact)@v4"),
                    "Unexpected or unversioned action: " + action.group(1));
        }
        assertEquals(16, actionCount);
        assertFalse(workflow.contains("@main"));
        assertFalse(workflow.contains("@master"));
        assertFalse(workflow.contains("@latest"));
        assertFalse(workflow.contains("continue-on-error"));
        assertFalse(workflow.contains("mvn install"));
    }

    @Test
    @DisplayName("compile and unit stages cover the complete reactor")
    void compileAndUnitStagesCoverTheCompleteReactor() {
        assertTrue(workflow.contains("mvn -B -ntp -DskipTests compile"));
        assertTrue(workflow.contains("mvn -B -ntp -pl equivalence-tests -am test"));
        assertEquals(4, occurrences(workflow, "needs: compile"));
    }

    @Test
    @DisplayName("integration and equivalence stages start and always remove their infrastructure")
    void integrationAndEquivalenceStagesManageTheirInfrastructure() {
        assertEquals(3, occurrences(workflow, "run: cp .env.example .env"));
        assertEquals(2, occurrences(workflow, "docker compose up --detach --wait postgres kafka"));
        assertEquals(3, occurrences(workflow, "docker compose down --volumes"),
                "the integration, equivalence and container stages each remove their own stack");
        assertEquals(8, occurrences(workflow, "if: always()"),
                "the four report uploads, the container-state report and the three teardowns run"
                        + " whether the stage passed or failed");
        assertTrue(workflow.contains("mvn -B -ntp -pl \"${modules}\" -am verify"));
        assertTrue(workflow.contains("mvn -B -ntp -pl equivalence-tests -am verify"));
        SERVICES.forEach(
                service ->
                        assertTrue(
                                workflow.contains("services/" + service),
                                "Integration stage omits " + service));
    }

    @Test
    @DisplayName("schema compatibility is a distinct build-failing stage")
    void schemaCompatibilityIsADistinctBuildFailingStage() {
        assertTrue(workflow.contains("name: Event schema compatibility"));
        assertTrue(workflow.contains("-pl libs/event-contracts"));
        assertTrue(
                workflow.contains(
                        "-Dtest=SchemaBackwardCompatibilityTest,EventRoundTripTest"));
        assertFalse(workflow.contains("-DskipSchema"));
    }

    @Test
    @DisplayName("container builds wait for every quality gate")
    void containerBuildsWaitForEveryQualityGate() {
        Map<?, ?> containerJob = assertInstanceOf(Map.class, jobs.get("container-builds"));
        Object needs = containerJob.get("needs");
        assertEquals(
                List.of(
                        "unit-tests",
                        "integration-tests",
                        "equivalence-tests",
                        "schema-compatibility"),
                needs);
        assertTrue(workflow.contains("mvn -B -ntp -DskipTests package"));
        assertTrue(workflow.contains("docker compose config --quiet"));
    }

    @Test
    @DisplayName("all six delivered Dockerfiles are built from their service contexts")
    void allSixDeliveredDockerfilesAreBuiltFromTheirServiceContexts() {
        assertTrue(workflow.contains("for service in \"${services[@]}\""));
        assertTrue(workflow.contains("--file \"services/${service}/Dockerfile\""));
        assertTrue(workflow.contains("\"services/${service}\""));
        SERVICES.forEach(
                service -> {
                    assertTrue(
                            Files.isRegularFile(
                                    platformRoot.resolve("services/" + service + "/Dockerfile")),
                            "Missing Dockerfile for " + service);
                    assertTrue(
                            workflow.contains("\n            " + service + "\n"),
                            "Container array omits " + service);
                });
    }

    @Test
    @DisplayName("every stage that writes a test report keeps it as a downloadable artifact")
    void everyStageThatWritesAReportKeepsIt() {
        assertEquals(4, occurrences(workflow, "uses: actions/upload-artifact@v4"),
                "the unit, integration, equivalence and schema stages each write reports; a run"
                        + " that fails has to leave them downloadable");
        assertEquals(4, occurrences(workflow, "if-no-files-found: error"),
                "an upload that finds nothing means the stage did not run its tests, which is a"
                        + " failure rather than an empty artifact");
        assertEquals(4, occurrences(workflow,
                        "retention-days: ${{ env.REPORT_RETENTION_DAYS }}"),
                "one retention period governs every upload");
        assertTrue(workflow.contains("REPORT_RETENTION_DAYS: \"14\""));
        for (String artifact : List.of("unit-test-reports", "integration-test-reports",
                "equivalence-test-reports", "schema-compatibility-reports")) {
            assertTrue(workflow.contains("name: " + artifact),
                    "the run summary needs a distinct name per artifact: " + artifact);
        }
        assertTrue(workflow.contains("card-platform/**/target/surefire-reports/**"));
        assertTrue(workflow.contains("card-platform/**/target/failsafe-reports/**"));
    }

    @Test
    @DisplayName("one image tag serves the workflow, Compose and Kubernetes")
    void oneImageTagServesEveryConsumer() throws IOException {
        String platformPom = Files.readString(platformRoot.resolve("pom.xml"));
        Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(platformPom);
        assertTrue(version.find(), "the aggregator declares its version");
        String projectVersion = version.group(1);

        assertTrue(workflow.contains("IMAGE_TAG: \"" + projectVersion + "\""),
                "the workflow builds under the project version, not a floating tag");
        assertTrue(workflow.contains("--tag \"carddemo/${service}:${IMAGE_TAG}\""),
                "and every image build reads that variable");
        assertFalse(workflow.contains(":ci\""),
                "a tag no other file names leaves the built images consumed by nothing");

        String compose = Files.readString(platformRoot.resolve("docker-compose.yml"));
        SERVICES.forEach(service ->
                assertTrue(compose.contains("image: carddemo/" + service + ":" + projectVersion),
                        "Compose has to run the image the workflow built: " + service));
    }

    @Test
    @DisplayName("the container stage starts the images it built and always removes them")
    void theContainerStageStartsWhatItBuilt() {
        assertTrue(workflow.contains("docker compose up --detach --wait\n"),
                "the whole stack starts, not only the database and the broker");
        assertTrue(workflow.contains("/actuator/health"),
                "a started service has to be asked whether it is up");
        assertTrue(workflow.contains("'\"status\":\"UP\"'"),
                "a 200 carrying a down status is not a passing check");
        assertTrue(workflow.contains("PasswordEncoderFactories"),
                "the three identity secrets are generated already encoded, because a service"
                        + " refuses a value carrying no encoding prefix");
        assertTrue(workflow.contains("test \"$(grep -c 'REPLACE-WITH\\|REPLACE-THIS' .env)\" -eq 0"),
                "the stage proves no published placeholder survived before it starts anything");
        assertFalse(workflow.contains("{noop}"),
                "a plaintext identity password has no place in a pipeline");
    }

    @Test
    @DisplayName("the workflow contains no placeholder or deferred stage")
    void theWorkflowContainsNoPlaceholderOrDeferredStage() {
        assertFalse(
                Pattern.compile(
                                "(?i)\\b(?:TODO|FIXME|TBD|placeholder|stub|implement later|coming soon)\\b")
                        .matcher(workflow)
                        .find());
        assertFalse(workflow.contains("|| true"));
        assertFalse(workflow.contains("exit 0"));
    }

    private static Path locatePlatformRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("equivalence-tests"))
                    && Files.isDirectory(cursor.resolve("services"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate card-platform root");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = source.indexOf(needle, fromIndex)) >= 0) {
            count++;
            fromIndex += needle.length();
        }
        return count;
    }
}