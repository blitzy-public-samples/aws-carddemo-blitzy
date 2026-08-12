package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the build's version floors to the shape that makes them take effect.
 *
 * <p>{@code com.fasterxml.jackson.core:jackson-databind:2.21.4} carries three advisories fixed in
 * 2.21.5, and no file in this build names that coordinate: {@code spring-kafka-test} resolves
 * {@code kafka-server} and {@code kafka-server} resolves Jackson 2. An unpinned graph therefore
 * puts that version on the test classpath of seven modules, arriving from a transitive edge and
 * staying.
 *
 * <p>Three things make that a fixable class of defect rather than one incident, and this class
 * asserts all three:
 *
 * <ul>
 *   <li>Every floor is declared as a property, so a raise happens in one place.
 *   <li>Every floor has a {@code dependencyManagement} entry, because a property alone changes
 *       nothing when the bill of materials is imported with {@code scope} {@code import}: the
 *       imported file ignores the importing project's properties.
 *   <li>Every floor has an {@code enforce-transitive-security-floors} range, so a coordinate that
 *       resolves below its floor fails the build. That rule is what would have caught the finding:
 *       the Jackson 2 path had no management entry at all, and a floor with a hole in it reads
 *       exactly like a floor without one.
 * </ul>
 *
 * <p>The assertions read {@code card-platform/pom.xml} rather than the resolved graph, because a
 * test runs on a classpath Maven has already resolved and cannot see what another module resolves.
 * The resolved graph is where the enforcer rule looks, and it runs at {@code validate} in every
 * module of every build, this one included.
 */
class SupplyChainContractTest {

    /**
     * Each version floor: the property that carries it, and the coordinates it governs.
     *
     * <p>{@code tomcat.version} is here for the same mechanical reasons as the others and for a
     * different substantive one. It answers no advisory: it is a compatibility raise over the
     * 11.0.22 the bill of materials manages, and CVE-2026-66299 covers the pinned 11.0.24 itself.
     * What holds the file to saying so is
     * {@link WhatTheDescriptorSays#theTomcatFloorNamesTheAdvisoryItDoesNotAnswer()}. A raise
     * recorded as a security floor invites a reader to believe an advisory was answered, and this
     * one leaves an advisory standing that the platform is simply outside the surface of.
     */
    private static final Map<String, List<String>> FLOORS = new LinkedHashMap<>();

    static {
        FLOORS.put("jackson2.version",
                List.of("com.fasterxml.jackson.core:jackson-databind",
                        "com.fasterxml.jackson.core:jackson-core",
                        "com.fasterxml.jackson.dataformat:jackson-dataformat-csv",
                        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml",
                        "com.fasterxml.jackson.datatype:jackson-datatype-jdk8"));
        FLOORS.put("jackson.version",
                List.of("tools.jackson.core:jackson-databind", "tools.jackson.core:jackson-core",
                        "tools.jackson.dataformat:jackson-dataformat-yaml"));
        FLOORS.put("postgresql.version", List.of("org.postgresql:postgresql"));
        FLOORS.put("lz4-java.version", List.of("at.yawk.lz4:lz4-java"));
        FLOORS.put("tomcat.version",
                List.of("org.apache.tomcat.embed:tomcat-embed-core",
                        "org.apache.tomcat.embed:tomcat-embed-el",
                        "org.apache.tomcat.embed:tomcat-embed-websocket"));
    }

    /** The floor each property carries, so a raise that misses a consumer is visible here. */
    private static final Map<String, String> EXPECTED_FLOOR_VERSIONS = Map.of(
            "jackson2.version", "2.21.5",
            "jackson.version", "3.1.5",
            "postgresql.version", "42.7.13",
            "lz4-java.version", "1.11.1",
            "tomcat.version", "11.0.24");

