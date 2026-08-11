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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
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

    /** The five services that register a listener, and the sixth that registers none. */
    private static final List<String> SERVICES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /** Number words the topology prose uses, so a changed count fails rather than reads wrong. */
    private static final Map<Integer, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry(3, "three"),
            Map.entry(4, "four"),
            Map.entry(5, "five"),
            Map.entry(6, "six"),
            Map.entry(7, "seven"),
            Map.entry(8, "eight"),
            Map.entry(9, "nine"),
            Map.entry(10, "ten"),
            Map.entry(11, "eleven"),
            Map.entry(12, "twelve"),
            Map.entry(13, "thirteen"),
            Map.entry(14, "fourteen"),
            Map.entry(15, "fifteen"),
            Map.entry(16, "sixteen"),
            Map.entry(17, "seventeen"),
            Map.entry(18, "eighteen"),
            Map.entry(19, "nineteen"),
            Map.entry(20, "twenty"),
            Map.entry(21, "twenty-one"),
            Map.entry(22, "twenty-two"),
            Map.entry(23, "twenty-three"));

    /** A listener declaration, anchored so a Javadoc mention of the annotation is not one. */
    private static final Pattern LISTENER_ANNOTATION =
            Pattern.compile("(?m)^\\s*@KafkaListener\\(");

    /**
     * A created topic expression that appends the dead-letter suffix to a source topic.
     *
     * <p>Read from the Compose provisioning program, which is one of the two places the inventory
     * is declared and the one {@code BrokerTopicProvisioningContractTest} proves the other agrees
     * with. One or more dollars, because that program writes {@code $$} wherever the expansion
     * belongs to the container's shell rather than to Compose.
     */
    private static final Pattern DEAD_LETTER_TWIN = Pattern.compile(
            "\\$+\\{TOPIC_[A-Z_]+}\\$+\\{TOPIC_DEAD_LETTER_SUFFIX}");

    private static final Pattern FLAG_ROW =
            Pattern.compile("(?m)^\\|\\s*(\\d+)\\s+[^|]*\\|");

    /** The topic property leaf one listener subscribes to, with any default stripped. */
    private static final Pattern LISTENER_TOPIC_PROPERTY = Pattern.compile(
            "topics = \"\\$\\{carddemo\\.kafka\\.topics\\.([a-z-]+)(?::[^}]*)?}\"");

    /** Characters either side of a denial phrase that count as naming the same topic. */
    private static final int DENIAL_WINDOW = 240;

    /** Lines of an expected-output file that carry no data: its comment line and its header. */
    private static final int PREAMBLE_AND_HEADER_LINES = 2;

    /** The consuming classes an expected-output file names on its comment line. */
    private static final Pattern CONSUMING_TESTS = Pattern.compile(
            "Consuming tests?:? +([A-Za-z0-9_]+(?: *(?:,|and) *[A-Za-z0-9_]+)*)");

    /** A test class name as an inventory row writes it. */
    private static final Pattern TEST_CLASS_NAME = Pattern.compile("[A-Za-z0-9_]+Test\\b");

    /**
     * Arguments that make git name the delivered set rather than the tracked subset of it.
     *
     * <p>Two paths sit at the repository root and are named individually, because the engagement
     * delivers them and no directory argument reaches them. The root {@code README.md} is the one
     * pre-existing document this work updates, and {@code .gitattributes} declares the line endings
     * that document already had. A review found both absent from the matrix's inventory, which is
     * how a document claiming closure over the whole tree closed over less than it.
     *
     * <p>The four remaining root files are deliberately not named. {@code CODE_OF_CONDUCT.md},
     * {@code CONTRIBUTING.md}, {@code LICENSE} and {@code NOTICE} are untouched since the baseline
     * commit, so a row for any of them would claim an operation this engagement did not perform.
     */
    private static final List<String> DELIVERED_SET_ARGUMENTS = List.of(
            "--cached", "--others", "--exclude-standard", "card-platform", ".github",
            "README.md", ".gitattributes");

    /** The command the matrix must name, written the way a reader would run it. */
    private static final String DELIVERED_SET_COMMAND =
            "git ls-files --cached --others --exclude-standard card-platform .github README.md"
                    + " .gitattributes";

    /** The provenance cell of a delivered file that cites nothing and says no more than that. */
    private static final String PLAIN_ABSENCE = "None cited in the file";

    /** Opening fence of a Mermaid block. */
    private static final String MERMAID_FENCE = "```mermaid";

    /** Closing fence of any block. */
    private static final String FENCE = "```";

    /** Heading that opens a figure legend. */
    private static final String LEGEND_HEADING = "**Legend**";

    /** A figure caption, as every Rule 2 document in this platform writes one. */
    private static final Pattern FIGURE_CAPTION =
            Pattern.compile("\\*\\*Figure (\\d+) \\u2014 (.+?)\\*\\*");

    /** How far above a block a caption may sit, allowing a paragraph of introduction between. */
    private static final int CAPTION_SEARCH_LINES = 10;

    /** How far below a block a legend may sit, allowing blank lines only. */
    private static final int LEGEND_SEARCH_LINES = 3;

    /** Shortest caption title that says something about the figure. */
    private static final int MINIMUM_TITLE_LENGTH = 25;

    /** A backward row: a leading cell holding one repository-relative path in backticks. */
    private static final Pattern BACKWARD_ROW =
            Pattern.compile("(?m)^\\| `([^`]+)` \\|");

    /** One backward row, captured as its target path and its Source provenance cell. */
    private static final Pattern BACKWARD_PROVENANCE_ROW =
            Pattern.compile("(?m)^\\| `([^`]+)` \\| (.*?) \\| [^|]+ \\|\\s*$");

    /** A reference to one source member, as a delivered file writes it. */
    private static final Pattern SOURCE_MEMBER_REFERENCE = Pattern.compile(
            "app/(?:cbl|cpy|cpy-bms|jcl|csd|bms|data/ASCII|data/EBCDIC|proc|ctl|catlg)"
                    + "/[A-Za-z0-9_.\\-]+");

    /** Matches one {@code CREATE INDEX} statement of a migration and captures the index name. */
    private static final Pattern MIGRATED_CREATE_INDEX = Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /** Matches one {@code DROP INDEX} statement of a migration and captures the index name. */
    private static final Pattern MIGRATED_DROP_INDEX = Pattern.compile(
            "DROP\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+EXISTS\\s+)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches one {@code CREATE INDEX} statement and captures its name and the table it indexes.
     *
     * <p>The table is needed because an index also goes away with the table it sits on, and a
     * migration that drops a table writes no {@code DROP INDEX} for the indexes it takes with it.
     */
    private static final Pattern MIGRATED_CREATE_INDEX_ON = Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /** Matches one {@code DROP TABLE} statement of a migration and captures the table name. */
    private static final Pattern MIGRATED_DROP_TABLE = Pattern.compile(
            "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /** Matches the version a migration file name opens with. */
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * Every file this class has read, by path.
     *
     * <p>The checks here read the same trees repeatedly: one nested assertion walks the six services
     * and reads every Java source under each, and several do it in turn, so a source was read once per
     * check rather than once per run. The content cannot change while the tests run — nothing here
     * writes a file — so the second read of a path answers what the first did.
     *
     * <p>A plain {@link HashMap} rather than a concurrent one, because this class declares no parallel
     * execution and JUnit runs it on one thread. If that ever changes, this is the field to revisit.
     */
    private static final Map<Path, String> FILE_CONTENT = new HashMap<>();

    /**
     * Line count of each cited source member, remembered across locators.
     *
     * <p>The shipped tree cites more than eleven thousand locators over a few dozen members, so
     * counting a member's lines once rather than once per locator is what keeps that scan quick.
     */
    private static final Map<Path, Long> SOURCE_LINE_COUNTS = new HashMap<>();

    /**
     * The Java sources under each service's main tree, by service directory name.
     *
     * <p>{@link Files#walk} was called on every question asked about a service's sources. The tree
     * does not change during a run, so it is walked once per service.
     */
    private static final Map<String, List<Path>> MAIN_SOURCES = new HashMap<>();

    /** A closure tally: the right-aligned count cell of a group row or of the total row. */
    private static final Pattern CLOSURE_TALLY =
            Pattern.compile("(?m)^\\|[^|]+\\|\\s*\\**(\\d+)\\**\\s*\\|\\s*$");

    /** Selects the count of findings the register heading states. */
    private static final Pattern DECLARED_REGISTER_SIZE =
            Pattern.compile("## Register coverage\\s*\\n\\s*All (\\d+) register items");

    /** The identifier the first register finding carries. */
    private static final int FIRST_REGISTER_IDENTIFIER = 1;

    /**
     * The number of register identifiers other documents already cite by number.
     *
     * <p>The register may grow past this, and findings above it are as binding as those below.
     * Shrinking below it would break a citation, so this is a floor and not a ceiling.</p>
     */
    private static final int SMALLEST_STABLE_REGISTER = 26;
    private static final Pattern LINK = Pattern.compile("\\[[^]]+\\]\\(([^)]+)\\)");
    private static final Pattern CITATION =
            Pattern.compile("(app/[A-Za-z0-9_./-]+):L(\\d+)(?:-L?(\\d+))?");

    /**
     * A locator whose range is cut off at the end of a line.
     *
     * <p>{@link #CITATION} reads one line at a time, so a locator wrapped after its dash matches
     * nothing and would leave the range unchecked. The shape is reported rather than tolerated: a
     * locator belongs on one line, wherever wrapping the sentence around it takes.
     */
    private static final Pattern DANGLING_CITATION =
            Pattern.compile("app/[A-Za-z0-9_./-]+:L\\d+-[ \\t]*$");

    /** File name suffixes the shipped-locator scan reads. */
    private static final List<String> SHIPPED_TEXT_SUFFIXES = List.of(
            ".java", ".sql", ".json", ".yaml", ".yml", ".md", ".sh", ".html", ".xml", ".example",
            ".properties", ".txt", ".csv");

    /** Shipped files the same scan reads that carry no suffix of their own. */
    private static final List<String> SHIPPED_TEXT_NAMES = List.of("Dockerfile");

    /**
     * Directory names the shipped-locator scan does not descend into.
     *
     * <p>{@code target} is build output, so a stale copy of a corrected file lives there until the
     * next clean. {@code blitzy} is run evidence rather than delivered code, which is how
     * {@code traceability-matrix.md} classifies it.
     *
     * <p>Each name is compared against a path made relative to the repository root, never against an
     * absolute one. The checkout itself sits below a directory named {@code blitzy}, so matching
     * absolute path elements rejected every file in the tree and left the scan reading one.
     */
    private static final List<String> UNSHIPPED_DIRECTORIES =
            List.of("target", "node_modules", ".git", "blitzy");

    /** Floor on the files the shipped-locator scan reaches before its verdict means anything. */
    private static final int SHIPPED_FILE_FLOOR = 500;

    /** Floor on the locators the same scan reads. */
    private static final int SHIPPED_LOCATOR_FLOOR = 8_000;

    /** The module this test runs inside, whose own reports are still being written. */
    private static final String THIS_MODULE = "equivalence-tests";

    /** Report directory the unit-test plugin writes, relative to a module directory. */
    private static final String UNIT_TEST_REPORTS = "target/surefire-reports";

    /** Report directory the integration-test plugin writes, relative to a module directory. */
    private static final String INTEGRATION_TEST_REPORTS = "target/failsafe-reports";

    /** Prefix every report file carries. */
    private static final String REPORT_PREFIX = "TEST-";

    /** Suffix every report file carries. */
    private static final String REPORT_SUFFIX = ".xml";

    /** One executed case inside a report. Counted, because the {@code tests} attribute under-reports. */
    private static final Pattern REPORT_CASE = Pattern.compile("<testcase\\b");

    /** One module path the aggregator declares. */
    private static final Pattern AGGREGATED_MODULE = Pattern.compile("<module>([^<]+)</module>");

    /** Suffix of the classes the integration-test plugin selects beside the equivalence pattern. */
    private static final String INTEGRATION_CLASS_SUFFIX = "IT.java";

    /** Suffix of the classes the results document tallies as equivalence classes. */
    private static final String EQUIVALENCE_CLASS_SUFFIX = "EquivalenceTest";

    /** The results-document row stating this module's unit and contract count. */
    private static final Pattern PUBLISHED_MODULE_UNIT_TESTS = Pattern.compile(
            "(?m)^\\| Surefire unit and contract tests in the same module \\| ([\\d,]+) passed \\|$");

    /** The results-document row stating the equivalence subtotal. */
    private static final Pattern PUBLISHED_EQUIVALENCE_TESTS = Pattern.compile(
            "(?m)^\\| Failsafe equivalence tests \\| ([\\d,]+) passed across");

    /** The results-document phrase stating this module's whole integration-test count. */
    private static final Pattern PUBLISHED_MODULE_INTEGRATION_TESTS =
            Pattern.compile("giving ([\\d,]+) for this module's whole Failsafe run");

    /** The results-document sentence stating both reactor totals. */
    private static final Pattern PUBLISHED_REACTOR_TESTS = Pattern.compile(
            "The whole reactor ran ([\\d,]+) Surefire and ([\\d,]+) Failsafe tests");

    /** One row of the per-class table: a class name in backticks and its count. */
    private static final Pattern PUBLISHED_CLASS_TALLY =
            Pattern.compile("(?m)^\\| `([A-Za-z0-9_]+)` \\| (\\d+) \\|$");

    /** The platform README sentence stating the same two module figures. */
    private static final Pattern README_PUBLISHED_TESTS = Pattern.compile(
            "reports ([\\d,]+) Failsafe equivalence tests and ([\\d,]+) unit or contract tests");

    /** One row of the per-module count table: a module path and its two figures. */
    private static final Pattern PUBLISHED_MODULE_COUNT = Pattern.compile(
            "(?m)^\\| `([A-Za-z0-9_/-]+)` \\| ([\\d,]+) \\| ([\\d,]+) \\|$");

    /**
     * The post-verify guard that measures what no in-build test can.
     *
     * <p>It reads every completed Surefire and Failsafe report, this module's own included, and holds
     * each published figure against it. A test cannot do that from inside the build it would have to
     * measure, which is why the script exists and why this class requires it to be present, runnable
     * and named in both the results document and the workflow.
     */
    private static final String COUNT_ORACLE_SCRIPT = "scripts/check-published-test-counts.sh";

    /** The workflow that has to run the guard, relative to the repository root. */
    private static final String WORKFLOW_PATH = ".github/workflows/ci.yml";

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
        // CSDAT01Y.cpy moved from excluded to reference-and-semantics: it is the timestamp
        // layout authority BillPaymentEquivalenceTest reads, not a presentation-only helper.
        assertTrue(matrix.contains("12 + 9 + 1 + 5 + 1 = 28"));
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

    /**
     * A register identifier is a permanent contract. Other documents, this suite and earlier review
     * records all cite findings by number, so renumbering silently redirects a citation to a
     * different finding. {@code PostedTransactionServiceTest} citing register item 6 is one such
     * reference.
     *
     * <p>The contract has two parts, and this test enforces both. Identifiers 1 to 26 are the block
     * the Agent Action Plan fixes, in the order it fixes them, so those 26 numbers must be present
     * and in order. Every later finding is appended, so the identifiers above 26 must form one
     * contiguous run starting at 27 with no gap and no repeat. Appending is what an earlier revision
     * failed to do: it collapsed the register onto the specification's numbering and lost eleven
     * findings, which is why the run below is checked rather than the total counted.</p>
     */
    @Test
    void theBusinessRuleRegisterKeepsStableIdentifiersInASpecificationBlockAndAnAppendedBlock() {
        String flags = read(docsDirectory().resolve("business-rule-flags.md"));
        String coverage = section(flags, "## Register coverage",
                "## The largest item: a named program that does not exist");
        Matcher matcher = FLAG_ROW.matcher(coverage);
        List<Integer> identifiers = new ArrayList<>();
        while (matcher.find()) {
            identifiers.add(Integer.valueOf(matcher.group(1)));
        }

        assertTrue(identifiers.size() >= 26,
                "The specification block fixes identifiers 1 to 26, so the register cannot hold"
                        + " fewer than 26 rows. Found " + identifiers.size() + ".");
        for (int position = 0; position < identifiers.size(); position++) {
            assertEquals(position + 1, identifiers.get(position).intValue(),
                    "Register identifiers must run contiguously from 1 with no gap and no repeat:"
                            + " identifiers 1 to 26 are the specification block and every later"
                            + " finding is appended from 27 upward. Row " + (position + 1)
                            + " carries identifier " + identifiers.get(position) + ".");
        }

        assertTrue(flags.contains("An identifier is a permanent contract, never reused and never"
                        + " renumbered"),
                "The register must publish the stable-identifier rule this test enforces.");


        assertEquals(identifiers.size(), new LinkedHashSet<>(identifiers).size(),

                "no two findings may share an identifier");

        assertEquals(String.valueOf(identifiers.size()), declaredRegisterSize(flags),

                "the register heading has to state the number of findings the table carries");

        assertTrue(flags.contains("## Resolved rather than flagged"));
        assertTrue(flags.contains("app/cpy/CVACT01Y.cpy:L13-L14"));
        assertTrue(flags.contains("`RECOVERY(NONE) JOURNAL(NO)`"));
    }

    /**
     * Reads the count of findings the register heading states.
     *
     * @param flags the whole register document
     * @return the number the heading states, as text
     * @throws IllegalStateException when the heading states no count
     */
    private static String declaredRegisterSize(String flags) {
        Matcher declared = DECLARED_REGISTER_SIZE.matcher(flags);
        if (!declared.find()) {
            throw new IllegalStateException(
                    "business-rule-flags.md states no register size after its coverage heading");
        }
        return declared.group(1);
    }

    @Test
    void theArchitectureDocumentContainsNamedBeforeAndAfterMermaidViews() {
        String architecture = read(docsDirectory().resolve("architecture-before-after.md"));
        assertTrue(architecture.contains("## Before:"));
        assertTrue(architecture.contains("## After:"));
        assertFiguresAreTitledLegendedAndReferenced(architecture, "architecture-before-after.md", 2);
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
        assertFiguresAreTitledLegendedAndReferenced(flow, "event-flow.md", 3);
        assertTrue(flow.contains("at least once"));
        assertTrue(flow.contains("Manual acknowledgement"));
        assertTrue(flow.contains("processed_event"));
        assertTrue(flow.contains("one local transaction"));
    }

    /**
     * The topology every document states is the topology the delivered code runs.
     *
     * <p>Four claims had drifted apart from the code at once, and each was the kind a reader
     * believes because it is specific: that {@code transaction.declined} had no runtime consumer,
     * that five source topics had a dead-letter twin, that ten consumer groups ran, and that the
     * authorization service left {@code commitRecovered} at its default. The ledger had gained a
     * third listener on the declines, the sixth twin came with it, and every one of the five
     * listening services sets the flag.
     *
     * <p>Each number below is counted from the code or from the provisioning program rather than
     * repeated from the prose, so the next listener added fails this test instead of quietly
     * making three documents wrong.
     */
    @Test
    void theTopologyProseMatchesTheDeliveredListenersAndTopics() {
        int listeners = occurrencesAcrossServiceSources(LISTENER_ANNOTATION);
        int commitRecovered = servicesContaining("setCommitRecovered(true);");
        int deadLetterTwins = sourceSpecificDeadLetterTopics();

        assertEquals(11, listeners,
                "eleven listeners run across the six services, so a different count means the"
                        + " topology changed and the three documents below have to change with it");
        assertEquals(5, commitRecovered,
                "the five listening services each make their dead-letter route terminal");
        assertEquals(6, deadLetterTwins,
                "six source topics have a dead-letter twin in the provisioning program");

        // Folded, because a count word opens a sentence in one document and sits mid-sentence in
        // another, and which of the two is a matter of where the paragraph break falls.
        String architecture = folded(read(docsDirectory().resolve("architecture-before-after.md")));
        String flow = folded(read(docsDirectory().resolve("event-flow.md")));
        String platformReadme = folded(read(platformDirectory().resolve("README.md")));

        assertTrue(architecture.contains(NUMBER_WORDS.get(listeners) + " consumer groups"),
                "the architecture document has to state the group count it has, which is "
                        + listeners);
        for (String document : List.of(architecture, flow, platformReadme)) {
            assertTrue(document.contains(NUMBER_WORDS.get(deadLetterTwins)
                            + " source-specific dead-letter topics"),
                    "every topology document has to state " + deadLetterTwins
                            + " source-specific dead-letter topics");
            assertFalse(document.contains("five source-specific dead-letter topics"),
                    "the superseded count may not survive anywhere");
        }

        for (String withdrawn : List.of(
                "`transaction.declined` has no runtime consumer",
                "ten consumer groups",
                "leaves the flag at its default",
                "leaves the setting at its default")) {
            for (String document : List.of(architecture, flow, platformReadme)) {
                assertFalse(document.contains(withdrawn),
                        "a document still claims: " + withdrawn);
            }
        }

        assertTrue(flow.contains("`transaction.declined.dlt`"),
                "the sixth twin belongs in the enumeration, since the ledger reads the source");
        assertTrue(architecture.contains("`ledger-reject`"),
                "and the group that reads the declines belongs in the inventory table");
    }

    /**
     * No contract document may deny a consumer the platform delivers.
     *
     * <p>{@code FraudFlagged} carried the sentence that no service consumed {@code fraud.assessed}
     * and that no listener was registered against the {@code notification-fraud} group, while the
     * notification service ran exactly that listener and rendered exactly that alert. Its sibling
     * {@code FraudCleared} described the arrangement correctly on the same topic, so the contract
     * library contradicted itself about its own fan-out, which is the claim a reader of an event
     * contract is least able to check.
     *
     * <p>The consumed set is read from the listener declarations rather than listed here, so a
     * listener added or removed changes what this test demands. A denial is looked for near the
     * topic name rather than anywhere in the file, because a true denial does exist and must stay
     * sayable: nothing consumes the shared dead-letter topic, and a document is right to say so.
     */
    @Test
    @DisplayName("no event contract denies a consumer that the services register")
    void noContractDocumentDeniesAConsumerTheServicesRegister() {
        Set<String> consumed = consumedTopicValues();
        assertEquals(7, consumed.size(),
                "eleven listeners subscribe to seven distinct topics, so a different figure means"
                        + " the topology moved and the contract prose has to move with it: "
                        + consumed);

        for (Path document : contractDocuments()) {
            String folded = folded(read(document));
            for (String denial : List.of("no service consumes", "no listener is registered",
                    "has no runtime consumer", "no service reads")) {
                int at = folded.indexOf(denial);
                while (at >= 0) {
                    String window = folded.substring(Math.max(0, at - DENIAL_WINDOW),
                            Math.min(folded.length(), at + denial.length() + DENIAL_WINDOW));
                    for (String topic : consumed) {
                        assertFalse(window.contains(topic),
                                document.getFileName() + " says \"" + denial + "\" beside " + topic
                                        + ", which " + servicesListeningOn(topic)
                                        + " consume. A delivered listener may not be denied in the"
                                        + " contract a consumer reads.");
                    }
                    at = folded.indexOf(denial, at + 1);
                }
            }
        }

        // The corrected statement is pinned, not merely the withdrawn one, so the paragraph cannot
        // revert to a denial by losing its detail.
        for (String file : List.of("java/com/carddemo/events/FraudFlagged.java",
                "resources/schemas/fraud-flagged-v1.json")) {
            String text = read(platformDirectory()
                    .resolve("libs/event-contracts/src/main/").resolve(file));
            assertTrue(text.contains("FraudFlaggedConsumer"),
                    file + " must name the class that consumes the event it describes");
            assertTrue(text.contains("notification-fraud"),
                    file + " must name the consumer group that reads it");
        }
    }

    @Test
    void theDataModelContainsOneNamedEntityRelationshipViewPerService() {
        String model = read(docsDirectory().resolve("data-model.md"));
        assertFiguresAreTitledLegendedAndReferenced(model, "data-model.md", 6);
        assertEquals(6, count(model, "erDiagram"));
        for (String service : List.of(
                "Authorization database", "Ledger database", "Fraud database",
                "Notification database", "Account database", "Card database")) {
            assertTrue(model.contains("### " + service), service + " section must exist");
        }
        assertTrue(model.contains("no relationship between `ACCOUNT` and `CUSTOMER`"));
        assertTrue(model.contains("`VARCHAR(10)`"));
        assertTrue(model.contains("`DATE`"));

        // The copybook spells 980 area-code literals but declares only 490 distinct codes: all 490
        // under VALID-PHONE-AREA-CODE, then 410 again as VALID-GENERAL-PURP-CODE and 80 as
        // VALID-EASY-RECOG-AREA-CODE. us_phone_area_code holds 490 rows, so the document must report
        // the distinct count and the literal count rather than conflating them.
        assertTrue(model.contains("490 distinct telephone area codes"));
        assertTrue(model.contains("980"));

        // The operational appendix carries every index and every named constraint, as tables. The
        // six diagram assertions above are what keeps it from being drawn as a seventh diagram.
        assertTrue(model.contains("## Operational DDL appendix"));
        // Measured from the migrations rather than written here. This assertion held a literal 45
        // while the delivered schemas carried more, because indexes were added and one section's rows
        // were never restated: a literal makes the test agree with the document instead of with the
        // database, so both were wrong together and nothing failed. The count applies DROP INDEX and
        // DROP TABLE, so an index a later migration withdraws, and one whose table it drops, is not
        // counted.
        int migratedIndexes = migratedIndexCount();
        assertTrue(model.contains("**" + migratedIndexes + " indexes and 146 named constraints**"),
                "the appendix must publish the " + migratedIndexes + " indexes the migrations leave"
                        + " behind, so a schema change restates it");
        // Each service row is asserted as well as the total, because a total that agrees with itself
        // while a row is wrong is the drift a review already found. The index column of each row is
        // measured from that service's own migrations; the constraint column and the generated
        // primary-key column are read from the catalogue and restated here.
        int[] namedConstraints = {37, 18, 21, 14, 23, 33};
        int[] generatedKeys = {2, 0, 0, 0, 5, 0};
        String[] schemaNames = {"Authorization", "Ledger posting", "Fraud detection", "Notification",
                "Account", "Card"};
        int rowIndexTotal = 0;
        for (int at = 0; at < SERVICES.size(); at++) {
            int indexes = migratedIndexCount(SERVICES.get(at));
            rowIndexTotal += indexes;
            String heading = "### " + schemaNames[at] + " schema \u2014 " + indexes + " indexes, "
                    + namedConstraints[at] + " named constraints";
            assertTrue(model.contains(heading), "the appendix must carry " + heading);
            String row = "| " + schemaNames[at] + " | " + indexes + " | " + namedConstraints[at]
                    + " | " + generatedKeys[at] + " |";
            assertTrue(model.contains(row), "the DDL closure table must carry " + row);
        }
        assertEquals(migratedIndexes, rowIndexTotal,
                "the per-service index counts must sum to the total the appendix publishes");
        assertTrue(model.contains("| **Total** | **" + migratedIndexes + "** | **146** | **7** |"),
                "the closure total must be the sum of its rows");
        // V7 drops the account card_xref replica, so no figure and no section may present it as live.
        assertFalse(model.contains("The ninth table of this schema"));
        assertFalse(model.contains("The account service holds its own replica"));
        assertTrue(model.contains("`REPLICA_GAP`, added by `V12__replica_gap.sql`"));
        assertTrue(model.contains("timestamptz dead_letter_published_at"));
        assertTrue(model.contains("## Reference data"));
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

    /**
     * Every source locator anywhere in the shipped tree names a range the cited file contains.
     *
     * <p>The check above reads six documents, and it was the rest of the tree that carried the
     * defect. Card {@code V5} cited the card status test as lines 1861 to 1863 of
     * {@code app/cbl/COCRDUPC.cbl}, a file of 1560 lines, and a published API comment, a repository
     * integration test and two unit tests repeated the same range. A reader following any of the
     * five reached nothing. The band the source really tests is
     * {@code app/cbl/COCRDUPC.cbl:L861-L871}, inside {@code 1240-EDIT-CARDSTATUS} at
     * {@code app/cbl/COCRDUPC.cbl:L845-L874}.
     *
     * <p>So the scan is the whole delivered tree rather than the documents: every java, sql, json,
     * yaml, markdown, shell, html, xml, csv, text and property file under the platform root, the
     * environment template, the container definitions, the workflow directory, and the repository
     * guide this engagement updates. Build output and run evidence are skipped, and both floors
     * below are asserted first, so a walk that silently reached nothing fails instead of passing.
     *
     * <p>Two shapes fail. A range outside {@code 1} to the cited file's line count fails, and so
     * does a locator whose range is cut off at a line end, because a wrapped locator matches no
     * pattern and would go unread. Pointing at the wrong line of the right file is not detectable
     * here and is not claimed to be: ledger {@code V4} cited two cycle columns one line above their
     * fields, ledger {@code V6} corrected them, and only a reader comparing the copybook could tell.
     */
    @Test
    @DisplayName("every source locator in the shipped tree resolves inside the file it names")
    void everyShippedCitationResolvesWithinTheNamedSourceFile() {
        List<Path> shipped = shippedTextFiles();
        assertTrue(shipped.size() >= SHIPPED_FILE_FLOOR,
                "the scan reached " + shipped.size() + " shipped text files, fewer than the "
                        + SHIPPED_FILE_FLOOR + " this platform delivers, so its verdict would mean"
                        + " nothing");

        List<String> offending = new ArrayList<>();
        int locators = 0;
        for (Path file : shipped) {
            String relative = repositoryRoot().relativize(file).toString();
            int number = 0;
            for (String line : read(file).split("\\R", -1)) {
                number++;
                if (DANGLING_CITATION.matcher(line).find()) {
                    offending.add(relative + ":" + number + " breaks a locator across two lines");
                }
                Matcher citation = CITATION.matcher(line);
                while (citation.find()) {
                    locators++;
                    Path source = repositoryRoot().resolve(citation.group(1)).normalize();
                    if (!Files.isRegularFile(source)) {
                        offending.add(relative + ":" + number + " cites missing "
                                + citation.group(1));
                        continue;
                    }
                    int start = Integer.parseInt(citation.group(2));
                    int end = citation.group(3) == null
                            ? start : Integer.parseInt(citation.group(3));
                    long lineCount = lineCountOf(source);
                    if (start < 1 || end < start || end > lineCount) {
                        offending.add(relative + ":" + number + " cites " + citation.group()
                                + " outside 1-" + lineCount);
                    }
                }
            }
        }

        assertTrue(locators >= SHIPPED_LOCATOR_FLOOR,
                "the scan read " + locators + " source locators, fewer than the "
                        + SHIPPED_LOCATOR_FLOOR + " this platform cites, so its verdict would mean"
                        + " nothing");
        assertEquals(List.of(), offending,
                "a source locator names the lines a reader is sent to, so every one has to resolve"
                        + " in the file it names: " + offending);
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

    /**
     * Each service README states the number of migrations that service ships.
     *
     * <p>A migration count is the one figure in a service README that a correction is guaranteed to
     * change, because an applied migration cannot be edited and a correction therefore arrives as a
     * new file. Correcting two column citations in the ledger schema moved its count from five to
     * six, and the same measurement found three other READMEs already behind their own tables: one
     * said three migrations above a table of four, and another said three and there is no fourth
     * while three further files shipped and were described later in the same document.
     *
     * <p>The count is read from the directory rather than listed here, and the README has to state
     * it as the word it reads in prose. A README that names a different count anywhere fails too,
     * so a superseded figure cannot survive in a second sentence. Demo overlays under
     * {@code db/demo} are excluded, because they are opt-in and the READMEs count them separately.
     *
     * <p>The second half compares on a word boundary rather than on a bare substring. A hyphenated
     * count contains a smaller one: {@code twenty-three migrations} ends in {@code three
     * migrations}, and the authorization README failed for stating its own count correctly the day
     * that service reached twenty-three files.
     */
    @Test
    @DisplayName("every service README states the migration count that service ships")
    void everyServiceReadmeStatesItsMigrationCount() {
        for (String service : SERVICES) {
            Path module = platformDirectory().resolve("services").resolve(service);
            long migrations;
            try (Stream<Path> files = Files.list(module.resolve("src/main/resources/db/migration"))) {
                migrations = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".sql"))
                        .count();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot list the migrations of " + service,
                        unreadable);
            }

            String readme = folded(read(module.resolve("README.md")));
            String stated = NUMBER_WORDS.get((int) migrations);
            assertTrue(stated != null,
                    service + " ships " + migrations + " migrations, a count this test has no word"
                            + " for. Extend NUMBER_WORDS rather than dropping the check.");
            assertTrue(readme.contains(stated + " migrations"),
                    service + " ships " + migrations + " migrations, so its README has to say \""
                            + stated + " migrations\"");
            for (Map.Entry<Integer, String> other : NUMBER_WORDS.entrySet()) {
                if (other.getKey() != (int) migrations) {
                    Pattern claim = Pattern.compile("(?<![\\w-])" + Pattern.quote(other.getValue())
                            + " migrations");
                    assertFalse(claim.matcher(readme).find(),
                            service + " ships " + migrations + " migrations and its README also"
                                    + " claims " + other.getValue() + ", so one of the two"
                                    + " sentences is wrong");
                }
            }
        }
    }

    /**
     * The published expected-output inventory is the one on disk.
     *
     * <p>{@code equivalence-results.md} stated the inventory twice and the two statements had come
     * apart. One said {@code posting-summary.csv} held 20 rows read by two classes, the other said
     * 18 rows that no assertion depended on; one gave {@code synthetic-boundary-cases.csv} a single
     * reader, the other partitioned it across four with a 47-row slice for a class that never opens
     * it. Both cannot be true, and a reader has no way to tell which is. The duplicate is gone and
     * this test holds what remains to the files.
     *
     * <p>Row counts and readers are read from the files themselves rather than from a list here. A
     * file's own comment line declares its consuming tests, which is the declaration
     * {@code ExpectedOutputBindingContractTest} already holds those classes to, so the document, the
     * files and the harness cannot disagree about who reads what. Naming a class the file does not
     * declare fails as surely as omitting one, because an over-claimed reader is what made the
     * withdrawn narrative sound specific.
     */
    @Test
    @DisplayName("the results document states the rows and readers the expected files carry")
    void theExpectedOutputInventoryMatchesTheFilesOnDisk() {
        String results = read(docsDirectory().resolve("equivalence-results.md"));
        Path expectedDirectory = platformDirectory()
                .resolve("equivalence-tests/src/test/resources/expected");

        List<Path> files;
        try (Stream<Path> listed = Files.list(expectedDirectory)) {
            files = listed.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + expectedDirectory, unreadable);
        }
        assertEquals(14, files.size(), "fourteen expected-output files ship with this module");

        int totalRows = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            List<String> lines = read(file).lines().toList();
            int rows = lines.size() - PREAMBLE_AND_HEADER_LINES;
            totalRows += rows;

            Matcher declaration = CONSUMING_TESTS.matcher(lines.getFirst());
            assertTrue(declaration.find(),
                    name + " must declare its consuming tests on its comment line");
            Set<String> declared = new LinkedHashSet<>(
                    List.of(declaration.group(1).trim().split(" *(?:,|and) *")));

            String row = tableRowFor(results, name);
            assertEquals(String.valueOf(rows), row.split("\\|")[2].trim().replace(",", ""),
                    name + " holds " + rows + " rows, and the document has to say so");

            Set<String> named = new LinkedHashSet<>();
            Matcher classes = TEST_CLASS_NAME.matcher(row.substring(row.indexOf('|',
                    row.indexOf('|', 1) + 1)));
            while (classes.find()) {
                named.add(classes.group());
            }
            assertEquals(declared, named,
                    name + " declares " + declared + " as its consuming tests, so the document may"
                            + " name those and no others. It names " + named + ".");
        }

        assertTrue(results.contains(String.format("| %,d |", totalRows)),
                "the run summary has to state the " + totalRows + " rows the files carry");

        for (String withdrawn : List.of(
                "supplemental context only",
                "four classes, each filtering",
                "the one file no assertion depends on",
                "partition across four classes")) {
            assertFalse(results.contains(withdrawn),
                    "the withdrawn inventory narrative may not survive: " + withdrawn);
        }
    }

    /**
     * Every published test count is measured, and no published figure is checked against another.
     *
     * <p>A review found the previous form of this test to be algebraically self-referential. It
     * subtracted the published module total from the published reactor total and compared the
     * remainder with the other modules' reports, so changing both published figures by the same
     * amount passed. The module's own integration figure was closed against a sum of another
     * published table, which is the same defect one level down.</p>
     *
     * <p><strong>What replaced it.</strong> The results document now carries one row per reactor
     * module. Every module but this one is compared against <em>its own</em> reports, so a wrong
     * figure has to be wrong against a measurement rather than against another claim. The reactor
     * totals are then required to be the sum of those rows, which removes the only place two figures
     * could move together.</p>
     *
     * <p><strong>The one figure this test cannot measure, and what does.</strong> This module's own
     * two counts are unmeasurable from inside it: the unit report for this very class does not exist
     * while the class is running, and the integration phase of this module has not started. They are
     * closed here against the per-class table and the platform README, and measured after the build
     * by {@code scripts/check-published-test-counts.sh}, which reads every completed report
     * including this module's. This test requires that script to exist, to be executable and to be
     * named in the results document, because a guard nobody runs is not a guard.</p>
     *
     * <p><strong>Why an absent report directory is not a failure here.</strong> The
     * continuous-integration workflow runs {@code mvn -pl equivalence-tests -am test} in one stage,
     * which executes every module's unit tests and no integration test anywhere. A comparison that
     * demanded integration reports would fail that stage for a reason unrelated to the documents. A
     * report kind is therefore compared whenever any module wrote one, a kind written by some modules
     * and not others fails naming the gap, and a kind nobody wrote is left to the post-verify script,
     * which runs only where a full {@code verify} has happened.</p>
     */
    @Test
    @DisplayName("every published test count is measured against the reports its own module wrote")
    void thePublishedTestCountsAreTheOnesThisBuildMeasured() {
        String results = read(docsDirectory().resolve("equivalence-results.md"));
        String platformReadme = read(platformDirectory().resolve("README.md"));

        List<String> modules = aggregatedModules();
        Map<String, long[]> publishedPerModule = publishedModuleCounts(results, modules);

        // 1. The per-class Failsafe table closes against the two subtotals the document states.
        long publishedEquivalence = publishedFigure(results, PUBLISHED_EQUIVALENCE_TESTS,
                "| Failsafe equivalence tests | <count> passed across the nine ... classes |");
        long publishedModuleIntegration = publishedFigure(results,
                PUBLISHED_MODULE_INTEGRATION_TESTS,
                "giving <count> for this module's whole Failsafe run");
        Map<String, Long> tallies = publishedClassTallies(results);
        long publishedEquivalenceTally = tallies.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(EQUIVALENCE_CLASS_SUFFIX))
                .mapToLong(Map.Entry::getValue)
                .sum();
        long publishedWholeTally = tallies.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(publishedEquivalence, publishedEquivalenceTally,
                "the equivalence subtotal has to be the sum of the per-class rows that name an "
                        + EQUIVALENCE_CLASS_SUFFIX + " class. The table sums to "
                        + publishedEquivalenceTally + " and the subtotal says " + publishedEquivalence);
        assertEquals(publishedModuleIntegration, publishedWholeTally,
                "this module's whole Failsafe figure has to be the sum of every per-class row. The "
                        + "table sums to " + publishedWholeTally + " and the figure says "
                        + publishedModuleIntegration);

        // 2. This module's own row agrees with the two figures the observed-run table publishes.
        long publishedModuleUnit = publishedFigure(results, PUBLISHED_MODULE_UNIT_TESTS,
                "| Surefire unit and contract tests in the same module | <count> passed |");
        long[] thisModuleRow = publishedPerModule.get(THIS_MODULE);
        assertEquals(publishedModuleUnit, thisModuleRow[0],
                "the per-module row for " + THIS_MODULE + " has to publish the same Surefire figure"
                        + " as the observed-run table, which says " + publishedModuleUnit);
        assertEquals(publishedModuleIntegration, thisModuleRow[1],
                "and the same Failsafe figure, which the observed-run table gives as "
                        + publishedModuleIntegration);

        // 3. The reactor totals are the sum of the per-module rows, so neither can drift alone.
        Matcher reactor = PUBLISHED_REACTOR_TESTS.matcher(results);
        assertTrue(reactor.find(),
                "equivalence-results.md must state both reactor totals as \"The whole reactor ran"
                        + " <count> Surefire and <count> Failsafe tests\", because this test reads"
                        + " them from that sentence");
        long publishedReactorUnit = number(reactor.group(1));
        long publishedReactorIntegration = number(reactor.group(2));
        long rowUnitSum = publishedPerModule.values().stream().mapToLong(row -> row[0]).sum();
        long rowIntegrationSum = publishedPerModule.values().stream().mapToLong(row -> row[1]).sum();
        assertEquals(rowUnitSum, publishedReactorUnit,
                "the reactor Surefire total has to be the sum of the per-module rows, which come to "
                        + rowUnitSum + " against a published total of " + publishedReactorUnit);
        assertEquals(rowIntegrationSum, publishedReactorIntegration,
                "the reactor Failsafe total has to be the sum of the per-module rows, which come to "
                        + rowIntegrationSum + " against a published total of "
                        + publishedReactorIntegration);

        // 4. The platform guide repeats this module's two figures rather than inventing its own.
        Matcher readme = README_PUBLISHED_TESTS.matcher(platformReadme);
        assertTrue(readme.find(),
                "the platform README must state the same two module figures as \"reports <count> "
                        + "Failsafe equivalence tests and <count> unit or contract tests\", because "
                        + "a figure published twice is a figure that can disagree with itself");
        assertEquals(publishedEquivalence, number(readme.group(1)),
                "the README equivalence figure must be the one equivalence-results.md publishes");
        assertEquals(publishedModuleUnit, number(readme.group(2)),
                "the README unit figure must be the one equivalence-results.md publishes");

        // 5. Every other module: its published row against its own reports.
        List<String> others = new ArrayList<>(modules);
        others.remove(THIS_MODULE);
        assertFalse(others.isEmpty(), "the aggregator declares modules beside " + THIS_MODULE);
        compareAgainstReports(others, UNIT_TEST_REPORTS, "Surefire", publishedPerModule, 0);
        compareAgainstReports(others.stream().filter(
                        DocumentationContractTest::carriesIntegrationClasses).toList(),
                INTEGRATION_TEST_REPORTS, "Failsafe", publishedPerModule, 1);

        // 6. The post-verify oracle exists, runs, and is the one the document names.
        Path oracle = platformDirectory().resolve(COUNT_ORACLE_SCRIPT);
        assertTrue(Files.isRegularFile(oracle),
                COUNT_ORACLE_SCRIPT + " must exist: it is the only check that measures this module's"
                        + " own two figures, which no test inside this module can");
        assertTrue(Files.isExecutable(oracle),
                COUNT_ORACLE_SCRIPT + " must be executable, or the workflow step that runs it fails"
                        + " for the wrong reason");
        assertTrue(results.contains(COUNT_ORACLE_SCRIPT),
                "equivalence-results.md must name " + COUNT_ORACLE_SCRIPT + " as the oracle behind"
                        + " its figures, so a reader has a command rather than a claim");
        String workflow = read(repositoryRoot().resolve(WORKFLOW_PATH));
        assertTrue(workflow.contains(COUNT_ORACLE_SCRIPT),
                WORKFLOW_PATH + " must run " + COUNT_ORACLE_SCRIPT + " after a verify stage, or the"
                        + " figures are measured on a developer machine and nowhere else");
    }

    /**
     * Returns the per-module count table of the results document, one entry per reactor module.
     *
     * @param document the results document
     * @param modules  module paths the aggregator declares
     * @return Surefire count at index 0 and Failsafe count at index 1, keyed by module path
     */
    private static Map<String, long[]> publishedModuleCounts(String document, List<String> modules) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Matcher rows = PUBLISHED_MODULE_COUNT.matcher(document);
        while (rows.find()) {
            counts.put(rows.group(1), new long[] {number(rows.group(2)), number(rows.group(3))});
        }
        assertEquals(new LinkedHashSet<>(modules), counts.keySet(),
                "equivalence-results.md must carry one per-module row for every module the"
                        + " aggregator declares, as \"| `<module path>` | <surefire> | <failsafe> |\","
                        + " because a module with no row is a module whose figures nothing measures");
        return counts;
    }

    /**
     * Holds each module's published row against the reports that module itself wrote.
     *
     * <p>No published figure takes part on the measured side, which is the property the previous
     * form of this helper lacked.</p>
     *
     * @param modules         module paths to measure, this module already excluded
     * @param reportDirectory report directory to read, relative to a module directory
     * @param plugin          plugin name, for the failure message
     * @param published       the per-module table of the results document
     * @param column          0 for the Surefire figure of a row, 1 for the Failsafe figure
     */
    private static void compareAgainstReports(List<String> modules, String reportDirectory,
            String plugin, Map<String, long[]> published, int column) {
        List<String> silent = new ArrayList<>();
        Map<String, Long> measured = new LinkedHashMap<>();
        for (String module : modules) {
            long cases = reportedCases(module, reportDirectory);
            if (cases < 0L) {
                silent.add(module);
            } else {
                measured.put(module, cases);
            }
        }
        if (silent.size() == modules.size()) {
            // No module reached this phase in this invocation. scripts/check-published-test-counts.sh
            // measures it after a full verify, and the closure checks above still hold here.
            return;
        }
        assertTrue(silent.isEmpty(),
                plugin + " reports are present for some modules and absent for " + silent
                        + ". Run the reactor rather than one module, so every published row is"
                        + " compared against a complete run");
        measured.forEach((module, cases) -> assertEquals(cases, published.get(module)[column],
                "the " + plugin + " row for " + module + " must publish the " + cases + " cases"
                        + " that module's own reports carry. Count the testcase elements under "
                        + module + "/" + reportDirectory + " and restate the figure rather than"
                        + " editing this test"));
    }

    /** Returns one figure the results document publishes, or fails naming the sentence it needs. */
    private static long publishedFigure(String document, Pattern figure, String shape) {
        Matcher match = figure.matcher(document);
        assertTrue(match.find(),
                "equivalence-results.md must state its test counts as \"" + shape + "\", because "
                        + "this test reads the published figure from there");
        return number(match.group(1));
    }

    /** Returns the per-class table of the results document, keyed by class name. */
    private static Map<String, Long> publishedClassTallies(String document) {
        Map<String, Long> tallies = new LinkedHashMap<>();
        Matcher rows = PUBLISHED_CLASS_TALLY.matcher(document);
        while (rows.find()) {
            tallies.put(rows.group(1), number(rows.group(2)));
        }
        assertFalse(tallies.isEmpty(),
                "equivalence-results.md must carry the per-class Failsafe table, one row per class"
                        + " as \"| `ClassName` | <count> |\"");
        return tallies;
    }

    /** Reads a published count, tolerating the thousands separator the documents use. */
    private static long number(String published) {
        return Long.parseLong(published.replace(",", ""));
    }

    /** Returns the module paths the aggregator declares, in declaration order. */
    private static List<String> aggregatedModules() {
        List<String> modules = new ArrayList<>();
        Matcher declared = AGGREGATED_MODULE.matcher(read(platformDirectory().resolve("pom.xml")));
        while (declared.find()) {
            modules.add(declared.group(1));
        }
        assertFalse(modules.isEmpty(), "the aggregator must declare its modules");
        return modules;
    }

    /**
     * Counts the executed cases one module's reports hold.
     *
     * @param module          module path as the aggregator declares it
     * @param reportDirectory report directory relative to the module directory
     * @return the case count, or {@code -1} when the module wrote no such directory
     */
    private static long reportedCases(String module, String reportDirectory) {
        Path directory = platformDirectory().resolve(module).resolve(reportDirectory);
        if (!Files.isDirectory(directory)) {
            return -1L;
        }
        long cases = 0L;
        try (Stream<Path> reports = Files.list(directory)) {
            for (Path report : reports.filter(Files::isRegularFile).toList()) {
                String name = report.getFileName().toString();
                if (!name.startsWith(REPORT_PREFIX) || !name.endsWith(REPORT_SUFFIX)) {
                    continue;
                }
                Matcher executed = REPORT_CASE.matcher(read(report));
                while (executed.find()) {
                    cases++;
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
        return cases;
    }

    /** Reports whether one module carries a class the integration-test plugin selects. */
    private static boolean carriesIntegrationClasses(String module) {
        Path tests = platformDirectory().resolve(module).resolve("src/test/java");
        if (!Files.isDirectory(tests)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(tests)) {
            return walk.anyMatch(
                    path -> path.getFileName().toString().endsWith(INTEGRATION_CLASS_SUFFIX));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + tests, unreadable);
        }
    }

    /** Returns the inventory row naming one expected-output file, or fails when there is none. */
    private static String tableRowFor(String document, String fileName) {
        List<String> rows = document.lines()
                .filter(line -> line.startsWith("| `" + fileName + "` |"))
                .toList();
        assertEquals(1, rows.size(),
                fileName + " must appear in exactly one inventory row, because a count stated twice"
                        + " is a count that can disagree with itself. Rows found: " + rows.size());
        return rows.getFirst();
    }

    /**
     * Closes the backward traceability count against itself and against the delivered tree.
     *
     * <p>Rule 1 requires the matrix to read backward from every delivered path, and the document
     * states that count four times in prose plus once per group in the closure table. A published
     * count is evidence, so a count that drifts from the tree is a Rule 1 defect rather than a
     * typographical one. This test derives the figure from the rows themselves and requires every
     * statement of it to agree, which is what a review found stale in the citations two ledger
     * column comments carried.
     *
     * <p>The disk anchor is the whole delivered tree, compared in both directions. An earlier form of
     * this test checked only that every row's path existed and that every schema migration owned a
     * row, on the reasoning that enumerating the tree would mean reproducing the working copy's
     * ignore rules. It would not: git applies those rules itself, and
     * {@code git ls-files --cached --others --exclude-standard} names exactly the delivered set. The
     * narrow form left the count complete by luck — a review found it would pass while a delivered
     * file carried no row, which is the one thing Rule 1's backward direction exists to prevent.
     *
     * <p>The {@code --others --exclude-standard} half matters and is easy to leave off. A plain
     * {@code git ls-files} lists tracked paths only, so a file this session added is invisible to it
     * until that file is staged or committed. A guard built on the plain form would report closure
     * against a tree missing every file the session added, and would report it differently before and
     * after a commit.
     *
     * <p>The set the command names is every path this engagement creates or updates, which is why
     * {@link #DELIVERED_SET_ARGUMENTS} names two files at the repository root as well as the two
     * directories. Scoping it to the directories alone left the root {@code README.md} and
     * {@code .gitattributes} outside a closure that claimed to cover the delivered tree, and a
     * review measured the gap. A withdrawn path holds no row by the same rule, so the matrix records
     * those operations in a section of their own below the closure.
     */
    @Test
    @DisplayName("the backward traceability count matches its rows, its groups and the delivered tree")
    void theBackwardTraceabilityCountClosesAgainstTheDeliveredTree() {
        String matrix = read(docsDirectory().resolve("traceability-matrix.md"));
        String backward = section(matrix, "## Backward: every target path",
                "### Backward closure arithmetic");
        String closure = section(matrix, "### Backward closure arithmetic", "**Backward closure:**");

        List<String> paths = new ArrayList<>();
        Matcher rows = BACKWARD_ROW.matcher(backward);
        while (rows.find()) {
            paths.add(rows.group(1));
        }
        int counted = paths.size();

        assertEquals(counted, new LinkedHashSet<>(paths).size(),
                "no target path may carry two backward rows");

        List<Integer> groups = new ArrayList<>();
        Matcher tallies = CLOSURE_TALLY.matcher(closure);
        while (tallies.find()) {
            groups.add(Integer.valueOf(tallies.group(1)));
        }
        int declaredTotal = groups.removeLast();
        assertEquals(counted, groups.stream().mapToInt(Integer::intValue).sum(),
                "the closure groups must sum to the rows the section carries");
        assertEquals(counted, declaredTotal, "the closure total must be the row count");

        for (String statement : List.of(
                "from every one of the " + counted + " delivered target paths",
                "| Delivered target tree | " + counted + " tracked files |",
                "The delivered tree holds " + counted + " tracked target paths.",
                "Every one of the " + counted + " then appears once",
                "reports " + counted + " delivered paths, and the tables below carry " + counted
                        + " rows.",
                "**Backward closure:** " + counted + " rows against " + counted
                        + " tracked paths,")) {
            assertTrue(matrix.contains(statement),
                    "every published statement of the count must read " + counted
                            + ", so this one has to be present: " + statement);
        }

        Set<String> rowed = new LinkedHashSet<>(paths);
        Set<String> delivered = deliveredPaths();

        List<String> unrowed = delivered.stream().filter(path -> !rowed.contains(path)).toList();
        assertTrue(unrowed.isEmpty(),
                "these paths are delivered and carry no backward row, so the matrix reads backward"
                        + " from less than the whole tree and Rule 1's closure is not closed: "
                        + unrowed);

        List<String> undelivered = paths.stream().filter(path -> !delivered.contains(path)).toList();
        assertTrue(undelivered.isEmpty(),
                "these rows name paths the tree does not deliver, so the count is inflated by rows"
                        + " for files that were renamed or removed: " + undelivered);

        assertEquals(delivered.size(), counted,
                "the row count and the delivered count are the same number measured two ways");

        assertTrue(matrix.contains(DELIVERED_SET_COMMAND),
                "the matrix has to name the command its figure comes from, and name the form that"
                        + " produces it. A plain `git ls-files` omits every file added in the"
                        + " session that wrote the matrix, so it reports a smaller number than the"
                        + " rows below it: " + DELIVERED_SET_COMMAND);

        long citing = paths.size() - withoutSourceCitation(backward).size();
        List<String> uncited = withoutSourceCitation(backward);
        long plainlyUncited = uncited.stream().filter(PLAIN_ABSENCE::equals).count();
        for (String statement : List.of(
                citing + " of the " + counted + " paths name at least one member under `app/`",
                "the remaining " + uncited.size() + " name none",
                "Every one of those " + uncited.size() + " cells opens with `None cited`",
                plainlyUncited + " read `None cited in the file`",
                "the other " + (uncited.size() - plainlyUncited) + " name the absence itself")) {
            assertTrue(matrix.contains(statement),
                    "the citation figures are measured from the provenance column, and this one has"
                            + " drifted from it. A review found them stale twice, so they are bound"
                            + " here rather than restated by hand: " + statement);
        }
    }

    /**
     * Repository entries the delivered set does not name, each for a stated reason.
     *
     * <p>{@code app}, {@code diagrams} and {@code samples} are the legacy inputs, which a project
     * constraint makes read-only. The four files are byte-identical to the baseline commit, so a row
     * for any of them would claim an operation this engagement did not perform.
     */
    private static final Set<String> ENTRIES_OUTSIDE_THE_DELIVERED_SET = Set.of(
            "app", "diagrams", "samples",
            "CODE_OF_CONDUCT.md", "CONTRIBUTING.md", "LICENSE", "NOTICE");

    /**
     * Holds the delivered-set definition closed against the repository rather than against itself.
     *
     * <p>{@link #DELIVERED_SET_ARGUMENTS} names two directories and two files, so a path written
     * anywhere else is invisible to every closure built on it. That is how the root
     * {@code README.md} and {@code .gitattributes} went unrowed: the command could not see them, so
     * the count it produced agreed with the rows and both were short of the tree. A review measured
     * the gap, which no test could.
     *
     * <p>This one can. Every top-level entry git resolves is either covered by the delivered set or
     * named in {@link #ENTRIES_OUTSIDE_THE_DELIVERED_SET} with the reason it is not. A new file or
     * directory at the repository root therefore fails here until it is either delivered or
     * excluded on the record, rather than passing unnoticed.
     */
    @Test
    @DisplayName("the delivered set covers every repository entry this engagement writes")
    void theDeliveredSetCoversEveryRepositoryEntryThisEngagementWrites() {
        Set<String> delivered = deliveredPaths();
        Set<String> entries = new LinkedHashSet<>();
        for (String path : gitListedPaths(List.of("--cached", "--others", "--exclude-standard"))) {
            int slash = path.indexOf('/');
            entries.add(slash < 0 ? path : path.substring(0, slash));
        }
        assertTrue(entries.containsAll(ENTRIES_OUTSIDE_THE_DELIVERED_SET),
                "every excluded entry has to exist, or the exclusion is a stale name that hides a"
                        + " real one: " + entries);

        List<String> uncovered = entries.stream()
                .filter(entry -> !ENTRIES_OUTSIDE_THE_DELIVERED_SET.contains(entry))
                .filter(entry -> delivered.stream()
                        .noneMatch(path -> path.equals(entry) || path.startsWith(entry + "/")))
                .toList();
        assertTrue(uncovered.isEmpty(),
                "these repository entries are neither covered by the inventory command nor recorded"
                        + " as outside it, so the traceability matrix would close over less than the"
                        + " tree and no count would report the difference: " + uncovered);
    }

    /**
     * Returns every path this engagement delivers, as git resolves the working copy's ignore rules.
     *
     * @return the delivered paths, repository-root relative
     */
    private static Set<String> deliveredPaths() {
        return gitListedPaths(DELIVERED_SET_ARGUMENTS);
    }

    /**
     * Runs {@code git ls-files} with the given arguments and returns the paths it names.
     *
     * @param arguments the arguments to pass, in order
     * @return the listed paths, repository-root relative, in git's order
     */
    private static Set<String> gitListedPaths(List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("git", "ls-files"));
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repositoryRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), () -> command + " failed: " + output);
            Set<String> paths = new LinkedHashSet<>();
            for (String line : output.split("\\R")) {
                if (!line.isBlank()) {
                    paths.add(line);
                }
            }
            assertFalse(paths.isEmpty(), "git listed no delivered path, so nothing was compared");
            return paths;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot run " + command, unreadable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running " + command, interrupted);
        }
    }

    /**
     * Holds the published store-ownership table to the tables the migrations actually create.
     *
     * <p>Rule 2 asks the paired views to be accurate, and a table naming what a service owns is the
     * part of them a reader trusts without checking. A review found the card row naming four tables
     * while its migrations create six: {@code V10__card_token_version_and_rotation.sql} adds
     * {@code card_token_rotation} and {@code card_token_rotation_mapping}, and neither reached the
     * document. Nothing detected it, because the row is prose.
     *
     * <p>The migrations are the anchor, read as created minus dropped. Authorization creates
     * {@code unresolved_card_attempt} in {@code V13} and drops it in {@code V20}, and the account
     * service creates the card-keyed {@code card_xref} replica in {@code V4} and drops it in
     * {@code V7}, so a withdrawn table must not appear in the table either.
     */
    @Test
    @DisplayName("the published store ownership names the tables each service's migrations create")
    void thePublishedStoreOwnershipMatchesTheMigrations() {
        String architecture = read(docsDirectory().resolve("architecture-before-after.md"));
        Matcher row = Pattern.compile("(?m)^\\| (authorization|ledger posting|fraud detection"
                        + "|notification|account|card) \\| `carddemo_\\w+`, `(\\w+)` \\| ([^|]+) \\|$")
                .matcher(architecture);
        Map<String, Set<String>> published = new LinkedHashMap<>();
        while (row.find()) {
            Set<String> named = new TreeSet<>();
            Matcher table = Pattern.compile("`(\\w+)`").matcher(row.group(3));
            while (table.find()) {
                named.add(table.group(1));
            }
            published.put(row.group(1), named);
        }
        assertEquals(6, published.size(),
                "the ownership table carries one row per service, and this scan read "
                        + published.keySet());

        Map<String, String> modules = Map.of(
                "authorization", "authorization-service",
                "ledger posting", "ledger-posting-service",
                "fraud detection", "fraud-detection-service",
                "notification", "notification-service",
                "account", "account-service",
                "card", "card-service");
        published.forEach((service, named) -> {
            Path migrations = platformDirectory()
                    .resolve("services/" + modules.get(service) + "/src/main/resources/db/migration");
            Set<String> live = new TreeSet<>();
            List<Path> files;
            try (Stream<Path> listed = Files.list(migrations)) {
                files = listed.filter(path -> path.getFileName().toString().endsWith(".sql"))
                        .sorted(Comparator.comparingInt(DocumentationContractTest::migrationVersion))
                        .toList();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot list " + migrations, unreadable);
            }
            for (Path file : files) {
                String migration = read(file);
                Matcher created = Pattern.compile(
                                "(?im)^\\s*CREATE TABLE (?:IF NOT EXISTS )?(?:\\w+\\.)?(\\w+)")
                        .matcher(migration);
                while (created.find()) {
                    live.add(created.group(1));
                }
                Matcher dropped = Pattern.compile(
                                "(?im)^\\s*DROP TABLE (?:IF EXISTS )?(?:\\w+\\.)?(\\w+)")
                        .matcher(migration);
                while (dropped.find()) {
                    live.remove(dropped.group(1));
                }
            }
            assertEquals(live, named,
                    "the " + service + " row of the store-ownership table must name exactly the"
                            + " tables its migrations leave in place. Migrations create " + live
                            + " and the document names " + named);
        });
    }

    /**
     * Holds every backward classification to the provenance cell beside it, and the published
     * breakdown to the labels it counts.
     *
     * <p>The matrix defines its own labels: source-derived names at least one member under
     * {@code app/}, and additive has no ancestor whether or not it cites one. A row classified
     * source-derived whose provenance cell names no member therefore contradicts the definition in
     * the same document, and a review found two such rows — {@code card-updated-v2.json} and the
     * account {@code V6__processed_event_topic_key.sql} migration, both additive behaviour published
     * as migrated behaviour. Rule 1's traceability is only worth reading if a label means what the
     * document says it means, so the two are measured against each other here rather than compared
     * by eye.
     *
     * <p>The published breakdown is measured the same way. Eight labels each carry a count in the
     * coverage row, and those counts have to be the counts of the rows below and to sum to the
     * closure total. A reclassified row therefore moves two numbers, and leaving either behind fails
     * here.
     */
    @Test
    @DisplayName("every backward classification matches its provenance and the published breakdown")
    void theBackwardClassificationsMatchTheirProvenanceAndTheBreakdown() {
        String matrix = read(docsDirectory().resolve("traceability-matrix.md"));
        String backward = section(matrix, "## Backward: every target path",
                "### Backward closure arithmetic");

        Map<String, Integer> measured = new LinkedHashMap<>();
        List<String> contradictions = new ArrayList<>();
        int rows = 0;
        for (String line : backward.split("\\R")) {
            Matcher row = BACKWARD_ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            String[] columns = line.split("\\|");
            assertTrue(columns.length > 3,
                    "a backward row carries a path, a provenance cell and a classification: " + line);
            String provenance = columns[2].trim();
            String classification = columns[3].trim();
            rows++;
            measured.merge(classification, 1, Integer::sum);
            if (classification.toLowerCase(Locale.ROOT).contains("source-derived")
                    && !provenance.contains("app/")) {
                contradictions.add(row.group(1) + " is classified " + classification
                        + " while its provenance reads \"" + provenance + "\"");
            }
        }
        assertEquals(List.of(), contradictions,
                "the matrix defines source-derived as naming at least one member under `app/`, so"
                        + " these rows claim a provenance they do not have. Classify a file with no"
                        + " ancestor as additive: " + contradictions);

        Matcher coverage = Pattern.compile("(?m)^\\| Delivered target tree \\| (\\d+) tracked files"
                        + " \\| ([^|]+) \\| (\\d+) \\|$").matcher(matrix);
        assertTrue(coverage.find(),
                "the coverage summary must carry the delivered-tree row that publishes the breakdown");
        assertEquals(rows, Integer.parseInt(coverage.group(1)),
                "the delivered-tree row must state the number of backward rows");
        assertEquals(rows, Integer.parseInt(coverage.group(3)),
                "the delivered-tree row must sum to the number of backward rows");

        Map<String, Integer> published = new LinkedHashMap<>();
        for (String entry : coverage.group(2).split(",")) {
            Matcher tally = Pattern.compile("\\s*(\\d+) (.+?)s?\\s*").matcher(entry);
            assertTrue(tally.matches(), "each breakdown entry reads \"<count> <label>\": " + entry);
            published.merge(tally.group(2).trim().toLowerCase(Locale.ROOT),
                    Integer.valueOf(tally.group(1)), Integer::sum);
        }
        Map<String, Integer> expected = new LinkedHashMap<>();
        measured.forEach((label, count) ->
                expected.merge(label.toLowerCase(Locale.ROOT).replace(", ", " "), count,
                        Integer::sum));
        assertEquals(expected, published,
                "the published breakdown has to be the count of each label the rows below carry."
                        + " Measured " + expected + " against published " + published);
        assertEquals(rows, published.values().stream().mapToInt(Integer::intValue).sum(),
                "the breakdown must sum to the row count");
    }

    /**
     * Returns the provenance cells of the backward section that cite no source member.
     *
     * @param backward the backward section
     * @return each cell, trimmed, in row order
     */
    private static List<String> withoutSourceCitation(String backward) {
        List<String> cells = new ArrayList<>();
        for (String line : backward.split("\\R")) {
            if (!BACKWARD_ROW.matcher(line).find()) {
                continue;
            }
            String[] columns = line.split("\\|");
            if (columns.length > 2 && !columns[2].contains("app/")) {
                cells.add(columns[2].trim());
            }
        }
        return cells;
    }


    /**
     * Requires every Mermaid figure of one document to be titled, legended and referenced in prose.
     *
     * <p>Rule 2 asks for three things around a diagram, and counting fenced blocks measures none of
     * them. A review found the event-flow guard asserting only that three blocks existed, which would
     * pass against three untitled diagrams with no legend and no sentence pointing at them — the
     * state Rule 2 exists to prevent. This checks each of the three properties against the figure it
     * belongs to:</p>
     *
     * <ul>
     *   <li>a caption of the form {@code **Figure N — title**} above the block, whose title is long
     *       enough to describe the figure rather than restate its number;</li>
     *   <li>a {@code **Legend**} block immediately after it, so a reader decoding a shape does not
     *       have to scroll to find what the shapes mean;</li>
     *   <li>at least one sentence outside the caption that names the figure, so no diagram is
     *       dropped in unannounced.</li>
     * </ul>
     *
     * <p>Figure numbers have to run from one without gaps or repeats, because a prose reference to
     * "Figure 3" is ambiguous when two figures claim the number and dangling when none does.</p>
     *
     * @param document the whole document text
     * @param name     the file name, for a failure message
     * @param expected how many figures the document is required to carry
     */
    private static void assertFiguresAreTitledLegendedAndReferenced(String document, String name,
            int expected) {
        List<String> lines = List.of(document.split("\\R", -1));
        List<Integer> blockStarts = new ArrayList<>();
        List<Integer> blockEnds = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).strip().equals(MERMAID_FENCE)) {
                continue;
            }
            int close = index + 1;
            while (close < lines.size() && !lines.get(close).strip().equals(FENCE)) {
                close++;
            }
            assertTrue(close < lines.size(), name + " leaves a Mermaid block unclosed at line "
                    + (index + 1));
            blockStarts.add(index);
            blockEnds.add(close);
            index = close;
        }

        assertEquals(expected, blockStarts.size(),
                name + " has to carry " + expected + " Mermaid figures");

        List<Integer> numbers = new ArrayList<>();
        for (int figure = 0; figure < blockStarts.size(); figure++) {
            int start = blockStarts.get(figure);
            int end = blockEnds.get(figure);

            Matcher caption = null;
            for (int above = start - 1; above >= 0 && above >= start - CAPTION_SEARCH_LINES;
                    above--) {
                Matcher candidate = FIGURE_CAPTION.matcher(lines.get(above));
                if (candidate.matches()) {
                    caption = candidate;
                    break;
                }
            }
            assertTrue(caption != null,
                    name + " has a Mermaid block at line " + (start + 1) + " with no"
                            + " `**Figure N — title**` caption above it. Rule 2 requires a"
                            + " descriptive title on every diagram");
            int number = Integer.parseInt(caption.group(1));
            String title = caption.group(2).strip();
            numbers.add(number);
            assertTrue(title.length() >= MINIMUM_TITLE_LENGTH,
                    name + " Figure " + number + " is captioned \"" + title + "\", which is too"
                            + " short to describe the diagram. A title is what a reader who skips"
                            + " the diagram takes away from it");

            boolean legended = false;
            for (int below = end + 1; below < lines.size() && below <= end + LEGEND_SEARCH_LINES;
                    below++) {
                if (lines.get(below).strip().isEmpty()) {
                    continue;
                }
                legended = lines.get(below).strip().startsWith(LEGEND_HEADING);
                break;
            }
            assertTrue(legended,
                    name + " Figure " + number + " has no `" + LEGEND_HEADING + "` immediately"
                            + " after its block. Rule 2 requires a legend adjacent to the figure it"
                            + " explains, not collected elsewhere in the document");

            boolean referenced = false;
            for (int index = 0; index < lines.size() && !referenced; index++) {
                if (insideAnyBlock(index, blockStarts, blockEnds)) {
                    continue;
                }
                String line = lines.get(index);
                if (FIGURE_CAPTION.matcher(line).matches()) {
                    continue;
                }
                referenced = line.contains("Figure " + number);
            }
            assertTrue(referenced,
                    name + " never refers to Figure " + number + " in prose, so the diagram is"
                            + " dropped in unannounced. Rule 2 requires each figure to be"
                            + " referenced by name in the text around it");
        }

        assertEquals(java.util.stream.IntStream.rangeClosed(1, expected).boxed().toList(), numbers,
                name + " numbers its figures " + numbers + ". They have to run from one in order, or"
                        + " a prose reference names two figures or none");
    }

    /** Reports whether one line sits inside any fenced block. */
    private static boolean insideAnyBlock(int line, List<Integer> starts, List<Integer> ends) {
        for (int block = 0; block < starts.size(); block++) {
            if (line >= starts.get(block) && line <= ends.get(block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the named indexes the six delivered schemas leave behind.
     *
     * @return the number of named indexes across all six service schemas
     */
    private static int migratedIndexCount() {
        int total = 0;
        for (String service : SERVICES) {
            total += migratedIndexCount(service);
        }
        return total;
    }

    /**
     * Counts the named indexes one delivered schema leaves behind.
     *
     * <p>Read out of that service's {@code src/main/resources/db/migration} directory in version
     * order with {@code DROP INDEX} and {@code DROP TABLE} applied, which is what the appendix of
     * {@code data-model.md} claims to enumerate. Measuring it here is what keeps that claim honest:
     * the figure was a literal in this test and drifted six behind the schemas without failing
     * anything. {@code DROP TABLE} matters as much as {@code DROP INDEX}, because a migration that
     * drops a table takes that table's indexes with it and writes no {@code DROP INDEX} for them.
     *
     * @param service the service module directory name
     * @return the number of named indexes that service's schema holds
     */
    private static int migratedIndexCount(String service) {
        Path migrations = platformDirectory().resolve("services").resolve(service)
                .resolve("src/main/resources/db/migration");
        List<Path> ordered;
        try (Stream<Path> files = Files.list(migrations)) {
            ordered = files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(DocumentationContractTest::migrationVersion))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + migrations, unreadable);
        }

        Map<String, String> present = new LinkedHashMap<>();
        for (Path migration : ordered) {
            String sql = read(migration);
            Matcher createdOn = MIGRATED_CREATE_INDEX_ON.matcher(sql);
            while (createdOn.find()) {
                present.put(createdOn.group(1), createdOn.group(2).toLowerCase(Locale.ROOT));
            }
            Matcher created = MIGRATED_CREATE_INDEX.matcher(sql);
            while (created.find()) {
                present.putIfAbsent(created.group(1), "");
            }
            Matcher dropped = MIGRATED_DROP_INDEX.matcher(sql);
            while (dropped.find()) {
                present.remove(dropped.group(1));
            }
            Matcher droppedTable = MIGRATED_DROP_TABLE.matcher(sql);
            while (droppedTable.find()) {
                String table = droppedTable.group(1).toLowerCase(Locale.ROOT);
                present.values().removeIf(table::equals);
            }
        }
        return present.size();
    }

    /**
     * Reads the leading version number of one migration file name, so files sort in applied order.
     *
     * @param migration the migration path
     * @return the integer that follows the leading {@code V}
     */
    private static int migrationVersion(Path migration) {
        Matcher version = MIGRATION_VERSION.matcher(migration.getFileName().toString());
        assertTrue(version.find(), "a migration file name must open with a version: " + migration);
        return Integer.parseInt(version.group(1));
    }

    /**
     * Holds every backward row's Source provenance cell to the members its own file really names.
     *
     * <p>Row presence was already asserted above, and a review found that insufficient: 59 rows
     * passed it while stating the wrong provenance. Seven said no source was cited by a file that
     * cites one, eight named a member absent from the file, one carried a path with a trailing full
     * stop inside the backticks, and the {@code and N more} counts disagreed with the sets they
     * summarise. Every one of those is a Rule 1 defect rather than a typographical one, because the
     * column is published as read out of the code.
     *
     * <p>The expected cell is therefore recomputed here from the bytes of the file each row names:
     * every {@code app/<dir>/<member>} reference the file carries, discarded unless it resolves to a
     * real file, sorted, then rendered as up to four backticked paths followed by
     * {@code and N more}. A cell may carry one of two classifying prefixes and a row naming nothing
     * one of four wordings; both are accepted, and the citation list behind them is what this test
     * compares. Ordering is part of the contract, because two cells that name the same members in
     * different orders cannot both have been generated from the file.
     */
    @Test
    @DisplayName("every backward provenance cell names exactly the members its own file cites")
    void everyBackwardProvenanceCellNamesTheMembersItsFileCites() {
        String matrix = read(docsDirectory().resolve("traceability-matrix.md"));
        String backward = section(matrix, "## Backward: every target path",
                "### Backward closure arithmetic");

        List<String> defects = new ArrayList<>();
        int naming = 0;
        int silent = 0;
        Matcher rows = BACKWARD_PROVENANCE_ROW.matcher(backward);
        while (rows.find()) {
            String target = rows.group(1);
            String cell = rows.group(2);
            List<String> expected = citedSourceMembers(repositoryRoot().resolve(target));
            if (expected.isEmpty()) {
                silent++;
                if (!cell.startsWith("None cited")) {
                    defects.add(target + " cites no member, so its cell must open None cited: "
                            + cell);
                }
                continue;
            }
            naming++;
            if (cell.startsWith("None cited")) {
                defects.add(target + " cites " + expected.size()
                        + " member(s) and its cell claims none: " + expected);
                continue;
            }
            String body = cell.contains(": `app/")
                    ? cell.substring(cell.indexOf(": `app/") + 2)
                    : cell;
            assertEquals(expected, expected.stream().sorted().toList(),
                    "the expected set is built sorted");
            List<String> shown = expected.subList(0, Math.min(4, expected.size()));
            String wanted = shown.stream().map(member -> '`' + member + '`')
                    .collect(java.util.stream.Collectors.joining(", "));
            if (expected.size() > 4) {
                wanted += " and " + (expected.size() - 4) + " more";
            }
            if (!body.equals(wanted)) {
                defects.add(target + "\n      cell: " + body + "\n      file: " + wanted);
            }
        }

        assertEquals(deliveredPaths().size(), naming + silent,
                "every backward row must be read, and there is one row per delivered path. A literal"
                        + " here went stale twice as the tree grew, so the count is measured");
        assertTrue(defects.isEmpty(),
                "a provenance cell that disagrees with its own file is a Rule 1 defect. "
                        + defects.size() + " row(s): " + String.join("\n   ", defects));
        assertTrue(matrix.contains("**" + naming + " of the " + (naming + silent)
                        + " paths name at least one member under `app/`, and the remaining " + silent
                        + " name none.**"),
                "the published split must be the measured one: " + naming + " and " + silent);
    }

    /**
     * Returns the source members one delivered file names, sorted, with unresolvable paths dropped.
     *
     * @param file the delivered file to read
     * @return the distinct {@code app/} members it names
     */
    private static List<String> citedSourceMembers(Path file) {
        String text = read(file);
        java.util.SortedSet<String> members = new java.util.TreeSet<>();
        Matcher references = SOURCE_MEMBER_REFERENCE.matcher(text);
        while (references.find()) {
            String reference = references.group();
            while (!reference.isEmpty()
                    && ".,;:)]}\"'".indexOf(reference.charAt(reference.length() - 1)) >= 0) {
                reference = reference.substring(0, reference.length() - 1);
            }
            String member = reference.substring(reference.lastIndexOf('/') + 1);
            if (member.indexOf('.') < 0) {
                continue;
            }
            if (Files.isRegularFile(repositoryRoot().resolve(reference))) {
                members.add(reference);
            }
        }
        return List.copyOf(members);
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

    /** One document with its case folded, so a sentence-initial count word still matches. */
    private static String folded(String document) {
        return document.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Counts the matches of one pattern across every service's main sources.
     *
     * <p>A pattern rather than a substring, because the fraud service's consumer configuration
     * names {@code @KafkaListener} inside a Javadoc sentence and a substring count reads that
     * mention as a twelfth listener.
     */
    private static int occurrencesAcrossServiceSources(Pattern pattern) {
        int total = 0;
        for (String service : SERVICES) {
            for (Path source : mainSourcesOf(service)) {
                total += (int) pattern.matcher(read(source)).results().count();
            }
        }
        return total;
    }

    /** Counts the services whose main sources carry one token at least once. */
    private static int servicesContaining(String token) {
        int services = 0;
        for (String service : SERVICES) {
            for (Path source : mainSourcesOf(service)) {
                if (read(source).contains(token)) {
                    services++;
                    break;
                }
            }
        }
        return services;
    }

    /** Every Java source under one service's main tree. */
    private static List<Path> mainSourcesOf(String service) {
        return MAIN_SOURCES.computeIfAbsent(service, DocumentationContractTest::walkMainSourcesOf);
    }

    /**
     * Walks one service's main tree once, for {@link #mainSourcesOf} to remember.
     *
     * @param service the service directory name
     * @return every Java source under its main tree
     */
    private static List<Path> walkMainSourcesOf(String service) {
        Path tree = platformDirectory().resolve("services").resolve(service)
                .resolve("src/main/java");
        try (Stream<Path> walk = Files.walk(tree)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + tree, unreadable);
        }
    }

    /**
     * The topic values at least one delivered listener subscribes to.
     *
     * <p>Each listener names a property leaf under {@code carddemo.kafka.topics}, and each service
     * declares that leaf's value in its own {@code application.yml}. The environment variable is
     * required to begin {@code TOPIC_} so the sibling {@code groups} block, whose leaves carry the
     * same names, cannot answer for a topic. The quotes around a value are optional in this tree,
     * and both spellings ship, so the pattern accepts either.
     */
    private static Set<String> consumedTopicValues() {
        Set<String> leaves = new LinkedHashSet<>();
        for (String service : SERVICES) {
            for (Path source : mainSourcesOf(service)) {
                Matcher subscription = LISTENER_TOPIC_PROPERTY.matcher(read(source));
                while (subscription.find()) {
                    leaves.add(subscription.group(1));
                }
            }
        }
        Set<String> values = new LinkedHashSet<>();
        for (String leaf : leaves) {
            String value = null;
            for (String service : SERVICES) {
                Matcher declaration = Pattern
                        .compile("(?m)^\\s+" + Pattern.quote(leaf)
                                + ": \"?\\$\\{TOPIC_[A-Z_]+:([^}\"]+)}\"?")
                        .matcher(read(platformDirectory().resolve("services").resolve(service)
                                .resolve("src/main/resources/application.yml")));
                if (declaration.find()) {
                    value = declaration.group(1);
                    break;
                }
            }
            assertTrue(value != null,
                    "a listener subscribes to carddemo.kafka.topics." + leaf
                            + " and no service declares its value");
            values.add(value);
        }
        return values;
    }

    /** Names the services whose listeners read one topic value, for a failure message. */
    private static String servicesListeningOn(String topic) {
        List<String> listening = new ArrayList<>();
        for (String service : SERVICES) {
            Path yml = platformDirectory().resolve("services").resolve(service)
                    .resolve("src/main/resources/application.yml");
            for (Path source : mainSourcesOf(service)) {
                Matcher subscription = LISTENER_TOPIC_PROPERTY.matcher(read(source));
                boolean reads = false;
                while (subscription.find()) {
                    reads = Pattern.compile("(?m)^\\s+" + Pattern.quote(subscription.group(1))
                                    + ": \"?\\$\\{TOPIC_[A-Z_]+:" + Pattern.quote(topic) + "}\"?")
                            .matcher(read(yml)).find();
                    if (reads) {
                        break;
                    }
                }
                if (reads) {
                    listening.add(service);
                    break;
                }
            }
        }
        return String.join(" and ", listening);
    }

    /** Every event record and every schema document the contract library publishes. */
    private static List<Path> contractDocuments() {
        Path library = platformDirectory().resolve("libs/event-contracts/src/main");
        try (Stream<Path> walk = Files.walk(library)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".json");
                    })
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + library, unreadable);
        }
    }

    /** The number of distinct source topics the provisioning program gives a dead-letter twin. */
    private static int sourceSpecificDeadLetterTopics() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        java.util.Set<String> twins = new LinkedHashSet<>();
        Matcher twin = DEAD_LETTER_TWIN.matcher(compose);
        while (twin.find()) {
            twins.add(twin.group());
        }
        return twins.size();
    }

    /**
     * Every shipped text file a source locator can appear in.
     *
     * <p>Three roots are read: the platform tree, the workflow directory beside it, and the
     * repository guide this engagement updates. A path holding any {@link #UNSHIPPED_DIRECTORIES}
     * element is skipped wherever that element sits, so build output under any module drops out
     * without naming each module here.
     */
    private static List<Path> shippedTextFiles() {
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(platformDirectory(), repositoryRoot().resolve(".github"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(DocumentationContractTest::isShippedTextFile)
                        .sorted()
                        .forEach(files::add);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot walk " + root, unreadable);
            }
        }
        files.add(repositoryRoot().resolve("README.md"));
        return files;
    }

    /**
     * Whether one file is shipped text the locator scan reads.
     *
     * @param file a regular file under one of the scanned roots
     * @return true when no path element is unshipped and the name carries a text suffix or is one of
     *         the extensionless names this platform ships
     */
    private static boolean isShippedTextFile(Path file) {
        for (Path element : repositoryRoot().relativize(file)) {
            if (UNSHIPPED_DIRECTORIES.contains(element.toString())) {
                return false;
            }
        }
        String name = file.getFileName().toString();
        if (SHIPPED_TEXT_NAMES.contains(name)) {
            return true;
        }
        for (String suffix : SHIPPED_TEXT_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lines in one cited source member.
     *
     * @param file a member under {@code app/} a locator names
     * @return its line count, counted once and remembered
     */
    private static long lineCountOf(Path file) {
        return SOURCE_LINE_COUNTS.computeIfAbsent(file, path -> {
            try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
                return lines.count();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot read " + path, unreadable);
            }
        });
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
        return FILE_CONTENT.computeIfAbsent(file, path -> {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot read " + path, unreadable);
            }
        });
    }
}