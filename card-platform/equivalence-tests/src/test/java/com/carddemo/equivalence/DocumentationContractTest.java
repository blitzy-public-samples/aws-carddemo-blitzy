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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            Map.entry(14, "fourteen"));

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

    /** A backward row: a leading cell holding one repository-relative path in backticks. */
    private static final Pattern BACKWARD_ROW =
            Pattern.compile("(?m)^\\| `([^`]+)` \\|");

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

        // The copybook spells 980 area-code literals but declares only 490 distinct codes: all 490
        // under VALID-PHONE-AREA-CODE, then 410 again as VALID-GENERAL-PURP-CODE and 80 as
        // VALID-EASY-RECOG-AREA-CODE. us_phone_area_code holds 490 rows, so the document must report
        // the distinct count and the literal count rather than conflating them.
        assertTrue(model.contains("490 distinct telephone area codes"));
        assertTrue(model.contains("980"));

        // The operational appendix carries every index and every named constraint, as tables. The
        // six diagram assertions above are what keeps it from being drawn as a seventh diagram.
        assertTrue(model.contains("## Operational DDL appendix"));
        assertTrue(model.contains("45 named indexes and 117 named constraints"));
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
                    assertFalse(readme.contains(other.getValue() + " migrations"),
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
     * <p>The disk anchor is deliberately narrow. Enumerating the whole tree would have to reproduce
     * every ignore rule the working copy applies, so instead every row's path must exist and every
     * schema migration on disk must own a row. A migration is unambiguous, is never ignored, and is
     * the artifact a correction lands as when an applied file cannot be edited, so it is the path
     * most likely to be added without the matrix following.
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
                "reports " + counted + " tracked paths, and the tables below carry " + counted
                        + " rows.",
                "**Backward closure:** " + counted + " rows against " + counted
                        + " tracked paths,")) {
            assertTrue(matrix.contains(statement),
                    "every published statement of the count must read " + counted
                            + ", so this one has to be present: " + statement);
        }

        for (String path : paths) {
            assertTrue(Files.exists(repositoryRoot().resolve(path)),
                    "the matrix carries a row for missing " + path);
        }

        for (String service : SERVICES) {
            Path migrations = platformDirectory().resolve("services").resolve(service)
                    .resolve("src/main/resources/db/migration");
            try (Stream<Path> files = Files.list(migrations)) {
                files.filter(Files::isRegularFile)
                        .map(file -> "card-platform/services/" + service
                                + "/src/main/resources/db/migration/" + file.getFileName())
                        .forEach(expected -> assertTrue(paths.contains(expected),
                                expected + " is delivered and carries no backward row"));
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot list " + migrations, unreadable);
            }
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