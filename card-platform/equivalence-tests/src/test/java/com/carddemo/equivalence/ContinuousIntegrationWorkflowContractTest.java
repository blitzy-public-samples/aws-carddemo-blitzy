package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
                    "secret-scan",
                    "static-analysis",
                    "unit-tests",
                    "integration-tests",
                    "equivalence-tests",
                    "schema-compatibility",
                    "supply-chain",
                    "container-builds");

    /**
     * The three stages a security review found missing, and the tool each one runs.
     *
     * <p>The review found compilation, tests, schema compatibility and container builds, and no
     * static analysis, secret scan, advisory review, image scan, bill of materials or provenance.
     * Six controls over three new jobs and one widened one, and every one of them fails the run.
     */
    private static final Map<String, String> SECURITY_STAGE_TOOLS =
            Map.of(
                    "secret-scan", "gitleaks",
                    "static-analysis", "github/codeql-action",
                    "supply-chain", "osv-scanner");

    /**
     * Every release archive this workflow installs, with the digest it is verified against.
     *
     * <p>An action is pinned by commit. A downloaded binary cannot be, so each is pinned to a
     * version and checked against a digest its own project published, which is the shape the Maven
     * install step has always used. A tool that arrived unverified would be a hole in the pipeline
     * that checks for holes.
     */
    private static final Map<String, String> PINNED_TOOL_DIGESTS =
            Map.of(
                    "gitleaks_${version}_linux_x64.tar.gz",
                    "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb",
                    "osv-scanner_linux_amd64",
                    "edcfc41d257db36148f065055655fe3fcfc434b0b423ea67468a84c207524e0c",
                    "trivy_${version}_Linux-64bit.tar.gz",
                    "2edd39da482bb4e9831962487b68f68e3928ec3137794757f54d00383d79547b");

    /** Every artifact the workflow keeps, one name per upload. */
    private static final List<String> EXPECTED_ARTIFACTS =
            List.of(
                    "unit-test-reports",
                    "integration-test-reports",
                    "equivalence-test-reports",
                    "schema-compatibility-reports",
                    "secret-scan-reports",
                    "static-analysis-results",
                    "supply-chain-reports",
                    "image-scan-reports");

    private static final List<String> SERVICES =
            List.of(
                    "authorization-service",
                    "ledger-posting-service",
                    "fraud-detection-service",
                    "notification-service",
                    "account-service",
                    "card-service");

    /** The dependency graph the nine jobs declare through their {@code needs} keys. */
    private static final Map<String, List<String>> EXPECTED_GRAPH =
            Map.of(
                    "compile", List.of(),
                    "secret-scan", List.of("compile"),
                    "static-analysis", List.of("compile"),
                    "unit-tests", List.of("compile"),
                    "integration-tests", List.of("compile"),
                    "equivalence-tests", List.of("compile"),
                    "schema-compatibility", List.of("compile"),
                    "supply-chain", List.of("compile"),
                    "container-builds",
                            List.of(
                                    "secret-scan",
                                    "static-analysis",
                                    "unit-tests",
                                    "integration-tests",
                                    "equivalence-tests",
                                    "schema-compatibility",
                                    "supply-chain"));

    /** The runner image every job names. */
    private static final String RUNNER_IMAGE = "ubuntu-24.04";

    /**
     * A first-party action, the commit the workflow runs it at, and how many jobs use it.
     *
     * <p>A tag is a movable reference. The owner of an action can repoint {@code v7} at a new
     * commit, so a tag records which release was intended while a commit records which code
     * runs. Each {@code uses} key therefore names the commit and carries the release beside it
     * as a comment, and this list is what holds the two together.
     */
    private record PinnedAction(String name, String commit, String release, int uses) {

        /** The exact key the workflow carries, comment included. */
        String reference() {
            return "uses: " + name + "@" + commit + " # " + release;
        }
    }

    private static final List<PinnedAction> PINNED_ACTIONS =
            List.of(
                    new PinnedAction(
                            "actions/checkout",
                            "3d3c42e5aac5ba805825da76410c181273ba90b1",
                            "v7.0.1",
                            9),
                    new PinnedAction(
                            "actions/setup-java",
                            "b6effb05e454b25005698d916606bdc6ffcbf961",
                            "v5.7.0",
                            0),
                    new PinnedAction(
                            "actions/upload-artifact",
                            "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
                            "v7.0.1",
                            8),
                    new PinnedAction(
                            "github/codeql-action/init",
                            "5595ccaf912efad79be6eef63a5619ff05969be3",
                            "v4.37.6",
                            1),
                    new PinnedAction(
                            "github/codeql-action/analyze",
                            "5595ccaf912efad79be6eef63a5619ff05969be3",
                            "v4.37.6",
                            1),
                    new PinnedAction(
                            "actions/dependency-review-action",
                            "2031cfc080254a8a887f58cffee85186f0e49e48",
                            "v4.9.0",
                            1),
                    new PinnedAction(
                            "actions/attest-build-provenance",
                            "4d101475d8b20a2381f78447822ac1eab6504dd8",
                            "v4.2.2",
                            1));

    /** Reads a {@code uses} key and, when one follows, the release comment beside it. */
    private static final Pattern USES_KEY =
            Pattern.compile("(?m)^\\s*uses:\\s*(\\S+)(?:\\s+#\\s*(\\S+))?\\s*$");

    /** A {@code uses} key naming a tag rather than a commit, in any of its forms. */
    private static final Pattern MUTABLE_REFERENCE =
            Pattern.compile("(?m)^\\s*uses:\\s*\\S+@(?!\\p{XDigit}{40}\\b)\\S+");

    /**
     * The statement the shared action assembles the checksum line with, from the digest
     * Apache publishes.
     *
     * <p>{@code apache-maven-3.9.16-bin.tar.gz.sha512} holds 128 hexadecimal characters, no file
     * name and no closing newline. GNU coreutils reads a checksum line as digest, two spaces, file
     * name, so the digest alone is not a checksum file. {@code awk} takes the first field, which
     * also tolerates the two-field form other projects publish.
     */
    private static final String ARCHIVE_CHECKSUM_LINE =
            "printf '%s  %s\\n' \"$(awk '{print $1}' \"/tmp/${archive}.sha512\")\" \"${archive}\"";

    /** The verification the shared action runs over the assembled line. */
    private static final String ARCHIVE_CHECKSUM_VERIFICATION =
            "sha512sum --check --strict \"${archive}.sha512sum\"";

    /**
     * The form that reads the published file as if it were a checksum file. It exits 1 with "no
     * properly formatted checksum lines found", and neither file may carry it.
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

    /** The local composite action every job that runs Maven installs its toolchain through. */
    private static final String TOOLCHAIN_ACTION = ".github/actions/setup-build-toolchain";

    /**
     * How many jobs reach the toolchain through {@link #TOOLCHAIN_ACTION}.
     *
     * <p>Eight of the nine. The secret scan reads the working tree and the history with a
     * downloaded binary and never invokes Maven, so it installs no toolchain at all.
     */
    private static final int TOOLCHAIN_ACTION_USES = 8;

    private static Path platformRoot;
    private static String workflow;
    private static String toolchainAction;
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

        Path actionPath =
                platformRoot.getParent().resolve(TOOLCHAIN_ACTION + "/action.yml");
        assertTrue(Files.isRegularFile(actionPath),
                "every job that invokes Maven installs its toolchain through a composite action,"
                        + " which is missing");
        toolchainAction = Files.readString(actionPath);
    }

    @Test
    @DisplayName("the workflow is valid YAML with the nine required jobs")
    void theWorkflowIsValidYamlWithTheNineRequiredJobs() {
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

        // Every job that invokes Maven reaches the toolchain through the shared action, and
        // passes both versions from the workflow environment so the two pins stay in one place.
        assertEquals(
                TOOLCHAIN_ACTION_USES, occurrences(workflow, "uses: ./" + TOOLCHAIN_ACTION));
        assertEquals(
                TOOLCHAIN_ACTION_USES,
                occurrences(workflow, "java-version: ${{ env.JAVA_VERSION }}"));
        assertEquals(
                TOOLCHAIN_ACTION_USES,
                occurrences(workflow, "maven-version: ${{ env.MAVEN_VERSION }}"));

        // The installation itself exists once. It was written out in every job that needed it,
        // twenty-one identical lines each, and a checksum step corrected in one copy and not the
        // others is the failure that shape invites. None of it may return to the workflow.
        assertEquals(0, occurrences(workflow, pinnedAction("actions/setup-java").reference()),
                "the Java install belongs to the shared action, not to a job");
        assertEquals(0, occurrences(workflow, "archive.apache.org/dist/maven/maven-3/"),
                "the Maven install belongs to the shared action, not to a job");
        assertEquals(1, occurrences(toolchainAction, pinnedAction("actions/setup-java").reference()));
        assertEquals(1, occurrences(toolchainAction, "distribution: temurin"));
        assertEquals(1,
                occurrences(toolchainAction, "cache-dependency-path: card-platform/**/pom.xml"));
        assertEquals(1, occurrences(toolchainAction, "archive.apache.org/dist/maven/maven-3/"));
        assertEquals(1, occurrences(toolchainAction, ARCHIVE_CHECKSUM_LINE));
        assertEquals(1, occurrences(toolchainAction, ARCHIVE_CHECKSUM_VERIFICATION));
        assertTrue(toolchainAction.contains("using: composite"),
                "a shared action a job calls with uses has to declare the composite runner");
        assertFalse(toolchainAction.contains(BARE_DIGEST_VERIFICATION),
                "the published file holds a digest with no file name, which GNU coreutils"
                        + " refuses; verifying it directly fails every job at its second step");
        assertFalse(workflow.contains(BARE_DIGEST_VERIFICATION),
                "no job may verify the published digest as if it were a checksum file");
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

    /**
     * Holds every action to an immutable reference and every failure to a fatal one.
     *
     * <p>A security review found six {@code actions/checkout@v4}, six
     * {@code actions/setup-java@v4} and four {@code actions/upload-artifact@v4}, and this test
     * required exactly that form. A major-version tag is a moving reference: whoever controls the
     * action repository can point {@code v4} at other code, and every run afterwards executes it.
     * The remedy is the same one the manifests under {@code deploy/k8s} use for an upstream image:
     * name the bytes, not a label that can be moved over them. A full commit identifier names the
     * bytes; the trailing release comment keeps the reference readable.
     */
    @Test
    @DisplayName("every action runs at a named commit, and no job weakens a failure")
    void actionsArePinnedToACommitAndFailuresRemainFatal() {
        Map<String, String> expectedRelease = new LinkedHashMap<>();
        PINNED_ACTIONS.forEach(pin -> expectedRelease.put(pin.name(), pin.release()));

        Matcher key = USES_KEY.matcher(workflow);
        int actionCount = 0;
        while (key.find()) {
            actionCount++;
            String reference = key.group(1);
            if (reference.equals("./" + TOOLCHAIN_ACTION)) {
                continue;
            }
            String[] parts = reference.split("@", 2);
            assertEquals(2, parts.length, "A uses key names no revision: " + reference);
            String release = expectedRelease.get(parts[0]);
            assertNotNull(release, "Unexpected action: " + parts[0]);
            assertTrue(
                    parts[1].matches("\\p{XDigit}{40}"),
                    "An action has to run at a full commit, not at a movable tag: " + reference);
            assertEquals(
                    release,
                    key.group(2),
                    "The comment beside " + parts[0] + " has to name the release that commit"
                            + " carries, so the pin can be read without a network call");
        }
        assertEquals(
                PINNED_ACTIONS.stream().mapToInt(PinnedAction::uses).sum() + TOOLCHAIN_ACTION_USES,
                actionCount,
                "every job checks out the repository, every job that runs Maven installs the"
                        + " toolchain, and the eight report uploads, the two analysis actions,"
                        + " the dependency review and the provenance attestation are the rest");

        PINNED_ACTIONS.forEach(
                pin ->
                        assertEquals(
                                pin.uses(),
                                occurrences(workflow, pin.reference()),
                                "Wrong number of uses of " + pin.name() + " at its pinned commit"));

        Matcher mutable = MUTABLE_REFERENCE.matcher(workflow);
        assertFalse(
                mutable.find(),
                "A tag can be repointed at a different commit by the action's owner, so a run"
                        + " that reads one is not reproducible: "
                        + (mutable.reset().find() ? mutable.group() : ""));
        assertFalse(workflow.contains("@main"));
        assertFalse(workflow.contains("@master"));
        assertFalse(workflow.contains("@latest"));
        assertFalse(workflow.contains("@v4\n"), "a moving major-version tag is not a pin");
        assertFalse(workflow.contains("continue-on-error"));
        assertFalse(workflow.contains("mvn install"));
    }

    /**
     * Holds the six controls the review found missing, each one able to fail the run.
     *
     * <p>The review's words were that the workflow "has compilation, tests, schema compatibility,
     * and container builds, but no SAST, secret scanning, dependency/advisory review, container
     * scanning, SBOM, signing, or provenance/attestation". Each clause is an assertion here.
     */
    @Test
    @DisplayName("every security stage is present, runs a pinned tool and can fail the run")
    void everySecurityStageIsPresentAndFailClosed() {
        SECURITY_STAGE_TOOLS.forEach((job, tool) -> {
            assertInstanceOf(Map.class, jobs.get(job), "missing stage: " + job);
            assertTrue(workflow.contains(tool),
                    "stage " + job + " has to run " + tool);
        });

        assertTrue(workflow.contains("gitleaks dir . --config card-platform/.gitleaks.toml"),
                "the working tree is scanned against the allowlist that names the known-safe"
                        + " values this repository ships, which sits under card-platform/ so the"
                        + " scope of this engagement stays mechanically auditable");
        assertTrue(workflow.contains("gitleaks git . --config card-platform/.gitleaks.toml"),
                "and so is the history, because a secret removed in a later commit is still"
                        + " published");
        assertTrue(Files.isRegularFile(platformRoot.resolve(".gitleaks.toml")),
                "the allowlist the two scans name has to exist at that path");
        assertFalse(Files.exists(platformRoot.getParent().resolve(".gitleaks.toml")),
                "a second copy at the repository root would sit outside the two directories this"
                        + " engagement adds to");
        assertEquals(2, occurrences(workflow, "--exit-code 1"),
                "both scans fail the run on a finding");

        assertTrue(workflow.contains("build-mode: manual"),
                "the analysis database is built from a real compile rather than inferred");
        assertTrue(workflow.contains("test \"${severe}\" -eq 0"),
                "a high or critical result fails the run");
        assertTrue(workflow.contains("upload: never"),
                "the result file is read here, so the workflow needs no code-scanning write"
                        + " scope");

        assertTrue(workflow.contains("cyclonedx-maven-plugin:2.9.3:makeAggregateBom"),
                "a bill of materials describes what the build resolves");
        assertTrue(workflow.contains("-DincludeTestScope=true"),
                "the finding this stage answers was on a test classpath, so test scope is"
                        + " described too");
        assertTrue(workflow.contains("osv-scanner scan source -L target/bom.json"),
                "and the description is read for known advisories");
        assertTrue(workflow.contains("fail-on-severity: low"),
                "the pull-request review refuses a change at any severity");

        assertTrue(workflow.contains("test \"${fixable_total}\" -eq 0"),
                "a high or critical image vulnerability with a fix available fails the run");
        assertTrue(workflow.contains("--severity HIGH,CRITICAL"),
                "and the scan is scoped to those two severities");
        assertTrue(workflow.contains("attest-build-provenance"),
                "a push records what produced the six archives");
        assertTrue(workflow.contains("subject-path: card-platform/services/*/target/*.jar"),
                "and names the archives it attests");
        assertFalse(workflow.contains("permissions: write"),
                "the attestation takes the two scopes it needs and nothing wider");
    }

    /**
     * Holds every downloaded tool to a version and a digest published by its own project.
     *
     * <p>An action is pinned by commit and a release archive cannot be, so each carries the digest
     * its project published. Without this, the stages that check the supply chain would themselves
     * arrive from an unverified download.
     */
    @Test
    @DisplayName("every downloaded tool is verified against a published digest")
    void everyDownloadedToolIsVerifiedAgainstAPublishedDigest() {
        PINNED_TOOL_DIGESTS.forEach((archive, digest) -> {
            assertTrue(workflow.contains(archive),
                    "the workflow no longer installs " + archive);
            assertTrue(workflow.contains("digest=\"" + digest + "\""),
                    archive + " has to be checked against the digest its project published");
        });
        assertEquals(3, occurrences(workflow, "sha256sum --check --strict"),
                "one verification per downloaded tool");
        assertFalse(workflow.contains("curl --fail --location --silent --show-error \\\n"
                        + "            \"https://github.com/gitleaks/gitleaks/releases/latest"),
                "a latest release is a moving reference");
    }

    @Test
    @DisplayName("every job names one runner image rather than the moving label")
    void everyJobNamesThePinnedRunnerImage() {
        assertEquals(
                EXPECTED_JOBS.size(),
                occurrences(workflow, "runs-on: " + RUNNER_IMAGE),
                "each of the nine jobs names the runner image it was verified against");
        assertFalse(
                workflow.contains("ubuntu-latest"),
                "the label moves to the next Ubuntu release on GitHub's own schedule, which"
                        + " changes the toolchain under a build that pins its Java and its Maven");
        jobs.forEach(
                (name, body) ->
                        assertEquals(
                                RUNNER_IMAGE,
                                assertInstanceOf(Map.class, body).get("runs-on"),
                                "Job " + name + " runs on the wrong image"));
    }

    @Test
    @DisplayName("the header describes the dependency graph the jobs declare")
    void theHeaderDescribesTheDependencyGraphTheJobsDeclare() {
        Map<String, List<String>> declared = new LinkedHashMap<>();
        jobs.forEach((name, body) -> declared.put(String.valueOf(name), dependenciesOf(body)));
        assertEquals(
                EXPECTED_GRAPH,
                declared,
                "the graph the header draws is only true while these keys are");

        assertFalse(
                workflow.contains("stages run in order"),
                "seven of the nine jobs depend on the compile stage alone, so the runner starts"
                        + " them together and the header may not describe a straight line");
        assertTrue(
                workflow.contains("the runner starts all\n# seven together"),
                "the header has to say the seven quality gates run at the same time");
        assertTrue(
                workflow.contains("#   compile --+--> secret-scan ----------+--> container-builds"),
                "the header carries the graph as a drawing, so a reader sees the fan-out");
        for (String gate :
                List.of(
                        "secret-scan",
                        "static-analysis",
                        "unit-tests",
                        "integration-tests",
                        "equivalence-tests",
                        "schema-compatibility",
                        "supply-chain")) {
            assertTrue(
                    workflow.contains("+--> " + gate),
                    "the drawn graph omits the gate " + gate);
        }
    }

    @Test
    @DisplayName("the compile stage parses the three committed shell procedures")
    void theCompileStageParsesTheCommittedShellProcedures() {
        assertTrue(workflow.contains("bash -n \"${script}\""),
                "no stage runs scripts/start-demo.sh or deploy/k8s/load-images.sh, so parsing them"
                        + " is what stops a syntax error reaching a reader following the guide");
        assertTrue(workflow.contains("test -x \"${script}\""),
                "a script without the executable bit fails the documented command with Permission"
                        + " denied");
        for (String script :
                List.of(
                        "scripts/start-demo.sh",
                        "scripts/generate-env.sh",
                        "deploy/k8s/load-images.sh")) {
            assertTrue(workflow.contains(script), "the check omits " + script);
        }
    }

    @Test
    @DisplayName("compile and unit stages cover the complete reactor")
    void compileAndUnitStagesCoverTheCompleteReactor() {
        assertTrue(workflow.contains("mvn -B -ntp -DskipTests compile"));
        assertTrue(workflow.contains("mvn -B -ntp -pl equivalence-tests -am test"));
        assertEquals(7, occurrences(workflow, "needs: compile"),
                "the secret scan, the analysis, the unit stage, the integration stage, the"
                        + " equivalence stage, the schema stage and the dependency review each"
                        + " start once the reactor compiles");
    }

    @Test
    @DisplayName("integration and equivalence stages start and always remove their infrastructure")
    void integrationAndEquivalenceStagesManageTheirInfrastructure() {
        assertEquals(3, occurrences(workflow, "run: cp .env.example .env"));
        assertEquals(2, occurrences(workflow, "docker compose up --detach --wait postgres kafka"));
        assertEquals(3, occurrences(workflow, "docker compose down --volumes"),
                "the integration, equivalence and container stages each remove their own stack");
        assertEquals(12, occurrences(workflow, "if: always()"),
                "the eight report uploads, the container-state report and the three teardowns run"
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
                        "secret-scan",
                        "static-analysis",
                        "unit-tests",
                        "integration-tests",
                        "equivalence-tests",
                        "schema-compatibility",
                        "supply-chain"),
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
        assertEquals(EXPECTED_ARTIFACTS.size(),

                occurrences(workflow, pinnedAction("actions/upload-artifact").reference()),

                "the unit, integration, equivalence and schema stages write test reports and the"

                        + " secret scan, the analysis, the dependency review and the image scan"

                        + " write findings; a run that fails has to leave every one of them"

                        + " downloadable");

        assertEquals(EXPECTED_ARTIFACTS.size(), occurrences(workflow, "if-no-files-found: error"),

                "an upload that finds nothing means the stage did not run, which is a failure"

                        + " rather than an empty artifact");

        assertEquals(EXPECTED_ARTIFACTS.size(), occurrences(workflow,

                        "retention-days: ${{ env.REPORT_RETENTION_DAYS }}"),
                "one retention period governs every upload");
        assertTrue(workflow.contains("REPORT_RETENTION_DAYS: \"14\""));
        for (String artifact : EXPECTED_ARTIFACTS) {
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

    /**
     * Holds that the container stage reads the headline acceptance path rather than only health.
     *
     * <p>Six containers reporting UP says the images start. It does not say the platform still does
     * the one thing it exists to do, so the authorization path could stop publishing, or a consumer
     * could stop subscribing, while this job stayed green. The step asserted here closes that gap: it
     * authenticates one synthetic authorization, reads the identifier of the one event it produced,
     * and requires all three consumers to have recorded that same identifier.
     *
     * <p>Each claim below names a property the step would lose if it were trimmed rather than the
     * exact wording of a line, except where the wording <em>is</em> the property: polling for the one
     * {@code event_id} in each consumer is what makes this a fan-out check rather than three
     * unrelated liveness checks.
     */
    @Test
    @DisplayName("the container stage follows one authorization through all three consumers")
    void theContainerStageFollowsOneAuthorizationThroughAllThreeConsumers() {
        List<?> steps = (List<?>) ((Map<?, ?>) jobs.get("container-builds")).get("steps");
        Map<?, ?> smoke = steps.stream()
                .map(step -> (Map<?, ?>) step)
                .filter(step -> String.valueOf(step.get("name"))
                        .contains("Follow one authenticated authorization"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the container stage carries no business"
                        + " smoke, so six healthy containers are all this job establishes"));
        String script = String.valueOf(smoke.get("run"));

        int healthCheck = indexOfStepNamed(steps, "Check that every service answers");
        int businessSmoke = indexOfStepNamed(steps, "Follow one authenticated authorization");
        assertTrue(healthCheck < businessSmoke,
                "the business smoke runs after the health checks, because it needs the services up");

        assertTrue(script.contains("/authorizations"),
                "the smoke calls the one synchronous entry point of the platform");
        assertTrue(script.contains("SMOKE_ADMIN_PASSWORD"),
                "the call is authenticated, so it exercises the filter chain as a caller would");
        assertTrue(script.contains("'\"approved\":true'"),
                "a declined answer is not a passing smoke");
        assertTrue(script.contains("transactionId"),
                "the identifier the service assigned is read back out of the answer");
        assertTrue(script.contains("TransactionAuthorized"),
                "the emitted event is located by its type");
        assertTrue(script.contains("event_id"),
                "the one event identifier is captured, which is what the three readings compare");
        // Four schemas are read: the producer's outbox, then one consumer schema each. Each is
        // named once, so a reading pointed at the wrong service's schema fails here.
        for (String schema : List.of("AUTHORIZATION_DB_SCHEMA", "LEDGER_DB_SCHEMA",
                "FRAUD_DB_SCHEMA", "NOTIFICATION_DB_SCHEMA")) {
            assertEquals(1, occurrences(script, schema),
                    schema + " is read exactly once, in the service that owns it");
        }
        assertEquals(4, occurrences(script, "_DB_SCHEMA:-"),
                "the producer's outbox and the three consumer schemas, and no other");
        for (String consumer : List.of("ledger-posting-service", "fraud-detection-service",
                "notification-service")) {
            assertTrue(script.contains(consumer),
                    consumer + " is one of the three consumers the smoke follows");
        }
        assertTrue(script.contains("consumed_topic = 'transaction.authorized'"),
                "each consumer is required to have recorded the authorization event itself, rather"
                        + " than any event that happened to arrive");
        assertTrue(script.contains("::error::"),
                "a failure names what went wrong rather than only exiting");
        assertTrue(script.contains("docker compose logs --tail 50"),
                "a failure carries bounded diagnostics rather than a whole log or none");
        assertTrue(script.contains("::add-mask::"),
                "the credentials this step reads are masked before anything else runs");
        assertTrue(script.contains("for attempt in $(seq 1 30)"),
                "every wait is bounded, so a consumer that never reads cannot hang the job");
    }

    /**
     * Returns the position of the first step whose name carries {@code fragment}.
     *
     * @param steps    the steps of one job
     * @param fragment the fragment to find
     * @return the position
     */
    private static int indexOfStepNamed(List<?> steps, String fragment) {
        for (int position = 0; position < steps.size(); position++) {
            if (String.valueOf(((Map<?, ?>) steps.get(position)).get("name")).contains(fragment)) {
                return position;
            }
        }
        throw new AssertionError("no step of the container stage is named " + fragment);
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

    /**
     * The integration count the decision log publishes is the count the tree holds.
     *
     * <p>That row said fifteen while the six service modules carried 23, which is the failure mode
     * of a count written into prose: it is right the day it is typed and silently wrong afterwards.
     * The row now argues its point with a figure this test derives, so the next integration class
     * added fails the build here rather than making a published number wrong.
     *
     * <p>The stage itself is left alone deliberately. It selects by Failsafe's naming pattern, so
     * nothing about the workflow depends on the number, and that separation is the row's actual
     * argument: the count is evidence for why the stage exists, not an input to it.
     */
    @Test
    @DisplayName("the published integration-class count matches the delivered service modules")
    void thePublishedIntegrationClassCountMatchesTheServiceModules() throws IOException {
        int delivered = 0;
        for (String service : SERVICES) {
            Path tests = platformRoot.resolve("services").resolve(service).resolve("src/test/java");
            try (var walk = Files.walk(tests)) {
                delivered += (int) walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("IT.java"))
                        .count();
            }
        }

        assertTrue(delivered > 0, "the service modules must carry integration classes at all");
        String decisionLog = Files.readString(platformRoot.resolve("docs/decision-log.md"));
        assertTrue(decisionLog.contains("— " + delivered + " of them today,"),
                "the decision log has to state the " + delivered + " integration classes the six"
                        + " service modules carry, and no other figure");
        assertFalse(decisionLog.contains("there are 15 of them today"),
                "the superseded count may not survive");

        // The stage selects by pattern, so the count is evidence rather than configuration.
        assertTrue(workflow.contains("-pl \"${modules}\" -am verify"),
                "the integration stage runs the six service modules under Failsafe by pattern");
    }

    /** Answers with the pin recorded for one action, failing when the name is unknown. */
    private static PinnedAction pinnedAction(String name) {
        return PINNED_ACTIONS.stream()
                .filter(pin -> pin.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No pin recorded for " + name));
    }

    /**
     * Reads one job's {@code needs} key as a list.
     *
     * <p>The key takes a single job name or a sequence of them, and a job that depends on
     * nothing omits it, so all three shapes answer with a list here.
     */
    private static List<String> dependenciesOf(Object jobBody) {
        Object needs = assertInstanceOf(Map.class, jobBody).get("needs");
        if (needs == null) {
            return List.of();
        }
        if (needs instanceof List<?> sequence) {
            return sequence.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(needs));
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