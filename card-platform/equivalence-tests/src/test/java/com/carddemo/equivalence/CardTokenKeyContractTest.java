package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the one card-token key to one value across the build, the Compose stack, the Kubernetes
 * manifests and every token already written down.
 *
 * <p>A card token is the key of {@code statement_transaction} and {@code notification_log}, the
 * identifier a card history route and a list cursor carry, and the subject of a {@code SCOPE_CARD}
 * authority. {@code com.carddemo.cobol.PanMasker} derives it as a keyed code, so a token belongs to
 * one key and one version. Two places deriving under different keys would key the same card two
 * ways and split every read model that joins on it, and a granted authority derived under a retired
 * key names no card at all.
 *
 * <p>Four artifacts therefore have to agree, and none of the four can be checked by a compiler:
 * {@code card-platform/pom.xml} supplies the key to every test run, {@code card-platform/.env.example}
 * declares it for the Compose stack, {@code deploy/k8s/31-secret.example.yaml} supplies it in the
 * cluster, and the fifty {@code card_token} literals in the card service seed plus the
 * {@code SCOPE_CARD} authority in {@code .env.example} and {@code deploy/k8s/30-configmap.yaml} were
 * derived under it. This class compares all of them, so a key changed in one place fails the build
 * rather than emptying an entitlement or orphaning a read model at run time.
 *
 * <p>The derivation itself is proved by {@code com.carddemo.cobol.PanMaskerTest}, and the seeded
 * literals are also compared against a live database by
 * {@code CardRepositoryIT.everySeededTokenMatchesTheJavaDerivation}. This class adds the part
 * neither of those can see: that the configuration those tokens were derived under is the
 * configuration a deployment applies.
 *
 * <p>No failure message here quotes the key or a card number. A divergence is reported by artifact
 * and by the one-based ordinal of the seeded row.
 */
@DisplayName("Card-token key agreement across the build, Compose and Kubernetes")
class CardTokenKeyContractTest {

    /** Rows the card fixture holds, and therefore tokens the card seed carries. */
    private static final int FIXTURE_ROW_COUNT = 50;

    /** Matches the key the parent build supplies to Surefire and Failsafe. */
    private static final Pattern POM_KEY = Pattern.compile(
            "<carddemo\\.card-token\\.secret>([^<]+)</carddemo\\.card-token\\.secret>");

    /** Matches the version the parent build supplies. */
    private static final Pattern POM_VERSION = Pattern.compile(
            "<carddemo\\.card-token\\.version>([^<]+)</carddemo\\.card-token\\.version>");

