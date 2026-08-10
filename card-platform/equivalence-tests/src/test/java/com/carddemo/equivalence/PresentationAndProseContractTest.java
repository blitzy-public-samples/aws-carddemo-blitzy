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
        assertEquals(3, occurrences(deck, "<pre class=\"mermaid\""));
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
        assertTrue(deck.contains("fontFamily: \"Inter, system-ui, sans-serif\""));
        assertTrue(deck.contains("titleColor: \"#333333\""));
        assertTrue(deck.contains("Reveal.on(\"ready\""));
        assertTrue(deck.contains("Reveal.on(\"slidechanged\""));
        assertTrue(deck.contains("renderAllDiagrams();"));
        assertTrue(deck.contains("renderDiagramsIn(event.currentSlide);"));
        assertEquals(2, occurrences(deck, "renderLucideIcons();"));
        assertTrue(deck.contains("await mermaid.run({ nodes })"));
        assertTrue(deck.contains("hash: true"));
        assertTrue(deck.contains("transition: \"slide\""));
        assertTrue(deck.contains("controlsTutorial: false"));
        assertTrue(deck.contains("width: 1920"));
        assertTrue(deck.contains("height: 1080"));
        assertTrue(deck.contains(".reveal .progress"));
        assertTrue(deck.contains("display: none !important"));
    }

    /**
     * Locks in the lifecycle properties that decide whether the diagrams survive real use.
     *
     * <p>Each assertion stands for one way the diagrams were previously lost. A cache keyed on a DOM
     * node is orphaned when reveal replaces a slide with a clone. A static Mermaid import as the
     * module's first statement means one unreachable host stops {@code Reveal.initialize} from ever
     * running, which blanks the whole deck. Rendering only the current slide leaves the print export
     * and the overview thumbnails showing raw diagram source. Mutating the live slide transform
     * during a render leaves a stale transform behind when a resize lands in the same window.</p>
     */
    @Test
    @DisplayName("the diagram lifecycle survives cloning, an unreachable library, print and overview")
    void theDiagramLifecycleSurvivesCloningAnUnreachableLibraryPrintAndOverview() {
        assertEquals(3, occurrences(deck, "data-diagram=\""));
        assertEquals(3, occurrences(deck, "data-diagram-label=\""));
        assertTrue(deck.contains("diagramSources.set(node.dataset.diagram"));
        assertFalse(deck.contains("WeakMap"), "a node-keyed cache cannot survive slide cloning");

        assertFalse(
                deck.contains("import mermaid from"),
                "a static Mermaid import blocks Reveal.initialize when the library is unreachable");
        assertTrue(deck.contains("import(MERMAID_MODULE_URL)"));
        assertTrue(deck.contains("typeof Reveal === \"undefined\""));
        assertTrue(deck.contains("showDiagramNotice"));
        assertTrue(deck.contains(".mermaid-notice"));

        assertFalse(
                deck.contains("slides.style.transform"),
                "a render must not write the live slide transform");
        assertTrue(deck.contains("mermaid-render-stage"));
        assertTrue(deck.contains("renderingDiagrams.has(key)"));
        assertTrue(deck.contains("renderedDiagrams.has(key)"));

        assertTrue(deck.contains("history: true"));
        assertTrue(deck.contains("controlsBackArrows: \"visible\""));
        assertTrue(deck.contains("scrollActivationWidth: 0"));
        assertTrue(deck.contains("margin: 0"));
        assertEquals(2, occurrences(deck, " defer src=\"https://cdn.jsdelivr.net/"));
        assertEquals(2, occurrences(deck, "rel=\"preload\" as=\"style\""));
    }

    /**
     * Locks the visual system to its tokens and keeps each painted surface to a single layer.
     *
     * <p>A gradient declared on a section as well as on the background element reveal generates for
     * it is painted twice over two differently sized boxes, and the percentage stops then leave a
     * rectangular seam. A radius or an ink written as a literal drifts from the token that governs
     * the same surface elsewhere. A diagram capped below the width of the frame that holds it is
     * scaled down a second time and its labels stop being readable.</p>
     */
    @Test
    @DisplayName("the visual system paints each surface once and reads every value from a token")
    void theVisualSystemPaintsEachSurfaceOnceAndReadsEveryValueFromAToken() {
        assertTrue(deck.contains(".reveal .slide-background.slide-title"));
        assertTrue(deck.contains(".reveal .slide-background.slide-divider"));
        assertTrue(deck.contains(".reveal .slide-background.slide-closing"));
        assertTrue(deck.contains("background-image: var(--gradient-hero);"));
        assertTrue(deck.contains("background-image: var(--gradient-divider);"));
        assertFalse(
                deck.contains("background: var(--gradient-hero);"),
                "the hero gradient must be painted on the background element only");
        assertFalse(
                deck.contains("background: var(--gradient-divider);"),
                "the divider gradient must be painted on the background element only");

        // A browser that cannot reach the slide framework gets no background element at all, and the
        // two gradient slides hold inverted ink. Each keeps a gradient on the section itself for
        // exactly that state, scoped so it stops applying once reveal reports itself ready.
        assertTrue(deck.contains(".reveal:not(.ready) section.slide-title"));
        assertTrue(deck.contains(".reveal:not(.ready) section.slide-divider"));
        assertEquals(
                2,
                occurrences(deck, "background-image: var(--gradient-hero);"),
                "the hero gradient covers the background element and the framework-less section");
        assertEquals(
                2,
                occurrences(deck, "background-image: var(--gradient-divider);"),
                "the divider gradient covers the background element and the framework-less section");

        assertEquals(3, occurrences(deck, "border-radius: var(--card-radius);"));
        assertFalse(deck.contains("border-radius: 20px"), "a card radius must come from the token");
        assertTrue(deck.contains("--ink-invert-strong: rgba(255, 255, 255, 0.92);"));
        assertTrue(deck.contains("--ink-invert-soft: rgba(255, 255, 255, 0.72);"));
        assertEquals(
                2,
                occurrences(deck, "rgba(255, 255, 255, 0."),
                "the inverted inks are declared once each and used through their tokens");

        assertTrue(deck.contains("--diagram-frame-width: 1680px;"));
        assertTrue(deck.contains("--diagram-height: 580px;"));
        assertEquals(2, occurrences(deck, "max-width: var(--diagram-frame-width);"));
        assertTrue(deck.contains("max-height: var(--diagram-height);"));
        assertFalse(deck.contains("max-width: 1600px"), "the diagram may use the whole frame width");
        assertTrue(deck.contains("fontSize: \"24px\""));
        assertTrue(deck.contains(".mermaid text"));
        assertTrue(deck.contains(".mermaid .cluster-label text"));
        assertTrue(deck.contains("font-family: var(--ff-body) !important;"));
        assertTrue(deck.contains("  B ~~~ A"), "the two states sit side by side, each laid out down");

        assertTrue(deck.contains("align-items: start;"));
        assertFalse(deck.contains("min-height: 300px"), "a table must keep its own row heights");
    }

    /**
     * Holds the deck to what a keyboard and a screen reader need from it.
     *
     * <p>Reveal strips the outline from its own navigation buttons and leaves the chevrons black
     * whatever sits behind them, and it names neither the slides nor the diagrams. Each assertion
     * here stands for one of those gaps: a focus ring drawn in two colours so one of the pair always
     * separates from the background, an explicit control colour that follows reveal's own dark-slide
     * mark, a named region per slide, a named graphic per diagram, scoped table headers with a row
     * header naming each body row, named groups over the two grids, and a motion preference the deck
     * actually reads. The secondary ink is a deck-local value because the brand token it replaces is
     * enumerated and must keep its published value.</p>
     */
    @Test
    @DisplayName("the deck is operable by keyboard and legible to a screen reader")
    void theDeckIsOperableByKeyboardAndLegibleToAScreenReader() {
        assertTrue(deck.contains(".reveal .controls button:focus-visible"));
        assertTrue(deck.contains("0 0 0 3px var(--blitzy-text-invert),"));
        assertTrue(deck.contains("0 0 0 6px var(--blitzy-primary-navy);"));
        assertTrue(deck.contains(".reveal .controls {"));
        assertTrue(deck.contains(".reveal.has-dark-background .controls"));
        assertTrue(deck.contains(".reveal:has(section.present.has-dark-background) .controls"));

        assertTrue(deck.contains(".slide-title .eyebrow {"));
        assertTrue(deck.contains("      color: var(--blitzy-text-invert);\n      font-size: 0.8em;"));
        assertTrue(deck.contains("--ink-muted: #6B6B6B;"));
        assertEquals(3, occurrences(deck, "color: var(--ink-muted);"));
        assertFalse(
                deck.contains("color: var(--blitzy-text-muted);"),
                "the enumerated muted token is too light for text on a white surface");
        assertTrue(deck.contains("--blitzy-text-muted: #999999;"), "the token keeps its value");
        assertFalse(deck.contains("BLITZY [A11Y]"), "no contrast waiver survives");

        assertEquals(16, occurrences(deck, "aria-label=\"Slide "));
        assertEquals(3, occurrences(deck, "data-diagram-label=\""));
        assertTrue(deck.contains("svg.setAttribute(\"aria-label\", label)"));
        assertTrue(deck.contains("createElementNS(svg.namespaceURI, \"title\")"));

        assertEquals(8, occurrences(deck, "<th scope=\"col\">"));
        assertEquals(15, occurrences(deck, "<th scope=\"row\">"));
        assertEquals(4, occurrences(deck, "aria-labelledby=\""));
        assertEquals(4, occurrences(deck, "<h2 id=\""));
        assertTrue(deck.contains(".reveal table thead th {"));
        assertTrue(deck.contains(".reveal table tbody th {"));
        assertEquals(2, occurrences(deck, "role=\"group\""));
        assertTrue(deck.contains("<ul class=\"closing-links\" aria-label=\""));

        assertTrue(deck.contains("@media (prefers-reduced-motion: reduce)"));
        assertTrue(deck.contains("transition: none !important;"));

        assertTrue(deck.contains("nameNavigationControls();"));
        assertTrue(deck.contains("controls.setAttribute(\"aria-label\", \"Slide navigation\")"));
        // The control strip is an aside. An aside is not permitted to take a navigation role, and
        // an accessibility checker reports one as invalid, so the strip takes a name and nothing
        // else: the complementary role an aside already carries is what holds that name.
        assertFalse(
                deck.contains("controls.setAttribute(\"role\""),
                "the control strip must not be given a role its element cannot take");
        assertFalse(deck.contains("role=\"navigation\""));
    }

    /**
     * Counts the register on disk and holds every claim about its size to that count.
     *
     * <p>The deck stated one number and the register held another, which is the kind of drift a
     * reader cannot detect from the slide alone. Counting the rows here rather than restating the
     * number means the next appended finding fails this test until every claim about the register
     * moves with it.</p>
     */
    @Test
    @DisplayName("every claim about the register size equals the number of rows the register holds")
    void everyClaimAboutTheRegisterSizeEqualsTheNumberOfRowsTheRegisterHolds() throws IOException {
        String register = Files.readString(platformRoot.resolve("docs/business-rule-flags.md"));
        Matcher row = Pattern.compile("(?m)^\\| *(\\d+) *\\|").matcher(register);
        int highest = 0;
        int rows = 0;
        while (row.find()) {
            rows++;
            highest = Math.max(highest, Integer.parseInt(row.group(1).trim()));
        }
        assertEquals(rows, highest, "the register is numbered from one with no gap and no repeat");
        assertTrue(rows > 0);

        String spelled = spellOut(rows);
        assertTrue(
                register.contains("All " + rows + " register items"),
                "the register states its own size as " + rows);
        assertTrue(
                deck.contains("<div class=\"kpi-value\">" + rows + "</div>"),
                "the deck metric card states " + rows);
        assertTrue(
                deck.contains(spelled + " flagged source rules carry file and line citations."),
                "the deck table row states " + spelled);

        String platformReadme = Files.readString(platformRoot.resolve("README.md"));
        assertTrue(
                platformReadme.contains(spelled + " ambiguous, inconsistent, or undocumented"),
                "the platform guide states " + spelled);
        String authorizationReadme =
                Files.readString(platformRoot.resolve("services/authorization-service/README.md"));
        assertTrue(
                authorizationReadme.contains("a " + spelled.toLowerCase() + "-item register"),
                "the authorization guide states " + spelled.toLowerCase());
    }

    /**
     * Every typeface weight the deck asks for is a weight the deck paints.
     *
     * <p>The font request names nine family and weight combinations because those are the ones the
     * rule enumerates, so the request itself must not be trimmed. Three of them reached no element,
     * which meant the deck advertised type it never used. Each now has one home.</p>
     */
    @Test
    @DisplayName("the deck paints every typeface weight its font request asks for")
    void theDeckPaintsEveryTypefaceWeightItsFontRequestAsksFor() {
        assertTrue(deck.contains("family=Inter:wght@400;500;600;700"));
        assertTrue(deck.contains("family=Space+Grotesk:wght@500;600;700"));
        assertTrue(deck.contains("family=Fira+Code:wght@400;500"));

        assertTrue(deck.contains("      font-weight: 500;\n      margin: 0.5em 0;"), "Inter 500");
        assertTrue(
                deck.contains("      font-size: 0.8em;\n      font-weight: 600;\n"
                        + "      line-height: 1.2;"),
                "Space Grotesk 600");
        assertTrue(
                deck.contains("      font-size: 2.15em;\n      font-weight: 500;\n"
                        + "      margin-top: 34px;"),
                "Space Grotesk 500");
        assertEquals(3, occurrences(deck, "font-weight: 700;"));
        assertEquals(2, occurrences(deck, "font-weight: 600;"));
    }

    /**
     * The deck reaches three hosts on every open, and the platform guide has to say so.
     *
     * <p>A single authored file is not a self-contained one. The rule that governs the deck forbids
     * a local asset beside it, so the dependency cannot be removed and the honest resolution is to
     * name it: which hosts, what each one serves, and what a reader sees when one is unreachable.
     * The guide also has to say that a typeface outage is silent, because nothing inside the page
     * can detect one.</p>
     */
    @Test
    @DisplayName("the platform guide states the deck's network requirement and its degradation")
    void thePlatformGuideStatesTheDecksNetworkRequirementAndItsDegradation() throws IOException {
        String platformReadme = Files.readString(platformRoot.resolve("README.md"));
        assertTrue(platformReadme.contains("### Opening the executive deck"));
        assertTrue(platformReadme.contains("presentation/executive-summary.html"));
        assertTrue(platformReadme.contains("It needs network access on the machine that opens it"));
        assertTrue(platformReadme.contains("not a\nself-contained one"));
        assertTrue(platformReadme.contains("cdn.jsdelivr.net"));
        assertTrue(platformReadme.contains("fonts.googleapis.com"));
        assertTrue(platformReadme.contains("fonts.gstatic.com"));
        assertTrue(platformReadme.contains("Nothing inside the page can detect a"));
        assertTrue(platformReadme.contains("file://"));
        assertTrue(platformReadme.contains("fixed sixteen-by-nine canvas"));
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

    /**
     * Writes a register size the way the prose writes it, so the count and the words cannot part.
     *
     * <p>The register is numbered from one, so only the sizes a growing register actually reaches
     * are covered. An unrecognised size fails loudly rather than returning something plausible.</p>
     */
    private static String spellOut(int value) {
        List<String> units =
                List.of(
                        "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
                        "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen",
                        "Sixteen", "Seventeen", "Eighteen", "Nineteen");
        List<String> tens =
                List.of(
                        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty",
                        "Ninety");
        if (value < units.size()) {
            return units.get(value);
        }
        if (value < 100) {
            String ten = tens.get(value / 10);
            return value % 10 == 0 ? ten : ten + "-" + units.get(value % 10).toLowerCase();
        }
        throw new IllegalArgumentException("No spelling is defined for a register of " + value);
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