    /**
     * Advisory identifiers the floors answer, each paired with the coordinate it affects.
     *
     * <p>An identifier in a comment is the only record of why a version is held above the bill of
     * materials, so a wrong identifier sends the next reader to the wrong advisory.
     * GHSA-xx22-p4ch-683r was recorded here as CVE-2026-7053 and the authoritative advisory maps it
     * to CVE-2026-59949, which is the correction this map holds in place.
     */
    private static final Map<String, String> ADVISORY_IDENTIFIERS = Map.of(
            "GHSA-5gvw-p9qm-jgwh", "CVE-2026-59889",
            "GHSA-xx22-p4ch-683r", "CVE-2026-59949");

    /** The identifier the LZ4 advisory was wrongly recorded as, which may not reappear. */
    private static final String WITHDRAWN_LZ4_IDENTIFIER = "CVE-2026-7053";

    /**
     * The advisory covering the pinned Tomcat version, which no reachable release fixes.
     *
     * <p>Published 28 July 2026, rated Low by Apache, covering 11.0.0-M20 through 11.0.24 and fixed
     * in 11.0.25. Maven Central publishes nothing above 11.0.24 on that line, so the pin cannot be
     * raised past it, and the affected component is the WebSocket chat sample of the examples web
     * application, which an embedded Tomcat does not ship.
     */
    private static final String TOMCAT_ADVISORY = "CVE-2026-66299";

    /** The Jackson 2 version carrying those three advisories, which no floor may permit. */
    private static final String VULNERABLE_JACKSON_2 = "2.21.4";

    /** The enforcer execution that checks a floor against the graph a module resolves. */
    private static final String FLOOR_EXECUTION = "enforce-transitive-security-floors";

    /** Reads the aggregator descriptor once per assertion, which costs less than caching it. */
    private static String pom() throws IOException {
        return Files.readString(platformRoot().resolve("pom.xml"));
    }

    @Nested
    @DisplayName("Every floor is declared, managed and enforced")
    class EveryFloor {

        @Test
        @DisplayName("each floor property carries the version this build expects")
        void eachFloorPropertyCarriesItsVersion() throws IOException {
            String pom = pom();
            List<String> failures = new ArrayList<>();
            EXPECTED_FLOOR_VERSIONS.forEach((property, version) -> {
                Matcher declared = Pattern.compile(
                        "<" + Pattern.quote(property) + ">([^<]+)</"
                                + Pattern.quote(property) + ">").matcher(pom);
                if (!declared.find()) {
                    failures.add(property + " is not declared");
                } else if (!version.equals(declared.group(1))) {
                    failures.add(property + " carries " + declared.group(1) + " and not " + version);
                }
            });
            assertThat(failures)
                    .as("version floors declared as properties of card-platform/pom.xml")
                    .isEmpty();
        }

        @Test
        @DisplayName("each floored coordinate has a dependencyManagement entry reading its property")
        void eachFlooredCoordinateIsManaged() throws IOException {
            String pom = pom();
            List<String> failures = new ArrayList<>();
            FLOORS.forEach((property, coordinates) -> coordinates.forEach(coordinate -> {
                String[] parts = coordinate.split(":");
                Pattern managed = Pattern.compile(
                        "<groupId>" + Pattern.quote(parts[0]) + "</groupId>\\s*"
                                + "<artifactId>" + Pattern.quote(parts[1]) + "</artifactId>\\s*"
                                + "<version>\\$\\{" + Pattern.quote(property) + "}</version>",
                        Pattern.DOTALL);
                if (!managed.matcher(pom).find()) {
                    failures.add(coordinate + " has no dependencyManagement entry reading ${"
                            + property + "}");
                }
            }));
            assertThat(failures)
                    .as("a property alone changes nothing: an imported bill of materials ignores "
                            + "the importing project's properties, so every floored coordinate "
                            + "needs its own entry")
                    .isEmpty();
        }

        @Test
        @DisplayName("each floored coordinate is banned below its floor on the resolved graph")
        void eachFlooredCoordinateIsEnforced() throws IOException {
            String pom = pom();
            String execution = floorExecution(pom);
            List<String> failures = new ArrayList<>();
            FLOORS.forEach((property, coordinates) -> coordinates.forEach(coordinate -> {
                String range = "<exclude>" + coordinate + ":[,${" + property + "})</exclude>";
                if (!execution.contains(range)) {
                    failures.add(coordinate + " carries no banned range ending at ${" + property
                            + "}");
                }
            }));
            assertThat(failures)
                    .as("the rule that would have caught jackson-databind " + VULNERABLE_JACKSON_2
                            + " arriving through spring-kafka-test")
                    .isEmpty();
        }

