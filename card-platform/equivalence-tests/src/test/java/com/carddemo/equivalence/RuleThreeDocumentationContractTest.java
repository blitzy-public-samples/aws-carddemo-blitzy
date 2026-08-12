package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds the Rule 3 onboarding inventory and both repository entry points against the delivered
 * platform.
 *
 * <p>The root guide remains an additive edit over the legacy mainframe guide. The platform and
 * service guides must describe live routes, event consumers, exact tool versions, and resolvable
 * documentation links.</p>
 */
class RuleThreeDocumentationContractTest {

    /** Hash of the LF-normalized 324-line root guide before the additive section. */
    private static final String ORIGINAL_ROOT_README_SHA256 =
            "9d76bfc9ed5a6639e9f09e64646b82f1549d08ee172f33588a2ac62bf624280c";

    /** The one table-of-contents line added to the existing root guide. */
    private static final String ROOT_TOC_ADDITION =
            "- [Modernized card platform](#modernized-card-platform)\n";

    /** Markdown links, excluding an optional fragment during file resolution. */
    private static final Pattern LINK = Pattern.compile("\\[[^]]+\\]\\(([^)]+)\\)");

    /** The header {@code config/CrossSiteRequestFilter} requires of every state-changing request. */
    private static final String CROSS_SITE_HEADER = "X-CardDemo-Request";

    /** The {@code curl} flags naming a method that changes state. */
    private static final List<String> WRITE_METHODS =
            List.of("-X POST", "-X PUT", "-X PATCH", "-X DELETE");

    /** A run of exactly sixty-four hexadecimal characters, which is the shape of a card token. */
    private static final Pattern SIXTY_FOUR_HEX_RUN =
            Pattern.compile("(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])");

