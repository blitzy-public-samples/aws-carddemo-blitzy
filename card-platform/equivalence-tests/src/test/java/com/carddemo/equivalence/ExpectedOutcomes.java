package com.carddemo.equivalence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads one checked-in expected-output file and records which of its rows an assertion consumed.
 *
 * <p>Every file under {@code src/test/resources/expected/} carries the same eight columns, declared
 * in {@link #COLUMNS}, above a single comment line that names the evaluation model and the
 * consuming test. A row states one expected value together with the Picture clause it is held in
 * and the source locator it was derived from, so a reader can check the expectation against
 * {@code app/cbl/} without running anything.</p>
 *
 * <h2>Why consumption is tracked</h2>
 *
 * <p>An expected-output file that no test reads is worse than no file at all: it looks like
 * evidence, and it can drift away from both the source and the code without anything failing. This
 * class therefore remembers every row an assertion looked up. Each consuming class ends the test
 * that walks its rows by asserting {@link #unconsumedRows()} is empty, which turns "the evidence is
 * checked" into a build-enforced property rather than a claim. The assertion closes the walking
 * test rather than sitting in an {@code @AfterAll} method, so it holds however the runner orders
 * the class. Adding a row to a CSV without asserting on it fails the build, and so does deleting an
 * assertion.</p>
 *
 * <h2>Two column shapes that trip up a naive reader</h2>
 *
 * <p>Values may contain commas and are then quoted in the manner of RFC 4180 — a merchant name and
 * a two-locator provenance both do this — so the parser handles quoting rather than splitting on
 * every comma. And {@code record_seq} is not always a number: alongside the 1-based ordinal of a
 * feed record it also carries labels such as {@code SITE-02}, {@code RATE-RESOLUTION} and
 * {@code SYN-109-REWRITE-FAIL}. It is therefore held as text.</p>
 *
 * <p>Instances are not thread safe. Each consuming test class holds one in a static field and
 * drives it from a single thread, which is how the equivalence suite runs.</p>
 */
final class ExpectedOutcomes {

    /** Columns every expected-output file in this module carries, in order. */
    static final List<String> COLUMNS = List.of(
            "evaluation_model",
            "derived_from",
            "record_seq",
            "entity_key",
            "expected_field",
            "expected_value",
            "pic_clause",
            "source_locator");

    /** Directory on the classpath holding every expected-output file. */
    static final String RESOURCE_DIRECTORY = "/expected/";

    /** Value a boolean-valued expectation carries when it holds. */
    static final String YES = "yes";

    /** Value a boolean-valued expectation carries when it does not hold. */
    static final String NO = "no";

    /** Value {@code pic_clause} carries when the expectation has no Picture clause. */
    static final String NO_PIC_CLAUSE = "NONE";

    /** Sequence a row carries when it states a fixture-level rather than a per-record fact. */
    static final String FIXTURE_LEVEL_SEQUENCE = "0";

    /** Name of the file this instance read, without its directory. */
    private final String fileName;

    /** The comment line above the header, with its leading marker removed. */
    private final String preamble;

    /** Every row, in file order. */
    private final List<Row> rows;

    /** Rows by their unique key, for lookup. */
    private final Map<Key, Row> byKey;

    /** Keys an assertion has looked up. */
    private final Set<Key> consumed = new LinkedHashSet<>();

    private ExpectedOutcomes(String fileName, String preamble, List<Row> rows,
            Map<Key, Row> byKey) {
        this.fileName = fileName;
        this.preamble = preamble;
        this.rows = List.copyOf(rows);
        this.byKey = Map.copyOf(byKey);
    }

    /**
     * One row of an expected-output file.
     *
     * @param evaluationModel the model the row belongs to, such as
     *                        {@code B_CUMULATIVE_DECLINES_SKIP}
     * @param derivedFrom     the fixture or program the expectation was derived from
     * @param recordSequence  the 1-based feed ordinal, {@value #FIXTURE_LEVEL_SEQUENCE} for a
     *                        fixture-level fact, or a label such as {@code SITE-02}
     * @param entityKey       the account, composite key, paragraph or subject the row describes
     * @param expectedField   the name of the expectation
     * @param expectedValue   the value the system under test must produce
     * @param picClause       the Picture clause the value is held in, or {@value #NO_PIC_CLAUSE}
     * @param sourceLocator   file and line range the expectation was read from
     */
    record Row(String evaluationModel,
            String derivedFrom,
            String recordSequence,
            String entityKey,
            String expectedField,
            String expectedValue,
            String picClause,
            String sourceLocator) {

        /**
         * Returns the key that identifies this row within its file.
         *
         * @return the key
         */
        Key key() {
            return new Key(recordSequence, entityKey, expectedField);
        }

        /**
         * Reports whether this row states a fixture-level fact rather than a per-record one.
         *
         * @return {@code true} when the sequence is {@value #FIXTURE_LEVEL_SEQUENCE}
         */
        boolean isFixtureLevel() {
            return FIXTURE_LEVEL_SEQUENCE.equals(recordSequence);
        }
    }

    /**
     * The three columns that identify a row uniquely inside one file.
     *
     * @param recordSequence the {@code record_seq} column
     * @param entityKey      the {@code entity_key} column
     * @param expectedField  the {@code expected_field} column
     */
    record Key(String recordSequence, String entityKey, String expectedField) {

        @Override
        public String toString() {
            return recordSequence + "|" + entityKey + "|" + expectedField;
        }
    }

    /**
     * Reads one expected-output file from the classpath.
     *
     * @param fileName the file name inside {@value #RESOURCE_DIRECTORY}, such as
     *                 {@code posting-summary.csv}
     * @return the parsed file, with nothing yet consumed
     * @throws IllegalStateException when the resource is absent, carries no comment line, declares
     *                               columns other than {@link #COLUMNS}, holds a row with the wrong
     *                               column count, or repeats a key
     */
    static ExpectedOutcomes load(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        String resource = RESOURCE_DIRECTORY + fileName;
        try (InputStream source = ExpectedOutcomes.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            List<String> lines = List.of(
                    new String(source.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
            return parse(fileName, resource, lines);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + resource, unreadable);
        }
    }

    /** Parses the lines of one expected-output file. */
    private static ExpectedOutcomes parse(String fileName, String resource, List<String> lines) {
        if (lines.size() < 3 || !lines.get(0).startsWith("#")) {
            throw new IllegalStateException(
                    resource + " must hold a comment line, a header and at least one row");
        }
        List<String> header = splitRow(lines.get(1));
        if (!COLUMNS.equals(header)) {
            throw new IllegalStateException(
                    resource + " must declare " + COLUMNS + ", found " + header);
        }

        List<Row> rows = new ArrayList<>();
        Map<Key, Row> byKey = new LinkedHashMap<>();
        for (int index = 2; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            List<String> columns = splitRow(line);
            if (columns.size() != COLUMNS.size()) {
                throw new IllegalStateException(resource + " line " + (index + 1) + " holds "
                        + columns.size() + " columns, not " + COLUMNS.size());
            }
            Row row = new Row(columns.get(0), columns.get(1), columns.get(2), columns.get(3),
                    columns.get(4), columns.get(5), columns.get(6), columns.get(7));
            if (byKey.putIfAbsent(row.key(), row) != null) {
                throw new IllegalStateException(resource + " repeats key " + row.key());
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException(resource + " holds no rows");
        }
        return new ExpectedOutcomes(fileName, lines.get(0).substring(1).trim(), rows, byKey);
    }

    /**
     * Splits one line into its columns, honouring the quoting a value with a comma carries.
     *
     * <p>A field opens and closes with a double quote, and a doubled quote inside one stands for a
     * single quote character, which is how RFC 4180 escapes it. Everything else is taken
     * literally, including leading and trailing spaces, because a fixture value may hold them.</p>
     *
     * @param line the line to split
     * @return the fields, in order
     */
    private static List<String> splitRow(String line) {
        List<String> fields = new ArrayList<>(COLUMNS.size());
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int position = 0; position < line.length(); position++) {
            char character = line.charAt(position);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (position + 1 < line.length() && line.charAt(position + 1) == '"') {
                    field.append('"');
                    position++;
                } else {
                    quoted = false;
                }
            } else if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return List.copyOf(fields);
    }

    /**
     * Returns the file name this instance read.
     *
     * @return the name, without its directory
     */
    String fileName() {
        return fileName;
    }

    /**
     * Returns the comment line above the header.
     *
     * @return the preamble, without its leading marker
     */
    String preamble() {
        return preamble;
    }

    /**
     * Returns every row, in file order. Reading rows this way does not consume them.
     *
     * @return the rows
     */
    List<Row> rows() {
        return rows;
    }

    /**
     * Returns the rows carrying one entity key, in file order, without consuming them.
     *
     * @param entityKey the {@code entity_key} to select
     * @return the matching rows, possibly empty
     */
    List<Row> rowsFor(String entityKey) {
        return rows.stream().filter(row -> row.entityKey().equals(entityKey)).toList();
    }

    /**
     * Returns the entity keys the file carries, in first-appearance order.
     *
     * @return the keys
     */
    Set<String> entityKeys() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(rows.stream().map(Row::entityKey).toList()));
    }

    /**
     * Returns the one value carrying an entity key and field, whatever its sequence, and marks its
     * row consumed.
     *
     * <p>Most files put fixture-level facts on sequence {@value #FIXTURE_LEVEL_SEQUENCE}, but
     * {@code posting-summary.csv} numbers every row of the file in turn, so matching on the sequence
     * would make a caller carry a number that means nothing to it. This overload ignores the
     * sequence and instead requires the pair to be unambiguous, which it is in nine of the fourteen
     * files. Where a pair repeats across sequences — a per-record field of the same name on many
     * records — the three-argument form is the only correct one and this method says so.</p>
     *
     * @param entityKey     the {@code entity_key} to select
     * @param expectedField the {@code expected_field} to select
     * @return the {@code expected_value} column
     * @throws IllegalStateException when the file holds no such row, or holds more than one
     */
    String value(String entityKey, String expectedField) {
        return row(entityKey, expectedField).expectedValue();
    }

    /**
     * Returns the one row carrying an entity key and field, whatever its sequence, and marks it
     * consumed.
     *
     * @param entityKey     the {@code entity_key} to select
     * @param expectedField the {@code expected_field} to select
     * @return the row
     * @throws IllegalStateException when the file holds no such row, or holds more than one
     */
    Row row(String entityKey, String expectedField) {
        List<Row> matches = rows.stream()
                .filter(candidate -> candidate.entityKey().equals(entityKey)
                        && candidate.expectedField().equals(expectedField))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException(RESOURCE_DIRECTORY + fileName + " holds no row for "
                    + entityKey + "|" + expectedField);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(RESOURCE_DIRECTORY + fileName + " holds "
                    + matches.size() + " rows for " + entityKey + "|" + expectedField
                    + ", on sequences " + matches.stream().map(Row::recordSequence).toList()
                    + ". Name the sequence to select one of them.");
        }
        Row match = matches.get(0);
        consumed.add(match.key());
        return match;
    }

    /**
     * Returns one value and marks its row consumed.
     *
     * @param recordSequence the {@code record_seq} to select
     * @param entityKey      the {@code entity_key} to select
     * @param expectedField  the {@code expected_field} to select
     * @return the {@code expected_value} column
     * @throws IllegalStateException when the file holds no such row
     */
    String value(String recordSequence, String entityKey, String expectedField) {
        return row(recordSequence, entityKey, expectedField).expectedValue();
    }

    /**
     * Returns one row and marks it consumed.
     *
     * @param recordSequence the {@code record_seq} to select
     * @param entityKey      the {@code entity_key} to select
     * @param expectedField  the {@code expected_field} to select
     * @return the row
     * @throws IllegalStateException when the file holds no such row
     */
    Row row(String recordSequence, String entityKey, String expectedField) {
        Key key = new Key(recordSequence, entityKey, expectedField);
        Row row = byKey.get(key);
        if (row == null) {
            throw new IllegalStateException(
                    RESOURCE_DIRECTORY + fileName + " holds no row for " + key);
        }
        consumed.add(key);
        return row;
    }

    /**
     * Returns one fixture-level value as a whole number and marks its row consumed.
     *
     * @param entityKey     the {@code entity_key} to select
     * @param expectedField the {@code expected_field} to select
     * @return the value
     */
    long count(String entityKey, String expectedField) {
        return Long.parseLong(value(entityKey, expectedField).trim());
    }

    /**
     * Returns one value as a whole number and marks its row consumed.
     *
     * @param recordSequence the {@code record_seq} to select
     * @param entityKey      the {@code entity_key} to select
     * @param expectedField  the {@code expected_field} to select
     * @return the value
     */
    long count(String recordSequence, String entityKey, String expectedField) {
        return Long.parseLong(value(recordSequence, entityKey, expectedField).trim());
    }

    /**
     * Returns one fixture-level value as a fixed-point amount and marks its row consumed.
     *
     * @param entityKey     the {@code entity_key} to select
     * @param expectedField the {@code expected_field} to select
     * @return the value
     */
    BigDecimal money(String entityKey, String expectedField) {
        return new BigDecimal(value(entityKey, expectedField).trim());
    }

    /**
     * Returns one value as a fixed-point amount and marks its row consumed.
     *
     * @param recordSequence the {@code record_seq} to select
     * @param entityKey      the {@code entity_key} to select
     * @param expectedField  the {@code expected_field} to select
     * @return the value
     */
    BigDecimal money(String recordSequence, String entityKey, String expectedField) {
        return new BigDecimal(value(recordSequence, entityKey, expectedField).trim());
    }

    /**
     * Returns one fixture-level value as a flag and marks its row consumed.
     *
     * @param entityKey     the {@code entity_key} to select
     * @param expectedField the {@code expected_field} to select
     * @return {@code true} for {@value #YES}, {@code false} for {@value #NO}
     */
    boolean flag(String entityKey, String expectedField) {
        return asFlag(row(entityKey, expectedField));
    }

    /**
     * Returns one value as a flag and marks its row consumed.
     *
     * @param recordSequence the {@code record_seq} to select
     * @param entityKey      the {@code entity_key} to select
     * @param expectedField  the {@code expected_field} to select
     * @return {@code true} for {@value #YES}, {@code false} for {@value #NO}
     * @throws IllegalStateException when the value is neither
     */
    boolean flag(String recordSequence, String entityKey, String expectedField) {
        return asFlag(row(recordSequence, entityKey, expectedField));
    }

    /** Reads one row's value as a flag. */
    private boolean asFlag(Row row) {
        String value = row.expectedValue().trim().toLowerCase(Locale.ROOT);
        if (YES.equals(value)) {
            return true;
        }
        if (NO.equals(value)) {
            return false;
        }
        throw new IllegalStateException(RESOURCE_DIRECTORY + fileName + " row " + row.key()
                + " holds " + value + ", which is neither " + YES + " nor " + NO);
    }

    /**
     * Returns the rows no assertion has looked up.
     *
     * <p>A consuming test class asserts this is empty at the end of the test that walks its rows. A
     * row that appears here is checked-in evidence nothing verifies.</p>
     *
     * @return the unconsumed rows, in file order
     */
    List<Row> unconsumedRows() {
        return rows.stream().filter(row -> !consumed.contains(row.key())).toList();
    }

    /**
     * Returns a message naming every unconsumed row, for an assertion failure.
     *
     * @return the message, listing at most the first twenty keys
     */
    String unconsumedDescription() {
        List<Row> outstanding = unconsumedRows();
        List<String> keys = outstanding.stream().limit(20).map(row -> row.key().toString()).toList();
        return RESOURCE_DIRECTORY + fileName + " holds " + outstanding.size()
                + " rows no assertion reads: " + keys;
    }
}
