package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that one allowlist of adaptive password encodings governs all six services, and that no
 * shipped artifact configures an encoding outside it.
 *
 * <p>Spring Security's stock delegating encoder maps ten encoding identifiers. Two of them are
 * adaptive; the rest exist so that a deployment holding legacy hashes can migrate off them, and
 * they include {@code noop}, which stores a password in plain text. A service built on the stock
 * encoder therefore authenticates successfully against a plaintext or unsalted-digest password, and
 * a start-up guard that only looks for a leading brace accepts one. That is the posture this class
 * exists to keep closed.
 *
 * <p>Each service declares the allowlist in its own {@code config/SecurityConfig}, because
 * {@code libs/cobol-compat} carries no framework type and {@code libs/event-contracts} carries
 * events. Six copies can drift, so this class reads all six as text and holds them to one set of
 * identifiers and one cost floor. Reading the text is also what makes the check cover a service
 * added later, which no test of today's beans could do.
 *
 * <p>The per-service {@code SecurityConfigTest.ApprovedPasswordEncodings} exercises the behaviour:
 * that a refused identifier stops start-up, that a bcrypt cost below the floor stops start-up, and
 * that the encoder throws rather than answering false for an encoding it does not map. This class
 * adds what those cannot see, which is that every service ships the same rule and that no
 * configuration file, workflow or test fixture on this platform still carries a refused encoding.
 *
 * <p>No failure message here quotes a hash. Each one names the artifact and the identifier.
 */
@DisplayName("Password encoding allowlist, every service and every shipped artifact")
class PasswordEncodingContractTest {

    /** Directory below the repository root holding the six service modules. */
    private static final String SERVICES_DIRECTORY = "card-platform/services";

    /** The six service module directory names. */
    private static final List<String> MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /** Path below a service module to its security configuration. */
    private static final String SECURITY_CONFIG =
            "src/main/java/com/carddemo/%s/config/SecurityConfig.java";

    /** Package leaf of each module, in the order of {@link #MODULES}. */
    private static final List<String> PACKAGE_LEAVES = List.of(
            "authorization", "ledger", "fraud", "notification", "account", "card");

    /**
     * Encoding identifiers a configured password may declare.
     *
     * <p>{@code bcrypt} and the 5.8 parameter set of {@code pbkdf2} are the two adaptive encodings
     * whose implementations need no dependency beyond the platform's own. {@code argon2} and
     * {@code scrypt} are adaptive too and are deliberately absent: both call Bouncy Castle, which
     * this platform does not depend on, so a password carrying either identifier would fail at the
     * first authentication rather than at start-up.
     */
    private static final List<String> APPROVED_IDENTIFIERS =
            List.of("bcrypt", "pbkdf2@SpringSecurity_v5_8");

    /** Lowest bcrypt cost every service must require. */
    private static final int BCRYPT_COST_FLOOR = 10;

    /**
     * Matches a password setting whose value declares an encoding outside the allowlist.
     *
     * <p>The expression anchors on the assignment rather than on the identifier alone. Every
     * artifact scanned below explains in prose which encodings are refused, and a document that
     * names {@code noop} is documentation; a setting that assigns it is a configured plaintext
     * password. Only the second form matches here.
     */
    private static final Pattern REFUSED_PASSWORD_SETTING = Pattern.compile(
            "(?i)password[A-Za-z_.\\[\\]0-9]*\\s*[=:]\\s*[\"\']?"
                    + "\\{(noop|md4|md5|sha-1|sha-256|sha256|ldap)}");

    /**
     * Artifacts that configure an identity password, relative to the repository root.
     *
     * <p>The list is every path a password reaches a running service through: the environment
     * template, the Compose stack, the two cluster manifests, the pipeline that generates the
     * values, and the six service configurations that read the variables.
     */
    private static final List<String> CONFIGURED_ARTIFACTS = List.of(
            "card-platform/.env.example",
            "card-platform/docker-compose.yml",
            "card-platform/deploy/k8s/30-configmap.yaml",
            "card-platform/deploy/k8s/31-secret.example.yaml",
            ".github/workflows/ci.yml");