        @Test
        @DisplayName("the floor rule reads the transitive graph and stays bound in every module")
        void theFloorRuleReadsTheTransitiveGraph() throws IOException {
            String execution = floorExecution(pom());
            assertThat(execution)
                    .as("every floored artifact arrives through a starter, a driver declaration or "
                            + "a test dependency, and none is declared by any module")
                    .contains("<searchTransitive>true</searchTransitive>");
            assertThat(execution)
                    .as("a rule that warns is not a floor")
                    .contains("<fail>true</fail>");

            String equivalence = Files.readString(
                    platformRoot().resolve("equivalence-tests/pom.xml"));
            Matcher unbound = Pattern.compile(
                    "<execution>\\s*<id>([^<]+)</id>\\s*<phase>none</phase>", Pattern.DOTALL)
                    .matcher(equivalence);
            List<String> unboundExecutions = new ArrayList<>();
            while (unbound.find()) {
                unboundExecutions.add(unbound.group(1));
            }
            assertThat(unboundExecutions)
                    .as("equivalence-tests is one of the seven modules the Jackson 2 path reaches, "
                            + "so it must not unbind the floor check while unbinding the "
                            + "service-independence check")
                    .doesNotContain(FLOOR_EXECUTION);
        }
    }

    @Nested
    @DisplayName("What the descriptor says about each floor")
    class WhatTheDescriptorSays {

        @Test
        @DisplayName("no floor permits the vulnerable Jackson 2 version")
        void noFloorPermitsTheVulnerableJacksonTwo() throws IOException {
            assertThat(pom())
                    .as("2.21.4 carries GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v and "
                            + "GHSA-mhm7-754m-9p8w, all fixed in 2.21.5")
                    .doesNotContain(VULNERABLE_JACKSON_2 + "</jackson2.version>");
            assertThat(EXPECTED_FLOOR_VERSIONS.get("jackson2.version"))
                    .isNotEqualTo(VULNERABLE_JACKSON_2);
        }

        @Test
        @DisplayName("each advisory identifier pairs with the ghsa identifier it belongs to")
        void eachAdvisoryIdentifierIsCorrect() throws IOException {
            String pom = pom();
            List<String> failures = new ArrayList<>();
            ADVISORY_IDENTIFIERS.forEach((ghsa, cve) -> {
                int ghsaAt = pom.indexOf(ghsa);
                if (ghsaAt < 0) {
                    failures.add(ghsa + " is not cited");
                    return;
                }
                String sentence = pom.substring(ghsaAt, Math.min(pom.length(), ghsaAt + 200));
                if (!sentence.contains(cve)) {
                    failures.add(ghsa + " is not recorded as " + cve);
                }
            });
            assertThat(failures).as("advisory identifiers as their authoritative source maps them")
                    .isEmpty();
            assertThat(pom)
                    .as(WITHDRAWN_LZ4_IDENTIFIER + " is not the identifier of "
                            + "GHSA-xx22-p4ch-683r and sends a reader to the wrong advisory")
                    .doesNotContain(WITHDRAWN_LZ4_IDENTIFIER);
        }

