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
 * Proves that no path into a deployment carries a usable card-token key, and that the tokens this
 * repository does check in are re-derived under the deployment's own key before anything reads them.
 *
 * <p>A card token is the key of {@code statement_transaction} and {@code notification_log}, the
 * identifier a card history route and a list cursor carry, and the subject of a {@code SCOPE_CARD}
 * authority. {@code com.carddemo.cobol.PanMasker} derives it as a keyed code over a sixteen-digit
 * card number, so the key is the whole of what makes a token more than a rename: with the key, one
 * token is enough to recompute the token of every candidate card number offline.
 *
 * <p>Two kinds of key therefore exist here, and keeping them apart is what this class is for. The
 * BUILD-SCOPE key in {@code card-platform/pom.xml} travels one way, as a JVM system property to
 * Surefire and Failsafe, and the fifty {@code card_token} literals in the card service seed are
 * derived under it so that a checked-in literal can be compared against the derivation. A DEPLOYMENT
 * key is generated for one deployment: {@code card-platform/.env.example} and
 * {@code deploy/k8s/31-secret.example.yaml} both ship a placeholder, the Compose stack supplies no
 * default, and the two services that derive a token refuse to start without a real value.
 *
 * <p>The seeded literals are a bootstrap rather than a live identity, which is what makes shipping
 * them safe. {@code com.carddemo.card.domain.CardTokenReconciler} re-derives every one of them under
 * the deployment's key as the card service starts, before the readiness probe accepts traffic.
 *
 * <p>Five properties are asserted here, and none of the five can be checked by a compiler: that no
 * deployment artifact carries a usable key, that the build-scope key reaches no deployment artifact,
 * that the version still agrees across the three places that declare it, that every seeded literal
 * belongs to the build-scope key and is re-derived at start-up, and that every published contract
 * naming the token names the keyed primitive the code applies.
 *
 * <p>Four more cover a key that moves. A security review found the card service re-deriving
 * {@code card.card_token} at every start-up whenever the derivation changed, while three other stores
 * hold a token derived from the same key and hold no card number to re-derive from:
 * {@code statement_transaction.card_token} and {@code notification_log.card_token} in the notification
 * service, and {@code authorization_decision.card_token} in the authorization service. A granted
 * {@code SCOPE_CARD} authority is a fourth. The four assertions are that no shipped path carries a
 * previous key or states a rotation, that the three rotation settings reach the one service holding a
 * card number, that the refusal and the operator procedure are both shipped, and that the two columns
 * separating a bootstrap from a rotation default to the seeded case.
 *
 * <p>The derivation itself is proved by {@code com.carddemo.cobol.PanMaskerTest}, the seeded literals
 * are compared against a live database by {@code CardRepositoryIT}, and the reconciliation is
 * exercised there too. This class adds what none of those can see, which is the shape of the
 * configuration around them.
 *
 * <p>No failure message here quotes a key or a card number. A divergence is reported by artifact and
 * by the one-based ordinal of the seeded row.
 */
@DisplayName("Card-token key handling across the build, Compose and Kubernetes")
class CardTokenKeyContractTest {

    /** Rows the card fixture holds, and therefore tokens the card seed carries. */
    private static final int FIXTURE_ROW_COUNT = 50;

    /** Environment variable by which a deployment states that its key is the published one. */
    private static final String ACKNOWLEDGEMENT_VARIABLE =
            PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_VARIABLE;

