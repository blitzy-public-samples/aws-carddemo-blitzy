package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the two controls that keep cardholder data out of what this build publishes.
 *
 * <h2>The secret-scan allowlist</h2>
 *
 * <p>A card token is sixty-four lowercase hexadecimal characters, and so is a 256-bit secret written
 * as hex. The allowlist of {@code card-platform/.gitleaks.toml} used to exempt that shape
 * everywhere, in the working tree and in the history, which meant an API token, a signing key or a
 * session secret of the same shape could be committed and the scan would report nothing. This class
 * holds the entry to a path scope as well as a shape, and holds that path scope to the files that
 * actually carry a token — measured off disk, so putting a token in a new file fails the build until
 * the file is named or the token is moved.</p>
 *
 * <h2>The report artifacts</h2>
 *
 * <p>Every Surefire and Failsafe report is uploaded and kept, so whatever a test wrote to standard
 * output is readable for a fortnight by everyone who can read the run. A review found one seeded card
 * number in one report, and the way it arrived is the general case: a PostgreSQL constraint violation
 * quotes the whole failing row in its detail, and the framework's own logger renders that message.
 * Two controls answer it, and this class holds both. The services turn that logger off, and
 * {@code scripts/redact-report-artifacts.sh} masks the seeded values in the reports and then fails
 * the run while any survive.</p>
 *
 * <p>Neither control is asserted by reading a document. The allowlist is parsed, the path scope is
 * compared with a measurement of the tree, the workflow is read for the step and its position, and
 * the script is read for the properties that make it fail rather than pass.</p>
 */
@DisplayName("Cardholder data in the artifacts this build publishes")
class CardholderDataInArtifactsContractTest {

    /** The scanner configuration, which is the allowlist both scans read. */
    private static final String SCANNER_CONFIGURATION = ".gitleaks.toml";

    /** The script that masks the seeded values in the reports. */
    private static final String REDACTION_SCRIPT = "scripts/redact-report-artifacts.sh";

    /**
     * The logger that renders the server's message for a failed statement.
     *
     * <p>This is the Hibernate 7 name. Under Hibernate 5 and 6 the same message came from
     * {@code org.hibernate.engine.jdbc.spi.SqlExceptionHelper}, and naming that one here suppresses
     * nothing — which was measured: a seeded card number reached a published report with the old
     * name configured, and the report carried {@code "logger_name":"org.hibernate.orm.jdbc.error"}
     * on the line that published it.</p>
     */
    private static final String FAILED_STATEMENT_LOGGER = "org.hibernate.orm.jdbc.error";

    /** The workflow both controls are wired into. */
    private static final String WORKFLOW = ".github/workflows/ci.yml";

    /** Opening line of one rule-scoped allowlist entry. */
    private static final String ENTRY_HEADER = "[[rules.allowlists]]";

    /** Opening line of one rule patch. */
    private static final String RULE_HEADER = "[[rules]]";

    /** Opening line of a top-level allowlist, which is the form that skips whole files. */
    private static final String GLOBAL_ENTRY_HEADER = "[[allowlists]]";

    /** The shape of a card token, and of a 256-bit secret written as hexadecimal. */
    private static final Pattern SIXTY_FOUR_HEX = Pattern.compile("[0-9a-f]{64}");

    /** A candidate value of that shape, used to test one allowlist regex against it. */
    private static final String CANDIDATE_TOKEN =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    // The two synthetic secrets below are assembled from fragments rather than written whole. Each
    // is the shape of a real credential, and a literal of either shape in this source would be
    // reported by the scan this class exists to keep honest — correctly, since the scan cannot know
    // a secret is synthetic. Splitting the prefix from the body leaves no matchable value on any
    // line of this file while the assembled constants still exercise the rules exactly.

    /** A GitHub personal access token shape, assembled so no line here carries one. */
    private static final String PLANTED_ACCESS_TOKEN =
            "ghp" + "_" + "16C7e42F292c6912E7710c838347Ae178B4a";