        /**
         * The Tomcat entry names the advisory the pinned version does not fix.
         *
         * <p>This test read the word {@code precautionary} while the comment said no advisory
         * affecting the 11.0.x line had been identified. CVE-2026-66299 was published on 28 July
         * 2026 covering 11.0.0-M20 through 11.0.24, so the pinned version is inside its range and
         * the earlier wording had gone stale. Naming the advisory, stating that the pin does not fix
         * it, and stating why the platform sits outside its surface is what a reader needs, and each
         * of the three is asserted below.
         */
        @Test
        @DisplayName("the Tomcat raise names the advisory it does not answer, and why it need not")
        void theTomcatFloorNamesTheAdvisoryItDoesNotAnswer() throws IOException {
            String pom = pom();
            int entryAt = pom.indexOf("<artifactId>tomcat-embed-core</artifactId>");
            assertThat(entryAt).as("the embedded container entry").isGreaterThan(0);
            String comment = pom.substring(Math.max(0, entryAt - 1400), entryAt);
            assertThat(comment)
                    .as("the raise answers no advisory, and the advisory that covers the pinned "
                            + "version has to be named rather than left out")
                    .contains("not the answer to an advisory")
                    .contains(TOMCAT_ADVISORY)
                    .contains("11.0.0-M20 through 11.0.24");
            assertThat(comment)
                    .as("a reader has to learn why an advisory covering the pin needs no action, "
                            + "which is that the affected component is absent")
                    .contains("examples web application");
            assertThat(pom)
                    .as("the version-floor block has to carry the same statement, because that is "
                            + "the block a reader of the floors reads")
                    .contains(TOMCAT_ADVISORY)
                    .contains("11.0.25");
        }

        @Test
        @DisplayName("the descriptor records the path the Jackson 2 floor answers")
        void theDescriptorRecordsTheJacksonTwoPath() throws IOException {
            String pom = pom();
            assertThat(pom)
                    .as("the next reader has to be able to find where Jackson 2 comes from "
                            + "without resolving the graph again")
                    .contains("spring-kafka-test")
                    .contains("kafka-server");
            assertThat(pom)
                    .as("jackson-annotations has no 2.21.5 to raise to, and leaving that "
                            + "unexplained reads as an oversight")
                    .contains("jackson-annotations");
        }
    }

    /**
     * Holds the build to one exercised toolchain and to archives a second build reproduces.
     *
     * <p><b>The drift this stands over.</b> Three files named the toolchain and no two agreed. The
     * workflow installed {@code JAVA_VERSION: "25"}, which is a release line and resolves to
     * whatever patch Adoptium published most recently. The enforcer admitted {@code [25,)} and
     * {@code [3.9.16,4.0.0)}, so JDK 26 and every future Maven 3.9 passed. Onboarding told a reader
     * to install exactly 25.0.4+7. A build that pins its dependencies by bill of materials, its
     * images by digest and its actions by commit was choosing its compiler by date.
     *
     * <p><b>The archive timestamp belongs beside it.</b> Both answer the same question: whether
     * two builds of one commit are the same build. Without
     * {@code project.build.outputTimestamp} the Jar plugin and the Spring Boot repackage stamp every
     * entry with the moment the build ran, so nothing can tell a rebuild from a change — not a
     * reader comparing two images, and not the provenance statement the pipeline records over the
     * six archives.
     */
    @Nested
    @DisplayName("One exercised toolchain, and archives a second build reproduces")
    class DeterministicBuild {

        @Test
        @DisplayName("both enforcer ranges end where the exercised toolchain ends")
        void bothEnforcerRangesEndWhereTheExercisedToolchainEnds() throws IOException {
            String pom = pom();

            assertThat(pom)
                    .as("the Java range admits release 25 and nothing later, because a newer major"
                            + " would run release-25 bytecode on a runtime no test here has used")
                    .contains("<version>[25,26)</version>")
                    .doesNotContain("<version>[25,)</version>");
            assertThat(pom)
                    .as("the Maven range admits the 3.9.16 patch line and nothing else")
                    .contains("<version>[3.9.16,3.10.0)</version>")
                    .doesNotContain("<version>[3.9.16,4.0.0)</version>");

            String workflow = Files.readString(
                    platformRoot().getParent().resolve(".github/workflows/ci.yml"));
            assertThat(workflow)
                    .as("the workflow installs the exact build the ranges and the onboarding table"
                            + " name, not the release line")
                    .contains("JAVA_VERSION: \"25.0.4+7\"")
                    .contains("MAVEN_VERSION: \"3.9.16\"");
            assertThat(Files.readString(platformRoot().resolve("docs/onboarding.md")))
                    .as("and onboarding names the same two")
                    .contains("25.0.4+7")
                    .contains("3.9.16");
        }

