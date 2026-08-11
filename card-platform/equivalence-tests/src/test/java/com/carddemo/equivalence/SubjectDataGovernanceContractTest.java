package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the platform's two subject-data commitments to the tree that has to keep them.
 *
 * <p>The first commitment is an exception. {@code card.card_verification_value} holds three
 * cleartext digits of authentication data that no delivered business rule reads, and a security
 * review asked for the column to be dropped. Agent Action Plan sections 0.4.1 and 0.6.4 require this
 * platform to store the value and never emit it, and section 0.2.2 excludes the controls that would
 * remove it, so the review's second route was taken: the column stays under a formal exception
 * carrying encryption at rest, minimal access, build-enforced audit and a published destruction
 * procedure. An exception nobody checks is a paragraph, so the tests below read the migration that
 * records it, the artifacts it names, and the procedure it points at.
 *
 * <p>The second commitment is a procedure. The same review found that no document put the platform's
 * catalogue classifications together, no procedure reached the stores in order, and nothing said
 * what a retained broker record or a backup meant for a request. {@code docs/data-model.md} now
 * carries the inventory, the four-hop lookup, the export rules and the ordered erasure, and four
 * migrations point the catalogue at it. The inventory is the part that goes stale silently: a table
 * added to any schema with a subject-data classification and no row in that document is a store a
 * request would miss. This class computes the inventory from the migrations and compares it.
 *
 * <p>Every test reads files. None opens a database connection, starts a context or sends a request,
 * which is what lets it run in the same fork as the rest of the suite.
 */
@DisplayName("Subject data governance: the published exception, its readers, and the procedure")
class SubjectDataGovernanceContractTest {

    /** Modules whose migrations are read, each against the schema its migrations land in. */
    private static final Map<String, String> SCHEMA_BY_MODULE = Map.of(
            "account-service", "account_service",
            "card-service", "card_service",
            "authorization-service", "authorization_service",
            "ledger-posting-service", "ledger_service",
            "fraud-detection-service", "fraud_service",
            "notification-service", "notification_service");

    /** One {@code COMMENT ON TABLE} statement, read to the closing quote rather than to a line end. */
    private static final Pattern TABLE_COMMENT =
            Pattern.compile("COMMENT ON TABLE (\\w+) IS\\s*'([^']*)'", Pattern.DOTALL);

    /** A table a migration creates. */
    private static final Pattern TABLE_CREATE =
            Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?(\\w+)");

    /** A table a later migration removes, so its comment goes with it. */
    private static final Pattern TABLE_DROP =
            Pattern.compile("DROP TABLE (?:IF EXISTS )?(\\w+)");

    /** The classification token every catalogue comment on this platform carries. */
    private static final Pattern PERSONAL_DATA = Pattern.compile("personal_data=(\\w+)");

    /** Classification values that put a table inside a subject request. */
    private static final Set<String> SUBJECT_CLASSES = Set.of("yes", "pseudonymous");

    /** The Flyway version a migration file name declares. */
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");

    /** One row of the subject-data inventory: schema in the first cell, table in the second. */
    private static final Pattern INVENTORY_ROW =
            Pattern.compile("^\\|\\s*`(\\w+)`\\s*\\|\\s*`(\\w+)`\\s*\\|\\s*(\\S+)\\s*\\|");

    /** The document carrying the procedure, relative to {@code docs}. */
    private static final String DATA_MODEL = "data-model.md";

    /** The heading the four migrations and the card guide name. */
    private static final String PROCEDURE_HEADING =
            "Subject data: purpose, retention, export and erasure";

    /** The heading the exception names as the home of the destruction procedure. */
    private static final String DESTRUCTION_HEADING = "Stored card verification value";

    /** The column the exception is about. */
    private static final String EXCEPTION_COLUMN = "card_verification_value";

    /** The migration that records the exception. */
    private static final String EXCEPTION_MIGRATION =
            "services/card-service/src/main/resources/db/migration/"
                    + "V11__card_verification_value_exception.sql";

    /** The four migrations that point the catalogue at the procedure. */
    private static final List<String> PROCEDURE_MIGRATIONS = List.of(
            EXCEPTION_MIGRATION,
            "services/authorization-service/src/main/resources/db/migration/"
                    + "V23__subject_request_procedure.sql",
            "services/account-service/src/main/resources/db/migration/"
                    + "V12__subject_request_procedure.sql",
            "services/notification-service/src/main/resources/db/migration/"
                    + "V9__subject_request_procedure.sql");

