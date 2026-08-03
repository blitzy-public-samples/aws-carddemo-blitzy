package com.carddemo.account.domain.validation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the literal lists of {@code app/cpy/CSLKPCDY.cpy} straight from disk.
 *
 * <p>This class is the independent oracle for the three reference-data validators. It reads the
 * copybook and reports what the copybook lists, so a test can compare a production set against the
 * source of that set rather than against itself. No method here reads
 * {@code com.carddemo.cobol.reference}, so a drift in either the copybook or a production class
 * shows up as a failure.
 *
 * <p>The copybook declares five condition names with {@code VALUES} clauses. Their declaration
 * lines were read first-hand: {@code VALID-PHONE-AREA-CODE} at {@code app/cpy/CSLKPCDY.cpy:L30},
 * {@code VALID-GENERAL-PURP-CODE} at {@code :L521}, {@code VALID-EASY-RECOG-AREA-CODE} at
 * {@code :L931}, {@code VALID-US-STATE-CODE} at {@code :L1013} and
 * {@code VALID-US-STATE-ZIP-CD2-COMBO} at {@code :L1073}.
 *
 * <p>Two parsing facts govern the reader, both measured across all 1,319 lines of the copybook.
 * No line carries content in the first six columns, and the first non-blank character of every
 * comment line is an asterisk, so a comment is any line whose stripped form opens with one. No
 * comment line holds a quoted literal, so skipping comments discards no value. Continuation lines
 * are indented with tab characters, which is why no column-based reader is used.
 *
 * <p>Every operation opens the copybook for reading. No operation writes to, copies or moves any
 * file under {@code app/}.
 *
 */
final class CslkpcdyCopybookOracle {

    /** The directory name that marks the repository root. */
    private static final String MARKER_DIRECTORY = "app";

    /** The directory holding the copybooks. */
    private static final String COPYBOOK_DIRECTORY = "cpy";

    /** The copybook this oracle parses. */
    private static final String COPYBOOK_FILE = "CSLKPCDY.cpy";

    /** Matches the declaration line of one condition name with a {@code VALUES} clause. */
    private static final Pattern CONDITION_NAME =
            Pattern.compile("\\b88\\s+([A-Z0-9-]+)\\s+VALUES");

    /** Matches one quoted literal. */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']*)'");

    /** The condition name listing the wider telephone area-code band. */
    static final String PHONE_AREA_CODE_CONDITION = "VALID-PHONE-AREA-CODE";

    /** The condition name the account update program tests. */
    static final String GENERAL_PURPOSE_CONDITION = "VALID-GENERAL-PURP-CODE";

    /** The condition name listing the easily recognisable codes. */
    static final String EASILY_RECOGNISABLE_CONDITION = "VALID-EASY-RECOG-AREA-CODE";

    /** The condition name listing the state and territory codes. */
    static final String STATE_CODE_CONDITION = "VALID-US-STATE-CODE";

    /** The condition name listing the state and postal-prefix combinations. */
    static final String STATE_ZIP_CONDITION = "VALID-US-STATE-ZIP-CD2-COMBO";

    /** The five condition names, in declaration order. */
    private static final List<String> CONDITIONS = List.of(PHONE_AREA_CODE_CONDITION,
            GENERAL_PURPOSE_CONDITION, EASILY_RECOGNISABLE_CONDITION, STATE_CODE_CONDITION,
            STATE_ZIP_CONDITION);

    /** The parsed lists, keyed by condition name, each in copybook declaration order. */
    private static final Map<String, Set<String>> LISTS = parseCopybook();

    /** This class holds static members only. */
    private CslkpcdyCopybookOracle() {
    }

    /**
     * The telephone area codes listed at {@code app/cpy/CSLKPCDY.cpy:L30}.
     *
     * @return the codes, in declaration order
     */
    static Set<String> phoneAreaCodes() {
        return literalsOf(PHONE_AREA_CODE_CONDITION);
    }

    /**
     * The telephone area codes listed at {@code app/cpy/CSLKPCDY.cpy:L521}.
     *
     * @return the codes, in declaration order
     */
    static Set<String> generalPurposeCodes() {
        return literalsOf(GENERAL_PURPOSE_CONDITION);
    }

