package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Holds the Rule 1 and Rule 2 documentation inventory against the shipped repository.
 *
 * <p>The documents are executable contracts rather than unchecked prose. The tests below close the
 * source inventory, preserve the numbered register, verify relative links and citations, and keep
 * the required Mermaid views present.</p>
 */
class DocumentationContractTest {

    private static final List<String> DOCUMENTS = List.of(
            "decision-log.md",
            "traceability-matrix.md",
            "business-rule-flags.md",
            "architecture-before-after.md",
            "event-flow.md",
            "data-model.md");

    private static final List<String> PROGRAMS = List.of(
            "CBACT01C.cbl", "CBACT02C.cbl", "CBACT03C.cbl", "CBACT04C.cbl",
            "CBCUS01C.cbl", "CBSTM03A.CBL", "CBSTM03B.CBL", "CBTRN01C.cbl",
            "CBTRN02C.cbl", "CBTRN03C.cbl", "COACTUPC.cbl", "COACTVWC.cbl",
            "COADM01C.cbl", "COBIL00C.cbl", "COCRDLIC.cbl", "COCRDSLC.cbl",
            "COCRDUPC.cbl", "COMEN01C.cbl", "CORPT00C.cbl", "COSGN00C.cbl",
            "COTRN00C.cbl", "COTRN01C.cbl", "COTRN02C.cbl", "COUSR00C.cbl",
            "COUSR01C.cbl", "COUSR02C.cbl", "COUSR03C.cbl", "CSUTLDTC.cbl");

    private static final List<String> COPYBOOKS = List.of(
            "COADM02Y.cpy", "COCOM01Y.cpy", "COMEN02Y.cpy", "COSTM01.CPY",
            "COTTL01Y.cpy", "CSDAT01Y.cpy", "CSLKPCDY.cpy", "CSMSG01Y.cpy",
            "CSMSG02Y.cpy", "CSSETATY.cpy", "CSSTRPFY.cpy", "CSUSR01Y.cpy",
            "CSUTLDPY.cpy", "CSUTLDWY.cpy", "CUSTREC.cpy", "CVACT01Y.cpy",
            "CVACT02Y.cpy", "CVACT03Y.cpy", "CVCRD01Y.cpy", "CVCUS01Y.cpy",
            "CVTRA01Y.cpy", "CVTRA02Y.cpy", "CVTRA03Y.cpy", "CVTRA04Y.cpy",
            "CVTRA05Y.cpy", "CVTRA06Y.cpy", "CVTRA07Y.cpy", "UNUSED1Y.cpy");

    private static final Pattern FLAG_ROW =
            Pattern.compile("(?m)^\\|\\s*(\\d+)\\s+[^|]*\\|");
    private static final Pattern LINK = Pattern.compile("\\[[^]]+\\]\\(([^)]+)\\)");
    private static final Pattern CITATION =
            Pattern.compile("(app/[A-Za-z0-9_./-]+):L(\\d+)(?:-L?(\\d+))?");

    @Test
    void allSixRuleDocumentsExistAndEveryRelativeLinkResolves() {
        for (String document : DOCUMENTS) {
            Path file = docsDirectory().resolve(document);
            assertTrue(Files.isRegularFile(file), document + " must exist");
            assertFalse(read(file).isBlank(), document + " must contain its delivered contract");

            Matcher links = LINK.matcher(read(file));
            while (links.find()) {
                String target = links.group(1).split("#", 2)[0];
                if (target.isBlank() || target.contains("://") || target.startsWith("#")) {
                    continue;
                }
                assertTrue(Files.exists(file.getParent().resolve(target).normalize()),
                        document + " links to missing " + links.group(1));
            }
        }
    }

    @Test
    void theDecisionLogUsesOnlyFourColumnTablesAndCarriesEveryRequiredDecision() {
        String decisionLog = read(docsDirectory().resolve("decision-log.md"));
        for (String line : decisionLog.lines().filter(line -> line.startsWith("|")).toList()) {
            assertEquals(5, line.chars().filter(character -> character == '|').count(),
                    "Rule 1 permits exactly four columns: " + line);
        }
        for (int choice = 1; choice <= 8; choice++) {
            assertTrue(decisionLog.contains("**C" + choice + " —"),
                    "platform decision C" + choice + " must be present");
        }
        for (String required : List.of(
                "Parallel reimplementation with equivalence verification",
                "Authorization reads local projections and calls no supporting service",
                "Truncate every monetary result toward zero",
                "Publish `TransactionPosted` schema version 2",
                "Treat the first daily transaction as `504.77`",
                "Treat the posting result as 100 category rows",
                "28 DOWN-versus-HALF_UP differences",
                "Rule 4’s enumerated values")) {
            assertTrue(decisionLog.contains(required), "decision log must name " + required);
        }
    }