    /** Decision-log headings the artifacts of this phase name, each of which has to exist. */
    private static final List<String> DECISION_HEADINGS = List.of(
            "The stored card verification value keeps a formal exception",
            "A subject request is a procedure before it is an orchestration",
            "Encryption at rest is a declared requirement and a one-command overlay");

    /** The four controls the exception carries, in the words the migration uses. */
    private static final List<String> CONTROLS = List.of(
            "Encryption at rest", "Minimal access", "Audit", "Destruction");

    /**
     * Evidence the exception offers: the text it names it by, against the path that has to exist.
     *
     * <p>A basename would not do. The exception names the two claims by the folder that holds them
     * and the overlay by its directory, which is how a reader follows either without a filename. Two
     * entries therefore share one naming token, which is why this is a list rather than a map.
     */
    private static final List<Evidence> EXCEPTION_EVIDENCE = List.of(
            new Evidence("card-platform/deploy/k8s", "deploy/k8s/10-kafka.yaml"),
            new Evidence("card-platform/deploy/k8s", "deploy/k8s/20-postgres.yaml"),
            new Evidence("deploy/k8s/overlays/encrypted-storage",
                    "deploy/k8s/overlays/encrypted-storage/kustomization.yaml"),
            new Evidence("card-platform/deploy/k8s/README.md", "deploy/k8s/README.md"),
            new Evidence("entity/CardEntity",
                    "services/card-service/src/main/java/com/carddemo/card/entity/CardEntity.java"),
            new Evidence("CardholderDataExposureTest",
                    "services/card-service/src/test/java/com/carddemo/card/entity/"
                            + "CardholderDataExposureTest.java"),
            new Evidence("card-platform/services/card-service/README.md",
                    "services/card-service/README.md"),
            new Evidence("business-rule-flags.md", "docs/business-rule-flags.md"),
            new Evidence("card-platform/docs/suggested-next-tasks.md",
                    "docs/suggested-next-tasks.md"),
            new Evidence("card-platform/docs/data-model.md", "docs/" + DATA_MODEL));

    /**
     * One piece of evidence the exception offers.
     *
     * @param named the text the exception names it by
     * @param path  the file that has to exist, relative to the platform root
     */
    private record Evidence(String named, String path) {
    }

    /** Both volumes that hold subject data, and therefore both claims that declare encryption. */
    private static final List<String> PERSISTENT_CLAIMS =
            List.of("deploy/k8s/10-kafka.yaml", "deploy/k8s/20-postgres.yaml");

    /** The annotation a claim carries to declare the requirement. */
    private static final String ENCRYPTION_ANNOTATION = "carddemo.io/requires-encryption-at-rest";

    /** The storage class the overlay binds. */
    private static final String ENCRYPTED_STORAGE_CLASS = "carddemo-encrypted";

    /** Directories no census descends into: build output carries no committed text. */
    private static final String BUILD_OUTPUT = "/target/";

    @Nested
    @DisplayName("The exception is published, and every artifact it names exists")
    class PublishedException {

        @Test
        @DisplayName("the migration records the four controls, the plan sections and the review trigger")
        void theExceptionRecordsItsControlsAndItsTrigger() {
            String migration = normalize(read(platformRoot().resolve(EXCEPTION_MIGRATION)));

            for (String control : CONTROLS) {
                assertTrue(migration.contains(control + "."),
                        "the exception has to name the control \"" + control + "\", because a control"
                                + " nobody stated is a control nobody owns");
            }
            for (String section : List.of("0.4.1", "0.6.4", "0.2.2")) {
                assertTrue(migration.contains(section),
                        "the exception rests on Agent Action Plan section " + section
                                + ", so it has to cite it");
            }
            assertTrue(migration.contains("business-rule-flags.md"),
                    "the exception is open until the register entry is answered, so it has to name the"
                            + " register");
            assertTrue(migration.contains("card-platform/docs/decision-log.md"),
                    "Rule 1 puts the rationale in the log, and the file has to point at it");
            assertTrue(migration.contains(DESTRUCTION_HEADING),
                    "the destruction control is a procedure, so the exception has to name where it is");
            assertTrue(migration.contains(
                            SubjectDataGovernanceContractTest.class.getSimpleName()),
                    "the audit control names this class, so a reader can find what enforces it");
        }

