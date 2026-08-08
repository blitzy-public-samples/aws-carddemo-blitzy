package com.carddemo.equivalence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the CardDemo sources so an expected value can be checked against the program it came from.
 *
 * <h2>Why the expectation files need this</h2>
 *
 * <p>Many checked-in expectations state a fact about the COBOL rather than a value a service
 * computes: which verb writes a record, which field a branch adds into, what a Picture clause
 * declares, whether a paragraph is a stub. Those rows are the ones most likely to rot, because
 * nothing about them changes when the Java changes, and nothing about them changes when the COBOL
 * changes either — unless a test reads both. Asserting them against a constant written beside them
 * would only prove a file agrees with itself.</p>
 *
 * <p>Every method here therefore reads {@code app/} from disk. Nothing is written: the engagement
 * forbids modifying the source, and these are the same bytes the migration was derived from.</p>
 *
 * <h2>Where a paragraph ends</h2>
 *
 * <p>A paragraph runs to the next paragraph label, not to the next {@code EXIT.}. Several paragraphs
 * of {@code CBTRN02C} carry no {@code EXIT.} at all — {@code 2700-A-CREATE-TCATBAL-REC} is one — so
 * a reader that stops at the first {@code EXIT.} silently continues into the following paragraph and
 * answers questions about the wrong code. {@link #paragraph} takes whichever boundary comes
 * first.</p>
 *
 * <p>Files are read once and cached, because a single test class can ask dozens of questions of the
 * same program.</p>
 */
final class CobolSourceEvidence {

    /** Whole files by repository-relative path, read on first use. */
    private static final Map<String, String> FILES = new ConcurrentHashMap<>();

    /** Shape of a paragraph label, such as {@code 2700-A-CREATE-TCATBAL-REC.}. */
    private static final Pattern PARAGRAPH_LABEL = Pattern.compile("\\d{4}(-[A-Z0-9]+)*\\.");

    private CobolSourceEvidence() {
    }

    /**
     * Reads one file of the source repository.
     *
     * @param repositoryPath path below the repository root, such as {@code app/cbl/CBTRN02C.cbl}
     * @return the whole file
     * @throws IllegalStateException when no ancestor of the fixture directory holds the file
     */
    static String file(String repositoryPath) {
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        return FILES.computeIfAbsent(repositoryPath, path -> {
            for (Path candidate = CardDemoFixtureLoader.fixtureDirectory();
                    candidate != null; candidate = candidate.getParent()) {
                Path file = candidate.resolve(path);
                if (Files.isRegularFile(file)) {
                    try {
                        return Files.readString(file, StandardCharsets.ISO_8859_1);
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException("cannot read " + file, unreadable);
                    }
                }
            }
            throw new IllegalStateException("no ancestor of '"
                    + CardDemoFixtureLoader.fixtureDirectory() + "' holds '" + path + "'");
        });
    }

    /**
     * Returns one file as normalised lines: comments removed, runs of spaces collapsed.
     *
     * <p>COBOL is written in fixed columns, so a statement carries whatever padding put it there.
     * Collapsing lets an assertion name a statement the way a reader would write it.</p>
     *
     * @param repositoryPath path below the repository root
     * @return the lines, in file order
     */
    static List<String> lines(String repositoryPath) {
        return file(repositoryPath).lines()
                .map(line -> line.replaceAll("\\s+", " ").strip())
                .filter(line -> !line.startsWith("*") && !line.isEmpty())
                .toList();
    }

    /**
     * Returns one paragraph as normalised lines, excluding its own label.
     *
     * @param repositoryPath path below the repository root
     * @param label          the paragraph label, without its full stop
     * @return the lines of the paragraph
     * @throws IllegalStateException when the file holds no such paragraph
     */
    static List<String> paragraph(String repositoryPath, String label) {
        List<String> normalised = lines(repositoryPath);
        int start = normalised.indexOf(label + ".");
        if (start < 0) {
            throw new IllegalStateException(repositoryPath + " holds no paragraph " + label);
        }
        int end = normalised.size();
        for (int index = start + 1; index < normalised.size(); index++) {
            String line = normalised.get(index);
            if (PARAGRAPH_LABEL.matcher(line).matches()) {
                end = index;
                break;
            }
            if ("EXIT.".equals(line)) {
                end = index + 1;
                break;
            }
        }
        return List.copyOf(normalised.subList(start + 1, end));
    }

    /**
     * Reports whether a paragraph carries a fragment on any of its lines.
     *
     * @param repositoryPath path below the repository root
     * @param label          the paragraph label
     * @param fragment       the text to look for, written the way a reader would
     * @return {@code true} when a line holds it
     */
    static boolean paragraphContains(String repositoryPath, String label, String fragment) {
        return paragraph(repositoryPath, label).stream().anyMatch(line -> line.contains(fragment));
    }

    /**
     * Returns the number of times a token appears in one file.
     *
     * @param repositoryPath path below the repository root
     * @param token          the text to count
     * @return the count, zero when the token is absent
     */
    static long occurrences(String repositoryPath, String token) {
        return file(repositoryPath).split(Pattern.quote(token), -1).length - 1L;
    }

    /**
     * Returns the Picture clause a file declares one field with.
     *
     * @param repositoryPath path below the repository root
     * @param fieldName      the field to look up
     * @return the clause as written, such as {@code 9(04)}
     * @throws IllegalStateException when the file declares no such field
     */
    static String pictureOf(String repositoryPath, String fieldName) {
        Matcher matcher = Pattern.compile(Pattern.quote(fieldName) + "\\s+PIC\\s+([^\\s.]+)")
                .matcher(file(repositoryPath));
        if (!matcher.find()) {
            throw new IllegalStateException(
                    repositoryPath + " declares no Picture clause for " + fieldName);
        }
        return matcher.group(1);
    }

    /**
     * Returns the verb a paragraph puts its record on a dataset with.
     *
     * @param repositoryPath path below the repository root
     * @param label          the paragraph label
     * @return {@code WRITE}, {@code REWRITE}, or {@code absent}
     */
    static String writeVerbOf(String repositoryPath, String label) {
        List<String> body = paragraph(repositoryPath, label);
        if (body.stream().anyMatch(line -> line.startsWith("REWRITE "))) {
            return "REWRITE";
        }
        return body.stream().anyMatch(line -> line.startsWith("WRITE ")) ? "WRITE" : "absent";
    }

    /**
     * Returns the file statuses a paragraph accepts, as its condition lists them.
     *
     * @param repositoryPath path below the repository root
     * @param label          the paragraph label
     * @return the statuses, comma separated, without their quotes
     * @throws IllegalStateException when the paragraph tests no file status
     */
    static String acceptedStatusesOf(String repositoryPath, String label) {
        for (String line : paragraph(repositoryPath, label)) {
            Matcher matcher = Pattern.compile("IF [A-Z0-9-]+-STATUS = ('\\d{2}'(?: OR '\\d{2}')*)")
                    .matcher(line);
            if (matcher.find()) {
                return matcher.group(1).replace("'", "").replace(" OR ", ",");
            }
        }
        throw new IllegalStateException(
                repositoryPath + " paragraph " + label + " tests no file status");
    }

    /**
     * Reports whether a paragraph does nothing but continue, which is what a stub looks like.
     *
     * @param repositoryPath path below the repository root
     * @param label          the paragraph label
     * @return {@code true} when the paragraph carries no statement other than a continue or exit
     */
    static boolean isUnimplementedStub(String repositoryPath, String label) {
        return paragraph(repositoryPath, label).stream()
                .allMatch(line -> line.equals("CONTINUE") || line.equals("CONTINUE.")
                        || line.equals("EXIT.") || line.equals("EXIT"));
    }

    /**
     * Returns one value of a dataset definition's {@code KEYS} parameter.
     *
     * @param jobPath  the job below the repository root
     * @param position 1 for the key length, 2 for the key offset
     * @return the digits as the job writes them
     */
    static String datasetKeyParameter(String jobPath, int position) {
        Matcher matcher = Pattern.compile("KEYS\\((\\d+) +(\\d+)\\)").matcher(file(jobPath));
        if (!matcher.find()) {
            throw new IllegalStateException(jobPath + " declares no KEYS parameter");
        }
        return matcher.group(position);
    }

    /**
     * Returns the first value of a dataset definition's {@code RECORDSIZE} parameter.
     *
     * @param jobPath the job below the repository root
     * @return the digits as the job writes them
     */
    static String datasetRecordSize(String jobPath) {
        Matcher matcher = Pattern.compile("RECORDSIZE\\((\\d+) +(\\d+)\\)").matcher(file(jobPath));
        if (!matcher.find()) {
            throw new IllegalStateException(jobPath + " declares no RECORDSIZE parameter");
        }
        return matcher.group(1);
    }

    /**
     * Returns the abend code a program moves into its abend reporting field.
     *
     * <p>The batch programs write the Language Environment field {@code ABCODE}, while the
     * reporting record of {@code app/cpy/CSMSG02Y.cpy} names its own field {@code ABEND-CODE}.
     * Both spellings are accepted so one helper serves either program.</p>
     *
     * @param repositoryPath path below the repository root
     * @return the digits as the program writes them
     * @throws IllegalStateException when the program moves nothing into either field
     */
    static String abendCode(String repositoryPath) {
        Matcher matcher = Pattern.compile("MOVE (\\d+) TO (?:ABCODE|ABEND-CODE)")
                .matcher(file(repositoryPath));
        if (!matcher.find()) {
            throw new IllegalStateException(
                    repositoryPath + " moves nothing into ABCODE or ABEND-CODE");
        }
        return matcher.group(1);
    }

    /**
     * Reports whether a program reaches its abend routine from one paragraph.
     *
     * @param repositoryPath path below the repository root
     * @param label          the paragraph label
     * @return {@code true} when the paragraph performs the abend routine
     */
    static boolean abendsFrom(String repositoryPath, String label) {
        return paragraphContains(repositoryPath, label, "PERFORM 9999-ABEND-PROGRAM");
    }

    /**
     * Reports whether a token appears anywhere in a file, ignoring letter case.
     *
     * @param repositoryPath path below the repository root
     * @param token          the text to look for
     * @return {@code true} when the file holds it
     */
    static boolean contains(String repositoryPath, String token) {
        return file(repositoryPath).toLowerCase(Locale.ROOT)
                .contains(token.toLowerCase(Locale.ROOT));
    }

    /**
     * Reports whether a statement appears on any line of a file, written the way a reader would.
     *
     * <p>Unlike {@link #contains(String, String)} this reads the normalised lines, so a caller
     * writes single spaces regardless of how many columns the fixed-format source put between the
     * words. A statement such as {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} carries two spaces in
     * the source and is invisible to a raw search.</p>
     *
     * @param repositoryPath path below the repository root
     * @param statement      the statement, with single spaces between its words
     * @return {@code true} when a normalised line holds it
     */
    static boolean containsStatement(String repositoryPath, String statement) {
        String wanted = statement.replaceAll("\\s+", " ").strip();
        return lines(repositoryPath).stream().anyMatch(line -> line.contains(wanted));
    }
}
