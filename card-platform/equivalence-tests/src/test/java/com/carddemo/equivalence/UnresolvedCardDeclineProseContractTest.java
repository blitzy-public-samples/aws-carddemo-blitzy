package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionDeclined;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the prose that describes reject reason {@code 0100} to the contract version the code
 * publishes it under.
 *
 * <p>The outcome this reason produces was reversed twice while the platform was being built. One
 * revision published nothing for it, a later one keyed it on an account a caller had merely declared,
 * and the delivered platform decides it and publishes it keyed on the transaction identifier it
 * minted. Migration {@code V24__unresolved_card_decline_is_decided.sql} states the settled position
 * and {@code docs/decision-log.md} records the two withdrawals.
 *
 * <p>Each reversal left explanatory prose behind describing the position before it. A review found
 * six files still telling a maintainer that the call is refused before a decision, allocates no
 * identifier and publishes nothing, while the code allocated an identifier, wrote a decision row and
 * published an event. A maintainer reading any one of them would have concluded that a live event
 * stream did not exist.
 *
 * <p>Two properties keep that from recurring. Every file that describes this outcome names the
 * contract version {@link TransactionDeclined#UNRESOLVED_ACCOUNT_SCHEMA_VERSION} declares, so a
 * reversal that moved the version leaves a failing test rather than stale prose. And no shipped text
 * outside the append-only history carries any of the withdrawn claims.
 *
 * <p>The append-only history is exempt by path and the exemption is asserted rather than assumed:
 * {@link #APPEND_ONLY_HISTORY} names the four files that record what was withdrawn, and
 * {@link #theAppendOnlyHistoryStillRecordsWhatWasWithdrawn()} fails if any of them stops carrying a
 * withdrawn claim, because a migration and a superseded log row exist to preserve exactly that text.
 */
@DisplayName("Reject reason 0100 is described by the version the code publishes it under")
class UnresolvedCardDeclineProseContractTest {

    /**
     * Every file that explains the reason-{@code 0100} outcome to a reader.
     *
     * <p>The first two are the sites the acceptance review named. The remaining five carried the same
     * withdrawn claim, or already carried the settled one, and were found by searching for the claim
     * rather than by being reported. {@code ../README.md} is the repository-root guide, which is the
     * one pre-existing file this engagement edits.
     */
    private static final List<String> OUTCOME_PROSE = List.of(
            "services/authorization-service/src/main/java/com/carddemo/authorization/domain/DeclineRule.java",
            "services/ledger-posting-service/README.md",
            "README.md",
            "services/authorization-service/src/main/resources/openapi.yaml",
            "services/authorization-service/README.md",
            "docs/business-rule-flags.md",
            "../README.md");

    /**
     * The files that exist to preserve a withdrawn claim, and are therefore excluded from the sweep.
     *
     * <p>A Flyway migration is applied history and is never edited once released. The two decision-log
     * rows are marked SUPERSEDED and quote the decision they replaced, which is what makes a reversal
     * auditable.
     */
    private static final List<String> APPEND_ONLY_HISTORY = List.of(
            "services/authorization-service/src/main/resources/db/migration/V19__unresolved_decline_names_its_account.sql",
            "services/authorization-service/src/main/resources/db/migration/V20__unresolved_card_attempt_withdrawn.sql",
            "docs/decision-log.md");

    /**
     * The claims the reversals left behind, each of which contradicts the delivered behaviour.
     *
     * <p>Every one of these was present in shipped text at the acceptance gate. They are held as
     * literals rather than as a pattern so that the failure message names the sentence a reader would
     * have believed.
     */
    private static final List<String> WITHDRAWN_CLAIMS = List.of(
            "refuses such a call rather than deciding it",
            "no producer publishes it",
            "refuses the call instead of deciding it",
            "the refusal reason 0100 now produces",
            "draws no identifier, writes no row and publishes nothing",
            "a pre-decision refusal carries no decision",
            "records no decision and publishes no event",
            "is refused before a decision, so it writes nothing and publishes nothing",
            "the outcome it recorded is no longer decided");

    /** Formats a shipped file reads for the sweep. Binary and generated output are not text. */
    private static final List<String> SWEPT_FORMATS =
            List.of(".java", ".md", ".sql", ".json", ".yaml", ".yml", ".html", ".xml", ".sh");

    /** Directories the sweep does not enter: build output and version-control metadata. */
    private static final List<String> UNSWEPT_DIRECTORIES = List.of("target", ".git", "node_modules");

    /**
     * This detector's own source, which holds every claim as a literal and would otherwise report
     * itself nine times.
     *
     * <p>It is the only file excluded on the grounds of being a detector, and it is named rather than
     * matched by a pattern, so no other test can acquire the exclusion by being renamed.
     */
    private static final String DETECTOR_SOURCE = "equivalence-tests/src/test/java/com/carddemo/"
            + "equivalence/UnresolvedCardDeclineProseContractTest.java";

    private static Path platformRoot;

    private static Map<String, String> outcomeProse;

    @BeforeAll
    static void readShippedText() {
        platformRoot = locatePlatformRoot();
        outcomeProse = new LinkedHashMap<>();
        for (String relative : OUTCOME_PROSE) {
            Path file = platformRoot.resolve(relative).normalize();
            assertTrue(Files.isRegularFile(file), "Missing prose target " + relative);
            outcomeProse.put(relative, flattened(read(file)));
        }
    }

    @Test
    @DisplayName("The published contract version is 2, and the event it builds names no account")
    void theContractVersionAndTheEventItBuildsAgree() {
        assertEquals(2, TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION,
                "The prose of this platform names version 2 in words. A change here is a change to"
                        + " every file of OUTCOME_PROSE, and this test exists to make that visible.");
        assertEquals(DeclineReason.INVALID_CARD_NUMBER,
                TransactionDeclined.UNRESOLVED_ACCOUNT_REASON,
                "Reason 0100 is the whole set of declines the unresolved-account version covers");

        TransactionDeclined declined = TransactionDeclined.ofUnresolvedAccount(
                "0000001000000000", new BigDecimal("125.00"), "************7065");

        assertEquals(TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION, declined.schemaVersion(),
                "The one builder of this outcome publishes the version the prose names");
        assertNull(declined.accountId(),
                "The prose says the decision names no account, so the event must carry none");
        assertEquals("0100", declined.declineReasonCode().code(),
                "The prose names reject code 0100 for this outcome");
        assertEquals("0000001000000000", declined.aggregateId(),
                "The prose says the event is keyed on the transaction identifier, not an account");
    }

    @Test
    @DisplayName("Every file explaining the outcome names the version the code publishes")
    void everyOutcomeProseNamesThePublishedVersion() {
        int version = TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION;
        String schemaDocument = "transaction-declined-v" + version;
        String spelledOut = "version " + version;
        String constant = "UNRESOLVED_ACCOUNT_SCHEMA_VERSION";

        outcomeProse.forEach((relative, text) -> {
            boolean names = text.contains(schemaDocument)
                    || text.contains(spelledOut)
                    || text.contains(constant);
            assertTrue(names, relative + " explains reject reason 0100 without naming the contract"
                    + " version the code publishes it under. Name " + schemaDocument + ", "
                    + spelledOut + " or " + constant + ", so a reversal of the version cannot leave"
                    + " this file describing a stream that no longer exists.");
        });
    }

    @Test
    @DisplayName("Every file explaining the outcome says it is decided rather than refused")
    void everyOutcomeProseSaysTheCallIsDecided() {
        outcomeProse.forEach((relative, text) -> {
            boolean decided = text.contains("decided all the same")
                    || text.contains("decides such a call all the same")
                    || text.contains("decides the call")
                    || text.contains("is decided rather than refused")
                    || text.contains("The platform decides it too")
                    || text.contains("publishes it as current traffic")
                    || text.contains("its decision and its event name none");
            assertTrue(decided, relative + " explains reject reason 0100 without stating that the"
                    + " call is decided. The delivered platform allocates a transaction identifier,"
                    + " writes one decision row whose account_id is null and publishes one event,"
                    + " all in one local transaction.");
        });
    }

    @Test
    @DisplayName("No shipped text outside the append-only history carries a withdrawn claim")
    void noShippedTextCarriesAWithdrawnClaim() {
        List<String> stranded = new ArrayList<>();
        for (Path file : sweptFiles()) {
            String relative = platformRoot.relativize(file).toString().replace('\\', '/');
            if (APPEND_ONLY_HISTORY.contains(relative) || DETECTOR_SOURCE.equals(relative)) {
                continue;
            }
            String text = flattened(read(file));
            for (String claim : WITHDRAWN_CLAIMS) {
                if (text.contains(claim)) {
                    stranded.add(relative + " carries \"" + claim + "\"");
                }
            }
        }
        assertTrue(stranded.isEmpty(), "Shipped text describes an outcome this platform no longer"
                + " produces. Reject reason 0100 is decided and published under"
                + " transaction-declined-v" + TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION
                + ". Stranded prose: " + stranded);
    }

    @Test
    @DisplayName("The append-only history still records what was withdrawn")
    void theAppendOnlyHistoryStillRecordsWhatWasWithdrawn() {
        for (String relative : APPEND_ONLY_HISTORY) {
            Path file = platformRoot.resolve(relative);
            assertTrue(Files.isRegularFile(file),
                    "The withdrawal history names " + relative + ", which does not exist");
            String text = flattened(read(file));
            boolean records = WITHDRAWN_CLAIMS.stream().anyMatch(text::contains)
                    || text.contains("SUPERSEDED");
            assertTrue(records, relative + " is exempt from the sweep because it exists to preserve a"
                    + " withdrawn claim, and it no longer carries one. Either restore the record or"
                    + " remove the path from APPEND_ONLY_HISTORY, so the exemption never covers a"
                    + " file that has become ordinary prose.");
        }
        Path settled = platformRoot.resolve("services/authorization-service/src/main/resources/db/"
                + "migration/V24__unresolved_card_decline_is_decided.sql");
        assertTrue(Files.isRegularFile(settled),
                "The settled position is stated by V24, which must exist for the history to close");
        String migration = flattened(read(settled));
        assertTrue(migration.contains("V19") && migration.contains("V20"),
                "V24 names the two migrations it supersedes, so the reversal chain is readable"
                        + " forwards from either of them");
        assertFalse(migration.contains("refuses such a call"),
                "The settled migration must state the settled behaviour and nothing else");
    }

    /**
     * Every shipped text of the platform, plus the repository-root guide.
     *
     * <p>The root guide is swept because it describes this outcome and sits outside the platform
     * directory, which is where the acceptance review's search had stopped. It carried the withdrawn
     * claim in a seventh place nobody had reported.
     */
    private static List<Path> sweptFiles() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(platformRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(UnresolvedCardDeclineProseContractTest::isSweptFormat)
                    .filter(UnresolvedCardDeclineProseContractTest::isOutsideUnsweptDirectory)
                    .sorted()
                    .forEach(files::add);
        } catch (IOException problem) {
            throw new UncheckedIOException("Cannot walk " + platformRoot, problem);
        }
        Path rootGuide = platformRoot.resolve("../README.md").normalize();
        assertTrue(Files.isRegularFile(rootGuide),
                "The repository-root guide must exist at " + rootGuide);
        files.add(rootGuide);
        return files;
    }

    private static boolean isSweptFormat(Path file) {
        String name = file.getFileName().toString();
        return SWEPT_FORMATS.stream().anyMatch(name::endsWith);
    }

    private static boolean isOutsideUnsweptDirectory(Path file) {
        for (Path element : platformRoot.relativize(file)) {
            if (UNSWEPT_DIRECTORIES.contains(element.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Drops the comment marker that opens each continuation line and collapses every run of
     * whitespace, so a claim wrapped across lines is still one string.
     *
     * <p>This matters because the withdrawn claim that survived longest was invisible to a plain
     * search: {@code V19}'s header wraps {@code records no decision and publishes no event} over two
     * comment lines, and a javadoc block wraps a sentence over three. A detector that reads raw bytes
     * finds the claim only where the author happened to fit it on one line.
     */
    private static String flattened(String text) {
        return text.replaceAll("(?m)^[ \\t]*(?:--|\\*|//|#)[ \\t]*", " ").replaceAll("\\s+", " ");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException problem) {
            throw new UncheckedIOException("Cannot read " + file, problem);
        }
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
}
