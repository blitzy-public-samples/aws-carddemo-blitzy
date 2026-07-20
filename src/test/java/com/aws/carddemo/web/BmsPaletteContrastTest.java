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
 * Static (no Spring context) verification of review finding #53 &mdash; the accessible, centralized
 * BMS colour palette.
 *
 * <p>Three properties are asserted directly against the template sources:</p>
 * <ol>
 *   <li>The two BMS foreground colours that previously failed contrast are defined once in the
 *       shared fragment {@code templates/fragments/bms-palette.html} and both meet the WCAG 2.1 AA
 *       normal-text ratio (&ge; 4.5:1) against the black terminal background.</li>
 *   <li>No screen template hardcodes the inaccessible legacy hexes {@code #0000CD} / {@code #CD0000}
 *       any more (they are referenced only through {@code var(--bms-*)}).</li>
 *   <li>Every screen template includes the centralized palette fragment, so the custom properties
 *       always resolve.</li>
 * </ol>
 *
 * <p>The WCAG contrast maths mirror the {@code relative luminance} definition (sRGB linearization
 * with the 0.03928 knee, then the 0.2126/0.7152/0.0722 weighting) and the
 * {@code (L1 + 0.05) / (L2 + 0.05)} ratio with the darker colour (pure black) as {@code L2}.</p>
 */
class BmsPaletteContrastTest {

    /** Template source root (tests run with the module base directory as the working directory). */
    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates");

    /** The single source of truth for the terminal palette. */
    private static final Path PALETTE = TEMPLATES.resolve("fragments").resolve("bms-palette.html");

    /** WCAG 2.1 AA minimum contrast ratio for normal-size text. */
    private static final double AA_NORMAL_TEXT = 4.5;

    /** Matches a {@code --name: #RRGGBB} custom-property declaration. */
    private static final Pattern VAR = Pattern.compile("--%s:\\s*#([0-9A-Fa-f]{6})");

    private static double linearize(int channel) {
        double s = channel / 255.0;
        return (s <= 0.03928) ? (s / 12.92) : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static double relativeLuminance(String rrggbb) {
        int r = Integer.parseInt(rrggbb.substring(0, 2), 16);
        int g = Integer.parseInt(rrggbb.substring(2, 4), 16);
        int b = Integer.parseInt(rrggbb.substring(4, 6), 16);
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b);
    }

    private static double contrastOnBlack(String rrggbb) {
        return (relativeLuminance(rrggbb) + 0.05) / (0.0 + 0.05);
    }

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
    void centralizedBlueAndRedMeetWcagAaOnBlack() {
        String palette = read(PALETTE);
        String blue = paletteValue(palette, "bms-blue");
        String red = paletteValue(palette, "bms-red");

        assertThat(contrastOnBlack(blue))
                .as("--bms-blue #%s on #000000 must meet WCAG 2.1 AA normal-text contrast", blue)
                .isGreaterThanOrEqualTo(AA_NORMAL_TEXT);
        assertThat(contrastOnBlack(red))
                .as("--bms-red #%s on #000000 must meet WCAG 2.1 AA normal-text contrast", red)
                .isGreaterThanOrEqualTo(AA_NORMAL_TEXT);
    }

    @Test
    void noScreenTemplateHardcodesInaccessibleLegacyHex() {
        // The fragment documents the "was #0000CD -> ..." mapping in comments; every other
        // template must reference the palette only through var(--bms-*).
        List<Path> offenders = templateFiles().stream()
                .filter(p -> !p.getFileName().toString().equals("bms-palette.html"))
                .filter(p -> {
                    String c = read(p);
                    return c.contains("#0000CD") || c.contains("#CD0000");
                })
                .toList();

        assertThat(offenders)
                .as("no screen template may hardcode the inaccessible legacy BMS blue/red")
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
