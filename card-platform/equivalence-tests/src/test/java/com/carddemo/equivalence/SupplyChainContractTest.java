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
 * <p>A security review found {@code com.fasterxml.jackson.core:jackson-databind:2.21.4} on the test
 * classpath of seven modules, carrying three advisories fixed in 2.21.5. Nothing had put it there
 * deliberately. {@code spring-kafka-test} resolves {@code kafka-server}, {@code kafka-server}
 * resolves Jackson 2, and no file in this build mentioned that coordinate, so the version arrived
 * from a transitive edge and stayed.
 *
 * <p>Three things had to become true for that to be a fixable class of defect rather than one
 * incident, and this class asserts all three:
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
     * different substantive one. It answers no advisory. It is a compatibility raise over the
     * 11.0.22 the bill of materials manages, and {@link #theTomcatFloorIsDescribedAsPrecautionary}
     * holds the file to saying so, because a precautionary pin recorded as a security floor invites
     * a reader to believe an advisory was found when none was.
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

    /** The version of Jackson 2 the review found, which no floor may permit again. */
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
        @DisplayName("no floor permits the Jackson 2 version the review found")
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

        @Test
        @DisplayName("the Tomcat raise is described as precautionary, not as an advisory answer")
        void theTomcatFloorIsDescribedAsPrecautionary() throws IOException {
            String pom = pom();
            int entryAt = pom.indexOf("<artifactId>tomcat-embed-core</artifactId>");
            assertThat(entryAt).as("the embedded container entry").isGreaterThan(0);
            String comment = pom.substring(Math.max(0, entryAt - 1400), entryAt);
            assertThat(comment)
                    .as("no advisory affecting the managed 11.0.22 was identified, so the raise "
                            + "has to read as compatibility hardening")
                    .contains("precautionary");
            assertThat(comment)
                    .as("and it has to say which version it was measured against")
                    .contains("11.0.22");
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