        @Test
        @DisplayName("every artifact the exception offers as evidence is in the tree")
        void everyArtifactTheExceptionNamesExists() {
            String migration = normalize(read(platformRoot().resolve(EXCEPTION_MIGRATION)));
            List<String> missing = new ArrayList<>();
            for (Evidence evidence : EXCEPTION_EVIDENCE) {
                String basename = evidence.path()
                        .substring(evidence.path().lastIndexOf('/') + 1);
                boolean stated = migration.contains(evidence.named())
                        || migration.contains(basename);
                if (!stated) {
                    missing.add("not named: " + evidence.path());
                } else if (!Files.exists(platformRoot().resolve(evidence.path()))) {
                    missing.add("named and absent: " + evidence.path());
                }
            }
            assertEquals(List.of(), missing,
                    "an exception that offers evidence has to offer evidence that exists: " + missing);
        }

        @Test
        @DisplayName("the three decision-log headings this phase's artifacts cite all resolve")
        void everyCitedDecisionHeadingResolves() {
            String log = read(platformRoot().resolve("docs/decision-log.md"));
            List<String> missing = DECISION_HEADINGS.stream()
                    .filter(heading -> !log.contains("### " + heading))
                    .toList();
            assertEquals(List.of(), missing,
                    "a shipped file names each of these as a section of the decision log: " + missing);
        }

        @Test
        @DisplayName("the catalogue comment on the column says the value is read by nothing")
        void theColumnCommentStatesTheAccessRule() {
            Map<String, String> comments = columnComments("card-service");
            String comment = comments.get("card." + EXCEPTION_COLUMN);
            assertTrue(comment != null,
                    "the column has to carry a catalogue comment, because the catalogue is what an"
                            + " operator reads");
            assertTrue(comment.contains("authentication_data=yes"),
                    "the comment has to classify the value as authentication data: " + comment);
            assertTrue(comment.toLowerCase(Locale.ROOT).contains("no application path")
                            || comment.toLowerCase(Locale.ROOT).contains("no reader"),
                    "the comment has to state that nothing reads the column: " + comment);
        }
    }

    @Nested
    @DisplayName("The reader inventory the exception claims is the one the tree has")
    class ReaderInventory {

        /**
         * Files under {@code src/main} that may name the column, each with the reason it does.
         *
         * <p>A file naming the column is not automatically a reader. The migrations declare it, the
         * entity maps it without exposing it, and four files name it in prose to say that nothing
         * carries it. Any other file naming it is a reader this platform did not have, which is what
         * this map turns into a failure.
         */
        private final Map<String, String> permitted = new TreeMap<>(Map.of(
                "services/card-service/src/main/resources/db/migration/V1__schema.sql",
                "declares the column and its digit check",
                "services/card-service/src/main/resources/db/migration/V2__seed.sql",
                "loads the fifty fixture values",
                "services/card-service/src/main/resources/db/migration/"
                        + "V4__subject_request_posture.sql",
                "states that dropping the column is a schema change rather than a request",
                EXCEPTION_MIGRATION,
                "records the exception",
                "services/card-service/src/main/java/com/carddemo/card/entity/CardEntity.java",
                "maps the column, with no accessor and no rendering",
                "services/card-service/src/main/java/com/carddemo/card/config/SecurityConfig.java",
                "names it in prose, as data no route returns",
                "services/card-service/src/main/resources/openapi.yaml",
                "names it in a comment stating that no schema declares it",
                "libs/event-contracts/src/main/java/com/carddemo/events/serde/"
                        + "SensitiveEventProperties.java",
                "names it as a screened property name"));

        @Test
        @DisplayName("no shipped source outside the permitted set names the column")
        void noUnexpectedShippedSourceNamesTheColumn() {
            List<String> unexpected = new ArrayList<>();
            for (Path file : mainSources()) {
                String relative = relative(file);
                if (permitted.containsKey(relative)) {
                    continue;
                }
                if (read(file).contains(EXCEPTION_COLUMN)) {
                    unexpected.add(relative);
                }
            }
            assertEquals(List.of(), unexpected,
                    "the exception rests on nothing reading the column, so a new file naming it is a"
                            + " reader the exception did not account for: " + unexpected);
        }

