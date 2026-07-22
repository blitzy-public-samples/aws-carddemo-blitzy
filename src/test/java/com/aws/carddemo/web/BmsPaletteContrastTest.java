package com.aws.carddemo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static (no Spring context) verification of the centralized BMS 3270 colour palette
 * (QA finding <b>P5-12</b>, superseding review finding #53).
 *
 * <p>There is no Figma for this migration, so the BMS colour contract is the authoritative
 * design intent (AAP &sect;0.3.4, "colors rendered as styles" one-for-one). Under the AAP
 * precedence rule, WCAG contrast heuristics never override that design contract; an earlier
 * revision brightened BLUE/RED to {@code #6E6EFF}/{@code #FF0000} for WCAG 2.1 AA, but that
 * silently changed the observable colour contract, so P5-12 restores the literal BMS palette.
 * Consequently this test asserts <em>exact palette parity</em> rather than a contrast floor (the
 * class name is retained for continuity and the decision-log cross-reference).</p>
 *
 * <p>Three properties are asserted directly against the template sources:</p>
 * <ol>
 *   <li>The shared fragment {@code templates/fragments/bms-palette.html} defines every BMS
 *       foreground colour with its exact literal 3270 hex (BLUE {@code #0000CD}, RED
 *       {@code #CD0000}, GREEN {@code #00CD00}, TURQUOISE {@code #00CDCD}, YELLOW {@code #CCCC00},
 *       NEUTRAL {@code #FFFFFF}) plus the black terminal background {@code #000000}.</li>
 *   <li>No screen template hardcodes the centralized BLUE/RED hexes; they are referenced only
 *       through {@code var(--bms-*)} so the fragment stays the single source of truth.</li>
 *   <li>Every screen template includes the centralized palette fragment, so the custom properties
 *       always resolve.</li>
 * </ol>
 */
class BmsPaletteContrastTest {

    /** Template source root (tests run with the module base directory as the working directory). */
    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates");

    /** The single source of truth for the terminal palette. */
    private static final Path PALETTE = TEMPLATES.resolve("fragments").resolve("bms-palette.html");

    /** Matches a {@code --name: #RRGGBB} custom-property declaration. */
    private static final Pattern VAR = Pattern.compile("--%s:\\s*#([0-9A-Fa-f]{6})");

    private static String paletteValue(String content, String name) {
        Matcher matcher = Pattern.compile(String.format(VAR.pattern(), name)).matcher(content);
        assertThat(matcher.find()).as("palette fragment must define --%s", name).isTrue();
        return matcher.group(1);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static List<Path> templateFiles() {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + TEMPLATES, e);
        }
    }

    @Test
    void paletteDefinesLiteralBmsColours() {
        // QA P5-12: the fragment must render the EXACT literal BMS 3270 palette (parity), because
        // the BMS colour contract is the authoritative design intent and WCAG contrast never
        // overrides it (AAP precedence; decision-log). Assert every foreground colour and the
        // black terminal background verbatim, case-insensitively.
        String palette = read(PALETTE);
        assertThat(paletteValue(palette, "bms-blue"))
                .as("--bms-blue must be the literal BMS blue").isEqualToIgnoringCase("0000CD");
        assertThat(paletteValue(palette, "bms-red"))
                .as("--bms-red must be the literal BMS red").isEqualToIgnoringCase("CD0000");
        assertThat(paletteValue(palette, "bms-green"))
                .as("--bms-green must be the literal BMS green").isEqualToIgnoringCase("00CD00");
        assertThat(paletteValue(palette, "bms-turquoise"))
                .as("--bms-turquoise must be the literal BMS turquoise").isEqualToIgnoringCase("00CDCD");
        assertThat(paletteValue(palette, "bms-yellow"))
                .as("--bms-yellow must be the literal BMS yellow").isEqualToIgnoringCase("CCCC00");
        assertThat(paletteValue(palette, "bms-neutral"))
                .as("--bms-neutral must be the literal BMS neutral").isEqualToIgnoringCase("FFFFFF");
        assertThat(paletteValue(palette, "bms-bg"))
                .as("--bms-bg must be the black terminal background").isEqualToIgnoringCase("000000");
    }

    @Test
    void noScreenTemplateHardcodesCentralizedBlueOrRed() {
        // Single-source-of-truth guard: every screen template must reference the centralized
        // BLUE/RED only through var(--bms-*), never as a raw hex, so a future palette change stays
        // a one-line edit in the fragment.
        List<Path> offenders = templateFiles().stream()
                .filter(p -> !p.getFileName().toString().equals("bms-palette.html"))
                .filter(p -> {
                    String c = read(p);
                    return c.contains("#0000CD") || c.contains("#CD0000");
                })
                .toList();

        assertThat(offenders)
                .as("no screen template may hardcode the centralized BMS blue/red; use var(--bms-*)")
                .isEmpty();
    }

    @Test
    void everyScreenTemplateIncludesTheCentralizedPalette() {
        List<Path> screens = templateFiles().stream()
                .filter(p -> !p.getFileName().toString().equals("bms-palette.html"))
                .toList();

        assertThat(screens).as("screen templates must exist").isNotEmpty();
        for (Path screen : screens) {
            assertThat(read(screen))
                    .as("%s must include the centralized palette fragment", screen.getFileName())
                    .contains("fragments/bms-palette :: palette");
        }
    }
}
