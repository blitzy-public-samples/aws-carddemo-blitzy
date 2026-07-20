package com.aws.carddemo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BMS-position parity guard for the COUSR03 (Delete User, transaction {@code CU03}) screen.
 *
 * <p>Origin: {@code legacy/bms/COUSR03.bms}. BMS {@code POS=(row,col)} coordinates are
 * <strong>1-based</strong> (row 1, column 1 is the top-left character cell of the 24x80 grid),
 * whereas the Thymeleaf absolute-positioning grid used by the migrated screens is
 * <strong>0-based</strong> ({@code left: 0ch} is the first column). Finding #53/#55 identified that
 * COUSR03 alone failed to subtract one when translating column coordinates, so every named and
 * static field rendered one character cell to the right of its authoritative BMS position.</p>
 *
 * <p>This test encodes the corrected invariant directly against the legacy source of truth so the
 * off-by-one cannot silently regress: for every distinct template column {@code N} (0-based), the
 * column {@code N + 1} must exist as a real BMS {@code POS} column (1-based). Before the fix the
 * leftmost labels sat at {@code left: 1ch}; {@code 1 + 1 = 2} is not a BMS column, so the invariant
 * (and this test) would fail. After the fix they sit at {@code left: 0ch}; {@code 0 + 1 = 1} is the
 * BMS origin column, so parity holds.</p>
 */
@DisplayName("COUSR03 template column positions match legacy BMS coordinates (0-based == BMS - 1)")
class CousrThreeBmsPositionParityTest {

    private static final Path BMS_SOURCE = Path.of("legacy", "bms", "COUSR03.bms");
    private static final Path TEMPLATE =
            Path.of("src", "main", "resources", "templates", "COUSR03.html");

    /** BMS {@code POS=(row,col)} - capture the 1-based column. */
    private static final Pattern BMS_POS = Pattern.compile("POS=\\(\\d+,(\\d+)\\)");
    /** Inline absolute field position {@code left: <int>ch} - capture the 0-based column. */
    private static final Pattern TEMPLATE_LEFT = Pattern.compile("left:\\s*(\\d+)ch");

    @Test
    @DisplayName("every template column N corresponds to a real BMS column N+1 (no off-by-one)")
    void everyTemplateColumnIsOneLeftOfItsBmsSource() throws IOException {
        SortedSet<Integer> bmsColumns = distinctInts(read(BMS_SOURCE), BMS_POS);
        SortedSet<Integer> templateColumns = distinctInts(read(TEMPLATE), TEMPLATE_LEFT);

        assertThat(bmsColumns)
                .as("legacy BMS must contribute 1-based POS columns")
                .isNotEmpty();
        assertThat(templateColumns)
                .as("migrated COUSR03 template must position fields with left: <col>ch")
                .isNotEmpty();

        // Core invariant: 0-based template column N maps onto 1-based BMS column N+1.
        for (Integer col : templateColumns) {
            assertThat(bmsColumns)
                    .as(
                            "template field at left:%dch must align to BMS column %d "
                                    + "(0-based grid == BMS 1-based - 1)",
                            col, col + 1)
                    .contains(col + 1);
        }
    }

    @Test
    @DisplayName("leftmost field is flush at column 0 (BMS origin column 1 minus one)")
    void leftmostFieldIsAtColumnZero() throws IOException {
        SortedSet<Integer> templateColumns = distinctInts(read(TEMPLATE), TEMPLATE_LEFT);

        // Regression guard: before the fix the leftmost label sat at left: 1ch. The BMS origin
        // column is 1, so the corrected 0-based leftmost column must be exactly 0.
        assertThat(templateColumns.first())
                .as("leftmost COUSR03 field must render at left: 0ch after the off-by-one fix")
                .isZero();
    }

    @Test
    @DisplayName("per-column field counts do not exceed the legacy BMS field counts")
    void perColumnCountsRemainConsistentWithBms() throws IOException {
        Map<Integer, Integer> bmsCounts = countInts(read(BMS_SOURCE), BMS_POS);
        Map<Integer, Integer> templateCounts = countInts(read(TEMPLATE), TEMPLATE_LEFT);

        // Structural sanity: the shift must not have dropped, duplicated, or invented fields.
        // Every 0-based template column N carries at most as many fields as legacy column N+1.
        templateCounts.forEach(
                (col, count) ->
                        assertThat(bmsCounts.getOrDefault(col + 1, 0))
                                .as(
                                        "template column %d hosts %d field(s); legacy BMS column %d"
                                                + " must host at least that many",
                                        col, count, col + 1)
                                .isGreaterThanOrEqualTo(count));
    }

    private static String read(Path path) throws IOException {
        assertThat(Files.exists(path)).as("expected source file to exist: %s", path).isTrue();
        return Files.readString(path);
    }

    private static SortedSet<Integer> distinctInts(String text, Pattern pattern) {
        SortedSet<Integer> values = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(Integer.parseInt(matcher.group(1)));
        }
        return values;
    }

    private static Map<Integer, Integer> countInts(String text, Pattern pattern) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            counts.merge(Integer.parseInt(matcher.group(1)), 1, Integer::sum);
        }
        return counts;
    }
}
