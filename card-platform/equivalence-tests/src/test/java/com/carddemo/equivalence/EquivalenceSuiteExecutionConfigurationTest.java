package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Holds the build configuration that decides how often each equivalence test runs.
 *
 * <p>The equivalence classes are excluded from the unit-test plugin and included by the
 * integration-test plugin, so each one runs exactly once, at the integration-test phase. Two build
 * shapes raise that count silently. One is an integration-test execution declared under a second
 * identifier. The other is an include placed at plugin level, which merges into every execution of
 * the plugin.</p>
 *
 * <p>This class reads both build files as text and holds three properties:</p>
 * <ul>
 *   <li>the module declares one integration-test execution</li>
 *   <li>that execution reuses the identifier the parent declares</li>
 *   <li>the include selecting the equivalence classes sits inside that execution alone</li>
 * </ul>
 *
 * <p>It also enumerates the equivalence classes on disk and holds that each is selected once.</p>
 *
 * <p>The class name ends in {@code ConfigurationTest} rather than {@code EquivalenceTest}, so the
 * unit-test plugin collects it and the integration-test plugin does not. Running at the unit-test
 * phase is deliberate: a build broken by a duplicate execution should fail before the equivalence
 * suite runs at all.</p>
 */
class EquivalenceSuiteExecutionConfigurationTest {

    /** Plugin that runs the equivalence classes, at the integration-test and verify phases. */
    private static final String INTEGRATION_TEST_PLUGIN = "maven-failsafe-plugin";

    /** Plugin that runs the unit tests, at the test phase. */
    private static final String UNIT_TEST_PLUGIN = "maven-surefire-plugin";

    /** Pattern that selects the equivalence classes. */
    private static final String EQUIVALENCE_PATTERN = "**/*EquivalenceTest.java";

    /** Identifier the parent declares for its integration-test execution. */
    private static final String INHERITED_EXECUTION_ID = "integration-test";

    /** Executions of the integration-test plugin this module is allowed to declare. */
    private static final int PERMITTED_EXECUTION_COUNT = 1;

    /** Suffix every equivalence class carries. */
    private static final String EQUIVALENCE_CLASS_SUFFIX = "EquivalenceTest.java";

    /**
     * The six equivalence classes the plan names for this module.
     *
     * <p>They are listed here so the selection count below is asserted whether or not a class has
     * been written yet. Selection is a property of the build file and not of the file system, so a
     * class that lands later is already covered.</p>
     */
    private static final List<String> PLANNED_EQUIVALENCE_CLASSES = List.of(
            "PostingEquivalenceTest.java",
            "AuthorizationDecisionEquivalenceTest.java",
            "BillPaymentEquivalenceTest.java",
            "InterestCalculationEquivalenceTest.java",
            "ValidationEquivalenceTest.java",
            "DecimalTruncationEquivalenceTest.java");

    @Test
    void theModuleDeclaresOneIntegrationTestExecutionUnderTheInheritedIdentifier() {
        List<Element> executions =
                executionsOf(moduleBuildFile(), INTEGRATION_TEST_PLUGIN);

        assertEquals(PERMITTED_EXECUTION_COUNT, executions.size(),
                "this module declares one execution of " + INTEGRATION_TEST_PLUGIN
                        + ", so each equivalence class runs once; a second execution would run "
                        + "every one of them twice");
        assertEquals(INHERITED_EXECUTION_ID, textOfChild(executions.get(0), "id"),
                "the execution reuses the identifier the parent declares, so Maven merges the "
                        + "two rather than adding a second execution");
    }

    @Test
    void theParentDeclaresOneIntegrationTestExecutionUnderThatSameIdentifier() {
        List<Element> executions =
                executionsOf(parentBuildFile(), INTEGRATION_TEST_PLUGIN);

        assertEquals(PERMITTED_EXECUTION_COUNT, executions.size(),
                "the parent declares one execution of " + INTEGRATION_TEST_PLUGIN
                        + ", so no module inherits a second");
        assertEquals(INHERITED_EXECUTION_ID, textOfChild(executions.get(0), "id"),
                "the parent execution carries the identifier this module reuses");

        List<String> goals = textOfChildren(executions.get(0), "goals", "goal");
        assertEquals(List.of("integration-test", "verify"), goals,
                "the inherited execution runs the classes at integration-test and fails the "
                        + "build at verify");
    }