        @Test
        @DisplayName("every archive carries one fixed entry timestamp")
        void everyArchiveCarriesOneFixedEntryTimestamp() throws IOException {
            assertThat(pom())
                    .as("without this property two builds of one commit produce two different"
                            + " archives, and the provenance statement over them says nothing")
                    .containsPattern(
                            "<project\\.build\\.outputTimestamp>\\d{4}-\\d{2}-\\d{2}"
                                    + "T\\d{2}:\\d{2}:\\d{2}Z</project\\.build\\.outputTimestamp>");
        }
    }

    /**
     * Holds every module to declaring the low-level artifacts it programs against.
     *
     * <p><b>The inconsistency this stands over.</b> All six services import
     * {@code org.apache.kafka.clients} and none declared {@code kafka-clients}; five imported
     * {@code tools.jackson} and only the card service declared {@code jackson-databind}, with a
     * comment explaining why it must. {@code libs/event-contracts} declared both. The policy was
     * therefore applied in two modules out of nine, and a module that programs against an API it
     * does not declare compiles only while some starter keeps supplying it.
     *
     * <p>The mapping is deliberately short. A module declares the artifact whose API it imports:
     * {@code kafka-clients} for {@code org.apache.kafka}, {@code jackson-databind} for
     * {@code tools.jackson.databind}. {@code jackson-core} and the Jackson 2 annotations arrive with
     * databind and are not declared, because neither can be absent while databind is present.
     *
     * <p>The other half is the reverse: a declaration no source uses. {@code spring-kafka-test} sat
     * in six modules and only the fraud service imports it; {@code spring-boot-testcontainers} sat
     * in three and only the notification service imports it. Both halves are derived from the tree
     * here, so the next module to import or to stop importing one of these fails this test rather
     * than drifting.
     */
    @Nested
    @DisplayName("Every module declares the artifacts it imports, and nothing it does not")
    class DirectDependencies {

        /** Package prefix a module may import, and the artifact identifier that supplies it. */
        private static final Map<String, String> ARTIFACT_OF_PACKAGE = Map.of(
                "org.apache.kafka.", "kafka-clients",
                "tools.jackson.databind.", "jackson-databind");

        /** Artifact identifier, and the one package prefix whose import justifies declaring it. */
        private static final Map<String, String> PACKAGE_OF_TEST_ARTIFACT = Map.of(
                "spring-kafka-test", "org.springframework.kafka.test",
                "spring-boot-testcontainers", "org.springframework.boot.testcontainers");

        private static final List<String> MODULES = List.of(
                "libs/event-contracts",
                "libs/cobol-compat",
                "services/authorization-service",
                "services/ledger-posting-service",
                "services/fraud-detection-service",
                "services/notification-service",
                "services/account-service",
                "services/card-service",
                "equivalence-tests");

        @Test
        @DisplayName("a module that imports an artifact's API declares that artifact")
        void aModuleThatImportsAnArtifactApiDeclaresIt() throws IOException {
            List<String> divergences = new ArrayList<>();

            for (String module : MODULES) {
                String sources = sourcesOf(module);
                String descriptor = Files.readString(
                        platformRoot().resolve(module).resolve("pom.xml"));
                ARTIFACT_OF_PACKAGE.forEach((prefix, artifact) -> {
                    boolean imported = sources.contains("import " + prefix)
                            || sources.contains("import static " + prefix);
                    boolean declared =
                            descriptor.contains("<artifactId>" + artifact + "</artifactId>");
                    if (imported && !declared) {
                        divergences.add(module + " imports " + prefix + "* and declares no "
                                + artifact);
                    }
                    if (!imported && declared) {
                        divergences.add(module + " declares " + artifact + " and imports no "
                                + prefix + "*");
                    }
                });
            }

            assertThat(divergences)
                    .as("the direct-use policy has to hold in every module or in none")
                    .isEmpty();
        }

