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
                "may publish a decline when its account projection is missing",
                "| planned |")) {
            assertFalse(platform.contains(stale), "platform README retains stale text: " + stale);
        }

        for (String delivered : List.of(
                "Nine listeners are present",
                "Seven business topics, five source-specific dead-letter topics, and one shared fallback",
                "8 files, 17 mapsets, 18 programs, and 18 transactions",
                "182 Failsafe equivalence tests",
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
                "POST /cards/detail",
                "PUT /cards")) {
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
                "authorization-card-updated")) {
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
                "cp .env.example .env",
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
        assertTrue(onboarding.contains("HTTP 200 for either outcome"));
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