    @Test
    void theEquivalenceIncludeSitsInsideThatOneExecutionAndNotAtPluginLevel() {
        Element plugin = pluginOf(moduleBuildFile(), INTEGRATION_TEST_PLUGIN);
        assertNotNull(plugin, "this module declares " + INTEGRATION_TEST_PLUGIN);

        List<Element> executions = executionsOf(moduleBuildFile(), INTEGRATION_TEST_PLUGIN);
        assertEquals(PERMITTED_EXECUTION_COUNT, executions.size(),
                "one execution holds the include");

        List<String> executionIncludes =
                textOfChildren(configurationOf(executions.get(0)), "includes", "include");
        assertEquals(List.of(EQUIVALENCE_PATTERN), executionIncludes,
                "the execution selects the equivalence classes and nothing else");

        Element pluginLevelConfiguration = directChild(plugin, "configuration");
        List<String> pluginLevelIncludes =
                textOfChildren(pluginLevelConfiguration, "includes", "include");
        assertTrue(pluginLevelIncludes.isEmpty(),
                "no include sits at plugin level, because a plugin-level include merges into "
                        + "every execution and would select the same class again");
    }

    @Test
    void theUnitTestPluginExcludesExactlyWhatTheIntegrationTestPluginIncludes() {
        Element unitPlugin = pluginOf(moduleBuildFile(), UNIT_TEST_PLUGIN);
        assertNotNull(unitPlugin, "this module declares " + UNIT_TEST_PLUGIN);

        List<String> excludes =
                textOfChildren(directChild(unitPlugin, "configuration"), "excludes", "exclude");
        assertEquals(List.of(EQUIVALENCE_PATTERN), excludes,
                "the unit-test plugin leaves the equivalence classes to the integration-test "
                        + "plugin, so no class runs under both");
    }

    @Test
    void eachEquivalenceClassIsSelectedByExactlyOneExecution() {
        int selections = selectingExecutionCount();

        List<String> candidates = new ArrayList<>(PLANNED_EQUIVALENCE_CLASSES);
        for (String onDisk : equivalenceClassFileNames()) {
            if (!candidates.contains(onDisk)) {
                candidates.add(onDisk);
            }
        }
        assertEquals(PLANNED_EQUIVALENCE_CLASSES.size(), PLANNED_EQUIVALENCE_CLASSES.stream()
                        .distinct().count(),
                "the planned list names each class once");
        assertFalse(candidates.isEmpty(),
                "the selection count below covers at least the planned classes");

        Map<String, Integer> selectionCounts = new LinkedHashMap<>();
        for (String className : candidates) {
            assertTrue(className.endsWith(EQUIVALENCE_CLASS_SUFFIX),
                    className + " ends in " + EQUIVALENCE_CLASS_SUFFIX
                            + ", so the include pattern selects it");
            selectionCounts.put(className, selections);
        }

        assertEquals(candidates.size(), selectionCounts.size(),
                "every candidate class has a selection count");
        for (Map.Entry<String, Integer> selection : selectionCounts.entrySet()) {
            assertEquals(1, selection.getValue().intValue(),
                    selection.getKey() + " is selected by " + selection.getValue()
                            + " execution or executions of " + INTEGRATION_TEST_PLUGIN
                            + " and must be selected by exactly one, or it runs that many times");
        }
    }

    /**
     * Returns how many executions of the integration-test plugin select the equivalence pattern,
     * following the two Maven rules that decide it.
     *
     * <p>Maven merges an execution of the same identifier rather than adding a second. The
     * effective set of executions is therefore the union of the identifiers the parent declares and
     * the identifiers this module declares. An execution selects the pattern when the include sits
     * in its own configuration. It also selects the pattern when the include sits at plugin level,
     * because a plugin-level configuration merges into every execution.</p>
     *
     * @return the number of executions that would run the equivalence classes
     */
    private static int selectingExecutionCount() {
        Document module = moduleBuildFile();
        Element modulePlugin = pluginOf(module, INTEGRATION_TEST_PLUGIN);
        assertNotNull(modulePlugin, "this module declares " + INTEGRATION_TEST_PLUGIN);

        boolean pluginLevelSelects = textOfChildren(directChild(modulePlugin, "configuration"),
                "includes", "include").contains(EQUIVALENCE_PATTERN);

        Map<String, Boolean> selectsById = new LinkedHashMap<>();
        for (Element inherited : executionsOf(parentBuildFile(), INTEGRATION_TEST_PLUGIN)) {
            selectsById.put(textOfChild(inherited, "id"), pluginLevelSelects);
        }
        for (Element declared : executionsOf(module, INTEGRATION_TEST_PLUGIN)) {
            String id = textOfChild(declared, "id");
            boolean executionLevelSelects = textOfChildren(configurationOf(declared),
                    "includes", "include").contains(EQUIVALENCE_PATTERN);
            selectsById.put(id, pluginLevelSelects || executionLevelSelects);
        }

        int selecting = 0;
        for (Boolean selects : selectsById.values()) {
            if (Boolean.TRUE.equals(selects)) {
                selecting++;
            }
        }
        return selecting;
    }

