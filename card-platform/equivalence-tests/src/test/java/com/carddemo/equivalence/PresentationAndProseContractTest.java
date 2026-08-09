package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Protects the Rule 4 presentation and Rule 5 prose-validation deliverables.
 *
 * <p>The checks intentionally inspect the shipped artifacts rather than a generated representation. A
 * presentation that compiles but loses a slide visual, lifecycle hook, or pinned dependency is not the
 * required deliverable.
 */
class PresentationAndProseContractTest {

    private static final Pattern SECTION =
            Pattern.compile("(?s)<section\\b([^>]*)>(.*?)</section>");
    private static final Pattern RESOURCE =
            Pattern.compile("(?:href|src)=\"([^\"]+)\"");
    private static final Pattern REPORT =
            Pattern.compile(
                    "(?ms)^### \\d+\\. .*?$(.*?)(?=^### \\d+\\. |^## Exemptions applied)");
    private static final Pattern FORBIDDEN_DECK_WORD =
            Pattern.compile(
                    "(?i)\\b(?:leverage|utilize|facilitate|synergy|holistic|paradigm|very|really|quite|rather)\\b");
    private static final Pattern FORBIDDEN_SEQUENCE_WORD =
            Pattern.compile(
                    "(?i)\\b(?:gantt|timeline|week|sprint|quarter|january|february|march|april|may|june|july|august|september|october|november|december)\\b");

    private static Path platformRoot;
    private static String deck;
    private static String prose;

    @BeforeAll
    static void loadArtifacts() throws IOException {
        platformRoot = locatePlatformRoot();
        deck = Files.readString(platformRoot.resolve("presentation/executive-summary.html"));
        prose = Files.readString(platformRoot.resolve("docs/prose-validation.md"));
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
        Matcher matcher = SECTION.matcher(deck);
        int count = 0;
        while (matcher.find()) {
            count++;
            assertFalse(
                    matcher.group(2).contains("<section"),
                    "A top-level slide must not contain a vertical sub-slide");
        }
        assertEquals(16, count);
        assertEquals(16, occurrences(deck, "</section>"));
    }

    @Test
    @DisplayName("every slide contains a rendering non-text visual")
    void everySlideContainsARenderingNonTextVisual() {
        Matcher matcher = SECTION.matcher(deck);
        int slide = 0;
        while (matcher.find()) {
            slide++;
            String body = matcher.group(2);
            boolean hasVisual =
                    body.contains("data-lucide=")
                            || body.contains("<table")
                            || body.contains("<pre class=\"mermaid\"")
                            || body.contains("class=\"kpi-grid\"")
                            || body.contains("class=\"icon-row\"")
                            || body.contains("class=\"accent-bar\"");
            assertTrue(hasVisual, "Slide " + slide + " is text-only");
        }
        assertEquals(16, slide);
    }

    @Test
    @DisplayName("the deck pins the required libraries and has no local media dependency")
    void theDeckPinsRequiredLibrariesAndHasNoLocalMediaDependency() {
        assertTrue(deck.contains("reveal.js@5.1.0/dist/reveal.css"));
        assertTrue(deck.contains("reveal.js@5.1.0/dist/reveal.js"));
        assertTrue(deck.contains("mermaid@11.4.0/dist/mermaid.esm.min.mjs"));
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
        properties.forEach(property -> assertTrue(deck.contains(property), property));

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
        classes.forEach(
                className -> {
                    assertTrue(deck.contains("." + className), "Missing style for " + className);
                    assertTrue(
                            deck.contains(className),
                            "Required visual class is not used: " + className);
                });
    }

    @Test
    @DisplayName("the three named Mermaid diagrams cover both states, event flow and dependency order")
    void theThreeNamedMermaidDiagramsCoverTheRequiredViews() {
        assertEquals(3, occurrences(deck, "<pre class=\"mermaid\">"));
        assertTrue(deck.contains("Figure 1 — From shared files to owned schemas"));
        assertTrue(deck.contains("subgraph B[\"Before\"]"));
        assertTrue(deck.contains("subgraph A[\"After\"]"));
        assertTrue(deck.contains("Figure 2 — One outcome event starts the work"));
        assertTrue(deck.contains("transaction.authorized"));
        assertTrue(deck.contains("transaction.posted"));
        assertTrue(deck.contains("fraud.assessed"));
        assertTrue(deck.contains("carddemo.dead-letter"));
        assertTrue(deck.contains("Figure 3 — Each capability starts from a proven contract"));
        assertTrue(deck.contains("Continuous equivalence suite"));
        assertFalse(FORBIDDEN_SEQUENCE_WORD.matcher(deck).find());
        String proseWithoutRequiredStageDimensions =
                deck.replace("width: 1920", "").replace("height: 1080", "");
        assertFalse(
                Pattern.compile("\\b(?:19|20)\\d{2}\\b")
                        .matcher(proseWithoutRequiredStageDimensions)
                        .find());
    }

    @Test
    @DisplayName("Reveal, Mermaid and Lucide use navigation-safe lifecycle hooks")
    void revealMermaidAndLucideUseNavigationSafeLifecycleHooks() {
        assertTrue(deck.contains("startOnLoad: false"));
        assertTrue(deck.contains("theme: \"base\""));
        assertTrue(deck.contains("htmlLabels: false"));
        assertTrue(deck.contains("wrappingWidth: 420"));
        assertTrue(deck.contains("primaryColor: \"#F2F0FE\""));
        assertTrue(deck.contains("primaryTextColor: \"#333333\""));
        assertTrue(deck.contains("primaryBorderColor: \"#5B39F3\""));
        assertTrue(deck.contains("lineColor: \"#999999\""));
        assertTrue(deck.contains("secondaryColor: \"#F4EFF6\""));
        assertTrue(deck.contains("Reveal.on(\"ready\""));
        assertTrue(deck.contains("Reveal.on(\"slidechanged\""));
        assertEquals(2, occurrences(deck, "renderMermaidFor(event.currentSlide)"));
        assertEquals(2, occurrences(deck, "renderLucideIcons();"));
        assertTrue(deck.contains("node.removeAttribute(\"data-processed\")"));
        assertTrue(deck.contains("slides.style.transform = \"translate(-50%, -50%) scale(1)\""));
        assertTrue(deck.contains("await window.mermaid.run({ nodes })"));
        assertTrue(deck.contains("hash: true"));
        assertTrue(deck.contains("transition: \"slide\""));
        assertTrue(deck.contains("controlsTutorial: false"));
        assertTrue(deck.contains("width: 1920"));
        assertTrue(deck.contains("height: 1080"));
        assertTrue(deck.contains(".reveal .progress"));
        assertTrue(deck.contains("display: none !important"));
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
        assertEquals(19, count);
    }

    @Test
    @DisplayName("the prose verdicts, counts and exemptions are self-consistent")
    void theProseVerdictsCountsAndExemptionsAreSelfConsistent() {
        assertEquals(19, occurrences(prose, "| CLEAN | 0 | 0 |"));
        assertEquals(
                19,
                occurrences(
                        prose,
                        "**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations."));
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
        assertFalse(
                Pattern.compile("(?i)\\b(?:minor|major|moderate)\\b")
                        .matcher(prose)
                        .find());
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