    /**
     * This class's own file name, the one test source the fixture scan skips.
     *
     * <p>The scan below reads every test source for a password assigned under a refused encoding,
     * and the sample strings that prove the rule works are assignments of exactly that shape. A
     * rule cannot be its own subject, so the file that states it is excluded and
     * {@code theScanSeparatesAConfiguredRefusedEncodingFromProseAboutOne} is what covers it.
     */
    private static final String THIS_CLASS_FILE = "PasswordEncodingContractTest.java";

    /** Matches a line comment, stripped before a source is read as code. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Matches a block comment, stripped before a source is read as code. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    @DisplayName("no service builds its encoder from the stock factory")
    void noServiceBuildsItsEncoderFromTheStockFactory() {
        for (int index = 0; index < MODULES.size(); index++) {
            String module = MODULES.get(index);
            String code = withoutComments(readText(securityConfigOf(index)));

            assertFalse(code.contains("PasswordEncoderFactories"), module
                    + " builds its password encoder from Spring Security's stock factory, which"
                    + " maps noop and six legacy digests alongside the two adaptive encodings");
            assertFalse(code.contains("setDefaultPasswordEncoderForMatches"), module
                    + " installs a fallback encoder for an unmapped identifier, which re-admits"
                    + " every encoding the allowlist leaves out");
            assertTrue(code.contains("new DelegatingPasswordEncoder("), module
                    + " no longer builds a delegating encoder over a declared map, so the"
                    + " allowlist below governs nothing");
        }
    }

    @Test
    @DisplayName("all six services declare one allowlist and one bcrypt cost floor")
    void allSixServicesDeclareOneAllowlistAndOneBcryptCostFloor() {
        for (int index = 0; index < MODULES.size(); index++) {
            String module = MODULES.get(index);
            String code = collapse(withoutComments(readText(securityConfigOf(index))));

            assertTrue(code.contains("BCRYPT_ENCODING_ID = \"" + APPROVED_IDENTIFIERS.get(0) + "\""),
                    module + " names a different first encoding identifier, so a hash one service"
                            + " accepts is refused by another");
            assertTrue(code.contains("PBKDF2_ENCODING_ID = \"" + APPROVED_IDENTIFIERS.get(1) + "\""),
                    module + " names a different second encoding identifier. The suffix is part of"
                            + " the identifier, and pbkdf2 without it names the weaker set");
            assertTrue(code.contains("BCRYPT_MINIMUM_COST = " + BCRYPT_COST_FLOOR + ";"),
                    module + " requires a different bcrypt cost, so the same hash is fit to run"
                            + " with in one service and not in another");
            assertTrue(code.contains("APPROVED_PASSWORD_ENCODINGS = List.of(BCRYPT_ENCODING_ID,"
                            + " PBKDF2_ENCODING_ID)"),
                    module + " declares an allowlist that is not exactly the two adaptive"
                            + " encodings");
        }
    }

    @Test
    @DisplayName("every service maps both adaptive encodings and checks the declared one")
    void everyServiceMapsBothAdaptiveEncodingsAndChecksTheDeclaredOne() {
        for (int index = 0; index < MODULES.size(); index++) {
            String module = MODULES.get(index);
            String code = collapse(withoutComments(readText(securityConfigOf(index))));

            assertTrue(code.contains("approved.put(BCRYPT_ENCODING_ID,"
                            + " new BCryptPasswordEncoder(BCRYPT_MINIMUM_COST))"),
                    module + " no longer maps bcrypt at the cost its own floor names");
            assertTrue(code.contains("approved.put(PBKDF2_ENCODING_ID,"
                            + " Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8())"),
                    module + " no longer maps the 5.8 parameter set of pbkdf2");
            assertTrue(code.contains("requireApprovedPasswordEncoding(value, property)"),
                    module + " no longer routes a configured identity password through the"
                            + " allowlist check, so a refused encoding would reach the first"
                            + " authentication instead of stopping start-up");
        }
    }

    @Test
    @DisplayName("no shipped artifact configures a refused encoding")
    void noShippedArtifactConfiguresARefusedEncoding() {
        List<String> artifacts = new ArrayList<>(CONFIGURED_ARTIFACTS);
        for (int index = 0; index < MODULES.size(); index++) {
            artifacts.add(SERVICES_DIRECTORY + "/" + MODULES.get(index)
                    + "/src/main/resources/application.yml");
        }

        for (String artifact : artifacts) {
            Matcher refused = REFUSED_PASSWORD_SETTING.matcher(readText(
                    repositoryRoot().resolve(artifact)));
            boolean carriesRefusedEncoding = refused.find();
            String declared = carriesRefusedEncoding ? refused.group(1) : "";
            assertFalse(carriesRefusedEncoding, artifact + " configures an identity password under"
                    + " {" + declared + "}, an encoding every service refuses at start-up");
        }
    }

    @Test
    @DisplayName("no test fixture configures a plaintext or legacy identity password")
    void noTestFixtureConfiguresAPlaintextOrLegacyIdentityPassword() {
        List<Path> offenders = new ArrayList<>();
        for (Path source : everyTestSource()) {
            if (source.getFileName().toString().equals(THIS_CLASS_FILE)) {
                continue;
            }
            if (REFUSED_PASSWORD_SETTING.matcher(withoutComments(readText(source))).find()) {
                offenders.add(source);
            }
        }

        assertEquals(List.of(), offenders, "these test sources configure an identity password under"
                + " an encoding the services refuse. A test that shortcuts the encoder no longer"
                + " exercises the verification path a deployment exercises, and it keeps the"
                + " refused encoding alive in the repository: " + offenders);
    }

    @Test
    @DisplayName("the scan separates a configured refused encoding from prose about one")
    void theScanSeparatesAConfiguredRefusedEncodingFromProseAboutOne() {
        assertAll(
                () -> assertTrue(carriesRefusedSetting(
                                "\"ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password\""),
                        "an environment assignment inside a test property is the form the sweep"
                                + " removed, so the scan has to match it"),
                () -> assertTrue(carriesRefusedSetting(
                                "carddemo.security.users[0].password={noop}a-value"),
                        "the indexed property form reaches the same identity list"),
                () -> assertTrue(carriesRefusedSetting("  ADMIN_PASSWORD_HASH: \"{MD5}0123\""),
                        "a YAML mapping is how a ConfigMap or a Secret template would carry one"),
                () -> assertFalse(carriesRefusedSetting(
                                "{noop} stores a password in plain text and is refused"),
                        "prose naming the identifier is documentation, and every artifact scanned"
                                + " above explains which encodings are refused"),
                () -> assertFalse(carriesRefusedSetting(
                                "the refused identifiers are {noop}, {MD5} and {ldap}"),
                        "a list of identifiers in prose is documentation too"),
                () -> assertFalse(carriesRefusedSetting(
                                "ADMIN_PASSWORD_HASH='{bcrypt}$2a$10$0123456789'"),
                        "an approved encoding is not a finding, however it is quoted"));
    }

    /**
     * Applies {@link #REFUSED_PASSWORD_SETTING} to one line, so the test above reads as the rule.
     *
     * @param text the text to scan
     * @return whether the text assigns a password under a refused encoding
     */
    private static boolean carriesRefusedSetting(String text) {
        return REFUSED_PASSWORD_SETTING.matcher(text).find();
    }