    /** The opening bytes of a real PKCS#12 keystore in base64, assembled for the same reason. */
    private static final String PLANTED_KEYSTORE =
            "MIIKvgIBAzCC" + "CnoGCSqGSIb3DQEHAaCCCmsEggpn";

    /** The field a Kubernetes Secret holds a keystore in. */
    private static final String KEYSTORE_FIELD = "keystore" + ".p12: ";

    /** Contexts in which a 64-hex value is a content digest rather than a card token. */
    private static final Pattern DIGEST_CONTEXT =
            Pattern.compile("@sha256:|sha256sum|digest=|_sha256,");

    /** Directories the census skips: build output carries no committed value. */
    private static final String BUILD_OUTPUT = "/target/";

    /** The platform root, found by walking up to the directory holding the scanner file. */
    private static final Path PLATFORM_ROOT = platformRoot();

    /** Text of the scanner configuration. */
    private static final String CONFIGURATION = read(PLATFORM_ROOT.resolve(SCANNER_CONFIGURATION));

    /** Text of the workflow. */
    private static final String WORKFLOW_TEXT =
            read(PLATFORM_ROOT.getParent().resolve(WORKFLOW));

    /** Returns the platform root. */
    private static Path platformRoot() {
        Path base = Path.of("").toAbsolutePath().normalize();
        while (base != null && !Files.isRegularFile(base.resolve(SCANNER_CONFIGURATION))) {
            base = base.getParent();
        }
        if (base == null) {
            throw new AssertionError("card-platform/" + SCANNER_CONFIGURATION + " was not found");
        }
        return base;
    }

    /** Reads one file, or fails when it is absent. */
    private static String read(Path file) {
        assertTrue(Files.isRegularFile(file), () -> file + " must exist");
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }

    /**
     * One allowlist entry of the scanner configuration.
     *
     * @param body        the whole entry, for a failure message
     * @param condition   the value of {@code condition}, or the empty string when it names none
     * @param regexTarget the value of {@code regexTarget}, or the empty string
     * @param regexes     the patterns the entry matches with
     * @param paths       the path patterns the entry is scoped to
     */
    private record AllowlistEntry(String body, String condition, String regexTarget,
            List<String> regexes, List<String> paths) {

        /** Reports whether this entry exempts a plain 64-hex value by its shape alone. */
        boolean matchesACardTokenByShape() {
            return "match".equals(regexTarget)
                    && regexes.stream().anyMatch(CardholderDataInArtifactsContractTest::admitsAToken);
        }
    }

    /** Reports whether one allowlist pattern matches a bare 64-hex value. */
    private static boolean admitsAToken(String pattern) {
        try {
            return Pattern.compile(pattern).matcher(CANDIDATE_TOKEN).find();
        } catch (RuntimeException notAPattern) {
            return false;
        }
    }

    /**
     * Returns every allowlist entry the configuration declares, in file order.
     *
     * <p>A header is recognized only as a whole line. The prose at the top of the configuration
     * names both table headers while explaining the difference between them, and a scan for the
     * text alone reads that sentence as an entry with no patterns in it.</p>
     */
    private static List<AllowlistEntry> allowlistEntries() {
        List<Integer> entryStarts = lineAnchoredOffsets(ENTRY_HEADER);
        List<Integer> ruleStarts = lineAnchoredOffsets(RULE_HEADER);
        List<AllowlistEntry> entries = new ArrayList<>();
        for (int index = 0; index < entryStarts.size(); index++) {
            int at = entryStarts.get(index);
            int end = index + 1 < entryStarts.size()
                    ? entryStarts.get(index + 1)
                    : CONFIGURATION.length();
            for (int ruleStart : ruleStarts) {
                if (ruleStart > at && ruleStart < end) {
                    end = ruleStart;
                }
            }
            String body = CONFIGURATION.substring(at, end);
            entries.add(new AllowlistEntry(body, scalar(body, "condition"),
                    scalar(body, "regexTarget"), listOf(body, "regexes"), listOf(body, "paths")));
        }
        return List.copyOf(entries);
    }

