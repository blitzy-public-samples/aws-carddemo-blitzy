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
        assertEquals(6, occurrences(workflow, "sha512sum --check"));
        assertTrue(workflow.contains("major version: 69"));
    }

    @Test
    @DisplayName("actions are versioned and no job weakens a failure")
    void actionsAreVersionedAndFailuresRemainFatal() {
        Matcher action = Pattern.compile("(?m)^\\s*uses:\\s*(\\S+)").matcher(workflow);
        int actionCount = 0;
        while (action.find()) {
            actionCount++;
            assertTrue(
                    action.group(1).matches("actions/(?:checkout|setup-java)@v4"),
                    "Unexpected or unversioned action: " + action.group(1));
        }
        assertEquals(12, actionCount);
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
        assertEquals(2, occurrences(workflow, "docker compose down --volumes"));
        assertEquals(2, occurrences(workflow, "if: always()"));
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