    /**
     * Returns the security configuration of one module.
     *
     * @param index the position in {@link #MODULES}
     * @return the path to that module's {@code SecurityConfig.java}
     */
    private static Path securityConfigOf(int index) {
        return repositoryRoot().resolve(SERVICES_DIRECTORY).resolve(MODULES.get(index))
                .resolve(String.format(SECURITY_CONFIG, PACKAGE_LEAVES.get(index)));
    }

    /**
     * Lists every test source of every module and of this module.
     *
     * @return every {@code .java} file below a {@code src/test/java} root under
     *         {@code card-platform}
     */
    private static List<Path> everyTestSource() {
        Path platform = repositoryRoot().resolve("card-platform");
        try (Stream<Path> entries = Files.walk(platform)) {
            return entries
                    .filter(path -> path.toString().contains("/src/test/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + platform, unreadable);
        }
    }

    /**
     * Returns one source with its comments removed, so a mention in prose is not a use.
     *
     * @param source the source text
     * @return the same text with block and line comments removed
     */
    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    /**
     * Collapses whitespace runs to one space, so a declaration wrapped across lines reads as one.
     *
     * @param text the text to collapse
     * @return the collapsed and trimmed text
     */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Reads a file as text, turning the checked failure into an unchecked one.
     *
     * @param path the file to read
     * @return its contents
     */
    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /**
     * Returns the repository root, being the ancestor of the fixture directory.
     *
     * @return that directory
     */
    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent();
    }
}
