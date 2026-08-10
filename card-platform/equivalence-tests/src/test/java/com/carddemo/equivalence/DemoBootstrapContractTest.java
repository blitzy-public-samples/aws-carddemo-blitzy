package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the committed bootstrap procedures against the files that advertise them.
 *
 * <p>Three documents told a reader to run {@code cp .env.example .env} and then
 * {@code docker compose up -d --build}, and that pair cannot start a clean clone: Compose reads
 * nineteen credentials nothing in this repository supplies. {@code scripts/start-demo.sh} is the one
 * command that performs those steps in the order that works, and
 * {@code deploy/k8s/load-images.sh} is the step a cluster needs before {@code kubectl apply}
 * because the six Deployments may never pull.
 *
 * <p>The archive half of that failure is gone. Every {@code Dockerfile} now compiles its own module
 * in a Java Development Kit 25 builder stage, so an image build needs no host packaging step and
 * neither script depends on one. What remains is the credentials, which is why
 * {@code scripts/generate-env.sh} exists.
 *
 * <p>Each assertion reads the shipped file, so a script that stops doing what a document promises
 * fails the unit-test phase rather than a demonstration.
 */
@DisplayName("Committed bootstrap procedures and the documents that advertise them")
class DemoBootstrapContractTest {

    private static final String START_DEMO = "scripts/start-demo.sh";
    private static final String GENERATE_ENV = "scripts/generate-env.sh";
    private static final String LOAD_IMAGES = "deploy/k8s/load-images.sh";

    private static final List<String> SCRIPTS = List.of(START_DEMO, GENERATE_ENV, LOAD_IMAGES);

    private static final List<String> SERVICES =
            List.of(
                    "authorization-service",
                    "ledger-posting-service",
                    "fraud-detection-service",
                    "notification-service",
                    "account-service",
                    "card-service");

    /** The names {@code .env.example} marks as passwords, and the script derives rather than lists. */
    private static final Pattern PASSWORD_PLACEHOLDER =
            Pattern.compile("(?m)^([A-Z0-9_]+)=REPLACE-WITH");

    /** The names it marks as already-encoded identity hashes. */
    private static final Pattern IDENTITY_PLACEHOLDER =
            Pattern.compile("(?m)^([A-Z0-9_]+)='\\{bcrypt}.*REPLACE-THIS");

    /**
     * A Compose reference of the form <code>${VAR:?message}</code>, which has no default at all.
     *
     * <p>A doubled dollar is excluded deliberately. Compose reads <code>$$</code> as one literal
     * dollar and interpolates nothing, so such a reference reaches a container's own shell and is
     * answered by that container's environment rather than by the dotenv file. The provisioning
     * container's SERVICE_DATABASE_NAMES is one: Compose sets it in the same file.
     */
    private static final Pattern REQUIRED_COMPOSE_VARIABLE =
            Pattern.compile("(?<!\\$)\\$\\{([A-Z][A-Z0-9_]*):\\?");