        @Test
        @DisplayName("every file in the permitted set still names the column, so the set has no dead entry")
        void everyPermittedFileStillNamesTheColumn() {
            List<String> stale = new ArrayList<>();
            for (Map.Entry<String, String> entry : permitted.entrySet()) {
                Path file = platformRoot().resolve(entry.getKey());
                if (!Files.isRegularFile(file)) {
                    stale.add("absent: " + entry.getKey());
                } else if (!read(file).contains(EXCEPTION_COLUMN)) {
                    stale.add("no longer names it: " + entry.getKey());
                }
            }
            assertEquals(List.of(), stale,
                    "a permitted entry that no longer applies hides the next reader: " + stale);
        }

        @Test
        @DisplayName("no event schema of any version declares a property for the value")
        void noEventSchemaDeclaresTheValue() {
            List<String> offending = new ArrayList<>();
            for (Path schema : schemaDocuments()) {
                String text = read(schema);
                for (String spelling : List.of("\"cardVerificationValue\"", "\"card_verification_value\"",
                        "\"cvv\"", "\"CVV\"")) {
                    if (text.contains(spelling)) {
                        offending.add(relative(schema) + " declares " + spelling);
                    }
                }
            }
            assertEquals(List.of(), offending,
                    "a released document declaring the value would put it on a topic for ever: "
                            + offending);
        }

        @Test
        @DisplayName("no deployment artifact names the column")
        void noDeploymentArtifactNamesTheColumn() {
            List<Path> artifacts = new ArrayList<>(List.of(
                    platformRoot().resolve("docker-compose.yml"),
                    platformRoot().resolve(".env.example")));
            artifacts.addAll(filesUnder(platformRoot().resolve("deploy"), ".yaml"));
            List<String> offending = artifacts.stream()
                    .filter(Files::isRegularFile)
                    .filter(file -> read(file).contains(EXCEPTION_COLUMN))
                    .map(SubjectDataGovernanceContractTest::relative)
                    .toList();
            assertEquals(List.of(), offending,
                    "a configuration naming the column is a path to it: " + offending);
        }
    }

    @Nested
    @DisplayName("The destruction procedure names what a deployment has to change")
    class DestructionProcedure {

        @Test
        @DisplayName("the card guide carries the procedure under the heading the exception names")
        void theProcedureIsWhereTheExceptionSaysItIs() {
            String section = cardGuideSection();
            assertTrue(!section.isBlank(),
                    "the exception names \"" + DESTRUCTION_HEADING + "\" in the card guide, so the"
                            + " section has to exist");
            assertTrue(section.contains("V12__card_verification_value_dropped.sql"),
                    "the procedure has to name the migration a deployment writes");
            assertTrue(section.contains("ALTER TABLE card DROP COLUMN " + EXCEPTION_COLUMN),
                    "the procedure has to carry the statement, not a description of it");
            assertTrue(section.contains("checksum"),
                    "the procedure has to say why V1, V2 and V11 are not edited");
        }

        @Test
        @DisplayName("every database object the procedure drops is one a migration declares")
        void everyObjectTheProcedureDropsExists() {
            String section = cardGuideSection();
            String migrations = normalize(migrationsOf("card-service").stream()
                    .map(SubjectDataGovernanceContractTest::read)
                    .reduce("", (left, right) -> left + " " + right));
            Matcher drops = Pattern
                    .compile("DROP (?:COLUMN|CONSTRAINT|INDEX)\\s+(?:IF EXISTS\\s+)?(\\w+)")
                    .matcher(section);
            List<String> named = new ArrayList<>();
            List<String> invented = new ArrayList<>();
            while (drops.find()) {
                named.add(drops.group(1));
                if (!migrations.contains(drops.group(1))) {
                    invented.add(drops.group(1));
                }
            }
            assertTrue(named.size() >= 2,
                    "the reader found " + named.size() + " objects the procedure drops, so its"
                            + " verdict would mean nothing");
            assertEquals(List.of(), invented,
                    "a procedure that drops an object no migration declares fails when it is run,"
                            + " which is the worst moment to find out: " + invented);
        }

