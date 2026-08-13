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
import org.yaml.snakeyaml.Yaml;

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
     * <p>Rule 4 fixes the version at 11.4.0, and this is the value the deck loads. An earlier
     * revision ran the 11.16.1 line over advisories found in 11.4.0, and a review held that the
     * pin is the Rule's to set. What did survive that revision is the loading form: the single-file
     * bundle replaces the module graph, so one digest covers every byte where digesting a module
     * entry covered 30 KB of a graph whose chunks were fetched unverified. Rationale, alternatives
     * considered and accepted risks: {@code card-platform/docs/decision-log.md}.
     */
    private static final String MERMAID_BUNDLE = "mermaid@11.4.0/dist/mermaid.min.js";

    /** The digest of that bundle, as jsDelivr serves it, over 2,571,838 bytes. */
    private static final String MERMAID_DIGEST =
            "sha384-Wm9qzEgq4j1jEnuFK2FxKTlwuhbV2QqtGhcchvjDoKxeJ7WWAW7fysBq+1s6myfX";

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
     * The twenty-six Rule 5 targets, in the order {@code docs/prose-validation.md} numbers them.
     *
     * <p>Every path is resolved against the platform root, so {@code ../README.md} reaches the
     * repository-root guide that the report scores one section of.
     *
     * <p>The report claims every authored document, so the inventory has to reach the deployment
     * guide and the description prose of all six OpenAPI documents as well as the guides. An
     * OpenAPI document is written for a reader as much as any guide is, so its descriptions are
     * governed prose. They are appended rather than interleaved, which keeps every number the
     * report already published attached to the target it was measured against.
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
                    "presentation/executive-summary.html",
                    "deploy/k8s/README.md",
                    "services/authorization-service/src/main/resources/openapi.yaml",
                    "services/ledger-posting-service/src/main/resources/openapi.yaml",
                    "services/fraud-detection-service/src/main/resources/openapi.yaml",
                    "services/notification-service/src/main/resources/openapi.yaml",
                    "services/account-service/src/main/resources/openapi.yaml",
                    "services/card-service/src/main/resources/openapi.yaml");

    /**
     * The keys of an OpenAPI document whose values are prose a reader reads.
     *
     * <p>{@code description} carries the body copy, {@code summary} the one-line lead of an operation
     * or an example, and {@code title} the document's own name. Every other value is a path, a type,
     * a pattern or an identifier, which is a contract rather than prose.
     */
    private static final Set<String> OPENAPI_PROSE_KEYS =
            Set.of("description", "summary", "title");

    /**
     * A quotation attributed to a source outside this engagement, which Rule 5 exempts.
     *
     * <p>The rule's special handling skips direct quotes and preserves their wording. The earlier
     * scorer dropped a Markdown blockquote line and nothing else, so an inline quotation was scored
     * as though this engagement had written it: a 47-word quotation of the user's own binding
     * constraint was counted a violation, and the report published a rewrite of the user's words.
     *
     * <p>An attributed quotation is recognized by the cue that introduces it, within forty characters
     * of the opening quotation mark, over a quoted span of at least twenty characters. Only the
     * quoted words are exempt: the sentence carrying them is still scored, so a long lead-in to a
     * short quotation still counts.
     *
     * <p>The cue is bounded by letters on both sides. Without that bound the alternative {@code ask}
     * matched inside {@code masking}, which paired the closing quotation mark of one line with the
     * opening mark of the next and exempted every word between them. Two neighbouring paragraphs of
     * {@code docs/suggested-next-tasks.md} were read as one sentence of forty-one words for exactly
     * that reason. A quoted span may still cross a line ending, because a wrapped file breaks one
     * quotation over several lines, but it may not cross a blank line: a paragraph break ends a
     * quotation whatever the marks around it do.
     */
    private static final Pattern ATTRIBUTED_QUOTATION =
            Pattern.compile(
                    "(?i)(?<![A-Za-z])(?:reads?|states?|stated|says?|said|wrote|writes|quoted"
                            + "|quotes|asks?|asked|requires?|required|verbatim|according to"
                            + "|in the user's own words|in the user's words|puts it)(?![A-Za-z])"
                            + "[^\"\u201c\n]{0,40}"
                            + "[\"\u201c]((?:(?!\n\s*\n)[^\"\u201d]){20,})[\"\u201d]");

    /** What an attributed quotation reads as once its words are exempt. */
    private static final String QUOTATION_TOKEN = "QUOTE";

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

    private static final Set<String> BUZZWORD_PRINCIPLES = Set.of("V5", "A6");

    private static final int LONG_SENTENCE_WORDS = 30;
    private static final int LONG_PARAGRAPH_SENTENCES = 5;
    private static final int MINIMUM_REWRITE_REDUCTION_PERCENT = 15;

    /** The results a judged principle may carry. {@code Not measured} is not one of them. */
    private static final Set<String> JUDGED_RESULTS =
            Set.of("Pass", "Soft violation", "Hard violation", "Not applicable");

    /** Shortest evidence cell a principle row may carry. A dash is not evidence. */
    private static final int MINIMUM_EVIDENCE_CHARACTERS = 20;

    /** Evidence cells of one scorecard that have to differ from one another. */
    private static final int MINIMUM_DISTINCT_EVIDENCE = 15;

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

    /** One sentence the measurement found too long, with the length that made it one. */
    private record Offender(int words, String text) {}

    /**
     * What a fresh measurement of one target found.
     *
     * <p>Every violation is carried, not only the worst of each kind, because Rule 5 asks for a
     * quote, a principle, a rewrite and a reason for each one. The two {@code longest} figures are
     * carried for the opposite reason: a principle that passes owes evidence too, and the longest
     * sentence and paragraph a target does hold is what a pass on length rests on.
     *
     * @param blocks                    scored paragraphs
     * @param sentences                 sentences inside them
     * @param longSentenceList          every sentence over thirty words
     * @param longParagraphList         every paragraph over five sentences, as its sentences
     * @param buzzwordUses              every buzzword occurrence, in the form it was written
     * @param longestSentenceWords      the longest scored sentence, in words
     * @param longestParagraphSentences the longest scored paragraph, in sentences
     */
    private record ProseMeasurement(
            int blocks,
            int sentences,
            List<Offender> longSentenceList,
            List<List<String>> longParagraphList,
            List<String> buzzwordUses,
            int longestSentenceWords,
            int longestParagraphSentences) {

        int longSentences() {
            return longSentenceList.size();
        }

        int longParagraphs() {
            return longParagraphList.size();
        }

        int buzzwords() {
            return buzzwordUses.size();
        }

        String worstSentence() {
            return worstOffender() == null ? "" : worstOffender().text();
        }

        int worstSentenceWords() {
            return worstOffender() == null ? 0 : worstOffender().words();
        }

        Offender worstOffender() {
            return longSentenceList.stream()
                    .max(java.util.Comparator.comparingInt(Offender::words))
                    .orElse(null);
        }

        List<String> worstParagraph() {
            return longParagraphList.stream()
                    .max(java.util.Comparator.comparingInt(List::size))
                    .orElse(List.of());
        }

        int hard() {
            return buzzwords();
        }

        int soft() {
            return longSentences() + longParagraphs();
        }

        /**
         * Returns the verdict the rule's own severity scale gives these counts.
         *
         * <p>CLEAN is zero hard violations and at most two soft ones. Admitting a third soft
         * violation would publish CLEAN for a target the rule calls NEEDS WORK. ROUGH DRAFT is four
         * or more hard violations, and everything between the two is NEEDS WORK.
         *
         * @return CLEAN, NEEDS WORK or ROUGH DRAFT
         */
        String verdict() {
            if (hard() >= 4) {
                return "ROUGH DRAFT";
            }
            return hard() >= 1 || soft() >= 3 ? "NEEDS WORK" : "CLEAN";
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
     * <p>The deck is one file opened from disk, so a header is not available and a meta element is
     * the only place a policy can be stated. Three properties are read. Every directive is present
     * with the value it was verified under. The inline module is admitted by the digest of its own
     * text rather than by an unsafe-inline keyword, which is what makes an injected script inert.
     * And the digest is recomputed here from the script the deck actually holds, so an edit to that
     * script fails this test rather than silently disabling the only script the policy allows.
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
     * <p>The style block is removed first, and that is the whole point. Reading
     * {@code deck.contains("." + className)} and then {@code deck.contains(className)} asserts one
     * thing twice, because a rule named {@code .kpi-card} contains the text {@code kpi-card}: a
     * class defined in the theme and applied to nothing passes both. Collecting the {@code class}
     * attributes of the remaining markup asks whether the markup applies the class.</p>
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
        assertTrue(
                declarationOf(".reveal .deck-slide-number").contains("color: var(--blitzy-text)"));

        String eyebrow = declarationOf(".slide-title .eyebrow");
        assertTrue(eyebrow.contains("color: var(--blitzy-accent-teal)"));
        assertTrue(
                eyebrow.contains("background: var(--blitzy-primary-navy)"),
                "The teal eyebrow needs its own dark backing to clear the contrast floor");

        assertEquals(
                slides.size(),
                occurrences(deckMarkup, "<span class=\"deck-slide-number\" aria-hidden=\"true\">"),
                "Every decorative slide number must be hidden from assistive technology");
        assertEquals(
                0,
                occurrences(deck, "class=\"slide-number\""),
                "slide-number is reveal's own class: its stylesheet styles it and two of its view"
                        + " modes hide it outright, so the authored numbers must not borrow the name");
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
        // would leave 2.6 MB unverified, and the second would delay every talk by a download.
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
     * <p>A report that invents a name for a principle leaves a reader comparing it against the rule
     * unable to tell which principle a row scored. The names are asserted rather than the numbers
     * alone, because a number without the rule's own name lets that drift pass.
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
    @DisplayName("the prose report declares the required method, boundaries and twenty-six targets")
    void theProseReportDeclaresTheRequiredMethodBoundariesAndTargets() {
        assertTrue(prose.startsWith("# Prose Validation\n"));
        assertTrue(prose.contains("Every target is Technical, so the Asimov agent governs"));
        assertTrue(prose.contains("V2, V3, V6, and V7 carry the highest weight"));
        assertTrue(prose.contains("V1 and V5 carry reduced weight"));
        assertTrue(prose.contains("Pass**, **Soft violation**, and **Hard violation"));
        assertTrue(prose.contains("B1 through B5"));
        assertTrue(prose.contains("Only the new `Modernized card platform` section"));
        assertTrue(prose.contains("Only headings, body copy, bullets, metric labels, and table cells"));

        assertTrue(prose.contains("CLEAN is zero hard and at most two soft."),
                "the threshold the rule sets has to be the threshold the report states");
        assertTrue(prose.contains("twenty-six targets"),
                "the inventory the report claims has to be the inventory it scores");
        assertTrue(
                prose.contains("`description`, `summary` and `title`"),
                "an OpenAPI document is scored as its prose values, and the report has to say which");
        assertTrue(
                prose.contains("attributed to a source outside this engagement is skipped"),
                "Rule 5 skips a direct quotation, and the report has to state that boundary");
        assertTrue(
                prose.contains("Every principle carries a judged result and the evidence behind it."),
                "a principle with no result is a review that did not happen");
        assertTrue(
                prose.contains("Every counted violation carries its own entry."),
                "a count with no entry beside it is a count a reader cannot check");
        assertFalse(
                prose.contains("Not measured"),
                "a principle reading Not measured is the self-attestation a review rejected: judge it"
                        + " and publish the evidence, or record it Not applicable with a reason");

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

            String measurementRow =
                    "| %d | `%s` | %d | %d | %d | %d | %d |"
                            .formatted(
                                    number,
                                    target,
                                    measured.blocks(),
                                    measured.sentences(),
                                    measured.longSentences(),
                                    measured.longParagraphs(),
                                    measured.buzzwords());
            assertTrue(
                    prose.contains(measurementRow),
                    "The measurement table does not publish what was read and found in "
                            + target
                            + ". Measured row: "
                            + measurementRow);

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
     * Holds every principle row to a judged result and to evidence a reader can check.
     *
     * <p>A principle reading {@code Not measured} with no evidence beside it is a review that was
     * not performed. Four results are admitted and {@code Not measured} is not among them:
     * {@code Pass}, {@code Soft violation}, {@code Hard violation} and {@code Not applicable}. The
     * last one is for a principle a technical reference cannot offend, and it owes a reason like
     * any other.
     *
     * <p>Five principles carry a mechanical result, and the four length and buzzword figures decide
     * it. Their evidence is mechanical too: a pass on sentence length has to publish the longest
     * sentence the target does hold, a pass on paragraph length the longest paragraph, and a pass on
     * buzzwords the number of paragraphs read. A pass with nothing beside it is what this test exists
     * to refuse.
     *
     * <p>The other seventeen are judged, and the judgement is bound to the text two ways. Every
     * evidence cell has to name something from the target: a backticked span or a quoted span that
     * occurs in the file. A cell whose result is {@code Not applicable} is exempt from that, because
     * an absence cannot be quoted. And one section may not fill its rows with one sentence: at least
     * fifteen of its twenty-two evidence cells have to differ from each other.
     */
    private static void assertPrincipleResults(
            String section, int number, ProseMeasurement measured) throws IOException {
        String target = PROSE_TARGETS.get(number - 1);
        String targetText = Files.readString(platformRoot.resolve(target).normalize());
        Set<String> distinctEvidence = new LinkedHashSet<>();
        for (String row : PRINCIPLE_ROWS) {
            String key = row.substring(0, row.indexOf(':'));
            Matcher matcher =
                    Pattern.compile(
                                    "(?m)^\\| "
                                            + Pattern.quote(row)
                                            + " \\| [^|]+ \\| ([^|]+) \\| ([^|]*)\\|")
                            .matcher(section);
            assertTrue(
                    matcher.find(),
                    "Section " + number + " has no row for principle " + row);
            String result = matcher.group(1).strip();
            String evidence = matcher.group(2).strip();

            String expected = null;
            if (SENTENCE_PRINCIPLES.contains(key)) {
                expected = measured.longSentences() > 0 ? "Soft violation" : "Pass";
            } else if (PARAGRAPH_PRINCIPLES.contains(key)) {
                expected = measured.longParagraphs() > 0 ? "Soft violation" : "Pass";
            } else if (BUZZWORD_PRINCIPLES.contains(key)) {
                expected = measured.buzzwords() > 0 ? "Hard violation" : "Pass";
            }
            if (expected != null) {
                assertEquals(
                        expected,
                        result,
                        "Section " + number + " publishes the wrong result for " + row);
            } else {
                assertTrue(
                        JUDGED_RESULTS.contains(result),
                        "Section " + number + " publishes \"" + result + "\" for " + row
                                + ", and the rule admits only " + JUDGED_RESULTS
                                + ". A principle nobody judged is a review nobody performed");
            }

            assertTrue(
                    evidence.length() >= MINIMUM_EVIDENCE_CHARACTERS && !"—".equals(evidence),
                    "Section " + number + " gives no evidence for " + row + ": \"" + evidence + "\"");
            distinctEvidence.add(evidence);

            if ("Pass".equals(expected) && SENTENCE_PRINCIPLES.contains(key)) {
                assertTrue(
                        evidence.contains(
                                "longest scored sentence " + measured.longestSentenceWords()
                                        + " words"),
                        "Section " + number + " passes " + key + " and owes the longest sentence it"
                                + " does hold, which is " + measured.longestSentenceWords()
                                + " words: \"" + evidence + "\"");
            }
            if ("Pass".equals(expected) && PARAGRAPH_PRINCIPLES.contains(key)) {
                assertTrue(
                        evidence.contains(
                                "longest scored paragraph " + measured.longestParagraphSentences()
                                        + " sentences"),
                        "Section " + number + " passes " + key + " and owes the longest paragraph it"
                                + " does hold, which is " + measured.longestParagraphSentences()
                                + " sentences: \"" + evidence + "\"");
            }
            if ("Pass".equals(expected) && BUZZWORD_PRINCIPLES.contains(key)) {
                assertTrue(
                        evidence.contains("no buzzword in " + measured.blocks()
                                + " scored paragraphs"),
                        "Section " + number + " passes " + key + " and owes the number of paragraphs"
                                + " read, which is " + measured.blocks() + ": \"" + evidence + "\"");
            }
            if (expected == null && !"Not applicable".equals(result)) {
                assertTrue(
                        namesSomethingIn(evidence, targetText),
                        "Section " + number + " judges " + key + " without naming anything from "
                                + target + ". Quote a passage or name an identifier the file carries:"
                                + " \"" + evidence + "\"");
            }
        }
        assertTrue(
                distinctEvidence.size() >= MINIMUM_DISTINCT_EVIDENCE,
                "Section " + number + " repeats itself across its scorecard: only "
                        + distinctEvidence.size() + " of " + PRINCIPLE_ROWS.size()
                        + " evidence cells differ, and " + MINIMUM_DISTINCT_EVIDENCE
                        + " is the floor");
    }

    /**
     * Reports whether one evidence cell names something the target really carries.
     *
     * <p>A backticked span or a double-quoted span inside the cell is read as the thing named, and one
     * of them has to occur in the file. The comparison is a substring test on the raw file, so naming
     * a heading, an identifier, a path or a phrase all work, and inventing one does not.
     *
     * @param evidence   the evidence cell
     * @param targetText the whole target
     * @return true where at least one named span occurs in the target
     */
    private static boolean namesSomethingIn(String evidence, String targetText) {
        Matcher named =
                Pattern.compile("`([^`]+)`|\"([^\"]+)\"|\u201c([^\u201d]+)\u201d").matcher(evidence);
        while (named.find()) {
            for (int group = 1; group <= named.groupCount(); group++) {
                String span = named.group(group);
                if (span != null && span.length() >= 3 && targetText.contains(span)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Requires one complete entry for every violation counted, not one for the worst of each kind.
     *
     * <p>Rule 5 asks for a quote, a named principle, a rewrite and a reason for each violation, so
     * a target publishing 28 soft violations under two entries leaves 26 counts a reader cannot
     * check. Three rules follow. Every over-length sentence measured is quoted by some V3 entry,
     * and no V3 entry quotes a sentence the measurement did not find. Every over-long paragraph
     * measured is named by a split point that begins one of its own sentences. Every buzzword
     * counted is named by an A6 entry.
     *
     * <p>A target with nothing measured says so, in one sentence, so silence is never mistaken for an
     * omission.
     */
    private static void assertEntriesCoverEveryViolation(
            String section, int number, ProseMeasurement measured) {
        String entries = section.substring(section.indexOf("**Per-violation entries:**"));
        if (measured.soft() == 0 && measured.hard() == 0) {
            assertTrue(
                    entries.contains("None. This target has no measured violation."),
                    "Section " + number + " measures clean and must say so under its entries");
            return;
        }

        Set<String> quoted = new LinkedHashSet<>();
        Matcher sentenceEntry =
                Pattern.compile(
                                "(?ms)^\\*\\*" + number + "\\.\\d+ — V3: Keep it simple\\*\\*.*?"
                                        + "^> (.+?)$")
                        .matcher(entries);
        while (sentenceEntry.find()) {
            quoted.add(asScored(sentenceEntry.group(1)));
        }
        Set<String> measuredSentences = new LinkedHashSet<>();
        measured.longSentenceList().forEach(offender -> measuredSentences.add(offender.text()));
        assertEquals(
                measuredSentences,
                quoted,
                "Section " + number + " owes one V3 entry per over-length sentence it counts. It"
                        + " counts " + measuredSentences.size() + " and quotes " + quoted.size());

        if (measured.longParagraphs() > 0) {
            assertTrue(
                    entries.contains("— V2: Do not ramble**"),
                    "Section " + number + " measures an over-long paragraph and owes a V2 entry");
            List<String> splits = new ArrayList<>();
            Matcher split = Pattern.compile("Start a new paragraph at \"([^\"]+)\"").matcher(entries);
            while (split.find()) {
                splits.add(asScored(split.group(1)));
            }
            assertEquals(
                    measured.longParagraphs(),
                    splits.size(),
                    "Section " + number + " counts " + measured.longParagraphs() + " over-long"
                            + " paragraphs and names " + splits.size() + " split points");
            for (String prefix : splits) {
                assertTrue(
                        measured.longParagraphList().stream()
                                .anyMatch(
                                        paragraph ->
                                                paragraph.stream()
                                                        .anyMatch(
                                                                sentence ->
                                                                        sentence.startsWith(
                                                                                prefix))),
                        "Section "
                                + number
                                + " names a split point that begins no sentence of any measured"
                                + " paragraph: "
                                + prefix);
            }
        }

        for (String buzzword : measured.buzzwordUses()) {
            assertTrue(
                    entries.contains("— A6: No Ornamental Language**")
                            && entries.contains(buzzword),
                    "Section " + number + " counts the buzzword \"" + buzzword + "\" and owes an A6"
                            + " entry naming it");
        }
    }

    @Test
    @DisplayName("every published rewrite is shorter than its offender and reads under thirty words a sentence")
    void thePublishedRewritesAreShorterThanTheirOffenders() throws IOException {
        Pattern entry =
                Pattern.compile(
                        "(?ms)^\\*\\*(\\d+)\\.(\\d+) — V3: Keep it simple\\*\\*.*?soft violation, (\\d+) words\\.\\n"
                                + "\\n> (.+?)\\n\\n\\*\\*Rewrite\\*\\* — (\\d+) words, (\\d+)% shorter:\\n"
                                + "\\n> (.+?)\\n");
        Matcher matcher = entry.matcher(prose);
        int checked = 0;
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            String label = number + "." + matcher.group(2);
            int declaredOffenderWords = Integer.parseInt(matcher.group(3));
            String offender = matcher.group(4).strip();
            int declaredRewriteWords = Integer.parseInt(matcher.group(5));
            int declaredReduction = Integer.parseInt(matcher.group(6));
            String rewrite = matcher.group(7).strip();

            ProseMeasurement measured = measure(PROSE_TARGETS.get(number - 1));
            Offender quoted =
                    measured.longSentenceList().stream()
                            .filter(found -> found.text().equals(asScored(offender)))
                            .findFirst()
                            .orElse(null);
            assertNotNull(
                    quoted,
                    "Entry " + label + " quotes a passage the measurement did not find over thirty"
                            + " words in " + PROSE_TARGETS.get(number - 1));
            assertEquals(
                    quoted.words(),
                    declaredOffenderWords,
                    "Entry " + label + " declares a length the measurement does not give");
            assertEquals(
                    declaredRewriteWords,
                    wordCount(asScored(rewrite)),
                    "Entry " + label + " declares a rewrite length that does not match its rewrite");
            int reduction =
                    Math.round(
                            100f
                                    * (declaredOffenderWords - declaredRewriteWords)
                                    / declaredOffenderWords);
            assertEquals(
                    reduction,
                    declaredReduction,
                    "Entry " + label + " declares a reduction its two lengths do not give");
            assertTrue(
                    reduction >= MINIMUM_REWRITE_REDUCTION_PERCENT,
                    "Entry " + label + " cuts only " + reduction + "%, and the rule asks for fifteen");
            for (String sentence : sentences(asScored(rewrite))) {
                assertTrue(
                        wordCount(sentence) <= LONG_SENTENCE_WORDS,
                        "Entry "
                                + label
                                + " proposes a replacement sentence of "
                                + wordCount(sentence)
                                + " words");
            }
            checked++;
        }
        int owed = 0;
        for (String target : PROSE_TARGETS) {
            owed += measure(target).longSentences();
        }
        assertEquals(
                owed,
                checked,
                "The report owes one worked rewrite per over-length sentence, not one per target");
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
     * <p>Removing tables, headings, metric grids and icon rows before counting would drop most of
     * what a slide of this deck carries, so slides holding 103, 94, 88 and 43 visible words would
     * pass a ceiling of {@value #RULE_FOUR_WORD_LIMIT}.
     *
     * @param slideBody the markup of one slide
     * @return how many visible words it carries
     */
    private static int bodyWordCount(String slideBody) {
        String text = slideBody;
        text = text.replaceAll("(?s)<pre class=\"mermaid\">.*?</pre>", " ");
        text = text.replaceAll("(?s)<span class=\"deck-slide-number\"[^>]*>.*?</span>", " ");
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
     * document index. A figure stated in three places and measured in none goes stale in the two
     * artifacts a customer and a new developer read first. The register's own heading is already
     * held to its rows by {@code DocumentationContractTest}, so binding these three to the same
     * figure closes the loop.
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
                deck.contains("All " + digits + " flagged source rules carry a file citation, and a"
                        + " line wherever a line exists."),
                "the deck's business-value table must state all " + digits
                        + " flagged source rules, which is what the register carries, and it must"
                        + " claim a line only where one exists: three findings are absences, and an"
                        + " absence has a file to name and no line to open");

        // The claim above is held to the register rather than taken on trust. Every row must cite a
        // file, and a row without a line locator must be one of the absences the register declares.
        int withFileCitation = 0;
        int withLineLocator = 0;
        int registerRows = 0;
        Matcher row = Pattern.compile("(?m)^\\|\\s*(\\d+)\\s+\\|.*$").matcher(coverage);
        while (row.find()) {
            registerRows++;
            String cells = row.group();
            if (Pattern.compile("app/[A-Za-z0-9_./-]+").matcher(cells).find()) {
                withFileCitation++;
            }
            if (Pattern.compile(":L\\d+").matcher(cells).find()) {
                withLineLocator++;
            }
        }
        assertEquals(findings, registerRows, "the coverage section must hold one row per finding");
        assertEquals(registerRows, withFileCitation,
                "every register row must cite a source file, which is the half of the claim that"
                        + " admits no exception");
        assertTrue(withLineLocator < registerRows,
                "a register in which every row carried a line would make the absence wording wrong");
        assertTrue(register.contains("Three of the " + registerRows + " findings are absences"),
                "the register must publish how many of its rows document an absence, and "
                        + (registerRows - withLineLocator) + " carry no line locator");
        assertEquals(3, registerRows - withLineLocator,
                "three rows document an absence and therefore carry no line; a fourth means either a"
                        + " new absence to declare or a locator someone left out");

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
     * <p>A report can claim every target CLEAN against text that has moved beneath it: edit the
     * root guide, the deck, the onboarding guide, the equivalence results, the flagged-rule
     * register, the decision log or the traceability matrix after a scoring pass, and the verdicts
     * describe documents that no longer exist. A digest per target turns that from something a
     * reader has to notice into something this build refuses: change a scored file and the report
     * fails here until the pass is run again over the new text.
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
        List<Offender> longSentences = new ArrayList<>();
        List<List<String>> longParagraphs = new ArrayList<>();
        List<String> buzzwordUses = new ArrayList<>();
        int blocks = 0;
        int sentenceCount = 0;
        int longestSentenceWords = 0;
        int longestParagraphSentences = 0;
        for (String block : scoredBlocks(target, text)) {
            blocks++;
            List<String> sentences = sentences(block);
            sentenceCount += sentences.size();
            longestParagraphSentences = Math.max(longestParagraphSentences, sentences.size());
            if (sentences.size() > LONG_PARAGRAPH_SENTENCES) {
                longParagraphs.add(sentences);
            }
            for (String sentence : sentences) {
                int words = wordCount(sentence);
                longestSentenceWords = Math.max(longestSentenceWords, words);
                if (words > LONG_SENTENCE_WORDS) {
                    longSentences.add(new Offender(words, sentence));
                }
            }
            Matcher buzzword = PROSE_BUZZWORD.matcher(block);
            while (buzzword.find()) {
                buzzwordUses.add(buzzword.group());
            }
        }
        return new ProseMeasurement(
                blocks,
                sentenceCount,
                List.copyOf(longSentences),
                List.copyOf(longParagraphs),
                List.copyOf(buzzwordUses),
                longestSentenceWords,
                longestParagraphSentences);
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
        } else if (target.endsWith(".yaml")) {
            body = openApiProse(text);
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
        body =
                ATTRIBUTED_QUOTATION
                        .matcher(body)
                        .replaceAll(
                                match ->
                                        Matcher.quoteReplacement(
                                                match.group()
                                                        .replace(
                                                                match.group(1),
                                                                QUOTATION_TOKEN)));
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

    /**
     * Returns the prose of an OpenAPI document, as blank-line separated paragraphs.
     *
     * <p>The document is parsed rather than read line by line, so a key inside a description body
     * cannot be mistaken for a key of the document. Every value under
     * {@link #OPENAPI_PROSE_KEYS} is collected in document order, and every newline left inside a
     * collected value becomes a paragraph break. That last step is what makes the paragraph counts
     * mean anything: these documents write their descriptions as folded scalars, where a line wrap is
     * already a space and only a blank line survives as a newline.
     *
     * @param text the whole document
     * @return its prose, ready for the same block reader Markdown body copy goes through
     */
    private static String openApiProse(String text) {
        List<String> values = new ArrayList<>();
        collectProse(new Yaml().load(text), values);
        StringBuilder prose = new StringBuilder();
        for (String value : values) {
            String stripped = value.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            if (!prose.isEmpty()) {
                prose.append("\n\n");
            }
            prose.append(stripped.replace("\n", "\n\n"));
        }
        return prose.toString();
    }

    /**
     * Collects every prose value of one parsed node, in document order.
     *
     * @param node   a map, a list or a scalar of the parsed document
     * @param values the collected values, appended to
     */
    private static void collectProse(Object node, List<String> values) {
        if (node instanceof Map<?, ?> map) {
            map.forEach(
                    (key, value) -> {
                        if (OPENAPI_PROSE_KEYS.contains(String.valueOf(key))
                                && value instanceof String prose) {
                            values.add(prose);
                        } else {
                            collectProse(value, values);
                        }
                    });
        } else if (node instanceof Iterable<?> items) {
            for (Object item : items) {
                collectProse(item, values);
            }
        }
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