        @Test
        @DisplayName("a test dependency no source uses is not declared")
        void aTestDependencyNoSourceUsesIsNotDeclared() throws IOException {
            List<String> divergences = new ArrayList<>();

            for (String module : MODULES) {
                String sources = sourcesOf(module);
                String descriptor = Files.readString(
                        platformRoot().resolve(module).resolve("pom.xml"));
                PACKAGE_OF_TEST_ARTIFACT.forEach((artifact, prefix) -> {
                    // An import, not a mention: this class names both packages as literals, and a
                    // mention would make every module that documents one look like a user of it.
                    boolean used = sources.contains("import " + prefix)
                            || sources.contains("import static " + prefix);
                    boolean declared =
                            descriptor.contains("<artifactId>" + artifact + "</artifactId>");
                    if (declared && !used) {
                        divergences.add(module + " declares " + artifact + " and no source names "
                                + prefix);
                    }
                    if (used && !declared) {
                        divergences.add(module + " names " + prefix + " and declares no " + artifact);
                    }
                });
            }

            assertThat(divergences)
                    .as("a declaration nothing uses is a dependency a reader has to account for")
                    .isEmpty();
        }

        /** Every Java source of one module, main and test, concatenated. */
        private static String sourcesOf(String module) throws IOException {
            Path root = platformRoot().resolve(module).resolve("src");
            if (!Files.isDirectory(root)) {
                return "";
            }
            StringBuilder combined = new StringBuilder();
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .toList()) {
                    combined.append(Files.readString(file)).append('\n');
                }
            }
            return combined.toString();
        }
    }

    /**
     * Holds every Java source of this platform to one import declaration per type.
     *
     * <p>A repeated {@code import} compiles, changes nothing and warns nothing, so copies
     * accumulate unnoticed across configuration records and test classes. The cost is not the line:
     * an import list a reader cannot scan is an import list nobody scans, and the second copy of a
     * name is exactly what makes a merge that added one look like a merge that added none.
     *
     * <p>The check reads the sources rather than a compiler setting, because no compiler on this
     * toolchain reports a duplicate import at all — not as an error and not as a warning under
     * {@code -Xlint:all}. This is the only thing that would report one.
     */
    @Nested
    @DisplayName("No Java source declares one import twice")
    class ImportHygiene {

        @Test
        @DisplayName("every import list names each type once")
        void everyImportListNamesEachTypeOnce() throws IOException {
            List<String> divergences = new ArrayList<>();

            for (Path source : platformSources()) {
                Map<String, Integer> declarations = new LinkedHashMap<>();
                for (String line : Files.readAllLines(source)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                        declarations.merge(trimmed, 1, Integer::sum);
                    }
                }
                declarations.forEach((declaration, count) -> {
                    if (count > 1) {
                        divergences.add(platformRoot().relativize(source) + " declares "
                                + declaration + " " + count + " times");
                    }
                });
            }

            assertThat(divergences)
                    .as("a repeated import compiles and warns nothing, so this is what reports one")
                    .isEmpty();
        }

        /** Every Java source under the platform, main and test, in a stable order. */
        private static List<Path> platformSources() throws IOException {
            List<Path> sources = new ArrayList<>();
            for (String module : List.of("libs/event-contracts", "libs/cobol-compat",
                    "services/authorization-service", "services/ledger-posting-service",
                    "services/fraud-detection-service", "services/notification-service",
                    "services/account-service", "services/card-service", "equivalence-tests")) {
                Path root = platformRoot().resolve(module).resolve("src");
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (var walk = Files.walk(root)) {
                    sources.addAll(walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .sorted()
                            .toList());
                }
            }
            assertThat(sources).as("the platform's Java sources").isNotEmpty();
            return sources;
        }
    }

    /** Returns the {@code enforce-transitive-security-floors} execution element. */
    private static String floorExecution(String pom) {
        int idAt = pom.indexOf("<id>" + FLOOR_EXECUTION + "</id>");
        assertThat(idAt).as("the floor execution of card-platform/pom.xml").isGreaterThan(0);
        int start = pom.lastIndexOf("<execution>", idAt);
        int end = pom.indexOf("</execution>", idAt);
        assertThat(end).as("the floor execution closes").isGreaterThan(start);
        return pom.substring(start, end);
    }

    /** Locates {@code card-platform} from the module the test runs in. */
    private static Path platformRoot() {
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