    @Test
    void theForwardTraceabilitySectionsNameEverySourceMember() {
        String matrix = read(docsDirectory().resolve("traceability-matrix.md"));
        String programs = section(matrix, "## Forward: COBOL programs", "## Forward: copybooks");
        String copybooks =
                section(matrix, "## Forward: copybooks", "## Forward: Job Control Language members");
        String jobs = section(matrix, "## Forward: Job Control Language members",
                "## Forward: CICS resource definitions");

        assertEquals(28, PROGRAMS.size());
        assertEquals(28, COPYBOOKS.size());
        for (String program : PROGRAMS) {
            assertTrue(programs.contains(program), program + " must be classified");
        }
        for (String copybook : COPYBOOKS) {
            assertTrue(copybooks.contains(copybook), copybook + " must be classified");
        }

        List<String> jobMembers;
        try (Stream<Path> listed = Files.list(repositoryRoot().resolve("app/jcl"))) {
            jobMembers = listed.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list app/jcl", unreadable);
        }
        assertEquals(29, jobMembers.size());
        for (String job : jobMembers) {
            assertTrue(jobs.contains(job), job + " must be classified");
        }

        assertTrue(matrix.contains("12 + 8 + 2 + 6 = 28"));
        assertTrue(matrix.contains("12 + 8 + 1 + 6 + 1 = 28"));
        assertTrue(matrix.contains("11 + 3 + 15 = 29"));
    }

    @Test
    void theMeasuredSourceInventoriesMatchThePublishedCoverageSummary() {
        Path root = repositoryRoot();
        assertEquals(28, sourceFileCount(root.resolve("app/cbl"), ignored -> true));
        assertEquals(28, sourceFileCount(root.resolve("app/cpy"), ignored -> true));
        assertEquals(29, sourceFileCount(root.resolve("app/jcl"), ignored -> true));
        assertEquals(17, sourceFileCount(root.resolve("app/bms"), ignored -> true));
        assertEquals(17, sourceFileCount(root.resolve("app/cpy-bms"),
                name -> !name.equals(".gitkeep")));
        assertEquals(9, sourceFileCount(root.resolve("app/data/ASCII"), ignored -> true));
        assertEquals(12, sourceFileCount(root.resolve("app/data/EBCDIC"),
                name -> !name.equals(".gitkeep")));

        String csd = read(root.resolve("app/csd/CARDDEMO.CSD"));
        assertEquals(8, count(csd, "DEFINE FILE"));
        assertEquals(17, count(csd, "DEFINE MAPSET"));
        assertEquals(18, count(csd, "DEFINE PROGRAM"));
        assertEquals(18, count(csd, "DEFINE TRANSACTION"));
        assertEquals(8, count(csd, "RECOVERY(NONE)"));
        assertEquals(8, count(csd, "JOURNAL(NO)"));
    }

    @Test
    void theBusinessRuleRegisterKeepsExactlyTheTwentySixStableIdentifiers() {
        String flags = read(docsDirectory().resolve("business-rule-flags.md"));
        String coverage = section(flags, "## Register coverage",
                "## The largest item: a named program that does not exist");
        Matcher matcher = FLAG_ROW.matcher(coverage);
        List<Integer> identifiers = new ArrayList<>();
        while (matcher.find()) {
            identifiers.add(Integer.valueOf(matcher.group(1)));
        }
        assertEquals(26, identifiers.size());
        for (int expected = 1; expected <= 26; expected++) {
            assertEquals(expected, identifiers.get(expected - 1).intValue());
        }
        assertTrue(flags.contains("## Resolved rather than flagged"));
        assertTrue(flags.contains("app/cpy/CVACT01Y.cpy:L13-L14"));
        assertTrue(flags.contains("`RECOVERY(NONE) JOURNAL(NO)`"));
    }