    /** The same statement as the build property Surefire and Failsafe carry. */
    private static final String ACKNOWLEDGEMENT_PROPERTY =
            PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY;

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
    @DisplayName("no deployment path carries a usable card-token key")
    void noDeploymentPathCarriesAUsableCardTokenKey() {
        String dotenvKey = dotenvValue("CARD_TOKEN_SECRET");
        String secretKey = captured(SECRET_KEY,
                read(platformDirectory().resolve("deploy/k8s/31-secret.example.yaml")),
                "31-secret.example.yaml");
        String compose = read(platformDirectory().resolve("docker-compose.yml"));

        assertTrue(dotenvKey.contains(PLACEHOLDER_MARKER),
                ".env.example ships a card-token key rather than a placeholder. A key checked in"
                        + " here is a key every reader of this repository holds, and one token"
                        + " derived under it names its card to any of them");
        assertTrue(secretKey.contains(PLACEHOLDER_MARKER),
                "deploy/k8s/31-secret.example.yaml ships a card-token key rather than a"
                        + " placeholder, so applying the template unedited deploys a published key");
        assertFalse(compose.contains("CARD_TOKEN_SECRET: ${CARD_TOKEN_SECRET:-"),
                "docker-compose.yml gives CARD_TOKEN_SECRET a default, so the stack starts on a"
                        + " key nobody chose. The variable has to be required, not defaulted");
        assertTrue(compose.contains("CARD_TOKEN_SECRET: ${CARD_TOKEN_SECRET:?"),
                "docker-compose.yml must require CARD_TOKEN_SECRET, so an unset value stops the"
                        + " stack with the variable named rather than starting on a fallback");
    }

    /**
     * Holds the statement that keeps the published key usable to the one path that means it.
     *
     * <p>{@code PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET} is written down in this repository, so a
     * token taken under it is recomputable by anyone holding the repository. Both deriving services
     * refuse that key at start-up unless the configuration states that it means to use it, through
     * {@value com.carddemo.cobol.PanMasker#CARD_TOKEN_ALLOW_PUBLISHED_KEY_VARIABLE} or the matching
     * property.
     *
     * <p>No shipped path carries the published key any more. {@code .env.example} and
     * {@code deploy/k8s/31-secret.example.yaml} both carry a placeholder,
     * {@code scripts/generate-env.sh} fills the placeholder with a key generated for the install,
     * and {@code CardTokenReconciler} re-derives the seeded literals under whatever key arrives. The
     * statement therefore belongs nowhere except the build, which does supply a key of its own and
     * needs the property only so a context refresh under it is not refused. A configuration that
     * names its own key must not carry the statement: a statement about a key nobody published says
     * nothing, and it would survive a rotation as a lie.
     */
    @Test
    @DisplayName("no shipped path claims to run under the published card-token key")
    void noShippedPathClaimsToRunUnderThePublishedKey() {
        String pom = read(platformDirectory().resolve("pom.xml"));
        assertTrue(pom.contains("<" + ACKNOWLEDGEMENT_PROPERTY + ">true</"
                        + ACKNOWLEDGEMENT_PROPERTY + ">"),
                "the build is the fixture environment and states it once, as a property");
        assertEquals(2, occurrences(pom, "<" + ACKNOWLEDGEMENT_PROPERTY + ">${"
                        + ACKNOWLEDGEMENT_PROPERTY + "}</" + ACKNOWLEDGEMENT_PROPERTY + ">"),
                "and passes it to Surefire and to Failsafe, because a context refresh in either"
                        + " phase runs the same refusal");

        for (String artifact : DEPLOYMENT_ARTIFACTS) {
            String text = read(repositoryRoot().resolve(artifact));
            assertFalse(text.contains(ACKNOWLEDGEMENT_VARIABLE + ": \"true\"")
                            || text.contains(ACKNOWLEDGEMENT_VARIABLE + "=true")
                            || text.contains(ACKNOWLEDGEMENT_VARIABLE + ": ${"
                                    + ACKNOWLEDGEMENT_VARIABLE + ":-true}"),
                    artifact + " states that it runs under the card-token key this repository"
                            + " publishes, while it names a placeholder rather than that key. A"
                            + " statement about a key nobody supplied says nothing and survives a"
                            + " rotation as a lie");
        }

        assertTrue(dotenvValue("CARD_TOKEN_SECRET").contains(PLACEHOLDER_MARKER),
                ".env.example must keep a placeholder here, which is what makes the statement"
                        + " above unnecessary rather than merely absent");

        assertFalse(PanMasker.requireCardTokenSecretFitForUse(),
                "this test run holds the build-scope key card-platform/pom.xml declares, not the"
                        + " published one, so the guard must report that the key is not published."
                        + " A true answer here would mean the build had picked up"
                        + " PanMaskerTest.PUBLISHED_DEMO_CARD_TOKEN_SECRET, and every token this"
                        + " suite compares would be recomputable by any reader of this repository");
    }