    /** Returns the offset of every line that is exactly the given table header. */
    private static List<Integer> lineAnchoredOffsets(String header) {
        List<Integer> offsets = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(header) + "[ \\t]*$")
                .matcher(CONFIGURATION);
        while (matcher.find()) {
            offsets.add(matcher.start());
        }
        return List.copyOf(offsets);
    }

    /** Returns the rule identifiers the configuration patches, in file order. */
    private static List<String> patchedRuleIds() {
        List<String> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^\\[\\[rules]]\\s*\\nid\\s*=\\s*\"([^\"]+)\"")
                .matcher(CONFIGURATION);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return List.copyOf(ids);
    }

    /**
     * Reports whether the configuration would exempt one synthetic finding.
     *
     * <p>This is the oracle that makes the negative cases below testable without the scanner
     * installed. It reproduces what gitleaks does with an allowlist that names no path: each entry
     * tests its patterns against either the matched text or the whole line, and the finding is
     * exempt when any pattern of any entry hits.</p>
     *
     * @param match the text the rule matched
     * @param line  the line the match sits on
     * @return whether some entry exempts it
     */
    private static boolean isExempt(String match, String line) {
        for (AllowlistEntry entry : allowlistEntries()) {
            String subject = "line".equals(entry.regexTarget()) ? line : match;
            for (String pattern : entry.regexes()) {
                if (Pattern.compile(pattern).matcher(subject).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns one quoted scalar of an entry, or the empty string. */
    private static String scalar(String body, String key) {
        Matcher matcher =
                Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]*)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * Returns the values of one key of an entry, whether it is written inline or one per line.
     *
     * <p>The closing bracket cannot be found by searching for it, because a pattern contains
     * brackets of its own: {@code [0-9a-f]{64}} closes one before the list ends. So the scan walks
     * the declaration, collects each triple-quoted literal, and stops at the first bracket that sits
     * outside a literal. Triple quoting is the file's own convention, because a pattern is full of
     * backslashes.</p>
     */
    private static List<String> listOf(String body, String key) {
        Matcher declaration = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\\[").matcher(body);
        if (!declaration.find()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        int at = declaration.end();
        while (at < body.length()) {
            int opening = body.indexOf("'''", at);
            int closingBracket = body.indexOf(']', at);
            if (closingBracket >= 0 && (opening < 0 || closingBracket < opening)) {
                break;
            }
            if (opening < 0) {
                break;
            }
            int closing = body.indexOf("'''", opening + 3);
            if (closing < 0) {
                break;
            }
            values.add(body.substring(opening + 3, closing));
            at = closing + 3;
        }
        return List.copyOf(values);
    }

    /**
     * Returns every file the repository delivers, which is what a scan of the working tree reads.
     *
     * <p>The list comes from git rather than from a directory walk, because a walk also finds build
     * output and the ignored {@code .env} of a configured clone. Neither is delivered, and the
     * second holds real generated credentials, so a walk would measure this repository as carrying
     * secrets it does not ship.</p>
     */
    private static List<Path> deliveredFiles() {
        Path repository = PLATFORM_ROOT.getParent();
        List<Path> files = new ArrayList<>();
        for (String line : git(repository, "ls-files", "--cached", "--others",
                "--exclude-standard").split("\\R")) {
            if (line.isBlank() || line.contains(BUILD_OUTPUT)) {
                continue;
            }
            if (!line.startsWith("card-platform/") && !line.startsWith(".github/")) {
                continue;
            }
            Path file = repository.resolve(line);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        assertThat(files).as("git has to list the delivered files for this measurement to mean"
                + " anything").isNotEmpty();
        return files;
    }

    /** Runs one git command in the repository and returns its output. */
    private static String git(Path repository, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), () -> command + " failed: " + output);
            return output;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot run " + command, unreadable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running " + command, interrupted);
        }
    }

    /**
     * Returns the delivered files carrying a card token, and those carrying a content digest.
     *
     * @return a map of the two kinds to the repository-relative paths that carry them
     */
    private static Map<String, Set<String>> filesCarryingSixtyFourHex() {
        Path repository = PLATFORM_ROOT.getParent();
        Set<String> tokens = new LinkedHashSet<>();
        Set<String> digests = new LinkedHashSet<>();
        for (Path file : deliveredFiles()) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            for (String line : text.split("\\R")) {
                if (!SIXTY_FOUR_HEX.matcher(line).find()) {
                    continue;
                }
                String relative = repository.relativize(file).toString();
                if (DIGEST_CONTEXT.matcher(line).find()) {
                    digests.add(relative);
                } else {
                    tokens.add(relative);
                }
            }
        }
        Map<String, Set<String>> carriers = new LinkedHashMap<>();
        carriers.put("token", tokens);
        carriers.put("digest", digests);
        return carriers;
    }

    @Nested
    @DisplayName("The secret-scan allowlist")
    class TheAllowlist {
        @Test
        @DisplayName("attaches every exemption to one rule rather than to the whole scan")
        void attachesEveryExemptionToOneRule() {
            assertThat(lineAnchoredOffsets(GLOBAL_ENTRY_HEADER))
                    .as("a top-level [[allowlists]] entry applies to every rule at once. Where it"
                            + " names a path, gitleaks skips that file before scanning it, so a"
                            + " GitHub token, a cloud key or a private key in the same file is never"
                            + " reported. Measured against gitleaks 8.30.1: a real-shaped personal"
                            + " access token in an allowlisted file was invisible under a top-level"
                            + " entry and reported under a rule-scoped one")
                    .isEmpty();
            assertThat(patchedRuleIds())
                    .as("the two default rules that report anything in this repository, and no"
                            + " others. Scanned with no allowlist the working tree yields findings"
                            + " from exactly these two")
                    .containsExactly("generic-api-key", "curl-auth-user");
            assertThat(allowlistEntries())
                    .as("every entry sits under one of those rules")
                    .isNotEmpty()
                    .allSatisfy(entry -> assertThat(entry.regexes())
                            .as("and every entry names what it exempts: an entry with no pattern"
                                    + " exempts whatever its other criteria select")
                            .isNotEmpty());
        }

        @Test
        @DisplayName("scopes every exemption by the text of the value, never by where it sits")
        void scopesEveryExemptionByTheTextOfTheValue() {
            List<AllowlistEntry> pathScoped = allowlistEntries().stream()
                    .filter(entry -> !entry.paths().isEmpty())
                    .toList();

            assertThat(pathScoped)
                    .as("a path-scoped allowlist makes gitleaks skip the whole file, and no value"
                            + " here needs that: each one carries a constant name, a JSON field or"
                            + " placeholder wording that identifies it wherever it appears. An"
                            + " entry that names a path exempts every future secret in that file"
                            + " too, which is the hole this file was reviewed for")
                    .isEmpty();
        }

        @Test
        @DisplayName("exempts no value by the card-token shape alone")
        void exemptsNoValueByTheCardTokenShapeAlone() {
            for (AllowlistEntry entry : allowlistEntries()) {
                for (String pattern : entry.regexes()) {
                    assertFalse(admitsAToken(pattern),
                            "pattern " + pattern + " of this entry matches a bare 64-hex value."
                                    + " Sixty-four lowercase hexadecimal characters is a card token"
                                    + " and equally a 256-bit key written as hex, so the shape on"
                                    + " its own cannot be the exemption: " + entry.body());
                }
            }
        }

        @Test
        @DisplayName("exempts each value this repository ships on purpose")
        void exemptsEachValueThisRepositoryShipsOnPurpose() {
            record Case(String description, String match, String line) { }

            for (Case exempt : List.of(
                    new Case("a card token assigned to a named constant",
                            "CARD_TOKEN = \"" + CANDIDATE_TOKEN + "\"",
                            "    private static final String CARD_TOKEN = \"" + CANDIDATE_TOKEN
                                    + "\";"),
                    new Case("a card token under an unmatched-token constant",
                            "UNMATCHED_CARD_TOKEN = \"" + CANDIDATE_TOKEN + "\"",
                            "    static final String UNMATCHED_CARD_TOKEN = \"" + CANDIDATE_TOKEN
                                    + "\";"),
                    new Case("a card token under a cardToken field of a JSON payload",
                            "cardToken\": \"" + CANDIDATE_TOKEN + "\"",
                            "              \"cardToken\": \"" + CANDIDATE_TOKEN + "\","),
                    new Case("a card token as a cardToken path variable",
                            "cardToken\", \"" + CANDIDATE_TOKEN + "\"",
                            "        pathContext(\"cardToken\", \"" + CANDIDATE_TOKEN + "\")"),
                    new Case("a card token built by repeating a short literal",
                            "\"a1b2c3d4e5f60718\".repeat(4)",
                            "    private static final String T = \"a1b2c3d4e5f60718\".repeat(4);"),
                    new Case("the two card tokens the history carries",
                            "CARD_TOKEN=" + CANDIDATE_TOKEN,
                            "CARD_TOKEN=" + CANDIDATE_TOKEN),
                    new Case("a version 4 identifier",
                            "\"5f1c0d3e-2b48-4a19-9c7e-0d3f8b6a2c14\"",
                            "        cardUpdated(KEY, \"5f1c0d3e-2b48-4a19-9c7e-0d3f8b6a2c14\")"),
                    new Case("a generated-password placeholder",
                            "LEDGER_DB_PASSWORD: \"REPLACE-WITH-A-GENERATED-PASSWORD-FOR-LEDGER\"",
                            "  LEDGER_DB_PASSWORD: \"REPLACE-WITH-A-GENERATED-PASSWORD-FOR-LEDGER\""),
                    new Case("a base64-encoded keystore placeholder",
                            KEYSTORE_FIELD + "UkVQTEFDRS1XSVRILVRIRS1BVVRIT1JJWkFUSU9O",
                            "  " + KEYSTORE_FIELD + "UkVQTEFDRS1XSVRILVRIRS1BVVRIT1JJWkFUSU9O"),
                    new Case("the build-scope card-token key",
                            "carddemo-build-scope-card-token-key-tests-only",
                            "<carddemo.card-token.secret>carddemo-build-scope-card-token-key-tests-"
                                    + "only</carddemo.card-token.secret>"),
                    new Case("a PanMasker test key",
                            "A_KEY = \"panmasker-test-key-one-0123456789\"",
                            "    private static final String A_KEY = \"panmasker-test-key-one-"
                                    + "0123456789\";"),
                    new Case("the reviewed sentence read as a key and a value",
                            "secret scanning, dependency/advisory",
                            "     * missing secret scanning, dependency/advisory review and a"),
                    new Case("a curl example naming the substitution to make",
                            "-u \"admin001:the password you chose\"",
                            "curl -fsS -u \"admin001:the password you chose\" \\"))) {
                assertTrue(isExempt(exempt.match(), exempt.line()),
                        exempt.description() + " is a value this repository ships on purpose and no"
                                + " entry exempts it, so the scan fails on every run and gets"
                                + " switched off");
            }
        }

        @Test
        @DisplayName("exempts no secret that merely resembles one of them")
        void exemptsNoSecretThatMerelyResemblesOneOfThem() {
            record Case(String description, String match, String line) { }

            for (Case reported : List.of(
                    new Case("a 64-hex value under an unrelated key name",
                            "api_key=\"" + CANDIDATE_TOKEN + "\"",
                            "api_key=\"" + CANDIDATE_TOKEN + "\""),
                    new Case("the HMAC key every card token is derived with",
                            "CARD_TOKEN_SECRET=" + CANDIDATE_TOKEN,
                            "CARD_TOKEN_SECRET=" + CANDIDATE_TOKEN),
                    new Case("that same key quoted as a constant",
                            "CARD_TOKEN_SECRET = \"" + CANDIDATE_TOKEN + "\"",
                            "  static final String CARD_TOKEN_SECRET = \"" + CANDIDATE_TOKEN
                                    + "\";"),
                    new Case("a signing key of the same shape",
                            "signingKey = \"" + CANDIDATE_TOKEN + "\"",
                            "    var signingKey = \"" + CANDIDATE_TOKEN + "\";"),
                    new Case("a session secret of the same shape",
                            "session_secret: " + CANDIDATE_TOKEN,
                            "  session_secret: " + CANDIDATE_TOKEN),
                    new Case("a GitHub personal access token",
                            PLANTED_ACCESS_TOKEN, "github_pat=" + PLANTED_ACCESS_TOKEN),
                    new Case("a real keystore encoded into the placeholder's field",
                            KEYSTORE_FIELD + PLANTED_KEYSTORE,
                            "  " + KEYSTORE_FIELD + PLANTED_KEYSTORE),
                    new Case("a real password where a placeholder used to be",
                            "LEDGER_DB_PASSWORD: \"" + CANDIDATE_TOKEN + "\"",
                            "  LEDGER_DB_PASSWORD: \"" + CANDIDATE_TOKEN + "\""),
                    new Case("a token differing from a card token only in case",
                            "CARD_TOKEN = \"" + CANDIDATE_TOKEN.toUpperCase(java.util.Locale.ROOT)
                                    + "\"",
                            "  static final String CARD_TOKEN = \""
                                    + CANDIDATE_TOKEN.toUpperCase(java.util.Locale.ROOT) + "\";"))) {
                assertFalse(isExempt(reported.match(), reported.line()),
                        reported.description() + " is exempt, and it is not a value this repository"
                                + " ships. Every exemption has to name what makes its value safe,"
                                + " not a shape a secret shares with it");
            }
        }

        /**
         * Runs the real scanner over a small planted tree and requires it to report the secret and
         * not the card token beside it.
         *
         * <p>This is the one property the workflow's own secret-scan stage cannot demonstrate. That
         * stage scans this repository and passes, which shows the exemptions are wide enough; it
         * cannot show they are narrow enough, because a secret it fails to report is a secret that
         * is not there to report. Planting one is the only way to see the difference, and the
         * difference is exactly what a review found: under the earlier configuration this same
         * planted token was invisible.</p>
         *
         * <p>The repository-wide scans are deliberately not repeated here. The workflow runs both
         * modes against this same configuration with {@code --exit-code 1}, and running them again
         * in the test suite adds about fifty seconds to every build to reach the same verdict.</p>
         */
        @Test
        @DisplayName("still reports a planted secret sitting beside an exempt card token")
        void stillReportsAPlantedSecretBesideAnExemptCardToken() throws IOException,
                InterruptedException {
            assumeTrue(scannerOnPath(), "gitleaks is not installed on this machine");
            Path configuration = PLATFORM_ROOT.resolve(SCANNER_CONFIGURATION);

            Path planted = Files.createTempDirectory("blitzy_adhoc_test_scan");
            try {
                Files.writeString(planted.resolve("ExemptOnlyTest.java"),
                        "class ExemptOnlyTest {\n"
                                + "  static final String CARD_TOKEN = \"" + CANDIDATE_TOKEN
                                + "\";\n}\n");
                assertEquals(0, scan("dir", planted, configuration),
                        "a card token named as one is reported, so the scan fails on files this"
                                + " repository legitimately ships");

                Files.writeString(planted.resolve("PlantedTest.java"),
                        "class PlantedTest {\n"
                                + "  static final String CARD_TOKEN = \"" + CANDIDATE_TOKEN
                                + "\";\n"
                                + "  static final String LEAK = \"" + PLANTED_ACCESS_TOKEN
                                + "\";\n}\n");
                assertEquals(1, scan("dir", planted, configuration),
                        "a GitHub token beside an exempt card token went unreported. That is the"
                                + " whole-file skip a path-scoped allowlist causes, and it is what"
                                + " this configuration is shaped to avoid");
            } finally {
                try (Stream<Path> walk = Files.walk(planted)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // A leftover temp directory is not worth failing a passing test over.
                        }
                    });
                }
            }
        }
    }

    @Nested
    @DisplayName("The report redaction")
    class TheRedaction {

        @Test
        @DisplayName("runs ahead of every report upload, on a failed run as well as a passing one")
        void runsAheadOfEveryReportUpload() {
            List<String> uploads = List.of("name: unit-test-reports",
                    "name: integration-test-reports", "name: equivalence-test-reports");

            assertEquals(3, occurrences(WORKFLOW_TEXT, "run: " + REDACTION_SCRIPT),
                    "the three stages that produce reports each run the redaction");
            for (String upload : uploads) {
                int uploadAt = WORKFLOW_TEXT.indexOf(upload);
                assertTrue(uploadAt > 0, "the workflow has to upload " + upload);
                int redactionAt = WORKFLOW_TEXT.lastIndexOf("run: " + REDACTION_SCRIPT, uploadAt);
                assertTrue(redactionAt > 0,
                        "the redaction has to run before " + upload + ", not after it. An artifact"
                                + " is published the moment it is uploaded");
            }
            assertTrue(WORKFLOW_TEXT.contains("- name: Redact cardholder data out of the reports\n"
                            + "        if: always()"),
                    "the redaction runs on a failed run too, because the reports of a failed run"
                            + " are the ones carrying test output");
        }

        @Test
        @DisplayName("is a script that exists, can be run, and fails rather than warning")
        void isAScriptThatExistsAndFails() {
            Path script = PLATFORM_ROOT.resolve(REDACTION_SCRIPT);
            String text = read(script);

            assertTrue(Files.isExecutable(script), script + " has to be executable");
            assertTrue(text.startsWith("#!/usr/bin/env bash"),
                    "the workflow runs it directly, so it names its interpreter");
            assertTrue(text.contains("set -o errexit"), "an unchecked step is a step that passes");
            assertTrue(text.contains("raise SystemExit(1)"),
                    "a survivor fails the run rather than printing a warning nobody reads");
            assertTrue(text.contains("app/data/ASCII") || text.contains("fixtures"),
                    "the seeded values are read out of the fixtures at scan time");
        }

        @Test
        @DisplayName("carries no cardholder value of its own")
        void carriesNoCardholderValueOfItsOwn() {
            String text = read(PLATFORM_ROOT.resolve(REDACTION_SCRIPT));

            assertThat(seededCardNumbers())
                    .as("the fixtures have to yield the values this assertion looks for")
                    .isNotEmpty();
            for (String seeded : seededCardNumbers()) {
                assertFalse(text.contains(seeded),
                        "a scanner that carried the list would be the leak it looks for");
            }
            assertFalse(Pattern.compile("[0-9]{13,}").matcher(text).find(),
                    "and it carries no long digit run of any kind");
        }

        @Test
        @DisplayName("is the control the services rely on, and every service turns the logger off")
        void everyServiceTurnsTheFailingRowLoggerOff() {
            Path services = PLATFORM_ROOT.resolve("services");
            List<String> silent = new ArrayList<>();
            List<String> loud = new ArrayList<>();
            try (Stream<Path> walk = Files.list(services)) {
                for (Path service : walk.filter(Files::isDirectory).sorted().toList()) {
                    Path configuration =
                            service.resolve("src/main/resources/application.yml");
                    if (!Files.isRegularFile(configuration)) {
                        continue;
                    }
                    String text = read(configuration);
                    if (text.contains(FAILED_STATEMENT_LOGGER + ": \"OFF\"")) {
                        silent.add(service.getFileName().toString());
                    } else {
                        loud.add(service.getFileName().toString());
                    }
                    assertFalse(
                            text.contains(FAILED_STATEMENT_LOGGER + ": OFF")
                                    && !text.contains(FAILED_STATEMENT_LOGGER + ": \"OFF\""),
                            () -> "the level of " + service.getFileName() + " is written unquoted,"
                                    + " and YAML reads a bare OFF as the boolean false. Spring then"
                                    + " binds the string \"false\" as a log level and the service"
                                    + " refuses to start. The quotes are the fix");
                    assertThat(settingLines(text))
                            .as("%s configures the Hibernate 5 and 6 logger. Under Hibernate 7 the"
                                    + " failed-statement message is written by %s, so the old name"
                                    + " suppresses nothing and the row still reaches the report."
                                    + " A comment may name it as a warning; a setting may not",
                                    service.getFileName(), FAILED_STATEMENT_LOGGER)
                            .noneMatch(line -> line.contains("SqlExceptionHelper"));
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot list " + services, unreadable);
            }

            String equivalenceLogging =
                    read(PLATFORM_ROOT.resolve("equivalence-tests/src/test/resources/"
                            + "logback-test.xml"));
            if (equivalenceLogging.contains("name=\"" + FAILED_STATEMENT_LOGGER + "\"")
                    && equivalenceLogging.contains("level=\"OFF\"")) {
                silent.add("equivalence-tests");
            } else {
                loud.add("equivalence-tests");
            }

            assertThat(silent)
                    .as("every module that opens a database suppresses the failed-statement logger:"
                            + " the six services, and the equivalence module, which maps the"
                            + " entities of all six and provokes a refused write on purpose")
                    .hasSize(7);
            assertThat(loud)
                    .as("this logger renders the server's own message, and PostgreSQL puts the"
                            + " whole failing row in the detail of a constraint violation. On a"
                            + " table holding a card number, a verification value or a social"
                            + " security number, one violation publishes all of them")
                    .isEmpty();
        }
    }

    /** Returns the card numbers the two card fixtures carry, read by offset. */
    private static Set<String> seededCardNumbers() {
        Path fixtures = PLATFORM_ROOT.getParent().resolve("app/data/ASCII");
        Set<String> numbers = new LinkedHashSet<>();
        for (String fixture : List.of("carddata.txt", "cardxref.txt")) {
            for (String record : read(fixtures.resolve(fixture)).split("\\R")) {
                if (record.length() >= 16 && record.substring(0, 16).chars()
                        .allMatch(Character::isDigit)) {
                    numbers.add(record.substring(0, 16));
                }
            }
        }
        return numbers;
    }

    /**
     * Returns the lines of a configuration that set something, with the commentary removed.
     *
     * <p>A comment naming a logger is a warning to the next reader; a setting naming it is the
     * configuration. Only the second decides behaviour, so only the second is asserted on.</p>
     *
     * @param text the whole configuration
     * @return its non-comment, non-blank lines
     */
    private static List<String> settingLines(String text) {
        return Stream.of(text.split("\\R"))
                .filter(line -> !line.isBlank())
                .filter(line -> !line.stripLeading().startsWith("#"))
                .toList();
    }

    /** Reports whether the gitleaks binary is on the path. */
    private static boolean scannerOnPath() {
        try {
            return new ProcessBuilder("gitleaks", "version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0;
        } catch (IOException absent) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs one gitleaks scan and returns its exit status.
     *
     * @param mode          {@code dir} for the working tree or {@code git} for the history
     * @param target        the directory to scan
     * @param configuration the configuration to scan with
     * @return 0 when the scan found nothing, 1 when it found something
     */
    private static int scan(String mode, Path target, Path configuration)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("gitleaks", mode, ".",
                "--config", configuration.toString(), "--no-banner", "--redact", "--exit-code", "1")
                .directory(target.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    /** Counts the occurrences of one phrase. */
    private static int occurrences(String text, String phrase) {
        int count = 0;
        int at = text.indexOf(phrase);
        while (at >= 0) {
            count++;
            at = text.indexOf(phrase, at + phrase.length());
        }
        return count;
    }
}
