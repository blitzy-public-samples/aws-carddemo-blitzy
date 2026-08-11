package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Protects the Rule 4 presentation and Rule 5 prose-validation deliverables.
 *
 * <p>The checks inspect the shipped artifacts rather than a generated representation. A presentation
 * that compiles but loses a slide visual, a lifecycle hook, or a pinned dependency is not the
 * required deliverable.
 *
 * <p>Where a check asserts a fact about the platform, it reads that fact from the artifact that owns
 * it and compares the deck against it: the flag register owns the finding count, the traceability
 * matrix owns the source classification, the service listener configuration owns the failure
 * routing, and the services directory owns the service and schema inventory. Both sides of every
 * such comparison are read at run time, so a deck that drifts from its own evidence fails here
 * instead of shipping.
 */
class PresentationAndProseContractTest {

    /** Heading the flagged-rule register opens its numbered rows under. */
    private static final String REGISTER_SECTION_START = "## Register coverage";

    /** Heading that closes those rows, so later tables of the register are not counted. */
    private static final String REGISTER_SECTION_END =
            "## The largest item: a named program that does not exist";

    /** One {@code class="..."} attribute of the deck's markup. */
    private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("class=\"([^\"]*)\"");

    private static final Pattern SECTION =
            Pattern.compile("(?s)<section\\b([^>]*)>(.*?)</section>");
    private static final Pattern RESOURCE = Pattern.compile("(?:href|src)=\"([^\"]+)\"");
    private static final Pattern REPORT =
            Pattern.compile(
                    "(?ms)^### \\d+\\. .*?$(.*?)(?=^### \\d+\\. |^## Exemptions applied)");
    private static final Pattern FORBIDDEN_DECK_WORD =
            Pattern.compile(
                    "(?i)\\b(?:leverage|utilize|facilitate|synergy|holistic|paradigm|very|really|quite|rather)\\b");
    private static final Pattern FORBIDDEN_SEQUENCE_WORD =
            Pattern.compile(
                    "(?i)\\b(?:gantt|timeline|week|sprint|quarter|january|february|march|april|may|june|july|august|september|october|november|december)\\b");
    private static final Pattern STYLE_BLOCK = Pattern.compile("(?s)<style>(.*?)</style>");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?s)<script\\b[^>]*>(.*?)</script>");
    private static final Pattern MODULE_SCRIPT =
            Pattern.compile("(?s)<script type=\"module\">(.*?)</script>");
    private static final Pattern MERMAID_BLOCK =
            Pattern.compile("(?s)<pre class=\"mermaid\">(.*?)</pre>");
    private static final Pattern MERMAID_NODE =
            Pattern.compile(
                    "([A-Za-z][A-Za-z0-9_]*)(?:\\[\\(\"|\\[\"|\\{\\{\")([^\"]*)\"(?:\\)\\]|\\]|\\}\\})");
    private static final Pattern MERMAID_EDGE =
            Pattern.compile(
                    "([A-Za-z][A-Za-z0-9_]*)\\s*(?:-\\.->|==>|-->|~~~)\\s*(?:\\|[^|]*\\|\\s*)?([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern TABLE = Pattern.compile("(?s)<table\\b[^>]*>(.*?)</table>");
    private static final Pattern TABLE_HEADER = Pattern.compile("<th\\b([^>]*)>");
    private static final Pattern TABLE_HEAD = Pattern.compile("(?s)<thead\\b[^>]*>(.*?)</thead>");
    private static final Pattern KPI_CARD =
            Pattern.compile(
                    "(?s)<div class=\"kpi-value\">(.*?)</div>\\s*<div class=\"kpi-label\">(.*?)</div>");
    private static final Pattern REGISTER_ROW = Pattern.compile("(?m)^\\|\\s*(\\d+)\\s*\\|");
    private static final Pattern SCHEMA_STEM = Pattern.compile("carddemo_([a-z]+)");
    private static final Pattern WORD_CHARACTER = Pattern.compile("[0-9A-Za-z]");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");

    /** Rule 4 caps the body text of a content slide. */
    private static final int RULE_FOUR_WORD_LIMIT = 40;

    /**
     * The Mermaid bundle this deck runs, and the digest its bytes answer to.
     *
     * <p>A review found the previous 11.4.0 line carrying nine advisories, loaded by an import
     * expression that can hold no integrity attribute. Both halves moved: the version is the fixed
     * line, and the single-file bundle replaces the module graph so one digest covers every byte
     * rather than the 30 KB entry of a graph whose chunks were fetched unverified.
     */
    private static final String MERMAID_BUNDLE = "mermaid@11.16.1/dist/mermaid.min.js";

    /** The digest of that bundle, as jsDelivr serves it. */
    private static final String MERMAID_DIGEST =
            "sha384-aBQXj4hK6Jm05i7aQAsUV3bLdSUrHX1BGYfMB0166TtWt/RRaw+h0Eelme9OCOvy";

    /**
     * Directives the deck's Content Security Policy has to state, each with the value it takes.
     *
     * <p>{@code default-src 'none'} is what makes the rest of the list a list of exceptions rather
     * than a set of additions. Style is the one relaxation and it is deliberate: reveal.js writes
     * element styles and Mermaid appends a style element beside every drawing, so neither can be
     * digested ahead of time.
     */
    private static final Map<String, String> POLICY_DIRECTIVES =
            Map.ofEntries(
                    Map.entry("default-src", "'none'"),
                    Map.entry("style-src",
                            "https://cdn.jsdelivr.net https://fonts.googleapis.com 'unsafe-inline'"),
                    Map.entry("font-src", "https://fonts.gstatic.com"),
                    Map.entry("img-src", "data:"),
                    Map.entry("connect-src", "https://cdn.jsdelivr.net"),
                    Map.entry("base-uri", "'none'"),
                    Map.entry("object-src", "'none'"),
                    Map.entry("frame-src", "'none'"),
                    Map.entry("form-action", "'none'"));

    /** The three classic assets that can carry a Subresource Integrity digest. */
    private static final List<String> INTEGRITY_PINNED_ASSETS =
            List.of(
                    "reveal.js@5.1.0/dist/reveal.css",
                    "reveal.js@5.1.0/dist/reveal.js",
                    "lucide@0.460.0/dist/umd/lucide.min.js");

    /** The event families the platform contract requires, independent of their version count. */
    private static final List<String> CORE_EVENT_FAMILIES =
            List.of(
                    "transaction-authorized",
                    "transaction-declined",
                    "transaction-posted",
                    "fraud-flagged",
                    "fraud-cleared");

    /** The one target that carries no digest, because a report cannot hold its own. */
    private static final int SELF_TARGET = 10;

    /**
     * The nineteen Rule 5 targets, in the order {@code docs/prose-validation.md} numbers them.
     *
     * <p>Every path is resolved against the platform root, so {@code ../README.md} reaches the
     * repository-root guide that the report scores one section of.
     */
    private static final List<String> PROSE_TARGETS =
            List.of(
                    "docs/decision-log.md",
                    "docs/traceability-matrix.md",
                    "docs/architecture-before-after.md",
                    "docs/event-flow.md",
                    "docs/data-model.md",
                    "docs/onboarding.md",
                    "docs/suggested-next-tasks.md",
                    "docs/business-rule-flags.md",
                    "docs/equivalence-results.md",
                    "docs/prose-validation.md",
                    "README.md",
                    "services/authorization-service/README.md",
                    "services/ledger-posting-service/README.md",
                    "services/fraud-detection-service/README.md",
                    "services/notification-service/README.md",
                    "services/account-service/README.md",
                    "services/card-service/README.md",
                    "../README.md",
                    "presentation/executive-summary.html");

    /**
     * The twenty-two principle rows every report section must carry, spelled as the rule spells them.
     *
     * <p>A previous revision of the report renamed fourteen of these, which made the scorecard
     * unverifiable against the rule it claimed to apply. The names are asserted literally here.
     */
    private static final List<String> PRINCIPLE_ROWS =
            List.of(
                    "V1: Find a subject you care about",
                    "V2: Do not ramble",
                    "V3: Keep it simple",
                    "V4: Have the guts to cut",
                    "V5: Sound like yourself",
                    "V6: Say what you mean",
                    "V7: Pity the reader",
                    "V8: Start close to the end",
                    "V9: The Dignity Test",
                    "V10: The Indifference Detector",
                    "V11: The Indianapolis Test",
                    "V12: Humor as Trust Signal",
                    "A1: Plate Glass Clarity",
                    "A2: Short Words, Simple Structures",
                    "A3: Logical Sequence",
                    "A4: Ideas Carry the Weight",
                    "A5: Conversational Informality",
                    "A6: No Ornamental Language",
                    "A7: Functional Dialogue",
                    "A8: Anticipate Reader Questions",
                    "A9: Efficiency Over Polish",
                    "A10: Respect the Reader's Intelligence");

    /** Principles an over-length sentence offends. */
    private static final Set<String> SENTENCE_PRINCIPLES = Set.of("V3", "A2");

    /** Principles an over-long paragraph offends. */
    private static final Set<String> PARAGRAPH_PRINCIPLES = Set.of("V2");

    /** Principles a buzzword offends. */
    private static final Set<String> BUZZWORD_PRINCIPLES = Set.of("V5", "A6");

    private static final int LONG_SENTENCE_WORDS = 30;
    private static final int LONG_PARAGRAPH_SENTENCES = 5;
    private static final int MINIMUM_REWRITE_REDUCTION_PERCENT = 15;

    private static final Pattern PROSE_BUZZWORD =
            Pattern.compile(
                    "(?i)\\b(?:leverage|utilize|facilitate|synergy|holistic|paradigm|robust|seamless"
                            + "|best-of-breed|going forward)\\b");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?ms)^```.*?^```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*+]\\s+|\\d+\\.\\s+)");
    private static final Pattern DECIMAL_POINT = Pattern.compile("(\\d)\\.(\\d)");
    private static final Pattern SINGLE_INITIAL = Pattern.compile("\\b([A-Z])\\.");
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("[.!?][\"'\u2019\u201d)\\]*]*(?=\\s+[*\"\u201c(\\[\\dA-Z])");
    private static final Pattern WORD_ORNAMENT = Pattern.compile("[*_>]");
    private static final List<String> ABBREVIATIONS =
            List.of(
                    "e.g.", "i.e.", "etc.", "vs.", "approx.", "cf.", "Mr.", "Ms.", "Dr.", "No.",
                    "Fig.", "al.");
    private static final String GUARD = "\u0000";
    private static final String SCORED_README_SECTION = "## Modernized card platform";

    /** What a fresh measurement of one target found. */
    private record ProseMeasurement(
            int longSentences,
            int longParagraphs,
            int buzzwords,
            String worstSentence,
            int worstSentenceWords,
            List<String> worstParagraph) {

        int hard() {
            return buzzwords;
        }

        int soft() {
            return longSentences + longParagraphs;
        }

        String verdict() {
            if (hard() >= 4) {
                return "ROUGH DRAFT";
            }
            return hard() >= 1 || soft() >= 4 ? "NEEDS WORK" : "CLEAN";
        }
    }

    private static Path platformRoot;
    private static String deck;
    private static String deckMarkup;
    private static String deckStyle;
    private static String deckScript;
    private static String prose;
    private static String flagRegister;
    private static String traceability;
    private static List<String> slides;
    private static List<String> diagrams;

    /** Service module name to the stem of the one private schema it owns. */
    private static Map<String, String> serviceSchemas;

    /** Services whose listeners route a refused record to their own source topic plus a suffix. */
    private static Set<String> sourceSuffixServices;

    /** Services that reach only the one shared dead-letter topic. */
    private static Set<String> sharedFallbackServices;

    /** The shared dead-letter topic name, as every service configuration defaults it. */
    private static String sharedDeadLetterTopic;

    /** The suffix a source-routing listener appends to its own topic. */
    private static String deadLetterSuffix;

    @BeforeAll
    static void loadArtifacts() throws IOException {
        platformRoot = locatePlatformRoot();
        deck = Files.readString(platformRoot.resolve("presentation/executive-summary.html"));
        prose = Files.readString(platformRoot.resolve("docs/prose-validation.md"));
        flagRegister = Files.readString(platformRoot.resolve("docs/business-rule-flags.md"));
        traceability = Files.readString(platformRoot.resolve("docs/traceability-matrix.md"));

        deckStyle = firstGroup(STYLE_BLOCK, deck, "inline <style> block");
        deckScript = firstGroup(MODULE_SCRIPT, deck, "inline <script type=\"module\"> block");
        deckMarkup = SCRIPT_BLOCK.matcher(STYLE_BLOCK.matcher(deck).replaceAll(" ")).replaceAll(" ");

        slides = new ArrayList<>();
        Matcher section = SECTION.matcher(deckMarkup);
        while (section.find()) {
            slides.add(section.group(2));
        }

        diagrams = new ArrayList<>();
        Matcher diagram = MERMAID_BLOCK.matcher(deckMarkup);
        while (diagram.find()) {
            diagrams.add(diagram.group(1));
        }

        serviceSchemas = new LinkedHashMap<>();
        sourceSuffixServices = new LinkedHashSet<>();
        sharedFallbackServices = new LinkedHashSet<>();
        for (String service : listServiceModules()) {
            String configuration =
                    Files.readString(
                            platformRoot.resolve(
                                    "services/" + service + "/src/main/resources/application.yml"));
            serviceSchemas.put(service, singleSchemaStem(service, configuration));

            String topic = defaultOf(configuration, "dead-letter");
            assertNotNull(topic, service + " configures no shared dead-letter topic");
            if (sharedDeadLetterTopic == null) {
                sharedDeadLetterTopic = topic;
            }
            assertEquals(
                    sharedDeadLetterTopic,
                    topic,
                    "One shared dead-letter topic means one name, and " + service + " differs");

            String suffix = defaultOf(configuration, "dead-letter-suffix");
            if (suffix == null) {
                sharedFallbackServices.add(service);
                continue;
            }
            sourceSuffixServices.add(service);
            if (deadLetterSuffix == null) {
                deadLetterSuffix = suffix;
            }
            assertEquals(
                    deadLetterSuffix,
                    suffix,
                    "One wire shape per dead-letter topic means one suffix, and "
                            + service
                            + " differs");
        }
    }

    @Test
    @DisplayName("the presentation and prose-validation artifacts are shipped at their contracted paths")
    void theArtifactsExistAtTheirContractedPaths() {
        assertTrue(Files.isRegularFile(platformRoot.resolve("presentation/executive-summary.html")));
        assertTrue(Files.isRegularFile(platformRoot.resolve("docs/prose-validation.md")));
        assertFalse(Files.exists(platformRoot.resolve("presentation/executive-summary.css")));
        assertFalse(Files.exists(platformRoot.resolve("presentation/executive-summary.js")));
    }

    @Test
    @DisplayName("the deck has exactly sixteen unnested slides")
    void theDeckHasExactlySixteenUnnestedSlides() {
        slides.forEach(
                body ->
                        assertFalse(
                                body.contains("<section"),
                                "A top-level slide must not contain a vertical sub-slide"));
        assertEquals(16, slides.size());
        assertEquals(16, occurrences(deckMarkup, "</section>"));
    }

    @Test
    @DisplayName("every slide contains a rendering non-text visual")
    void everySlideContainsARenderingNonTextVisual() {
        for (int index = 0; index < slides.size(); index++) {
            String body = slides.get(index);
            boolean hasVisual =
                    body.contains("data-lucide=")
                            || body.contains("<table")
                            || body.contains("<pre class=\"mermaid\"")
                            || body.contains("class=\"kpi-grid\"")
                            || body.contains("class=\"icon-row\"")
                            || body.contains("class=\"accent-bar\"");
            assertTrue(hasVisual, "Slide " + (index + 1) + " is text-only");
        }
        assertEquals(16, slides.size());
    }

    @Test
    @DisplayName("every content slide stays within the Rule 4 body-word limit")
    void everyContentSlideStaysWithinTheRuleFourWordLimit() {
        for (int index = 0; index < slides.size(); index++) {
            int words = bodyWordCount(slides.get(index));
            assertTrue(
                    words <= RULE_FOUR_WORD_LIMIT,
                    "Slide "
                            + (index + 1)
                            + " carries "
                            + words
                            + " body words, above the Rule 4 limit of "
                            + RULE_FOUR_WORD_LIMIT);
        }
    }

    @Test
    @DisplayName("the deck pins the required libraries and has no local media dependency")
    void theDeckPinsRequiredLibrariesAndHasNoLocalMediaDependency() {
        assertTrue(deck.contains("reveal.js@5.1.0/dist/reveal.css"));
        assertTrue(deck.contains("reveal.js@5.1.0/dist/reveal.js"));
        assertTrue(deck.contains(MERMAID_BUNDLE));
        assertTrue(deck.contains("lucide@0.460.0/dist/umd/lucide.min.js"));
        assertTrue(deck.contains("family=Fira+Code"));
        assertTrue(deck.contains("family=Inter"));
        assertTrue(deck.contains("family=Space+Grotesk"));
        assertFalse(deck.contains("<img"));
        assertFalse(deck.contains("blitzy-reveal-theme"));

        Matcher resource = RESOURCE.matcher(deck);
        while (resource.find()) {
            assertTrue(
                    resource.group(1).startsWith("https://"),
                    "A deck resource is not an absolute HTTPS URL: " + resource.group(1));
        }
    }

    @Test
    @DisplayName("every classic content-delivery asset carries a digest and an anonymous origin")
    void everyClassicContentDeliveryAssetCarriesADigestAndAnAnonymousOrigin() {
        for (String asset : INTEGRITY_PINNED_ASSETS) {
            String tag = enclosingTag(deck, asset);
            assertTrue(
                    tag.contains("integrity=\"sha384-"),
                    "No SHA-384 digest pins " + asset + ": " + tag);
            assertTrue(
                    tag.contains("crossorigin=\"anonymous\""),
                    "A digest without an anonymous origin cannot be verified for " + asset);
        }
        assertEquals(INTEGRITY_PINNED_ASSETS.size(), occurrences(deck, "integrity=\"sha384-"));
        // The fourth library is pinned too, on the element the module creates for it. Its digest is
        // stated here rather than read out of the deck, so a bundle swapped for another under the
        // same version fails this test instead of passing it.
        assertTrue(deck.contains(MERMAID_DIGEST),
                "Mermaid's bundle is not pinned to the bytes this deck was verified against");
        assertTrue(enclosingTag(deck, "fonts.googleapis.com/css2").contains("rel=\"stylesheet\""),
                "the font stylesheet is the one asset carrying no digest, and it has to stay the"
                        + " ordinary stylesheet link the head comment says it is");
        assertFalse(enclosingTag(deck, "fonts.googleapis.com/css2").contains("integrity="),
                "a digest over a stylesheet generated per user agent would fail for some readers"
                        + " and pass for others");
    }

    /**
     * Asserts the deck states a policy, and that the policy admits its own script and nothing else.
     *
     * <p>A review found no policy at all. The deck is one file opened from disk, so a header is not
     * available and a meta element is the only place a policy can be stated. Three properties are
     * read. Every directive is present with the value it was verified under. The inline module is
     * admitted by the digest of its own text rather than by an unsafe-inline keyword, which is what
     * makes an injected script inert. And the digest is recomputed here from the script the deck
     * actually holds, so an edit to that script fails this test rather than silently disabling the
     * only script the policy allows.
     */
    @Test
    @DisplayName("the deck states a policy that admits its own module by digest and nothing else")
    void theDeckStatesAPolicyThatAdmitsItsOwnModuleByDigest() {
        String policy = firstGroup(
                Pattern.compile("(?s)<meta http-equiv=\"Content-Security-Policy\" content=\"(.*?)\">"),
                deck,
                "Content-Security-Policy meta element");
        String flattened = policy.replaceAll("\\s+", " ").trim();

        POLICY_DIRECTIVES.forEach((directive, value) ->
                assertTrue(flattened.contains(directive + " " + value + ";")
                                || flattened.endsWith(directive + " " + value),
                        "the policy states no " + directive + " of " + value + ": " + flattened));

        String expected = "'sha384-" + base64Sha384(deckScript) + "'";
        assertTrue(flattened.contains("script-src https://cdn.jsdelivr.net " + expected + ";"),
                "the policy admits the inline module by the digest of its own text, and that digest"
                        + " is now " + expected + ". An edit to the module changes it, and a policy"
                        + " naming the old one blocks the only script this deck runs");
        assertFalse(flattened.contains("script-src 'unsafe-inline'")
                        || flattened.contains("'unsafe-inline' https://cdn.jsdelivr.net"),
                "a script-src carrying unsafe-inline would admit a script injected into this"
                        + " document, which is the whole point of digesting the one that belongs");
        assertFalse(flattened.contains("'unsafe-eval'"),
                "neither library evaluates a string, measured against both pinned bundles");
        assertTrue(deckScript.contains("securityLevel: \"strict\""),
                "the diagram library has to escape the labels it draws and refuse a click binding,"
                        + " which is what its strict level does");
    }

    /**
     * Digests one string the way a policy and an integrity attribute state a digest.
     *
     * @param content the text to digest
     * @return the SHA-384 digest, base64 encoded
     */
    private static String base64Sha384(String content) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-384")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-384 must be present in the JDK", impossible);
        }
    }

    @Test
    @DisplayName("the inline visual system defines and uses every required class and token")
    void theInlineVisualSystemDefinesAndUsesEveryRequiredClassAndToken() {
        List<String> properties =
                List.of(
                        "--blitzy-primary: #5B39F3;",
                        "--blitzy-primary-dark: #2D1C77;",
                        "--blitzy-primary-navy: #1A105F;",
                        "--blitzy-primary-light: #7A6DEC;",
                        "--blitzy-primary-deep: #4101DB;",
                        "--blitzy-accent-teal: #94FAD5;",
                        "--blitzy-surface-0: #FFFFFF;",
                        "--blitzy-surface-1: #F4EFF6;",
                        "--blitzy-surface-2: #F2F0FE;",
                        "--blitzy-surface-3: #F5F5F5;",
                        "--blitzy-border: #D9D9D9;",
                        "--blitzy-border-soft: rgba(91, 57, 243, 0.18);",
                        "--blitzy-text: #333333;",
                        "--blitzy-text-muted: #999999;",
                        "--blitzy-text-invert: #FFFFFF;",
                        "--ff-body: 'Inter', system-ui, sans-serif;",
                        "--ff-display: 'Space Grotesk', 'Inter', sans-serif;",
                        "--ff-mono: 'Fira Code', 'Courier New', monospace;",
                        "--gradient-hero: linear-gradient(68deg, #7A6DEC 15.56%, #5B39F3 62.74%,"
                                + " #4101DB 84.44%);",
                        "--gradient-divider: linear-gradient(135deg, #2D1C77 0%, #5B39F3 100%);",
                        "--gradient-accent-bar: linear-gradient(90deg, #5B39F3 0%, #94FAD5"
                                + " 100%);");
        properties.forEach(property -> assertTrue(deckStyle.contains(property), property));

        List<String> classes =
                List.of(
                        "slide-title",
                        "slide-divider",
                        "slide-closing",
                        "kpi-card",
                        "kpi-grid",
                        "kpi-value",
                        "kpi-label",
                        "kpi-icon",
                        "eyebrow",
                        "accent-bar",
                        "brand-lockup",
                        "hero-icon",
                        "icon-row");
        Set<String> applied = classesAppliedInMarkup(deck);
        classes.forEach(
                className -> {
                    assertTrue(
                            deckStyle.contains("." + className),
                            "The inline style block defines no rule for " + className);
                    assertTrue(
                            applied.contains(className),
                            "Required visual class is styled and never applied to an element: "
                                    + className
                                    + ". The deck carries the rule and no slide uses it, so the"
                                    + " visual it defines does not appear. Classes applied in the"
                                    + " markup: "
                                    + applied);
                });
    }

    /**
     * Returns every class name applied to an element in the deck's markup.
     *
     * <p>The style block is removed first, and that is the whole point. An earlier form of the
     * assertion above read {@code deck.contains("." + className)} and then
     * {@code deck.contains(className)}: the second follows from the first, because a rule named
     * {@code .kpi-card} contains the text {@code kpi-card}. A review found it could not fail for the
     * reason it existed — a class defined in the theme and applied to nothing passed both checks.
     * Collecting the {@code class} attributes of the remaining markup asks the question the second
     * assertion was written to ask.</p>
     *
     * @param deck the whole HTML document
     * @return the class names the markup applies, each once
     */
    private static Set<String> classesAppliedInMarkup(String deck) {
        String markup = deck.replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ");
        assertFalse(markup.contains("--blitzy-primary:"),
                "the style block has to be removed before the markup is read, or every styled class"
                        + " counts as an applied one and the assertion is circular again");

        Set<String> applied = new LinkedHashSet<>();
        Matcher attribute = CLASS_ATTRIBUTE.matcher(markup);
        while (attribute.find()) {
            for (String name : attribute.group(1).trim().split("\\s+")) {
                if (!name.isEmpty()) {
                    applied.add(name);
                }
            }
        }
        assertFalse(applied.isEmpty(), "the deck applies no class at all, so it carries no markup"
                + " this assertion can measure");
        return applied;
    }

    @Test
    @DisplayName("small text and decoration use tokens that clear the contrast floor")
    void smallTextAndDecorationUseTokensThatClearTheContrastFloor() {
        assertTrue(deckStyle.contains("--blitzy-text-muted: #999999;"));
        assertFalse(
                deckStyle.contains("color: var(--blitzy-text-muted)"),
                "#999999 on any deck surface falls below the contrast floor, so it must not"
                        + " colour text");

        assertTrue(declarationOf(".kpi-label").contains("color: var(--blitzy-text)"));
        assertTrue(declarationOf(".diagram-legend").contains("color: var(--blitzy-text)"));
        assertTrue(declarationOf(".reveal .slide-number").contains("color: var(--blitzy-text)"));

        String eyebrow = declarationOf(".slide-title .eyebrow");
        assertTrue(eyebrow.contains("color: var(--blitzy-accent-teal)"));
        assertTrue(
                eyebrow.contains("background: var(--blitzy-primary-navy)"),
                "The teal eyebrow needs its own dark backing to clear the contrast floor");

        assertEquals(
                slides.size(),
                occurrences(deckMarkup, "<span class=\"slide-number\" aria-hidden=\"true\">"),
                "Every decorative slide number must be hidden from assistive technology");
    }

    @Test
    @DisplayName("the deck honours reduced motion and restores a visible keyboard focus ring")
    void theDeckHonoursReducedMotionAndRestoresAVisibleKeyboardFocusRing() {
        assertTrue(deckStyle.contains("@media (prefers-reduced-motion: reduce)"));
        assertTrue(deckStyle.contains(".reveal .controls button:focus-visible"));
        assertTrue(deckStyle.contains(":focus-visible"));
        assertTrue(declarationOf(".reveal .controls button:focus-visible").contains("outline:"));
        assertTrue(deck.contains("transition: \"slide\""));
    }

    /**
     * Requires every data table to announce itself and to scope every header cell it declares.
     *
     * <p>A caption is what a screen reader reads before the cells, so a table without one arrives as
     * a grid of values with no subject.
     *
     * <p>Scope is checked where it is declared rather than uniformly. A cell in the head row heads a
     * column and says {@code scope="col"}. The first cell of each body row heads that row and says
     * {@code scope="row"}, which is what lets a value in the second column be read as the answer to
     * the row it sits on. Requiring {@code col} of every header cell would reject the row headers the
     * deck's four tables use, and those row headers are the reason the tables read at all. A header
     * cell declaring no scope is the case this refuses.
     */
    @Test
    @DisplayName("every data table is announced with a caption and scoped header cells")
    void everyDataTableIsAnnouncedWithACaptionAndColumnScopes() {
        Matcher table = TABLE.matcher(deckMarkup);
        int tables = 0;
        while (table.find()) {
            tables++;
            String body = table.group(1);
            assertTrue(body.contains("<caption"), "Table " + tables + " carries no caption");

            int columnHeaders = 0;
            Matcher head = TABLE_HEAD.matcher(body);
            while (head.find()) {
                Matcher headCell = TABLE_HEADER.matcher(head.group(1));
                while (headCell.find()) {
                    columnHeaders++;
                    assertTrue(
                            headCell.group(1).contains("scope=\"col\""),
                            "Head cell "
                                    + columnHeaders
                                    + " of table "
                                    + tables
                                    + " declares no column scope");
                }
            }
            assertTrue(columnHeaders > 0, "Table " + tables + " declares no column header");

            Matcher header = TABLE_HEADER.matcher(body);
            int headers = 0;
            while (header.find()) {
                headers++;
                assertTrue(
                        header.group(1).contains("scope=\"col\"")
                                || header.group(1).contains("scope=\"row\""),
                        "Header " + headers + " of table " + tables + " declares no scope");
            }
            assertTrue(headers > 0, "Table " + tables + " has no header cell");
        }
        assertEquals(4, tables);
    }

    @Test
    @DisplayName("every diagram carries an accessible title and description")
    void everyDiagramCarriesAnAccessibleTitleAndDescription() {
        assertEquals(3, diagrams.size());
        for (int index = 0; index < diagrams.size(); index++) {
            String diagram = diagrams.get(index);
            assertTrue(
                    diagram.contains("accTitle:"),
                    "Diagram " + (index + 1) + " declares no accessible title");
            assertTrue(
                    diagram.contains("accDescr:"),
                    "Diagram " + (index + 1) + " declares no accessible description");
        }
    }

    @Test
    @DisplayName("the three named diagrams cover both states, event flow and dependency order")
    void theThreeNamedDiagramsCoverBothStatesEventFlowAndDependencyOrder() {
        assertEquals(3, occurrences(deckMarkup, "<pre class=\"mermaid\">"),
                "Rule 2 requires all three views, and each travels as one Mermaid block");
        assertTrue(deckMarkup.contains("Figure 1 — From shared files to owned schemas"));
        assertTrue(deckMarkup.contains("Figure 2 — One outcome event starts the work"));
        assertTrue(deckMarkup.contains("Figure 3 — Each capability starts from a proven contract"));
        assertTrue(deckMarkup.contains("Continuous equivalence suite"));

        Map<String, String> firstFigure = nodeLabels(diagrams.get(0));
        assertTrue(
                firstFigure.values().stream().anyMatch(label -> label.startsWith("Before")),
                "Rule 2 requires the before state to be labelled as such");
        assertTrue(
                firstFigure.values().stream().anyMatch(label -> label.startsWith("After")),
                "Rule 2 requires the after state to be labelled as such");

        assertTrue(deckMarkup.contains("transaction.authorized"));
        assertTrue(deckMarkup.contains("transaction.posted"));
        assertTrue(deckMarkup.contains("fraud.assessed"));
        assertTrue(deckMarkup.contains("carddemo.dead-letter"));

        assertFalse(FORBIDDEN_SEQUENCE_WORD.matcher(deck).find());
        String deckWithoutRequiredStageDimensions =
                deck.replace("width: 1920", "").replace("height: 1080", "");
        assertFalse(
                Pattern.compile("\\b(?:19|20)\\d{2}\\b")
                        .matcher(deckWithoutRequiredStageDimensions)
                        .find());
    }

    @Test
    @DisplayName("the diagrams show every service reaching one private schema and no other consumer")
    void theDiagramsShowEveryServiceReachingOnePrivateSchemaAndNoOtherConsumer() {
        Map<String, String> firstFigure = nodeLabels(diagrams.get(0));
        Set<String> drawn = new LinkedHashSet<>(firstFigure.values());
        serviceSchemas.forEach(
                (service, schema) -> {
                    assertTrue(
                            drawn.contains(service),
                            "Figure 1 omits the service " + service);
                    assertTrue(
                            drawn.contains(schema + " schema"),
                            "Figure 1 omits the private schema of " + service);
                });
        assertEquals(
                serviceSchemas.size(),
                drawn.stream().filter(label -> label.endsWith(" schema")).count(),
                "Figure 1 must show one private schema per service and no more");

        for (int index = 0; index < diagrams.size(); index++) {
            String diagram = diagrams.get(index);
            Map<String, String> labels = nodeLabels(diagram);
            Set<String> consumerIdentifiers = new LinkedHashSet<>();
            labels.forEach(
                    (identifier, label) -> {
                        if (sourceSuffixServices.contains(label)) {
                            consumerIdentifiers.add(identifier);
                        }
                    });
            for (String[] edge : edges(diagram)) {
                assertFalse(
                        consumerIdentifiers.contains(edge[0])
                                && consumerIdentifiers.contains(edge[1]),
                        "Figure "
                                + (index + 1)
                                + " couples two event consumers directly: "
                                + labels.get(edge[0])
                                + " to "
                                + labels.get(edge[1]));
            }
        }
    }

    @Test
    @DisplayName("the deck depicts the failure routing the services are configured to use")
    void theDeckDepictsTheFailureRoutingTheServicesAreConfiguredToUse() {
        assertFalse(sourceSuffixServices.isEmpty());
        assertFalse(sharedFallbackServices.isEmpty());

        String eventFlow = diagrams.get(1);
        Map<String, String> labels = nodeLabels(eventFlow);
        Set<String> suffixSinks = identifiersLabelledWith(labels, deadLetterSuffix);
        Set<String> sharedSinks = identifiersLabelledWith(labels, sharedDeadLetterTopic);
        assertFalse(suffixSinks.isEmpty(), "Figure 2 draws no source-specific dead-letter route");
        assertFalse(sharedSinks.isEmpty(), "Figure 2 draws no shared dead-letter fallback");

        Set<String> drawnSuffixServices = new TreeSet<>();
        Set<String> drawnSharedServices = new TreeSet<>();
        for (String[] edge : edges(eventFlow)) {
            String origin = labels.get(edge[0]);
            if (origin == null) {
                continue;
            }
            if (suffixSinks.contains(edge[1])) {
                drawnSuffixServices.add(origin);
            }
            if (sharedSinks.contains(edge[1])) {
                drawnSharedServices.add(origin);
            }
        }

        assertEquals(
                new TreeSet<>(sourceSuffixServices),
                drawnSuffixServices,
                "Figure 2 must route exactly the services configured with a dead-letter suffix to"
                        + " their own source topic");
        assertTrue(
                sharedFallbackServices.containsAll(drawnSharedServices),
                "Figure 2 routes a suffix-configured service to the shared fallback: "
                        + drawnSharedServices);
        assertFalse(drawnSharedServices.isEmpty());
    }

    @Test
    @DisplayName("the deck reports the finding count, coverage and inventory its evidence records")
    void theDeckReportsTheFindingCountCoverageAndInventoryItsEvidenceRecords() {
        int registerSize = registerSize();
        assertTrue(
                flagRegister.contains("All " + registerSize + " register items"),
                "The register heading disagrees with its own table");
        assertEquals(
                String.valueOf(registerSize),
                kpiValueFor("Business-rule findings"),
                "The headline metric disagrees with the flag register");
        assertTrue(
                deckMarkup.contains("All " + registerSize + " flagged source rules"),
                "The business-value row disagrees with the flag register");

        assertTrue(deckMarkup.contains(classificationOf("app/cbl/")));
        assertTrue(deckMarkup.contains(classificationOf("app/cpy/")));

        assertEquals(
                "8 \u2192 " + serviceSchemas.size(),
                kpiValueFor("Shared datasets to private schemas"),
                "The datastore metric disagrees with the service inventory");

        for (String family : CORE_EVENT_FAMILIES) {
            assertTrue(
                    Files.isRegularFile(
                            platformRoot.resolve(
                                    "libs/event-contracts/src/main/resources/schemas/"
                                            + family
                                            + "-v1.json")),
                    "The deck claims a core event contract with no schema: " + family);
        }
        List<String> families = eventSchemaFamilies();
        long businessTypes = families.stream().filter(family -> !"dead-letter".equals(family)).count();
        assertEquals(
                String.valueOf(businessTypes),
                kpiValueFor("Event types, " + eventSchemaDocuments().size() + " schemas"),
                "The contract metric disagrees with the shipped event schemas");
    }

    /** The schema documents the contract library publishes, by file name. */
    private static List<String> eventSchemaDocuments() {
        Path schemas =
                platformRoot.resolve("libs/event-contracts/src/main/resources/schemas");
        try (var documents = Files.list(schemas)) {
            return documents
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Cannot read " + schemas, unreadable);
        }
    }

    /**
     * The event families those documents govern, one entry per family however many versions it has.
     *
     * <p>The deck's headline metric names both numbers, and the two moved apart once: withdrawing the
     * unpublished {@code card-updated} version two document left the label claiming fourteen
     * documents where thirteen shipped. Reading the directory is what keeps the slide honest.
     */
    private static List<String> eventSchemaFamilies() {
        return eventSchemaDocuments().stream()
                .map(name -> name.replaceFirst("-v\\d+\\.json$", ""))
                .distinct()
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("Reveal, Mermaid and Lucide use navigation-safe lifecycle hooks")
    void revealMermaidAndLucideUseNavigationSafeLifecycleHooks() {
        assertTrue(deckScript.contains("startOnLoad: false"));
        assertTrue(deckScript.contains("theme: \"base\""));
        assertTrue(deckScript.contains("htmlLabels: false"));
        assertTrue(deckScript.contains("wrappingWidth: 420"));
        assertTrue(deckScript.contains("primaryColor: \"#F2F0FE\""));
        assertTrue(deckScript.contains("primaryTextColor: \"#333333\""));
        assertTrue(deckScript.contains("primaryBorderColor: \"#5B39F3\""));
        assertTrue(deckScript.contains("lineColor: \"#999999\""));
        assertTrue(deckScript.contains("secondaryColor: \"#F4EFF6\""));
        assertTrue(deckScript.contains("Reveal.on(\"ready\""));
        assertTrue(deckScript.contains("Reveal.on(\"slidechanged\""));
        assertEquals(2, occurrences(deckScript, "renderMermaidFor(event.currentSlide)"));
        assertTrue(occurrences(deckScript, "renderLucideIcons();") >= 2);

        // A diagram is drawn once. The marker records the source each block was drawn from, and it
        // is written only after the run resolves, so a failed diagram is attempted again on the next
        // visit and a successful one is left alone. Without the marker every return to a diagram
        // slide restored the raw source and drew it again.
        assertTrue(
                deckScript.contains("mermaidRendered.get(node) !== mermaidSources.get(node)"),
                "the deck has to skip a block already drawn from the source it still holds");
        assertTrue(
                deckScript.contains("mermaidRendered.set(node, mermaidSources.get(node))"),
                "the marker has to be written, and after the run rather than before it");
        assertTrue(
                deckScript.contains("await mermaid.run({ nodes })"),
                "the queued task has to await the library it resolved, not a global");

        // The icon guard has to test for the class as well as the attribute. The library copies the
        // name attribute onto the graphic it draws, so a guard reading "[data-lucide]" alone matches
        // finished icons, never reaches zero on a slide that has any, and rebuilds all seventeen on
        // every navigation. The class is what the library always adds to its own output, so it is
        // what separates a waiting placeholder from a drawn icon.
        assertTrue(
                deckScript.contains("[data-lucide]:not(.lucide)"),
                "the icon guard has to exclude icons already drawn, or it never returns early");
        assertTrue(deckScript.contains("hash: true"));
        assertTrue(deckScript.contains("transition: \"slide\""));
        assertTrue(deckScript.contains("controlsTutorial: false"));
        assertTrue(deckScript.contains("width: 1920"));
        assertTrue(deckScript.contains("height: 1080"));
        assertTrue(deckStyle.contains(".reveal .progress"));
        assertTrue(deckStyle.contains("display: none !important"));
    }

    @Test
    @DisplayName("the diagram lifecycle is serialized, self-contained and degrades readably")
    void theDiagramLifecycleIsSerializedSelfContainedAndDegradesReadably() {
        // Mermaid is fetched by a script element this module creates. An element carries an
        // integrity attribute where an import expression cannot, and an inserted element is
        // asynchronous, so the first slide is still drawn without waiting for the library. Both
        // properties are read here because losing either one is a silent regression: the first
        // would leave 3.5 MB unverified, and the second would delay every talk by a download.
        assertTrue(deckScript.contains("document.createElement(\"script\")"),
                "Mermaid has to load through an element the module inserts, which is what lets its"
                        + " bytes be digested");
        assertTrue(deckScript.contains("element.integrity = MERMAID_DIGEST")
                        && deckScript.contains("element.crossOrigin = \"anonymous\""),
                "the inserted element has to carry the digest and the anonymous origin a digest"
                        + " needs to be checked at all");
        assertTrue(deckScript.contains("element.addEventListener(\"error\""),
                "a failed load or a digest mismatch has to reach the failure path rather than"
                        + " leaving the promise pending forever");
        assertFalse(
                Pattern.compile("(?m)^\\s*import\\s").matcher(deckScript).find(),
                "A static import failure aborts the whole module, including Reveal start-up");
        assertTrue(deckScript.contains(".catch("), "The Mermaid load declares no failure path");
        assertTrue(
                deckScript.contains("if (window.Reveal)"),
                "Reveal start-up must not assume the library loaded");
        assertTrue(deckScript.contains("Reveal.initialize({"));
        assertTrue(
                deckScript.contains("if (window.lucide)")
                        || deckScript.contains("if (!window.lucide)"),
                "Icon rendering must not assume the library loaded");

        int queueStart = deckScript.indexOf("mermaidRenderQueue = mermaidRenderQueue.then(");
        int reset = deckScript.indexOf("removeAttribute(\"data-processed\")");
        assertTrue(queueStart >= 0, "Diagram renders are not serialized through a queue");
        assertTrue(reset >= 0, "The diagram source is never reset before a re-render");
        assertTrue(
                reset > queueStart,
                "Resetting a diagram outside the queue lets one navigation mutate a block another"
                        + " render is inside");

        assertFalse(
                deckScript.contains("slides.style.transform"),
                "Writing Reveal's own stage transform strands the deck at a stale scale");
        assertTrue(deckScript.contains("Reveal.layout()"), "A rendered diagram must trigger a refit");

        assertTrue(deckStyle.contains(".diagram-fallback"));
        assertTrue(declarationOf(".reveal pre.mermaid.diagram-fallback").contains("white-space: normal"));
        assertTrue(deckScript.contains("classList.add(\"diagram-fallback\")"));
        assertTrue(deckStyle.contains("body.deck-static"));
        assertTrue(deckScript.contains("classList.add(\"deck-static\")"));
    }

    @Test
    @DisplayName("the executive slide text avoids prohibited content and source listings")
    void theExecutiveSlideTextAvoidsProhibitedContentAndSourceListings() {
        assertFalse(FORBIDDEN_DECK_WORD.matcher(deck).find());
        assertFalse(deck.contains("```"));
        assertFalse(deck.contains("<pre><code"));
        assertFalse(deck.contains("COPAUA0C"));
        assertFalse(deck.contains("CP00"));
        assertFalse(deck.contains("<img"));
        assertTrue(
                deck.codePoints()
                        .noneMatch(codePoint -> codePoint >= 0x1F300 && codePoint <= 0x1FAFF),
                "The executive deck contains an emoji code point");
    }

    /**
     * The twelve Vonnegut principle names, exactly as the governing rules document writes them.
     *
     * <p>An earlier revision of the report invented names for V8 and V10 through V12, so a reader
     * comparing the report against the rule could not tell which principle a row scored. The names
     * are asserted rather than the numbers alone because the number without the rule's own name is
     * what allowed the drift.
     */
    private static final List<String> VONNEGUT_PRINCIPLES =
            List.of(
                    "V1: Find a subject you care about",
                    "V2: Do not ramble",
                    "V3: Keep it simple",
                    "V4: Have the guts to cut",
                    "V5: Sound like yourself",
                    "V6: Say what you mean",
                    "V7: Pity the reader",
                    "V8: Start close to the end",
                    "V9: The Dignity Test",
                    "V10: The Indifference Detector",
                    "V11: The Indianapolis Test",
                    "V12: Humor as Trust Signal");

    /** The ten Asimov principle names, exactly as the governing rules document writes them. */
    private static final List<String> ASIMOV_PRINCIPLES =
            List.of(
                    "A1: Plate Glass Clarity",
                    "A2: Short Words, Simple Structures",
                    "A3: Logical Sequence",
                    "A4: Ideas Carry the Weight",
                    "A5: Conversational Informality",
                    "A6: No Ornamental Language",
                    "A7: Functional Dialogue",
                    "A8: Anticipate Reader Questions",
                    "A9: Efficiency Over Polish",
                    "A10: Respect the Reader's Intelligence");

    @Test
    @DisplayName("the prose report declares the required method, boundaries and nineteen targets")
    void theProseReportDeclaresTheRequiredMethodBoundariesAndTargets() {
        assertTrue(prose.startsWith("# Prose Validation\n"));
        assertTrue(prose.contains("Every target is Technical, so the Asimov agent governs"));
        assertTrue(prose.contains("V2, V3, V6, and V7 carry the highest weight"));
        assertTrue(prose.contains("V1 and V5 carry reduced weight"));
        assertTrue(prose.contains("Pass**, **Soft violation**, and **Hard violation"));
        assertTrue(prose.contains("B1 through B5"));
        assertTrue(prose.contains("Only the new `Modernized card platform` section"));
        assertTrue(prose.contains("Only headings, body copy, bullets, metric labels, and table cells"));

        Matcher reports = REPORT.matcher(prose);
        int count = 0;
        while (reports.find()) {
            count++;
            String report = reports.group();
            assertTrue(report.contains("**Overall verdict:**"));
            assertEquals(12, occurrencesMatching(report, "(?m)^\\| V(?:[1-9]|1[0-2]):"));
            assertEquals(10, occurrencesMatching(report, "(?m)^\\| A10?:|^\\| A[1-9]:"));
            assertTrue(report.contains("**Per-violation entries:**"));
        }
        assertEquals(PROSE_TARGETS.size(), count);

        assertEquals(
                PROSE_TARGETS.size(),
                occurrencesMatching(
                        prose,
                        "(?m)^\\| \\d+ \\| .* \\| (?:CLEAN|NEEDS WORK|ROUGH DRAFT) \\| \\d+ \\| \\d+ \\|$"),
                "the summary table carries one row per scored target, each carrying one of the three"
                        + " verdicts the rule defines rather than a verdict of its own");
    }

    /**
     * Asserts every scorecard row names its principle with the rule's own wording.
     *
     * <p>Twenty-two rows appear once per report, so each name appears once per target. Counting rather than merely finding is what catches a report that scores a principle
     * under a name of its own invention in some of its sections and the rule's name in others.
     */
    @Test
    @DisplayName("every scorecard names the twenty-two principles the rules document declares")
    void everyScorecardNamesThePrinciplesTheRulesDocumentDeclares() {
        for (String principle : VONNEGUT_PRINCIPLES) {
            assertEquals(
                    PROSE_TARGETS.size(),
                    occurrences(prose, "| " + principle + " |"),
                    "principle row missing or renamed: " + principle);
        }
        for (String principle : ASIMOV_PRINCIPLES) {
            assertEquals(
                    PROSE_TARGETS.size(),
                    occurrences(prose, "| " + principle + " |"),
                    "principle row missing or renamed: " + principle);
        }
    }

    @Test
    @DisplayName("the twenty-two principle rows of every report read the rule's own names in order")
    void thePrincipleRowsReadTheRuleNamesInOrder() {
        for (int number = 1; number <= PROSE_TARGETS.size(); number++) {
            String section = reportSection(number);
            int cursor = -1;
            for (String row : PRINCIPLE_ROWS) {
                int at = section.indexOf("| " + row + " |");
                assertTrue(
                        at >= 0,
                        "Section "
                                + number
                                + " does not name principle \""
                                + row
                                + "\" exactly as the rule names it");
                assertTrue(
                        at > cursor,
                        "Section " + number + " lists principle " + row + " out of order");
                cursor = at;
            }
            assertEquals(
                    PRINCIPLE_ROWS.size(),
                    occurrencesMatching(section, "(?m)^\\| [VA]\\d+: "),
                    "Section " + number + " carries a principle row the rule does not define");
        }
    }

    @Test
    @DisplayName("every published verdict and count equals a fresh measurement of that target")
    void theProseVerdictsAndCountsEqualAFreshMeasurement() throws IOException {
        int publishedClean = 0;
        int measuredClean = 0;
        for (int number = 1; number <= PROSE_TARGETS.size(); number++) {
            String target = PROSE_TARGETS.get(number - 1);
            ProseMeasurement measured = measure(target);
            String section = reportSection(number);

            String summaryRow =
                    "| %d | `%s` | %s | %d | %d |"
                            .formatted(
                                    number,
                                    target,
                                    measured.verdict(),
                                    measured.hard(),
                                    measured.soft());
            assertTrue(
                    prose.contains(summaryRow),
                    "The summary table does not publish the measurement of "
                            + target
                            + ". Measured row: "
                            + summaryRow);

            String verdictLine =
                    "**Overall verdict:** **%s** — %d hard violations, %d soft violations."
                            .formatted(measured.verdict(), measured.hard(), measured.soft());
            assertTrue(
                    section.contains(verdictLine),
                    "Section " + number + " does not publish " + verdictLine);

            String measuredLine =
                    "**Measured:** %d sentences over thirty words, %d paragraphs over five sentences, %d buzzword uses."
                            .formatted(
                                    measured.longSentences(),
                                    measured.longParagraphs(),
                                    measured.buzzwords());
            assertTrue(
                    section.contains(measuredLine),
                    "Section " + number + " does not publish " + measuredLine);

            assertPrincipleResults(section, number, measured);
            assertEntriesCoverEveryViolation(section, number, measured);

            publishedClean += occurrences(section, "**Overall verdict:** **CLEAN**");
            measuredClean += "CLEAN".equals(measured.verdict()) ? 1 : 0;
        }
        assertEquals(
                measuredClean,
                publishedClean,
                "The report publishes a different number of CLEAN verdicts than the measurement finds");
        assertTrue(
                measuredClean < PROSE_TARGETS.size() || !prose.contains("do not reach CLEAN"),
                "The report narrates a shortfall that the measurement does not find");
    }

    /**
     * Holds each principle row to the result the measurement implies.
     *
     * <p>Five principles are measured. The other seventeen must read {@code Not measured}, because a
     * pass this report cannot demonstrate is the self-attestation a review already rejected once.
     */
    private static void assertPrincipleResults(
            String section, int number, ProseMeasurement measured) {
        for (String row : PRINCIPLE_ROWS) {
            String key = row.substring(0, row.indexOf(':'));
            String expected;
            if (SENTENCE_PRINCIPLES.contains(key)) {
                expected = measured.longSentences() > 0 ? "Soft violation" : "Pass";
            } else if (PARAGRAPH_PRINCIPLES.contains(key)) {
                expected = measured.longParagraphs() > 0 ? "Soft violation" : "Pass";
            } else if (BUZZWORD_PRINCIPLES.contains(key)) {
                expected = measured.buzzwords() > 0 ? "Hard violation" : "Pass";
            } else {
                expected = "Not measured";
            }
            Matcher matcher =
                    Pattern.compile(
                                    "(?m)^\\| "
                                            + Pattern.quote(row)
                                            + " \\| [^|]+ \\| ([^|]+) \\|")
                            .matcher(section);
            assertTrue(
                    matcher.find(),
                    "Section " + number + " has no row for principle " + row);
            assertEquals(
                    expected,
                    matcher.group(1).strip(),
                    "Section " + number + " publishes the wrong result for " + row);
        }
    }

    /** Requires an entry for every kind of violation measured, and none where none was measured. */
    private static void assertEntriesCoverEveryViolation(
            String section, int number, ProseMeasurement measured) {
        String entries = section.substring(section.indexOf("**Per-violation entries:**"));
        if (measured.soft() == 0 && measured.hard() == 0) {
            assertTrue(
                    entries.contains("None. This target has no measured violation."),
                    "Section " + number + " measures clean and must say so under its entries");
            return;
        }
        if (measured.longSentences() > 0) {
            assertTrue(
                    entries.contains("**" + number + ".1 — V3: Keep it simple**"),
                    "Section " + number + " measures an over-length sentence and owes entry " + number + ".1");
        }
        if (measured.longParagraphs() > 0) {
            assertTrue(
                    entries.contains("— V2: Do not ramble**"),
                    "Section " + number + " measures an over-long paragraph and owes a V2 entry");
            Matcher split = Pattern.compile("Start a new paragraph at \"([^\"]+)\"").matcher(entries);
            int named = 0;
            while (split.find()) {
                String prefix = asScored(split.group(1));
                assertTrue(
                        measured.worstParagraph().stream()
                                .anyMatch(sentence -> sentence.startsWith(prefix)),
                        "Section "
                                + number
                                + " names a split point that begins no sentence of the measured paragraph: "
                                + prefix);
                named++;
            }
            assertTrue(named > 0, "Section " + number + " gives no split point");
        }
    }

    @Test
    @DisplayName("every published rewrite is shorter than its offender and reads under thirty words a sentence")
    void thePublishedRewritesAreShorterThanTheirOffenders() throws IOException {
        Pattern entry =
                Pattern.compile(
                        "(?ms)^\\*\\*(\\d+)\\.1 — V3: Keep it simple\\*\\*.*?soft violation, (\\d+) words\\.\\n"
                                + "\\n> (.+?)\\n\\n\\*\\*Rewrite\\*\\* — (\\d+) words, (\\d+)% shorter:\\n"
                                + "\\n> (.+?)\\n");
        Matcher matcher = entry.matcher(prose);
        int checked = 0;
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            int declaredOffenderWords = Integer.parseInt(matcher.group(2));
            String offender = matcher.group(3).strip();
            int declaredRewriteWords = Integer.parseInt(matcher.group(4));
            int declaredReduction = Integer.parseInt(matcher.group(5));
            String rewrite = matcher.group(6).strip();

            ProseMeasurement measured = measure(PROSE_TARGETS.get(number - 1));
            assertEquals(
                    measured.worstSentenceWords(),
                    declaredOffenderWords,
                    "Entry " + number + ".1 declares a length the measurement does not give");
            assertEquals(
                    measured.worstSentence(),
                    asScored(offender),
                    "Entry " + number + ".1 quotes a passage that is not the measured worst sentence");
            assertEquals(
                    declaredRewriteWords,
                    wordCount(asScored(rewrite)),
                    "Entry " + number + ".1 declares a rewrite length that does not match its rewrite");
            int reduction =
                    Math.round(
                            100f
                                    * (declaredOffenderWords - declaredRewriteWords)
                                    / declaredOffenderWords);
            assertEquals(
                    reduction,
                    declaredReduction,
                    "Entry " + number + ".1 declares a reduction its two lengths do not give");
            assertTrue(
                    reduction >= MINIMUM_REWRITE_REDUCTION_PERCENT,
                    "Entry " + number + ".1 cuts only " + reduction + "%, and the rule asks for fifteen");
            for (String sentence : sentences(asScored(rewrite))) {
                assertTrue(
                        wordCount(sentence) <= LONG_SENTENCE_WORDS,
                        "Entry "
                                + number
                                + ".1 proposes a replacement sentence of "
                                + wordCount(sentence)
                                + " words");
            }
            checked++;
        }
        int owed = 0;
        for (String target : PROSE_TARGETS) {
            owed += measure(target).longSentences() > 0 ? 1 : 0;
        }
        assertEquals(owed, checked, "The report owes one worked rewrite for every target with a long sentence");
    }

    @Test
    @DisplayName("the prose exemptions and terminology are self-consistent")
    void theProseVerdictsCountsAndExemptionsAreSelfConsistent() {
        assertTrue(prose.contains("## Exemptions applied"));
        assertTrue(prose.contains("PIC S9(09)V99"));
        assertTrue(prose.contains("technical specification's section 0.8"));
        assertTrue(prose.contains("TRANSACTION RECEIVED AFTER ACCT EXPIRATION"));
        assertTrue(prose.contains("Slide 9 presents `RoundingMode.DOWN` and `HALF_UP`"));
        assertFalse(
                Pattern.compile(
                                "(?i)\\b(?:leverage|utilize|facilitate|synergy|holistic|paradigm)\\b")
                        .matcher(prose)
                        .find());
        assertFalse(Pattern.compile("(?i)\\b(?:minor|major|moderate)\\b").matcher(prose).find());
    }

    /** Counts the register rows and proves the identifiers run unbroken from one. */
    private static int registerSize() {
        Set<Integer> identifiers = new TreeSet<>();
        Matcher row = REGISTER_ROW.matcher(flagRegister);
        while (row.find()) {
            assertTrue(
                    identifiers.add(Integer.valueOf(row.group(1))),
                    "The flag register repeats identifier " + row.group(1));
        }
        assertFalse(identifiers.isEmpty(), "The flag register has no numbered rows");
        int expected = 1;
        for (Integer identifier : identifiers) {
            assertEquals(
                    expected++,
                    identifier.intValue(),
                    "The flag register skips an identifier, so a citation would repoint");
        }
        return identifiers.size();
    }

    /** Reads the classification breakdown the traceability matrix records for a source location. */
    private static String classificationOf(String sourceLocation) {
        Matcher row =
                Pattern.compile(
                                "(?m)^\\|\\s*`"
                                        + Pattern.quote(sourceLocation)
                                        + "`\\s*\\|[^|]*\\|([^|]*)\\|")
                        .matcher(traceability);
        assertTrue(row.find(), "The traceability matrix has no coverage row for " + sourceLocation);
        return row.group(1).trim();
    }

    /** Reads the headline metric whose label carries the given fragment. */
    private static String kpiValueFor(String labelFragment) {
        Matcher card = KPI_CARD.matcher(deckMarkup);
        while (card.find()) {
            if (card.group(2).contains(labelFragment)) {
                return card.group(1).trim();
            }
        }
        throw new AssertionError("The deck carries no headline metric labelled " + labelFragment);
    }

    /**
     * Every visible word of one slide, which is what the Rule 4 ceiling bounds.
     *
     * <p>Only two things are removed. Mermaid source is the text a diagram is generated from and is
     * never rendered as words, and the slide number is a decorative ordinal. Everything else a reader
     * sees is counted: the heading, the eyebrow, paragraphs, bullets, table captions, header cells and
     * body cells, metric cards and icon labels.
     *
     * <p>A table caption counts even though this deck's stylesheet paints none of them: a caption is
     * content the slide carries and assistive technology reads, so counting it keeps the ceiling on the
     * conservative side of what a reader receives.
     *
     * <p>An earlier revision of this method also removed tables, headings, metric grids and icon rows.
     * Those are where a slide of this deck carries most of its words, so four slides stood at 103, 94,
     * 88 and 43 visible words against a ceiling of {@value #RULE_FOUR_WORD_LIMIT} while this test
     * reported every one of them as passing.
     *
     * @param slideBody the markup of one slide
     * @return how many visible words it carries
     */
    private static int bodyWordCount(String slideBody) {
        String text = slideBody;
        text = text.replaceAll("(?s)<pre class=\"mermaid\">.*?</pre>", " ");
        text = text.replaceAll("(?s)<span class=\"slide-number\"[^>]*>.*?</span>", " ");
        text = TAG.matcher(text).replaceAll(" ").replace('\u00b7', ' ');
        int words = 0;
        for (String token : text.split("\\s+")) {
            if (!token.isEmpty() && WORD_CHARACTER.matcher(token).find()) {
                words++;
            }
        }
        return words;
    }

    /** Maps every diagram node identifier to its label. */
    private static Map<String, String> nodeLabels(String diagram) {
        Map<String, String> labels = new LinkedHashMap<>();
        Matcher node = MERMAID_NODE.matcher(diagram);
        while (node.find()) {
            labels.put(node.group(1), node.group(2).trim());
        }
        return labels;
    }

    /** Lists every diagram edge as an origin and destination identifier pair. */
    private static List<String[]> edges(String diagram) {
        String reduced = MERMAID_NODE.matcher(diagram).replaceAll("$1");
        List<String[]> found = new ArrayList<>();
        Matcher edge = MERMAID_EDGE.matcher(reduced);
        while (edge.find()) {
            found.add(new String[] {edge.group(1), edge.group(2)});
        }
        return found;
    }

    private static Set<String> identifiersLabelledWith(
            Map<String, String> labels, String fragment) {
        Set<String> identifiers = new LinkedHashSet<>();
        labels.forEach(
                (identifier, label) -> {
                    if (label.contains(fragment)) {
                        identifiers.add(identifier);
                    }
                });
        return identifiers;
    }

    /** Returns the declaration body of one style rule. */
    private static String declarationOf(String selector) {
        Matcher rule =
                Pattern.compile(
                                "(?s)(?<![\\w.-])"
                                        + Pattern.quote(selector)
                                        + "\\s*(?:,[^{}]*)?\\{(.*?)\\}")
                        .matcher(deckStyle);
        assertTrue(rule.find(), "The inline style block declares no rule for " + selector);
        return rule.group(1);
    }

    /** Returns the whole element that references a resource, so its attributes can be read. */
    private static String enclosingTag(String document, String resource) {
        int reference = document.indexOf(resource);
        assertTrue(reference >= 0, "The deck does not reference " + resource);
        int open = document.lastIndexOf('<', reference);
        int close = document.indexOf('>', reference);
        assertTrue(open >= 0 && close > open, "Malformed element around " + resource);
        return document.substring(open, close + 1);
    }

    private static List<String> listServiceModules() throws IOException {
        try (Stream<Path> modules = Files.list(platformRoot.resolve("services"))) {
            List<String> names =
                    modules.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                            .sorted()
                            .toList();
            assertFalse(names.isEmpty(), "No service modules were found");
            return names;
        }
    }

    /** Confirms one service owns exactly one schema and returns its stem. */
    private static String singleSchemaStem(String service, String configuration) {
        Set<String> stems = new TreeSet<>();
        Matcher stem = SCHEMA_STEM.matcher(configuration);
        while (stem.find()) {
            stems.add(stem.group(1));
        }
        assertEquals(
                1,
                stems.size(),
                "Database per service requires exactly one schema for " + service + ", found " + stems);
        return stems.iterator().next();
    }

    /**
     * Reads the default a configuration property falls back to when its variable is unset.
     *
     * <p>Returns {@code null} when the property is absent, which is how a service that routes only
     * to the shared topic is told apart from one that routes to its own source topic.
     */
    private static String defaultOf(String configuration, String property) {
        Matcher setting =
                Pattern.compile(
                                "(?m)^\\s*"
                                        + Pattern.quote(property)
                                        + ":\\s*\"?\\$\\{[A-Z_]+:([^}]*)\\}\"?\\s*$")
                        .matcher(configuration);
        return setting.find() ? setting.group(1) : null;
    }

    private static String firstGroup(Pattern pattern, String source, String description) {
        Matcher matcher = pattern.matcher(source);
        assertTrue(matcher.find(), "The deck has no " + description);
        return matcher.group(1);
    }

    /**
     * Ties every published count of the flagged-rule register to the register itself.
     *
     * <p>Three artifacts state the size of {@code docs/business-rule-flags.md}: the deck's
     * findings-surfaced metric card, the deck's business-value table, and the platform guide's
     * document index. A review found all three stating sixty-five while the register carried
     * sixty-six rows, which is stale evidence in the two artifacts a customer and a new developer
     * read first. The register's own heading is already held to its rows by
     * {@code DocumentationContractTest}, so binding these three to the same figure closes the loop.
     */
    @Test
    @DisplayName("the deck and the platform guide state the register's own finding count")
    void theDeckAndPlatformGuideStateTheRegisterCount() throws IOException {
        String register = Files.readString(platformRoot.resolve("docs/business-rule-flags.md"));
        int coverageFrom = register.indexOf(REGISTER_SECTION_START);
        int coverageTo = register.indexOf(REGISTER_SECTION_END);
        assertTrue(coverageFrom >= 0 && coverageTo > coverageFrom,
                "the register must carry its coverage section between " + REGISTER_SECTION_START
                        + " and " + REGISTER_SECTION_END);
        String coverage = register.substring(coverageFrom, coverageTo);
        int findings = occurrencesMatching(coverage, "(?m)^\\|\\s*\\d+\\s+[^|]*\\|");
        assertTrue(findings >= 26,
                "the register fixes identifiers 1 to 26, so it cannot hold fewer rows: " + findings);

        String digits = String.valueOf(findings);
        String word = spelled(findings);
        String capitalised = Character.toUpperCase(word.charAt(0)) + word.substring(1);

        Matcher card = Pattern.compile(
                "(?s)<div class=\"kpi-value\">(\\d+)</div>\\s*"
                        + "<div class=\"kpi-label\">Business-rule findings surfaced</div>")
                .matcher(deck);
        assertTrue(card.find(),
                "the deck must carry a metric card labelled \"Business-rule findings surfaced\"");
        assertEquals(digits, card.group(1),
                "the deck's findings metric must be the number of rows the register carries");

        assertTrue(
                deck.contains("All " + digits + " flagged source rules carry file and line citations."),
                "the deck's business-value table must state all " + digits
                        + " flagged source rules, which is what the register carries");

        String guide = Files.readString(platformRoot.resolve("README.md"));
        assertTrue(guide.contains(capitalised
                        + " ambiguous, inconsistent, or undocumented source rules"),
                "the platform guide's document index must state " + capitalised
                        + " source rules, which is what the register carries");
    }

    /**
     * Spells one count below one hundred, so a published word form can be derived rather than pinned.
     *
     * @param value the count to spell, from 1 to 99
     * @return the count in lower-case words, hyphenated above twenty
     * @throws IllegalArgumentException when the value falls outside 1 to 99
     */
    private static String spelled(int value) {
        List<String> units = List.of("zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
                "sixteen", "seventeen", "eighteen", "nineteen");
        List<String> tens = List.of("twenty", "thirty", "forty", "fifty", "sixty", "seventy",
                "eighty", "ninety");
        if (value < 1 || value > 99) {
            throw new IllegalArgumentException("no word form is defined for " + value);
        }
        if (value < 20) {
            return units.get(value);
        }
        String ten = tens.get(value / 10 - 2);
        return value % 10 == 0 ? ten : ten + "-" + units.get(value % 10);
    }

    /**
     * Binds the prose report to the exact text it scored.
     *
     * <p>A review found the report claiming every target CLEAN against text that had moved beneath it:
     * the root guide, the deck, the onboarding guide, the equivalence results, the flagged-rule
     * register, the decision log and the traceability matrix had all changed after the scoring pass,
     * so the verdicts described documents that no longer existed. A digest per target turns that from
     * something a reader has to notice into something this build refuses: change a scored file and the
     * report fails here until the pass is run again over the new text.
     *
     * <p>Target 10 is the report itself and carries no digest, because a file cannot publish a digest
     * of its own bytes. The report says so in the row where a digest would sit, and this test requires
     * that statement rather than a value.
     */
    @Test
    @DisplayName("the prose report publishes the digest of every text it scored")
    void theProseReportIsBoundToTheTextItScored() throws IOException {
        for (int number = 1; number <= PROSE_TARGETS.size(); number++) {
            String target = PROSE_TARGETS.get(number - 1);
            String row = digestRow(number);
            assertTrue(row != null, "the content binding table must carry a row for target " + number);
            if (number == SELF_TARGET) {
                assertTrue(row.contains("carries no digest"),
                        "target " + number + " is the report itself, so its row must say it carries no"
                                + " digest rather than publish one: " + row);
                continue;
            }
            String measured =
                    sha256(Files.readAllBytes(platformRoot.resolve(target).normalize()));
            assertTrue(row.contains(measured),
                    "target " + number + " (" + target + ") now hashes to " + measured
                            + ", so its text changed after it was scored. Run the Rule 5 pass over the"
                            + " new text and publish the new digest. The row reads: " + row);
        }
    }

    /**
     * Requires the report to score every target it lists and to publish the measurement behind it.
     *
     * <p>A verdict with no measurement beside it is an assertion. The report therefore carries one
     * summary row, one numbered section and one measurement row per target, and this test counts all
     * three against the target list rather than against a number written in prose.
     */
    @Test
    @DisplayName("the prose report scores every target it lists and publishes each measurement")
    void theProseReportScoresEveryTargetItLists() {
        for (int number = 1; number <= PROSE_TARGETS.size(); number++) {
            assertTrue(prose.contains("\n### " + number + ". "),
                    "the report must carry a numbered section for target " + number);
        }
        assertEquals(PROSE_TARGETS.size(), occurrencesMatching(prose, "(?m)^### \\d+\\. "),
                "the report must carry exactly one numbered section per target");

        String measurement = section("## Measurement", "## Summary");
        assertEquals(PROSE_TARGETS.size(), occurrencesMatching(measurement, "(?m)^\\| *\\d+ \\|"),
                "every target must appear once in the measurement table");

        String summary = section("## Summary", "## Content binding");
        assertEquals(PROSE_TARGETS.size(),
                occurrencesMatching(
                        summary,
                        "(?m)^\\| *\\d+ \\|[^\\n]*\\| (?:CLEAN|NEEDS WORK|ROUGH DRAFT) \\|"),
                "every target must appear once in the summary table, carrying the verdict its own"
                        + " measurement gives it");
    }

    /**
     * Returns one section of the prose report.
     *
     * @param start the heading the section opens with
     * @param end   the heading that closes it
     * @return the text between the two headings
     */
    private static String section(String start, String end) {
        int from = prose.indexOf(start);
        int to = prose.indexOf(end);
        assertTrue(from >= 0 && to > from,
                "the report must carry " + start + " before " + end);
        return prose.substring(from, to);
    }

    /**
     * Returns the content-binding row for one target.
     *
     * @param number the target number the report uses
     * @return the whole row, or {@code null} when the table carries no row for that target
     */
    private static String digestRow(int number) {
        Matcher row = Pattern.compile("(?m)^\\| *" + number + " \\|([^\\n]*)$").matcher(binding());
        return row.find() ? row.group(1) : null;
    }

    /** Returns the content-binding section of the report. */
    private static String binding() {
        return section("## Content binding", "## Per-document reports");
    }

    /**
     * Digests one file's bytes.
     *
     * @param content the bytes to digest
     * @return the digest as lower-case hexadecimal
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be present in the JDK", impossible);
        }
    }

    /**
     * Measures one target the way {@code docs/prose-validation.md} declares it is measured.
     *
     * <p>Prose only is scored. Fenced blocks, table rows, headings, horizontal rules and blockquoted
     * passages are dropped, inline code collapses to one token, and consecutive body lines rejoin into
     * the paragraph the author wrote, while a list item stays a paragraph of its own.
     */
    private static ProseMeasurement measure(String target) throws IOException {
        String text = Files.readString(platformRoot.resolve(target).normalize());
        int longSentences = 0;
        int buzzwords = 0;
        String worstSentence = "";
        int worstSentenceWords = 0;
        List<String> worstParagraph = List.of();
        List<String> longParagraphs = new ArrayList<>();
        for (String block : scoredBlocks(target, text)) {
            List<String> sentences = sentences(block);
            if (sentences.size() > LONG_PARAGRAPH_SENTENCES) {
                longParagraphs.add(block);
                if (sentences.size() > worstParagraph.size()) {
                    worstParagraph = sentences;
                }
            }
            for (String sentence : sentences) {
                int words = wordCount(sentence);
                if (words > LONG_SENTENCE_WORDS) {
                    longSentences++;
                    if (words > worstSentenceWords) {
                        worstSentenceWords = words;
                        worstSentence = sentence;
                    }
                }
            }
            Matcher buzzword = PROSE_BUZZWORD.matcher(block);
            while (buzzword.find()) {
                buzzwords++;
            }
        }
        return new ProseMeasurement(
                longSentences,
                longParagraphs.size(),
                buzzwords,
                worstSentence,
                worstSentenceWords,
                worstParagraph);
    }

    private static List<String> scoredBlocks(String target, String text) {
        String body;
        if (target.endsWith(".html")) {
            body =
                    text.replaceAll("(?is)<style\\b.*?</style>", " ")
                            .replaceAll("(?is)<script\\b.*?</script>", " ")
                            .replaceAll("(?is)<pre\\b.*?</pre>", " ")
                            .replaceAll("(?s)<!--.*?-->", " ")
                            .replaceAll("(?s)<[^>]+>", "\n");
        } else {
            body = FENCED_BLOCK.matcher(text).replaceAll("");
        }
        if (target.equals("../README.md")) {
            int at = body.indexOf(SCORED_README_SECTION);
            assertTrue(at >= 0, "The scored section is absent from the repository-root guide");
            int end = body.indexOf("\n## ", at + 1);
            body = end > at ? body.substring(at, end) : body.substring(at);
        }
        body = INLINE_CODE.matcher(body).replaceAll("CODE");
        List<String> blocks = new ArrayList<>();
        boolean fresh = true;
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()
                    || line.startsWith("|")
                    || line.startsWith("#")
                    || line.startsWith("---")
                    || line.startsWith(">")) {
                fresh = true;
                continue;
            }
            boolean listed = LIST_MARKER.matcher(line).find();
            line = LIST_MARKER.matcher(line).replaceFirst("");
            line = MARKDOWN_LINK.matcher(line).replaceAll(matchResult -> matchResult.group(1));
            line = line.replace("**", "").replace("__", "");
            if (fresh || listed || blocks.isEmpty()) {
                blocks.add(line);
            } else {
                blocks.set(blocks.size() - 1, blocks.get(blocks.size() - 1) + " " + line);
            }
            fresh = false;
        }
        return blocks;
    }

    private static List<String> sentences(String block) {
        String guarded = block;
        for (String abbreviation : ABBREVIATIONS) {
            guarded =
                    Pattern.compile("(?<![A-Za-z])" + Pattern.quote(abbreviation))
                            .matcher(guarded)
                            .replaceAll(
                                    Matcher.quoteReplacement(abbreviation.replace(".", GUARD)));
        }
        guarded = DECIMAL_POINT.matcher(guarded).replaceAll("$1" + GUARD + "$2");
        guarded = SINGLE_INITIAL.matcher(guarded).replaceAll("$1" + GUARD);
        List<String> parts = new ArrayList<>();
        Matcher boundary = SENTENCE_BOUNDARY.matcher(guarded);
        int start = 0;
        while (boundary.find()) {
            parts.add(guarded.substring(start, boundary.end()));
            start = boundary.end();
        }
        parts.add(guarded.substring(start));
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.replace(GUARD, ".").strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private static int wordCount(String text) {
        String stripped = WORD_ORNAMENT.matcher(text).replaceAll("").strip();
        return stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
    }

    /** Renders a quoted passage the way the measurement sees it, so the two can be compared. */
    private static String asScored(String quoted) {
        return INLINE_CODE
                .matcher(quoted)
                .replaceAll("CODE")
                .replace("**", "")
                .replace("__", "")
                .strip();
    }

    private static String reportSection(int number) {
        Matcher matcher =
                Pattern.compile("(?ms)^### " + number + "\\. .*?(?=^### \\d+\\. |^## Exemptions applied)")
                        .matcher(prose);
        assertTrue(matcher.find(), "The prose report has no section " + number);
        return matcher.group();
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

    private static int occurrencesMatching(String source, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