    /** Every assignment one dotenv document declares, in the order it declares them. */
    private static final Pattern DOTENV_ASSIGNMENT =
            Pattern.compile("(?m)^([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    @Nested
    @DisplayName("every committed script")
    class EveryScript {

        @Test
        @DisplayName("exists, is executable, and stops on the first failure")
        void existsIsExecutableAndStopsOnTheFirstFailure() {
            for (String script : SCRIPTS) {
                Path path = platformDirectory().resolve(script);
                assertTrue(Files.isRegularFile(path), script + " must exist");
                assertTrue(Files.isExecutable(path),
                        script + " must carry the executable bit, or the documented command fails"
                                + " with Permission denied");

                String text = read(path);
                assertTrue(text.startsWith("#!/usr/bin/env bash\n"),
                        script + " must name bash through env, because the tested form uses mapfile"
                                + " and arrays");
                assertTrue(text.contains("\nset -euo pipefail\n"),
                        script + " must stop on the first failure, on an unset name and inside a"
                                + " pipeline; a bootstrap that carries on after a failed step leaves"
                                + " a half-built stack and reports success");
            }
        }

        @Test
        @DisplayName("resolves its own directory rather than trusting the caller's")
        void resolvesItsOwnDirectory() {
            for (String script : SCRIPTS) {
                String text = read(platformDirectory().resolve(script));
                assertTrue(text.contains("BASH_SOURCE[0]"),
                        script + " must resolve its own location, so it runs the same from any"
                                + " working directory");
                assertTrue(text.contains("cd \"${platform_root}\""),
                        script + " must run from the platform root it resolved");
            }
        }

        @Test
        @DisplayName("carries no placeholder, no interactive prompt and no deferred step")
        void carriesNoPlaceholderOrPrompt() {
            Pattern prohibited =
                    Pattern.compile("(?i)\\b(TODO|FIXME|TBD|coming soon|implement later)\\b");
            for (String script : SCRIPTS) {
                String text = read(platformDirectory().resolve(script));
                assertFalse(prohibited.matcher(text).find(), script + " must be complete");
                assertFalse(text.contains("read -r"),
                        script + " must not prompt: the whole point of it is a run that asks"
                                + " nothing");
                assertFalse(text.contains("read -p"), script + " must not prompt");
            }
        }
    }

    @Nested
    @DisplayName("the environment generator")
    class EnvironmentGenerator {

        @Test
        @DisplayName("derives the credential names from .env.example rather than listing them")
        void derivesTheCredentialNamesFromTheExampleFile() {
            String script = read(platformDirectory().resolve(GENERATE_ENV));
            String example = read(platformDirectory().resolve(".env.example"));

            List<String> passwords = names(PASSWORD_PLACEHOLDER, example);
            List<String> identities = names(IDENTITY_PLACEHOLDER, example);
            assertEquals(15, passwords.size(),
                    "the example file has to keep marking its fourteen passwords and its"
                            + " card-token key with REPLACE-WITH, because that marker is what the"
                            + " script reads");
            assertEquals(4, identities.size(),
                    "and its four identity hashes with REPLACE-THIS inside a {bcrypt} prefix");

            assertTrue(script.contains("=REPLACE-WITH.*/\\1/p"),
                    "the script has to read the password names out of the file");
            assertTrue(script.contains("REPLACE-THIS.*/\\1/p"),
                    "and the identity names the same way");
            // Comments are read out first. One of them explains the derivation by naming a key
            // as an example, and that sentence is worth keeping; what may not exist is a name the
            // script acts on.
            String code = statementsOf(script);
            for (String credential : passwords) {
                assertFalse(code.contains(credential),
                        "no statement may name " + credential + ": a second list of these names is"
                                + " a list that falls behind .env.example");
            }
            for (String identity : identities) {
                assertFalse(code.contains(identity),
                        "no statement may name " + identity);
            }
        }

        @Test
        @DisplayName("protects what it writes and proves no placeholder survived")
        void protectsWhatItWritesAndProvesNoPlaceholderSurvived() {
            String script = read(platformDirectory().resolve(GENERATE_ENV));

            assertEquals(2, occurrences(script, "chmod 600"),
                    "both files it writes hold working credentials: .env and .demo-credentials");
            assertTrue(script.contains("grep -c 'REPLACE-WITH\\|REPLACE-THIS'"),
                    "the script has to end by proving the file it produced carries no placeholder,"
                            + " because a placeholder left behind stops the stack later and further"
                            + " away");
            assertTrue(script.contains("tr -d '/+='"),
                    "a generated password may carry no quote, backslash or whitespace: the broker"
                            + " builds a login entry around it and any of the three ends the entry"
                            + " early");
            assertTrue(script.contains("PasswordEncoderFactories"),
                    "the four identity values are stored already encoded, because every service"
                            + " refuses a value carrying no encoding prefix");
            assertTrue(script.contains("${key}='${hash}'"),
                    "a bcrypt value contains $, and Compose expands $ in an unquoted or"
                            + " double-quoted dotenv value");
            assertTrue(script.contains("CARDDEMO_${identity}_PASSWORD")
                            || script.contains("CARDDEMO_\" + \"${identity}_PASSWORD"),
                    "the caller has to be able to choose a password rather than read a generated"
                            + " one");
        }

        /**
         * Reconciles a prior-version environment file, and every value already chosen survives.
         *
         * <p>This is the failure that made the documented demo path stop at its first command.
         * {@code .env.example} is the declaration of what a run needs and {@code .env} is one
         * machine's answer to it, so a key added to the declaration after the answer was written is
         * simply absent from the answer. An absent key leaves no placeholder behind, so the
         * placeholder count the script ends with could not see it: the script reported success on a
         * file that was missing {@code ACQUIRER_PASSWORD_HASH}, and {@code docker compose config}
         * then refused to interpolate a variable Compose requires without a default.
         *
         * <p>The fixture here is a real prior version rather than a sketch: it is the shipped
         * example with the same kind of gap, every placeholder already answered, and one setting the
         * example no longer declares. Nothing is generated, so this runs without {@code openssl} or
         * {@code jshell} on the path, which is why the script requires each tool where it uses it.
         *
         * @param workspace a directory JUnit creates and removes
         * @throws Exception when the script cannot be run
         */
        @Test
        @DisplayName("reconciles a prior-version .env, preserving every value already chosen")
        void reconcilesAPriorVersionEnvironmentFile(@TempDir Path workspace) throws Exception {
            String example = read(platformDirectory().resolve(".env.example"));
            List<String> declared = declaredKeys(example);
            assertTrue(declared.size() > 100,
                    "the example declares " + declared.size() + " assignments, so this fixture is"
                            + " reading the wrong file");

            Set<String> withheld = Set.of("ACQUIRER_USERNAME", "ACQUIRER_PASSWORD_HASH",
                    "API_CROSS_SITE_HEADER", "REPLICA_LAG_CEILING");
            String retired = "A_SETTING_THE_EXAMPLE_NO_LONGER_DECLARES";
            String priorVersion = priorVersionOf(example, withheld) + retired + "=kept\n";

            Files.createDirectory(workspace.resolve("scripts"));
            Files.copy(platformDirectory().resolve(GENERATE_ENV),
                    workspace.resolve(GENERATE_ENV));
            Files.writeString(workspace.resolve(".env.example"), example, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve(".env"), priorVersion, StandardCharsets.UTF_8);

            Process run = new ProcessBuilder("bash", GENERATE_ENV)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(run.waitFor(2, TimeUnit.MINUTES), "the generator did not finish");
            assertEquals(0, run.exitValue(),
                    "the generator refused a prior-version file it can reconcile:\n" + output);

            String reconciled = read(workspace.resolve(".env"));
            List<String> stillMissing = declared.stream()
                    .filter(key -> !reconciled.contains("\n" + key + "=")
                            && !reconciled.startsWith(key + "="))
                    .toList();
            assertEquals(List.of(), stillMissing,
                    "every assignment the example declares has to be present afterwards, or"
                            + " Compose refuses to interpolate it");
            for (String added : withheld) {
                assertTrue(output.contains(added),
                        "the run has to name " + added + " as one it added, so an operator can see"
                                + " what changed");
            }
            assertTrue(output.contains(retired),
                    "and name the setting the example no longer declares rather than deleting a"
                            + " value this machine may have chosen");
            assertTrue(reconciled.contains(retired + "=kept"),
                    "which means leaving it in place");

            Map<String, String> before = assignments(priorVersion);
            Map<String, String> after = assignments(reconciled);
            List<String> rewritten = before.keySet().stream()
                    .filter(key -> !before.get(key).equals(after.get(key)))
                    .toList();
            assertEquals(List.of(), rewritten,
                    "no value already chosen on this machine may be rewritten: a password is not"
                            + " recoverable from the hash it made");

            for (String identity : names(IDENTITY_PLACEHOLDER, example)) {
                assertTrue(after.containsKey(identity),
                        "all four request identities have to be declared afterwards, and "
                                + identity + " is not");
                assertFalse(after.get(identity).contains("REPLACE-THIS"),
                        identity + " is still a placeholder, so nothing generated it");
            }
        }

        /**
         * A published key is replaced, and a tightened default is named rather than changed.
         *
         * <p>Reconciling names closed one half of the upgrade problem. This is the other half, and
         * both halves were found the same way: the delivered workspace would not start. Two values
         * that were usable when they were written had stopped being usable. {@code
         * CARD_TOKEN_SECRET} held the demo key this repository publishes, which the authorization
         * and card services now refuse outright, and {@code PROCESSED_EVENT_RETENTION_HOURS} held a
         * week, which is no longer twice the broker window the marker has to outlive. Neither is a
         * missing key and neither is a placeholder, so every check the script had passed while four
         * of six services could not start.
         *
         * <p>The two are handled differently on purpose. A published key is nobody's secret and no
         * running deployment can hold it, so it is regenerated. An overridden setting may be a
         * deliberate choice, so it is reported with both values and left alone: the operator decides,
         * and the report is what makes the decision possible.
         */
        @Test
        @DisplayName("regenerates a published card-token key and names an overridden setting")
        void regeneratesAPublishedKeyAndNamesAnOverriddenSetting(@TempDir Path workspace)
                throws Exception {
            String example = read(platformDirectory().resolve(".env.example"));
            String publishedKey = publishedCardTokenKey();
            String prior = priorVersionOf(example, Set.of())
                    .replaceAll("(?m)^CARD_TOKEN_SECRET=.*$",
                            "CARD_TOKEN_SECRET=" + publishedKey)
                    .replaceAll("(?m)^PROCESSED_EVENT_RETENTION_HOURS=.*$",
                            "PROCESSED_EVENT_RETENTION_HOURS=168");

            Files.createDirectory(workspace.resolve("scripts"));
            Files.copy(platformDirectory().resolve(GENERATE_ENV), workspace.resolve(GENERATE_ENV));
            Files.writeString(workspace.resolve(".env.example"), example, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve(".env"), prior, StandardCharsets.UTF_8);

            Process run = new ProcessBuilder("bash", GENERATE_ENV)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(run.waitFor(2, TimeUnit.MINUTES), "the generator did not finish");
            assertEquals(0, run.exitValue(), "the generator refused the file:\n" + output);

            Map<String, String> after = assignments(read(workspace.resolve(".env")));
            assertFalse(publishedKey.equals(after.get("CARD_TOKEN_SECRET")),
                    "the published key has to be replaced, because every service that reads it"
                            + " refuses to start on it");
            assertFalse(after.get("CARD_TOKEN_SECRET").contains("REPLACE-WITH"),
                    "and replaced with a generated value rather than the placeholder");
            assertTrue(after.get("CARD_TOKEN_SECRET").length() >= 32,
                    "a card-token key under 32 characters is padded rather than filled, so the"
                            + " generated one has to be at least that long");
            assertTrue(output.contains(
                            "CARD_TOKEN_SECRET held a value this repository publishes"),
                    "the run has to say why it replaced a value, since replacing one is otherwise"
                            + " the thing this script never does:\n" + output);

            String script = read(platformDirectory().resolve(GENERATE_ENV));
            for (String refused : refusedCardTokenKeys()) {
                assertTrue(script.contains("\"" + refused + "\""),
                        "the services refuse " + refused + " at start-up, so the generator has to"
                                + " recognise it. A value the code refuses and the generator keeps"
                                + " is a workspace that reports success and cannot start.");
            }

            assertEquals("168", after.get("PROCESSED_EVENT_RETENTION_HOURS"),
                    "an overridden setting stays as this machine set it");
            assertTrue(output.contains(
                            "PROCESSED_EVENT_RETENTION_HOURS is 168 here and 720 in the example"),
                    "and is reported with both values, which is what turns a start-up refusal into"
                            + " one actionable line:\n" + output);
        }

        /**
         * The demo card-token key this repository publishes, read from the service that refuses it.
         *
         * @return the published literal
         */
        private String publishedCardTokenKey() {
            List<String> refused = refusedCardTokenKeys();
            assertFalse(refused.isEmpty(),
                    "the authorization service has to declare the published key this script"
                            + " replaces, or the two have drifted apart");
            return refused.getFirst();
        }

        /**
         * Every card-token key the authorization service refuses at start-up.
         *
         * <p>Read from the service rather than listed here, so a key added to the refusal there
         * fails this test until the generator recognises it too.
         *
         * @return the refused literals, in declaration order
         */
        private List<String> refusedCardTokenKeys() {
            String source = read(platformDirectory().resolve("services/authorization-service/src"
                    + "/main/java/com/carddemo/authorization/config/SecurityConfig.java"));
            Matcher literal = Pattern
                    .compile("(?:PUBLISHED|BUILD_SCOPE)_CARD_TOKEN_KEY = \"([^\"]+)\"")
                    .matcher(source);
            return literal.results().map(match -> match.group(1)).toList();
        }

        /**
         * Every variable Compose requires without a default is declared by the example.
         *
         * <p>The static half of the same guarantee, and it is what makes the reconciliation above
         * sufficient. {@code ${VAR:?message}} tells Compose to refuse the whole file when {@code VAR}
         * resolves to nothing, so such a variable has to be declared somewhere a reader can find it.
         * A variable Compose requires and the example does not declare cannot be reconciled into an
         * environment file, because there is nothing to copy it from.
         */
        @Test
        @DisplayName("every variable Compose requires without a default is declared by the example")
        void everyVariableComposeRequiresIsDeclaredByTheExample() {
            String compose = read(platformDirectory().resolve("docker-compose.yml"));
            String example = read(platformDirectory().resolve(".env.example"));
            Set<String> declared = Set.copyOf(declaredKeys(example));

            Matcher required = REQUIRED_COMPOSE_VARIABLE.matcher(compose);
            List<String> undeclared = new ArrayList<>();
            int checked = 0;
            while (required.find()) {
                checked++;
                if (!declared.contains(required.group(1))) {
                    undeclared.add(required.group(1));
                }
            }

            assertTrue(checked >= 15,
                    "Compose requires " + checked + " variables without a default, which is fewer"
                            + " than this file has ever had");
            assertEquals(List.of(), undeclared,
                    "a variable Compose refuses to default cannot be reconciled from an example"
                            + " that does not declare it");
        }

        @Test
        @DisplayName("keeps the plaintext it generated out of version control")
        void keepsThePlaintextOutOfVersionControl() {
            String ignores = read(platformDirectory().resolve(".gitignore"));
            assertTrue(ignores.contains("\n/.demo-credentials\n"),
                    "the file holding four working passwords may not enter history");
            assertTrue(ignores.contains("\n/.env\n"), "and neither may the environment file");
            assertTrue(ignores.contains("!/.env.example"),
                    ".env.example is the tracked template and stays tracked");
            assertTrue(ignores.indexOf("/.demo-credentials")
                            > ignores.indexOf("bcrypt"),
                    "the ignore rule has to carry the reason the file exists, so a later reader"
                            + " does not remove it as noise");
        }
    }

    @Nested
    @DisplayName("the one-command bootstrap")
    class OneCommandBootstrap {

        @Test
        @DisplayName("performs the three steps a clean clone cannot skip, in order")
        void performsTheThreeStepsACleanCloneCannotSkip() {
            String script = read(platformDirectory().resolve(START_DEMO));

            int packaged = script.indexOf("mvn -B -ntp -DskipTests package");
            int credentials = script.indexOf(GENERATE_ENV.substring("scripts/".length()));
            int started = script.indexOf("docker compose up --detach --build --wait");

            assertTrue(packaged > 0, "the reactor has to be packaged: every image copies an archive");
            assertTrue(credentials > packaged,
                    "the identity hashes need the spring-security-crypto archive the packaging step"
                            + " resolves, so the environment file is filled after it");
            assertTrue(started > credentials,
                    "Compose reads the credentials, so the stack starts last");
            assertTrue(script.contains("scripts/generate-env.sh"),
                    "the bootstrap calls the generator rather than repeating it");
        }

        @Test
        @DisplayName("refuses a missing prerequisite by name and reads every health endpoint")
        void refusesAMissingPrerequisiteAndReadsEveryHealthEndpoint() {
            String script = read(platformDirectory().resolve(START_DEMO));

            for (String tool : List.of("java", "mvn", "docker", "openssl", "jshell")) {
                assertTrue(script.contains(tool),
                        "the prerequisite check has to name " + tool);
            }
            assertTrue(script.contains("docker info >/dev/null 2>&1"),
                    "an unreachable daemon has to be reported as itself rather than as a Compose"
                            + " error");
            for (String service : SERVICES) {
                assertTrue(script.contains(service),
                        "all six services have to be asked whether they are up: " + service);
            }
            assertTrue(script.contains("docker compose port \"${service}\""),
                    "the published host port is asked of Compose rather than assumed, because .env"
                            + " or the shell may have moved it and CLONE_INDEX exists so a second"
                            + " stack can");
            assertTrue(script.contains("'\"status\":\"UP\"'"),
                    "a 200 carrying a down status is not a passing check");
            assertTrue(script.contains("docker compose down --volumes"),
                    "a reader has to be told how to stop what was started");
            assertTrue(script.contains("the stack did not converge."),
                    "Compose reports a failed start as \"container X is unhealthy\", which names no"
                            + " cause, so the script has to name the one cause it can predict: a"
                            + " database volume that outlived the environment file whose superuser"
                            + " password built it");
        }

        @Test
        @DisplayName("is the command .env.example, Compose, the README and onboarding advertise")
        void isTheCommandEveryDocumentAdvertises() {
            for (String advertiser :
                    List.of(".env.example", "docker-compose.yml", "README.md", "docs/onboarding.md")) {
                String text = read(platformDirectory().resolve(advertiser));
                assertTrue(text.contains(START_DEMO),
                        advertiser + " must advertise " + START_DEMO + " as the path from a clean"
                                + " clone; a document naming only the four manual steps is what"
                                + " left a reader assembling them");
            }

            String onboarding = read(platformDirectory().resolve("docs/onboarding.md"));
            assertTrue(onboarding.contains("### Start with one command"));
            assertTrue(onboarding.contains(GENERATE_ENV),
                    "onboarding must name the generator for a reader who wants .env alone");
            assertTrue(onboarding.contains(".demo-credentials"),
                    "and must say where the four generated passwords are written");
        }
    }

    @Nested
    @DisplayName("the cluster image loader")
    class ClusterImageLoader {

        @Test
        @DisplayName("covers every runtime that can be handed a locally built image")
        void coversEveryRuntimeThatCanBeHandedALocalImage() {
            String script = read(platformDirectory().resolve(LOAD_IMAGES));

            assertTrue(script.contains("kind load docker-image"),
                    "kind needs an explicit load, or the node never sees the image");
            assertTrue(script.contains("minikube image load"),
                    "minikube needs its own load command");
            assertTrue(script.contains("docker-desktop"),
                    "Docker Desktop shares this daemon, and saying so is what stops a reader"
                            + " looking for a step that does not exist");
            assertTrue(script.contains("kubectl config current-context"),
                    "the runtime is read from the context when the caller names none");
            assertTrue(script.contains("KIND_CLUSTER_NAME") && script.contains("MINIKUBE_PROFILE"),
                    "a cluster other than the default has to be selectable");
            for (String service : SERVICES) {
                assertTrue(script.contains(service), "the loader omits " + service);
            }
        }

        @Test
        @DisplayName("builds under the tag the manifests name and verifies the load")
        void buildsUnderTheTagTheManifestsNameAndVerifiesTheLoad() {
            String script = read(platformDirectory().resolve(LOAD_IMAGES));
            String pom = read(platformDirectory().resolve("pom.xml"));
            Matcher version = Pattern.compile("<version>([^<]+)</version>").matcher(pom);
            assertTrue(version.find(), "the aggregator declares its version");
            String projectVersion = version.group(1);

            assertTrue(script.contains("grep -m1 -o '<version>[^<]*</version>' pom.xml"),
                    "the tag is read out of pom.xml, so it cannot drift from the manifests");
            assertTrue(script.contains("sed -n 's/^ *newTag: *//p' \"${kustomization}\""),
                    "and it is checked against what the manifests actually request, rather than "
                            + "assumed to agree with it");
            assertTrue(script.contains("--tag \"carddemo/${service}:${image_tag}\""),
                    "and every build uses it");
            assertTrue(script.contains("--file \"services/${service}/Dockerfile\""),
                    "each image builds from its own module's Dockerfile");
            assertFalse(script.contains("\"services/${service}\"\n"),
                    "the module directory is no longer the context: the builder stage compiles the "
                            + "module and needs the aggregator descriptor and both shared libraries, "
                            + "neither of which is inside it");
            assertTrue(script.contains("--tag \"carddemo/${service}:${image_tag}\" \\\n        ."),
                    "the context is the platform directory, given as the last argument");
            assertTrue(script.contains("DOCKER_BUILDKIT=1 docker build"),
                    "each builder stage mounts a cache for the local Maven repository, which the "
                            + "legacy builder does not support");
            assertFalse(script.contains("mvn "),
                    "this script requires Docker and nothing else now: every image compiles its "
                            + "own module inside a builder stage, so no Maven installation and no "
                            + "host archive has to be present");
            assertTrue(script.contains("crictl images") && script.contains("minikube image ls"),
                    "the node's image list is read back, because a load that silently did nothing"
                            + " looks identical until the first apply");

            for (int manifest = 40; manifest <= 45; manifest++) {
                Path path = firstManifestStartingWith(Integer.toString(manifest));
                String text = read(path);
                assertTrue(text.contains(":" + projectVersion),
                        path.getFileName() + " must name the project version");
                assertTrue(text.contains("imagePullPolicy: Never"),
                        path.getFileName() + " must keep the policy the loader exists for");
            }
        }

        @Test
        @DisplayName("is documented with the failure it prevents and the apply order it prints")
        void isDocumentedWithTheFailureItPrevents() {
            String onboarding = read(platformDirectory().resolve("docs/onboarding.md"));
            assertTrue(onboarding.contains(LOAD_IMAGES),
                    "onboarding must carry the command");
            assertTrue(onboarding.contains("ErrImageNeverPull"),
                    "and name the status a reader will otherwise be left holding");
            assertTrue(onboarding.contains("### Run it on a local Kubernetes cluster"));
            assertTrue(onboarding.contains(
                            "### 11. A local Kubernetes cluster cannot pull the six service images"),
                    "the pitfall list has to carry it, because it is where a reader looks after a"
                            + " Pod refuses to start");
            assertTrue(onboarding.contains("kubectl -n carddemo rollout restart"),
                    "a rebuilt image changes nothing until the Pods restart");
            for (String service : SERVICES) {
                assertTrue(onboarding.contains("deployment/" + service),
                        "the restart names " + service + " rather than the whole namespace: a bare"
                                + " `rollout restart deployment` cycles the broker and the database"
                                + " too, dropping every consumer group for no reason");
            }

            String readme = read(platformDirectory().resolve("README.md"));
            assertTrue(readme.contains("load-images.sh"),
                    "the repository map has to name it beside deploy/k8s");

            String script = read(platformDirectory().resolve(LOAD_IMAGES));
            assertTrue(script.contains("31-secret.example.yaml"),
                    "the printed apply order has to keep the Secret template excluded, which is the"
                            + " order 00-namespace.yaml documents");
        }

        /**
         * Three things a reader can act on have to be true at once here, and a review found all
         * three wrong. The apply command has to be one that works: a glob over this folder reaches
         * {@code kustomization.yaml}, which is not an API object, so {@code kubectl apply -f} fails
         * on it. The image step has to be one that puts images where a node looks: a host
         * {@code docker build} leaves them in this machine's daemon, which is not kind's or
         * minikube's store. And a tag override has to either reach the manifests or be refused,
         * because {@code imagePullPolicy: Never} turns a tag the manifests do not request into the
         * same {@code ErrImageNeverPull} as no image at all.
         */
        @Test
        @DisplayName("names an apply path that works, an image step that loads, and a tag that reaches the manifests")
        void namesAnApplyPathThatWorksAndATagThatReachesTheManifests() {
            String script = read(platformDirectory().resolve(LOAD_IMAGES));
            String namespace = read(kubernetesDirectory().resolve("00-namespace.yaml"));
            String clusterReadme = read(kubernetesDirectory().resolve("README.md"));
            String onboarding = read(platformDirectory().resolve("docs/onboarding.md"));

            for (String document : List.of(script, namespace, clusterReadme, onboarding)) {
                assertTrue(document.contains("apply -k "),
                        "every document that states how to apply these manifests must name the"
                                + " kustomize entry point");
                assertFalse(document.contains("xargs -n1 kubectl apply -f"),
                        "and none may name the glob apply, which fails on kustomization.yaml");
            }

            assertTrue(script.contains("IMAGE_TAG is '${image_tag}' but the manifests request"),
                    "an IMAGE_TAG the manifests do not request has to be refused by name");
            assertTrue(script.contains("kustomize edit set image"),
                    "and the refusal has to print the command that changes what they request");
            assertTrue(script.contains("ErrImageNeverPull"),
                    "the refusal has to name the failure it is preventing");
            assertTrue(script.contains("requests '${manifest_tag}' but pom.xml declares"),
                    "drift between kustomization.yaml and the project version has to be caught too,"
                            + " because Compose and the pipeline build the project version");

            String restart = "kubectl -n carddemo rollout restart ${restart_targets}";
            assertTrue(script.contains(restart),
                    "the printed restart has to name its targets");
            for (String service : SERVICES) {
                assertTrue(script.contains("deployment/${service}"),
                        "and build them from the six service names");
            }
            assertFalse(script.contains("rollout restart deployment\n"),
                    "a bare namespace-wide restart cycles Kafka and PostgreSQL as well");

            assertTrue(clusterReadme.contains("deploy/k8s/load-images.sh"),
                    "the cluster README's own step 1 has to be the script, not a build loop that"
                            + " leaves a kind or minikube node with nothing to run");
            assertFalse(clusterReadme.contains("docker build -f \"services/${service}/Dockerfile\""),
                    "and it must not restate the build loop beside the script, which is how the two"
                            + " came to disagree");
        }
    }

    /** The names one dotenv document assigns, in file order. */
    private static List<String> declaredKeys(String document) {
        return names(DOTENV_ASSIGNMENT, document);
    }

    /** The assignments one dotenv document carries, name to value. */
    private static Map<String, String> assignments(String document) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher assignment = DOTENV_ASSIGNMENT.matcher(document);
        while (assignment.find()) {
            values.put(assignment.group(1), assignment.group(2));
        }
        return values;
    }