    @Test
    void thisClassRunsUnderTheUnitTestPluginAndNotTheIntegrationTestPlugin() {
        String thisClassFileName =
                EquivalenceSuiteExecutionConfigurationTest.class.getSimpleName() + ".java";

        assertTrue(thisClassFileName.endsWith("Test.java"),
                "the unit-test plugin collects a class whose name ends in Test");
        assertFalse(thisClassFileName.endsWith(EQUIVALENCE_CLASS_SUFFIX),
                "this class is not an equivalence class, so the unit-test exclusion and the "
                        + "integration-test include both pass it over");
        assertFalse(equivalenceClassFileNames().contains(thisClassFileName),
                "this class does not appear among the equivalence classes it counts");
    }

    /** Returns the parsed build file of this module. */
    private static Document moduleBuildFile() {
        return parse(moduleDirectory().resolve("pom.xml"));
    }

    /** Returns the parsed build file of the aggregator. */
    private static Document parentBuildFile() {
        return parse(moduleDirectory().getParent().resolve("pom.xml"));
    }

    /**
     * Returns the directory of this module, found by walking up from the working directory to the
     * directory holding this module's test sources.
     */
    private static Path moduleDirectory() {
        Path marker = Path.of("equivalence-tests", "src", "test", "java", "com", "carddemo",
                "equivalence");
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(marker))) {
                return candidate.resolve("equivalence-tests");
            }
            if (Files.isDirectory(candidate.resolve(Path.of("src", "test", "java", "com",
                    "carddemo", "equivalence")))
                    && candidate.getFileName() != null
                    && "equivalence-tests".equals(candidate.getFileName().toString())) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("no ancestor of the working directory holds the equivalence-tests "
                + "module");
    }

    /** Returns the file name of every equivalence class in this module, sorted. */
    private static List<String> equivalenceClassFileNames() {
        Path sources = moduleDirectory().resolve(Path.of("src", "test", "java", "com", "carddemo",
                "equivalence"));
        assertTrue(Files.isDirectory(sources), "the module holds its test sources at " + sources);

        try (Stream<Path> walk = Files.walk(sources)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EQUIVALENCE_CLASS_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + sources, unreadable);
        }
    }

    /** Parses one build file with external entity resolution disabled. */
    private static Document parse(Path buildFile) {
        assertTrue(Files.isRegularFile(buildFile), "the build file sits at " + buildFile);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(buildFile.toFile());
        } catch (ParserConfigurationException | SAXException | IOException unparsable) {
            throw new AssertionError("cannot parse " + buildFile, unparsable);
        } catch (IllegalArgumentException unsupported) {
            throw new AssertionError("the parser rejected a hardening setting", unsupported);
        }
    }

    // Document navigation. Every helper reads direct children only, so a nested element of the
    // same name in another plugin cannot be mistaken for the one being read.

    /**
     * Returns the plugin element of one artifact, searching every {@code plugin} element of the
     * document.
     *
     * @param document  a parsed build file
     * @param artifactId the artifact identifier of the plugin
     * @return the plugin element, or null when the document declares no such plugin
     */
    private static Element pluginOf(Document document, String artifactId) {
        NodeList plugins = document.getElementsByTagName("plugin");
        Element found = null;
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            if (artifactId.equals(textOfChild(plugin, "artifactId"))
                    && directChild(plugin, "executions") != null) {
                found = plugin;
            }
        }
        if (found != null) {
            return found;
        }
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            if (artifactId.equals(textOfChild(plugin, "artifactId"))) {
                return plugin;
            }
        }
        return null;
    }

    /** Returns every execution element of one plugin, in declaration order. */
    private static List<Element> executionsOf(Document document, String artifactId) {
        Element plugin = pluginOf(document, artifactId);
        assertNotNull(plugin, "the build file declares " + artifactId);
        Element executions = directChild(plugin, "executions");
        if (executions == null) {
            return List.of();
        }
        return directChildren(executions, "execution");
    }

    private static Element configurationOf(Element execution) {
        return directChild(execution, "configuration");
    }

    private static String textOfChild(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    /**
     * Returns the text of every grandchild under one named child.
     *
     * @param parent      the element to read, or null for an empty result
     * @param wrapperName the direct child holding the values, such as includes
     * @param valueName   the repeated element under the wrapper, such as include
     * @return the trimmed text of each value, in declaration order
     */
    private static List<String> textOfChildren(Element parent, String wrapperName,
            String valueName) {
        if (parent == null) {
            return List.of();
        }
        Element wrapper = directChild(parent, wrapperName);
        if (wrapper == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Element value : directChildren(wrapper, valueName)) {
            values.add(value.getTextContent().trim());
        }
        return values;
    }

    private static Element directChild(Element parent, String name) {
        List<Element> children = directChildren(parent, name);
        return children.isEmpty() ? null : children.get(0);
    }

    /** Returns every direct child of one name, in document order. */
    private static List<Element> directChildren(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                children.add((Element) node);
            }
        }
        return children;
    }
}
