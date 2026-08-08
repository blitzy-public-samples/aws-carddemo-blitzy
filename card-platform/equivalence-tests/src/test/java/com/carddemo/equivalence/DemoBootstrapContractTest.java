package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the committed bootstrap procedures against the files that advertise them.
 *
 * <p>Three documents told a reader to run {@code cp .env.example .env} and then
 * {@code docker compose up -d --build}, and that pair cannot start a clean clone: every
 * {@code Dockerfile} copies an archive out of its own module's {@code target/} directory, which a
 * fresh checkout has none of, and Compose reads nineteen credentials nothing in this repository
 * supplies. {@code scripts/start-demo.sh} is the one command that performs those steps in the
 * order that works, and {@code deploy/k8s/load-images.sh} is the step a cluster needs before
 * {@code kubectl apply} because the six Deployments may never pull.
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
            assertTrue(script.contains("--tag \"carddemo/${service}:${image_tag}\""),
                    "and every build uses it");
            assertTrue(script.contains("--file \"services/${service}/Dockerfile\""),
                    "each image builds from its own module's Dockerfile and context");
            assertTrue(script.contains("mvn -B -ntp -DskipTests package"),
                    "a missing archive is packaged rather than reported as a COPY failure");
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
            assertTrue(onboarding.contains("kubectl -n carddemo rollout restart deployment"),
                    "a rebuilt image changes nothing until the Pods restart");

            String readme = read(platformDirectory().resolve("README.md"));
            assertTrue(readme.contains("load-images.sh"),
                    "the repository map has to name it beside deploy/k8s");

            String script = read(platformDirectory().resolve(LOAD_IMAGES));
            assertTrue(script.contains("31-secret.example.yaml"),
                    "the printed apply order has to keep the Secret template excluded, which is the"
                            + " order 00-namespace.yaml documents");
        }
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

    private static Path firstManifestStartingWith(String prefix) {
        Path directory = platformDirectory().resolve("deploy/k8s");
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