        @Test
        @DisplayName("every artifact the procedure tells a deployment to edit exists today")
        void everyArtifactTheProcedureNamesExists() {
            String section = cardGuideSection();
            Map<String, String> edits = new LinkedHashMap<>();
            edits.put("entity/CardEntity",
                    "services/card-service/src/main/java/com/carddemo/card/entity/CardEntity.java");
            edits.put("entity/CardholderDataExposureTest",
                    "services/card-service/src/test/java/com/carddemo/card/entity/"
                            + "CardholderDataExposureTest.java");
            edits.put("entity/CardEntityMappingTest",
                    "services/card-service/src/test/java/com/carddemo/card/entity/"
                            + "CardEntityMappingTest.java");
            edits.put("domain/CardChangeDetectionTest",
                    "services/card-service/src/test/java/com/carddemo/card/domain/"
                            + "CardChangeDetectionTest.java");
            edits.put("EntitySchemaMappingContractTest",
                    "equivalence-tests/src/test/java/com/carddemo/equivalence/"
                            + "EntitySchemaMappingContractTest.java");
            edits.put("CardSeedEquivalenceTest",
                    "equivalence-tests/src/test/java/com/carddemo/equivalence/"
                            + "CardSeedEquivalenceTest.java");
            edits.put("ApiSurfaceSecurityContractTest",
                    "equivalence-tests/src/test/java/com/carddemo/equivalence/"
                            + "ApiSurfaceSecurityContractTest.java");
            edits.put("docs/data-model.md", "docs/" + DATA_MODEL);
            edits.put("docs/business-rule-flags.md", "docs/business-rule-flags.md");
            edits.put("docs/suggested-next-tasks.md", "docs/suggested-next-tasks.md");
            edits.put("openapi.yaml", "services/card-service/src/main/resources/openapi.yaml");

            List<String> broken = new ArrayList<>();
            edits.forEach((named, path) -> {
                if (!section.contains(named)) {
                    broken.add("procedure does not name " + named);
                } else if (!Files.exists(platformRoot().resolve(path))) {
                    broken.add("procedure names " + named + ", absent at " + path);
                }
            });
            assertEquals(List.of(), broken,
                    "a procedure that names an artifact nobody can find is not a procedure: " + broken);
        }

        @Test
        @DisplayName("the procedure names this class as what holds it, and names the register entry")
        void theProcedureNamesItsOwnEnforcementAndItsTrigger() {
            String section = cardGuideSection();
            assertTrue(section.contains(
                            SubjectDataGovernanceContractTest.class.getSimpleName()),
                    "the procedure is held to the tree by this class, so it has to say so");
            assertTrue(section.contains("business-rule-flags.md"),
                    "the procedure is run when the register entry is answered, so it has to name it");
            assertTrue(section.contains("CardholderDataExposureTest"),
                    "the minimal-access control is asserted inside the service, so the four controls"
                            + " table has to name the class that asserts it");
        }
    }

    @Nested
    @DisplayName("The subject-data inventory is computed from the catalogue, not maintained by hand")
    class SubjectDataInventory {

        @Test
        @DisplayName("every table classified as subject data has a row in the inventory, and no row is invented")
        void theInventoryIsTheCatalogue() {
            Map<String, String> classified = classifiedTables();
            Set<String> expected = new TreeSet<>(classified.keySet());
            Set<String> listed = inventoryRows().keySet();

            assertThat(listed)
                    .as("a classified table with no inventory row is a store a request would miss,"
                            + " and an invented row sends an operator to a table that is not there")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("each inventory row carries the class its catalogue comment declares")
        void everyRowCarriesTheClassTheCatalogueDeclares() {
            Map<String, String> classified = classifiedTables();
            List<String> disagreeing = new ArrayList<>();
            inventoryRows().forEach((table, stated) -> {
                String catalogue = classified.get(table);
                String normalized = stated.replace("`", "");
                if (catalogue != null && !catalogue.equals(normalized)) {
                    disagreeing.add(table + ": catalogue says " + catalogue + ", the document says "
                            + normalized);
                }
            });
            assertEquals(List.of(), disagreeing,
                    "the document and the catalogue cannot disagree about how sensitive a store is: "
                            + disagreeing);
        }

        @Test
        @DisplayName("the section states the figures it lists")
        void theSectionStatesTheFiguresItLists() {
            String section = procedureSection();
            int subject = classifiedTables().size();
            int total = allTables().size();
            String folded = section.toLowerCase(Locale.ROOT);
            assertTrue(folded.contains(numberWord(subject) + " of the platform's "
                            + numberWord(total) + " tables"),
                    "the section lists " + subject + " of " + total + " tables, so it has to say so"
                            + " rather than carrying a figure a later migration made wrong");
            assertTrue(folded.contains("other " + numberWord(total - subject)),
                    "the section has to account for the " + (total - subject) + " tables it omits");
        }

        @Test
        @DisplayName("the ordered erasure reaches every store the inventory names")
        void theErasureReachesEveryStore() {
            String erasure = sectionOf(read(platformRoot().resolve("docs/" + DATA_MODEL)),
                    "### Erasure", "### What a retained broker record");
            List<String> unreached = inventoryRows().keySet().stream()
                    .map(qualified -> qualified.substring(qualified.indexOf('.') + 1))
                    .distinct()
                    .filter(table -> !erasure.contains("`" + table + "`"))
                    .sorted()
                    .toList();
            assertEquals(List.of(), unreached,
                    "a store the inventory names and the erasure does not reach survives the request: "
                            + unreached);
        }

        @Test
        @DisplayName("the section states what a retained broker record and a backup mean")
        void theSectionAnswersTheTwoThingsNoStatementReaches() {
            String section = procedureSection();
            assertTrue(section.contains("KAFKA_LOG_RETENTION_HOURS"),
                    "the broker window is a setting, so the section has to name it rather than"
                            + " describing it");
            assertTrue(section.toLowerCase(Locale.ROOT).contains("backup"),
                    "a restore undoes an erasure, so the section has to say what a backup means");
            assertTrue(section.contains("case management"),
                    "the completion record lives outside this platform, so the section has to say"
                            + " where");
        }
    }