    /** Matches the key the Kubernetes Secret template supplies. */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?m)^  CARD_TOKEN_SECRET: \"([^\"]+)\"$");

    /** Matches the version the Kubernetes ConfigMap supplies. */
    private static final Pattern CONFIGMAP_VERSION =
            Pattern.compile("(?m)^  CARD_TOKEN_VERSION: \"([^\"]+)\"$");

    /** Matches one card number and the token literal seeded beside it. */
    private static final Pattern SEEDED_CARD = Pattern.compile(
            "\\('(\\d{16})',\\s*'\\d{11}',\\s*'\\d{3}',[^\\n]*\\n\\s*'([0-9a-f]{64})'\\)");

    /** Matches the card authority granted to the ordinary identity. */
    private static final Pattern GRANTED_CARD_SCOPE =
            Pattern.compile("SCOPE_CARD_([0-9a-f]{64})");

    @Test
    @DisplayName("the build, Compose and Kubernetes all name one key and one version")
    void theBuildComposeAndKubernetesAllNameOneKeyAndOneVersion() {
        String pom = read(platformDirectory().resolve("pom.xml"));
        String secretTemplate =
                read(platformDirectory().resolve("deploy/k8s/31-secret.example.yaml"));
        String configMap = read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));
        String compose = read(platformDirectory().resolve("docker-compose.yml"));

        String buildKey = captured(POM_KEY, pom, "card-platform/pom.xml");
        String dotenvKey = dotenvValue("CARD_TOKEN_SECRET");
        String secretKey = captured(SECRET_KEY, secretTemplate, "31-secret.example.yaml");

        assertEquals(dotenvKey, buildKey,
                "card-platform/pom.xml supplies a different card-token key to the build than"
                        + " .env.example supplies to the Compose stack, so a token derived by a"
                        + " test would not be the token a running service derives");
        assertEquals(dotenvKey, secretKey,
                "deploy/k8s/31-secret.example.yaml supplies a different card-token key than"
                        + " .env.example, so the same card is keyed two ways across the two paths");

        String buildVersion = captured(POM_VERSION, pom, "card-platform/pom.xml");
        assertEquals(dotenvValue("CARD_TOKEN_VERSION"), buildVersion,
                "the build and .env.example name different card-token versions");
        assertEquals(dotenvValue("CARD_TOKEN_VERSION"),
                captured(CONFIGMAP_VERSION, configMap, "30-configmap.yaml"),
                "the ConfigMap and .env.example name different card-token versions");

        assertTrue(compose.contains("CARD_TOKEN_SECRET: ${CARD_TOKEN_SECRET:-" + dotenvKey + "}"),
                "docker-compose.yml must pass the documented card-token key to every service");
        assertTrue(compose.contains("CARD_TOKEN_VERSION: ${CARD_TOKEN_VERSION:-"
                        + dotenvValue("CARD_TOKEN_VERSION") + "}"),
                "docker-compose.yml must pass the documented card-token version to every service");

        assertTrue(buildKey.length() >= PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH,
                "the configured card-token key is shorter than the minimum PanMasker accepts, so"
                        + " every derivation would be refused");
    }

    @Test
    @DisplayName("this build derives tokens under the key the configuration names")
    void thisBuildDerivesTokensUnderTheKeyTheConfigurationNames() {
        String buildKey = captured(POM_KEY, read(platformDirectory().resolve("pom.xml")),
                "card-platform/pom.xml");

        assertEquals(buildKey, System.getProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY),
                "the test run did not receive the key card-platform/pom.xml declares, so the"
                        + " comparisons in this class would prove nothing");
        assertEquals(dotenvValue("CARD_TOKEN_VERSION"), PanMasker.cardTokenVersion(),
                "the test run derives under a different version than the configuration names");
    }

    @Test
    @DisplayName("every seeded token and the granted authority belong to the configured key")
    void everySeededTokenAndTheGrantedAuthorityBelongToTheConfiguredKey() {
        String seed = read(platformDirectory()
                .resolve("services/card-service/src/main/resources/db/migration/V2__seed.sql"));

        List<Integer> divergent = new ArrayList<>();
        Matcher rows = SEEDED_CARD.matcher(seed);
        int ordinal = 0;
        while (rows.find()) {
            ordinal++;
            if (!PanMasker.cardToken(rows.group(1)).equals(rows.group(2))) {
                divergent.add(ordinal);
            }
        }

        assertEquals(FIXTURE_ROW_COUNT, ordinal,
                "the card seed must carry one token per fixture card");
        assertEquals(List.of(), divergent,
                "these seeded rows, by one-based ordinal, carry a token that is not the token the"
                        + " configured key derives, so the row and the running service disagree"
                        + " about which card it is: " + divergent);

        String grantedInDotenv = captured(GRANTED_CARD_SCOPE, dotenvValue("USER_SCOPES"),
                ".env.example USER_SCOPES");
        assertTrue(seedTokens(seed).contains(grantedInDotenv),
                "the SCOPE_CARD authority .env.example grants names a token no seeded card carries,"
                        + " so the ordinary identity owns no card and POST /cards/detail answers"
                        + " 403 for every card");
        assertEquals(grantedInDotenv,
                captured(GRANTED_CARD_SCOPE,
                        read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml")),
                        "30-configmap.yaml USER_SCOPES"),
                "the two paths grant different card authorities, so a demo scripted on one answers"
                        + " 403 on the other");
    }

    @Test
    @DisplayName("no artifact still carries a token from the retired unkeyed derivation")
    void noArtifactStillCarriesATokenFromTheRetiredUnkeyedDerivation() {
        for (String artifact : List.of("pom.xml", ".env.example", "docker-compose.yml",
                "deploy/k8s/30-configmap.yaml", "deploy/k8s/31-secret.example.yaml",
                "services/card-service/src/main/resources/db/migration/V2__seed.sql")) {
            String text = read(platformDirectory().resolve(artifact));

            assertFalse(text.contains(RETIRED_TOKEN_OF_THE_SEEDED_DEMO_CARD),
                    artifact + " still carries the token the retired unkeyed digest produced for"
                            + " the seeded demo card, so a keyed deployment would not recognise it");
            assertFalse(text.contains("SHA-256 digest of the label"),
                    artifact + " still describes the retired unkeyed derivation");
        }
    }

    /**
     * The token the retired unkeyed digest produced for the card the demo authority names.
     *
     * <p>It is a token and not a card number, so writing it down discloses nothing, and it is the
     * one value that tells a stale artifact from a current one.
     */
    private static final String RETIRED_TOKEN_OF_THE_SEEDED_DEMO_CARD =
            "1134636222d1a2485d20203d0e970c72124893eb01be73a5fde5fdcccc2c4ac9";

    /** Every token literal the card seed carries. */
    private static List<String> seedTokens(String seed) {
        List<String> tokens = new ArrayList<>();
        Matcher rows = SEEDED_CARD.matcher(seed);
        while (rows.find()) {
            tokens.add(rows.group(2));
        }
        return List.copyOf(tokens);
    }

    /** Returns the first capture of one pattern, and fails when the artifact carries none. */
    private static String captured(Pattern pattern, String text, String artifact) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), artifact + " must declare the value " + pattern.pattern()
                + " matches");
        String captured = matcher.group(1);
        assertNotNull(captured, artifact + " declared the value with no content");
        return captured;
    }

    /** Reads one entry of {@code .env.example}. */
    private static String dotenvValue(String key) {
        Path file = platformDirectory().resolve(".env.example");
        for (String line : read(file).lines().toList()) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        throw new AssertionError(".env.example must declare " + key);
    }

    /** The {@code card-platform} directory below the repository root. */
    private static Path platformDirectory() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent()
                .resolve("card-platform");
    }

    /** Reads one artifact as text. */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