    @Test
    void theArchitectureDocumentContainsNamedBeforeAndAfterMermaidViews() {
        String architecture = read(docsDirectory().resolve("architecture-before-after.md"));
        assertTrue(architecture.contains("## Before:"));
        assertTrue(architecture.contains("## After:"));
        assertEquals(2, count(architecture, "```mermaid"));
        assertEquals(2, count(architecture, "**Legend**"));
        assertTrue(architecture.contains("8 files, 17 mapsets, 18 programs, 18 transactions"));
        assertTrue(architecture.contains("account.state-changed"));
        assertTrue(architecture.contains("card.updated"));
        assertTrue(architecture.contains("no direct edge between them"));
    }

    @Test
    void theEventFlowCoversEveryRuntimeBusinessEventAndDeliveryGuarantee() {
        String flow = read(docsDirectory().resolve("event-flow.md"));
        for (String eventType : List.of(
                "TransactionAuthorized", "TransactionDeclined", "TransactionPosted",
                "FraudFlagged", "FraudCleared", "AccountStateChanged",
                "CustomerContextChanged", "CardUpdated")) {
            assertTrue(flow.contains("`" + eventType + "`"), eventType + " must be documented");
        }
        for (String topic : List.of(
                "transaction.authorized", "transaction.declined", "transaction.posted",
                "fraud.assessed", "account.state-changed", "customer.context-changed",
                "card.updated",
                "carddemo.dead-letter")) {
            assertTrue(flow.contains("`" + topic + "`"), topic + " must be documented");
        }
        assertEquals(3, count(flow, "```mermaid"));
        assertTrue(flow.contains("at least once"));
        assertTrue(flow.contains("Manual acknowledgement"));
        assertTrue(flow.contains("processed_event"));
        assertTrue(flow.contains("one local transaction"));
    }

    @Test
    void theDataModelContainsOneNamedEntityRelationshipViewPerService() {
        String model = read(docsDirectory().resolve("data-model.md"));
        assertEquals(6, count(model, "```mermaid"));
        assertEquals(6, count(model, "erDiagram"));
        assertEquals(6, count(model, "**Legend**"));
        for (String service : List.of(
                "Authorization database", "Ledger database", "Fraud database",
                "Notification database", "Account database", "Card database")) {
            assertTrue(model.contains("### " + service), service + " section must exist");
        }
        assertTrue(model.contains("no relationship between `ACCOUNT` and `CUSTOMER`"));
        assertTrue(model.contains("`VARCHAR(10)`"));
        assertTrue(model.contains("`DATE`"));
        assertTrue(model.contains("980 telephone area codes"));
    }

    @Test
    void everyRepositoryCitationResolvesWithinTheNamedSourceFile() {
        for (String document : DOCUMENTS) {
            Matcher citations = CITATION.matcher(read(docsDirectory().resolve(document)));
            while (citations.find()) {
                Path source = repositoryRoot().resolve(citations.group(1)).normalize();
                assertTrue(Files.isRegularFile(source),
                        document + " cites missing " + citations.group(1));
                int start = Integer.parseInt(citations.group(2));
                int end = citations.group(3) == null
                        ? start : Integer.parseInt(citations.group(3));
                long lineCount;
                try (Stream<String> lines = Files.lines(source, StandardCharsets.UTF_8)) {
                    lineCount = lines.count();
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot read " + source, unreadable);
                }
                assertTrue(start > 0 && end >= start && end <= lineCount,
                        document + " cites " + citations.group() + " outside 1-" + lineCount);
            }
        }
    }

    @Test
    void theSixDocumentsContainNoPlaceholderOrBannedProseToken() {
        Pattern prohibited = Pattern.compile(
                "(?i)\\b(TBD|TODO|FIXME|leverage|utilize|facilitate|synergy|holistic|paradigm)\\b");
        for (String document : DOCUMENTS) {
            String text = read(docsDirectory().resolve(document));
            assertFalse(prohibited.matcher(text).find(),
                    document + " must contain no placeholder or banned prose token");
            assertFalse(text.contains("app/cbl/*.cbl"),
                    document + " must not use a case-sensitive glob that drops source members");
        }
    }

    private static String section(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end);
        assertTrue(from >= 0 && to > from, "section bounds must exist: " + start + " to " + end);
        return text.substring(from, to);
    }

    private static int count(String text, String token) {
        int occurrences = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            occurrences++;
            offset += token.length();
        }
        return occurrences;
    }

    private static long sourceFileCount(Path directory,
            java.util.function.Predicate<String> include) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(include)
                    .count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    private static Path docsDirectory() {
        return platformDirectory().resolve("docs");
    }

    private static Path platformDirectory() {
        return repositoryRoot().resolve("card-platform");
    }

    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}