    @Nested
    @DisplayName("The four migrations and the two claims carry what the procedure needs")
    class ShippedPointers {

        @Test
        @DisplayName("each of the four migrations points the catalogue at the procedure")
        void everyMigrationPointsAtTheProcedure() {
            List<String> incomplete = new ArrayList<>();
            for (String migration : PROCEDURE_MIGRATIONS) {
                Path file = platformRoot().resolve(migration);
                if (!Files.isRegularFile(file)) {
                    incomplete.add("absent: " + migration);
                    continue;
                }
                String text = normalize(read(file));
                if (!text.contains(PROCEDURE_HEADING)) {
                    incomplete.add(migration + " names no procedure heading");
                }
                if (!text.contains("card-platform/docs/" + DATA_MODEL)) {
                    incomplete.add(migration + " names no path a reader can follow");
                }
                if (!text.contains("card-platform/docs/decision-log.md")) {
                    incomplete.add(migration + " points at no rationale");
                }
            }
            assertEquals(List.of(), incomplete,
                    "a catalogue comment is where an operator looks first: " + incomplete);
        }

        @Test
        @DisplayName("no catalogue comment still claims that no route exists at all")
        void noCommentStillClaimsTheAbsenceOfAProcedure() {
            List<String> offending = new ArrayList<>();
            for (String module : SCHEMA_BY_MODULE.keySet()) {
                effectiveTableComments(module).forEach((table, comment) -> {
                    boolean deniesEverything = comment.contains("ERASURE OR EXPORT WORKFLOW EXISTS")
                            && !comment.contains("card-platform/docs/" + DATA_MODEL);
                    if (deniesEverything) {
                        offending.add(SCHEMA_BY_MODULE.get(module) + "." + table);
                    }
                });
            }
            assertEquals(List.of(), offending,
                    "a comment that denies the procedure that now exists sends an operator away from"
                            + " it: " + offending);
        }

        @Test
        @DisplayName("both persistent claims declare encryption at rest and the overlay binds it")
        void bothClaimsDeclareEncryptionAndTheOverlayBindsIt() {
            for (String claim : PERSISTENT_CLAIMS) {
                String text = read(platformRoot().resolve(claim));
                assertTrue(text.contains(ENCRYPTION_ANNOTATION),
                        claim + " holds subject data, so its claim has to declare the requirement");
                assertTrue(text.contains("carddemo.io/data-classification"),
                        claim + " has to say what the volume holds, or the requirement is decorative");
                assertTrue(text.contains("carddemo.io/encryption-overlay"),
                        claim + " has to name the overlay that satisfies the requirement");
            }

            Path overlay = platformRoot()
                    .resolve("deploy/k8s/overlays/encrypted-storage/kustomization.yaml");
            String text = read(overlay);
            assertTrue(text.contains("kind: PersistentVolumeClaim"),
                    "the overlay patches by kind, so a third claim is covered without an edit");
            assertTrue(text.contains("/spec/storageClassName") && text.contains(
                            ENCRYPTED_STORAGE_CLASS),
                    "the overlay has to bind a named class, which is the whole of what it does");

            String guide = read(platformRoot().resolve("deploy/k8s/README.md"));
            assertTrue(guide.contains("## Encryption at rest"),
                    "the obligations the overlay does not meet belong in the deployment guide");
            for (String obligation : List.of("backup", "rotat", "restore")) {
                assertTrue(guide.toLowerCase(Locale.ROOT).contains(obligation),
                        "the guide has to state the " + obligation + " obligation a volume does not"
                                + " cover");
            }
        }