    @Test
    @DisplayName("the card-token key reaches the two services that derive a token and no others")
    void theCardTokenKeyReachesTheTwoServicesThatDeriveATokenAndNoOthers() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        String secretTemplate =
                read(platformDirectory().resolve("deploy/k8s/31-secret.example.yaml"));

        assertEquals(DERIVING_SERVICE_COUNT, occurrences(compose, "      CARD_TOKEN_SECRET:"),
                "docker-compose.yml must name the card-token key inside the two deriving service"
                        + " blocks and nowhere else. The shared environment block reaches all six,"
                        + " and four of the six derive nothing");
        assertTrue(secretTemplate.contains("name: " + CARD_TOKEN_SECRET_NAME),
                "deploy/k8s/31-secret.example.yaml must declare a Secret of its own for the key, so"
                        + " a Deployment can pull the key without pulling the identity hashes");

        List<String> pulling = new ArrayList<>();
        for (String manifest : SERVICE_MANIFESTS) {
            if (read(platformDirectory().resolve("deploy/k8s/" + manifest))
                    .contains("name: " + CARD_TOKEN_SECRET_NAME)) {
                pulling.add(manifest);
            }
        }
        assertEquals(DERIVING_SERVICE_MANIFESTS, pulling,
                "exactly the two Deployments that derive a card token may pull the key. These"
                        + " pulled it: " + pulling);

