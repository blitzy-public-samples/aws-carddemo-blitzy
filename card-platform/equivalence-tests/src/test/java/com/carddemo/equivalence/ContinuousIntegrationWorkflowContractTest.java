package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.carddemo.authorization.config.CrossSiteRequestFilter;

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
                    "container-builds",
                    "provenance");

    /**
     * The three security stages, and the tool each one runs.
     *
     * <p>Compilation, tests, schema compatibility and container builds say nothing about static
     * analysis, committed credentials, published advisories, image contents, a bill of materials or
     * provenance. Six controls sit across these three jobs and one widened one, and every one of
     * them fails the run.
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

    /**
     * Every artifact the workflow keeps, one name per upload.
     *
     * <p>One artifact holding both report kinds cannot answer whether any integration test ran: the
     * Surefire files satisfy {@code if-no-files-found} on their own, so a run that executed no
     * integration test would still produce an artifact and the reviewer of that run would see a
     * green stage with a populated download. Two properties of the integration stage close that:
     * its artifact path names {@code failsafe-reports} and nothing else, and the stage passes
     * {@code -DskipUnitTests=true} so no Surefire report exists there to be mistaken for one. The
     * stage's own case-count gate is the stronger control - it reads the written reports and fails
     * the run when a module holding an {@code *IT} class executed no case at all.
     */
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
                                    "supply-chain"),
                    "provenance", List.of("container-builds"));

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
                            10),
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
                            "a1d282b36b6f3519aa1f3fc636f609c47dddb294",
                            "v5.0.0",
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

    /** The configuration the static-analysis stage hands to the analysis it runs. */
    private static final String CODEQL_CONFIG = ".github/codeql/codeql-config.yml";

    /**
     * The header every service requires of a state-changing request.
     *
     * <p>It is what a browser form cannot add, so its presence is what separates a deliberate
     * client from a forged submission. A request without it is answered 403 before any rule runs.
     */
    private static final String CROSS_SITE_HEADER = "X-CardDemo-Request";

    /**
     * The one query of the analysis suite whose result this platform accepts.
     *
     * <p>It reports the two stateless filter chains in each of the six services, which hold no
     * session and read no cookie, so the framework's forgery token has nothing to protect there.
     * {@code config/CrossSiteRequestFilter} is the control that stands in its place. The record of
     * the choice is in {@code card-platform/docs/decision-log.md}.
     */
    private static final String ACCEPTED_CODEQL_QUERY = "java/spring-disabled-csrf-protection";

    /**
     * How many jobs reach the toolchain through {@link #TOOLCHAIN_ACTION}.
     *
     * <p>Nine of the ten. The secret scan reads the working tree and the history with a
     * downloaded binary and never invokes Maven, so it installs no toolchain at all.
     */
    private static final int TOOLCHAIN_ACTION_USES = 9;

    /**
     * Findings the reviewed secret baseline records, measured with gitleaks 8.30.1 over every ref.
     *
     * <p>None sits on a commit this branch can reach, and none inside the range this engagement
     * authored. The triage, finding by finding, is in card-platform/docs/decision-log.md.
     */
    private static final int BASELINE_FINDINGS = 113;

    /** The reviewed baseline the scheduled history scan reads. */
    private static final String BASELINE_PATH = "card-platform/.gitleaks-baseline.json";

    /** The base a push carries for the first push of a new branch. */
    private static final String EMPTY_TREE_BASE = "0000000000000000000000000000000000000000";

    /**
     * Exceptions the image scan carries for the two pinned infrastructure images.
     *
     * <p>Thirteen in {@code apache/kafka:4.2.1} and fifteen in {@code postgres:18.4}, each one a
     * package inside an image this project consumes rather than one it builds.
     */
    private static final int IMAGE_EXCEPTIONS = 28;

    /** The dated exceptions the infrastructure image scan runs under. */
    private static final String EXCEPTION_FILE = ".trivyignore.yaml";

    /** Where every rationale this platform ships points. */
    private static final String DECISION_LOG = "card-platform/docs/decision-log.md";

    private static Path platformRoot;
    /**
     * A {@code curl} call that changes state, and is therefore tested by the request filter.
     *
     * <p>A body or an unsafe method is what makes it unsafe. {@code --fail} and the reads around it
     * are {@code GET} calls the filter lets through untouched.
     */
    private static final Pattern STATE_CHANGING_CURL = Pattern.compile(
            "--data\\b|--data-raw\\b|--data-binary\\b|--upload-file\\b|\\s-d\\s|"
                    + "(?:-X|--request)\\s+\"?(?:POST|PUT|PATCH|DELETE)");

    /** The variable every container reads the required header name from. */
    private static final String CROSS_SITE_HEADER_VARIABLE = "API_CROSS_SITE_HEADER";

    /** Fewest curl calls the scan must find before its verdict means anything. */
    private static final int CURL_FLOOR = 4;

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
        // The exact Temurin build, not the release line. "25" resolved to whichever patch
        // Adoptium had published that morning, so the toolchain moved under a build that pins
        // everything else and onboarding named a version this workflow did not install.
        assertTrue(workflow.contains("JAVA_VERSION: \"25.0.4+7\""),
                "the workflow has to install the exact Temurin build the platform is exercised"
                        + " against, which is the one card-platform/docs/onboarding.md names");
        assertFalse(workflow.contains("JAVA_VERSION: \"25\""),
                "a release line floats to a later patch on Adoptium's schedule");
        assertTrue(workflow.contains("MAVEN_VERSION: \"3.9.16\""));
        // Both pins are read back out of the installed toolchain, so an installer that
        // answered with another build fails the compile stage by name.
        assertTrue(workflow.contains("java -version 2>&1 | grep -F -- \"${JAVA_VERSION}\""),
                "the resolved runtime has to be compared against the pin");
        assertTrue(workflow.contains("mvn -v | grep -F -- \"Apache Maven ${MAVEN_VERSION}\""),
                "and so does the resolved build tool");

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
     * <p>A major-version tag such as {@code actions/checkout@v4}, {@code actions/setup-java@v4} or
     * {@code actions/upload-artifact@v4} is a moving reference: whoever controls the action
     * repository can point {@code v4} at other code, and every run afterwards executes it. The
     * remedy is the same one the manifests under {@code deploy/k8s} use for an upstream image: name
     * the bytes, not a label that can be moved over them. A full commit identifier names the bytes;
     * the trailing release comment keeps the reference readable.
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
     * Holds the six security controls, each one able to fail the run.
     *
     * <p>Compilation, tests, schema compatibility and container builds leave six controls
     * unchecked: static analysis of the sources, a scan for committed credentials, a review of
     * dependencies against published advisories, a scan of the images that were built, a bill of
     * materials, and provenance for the archives. Each of the six is an assertion here.
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
        assertEquals(6, occurrences(workflow, "--exit-code 1"),
                "every scan fails the run on a finding, and every canary reads the same signal to"
                        + " prove the scanner still produces it: the working tree, the range under"
                        + " review, the scheduled whole history, the baseline canary and the two"
                        + " planted-secret canaries");

        assertTrue(workflow.contains("build-mode: manual"),
                "the analysis database is built from a real compile rather than inferred");
        assertTrue(workflow.contains("test \"${severe}\" -eq 0"),
                "a high or critical result fails the run");
        assertTrue(workflow.contains("upload: never"),
                "the result file is read here, so the workflow needs no code-scanning write"
                        + " scope");
        assertTrue(workflow.contains("config-file: " + CODEQL_CONFIG),
                "the analysis reads the configuration that carries the one finding this platform"
                        + " accepts, so the acceptance is a file a reviewer can read rather than a"
                        + " threshold nobody can see");
        Path codeqlConfig = platformRoot.getParent().resolve(CODEQL_CONFIG);
        assertTrue(Files.isRegularFile(codeqlConfig),
                "the configuration the init step names has to exist at " + CODEQL_CONFIG);
        String codeql = readFile(codeqlConfig);
        assertTrue(codeql.contains("query-filters:") && codeql.contains("- exclude:"),
                "the acceptance is stated as a query filter, which leaves every other query and"
                        + " the severity threshold exactly as they were");
        assertTrue(codeql.contains("id: " + ACCEPTED_CODEQL_QUERY),
                "and the query it excludes is " + ACCEPTED_CODEQL_QUERY);
        assertEquals(1, occurrences(codeql, "- exclude:"),
                "one accepted finding, so one exclusion. A second needs its own row in the"
                        + " decision log before it is added here");
        assertTrue(codeql.contains("CrossSiteRequestFilter"),
                "and the file names the control that answers the query, because an exclusion"
                        + " without a compensating control is an unprotected chain");
        assertTrue(readFile(platformRoot.resolve("docs/decision-log.md"))
                        .contains(ACCEPTED_CODEQL_QUERY),
                "Rule 1 keeps rationale out of the workflow, so the acceptance is recorded in"
                        + " docs/decision-log.md with what it was chosen over");
        assertTrue(workflow.contains("select(((.suppressions // []) | length) == 0)"),
                "a result the analysis reports as suppressed is not counted, so an alert"
                        + " dismissed at its source cannot fail a run it was dismissed in");

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
     * Holds the secret gate to a proof rather than to a passing scan.
     *
     * <p><b>The defect this stands over.</b> {@code card-platform/.gitleaks.toml} carried an
     * allowlist naming {@code deploy/k8s/31-secret.example.yaml} by path and nothing else, so every
     * finding in the one file this repository fills with credentials was dropped before any detector
     * ran. A real private key or password committed there was reported as clean, and this stage
     * stayed green. A second allowlist excused any 64-character lowercase hexadecimal value
     * anywhere, which is the shape of a card token and equally the shape of an unrelated key.
     *
     * <p><b>An assertion on the configuration is not enough.</b> A narrower allowlist can still
     * be too wide in a way no reading of the file reveals: measured on gitleaks 8.30.1, a
     * {@code condition = "AND"} beside a {@code paths} key does not narrow that path at all, and a
     * file that reads as scoped behaves as unscoped. The workflow therefore plants a secret and
     * requires the scanner to find it, which is the only claim that cannot be wrong on paper.
     *
     * <p>Two plants run separately, so a scanner that catches one and misses the other fails: a
     * private key inside the Secret template, and an unrelated hexadecimal key beside it. Both are
     * removed and the tree is checked back to what git holds.
     */
    @Test
    @DisplayName("the secret stage plants two secrets and requires the scanner to catch each one")
    void theSecretStageProvesTheGateStillFires() throws IOException {
        List<?> steps = (List<?>) ((Map<?, ?>) jobs.get("secret-scan")).get("steps");
        Map<?, ?> canary = steps.stream()
                .map(step -> (Map<?, ?>) step)
                .filter(step -> String.valueOf(step.get("name")).contains("Prove the secret gate"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the secret stage carries no canary, so a"
                        + " clean report is all it establishes"));
        String script = String.valueOf(canary.get("run"));

        // The armour markers are assembled rather than written out: a literal BEGIN marker in the
        // workflow is itself a finding, and a canary that fails the stage it protects is no canary.
        assertTrue(script.contains("-----BEGIN %s PRIVATE KEY-----"),
                "one plant has to be a credential inside the Secret template, which is the file the"
                        + " withdrawn path exception used to excuse in full");
        assertFalse(workflow.contains("-----BEGIN RSA PRIVATE KEY-----"),
                "the workflow is inside the scan, so a literal armour marker here fails the stage"
                        + " that carries it");
        assertTrue(script.contains("openssl rand -hex 32"),
                "and the other an unrelated hexadecimal key, which is the shape the withdrawn"
                        + " 64-character exception used to excuse anywhere");
        assertTrue(script.contains("for planted in"),
                "the two cases run separately, so catching one and missing the other cannot pass");
        assertTrue(script.contains("--exit-code 1"),
                "the canary reads the scanner's own failure signal");
        assertTrue(script.contains("::error::the secret scan reported clean with a planted"),
                "a scan that finds neither plant has to name what it missed");
        assertTrue(script.contains("git checkout -- \"${template}\""),
                "the plant is removed rather than left for the rest of the run");
        assertTrue(script.contains("git diff --quiet -- \"${template}\""),
                "and the removal is proved rather than assumed");

        String allowlist = Files.readString(platformRoot.resolve(".gitleaks.toml"));
        assertFalse(allowlist.contains("paths = "),
                "an allowlist naming a path excuses every finding in it before a detector runs;"
                        + " every entry has to name the shape of the value it excuses");
        assertTrue(allowlist.contains("NO ENTRY NAMES A PATH"),
                "the file's own header describes the mechanism a reader trusts instead of reading"
                        + " every entry, so it has to say that no entry names a path rather than"
                        + " describing the narrowed path exception this file no longer carries");
        assertFalse(allowlist.contains("regexes = ['''\\b[0-9a-f]{64}\\b''']"),
                "a bare 64-character hexadecimal exception hides every secret of that shape, not"
                        + " only the card tokens it was written for");
        assertTrue(allowlist.contains("REPLACE-WITH-A-GENERATED-")
                        && allowlist.contains("REPLACE-THIS-PLACEHOLDER-WITH-"),
                "the placeholder values this repository publishes are excused by their own"
                        + " wording, which is what a real credential in the same field does not"
                        + " carry");
        assertTrue(allowlist.contains("CARD_TOKEN"),
                "and a card token is excused only where the value is named as one");
        assertTrue(allowlist.contains("[[rules.allowlists]]")
                        && !allowlist.contains("\n[[allowlists]]"),
                "every exemption sits under the one rule that reports the value: a top-level"
                        + " entry applies to every rule at once, so a GitHub token or a private key"
                        + " beside the exempt value would be excused with it");
    }

    /**
     * Holds every write permission to the one job that runs on a push alone.
     *
     * <p><b>The defect this stands over.</b> The image build took {@code id-token: write} and
     * {@code attestations: write} so that its last step could attest the six archives. That job runs
     * on a pull request, and the steps before the attestation execute what the request supplies: the
     * local composite action, the Maven descriptors, the sources, the Compose file and the six
     * Dockerfiles. Two write scopes were therefore held while proposed content ran.
     *
     * <p>The remedy is the event rather than the ordering of steps. Provenance is a job of its own
     * that runs on a push, where the content is what was merged, and it is the only job in the
     * workflow that holds a write scope. Its checkout keeps no credential in the local git
     * configuration, so no later step can reach the token through it.
     */
    @Test
    @DisplayName("only the push-only provenance job holds a write permission")
    void onlyThePushOnlyProvenanceJobHoldsAWritePermission() {
        jobs.forEach((name, body) -> {
            Object declared = assertInstanceOf(Map.class, body).get("permissions");
            if (declared == null) {
                return;
            }
            Map<?, ?> permissions = assertInstanceOf(Map.class, declared);
            permissions.forEach((scope, level) -> assertTrue(
                    "provenance".equals(name) || !"write".equals(level),
                    "job " + name + " holds " + scope + ": write, and every job but provenance runs"
                            + " on a pull request, where the content it executes is proposed from"
                            + " outside"));
        });

        Map<?, ?> containerBuilds = assertInstanceOf(Map.class, jobs.get("container-builds"));
        assertEquals(Map.of("contents", "read"), containerBuilds.get("permissions"),
                "the image build runs on a pull request, so read is the whole of what it may hold");

        Map<?, ?> provenance = assertInstanceOf(Map.class, jobs.get("provenance"));
        assertEquals(
                Map.of("contents", "read", "id-token", "write", "attestations", "write"),
                provenance.get("permissions"),
                "the attestation needs a token to identify the run and the scope that records the"
                        + " statement, and nothing wider");
        assertEquals("github.event_name == 'push'", provenance.get("if"),
                "a pull request may not reach the job that holds the two write scopes");
        assertTrue(workflow.contains("persist-credentials: false"),
                "the privileged checkout leaves no credential in the local git configuration");

        List<?> steps = (List<?>) provenance.get("steps");
        Map<?, ?> checkout = (Map<?, ?>) steps.getFirst();
        assertEquals(Map.of("persist-credentials", false), checkout.get("with"),
                "and that checkout is the one this job performs");
        assertTrue(steps.stream()
                        .map(step -> String.valueOf(((Map<?, ?>) step).get("uses")))
                        .anyMatch(uses -> uses.startsWith("actions/attest-build-provenance@")),
                "the attestation runs here and in no other job");
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
        assertEquals(2, occurrences(workflow, "mvn -B -ntp -DskipTests compile"),
                "the compile stage builds the reactor once, and the analysis stage builds it again"
                        + " because its scanner is configured to observe a build it drives itself;"
                        + " a third would be a reactor nothing reads");
        assertTrue(workflow.contains("mvn -B -ntp -pl equivalence-tests -am test"));
        assertEquals(7, occurrences(workflow, "needs: compile"),
                "the secret scan, the analysis, the unit stage, the integration stage, the"
                        + " equivalence stage, the schema stage and the dependency review each"
                        + " start once the reactor compiles");
    }

    @Test
    @DisplayName("one stage owns the Compose stack and always removes it")
    void integrationAndEquivalenceStagesManageTheirInfrastructure() {
        assertEquals(1, occurrences(workflow, "run: install -m 600 .env.example .env"),
                "only the container stage reads docker-compose.yml, so only it needs the"
                        + " environment file Compose takes its variables from, and it is"
                        + " installed at 600 because the copy is then filled with"
                        + " credentials");
        assertFalse(workflow.contains("docker compose up --detach --wait postgres kafka"),
                "the two test stages used to start a database and a broker from the Compose file"
                        + " that no test reached: every integration class provisions its own through"
                        + " Testcontainers, so starting a second pair cost a minute a stage and"
                        + " proved nothing the container stage does not prove by starting the whole"
                        + " stack");
        assertEquals(1, occurrences(workflow, "docker compose down --volumes"),
                "the container stage is the only stage with a stack to remove");
        assertEquals(13, occurrences(workflow, "if: always()"),
                "the eight report uploads, the three cardholder-data redactions ahead of them, the"
                        + " container-state report and the one teardown run whether the stage passed"
                        + " or failed");
        assertTrue(workflow.contains("mvn -B -ntp -pl \"${modules}\" -am -DskipUnitTests=true verify"));
        assertTrue(
                workflow.contains(
                        "mvn -B -ntp -pl equivalence-tests -am -DskipUnitTests=true"
                                + " -DskipServiceIntegrationTests=true verify"));
        SERVICES.forEach(
                service ->
                        assertTrue(
                                workflow.contains("services/" + service),
                                "Integration stage omits " + service));
    }

    @Test
    @DisplayName("every suite runs in exactly one stage, and -DskipTests keeps its meaning")
    void everySuiteRunsInExactlyOneStage() throws IOException {
        // Asking Maven for a module asks for the modules it depends on, and building those runs
        // their tests too. equivalence-tests sits at the foot of the reactor and depends on the
        // eight modules above it, so `-pl equivalence-tests -am verify` on its own runs every
        // Surefire test in the platform and every service integration suite -- all of which the
        // two stages before it have already run and reported. The two flags below are what
        // leaves each stage with only the work it owns.
        assertTrue(workflow.contains("-DskipUnitTests=true"),
                "the integration and equivalence stages have to skip Surefire, which the unit"
                        + " stage ran across all nine modules");
        assertEquals(1, occurrences(workflow, "-DskipServiceIntegrationTests=true"),
                "only the equivalence stage skips the services' Failsafe suites, and only because"
                        + " the integration stage ran them; the integration stage itself must not"
                        + " carry the flag, or nothing would run them at all");

        // A profile activated by a property is inert until a command names that property. This
        // is the whole reason the skip is expressed as two profiles rather than as a plugin
        // setting: `-DskipTests` is named in the compile stage, in the container stage, in
        // scripts/start-demo.sh and in every service README, and binding Surefire's own skip
        // parameter to a new property would have made those commands start running the unit
        // suite they exist to avoid.
        Map<String, String> activations =
                Map.of(
                        "pom.xml", "skipUnitTests",
                        "services/authorization-service/pom.xml", "skipServiceIntegrationTests");
        for (Map.Entry<String, String> activation : activations.entrySet()) {
            String descriptor = Files.readString(platformRoot.resolve(activation.getKey()));
            assertTrue(descriptor.contains("<name>" + activation.getValue() + "</name>"),
                    activation.getKey() + " has to declare the profile the workflow names,"
                            + " activated by the property " + activation.getValue());
            assertFalse(descriptor.contains("<name>skipTests</name>"),
                    "no profile may key on skipTests: that property already means skip every test,"
                            + " and reusing it would change what it does everywhere it is named");
        }
        for (String service : SERVICES) {
            String descriptor =
                    Files.readString(platformRoot.resolve("services/" + service + "/pom.xml"));
            assertTrue(descriptor.contains("<name>skipServiceIntegrationTests</name>"),
                    service + " has to declare the profile, because the equivalence stage names it"
                            + " once for every service module it pulls in");
        }
        String parent = Files.readString(platformRoot.resolve("pom.xml"));
        assertFalse(parent.contains("<name>skipServiceIntegrationTests</name>"),
                "the services' Failsafe skip may not live in the parent: equivalence-tests"
                        + " inherits from it, and its own Failsafe classes are the equivalence"
                        + " stage's entire purpose");
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

    /**
     * Asserts each image is built from the platform context with a builder stage that can compile.
     *
     * <p><b>What changed and why.</b> The context was the module directory and both stages of every
     * Dockerfile were the Java Runtime Environment image, so the image copied an archive the host had
     * already packaged. The Agent Action Plan requires a Java Development Kit 25 builder stage, and a
     * module directory cannot host one: {@code mvn -pl services/X -am} needs the aggregator descriptor
     * and both shared libraries, and neither is inside the module. The context is therefore
     * {@code card-platform} and the ignore file that reduces it sits beside each Dockerfile as
     * {@code Dockerfile.dockerignore}, which BuildKit reads in place of the root one.
     *
     * <p>The final argument is asserted as a lone {@code .} on its own line, because a build that
     * still named the module directory would resolve every {@code COPY} against a tree holding no
     * aggregator and fail on the Maven invocation rather than on the context.
     */
    @Test
    @DisplayName("all six images are built from the platform context by a builder stage")
    void allSixImagesAreBuiltFromThePlatformContextByABuilderStage() {
        assertTrue(workflow.contains("for service in \"${services[@]}\""));
        assertTrue(workflow.contains("--file \"services/${service}/Dockerfile\""));
        assertFalse(workflow.contains("\"services/${service}\"\n"),
                "the module directory is no longer a build context: it holds no aggregator "
                        + "descriptor and no shared library, so a builder stage cannot compile in it");
        assertTrue(workflow.contains("--tag \"carddemo/${service}:${IMAGE_TAG}\" \\\n              ."),
                "the context is card-platform, given as the last argument");
        assertTrue(workflow.contains("DOCKER_BUILDKIT=1 docker build"),
                "each builder stage mounts a cache for the local Maven repository, which the "
                        + "legacy builder does not support");
        SERVICES.forEach(
                service -> {
                    Path dockerfile = platformRoot.resolve("services/" + service + "/Dockerfile");
                    assertTrue(Files.isRegularFile(dockerfile),
                            "Missing Dockerfile for " + service);
                    assertTrue(Files.isRegularFile(platformRoot.resolve(
                                    "services/" + service + "/Dockerfile.dockerignore")),
                            service + " must carry the ignore file BuildKit reads for this "
                                    + "Dockerfile; without it the whole platform directory, "
                                    + "including every host target/, is sent to the daemon");
                    assertFalse(Files.exists(platformRoot.resolve(
                                    "services/" + service + "/.dockerignore")),
                            service + " must not keep a module-context ignore file: the context is "
                                    + "the platform directory and that file would govern nothing");
                    String text = readFile(dockerfile);
                    assertTrue(text.contains("FROM ${BUILDER_IMAGE} AS builder"),
                            service + " must build its own archive in a builder stage");
                    assertTrue(text.contains("ARG BUILDER_IMAGE=maven:3.9.16-eclipse-temurin-25@sha256:"),
                            service + " must build on the pinned Maven 3.9.16 and Java Development "
                                    + "Kit 25 image, by digest");
                    assertTrue(text.contains("mvn -B -ntp -pl services/" + service
                                    + " -am -Dmaven.test.skip=true package"),
                            service + " must compile itself and only what it depends on");
                    assertTrue(text.contains("FROM ${RUNTIME_IMAGE} AS runtime"),
                            service + " must ship on the Java Runtime Environment image");
                    assertTrue(text.contains("COPY --from=builder --chown=10001:10001 /build/services/"
                                    + service + "/target/app.jar"),
                            service + " must carry one archive out of the builder and nothing else");
                    assertFalse(text.contains("COPY target/*.jar"),
                            service + " must not consume an archive built on the host");
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

                "the unit stage, the integration stage, the equivalence stage and the"
                        + " schema stage write test reports and the"

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
        assertTrue(script.contains("--header '" + CROSS_SITE_HEADER + ": ")
                        || script.contains(CROSS_SITE_HEADER_VARIABLE + ":-" + CROSS_SITE_HEADER),
                "every service refuses a state-changing request that carries no non-simple header,"
                        + " so a smoke without " + CROSS_SITE_HEADER + " is answered 403 and never"
                        + " reaches the authorization rules. The three consumer readings behind it"
                        + " then never run, which is how this stage stayed red while the platform"
                        + " was correct. The name may be written out or read from"
                        + " " + CROSS_SITE_HEADER_VARIABLE + ", the variable the containers"
                        + " themselves read, so renaming the header in one place cannot leave this"
                        + " call sending the old one");
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
     * Every state-changing request the workflow issues carries the first-party request header.
     *
     * <p>{@code config/CrossSiteRequestFilter} answers 403 to any unsafe method that carries no
     * value in the header {@code API_CROSS_SITE_HEADER} names, and it answers before the controller
     * runs. The business smoke sent Basic authentication and {@code Content-Type} and nothing else,
     * so it was refused at the filter and the stage could not establish the one thing it exists to
     * establish: that one authorization produces one event three consumers read.
     *
     * <p>The check is on every state-changing call rather than on that one step, because the next
     * such call would be written from the same template. A {@code curl} counts as state-changing
     * when it carries a body or names an unsafe method; the header may be spelled as the variable or
     * as the literal, since the variable is what the containers read and a caller may pin either.
     * Safe calls are left alone: the health and artifact reads are {@code GET}, and the filter lets
     * every safe method through untested.
     */
    @Test
    @DisplayName("every state-changing request in the workflow carries the first-party header")
    void everyStateChangingRequestCarriesTheFirstPartyHeader() {
        List<String> invocations = curlInvocationsOf(workflow);
        assertTrue(invocations.size() >= CURL_FLOOR,
                "the scan found " + invocations.size() + " curl calls in the workflow, fewer than"
                        + " the " + CURL_FLOOR + " it issues, so its verdict would mean nothing");

        List<String> stateChanging = invocations.stream()
                .filter(invocation -> STATE_CHANGING_CURL.matcher(invocation).find())
                .toList();
        assertFalse(stateChanging.isEmpty(),
                "the workflow has to issue at least one state-changing request, or nothing in it"
                        + " exercises the authorization path a consumer reads from");

        List<String> unheaded = stateChanging.stream()
                .filter(invocation -> !invocation.contains(CROSS_SITE_HEADER_VARIABLE)
                        && !invocation.contains(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER))
                .toList();
        assertEquals(List.of(), unheaded,
                "a state-changing call without the first-party header is refused with 403 before the"
                        + " controller runs, so it proves nothing: " + unheaded);
    }

    /**
     * Splits one shell script into the {@code curl} invocations it issues.
     *
     * <p>A line ending in a backslash continues the invocation, which is how every call in this
     * workflow is written, so reading one line at a time would separate a call from its own headers.
     *
     * @param script the workflow text
     * @return one string per invocation, headers and body included
     */
    private static List<String> curlInvocationsOf(String script) {
        List<String> invocations = new ArrayList<>();
        String[] lines = script.split("\\R", -1);
        for (int at = 0; at < lines.length; at++) {
            if (!lines[at].contains("curl ")) {
                continue;
            }
            StringBuilder invocation = new StringBuilder(lines[at].strip());
            int cursor = at;
            while (lines[cursor].stripTrailing().endsWith("\\") && cursor + 1 < lines.length) {
                cursor++;
                invocation.append(' ').append(lines[cursor].strip());
            }
            invocations.add(invocation.toString());
            at = cursor;
        }
        return invocations;
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
        assertTrue(workflow.contains("-pl \"${modules}\" -am -DskipUnitTests=true verify"),
                "the integration stage runs the six service modules under Failsafe by pattern");
    }

    /**
     * Asserts the secret scan gates the change under review and reads the history on a schedule.
     *
     * <p><b>What this stands over.</b> The stage read the whole history of every ref on every
     * event with no baseline. That scan reports 113 findings on 39 commits, none of them reachable
     * from this branch and none inside the range this engagement authored, so every push failed
     * before an image was built. A gate that can never pass is a gate somebody switches off.
     *
     * <p>Four properties are read, and each one is the difference between a gate and a formality.
     * The range comes from the event rather than being assumed. The whole-history scan is
     * conditioned on a trigger a push does not carry. The baseline is named on that scan and on the
     * fallback alone, never on the working tree. And the schedule the scan needs is declared.
     */
    @Test
    @DisplayName("the secret scan gates the range under review and reads the history on a schedule")
    void theSecretScanGatesTheRangeAndReadsTheHistoryOnASchedule() {
        Map<String, Map<?, ?>> steps = stepsByName("secret-scan");

        Map<?, ?> tree = steps.get("Read the working tree");
        assertNotNull(tree, "every file as it stands still has to be clean on every event");
        assertFalse(String.valueOf(tree.get("run")).contains("--baseline-path"),
                "the working tree is held to the strict standard: a baseline there would excuse a"
                        + " value sitting in a delivered file");

        Map<?, ?> range = steps.get("Read the commit range under review");
        assertNotNull(range, "the stage has to gate the change this event proposes");
        String rangeScript = String.valueOf(range.get("run"));
        assertTrue(rangeScript.contains("github.event.pull_request.base.sha || github.event.before"),
                "the base comes from the event: a pull request carries its base and a push carries"
                        + " the commit the branch pointed at before it");
        assertTrue(rangeScript.contains("git merge-base"),
                "the range starts where the branch left the base rather than at the base itself, so"
                        + " a base that has moved on does not drag unrelated commits in");
        assertTrue(rangeScript.contains("--log-opts \"${merge_base}..${head}\""),
                "and the scanner is told to read exactly that range");
        assertTrue(rangeScript.contains(EMPTY_TREE_BASE)
                        && rangeScript.contains("--baseline-path " + BASELINE_PATH),
                "the first push of a branch carries an all-zero base, and an event with no usable"
                        + " base reads the whole history against the baseline rather than nothing");
        assertFalse(range.containsKey("if"),
                "the range scan runs on every event, because it is the gate a push answers to");

        Map<?, ?> history = steps.get("Read the whole history against the reviewed baseline");
        assertNotNull(history, "the whole history still has to be read, on a schedule");
        String condition = String.valueOf(history.get("if"));
        assertTrue(condition.contains("'schedule'") && condition.contains("'workflow_dispatch'"),
                "a push and a pull request skip it: reading 2,580 commits on every push is what"
                        + " made this stage unpassable, and the range scan is what replaced it");
        assertTrue(String.valueOf(history.get("run")).contains("--baseline-path " + BASELINE_PATH),
                "the scheduled scan reads the reviewed baseline, so a finding it does not name"
                        + " fails wherever in the history that finding sits");

        assertTrue(workflow.contains("  schedule:\n    - cron: \"17 3 * * 1\""),
                "the scheduled scan needs a schedule trigger, declared where a reader of the"
                        + " triggers is looking");
    }

    /**
     * Asserts the reviewed baseline names its findings and never records what they held.
     *
     * <p>The baseline is committed, so it is a file anyone with the repository can read. Every
     * entry is matched by fingerprint, which is the commit, the path, the rule and the line, and
     * every value reads {@code REDACTED}. Two further properties are what make it a review rather
     * than a suppression: no entry sits on a commit this branch can reach, and two workflow steps
     * prove the file suppresses only its own entries and carries no value of its own.
     */
    @Test
    @DisplayName("every baseline entry is redacted, fingerprinted and unreachable from this branch")
    void everyBaselineEntryIsRedactedFingerprintedAndUnreachable() {
        Path baseline = platformRoot.resolve(".gitleaks-baseline.json");
        assertTrue(Files.isRegularFile(baseline),
                "the baseline the scheduled scan names has to exist at " + BASELINE_PATH);

        String text = readFile(baseline);
        assertEquals(BASELINE_FINDINGS, occurrences(text, "\"Fingerprint\": \""),
                "one entry per finding the history carried when it was reviewed");
        assertEquals(BASELINE_FINDINGS, occurrences(text, "\"Secret\": \"REDACTED\""),
                "every value is redacted: the file records which finding was reviewed and never"
                        + " what that finding held");
        assertFalse(text.contains("\"Secret\": \"\""),
                "an empty value is not a redacted one, and it would match a finding the scanner"
                        + " reports with a value");

        Set<String> reachable = commitsReachableFromHead();
        List<String> reachableEntries = new ArrayList<>();
        Matcher commits = Pattern.compile("\"Commit\": \"([0-9a-f]{40})\"").matcher(text);
        while (commits.find()) {
            if (reachable.contains(commits.group(1))) {
                reachableEntries.add(commits.group(1));
            }
        }
        assertEquals(List.of(), reachableEntries,
                "an entry on a commit this branch can reach would excuse a finding in the delivered"
                        + " history. Every one of the " + BASELINE_FINDINGS + " sits on an unrelated"
                        + " ref of a shared sample repository, which the scanner reads because it"
                        + " reads every ref");

        Map<String, Map<?, ?>> steps = stepsByName("secret-scan");
        Map<?, ?> canary = steps.get("Prove the baseline hides nothing it does not name");
        assertNotNull(canary, "a clean scheduled scan says the baseline names every finding the"
                + " history holds, and says nothing about an unnamed one");
        String script = String.valueOf(canary.get("run"));
        assertTrue(script.contains("jq '.[1:]'"),
                "the canary withholds one entry from a copy of the baseline");
        assertTrue(script.contains("test \"${reported}\" -eq 1"),
                "and requires exactly that finding back, which is what proves the comparison is"
                        + " per fingerprint rather than per file, per rule or per run");
        assertTrue(script.contains("::error::the history scan reported clean with one baseline"),
                "a canary that catches nothing has to name what it missed");

        Map<?, ?> redaction = steps.get("Prove every baseline entry is redacted");
        assertNotNull(redaction, "the count above is measured once here and enforced on every run");
        assertTrue(String.valueOf(redaction.get("run")).contains("select(.Secret != \"REDACTED\")"),
                "the step counts the entries carrying a value, and fails on the first one");
    }

    /**
     * Asserts the image stage scans the two images this platform deploys, not only the six it builds.
     *
     * <p><b>What this stands over.</b> The stage scanned the six service images and gated on their
     * fixable findings alone, so a green stage established nothing about the broker or the
     * database. {@code apache/kafka:4.2.1} carries thirteen fixable high findings and
     * {@code postgres:18.4} fifteen including one critical.
     *
     * <p>Three properties are read. The reference is derived from the shipped deployment files, so
     * the scan cannot drift from what is deployed and the two deployment paths cannot disagree. The
     * gate is the one the six service images already answer to. And the exception file is the only
     * reason it passes, which a canary proves by running the same scan without it.
     */
    @Test
    @DisplayName("the image stage scans the deployed broker and database at the shipped digest")
    void theImageStageScansTheDeployedBrokerAndDatabase() {
        Map<String, Map<?, ?>> steps = stepsByName("container-builds");

        Map<?, ?> scan = steps.get("Scan the two infrastructure images this platform deploys");
        assertNotNull(scan, "the two images this platform deploys are never scanned, so a green"
                + " image stage establishes nothing about either of them");
        String script = String.valueOf(scan.get("run"));
        assertTrue(script.contains("docker-compose.yml") && script.contains("deploy/k8s/$2"),
                "the reference is read out of the shipped files, because a digest written again in"
                        + " the workflow is a third copy and the copy that goes stale is the one"
                        + " nothing deploys");
        assertTrue(script.contains("[kafka]=10-kafka.yaml")
                        && script.contains("[postgres]=20-postgres.yaml"),
                "both deployment descriptions are read");
        assertTrue(script.contains("if [ \"${composed}\" != \"${clustered}\" ]"),
                "and the two have to name one digest, because a scan of one proves nothing about"
                        + " the other");
        assertTrue(script.contains("--ignorefile " + EXCEPTION_FILE),
                "the dated exceptions are what this gate passes under");
        assertTrue(script.contains("test \"${fixable_total}\" -eq 0"),
                "a high or critical finding with a fix available fails the stage, which is the gate"
                        + " the six service images already answer to");

        Map<?, ?> canary = steps.get("Prove the infrastructure gate still fires");
        assertNotNull(canary, "a clean result under the exception file says the exceptions hold and"
                + " says nothing about whether the gate would fail without them");
        String canaryScript = String.valueOf(canary.get("run"));
        assertFalse(canaryScript.contains("--ignorefile"),
                "the canary runs the same scan of the broker with no exception file");
        assertTrue(canaryScript.contains("::error::the broker image reports no fixable finding"),
                "and names what a clean unexcused scan would mean");

        assertTrue(indexOfStepNamed(stepList("container-builds"),
                        "Scan the two infrastructure images")
                        < indexOfStepNamed(stepList("container-builds"), "Keep the image-scan"),
                "both infrastructure reports are written before the upload that keeps them");
    }

    /**
     * Asserts every image exception names a package, an owner, a reason and an expiry.
     *
     * <p>An exception with no expiry is a decision nobody revisits, and trivy is what enforces the
     * date: it stops honouring an entry the day after, so the finding returns and the image stage
     * fails. Each entry is read for four things rather than for its identifier alone, and one
     * further property is read of the file as a whole. An entry that cannot establish
     * non-reachability has to say so, because that difference is what a reader is deciding on.
     */
    @Test
    @DisplayName("every image exception names a package, an owner, a reason and an expiry")
    void everyImageExceptionNamesAPackageAnOwnerAReasonAndAnExpiry() {
        Path exceptions = platformRoot.resolve(".trivyignore.yaml");
        assertTrue(Files.isRegularFile(exceptions),
                "the exception file the image stage names has to exist at " + EXCEPTION_FILE);

        String text = readFile(exceptions);
        assertEquals(IMAGE_EXCEPTIONS, occurrences(text, "\n  - id: "),
                "one entry per fixable finding the two pinned images carry: thirteen in the broker"
                        + " and fifteen in the database");
        assertEquals(IMAGE_EXCEPTIONS, occurrences(text, "\n    expired_at: "),
                "every entry carries the day it stops being honoured, because an exception with no"
                        + " date is a decision nobody revisits");
        assertEquals(IMAGE_EXCEPTIONS, occurrences(text, "\n    statement: >-"),
                "and the reason it was accepted");
        assertEquals(IMAGE_EXCEPTIONS, occurrences(text, "owner: project owner"),
                "and who accepted it");
        assertEquals(IMAGE_EXCEPTIONS,
                occurrences(text, "\n    paths:") + occurrences(text, "\n    purls:"),
                "every entry is scoped to the path or the package it excuses, so the same"
                        + " identifier elsewhere in either image still fails the stage");
        assertTrue(text.contains("Reachability is NOT claimed"),
                "an entry that cannot establish non-reachability says so rather than implying it,"
                        + " because that difference is what a reader of this file is deciding on");
        assertTrue(text.contains(DECISION_LOG),
                "and the file points at the decision its exceptions were taken under");

        assertEquals(Set.of("2026-11-30"), expiryDatesOf(text),
                "one shared expiry means one review answers all " + IMAGE_EXCEPTIONS + " rather"
                        + " than " + IMAGE_EXCEPTIONS + " reviews spread over a year");
    }

    /**
     * Asserts the decision every new security artifact cites is recorded under the heading it names.
     *
     * <p>Rule 1 puts rationale in one place and has each file point at it, which makes a quoted
     * heading a reference that can dangle. Nothing else in this build resolves one: the pointer
     * check reads the path and stops there, and it reads no YAML at all. Both headings the two
     * artifacts of this review name are resolved here against the log itself.
     */
    @Test
    @DisplayName("the decision each security artifact quotes is recorded under that exact heading")
    void theDecisionEachSecurityArtifactQuotesIsRecorded() {
        String log = readFile(platformRoot.resolve("docs/decision-log.md"));
        String citing = unwrapComments(workflow)
                + unwrapComments(readFile(platformRoot.resolve(EXCEPTION_FILE)));
        List<String> quoted = List.of(
                "A secret scan reads the change under review, and the whole history on a schedule",
                "Two pinned infrastructure images carry a dated exception rather than an unpinned"
                        + " upgrade");
        for (String heading : quoted) {
            assertTrue(citing.contains(heading),
                    "no shipped file quotes this heading any longer, so the assertion below is"
                            + " protecting a reference nothing makes: " + heading);
            assertTrue(log.contains("### " + heading),
                    DECISION_LOG + " carries no heading \"" + heading + "\", so a shipped file"
                            + " points a reader at a decision that is not recorded");
        }
    }

    /**
     * Reads commented text as the sentences it holds, with the markers and the wrapping removed.
     *
     * <p>A quoted heading is wrapped across lines by whatever comment marker the file uses, so a
     * plain search for it finds nothing. Stripping the leading marker of every line and collapsing
     * the whitespace leaves the sentence a reader sees.
     */
    private static String unwrapComments(String text) {
        StringBuilder unwrapped = new StringBuilder();
        for (String line : text.split("\n")) {
            unwrapped.append(line.replaceFirst("^\\s*#+\\s?", "")).append(' ');
        }
        return unwrapped.toString().replaceAll("\\s+", " ");
    }

    /** Reads one job's steps as a list. */
    private static List<?> stepList(String job) {
        return assertInstanceOf(List.class,
                assertInstanceOf(Map.class, jobs.get(job)).get("steps"));
    }

    /**
     * Indexes one job's steps by name.
     *
     * @param job the job to read
     * @return each step body under the name it declares
     */
    private static Map<String, Map<?, ?>> stepsByName(String job) {
        Map<String, Map<?, ?>> byName = new LinkedHashMap<>();
        for (Object step : stepList(job)) {
            Map<?, ?> body = assertInstanceOf(Map.class, step);
            byName.put(String.valueOf(body.get("name")), body);
        }
        return byName;
    }

    /** Answers with every distinct expiry the exception file declares. */
    private static Set<String> expiryDatesOf(String exceptions) {
        Set<String> dates = new LinkedHashSet<>();
        Matcher declared = Pattern.compile("expired_at: (\\d{4}-\\d{2}-\\d{2})").matcher(exceptions);
        while (declared.find()) {
            dates.add(declared.group(1));
        }
        return dates;
    }

    /**
     * Answers with every commit this branch can reach.
     *
     * <p>Read from git rather than assumed, because the property being asserted is that no baseline
     * entry sits on one of them and that set changes with every commit.
     */
    private static Set<String> commitsReachableFromHead() {
        try {
            Process process = new ProcessBuilder("git", "rev-list", "HEAD")
                    .directory(platformRoot.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), () -> "git rev-list HEAD failed: " + output);
            Set<String> reachable = new LinkedHashSet<>();
            for (String line : output.split("\n")) {
                String commit = line.trim();
                if (!commit.isEmpty()) {
                    reachable.add(commit);
                }
            }
            return reachable;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot run git rev-list", unreadable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running git rev-list", interrupted);
        }
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

    /**
     * Reads a file, turning the checked failure into an unchecked one.
     *
     * @param file the file to read
     * @return its contents
     */
    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
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