        @Test
        @DisplayName("the procedure is a procedure: no route, event or scheduled task erases a subject")
        void nothingAutomatesWhatTheDocumentSaysIsManual() {
            List<String> automated = new ArrayList<>();
            for (Path source : mainSources()) {
                if (!relative(source).endsWith(".java")) {
                    continue;
                }
                String text = read(source);
                for (String claim : List.of("SubjectErasure", "subject-request", "eraseSubject",
                        "exportSubject")) {
                    if (text.contains(claim)) {
                        automated.add(relative(source) + " names " + claim);
                    }
                }
            }
            assertEquals(List.of(), automated,
                    "the document states that no endpoint, event or scheduled task erases or exports a"
                            + " subject. Code that does makes the document wrong: " + automated);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Catalogue reading
    // ----------------------------------------------------------------------------------------

    /** Every table each module's migrations leave behind, against its final table comment. */
    private static Map<String, String> effectiveTableComments(String module) {
        Map<String, String> comments = new TreeMap<>();
        Set<String> present = new TreeSet<>();
        for (Path migration : migrationsOf(module)) {
            String text = read(migration);
            Matcher created = TABLE_CREATE.matcher(text);
            while (created.find()) {
                present.add(created.group(1));
            }
            Matcher dropped = TABLE_DROP.matcher(text);
            while (dropped.find()) {
                present.remove(dropped.group(1));
                comments.remove(dropped.group(1));
            }
            Matcher comment = TABLE_COMMENT.matcher(text);
            while (comment.find()) {
                comments.put(comment.group(1), normalize(comment.group(2)));
            }
        }
        comments.keySet().retainAll(present);
        Map<String, String> effective = new TreeMap<>();
        for (String table : present) {
            effective.put(table, comments.getOrDefault(table, ""));
        }
        return effective;
    }

    /** Every column comment of one module, keyed {@code table.column}. */
    private static Map<String, String> columnComments(String module) {
        Pattern columnComment = Pattern.compile(
                "COMMENT ON COLUMN (\\w+)\\.(\\w+) IS\\s*'([^']*)'", Pattern.DOTALL);
        Map<String, String> comments = new TreeMap<>();
        for (Path migration : migrationsOf(module)) {
            Matcher match = columnComment.matcher(read(migration));
            while (match.find()) {
                comments.put(match.group(1) + "." + match.group(2), normalize(match.group(3)));
            }
        }
        return comments;
    }

    /** Every surviving table of the platform, qualified by schema. */
    private static Map<String, String> allTables() {
        Map<String, String> tables = new TreeMap<>();
        SCHEMA_BY_MODULE.forEach((module, schema) ->
                effectiveTableComments(module).forEach((table, comment) ->
                        tables.put(schema + "." + table, comment)));
        return tables;
    }

    /** Every table whose catalogue comment classifies it as subject data, against its class. */
    private static Map<String, String> classifiedTables() {
        Map<String, String> classified = new TreeMap<>();
        allTables().forEach((qualified, comment) -> {
            Matcher match = PERSONAL_DATA.matcher(comment);
            if (match.find() && SUBJECT_CLASSES.contains(match.group(1))) {
                classified.put(qualified, match.group(1));
            }
        });
        return classified;
    }

    /** The inventory table of the procedure section, keyed {@code schema.table}. */
    private static Map<String, String> inventoryRows() {
        String inventory = sectionOf(read(platformRoot().resolve("docs/" + DATA_MODEL)),
                "### Every store that holds subject data", "### Finding one subject");
        Map<String, String> rows = new TreeMap<>();
        for (String line : inventory.split("\\R")) {
            Matcher row = INVENTORY_ROW.matcher(line.trim());
            if (row.find()) {
                rows.put(row.group(1) + "." + row.group(2), row.group(3));
            }
        }
        assertTrue(rows.size() > 10,
                "the inventory reader found " + rows.size() + " rows, so its verdict would mean"
                        + " nothing");
        return rows;
    }

    // ----------------------------------------------------------------------------------------
    // File reading
    // ----------------------------------------------------------------------------------------

    /** Migrations of one module, in the order Flyway applies them. */
    private static List<Path> migrationsOf(String module) {
        Path directory = platformRoot()
                .resolve(Path.of("services", module, "src", "main", "resources", "db", "migration"));
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(SubjectDataGovernanceContractTest::versionOf))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list the migrations of " + module, unreadable);
        }
    }