    /**
     * The telephone area codes listed at {@code app/cpy/CSLKPCDY.cpy:L931}.
     *
     * @return the codes, in declaration order
     */
    static Set<String> easilyRecognisableAreaCodes() {
        return literalsOf(EASILY_RECOGNISABLE_CONDITION);
    }

    /**
     * The state and territory codes listed at {@code app/cpy/CSLKPCDY.cpy:L1013}.
     *
     * @return the codes, in declaration order
     */
    static Set<String> stateCodes() {
        return literalsOf(STATE_CODE_CONDITION);
    }

    /**
     * The state and postal-prefix combinations listed at {@code app/cpy/CSLKPCDY.cpy:L1073}.
     *
     * @return the combinations, in declaration order
     */
    static Set<String> stateZipCombinations() {
        return literalsOf(STATE_ZIP_CONDITION);
    }

    /**
     * Counts every quoted literal the five lists hold, counting a repeated value once per list.
     *
     * @return the total literal count
     */
    static int literalCount() {
        int total = 0;
        for (String condition : CONDITIONS) {
            total += literalsOf(condition).size();
        }
        return total;
    }

    /**
     * The five condition names, in the order the copybook declares them.
     *
     * @return the names
     */
    static List<String> conditionNames() {
        return CONDITIONS;
    }

    /**
     * Reports the literals of one condition name.
     *
     * @param condition the condition name
     * @return the literals, in declaration order
     * @throws IllegalStateException if the copybook declares no such condition name
     */
    private static Set<String> literalsOf(String condition) {
        Set<String> literals = LISTS.get(condition);
        if (literals == null) {
            throw new IllegalStateException(
                    "%s declares no condition named %s. The five names it declared when this "
                            .formatted(COPYBOOK_FILE, condition)
                            + "oracle was written are " + CONDITIONS + ".");
        }
        return literals;
    }

    /**
     * Reads the copybook and groups its quoted literals under the condition name that precedes
     * them.
     *
     * @return the lists, keyed by condition name
     * @throws IllegalStateException if a declared condition name holds no literal
     */
    private static Map<String, Set<String>> parseCopybook() {
        Map<String, Set<String>> lists = new LinkedHashMap<>();
        String current = null;

        for (String line : readCopybook()) {
            if (line.strip().startsWith("*")) {
                continue;
            }

            Matcher declaration = CONDITION_NAME.matcher(line);
            if (declaration.find()) {
                current = declaration.group(1);
                lists.computeIfAbsent(current, name -> new LinkedHashSet<>());
            }
            if (current == null) {
                continue;
            }

            Matcher literal = QUOTED_LITERAL.matcher(line);
            while (literal.find()) {
                lists.get(current).add(literal.group(1));
            }
        }

        for (Map.Entry<String, Set<String>> list : lists.entrySet()) {
            if (list.getValue().isEmpty()) {
                throw new IllegalStateException("%s declares %s with no literal."
                        .formatted(COPYBOOK_FILE, list.getKey()));
            }
            list.setValue(Collections.unmodifiableSet(list.getValue()));
        }
        return Collections.unmodifiableMap(lists);
    }

    /**
     * Reads every line of the copybook.
     *
     * @return the lines, in file order
     */
    private static List<String> readCopybook() {
        Path copybook = copybookUnder(repositoryRoot());
        try {
            return Files.readAllLines(copybook, StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading %s failed.".formatted(copybook), failure);
        }
    }

    /**
     * Walks upward from the working directory until a candidate holds the copybook.
     *
     * @return the repository root
     * @throws IllegalStateException if no ancestor holds the copybook
     */
    private static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(copybookUnder(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                ("Walked upward from %s to the filesystem root without finding '%s/%s/%s'. Run "
                        + "the tests from inside the repository.")
                        .formatted(start, MARKER_DIRECTORY, COPYBOOK_DIRECTORY, COPYBOOK_FILE));
    }

    /**
     * Builds the copybook path a candidate directory would hold if the candidate were the root.
     *
     * @param candidate directory to test
     * @return the path {@code candidate/app/cpy/CSLKPCDY.cpy}, which need not exist
     */
    private static Path copybookUnder(Path candidate) {
        return candidate.resolve(MARKER_DIRECTORY)
                .resolve(COPYBOOK_DIRECTORY)
                .resolve(COPYBOOK_FILE);
    }
}