    /**
     * Builds a plausible earlier answer to the shipped example: every declared assignment except
     * the withheld ones, with each placeholder replaced by a value of the right shape.
     *
     * <p>Answering the placeholders is what keeps this fixture hermetic. A file still carrying them
     * would send the script to {@code openssl} and to {@code jshell}, and a test that needs a
     * particular binary on the path tests the machine it runs on as much as the script.
     *
     * @param example  the shipped declaration
     * @param withheld the names this earlier answer never carried
     * @return the earlier answer, one assignment per line
     */
    private static String priorVersionOf(String example, Set<String> withheld) {
        StringBuilder earlier = new StringBuilder();
        assignments(example).forEach((key, value) -> {
            if (withheld.contains(key)) {
                return;
            }
            String answered = value
                    .replace("'{bcrypt}$2a$10$REPLACE-THIS-PLACEHOLDER-WITH-A-REAL-BCRYPT-HASH'",
                            "'{bcrypt}$2a$10$" + "0123456789012345678901"
                                    + "0123456789012345678901234567890'")
                    .replaceAll("REPLACE-WITH[A-Za-z0-9-]*",
                            "aValueThisMachineAlreadyChoseOfAtLeastThirtyTwoCharacters");
            earlier.append(key).append('=').append(answered).append('\n');
        });
        return earlier.toString();
    }

    /** The script with its comment lines removed, leaving what it actually executes. */
    private static String statementsOf(String script) {
        return script.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static List<String> names(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(match -> match.group(1)).toList();
    }

    /** The folder holding the cluster manifests, the kustomization and their README. */
    private static Path kubernetesDirectory() {
        return platformDirectory().resolve("deploy/k8s");
    }

    private static Path firstManifestStartingWith(String prefix) {
        Path directory = kubernetesDirectory();
        try (var entries = Files.list(directory)) {
            return entries
                    .filter(path -> path.getFileName().toString().startsWith(prefix + "-"))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalStateException("no manifest numbered " + prefix));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
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

    private static Path platformDirectory() {
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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