    /** The Flyway version of a migration file. */
    private static int versionOf(Path migration) {
        Matcher version = MIGRATION_VERSION.matcher(migration.getFileName().toString());
        assertTrue(version.find(), migration + " carries no Flyway version");
        return Integer.parseInt(version.group(1));
    }

    /** Every shipped source and resource of every module, excluding build output. */
    private static List<Path> mainSources() {
        List<Path> sources = new ArrayList<>();
        for (String area : List.of("services", "libs")) {
            sources.addAll(filesUnder(platformRoot().resolve(area), null).stream()
                    .filter(file -> relative(file).contains("/src/main/"))
                    .toList());
        }
        assertTrue(sources.size() > 200,
                "the census reached " + sources.size() + " shipped files, so its verdict would mean"
                        + " nothing");
        return sources;
    }

    /** Every event schema document of the contracts library, all versions. */
    private static List<Path> schemaDocuments() {
        List<Path> documents = filesUnder(
                platformRoot().resolve("libs/event-contracts/src/main/resources/schemas"), ".json");
        assertTrue(documents.size() > 5,
                "the schema census reached " + documents.size() + " documents, so its verdict would"
                        + " mean nothing");
        return documents;
    }

    /** Every regular file below one directory, optionally filtered by suffix. */
    private static List<Path> filesUnder(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> !file.toString().contains(BUILD_OUTPUT))
                    .filter(file -> suffix == null || file.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }
    }

    /** The procedure section of the data model document. */
    private static String procedureSection() {
        return sectionOf(read(platformRoot().resolve("docs/" + DATA_MODEL)),
                "## " + PROCEDURE_HEADING, "## Reference data");
    }

    /** The destruction-procedure section of the card guide. */
    private static String cardGuideSection() {
        return sectionOf(read(platformRoot().resolve("services/card-service/README.md")),
                "## " + DESTRUCTION_HEADING, "## Deliberate non-additions");
    }

    /** The text between two headings, or the empty string when the opening heading is absent. */
    private static String sectionOf(String document, String opening, String closing) {
        int at = document.indexOf(opening);
        if (at < 0) {
            return "";
        }
        int end = document.indexOf(closing, at + opening.length());
        return end < 0 ? document.substring(at) : document.substring(at, end);
    }

    /** Collapses whitespace, so a comment written over several lines reads as one string. */
    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /** The English word for a figure the document spells out. */
    private static String numberWord(int figure) {
        Map<Integer, String> words = Map.ofEntries(
                Map.entry(12, "twelve"), Map.entry(13, "thirteen"), Map.entry(14, "fourteen"),
                Map.entry(15, "fifteen"), Map.entry(16, "sixteen"), Map.entry(17, "seventeen"),
                Map.entry(18, "eighteen"), Map.entry(19, "nineteen"), Map.entry(20, "twenty"),
                Map.entry(21, "twenty-one"), Map.entry(22, "twenty-two"),
                Map.entry(23, "twenty-three"), Map.entry(24, "twenty-four"),
                Map.entry(25, "twenty-five"), Map.entry(35, "thirty-five"),
                Map.entry(36, "thirty-six"), Map.entry(37, "thirty-seven"),
                Map.entry(38, "thirty-eight"), Map.entry(39, "thirty-nine"));
        String word = words.get(figure);
        assertTrue(word != null,
                "this test has no word for " + figure + ". Extend the map rather than dropping the"
                        + " check.");
        return word;
    }

    /** Walks up from the working directory to the {@code card-platform} module root. */
    private static Path platformRoot() {
        Path base = Path.of("").toAbsolutePath().normalize();
        while (base != null && !Files.isDirectory(base.resolve("services"))) {
            base = base.getParent();
        }
        if (base == null) {
            throw new AssertionError("card-platform module root was not found");
        }
        return base;
    }

    /** One path, relative to the platform root, in the spelling every message uses. */
    private static String relative(Path file) {
        return platformRoot().relativize(file).toString().replace('\\', '/');
    }

    /** Reads one file, or fails when it is absent. */
    private static String read(Path file) {
        assertTrue(Files.isRegularFile(file), () -> file + " must exist");
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