        String identitySecret = secretTemplate.substring(
                secretTemplate.indexOf("name: carddemo-identity-secret"),
                secretTemplate.indexOf("name: " + CARD_TOKEN_SECRET_NAME));
        assertFalse(identitySecret.contains("CARD_TOKEN_SECRET:"),
                "the shared identity Secret still carries the card-token key, and all six"
                        + " Deployments pull that Secret whole");
    }

    @Test
    @DisplayName("the build-scope key reaches no deployment artifact")
    void theBuildScopeKeyReachesNoDeploymentArtifact() {
        String buildKey = captured(POM_KEY, read(platformDirectory().resolve("pom.xml")),
                "card-platform/pom.xml");

        assertTrue(buildKey.length() >= PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH,
                "the build-scope card-token key is shorter than the minimum PanMasker accepts, so"
                        + " every derivation in this build would be refused");
        assertFalse(buildKey.contains(PLACEHOLDER_MARKER),
                "the build-scope key is a real value rather than a placeholder, because a build has"
                        + " to derive a token to compare a seeded literal against");

        for (String artifact : DEPLOYMENT_ARTIFACTS) {
            String text = read(repositoryRoot().resolve(artifact));
            assertFalse(text.contains(buildKey), artifact + " carries the build-scope card-token"
                    + " key this repository publishes in pom.xml. A deployment reading it would run"
                    + " on a key every reader of this repository holds");
            assertFalse(text.contains(PUBLISHED_CARD_TOKEN_KEY), artifact + " carries the"
                    + " card-token key this repository once published as a default. The two"
                    + " deriving services refuse it at start-up, so this artifact deploys a service"
                    + " that will not start");
        }
    }

    @Test
    @DisplayName("the build, Compose and Kubernetes all name one card-token version")
    void theBuildComposeAndKubernetesAllNameOneCardTokenVersion() {
        String pom = read(platformDirectory().resolve("pom.xml"));
        String configMap = read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        String dotenvVersion = dotenvValue("CARD_TOKEN_VERSION");

        assertEquals(dotenvVersion, captured(POM_VERSION, pom, "card-platform/pom.xml"),
                "the build and .env.example name different card-token versions, so a token a test"
                        + " derives is not the token a running service derives");
        assertEquals(dotenvVersion, captured(CONFIGMAP_VERSION, configMap, "30-configmap.yaml"),
                "the ConfigMap and .env.example name different card-token versions");
        assertEquals(DERIVING_SERVICE_COUNT,
                occurrences(compose, "CARD_TOKEN_VERSION: ${CARD_TOKEN_VERSION:-"
                        + dotenvVersion + "}"),
                "docker-compose.yml must pass the documented version to the two deriving services");
    }

    @Test
    @DisplayName("this build derives tokens under the build-scope key")
    void thisBuildDerivesTokensUnderTheBuildScopeKey() {
        String buildKey = captured(POM_KEY, read(platformDirectory().resolve("pom.xml")),
                "card-platform/pom.xml");

        assertEquals(buildKey, System.getProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY),
                "the test run did not receive the key card-platform/pom.xml declares, so the"
                        + " comparisons in this class would prove nothing");
        assertEquals(dotenvValue("CARD_TOKEN_VERSION"), PanMasker.cardTokenVersion(),
                "the test run derives under a different version than the configuration names");
    }

    @Test
    @DisplayName("every seeded token belongs to the build-scope key and is re-derived at start-up")
    void everySeededTokenBelongsToTheBuildScopeKeyAndIsReDerivedAtStartUp() {
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
                        + " build-scope key derives, so a literal and the derivation disagree about"
                        + " which card the row is: " + divergent);

        String reconciler = read(platformDirectory().resolve("services/card-service/src/main/java/"
                + "com/carddemo/card/domain/CardTokenReconciler.java"));
        assertTrue(reconciler.contains("implements ApplicationRunner"),
                "the card service must re-derive these literals before it accepts traffic, and an"
                        + " application runner is what Spring Boot invokes in that window. Without"
                        + " it every seeded row would keep a token derived under a key this"
                        + " repository publishes");
        assertTrue(reconciler.contains("PanMasker.cardToken("),
                "the reconciliation must derive through the one helper this platform holds, or a"
                        + " row it rewrites carries a value nothing else agrees with");
    }

    /**
     * Proves no shipped path carries a usable previous key, and none states that it is rotating.
     *
     * <p>The previous key is the read half of a rotation: it makes the token a stored row carries
     * derivable beside the token it should carry, which is the mapping the three stores holding a
     * token and no card number are re-keyed from. It is therefore key material of exactly the same
     * kind as the current key, and a shipped value would publish a key some deployment's rows were
     * taken under.
     *
     * <p>The statement is asserted for the opposite reason. A rotation rewrites a value other stores
     * and every granted authority already name, so an unedited deployment must not perform one.
     */
    @Test
    @DisplayName("no shipped path carries a previous card-token key or states a rotation")
    void noShippedPathCarriesAPreviousKeyOrStatesARotation() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        String configMap = read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));
        String secretTemplate =
                read(platformDirectory().resolve("deploy/k8s/31-secret.example.yaml"));

        assertEquals("", dotenvValue(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE),
                ".env.example carries a previous card-token key. A rotation is the only state that"
                        + " needs one, and a value shipped here is a key some deployment's stored"
                        + " tokens were taken under");
        assertTrue(secretTemplate.contains(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE + ": \"\""),
                "deploy/k8s/31-secret.example.yaml must declare the previous key empty, so applying"
                        + " the template unedited deploys no key and starts no rotation");
        assertTrue(compose.contains(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE + ": ${"
                        + PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE + ":-}"),
                "docker-compose.yml must default the previous key to nothing rather than requiring"
                        + " it, because the ordinary state of this platform is not rotating");

        assertEquals("false", dotenvValue(PanMasker.CARD_TOKEN_ROTATION_VARIABLE),
                ".env.example states that it is rotating. A rotation moves an identity three other"
                        + " stores and every granted SCOPE_CARD authority name, so it is a thing an"
                        + " operator states for one run rather than a shipped default");
        assertTrue(configMap.contains(PanMasker.CARD_TOKEN_ROTATION_VARIABLE + ": \"false\""),
                "deploy/k8s/30-configmap.yaml must ship the statement as false, so the refusal is"
                        + " what an unedited cluster gets");
        assertTrue(compose.contains(PanMasker.CARD_TOKEN_ROTATION_VARIABLE + ": ${"
                        + PanMasker.CARD_TOKEN_ROTATION_VARIABLE + ":-false}"),
                "docker-compose.yml must default the statement to false for the same reason");
        assertEquals("", dotenvValue(PanMasker.CARD_TOKEN_PREVIOUS_VERSION_VARIABLE),
                ".env.example names a previous card-token version while no rotation is in progress."
                        + " Empty, it falls back to the current version, which is what turning only"
                        + " the key over needs");
    }

    /**
     * Proves the three rotation settings reach the one service that can perform a rotation.
     *
     * <p>A rotation derives both halves of the mapping from a card number, and {@code card} is the
     * only table on this platform that holds one. The authorization service derives a token for a
     * decision diagnostic and holds no card number afterwards, so a previous key would reach a
     * service that cannot use it and could only leak.
     */
    @Test
    @DisplayName("the rotation settings reach the card service alone")
    void theRotationSettingsReachTheCardServiceAlone() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));

        for (String variable : List.of(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE,
                PanMasker.CARD_TOKEN_PREVIOUS_VERSION_VARIABLE,
                PanMasker.CARD_TOKEN_ROTATION_VARIABLE)) {
            assertEquals(1, occurrences(compose, "      " + variable + ":"),
                    "docker-compose.yml must name " + variable + " inside the card service block"
                            + " and nowhere else. Only that service holds a card number, so only it"
                            + " can derive the two halves of a mapping row");
        }

        String cardBlock = compose.substring(compose.indexOf("  card-service:"));
        for (String variable : List.of(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE,
                PanMasker.CARD_TOKEN_ROTATION_VARIABLE)) {
            assertTrue(cardBlock.contains("      " + variable + ":"),
                    variable + " is named outside the card service block, so the one service that"
                            + " can rotate does not receive it");
        }
    }

    /**
     * Proves the refusal a security review asked for is shipped, together with its procedure.
     *
     * <p>The finding was that a key change re-derived {@code card.card_token} at start-up and left
     * {@code statement_transaction}, {@code notification_log},
     * {@code authorization_decision} and every granted authority naming a card nobody could reach.
     * Three things answer it: the reconciler refuses a rewrite nobody asked for, it derives the
     * previous token so the rewrite is mappable, and the operator has the statements that apply the
     * mapping to the other schemas.
     */
    @Test
    @DisplayName("the rotation refusal, the dual read and the operator procedure are shipped")
    void theRotationRefusalAndItsProcedureAreShipped() {
        String reconciler = read(platformDirectory().resolve("services/card-service/src/main/java/"
                + "com/carddemo/card/domain/CardTokenReconciler.java"));
        String readme = read(platformDirectory().resolve("services/card-service/README.md"));

        assertTrue(reconciler.contains("PanMasker.cardTokenRotationRequested()"),
                "the reconciler must refuse a rotation nobody asked for, which is the finding a"
                        + " security review raised against a silent start-up rewrite");
        assertTrue(reconciler.contains("PanMasker.previousCardToken("),
                "and derive what the stored row was called, or the rewrite it performs is not"
                        + " mappable and the other three stores cannot be re-keyed");
        assertTrue(reconciler.contains("CardTokenRotationMappingEntity("),
                "and write one mapping row per moved card, which is what an operator exports");

        assertTrue(readme.contains("## Rotating the card-token key"),
                "services/card-service/README.md must carry the procedure, because a refusal that"
                        + " names no procedure leaves an operator with a service that will not"
                        + " start");
        for (String statement : List.of("statement_transaction", "notification_log",
                "authorization_decision", "SCOPE_CARD")) {
            assertTrue(readme.contains(statement),
                    "the procedure must name " + statement + " among the places a rotation leaves"
                            + " behind, or one of them is re-keyed by nobody");
        }
        assertTrue(readme.contains("card_token_rotation_mapping"),
                "and name the table the re-key statements read from");
    }

    /**
     * Proves the two columns that separate a bootstrap from a rotation default to the seeded case.
     *
     * <p>The fifty checked-in literals are a bootstrap: they belong to the build-scope key and are
     * corrected without anybody asking. A default that read as a value this deployment derived would
     * turn every first start-up into a refused rotation.
     */
    @Test
    @DisplayName("the provenance and version columns default to the seeded case")
    void theProvenanceAndVersionColumnsDefaultToTheSeededCase() {
        String migration = read(platformDirectory().resolve("services/card-service/src/main/"
                + "resources/db/migration/V10__card_token_version_and_rotation.sql"));

        assertTrue(migration.contains("ADD COLUMN card_token_version VARCHAR(3) NOT NULL"
                        + " DEFAULT '" + PanMasker.DEFAULT_CARD_TOKEN_VERSION + "'"),
                "the version column must default to the version the seeded literals were taken"
                        + " under, or the fifty rows arrive claiming another one");
        assertTrue(migration.contains("ADD COLUMN card_token_provenance VARCHAR(7) NOT NULL"
                        + " DEFAULT 'SEED'"),
                "and the provenance column must default to the seeded case, or a first start-up"
                        + " reads fifty bootstrap rows as fifty refused rotations");
        assertTrue(migration.contains("CREATE TABLE card_token_rotation_mapping"),
                "the mapping table is the deliverable of a rotation, so the migration that admits"
                        + " one has to create it");
    }

    @Test
    @DisplayName("neither path grants a card authority derived under a key it does not have")
    void neitherPathGrantsACardAuthorityDerivedUnderAKeyItDoesNotHave() {
        for (String artifact : List.of(".env.example", "deploy/k8s/30-configmap.yaml")) {
            String text = read(platformDirectory().resolve(artifact));
            Matcher granted = GRANTED_CARD_SCOPE.matcher(text);
            boolean grantsACardAuthority = granted.find();
            assertFalse(grantsACardAuthority, artifact + " grants a SCOPE_CARD authority. A card"
                    + " token is derived under a key each deployment generates for itself, so an"
                    + " authority written down here names a card under a key no deployment holds,"
                    + " and the route it was meant to open answers 403 anyway");
        }
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
            assertFalse(text.contains(TOKEN_OF_THE_DEMO_CARD_UNDER_THE_PUBLISHED_KEY),
                    artifact + " still carries the token the published demo key produced for that"
                            + " card. Nothing derives that value now, so an artifact holding it is"
                            + " a stale copy rather than a working one");
        }
    }

    /**
     * Every published contract that carries a card token, and the member each one carries it as.
     *
     * <p>A consumer implements the derivation from the description in one of these documents and
     * from nothing else, so a description naming the wrong primitive is a specification for an
     * incompatible token. Two of the three name the token in a property description and the third
     * in a path-parameter description, so the scan reads whole documents rather than parsed nodes:
     * the two forms have no common structure, and the text is what a reader reads.
     */
    private static final List<String> TOKEN_BEARING_CONTRACTS = List.of(
            "libs/event-contracts/src/main/resources/schemas/transaction-authorized-v2.json",
            "libs/event-contracts/src/main/resources/schemas/transaction-posted-v2.json",
            "services/notification-service/src/main/resources/openapi.yaml");

    /**
     * Matches a description of an unkeyed digest, whatever words surround the primitive.
     *
     * <p>The earlier form of this check compared one exact phrase, and the two schemas spelled the
     * claim slightly differently, so both went on describing an unkeyed digest while the check
     * passed. The pattern therefore looks for the primitive with no {@code HMAC} in front of it,
     * which is the property that matters: an unkeyed digest over a sixteen-digit space can be
     * recomputed card number by card number.
     */
    private static final Pattern UNKEYED_DIGEST_CLAIM = Pattern.compile(
            "(?<!HMAC.)(?<!HMAC-)\\bSHA-?256\\b(?![^.]{0,40}\\bkey)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every token-bearing contract names the keyed primitive the code applies")
    void everyTokenBearingContractNamesTheKeyedPrimitiveTheCodeApplies() {
        for (String contract : TOKEN_BEARING_CONTRACTS) {
            String text = read(platformDirectory().resolve(contract));
            String folded = text.replaceAll("\\s+", " ");

            assertTrue(folded.contains("HMAC-SHA-256"),
                    contract + " describes the card token without naming HMAC-SHA-256, which is the"
                            + " primitive PanMasker applies. A consumer coding from this document"
                            + " would derive a different token");
            assertTrue(folded.contains("under a deployment-supplied key"),
                    contract + " does not say the derivation is keyed, so a consumer may implement"
                            + " an unkeyed digest that any reader can invert over the card-number"
                            + " space");

            Matcher unkeyed = UNKEYED_DIGEST_CLAIM.matcher(folded);
            boolean describesAnUnkeyedDigest = unkeyed.find();
            assertFalse(describesAnUnkeyedDigest,
                    contract + " still describes an unkeyed digest: "
                            + (describesAnUnkeyedDigest
                                    ? folded.substring(Math.max(0, unkeyed.start() - 60),
                                            Math.min(folded.length(), unkeyed.end() + 60))
                                    : ""));
        }
    }

    @Test
    @DisplayName("the primitive the contracts name is the primitive the code applies")
    void thePrimitiveTheContractsNameIsThePrimitiveTheCodeApplies() {
        String masker = read(platformDirectory()
                .resolve("libs/cobol-compat/src/main/java/com/carddemo/cobol/PanMasker.java"));

        assertTrue(masker.contains("CARD_TOKEN_ALGORITHM = \"HmacSHA256\""),
                "PanMasker no longer derives under HmacSHA256, so the three published contracts now"
                        + " describe a primitive the code does not apply. Change both together");
        assertTrue(masker.contains("Mac.getInstance(CARD_TOKEN_ALGORITHM)"),
                "PanMasker no longer takes a message authentication code, so the derivation may be"
                        + " unkeyed however the constant is named");
    }

    /**
     * The token the retired unkeyed digest produced for the card the demo authority names.
     *
     * <p>It is a token and not a card number, so writing it down discloses nothing, and it is the
     * one value that tells a stale artifact from a current one.
     */
    private static final String RETIRED_TOKEN_OF_THE_SEEDED_DEMO_CARD =
            "1134636222d1a2485d20203d0e970c72124893eb01be73a5fde5fdcccc2c4ac9";

    /**
     * The token the published demo key produced for the card the demo authority used to name.
     *
     * <p>It is a token and not a card number, so writing it down discloses nothing, and no key this
     * platform now uses derives it. An artifact still carrying it is a copy nobody re-derived when
     * the published key was withdrawn.
     */
    private static final String TOKEN_OF_THE_DEMO_CARD_UNDER_THE_PUBLISHED_KEY =
            "98433fc178365d966539a9156365078b3b382bbb8d75143e37e49a18290d58c0";

    /** Marks a value this repository publishes as an example rather than as a credential. */
    private static final String PLACEHOLDER_MARKER = "REPLACE";

    /**
     * The card-token key this repository published as the effective default of every deployment
     * path, and which both deriving services now refuse at start-up.
     */
    private static final String PUBLISHED_CARD_TOKEN_KEY =
            "carddemo-demo-card-token-key-not-for-production";

    /** Name of the Kubernetes Secret carrying the card-token key. */
    private static final String CARD_TOKEN_SECRET_NAME = "carddemo-card-token-secret";

    /** Services that derive a card token: the authorization service and the card service. */
    private static final int DERIVING_SERVICE_COUNT = 2;

    /** Every service Deployment manifest, in apply order. */
    private static final List<String> SERVICE_MANIFESTS = List.of(
            "40-authorization-service.yaml", "41-ledger-posting-service.yaml",
            "42-fraud-detection-service.yaml", "43-notification-service.yaml",
            "44-account-service.yaml", "45-card-service.yaml");

    /** The two manifests permitted to pull {@link #CARD_TOKEN_SECRET_NAME}. */
    private static final List<String> DERIVING_SERVICE_MANIFESTS =
            List.of("40-authorization-service.yaml", "45-card-service.yaml");

    /**
     * Every artifact that supplies configuration to a running deployment.
     *
     * <p>The build file is absent on purpose: it is the one place the build-scope key belongs, and
     * the scan below asserts that value appears in none of these.
     */
    private static final List<String> DEPLOYMENT_ARTIFACTS = List.of(
            "card-platform/.env.example",
            "card-platform/docker-compose.yml",
            "card-platform/deploy/k8s/30-configmap.yaml",
            "card-platform/deploy/k8s/31-secret.example.yaml",
            ".github/workflows/ci.yml");

    /**
     * Counts non-overlapping occurrences of one token.
     *
     * @param text  the text to read
     * @param token the token to count
     * @return how many times it occurs
     */
    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    /**
     * Returns the repository root, being the parent of the {@code card-platform} directory.
     *
     * @return that directory
     */
    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent();
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