    /** The six independently deployable service module directories. */
    private static final List<String> SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service",
            "card-service");

    /** Rule 3 headings every service guide carries. */
    private static final List<String> SERVICE_SECTIONS = List.of(
            "## Purpose",
            "## Source provenance",
            "## Endpoints",
            "## Events",
            "## Domain and data ownership",
            "## Pitfalls",
            "## Architecture",
            "## How to extend",
            "## Local run and tests",
            "## Related documentation");

    @Test
    void theNineRuleThreeArtifactsExistAndContainDeliveredContent() {
        List<Path> artifacts = List.of(
                platformDirectory().resolve("docs/onboarding.md"),
                platformDirectory().resolve("docs/suggested-next-tasks.md"),
                serviceReadme("authorization-service"),
                serviceReadme("ledger-posting-service"),
                serviceReadme("fraud-detection-service"),
                serviceReadme("notification-service"),
                serviceReadme("account-service"),
                serviceReadme("card-service"),
                repositoryRoot().resolve("README.md"));

        assertEquals(9, artifacts.size());
        for (Path artifact : artifacts) {
            assertTrue(Files.isRegularFile(artifact), artifact + " must exist");
            assertFalse(read(artifact).isBlank(), artifact + " must not be blank");
        }
        assertTrue(Files.isRegularFile(platformDirectory().resolve("README.md")));
    }

    @Test
    void everyRelativeLinkInTheRuleThreeGuidesResolves() {
        for (Path guide : allGuides()) {
            Matcher links = LINK.matcher(read(guide));
            while (links.find()) {
                String reference = links.group(1);
                String target = reference.split("#", 2)[0];
                int title = target.indexOf(" \"");
                if (title >= 0) {
                    target = target.substring(0, title);
                }
                target = target.split("\\?", 2)[0];
                if (target.isBlank() || target.contains("://") || target.startsWith("#")
                        || target.startsWith("mailto:")) {
                    continue;
                }
                assertTrue(Files.exists(guide.getParent().resolve(target).normalize()),
                        guide + " links to missing " + reference);
            }
        }
    }

    @Test
    void thePlatformReadmeDescribesTheDeliveredInventoryWithoutStaleCheckpointClaims() {
        String platform = read(platformDirectory().resolve("README.md"));
        for (String stale : List.of(
                "under construction",
                "Not built yet",
                "Only two services answer a request",
                "No `@KafkaListener` anywhere",
                "No posting arithmetic and no risk rules",
                "An outbox relay in one service only",
                "do not exist yet",
                "no service carries a `README.md` yet",
                "Five comparisons are pending",
                "19 programs, 19 transactions",
                "Every surface below is unauthenticated",
                "None answers yet",
                "Seven listeners are present",
                "Nine listeners are present",
                "five source-specific dead-letter topics",
                "no runtime consumer",
                "182 Failsafe equivalence tests",
                "cross-reference divergence metering",
                "may publish a decline when its account projection is missing",
                "| planned |")) {
            assertFalse(platform.contains(stale), "platform README retains stale text: " + stale);
        }

        assertEquals(14, schemaDocumentCount(),
                "the platform guide names fourteen schema documents, so fourteen must ship. Adding or"
                        + " withdrawing one moves the sentence in README.md and the headline metric on"
                        + " slide 2 with it, and withdrawing one also fails the released-contract"
                        + " baseline in libs/event-contracts, because a released document is never"
                        + " deleted");

        for (String delivered : List.of(
                "Eleven listeners are present",
                "Fourteen schema documents cover eight business event types, five released"
                        + " versions above version one",
                "Seven business topics, six source-specific dead-letter topics, and one shared"
                        + " fallback",
                "8 files, 17 mapsets, 18 programs, and 18 transactions",
                "229 Failsafe equivalence tests",
                "Every business route requires HTTP Basic authentication")) {
            assertTrue(platform.contains(delivered), "platform README must name " + delivered);
        }
    }

    @Test
    void theRootReadmeIsTheOriginalGuidePlusOneTocLineAndOneSection() {
        byte[] rootBytes = readBytes(repositoryRoot().resolve("README.md"));
        int lineFeeds = 0;
        int carriageReturnLineFeeds = 0;
        for (int index = 0; index < rootBytes.length; index++) {
            if (rootBytes[index] == '\n') {
                lineFeeds++;
                if (index > 0 && rootBytes[index - 1] == '\r') {
                    carriageReturnLineFeeds++;
                }
            }
        }
        assertEquals(lineFeeds, carriageReturnLineFeeds,
                "the additive edit preserves the legacy guide's CRLF line endings");
        assertTrue(carriageReturnLineFeeds > 0, "the legacy guide is carriage-return terminated");

        String current = read(repositoryRoot().resolve("README.md"));
        assertEquals(1, count(current, ROOT_TOC_ADDITION));

        String withoutTocAddition = current.replace(ROOT_TOC_ADDITION, "");
        String sectionHeading = "## Modernized card platform\n";
        String followingHeading = "## Installation on the mainframe ";
        int sectionStart = withoutTocAddition.indexOf(sectionHeading);
        int sectionEnd = withoutTocAddition.indexOf(followingHeading, sectionStart);
        assertTrue(sectionStart >= 0 && sectionEnd > sectionStart);

        String restored = withoutTocAddition.substring(0, sectionStart)
                + withoutTocAddition.substring(sectionEnd);
        assertEquals(ORIGINAL_ROOT_README_SHA256, sha256(restored),
                "legacy root README content must remain byte-for-byte unchanged");
        assertTrue(current.contains("## Installation on the mainframe \n"),
                "the legacy heading must retain its trailing space");
    }

    /**
     * Holds every carriage-return terminated file this repository delivers to a checked policy.
     *
     * <p>{@code git diff --check} treats a carriage return before the line feed as trailing
     * whitespace unless it is told otherwise, so every added line of the root guide is reported as
     * a defect and the command exits 2. The guide cannot be converted:
     * {@link #theRootReadmeIsTheOriginalGuidePlusOneTocLineAndOneSection()} requires its legacy
     * bytes unchanged, and those bytes are carriage-return terminated.
     *
     * <p>The policy is carried by the invocation rather than by a repository file. A root
     * {@code .gitattributes} would sit outside the three paths this engagement is allowed to write,
     * so the documented command names the setting instead:
     * {@code git -c core.whitespace=cr-at-eol diff --check}. That form covers every file at once,
     * which is why this test asserts the documentation carries it rather than counting per-path
     * declarations.
     *
     * <p>Two properties are checked together. The scan reads bytes rather than trusting a list, so
     * it finds the guide the setting exists for. The absence of any attributes file is asserted in
     * its own right, because {@code text}, {@code eol} and {@code working-tree-encoding} would
     * rewrite content that has to stay byte for byte.
     */
    @Test
    void everyCarriageReturnTerminatedFileIsCheckedUnderADocumentedInvocation() {
        List<String> attributeFiles = new ArrayList<>();
        for (Path file : deliveredFilesOutsideTheLegacyApplication()) {
            if (file.getFileName().toString().equals(".gitattributes")) {
                attributeFiles.add(repositoryRoot().relativize(file).toString());
            }
        }
        assertEquals(List.of(), attributeFiles,
                "this engagement writes card-platform, the root README.md and .github and nothing "
                        + "else, and an attributes file could also rewrite line endings or encoding "
                        + "in content preserved verbatim: " + attributeFiles);

        List<String> carriageReturnFiles = new ArrayList<>();
        for (Path file : deliveredFilesOutsideTheLegacyApplication()) {
            if (contains(readBytes(file), (byte) '\r', (byte) '\n')) {
                carriageReturnFiles.add(repositoryRoot().relativize(file).toString());
            }
        }
        assertTrue(carriageReturnFiles.contains("README.md"),
                "the scan found no carriage return in the root guide, so this verdict would mean "
                        + "nothing: the guide is the file the setting exists for, and its legacy "
                        + "bytes are carriage-return terminated");

        for (Path guide : List.of(platformDirectory().resolve("docs/onboarding.md"),
                platformDirectory().resolve("docs/decision-log.md"))) {
            String text = read(guide);
            assertTrue(text.contains("core.whitespace=cr-at-eol"),
                    guide.getFileName() + " must name the setting, because a reader who runs the "
                            + "check without it is told every one of the guide's "
                            + carriageReturnFiles.size() + " carriage-return terminated files is "
                            + "defective");
            assertTrue(text.contains("git -c core.whitespace=cr-at-eol diff --check"),
                    guide.getFileName() + " must carry the whole invocation, since the setting is "
                            + "useless to a reader who cannot see which command takes it");
        }
    }

    /**
     * Returns the files this engagement delivers, excluding the legacy mainframe application.
     *
     * <p>{@code app}, {@code diagrams} and {@code samples} are read-only inputs this engagement
     * neither adds to nor edits, so their line endings are not this policy's subject.
     *
     * @return every delivered regular file
     */
    private static List<Path> deliveredFilesOutsideTheLegacyApplication() {
        List<String> excluded = List.of("app", "diagrams", "samples", ".git", "blitzy", "target",
                "node_modules");
        List<Path> delivered = new ArrayList<>();
        try (java.util.stream.Stream<Path> tree = Files.walk(repositoryRoot())) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> {
                        for (Path element : repositoryRoot().relativize(path)) {
                            if (excluded.contains(element.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted()
                    .forEach(delivered::add);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unreadable repository", unreadable);
        }
        return delivered;
    }

    /**
     * Reports whether one byte sequence occurs in another.
     *
     * @param haystack the bytes to search
     * @param needle   the bytes to find
     * @return {@code true} when the sequence occurs
     */
    private static boolean contains(byte[] haystack, byte... needle) {
        outer:
        for (int at = 0; at <= haystack.length - needle.length; at++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[at + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void theRootModernizationSectionCarriesBothStatesAndLiveEntryPointLinks() {
        String root = read(repositoryRoot().resolve("README.md"));
        int technologies = root.indexOf("## Technologies used");
        int modernization = root.indexOf("## Modernized card platform");
        int installation = root.indexOf("## Installation on the mainframe ");
        assertTrue(technologies >= 0 && technologies < modernization && modernization < installation);

        String section = root.substring(modernization, installation);
        assertEquals(1, count(section, "```mermaid"));
        assertTrue(section.contains("**Figure 1 — CardDemo Before and After:"));
        assertTrue(section.contains("Legend for Figure 1:"));
        assertTrue(section.contains("BEFORE — retained mainframe application"));
        assertTrue(section.contains("AFTER — card-platform"));
        assertTrue(section.contains("(card-platform/README.md)"));
        assertTrue(section.contains("(card-platform/docs/onboarding.md)"));
    }

    @Test
    void everyServiceReadmeCarriesTheSameOnboardingShapeAndAValidArchitectureView() {
        Pattern prohibited = Pattern.compile(
                "(?i)\\b(TBD|TODO|FIXME|planned|not built|pending|under construction)\\b");
        for (String service : SERVICES) {
            String guide = read(serviceReadme(service));
            for (String section : SERVICE_SECTIONS) {
                assertTrue(guide.contains(section), service + " must carry " + section);
            }
            assertTrue(count(guide, "```mermaid") >= 1, service + " needs a Mermaid view");
            assertEquals(count(guide, "```mermaid"), count(guide, "Legend for Figure"),
                    service + " must give every figure a legend");
            assertFalse(prohibited.matcher(guide).find(),
                    service + " must contain no placeholder or stale checkpoint wording");
        }
    }

    @Test
    void theGuidesPinTheTestedToolchainAndContainerVersions() {
        String onboarding = read(platformDirectory().resolve("docs/onboarding.md"));
        String platform = read(platformDirectory().resolve("README.md"));
        for (String exact : List.of(
                "25.0.4+7",
                "3.9.16",
                "29.7.0",
                "5.3.1",
                "`apache/kafka:4.2.1`",
                "`postgres:18.4`")) {
            assertTrue(onboarding.contains(exact), "onboarding must pin " + exact);
            assertTrue(platform.contains(exact), "platform README must pin " + exact);
        }

        for (String service : SERVICES) {
            String guide = read(serviceReadme(service));
            for (String exact : List.of(
                    "25.0.4+7", "3.9.16", "`apache/kafka:4.2.1`", "`postgres:18.4`")) {
                assertTrue(guide.contains(exact), service + " must pin " + exact);
            }
        }
    }

    @Test
    void thePlatformReadmeNamesEveryDeliveredRoutePortTopicAndConsumerGroup() {
        String platform = read(platformDirectory().resolve("README.md"));
        for (int port = 8081; port <= 8086; port++) {
            assertTrue(platform.contains(Integer.toString(port)), "missing business port " + port);
        }
        for (int port = 9081; port <= 9086; port++) {
            assertTrue(platform.contains(Integer.toString(port)),
                    "missing management port " + port);
        }
        for (String route : List.of(
                "POST /authorizations",
                "GET /balances/{accountId}",
                "GET /fraud-assessments",
                "GET /notifications/{cardToken}",
                "PUT /accounts/{accountId}",
                "POST /accounts/{accountId}/cycle-close",
                "GET /customers/{customerId}",
                "GET /cards",
                "GET /cards/{cardToken}",
                "PUT /cards/{cardToken}")) {
            assertTrue(platform.contains("`" + route + "`"), "missing route " + route);
        }
        for (String topic : List.of(
                "transaction.authorized",
                "transaction.declined",
                "transaction.posted",
                "fraud.assessed",
                "account.state-changed",
                "customer.context-changed",
                "card.updated",
                "carddemo.dead-letter")) {
            assertTrue(platform.contains("`" + topic + "`"), "missing topic " + topic);
        }
        for (String group : List.of(
                "ledger-posting",
                "ledger-account-state",
                "fraud-detection",
                "notification-authorized",
                "notification-posted",
                "notification-fraud",
                "notification-customer",
                "authorization-account-state",
                "authorization-card-updated",
                "account-posted")) {
            assertTrue(platform.contains("`" + group + "`"), "missing consumer group " + group);
        }
    }

    @Test
    void thePlatformReadmeLinksEveryDeliveredDocumentAndServiceGuide() {
        String platform = read(platformDirectory().resolve("README.md"));
        for (String document : List.of(
                "docs/onboarding.md",
                "docs/suggested-next-tasks.md",
                "docs/decision-log.md",
                "docs/traceability-matrix.md",
                "docs/business-rule-flags.md",
                "docs/architecture-before-after.md",
                "docs/event-flow.md",
                "docs/data-model.md",
                "docs/equivalence-results.md")) {
            assertTrue(platform.contains("(" + document + ")"),
                    "platform README must link " + document);
        }
        for (String service : SERVICES) {
            String guide = "services/" + service + "/README.md";
            assertTrue(platform.contains("(" + guide + ")"),
                    "platform README must link " + guide);
        }
    }

    @Test
    void onboardingContainsTheCompleteBuildStartHealthAndVerificationSequence() {
        String onboarding = read(platformDirectory().resolve("docs/onboarding.md"));
        for (String command : List.of(
                "install -m 600 .env.example .env",
                "mvn -B -DskipTests package",
                "jshell --class-path",
                "mvn -B clean verify",
                "docker compose up -d --build",
                "docker compose ps",
                "for port in 9081 9082 9083 9084 9085 9086",
                "POST /authorizations",
                "kafka-console-consumer.sh")) {
            assertTrue(onboarding.contains(command), "onboarding must carry " + command);
        }
        assertTrue(onboarding.contains("`mvn test` does not run the equivalence classes"));
        assertTrue(onboarding.contains(
                "HTTP 200 for an approval and HTTP 422 for a source-equivalent decline"));
    }

    /**
     * Holds the account round-trip guidance the onboarding guide owes a new developer.
     *
     * <p>{@code GET /accounts/{accountId}} and {@code GET /customers/{customerId}} answer a shape
     * {@code PUT /accounts/{accountId}} does not accept, and every seeded row fails at least one
     * edit of {@code app/cbl/COACTUPC.cbl:L1470-L1676}. Without a worked body a reader has no route
     * from the two reads to an accepted write, so this test pins the body, each refusal text it
     * exists to avoid, and the pitfall that a resubmitted fixture expiry declines the account.</p>
     */
    @Test
    void onboardingCarriesAWorkingAccountUpdateAndTheDemoExpiryPitfall() {
        String onboarding = read(platformDirectory().resolve("docs/onboarding.md"));

        assertTrue(onboarding.contains("### Update an account and a customer"));
        assertTrue(onboarding.contains("PUT http://localhost:8085/accounts/00000000050"));
        for (String component : List.of(
                "\"activeStatus\": \"Y\"",
                "\"openDate\": \"20110422\"",
                "\"expirationDate\": \"20991231\"",
                "\"customerId\": \"000000050\"",
                "\"addressZip\": \"97201\"",
                "\"phoneNumber2\": \"(503)985-9283\"",
                "\"socialSecurityPart1\": \"111\"",
                "\"ficoCreditScore\": \"623\"")) {
            assertTrue(onboarding.contains(component),
                    "onboarding must carry the accepted request component " + component);
        }

        for (String refusal : List.of(
                "Open Date: Month must be a number between 1 and 12.",
                "Expiry Date: Month must be a number between 1 and 12.",
                "Reissue Date: Month must be a number between 1 and 12.",
                "Date of Birth: Month must be a number between 1 and 12.",
                "FICO Score: should be between 300 and 850",
                "SSN: First 3 chars must be supplied.",
                "Invalid zip code for state",
                "Phone Number 2: Not valid North America general purpose area code",
                "Changes committed to database",
                "No change detected with respect to values fetched.")) {
            assertTrue(onboarding.contains(refusal),
                    "onboarding must quote the outcome text " + refusal);
        }

        int pitfall =
                onboarding.indexOf("### 10. Resubmitting the fixture expiry declines the account");
        assertTrue(pitfall > 0, "onboarding must carry the demo expiry pitfall");
        String section = onboarding.substring(pitfall);
        assertTrue(section.contains("classpath:db/demo"));
        assertTrue(section.contains("AccountStateChanged"));
        assertTrue(section.contains("0103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION"));
        assertTrue(section.contains("ACCOUNT_FLYWAY_LOCATIONS")
                && section.contains("AUTHORIZATION_FLYWAY_LOCATIONS"));
        assertTrue(onboarding.contains(
                "#10-resubmitting-the-fixture-expiry-declines-the-account-you-just-updated"),
                "the update section must link the pitfall it depends on");

        String description = read(platformDirectory().resolve(
                "services/account-service/src/main/resources/openapi.yaml"));
        assertTrue(description.contains("classpath:db/demo overlay reads 2099-12-31"),
                "the request example carrying the fixture expiry must warn about the overlay");
    }

    /**
     * Asserts every state-changing {@code curl} command any guide publishes carries the two things the
     * shipped filters require of one, so a reader who copies a command receives the answer the guide
     * describes rather than a refusal.
     *
     * <p>Two contracts are involved and each has one enforcement point in the running code.
     * {@code config/CrossSiteRequestFilter} refuses a {@code POST}, {@code PUT}, {@code PATCH} or
     * {@code DELETE} carrying no {@value #CROSS_SITE_HEADER} header, answering 403 before the security
     * chain runs. And a route declaring {@code consumes = APPLICATION_JSON_VALUE} answers 415 to a body
     * arriving without {@code Content-Type: application/json}, which {@code curl} does not set by
     * itself: its default for {@code -d} is a form encoding.
     *
     * <p>The header name is read from {@code .env.example} rather than written here, so a deployment
     * that renamed it renames it in one place and this test follows. Continuation lines are joined
     * before the scan, because every command in these guides spans several lines.
     *
     * <p>The two omissions this test exists to catch had both happened: four write commands across the
     * guides carried no header and would have answered 403, and one carried the header but no media
     * type and would have answered 415.
     */
    @Test
    void everyStateChangingCommandInEveryGuideCarriesTheHeaderAndTheMediaType() {
        String header = configuredCrossSiteHeader();
        assertEquals(CROSS_SITE_HEADER, header,
                ".env.example must document the header the guides carry");

        int commands = 0;
        for (Path guide : allGuides()) {
            for (String command : curlCommandsOf(read(guide))) {
                if (WRITE_METHODS.stream().noneMatch(command::contains)) {
                    continue;
                }
                commands++;
                assertTrue(command.contains(header),
                        guide.getFileName() + " publishes a state-changing command carrying no "
                                + header + " header, which config/CrossSiteRequestFilter answers 403"
                                + " to: " + command);
                if (command.contains("-d ") || command.contains("--data")) {
                    assertTrue(command.contains("Content-Type: application/json"),
                            guide.getFileName() + " publishes a state-changing command sending a body"
                                    + " with no JSON media type, which a consuming route answers 415"
                                    + " to: " + command);
                }
            }
        }
        assertTrue(commands >= 5,
                "the guides publish " + commands + " state-changing commands, and the delivered "
                        + "write surface has more than that");
    }

    /**
     * Asserts no guide passes a written-down card token into a route.
     *
     * <p>A card token is the keyed code over a card number under {@code CARD_TOKEN_SECRET}, so it
     * belongs to one key. A literal published in a guide resolves under the key it was derived with and
     * under no other, and every deployment is told to generate its own key before the first run. Such a
     * literal therefore reads as a working value and answers 404 for the reader who copies it.
     *
     * <p>Each guide that names a token derives it instead, from the fixture number and the configured
     * key, which is what makes the command correct under any key. The scan reads every run of
     * sixty-four hexadecimal characters and admits only the all-zero placeholder, which resolves to no
     * row by design.
     */
    @Test
    void noGuidePublishesACardTokenLiteralForARoute() {
        for (Path guide : allGuides()) {
            Matcher run = SIXTY_FOUR_HEX_RUN.matcher(read(guide));
            while (run.find()) {
                assertEquals("0".repeat(64), run.group(),
                        guide.getFileName() + " publishes a card token literal, which resolves only "
                                + "under the key it was derived with: " + run.group());
            }
        }

        String platform = read(platformDirectory().resolve("README.md"));
        assertTrue(platform.contains("openssl dgst -sha256 -hmac"),
                "the platform guide must derive the token it uses rather than publish one");
        assertTrue(platform.contains("CardDemo/card-token/v"),
                "the derivation must cover the label PanMasker.cardToken covers");
    }

    /**
     * A guide is a set of instructions, so a command it prints has to be one that does what the
     * surrounding sentence says it does. Six ways for that to stop being true share one shape: the
     * document restates something a committed script or the build already owns, and the copy
     * drifts. These assertions hold the document to the owner.
     *
     * <p>The credential half is the sharpest. Every service guide carried a block that set
     * seventeen of the nineteen values {@code .env.example} declares, omitting the card-token key
     * and the acquirer hash, and then printed "all 17 values are set" beside a paragraph promising
     * nineteen. {@code scripts/generate-env.sh} reads the names out of {@code .env.example}, so it
     * cannot omit one, and a guide that calls it cannot disagree with it.
     */
    @Test
    void everyGuideCommandDoesWhatItsSentenceClaims() {
        String onboarding = read(platformDirectory().resolve("docs/onboarding.md"));

        // A bcrypt value starts {bcrypt}$2a$10$, and Compose expands $ in an unquoted dotenv value,
        // which truncates the hash and answers 401 at every sign-on.
        assertEquals(4, count(onboarding, "_PASSWORD_HASH='$(hash_password"),
                "onboarding writes four identity hashes and every one has to be single-quoted");
        for (Path guide : allGuides()) {
            assertFalse(Pattern.compile("_PASSWORD_HASH=\\$\\(").matcher(read(guide)).find(),
                    guide.getFileName() + " writes a bcrypt hash unquoted, which Compose corrupts");
        }

        for (String service : SERVICES) {
            String guide = read(serviceReadme(service));

            assertTrue(guide.contains("scripts/generate-env.sh"),
                    service + " must defer to the one script that fills all nineteen credentials");
            assertFalse(guide.contains("all 17 values are set"),
                    service + " must not claim seventeen where .env.example declares nineteen");
            assertFalse(guide.contains("CRYPTO_CP") || guide.contains("hash_password("),
                    service + " must not restate the hash helper the script already owns");

            // Failsafe's default pattern matches **/*IT.java and Surefire's does not, so `test`
            // finishes without the integration classes and reports nothing missing.
            assertFalse(Pattern.compile("mvn [^\\n]*-pl services/" + service + " -am test\\b")
                            .matcher(guide).find(),
                    service + " has integration classes Surefire never runs, so a documented "
                            + "module run has to be `verify` rather than `test`");

            // A health request immediately after `up` races start-up without --wait. Prose that
            // merely mentions the command is not a command, so only runnable lines are held.
            for (String line : runnableLines(guide)) {
                if (!line.strip().startsWith("docker compose up")) {
                    continue;
                }
                assertTrue(line.contains("--wait"),
                        service + " curls a health endpoint straight after this, so the command has "
                                + "to hold until the containers report healthy: " + line.strip());
            }
        }
    }

    /**
     * Every relative path a guide prints inside a runnable block has to resolve. Two conventions are
     * in use and both are correct — a block stating the repository root writes {@code app/...} and
     * {@code card-platform/.env}, and a block stating {@code card-platform/} writes {@code ../app/...}
     * and {@code .env} — so this resolves each token under the base its own prefix implies. A review
     * found three tokens in the {@code ../../} form, which is correct from a service directory that
     * no section names, so they resolved from nowhere a reader would be standing.
     */
    @Test
    void everyPathAGuidePrintsResolvesOnDisk() {
        Pattern token = Pattern.compile(
                "(?:\\.\\./)*(?:card-platform/)?(?:app/data/[A-Za-z]+/[a-z]+\\.txt|\\.env)\\b");
        int checked = 0;
        for (Path guide : allGuides()) {
            boolean inBlock = false;
            for (String line : read(guide).lines().toList()) {
                if (line.startsWith("```bash")) {
                    inBlock = true;
                    continue;
                }
                if (line.startsWith("```")) {
                    inBlock = false;
                    continue;
                }
                if (!inBlock) {
                    continue;
                }
                Matcher found = token.matcher(line);
                while (found.find()) {
                    String path = found.group();
                    // A bare .env or a ../ prefix is written from card-platform/; anything naming
                    // app/ or card-platform/ without a prefix is written from the repository root.
                    Path base = path.equals(".env") || path.startsWith("../")
                            ? platformDirectory()
                            : repositoryRoot();
                    assertTrue(Files.exists(base.resolve(path).normalize()),
                            guide.getFileName() + " prints " + path + ", which resolves to "
                                    + base.resolve(path).normalize() + " and does not exist");
                    checked++;
                }
            }
        }
        assertTrue(checked >= 40, "the guides print many such paths; only " + checked + " were found,"
                + " so this test is no longer reading them");
    }

    /**
     * The toolchain table mixes requirements with floors, and a reader acts differently on each. The
     * language level and Maven are requirements: the enforcer plugin refuses a build outside
     * {@code [25,26)} and {@code [3.9.16,3.10.0)}, so a newer Maven fails rather than passes.
     * Nothing here constrains Docker Engine, Compose or OpenSSL, so an exact value published for
     * them goes stale the week after it is written and tells a reader to downgrade for no reason.
     */
    @Test
    void theToolchainSeparatesPinnedRequirementsFromExercisedFloors() {
        for (Path guide : List.of(platformDirectory().resolve("docs/onboarding.md"),
                platformDirectory().resolve("README.md"))) {
            String text = read(guide);
            for (String floor : List.of("29.7.0", "5.3.1")) {
                assertTrue(text.contains(floor + " or later"),
                        guide.getFileName() + " must publish " + floor + " as a floor, because "
                                + "nothing in this repository constrains that tool");
            }
        }
        assertTrue(read(platformDirectory().resolve("docs/onboarding.md"))
                        .contains("3.5.3 or later"),
                "onboarding must publish the OpenSSL version as a floor too");

        for (Path guide : List.of(platformDirectory().resolve("docs/onboarding.md"),
                platformDirectory().resolve("README.md"))) {
            assertTrue(read(guide).contains("[25,26)") && read(guide).contains("[3.9.16,3.10.0)"),
                    guide.getFileName() + " must name the two ranges the enforcer refuses outside, or "
                            + "a reader treats every version in the table the same way");
        }

        for (String service : SERVICES) {
            String guide = read(serviceReadme(service));
            assertTrue(guide.contains("29.7.0 or later"),
                    service + " must state the container-runtime floor rather than an exact value");
            assertTrue(guide.contains("[25,26)") && guide.contains("[3.9.16,3.10.0)"),
                    service + " must say why the language level and Maven are exact, or a reader "
                            + "treats every version in the table the same way");
        }
    }

    /** The lines of one guide that sit inside a fenced bash block, so a reader would run them. */
    private static List<String> runnableLines(String guide) {
        List<String> runnable = new ArrayList<>();
        boolean inBlock = false;
        for (String line : guide.lines().toList()) {
            if (line.startsWith("```bash")) {
                inBlock = true;
            } else if (line.startsWith("```")) {
                inBlock = false;
            } else if (inBlock) {
                runnable.add(line);
            }
        }
        return runnable;
    }

    private static List<Path> allGuides() {
        return List.of(
                repositoryRoot().resolve("README.md"),
                platformDirectory().resolve("README.md"),
                platformDirectory().resolve("docs/onboarding.md"),
                platformDirectory().resolve("docs/suggested-next-tasks.md"),
                serviceReadme("authorization-service"),
                serviceReadme("ledger-posting-service"),
                serviceReadme("fraud-detection-service"),
                serviceReadme("notification-service"),
                serviceReadme("account-service"),
                serviceReadme("card-service"));
    }

    /**
     * Reads the cross-site header name the delivered environment template documents.
     *
     * <p>The value is read rather than written here so that a deployment renaming the header renames it
     * in one place. {@code API_CROSS_SITE_HEADER} is the key
     * {@code carddemo.api.cross-site.required-header} binds to.
     *
     * @return the configured header name
     */
    private static String configuredCrossSiteHeader() {
        for (String line : read(platformDirectory().resolve(".env.example")).split("\n")) {
            if (line.startsWith("API_CROSS_SITE_HEADER=")) {
                return line.substring("API_CROSS_SITE_HEADER=".length()).trim()
                        .replace("'", "").replace("\"", "");
            }
        }
        throw new IllegalStateException(".env.example declares no API_CROSS_SITE_HEADER");
    }

    /**
     * Splits one guide into its {@code curl} invocations, joining every continuation line so a command
     * spanning ten lines is scanned as one string.
     *
     * @param guide the guide text, newline-normalised
     * @return one entry per {@code curl} invocation the guide publishes
     */
    private static List<String> curlCommandsOf(String guide) {
        List<String> commands = new ArrayList<>();
        String[] lines = guide.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].contains("curl ")) {
                continue;
            }
            StringBuilder command = new StringBuilder(lines[index].strip());
            while (command.toString().endsWith("\\") && index + 1 < lines.length) {
                command.setLength(command.length() - 1);
                index++;
                command.append(' ').append(lines[index].strip());
            }
            commands.add(command.toString());
        }
        return commands;
    }

    private static Path serviceReadme(String service) {
        return platformDirectory().resolve("services").resolve(service).resolve("README.md");
    }

    private static int count(String text, String token) {
        int occurrences = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            occurrences++;
            offset += token.length();
        }
        return occurrences;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be present in the JDK", impossible);
        }
    }

    /**
     * Counts the schema documents the contract library publishes.
     *
     * <p>The guide's sentence and the deck's headline metric both name this number, and the
     * inventory has moved once already: withdrawing the unpublished {@code card-updated} version two
     * document took it from fourteen to thirteen. Counting the directory is what keeps the three in
     * step.
     *
     * @return how many {@code .json} documents sit in the contract library's schema directory
     */
    private static long schemaDocumentCount() {
        Path schemas = platformDirectory()
                .resolve("libs/event-contracts/src/main/resources/schemas");
        try (var documents = Files.list(schemas)) {
            return documents.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Cannot read " + schemas, unreadable);
        }
    }

    private static Path platformDirectory() {
        return repositoryRoot().resolve("card-platform");
    }

    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent();
    }

    private static String read(Path file) {
        return new String(readBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}