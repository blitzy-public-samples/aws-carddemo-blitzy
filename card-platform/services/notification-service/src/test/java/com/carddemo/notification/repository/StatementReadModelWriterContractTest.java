package com.carddemo.notification.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the read model is written only through the two statements that keep its totals current.
 *
 * <p>{@code statement_card_total} holds one card's whole-history count and its two sums, and
 * {@code src/main/resources/db/migration/V10__statement_card_totals.sql} maintains it by delta:
 * {@link StatementTransactionRepository#upsertRow} adds what one posted event brings and
 * {@link StatementTransactionRepository#deleteProcessedBefore} subtracts what retention removes.
 * Every figure the history route publishes rests on that.
 *
 * <p>The interface also inherits {@code save}, {@code saveAll} and four {@code delete} methods from
 * {@code ListCrudRepository}, and none of them touches the totals. A single production call to one of
 * them would leave a card's published count and total describing rows the table no longer holds, and
 * nothing at runtime would say so: the figures would simply be wrong by however much that call moved.
 * This test is what stops that, by reading the shipped sources rather than by asking a reviewer to
 * notice.
 *
 * <p>The inherited names are taken from the interface itself rather than listed here, so a future
 * base interface carrying one more mutator moves the expectation with it.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("The statement read model has exactly two writers")
class StatementReadModelWriterContractTest {

    /** The two statements that write the read model and its totals together. */
    private static final Set<String> DECLARED_WRITERS =
            Set.of("upsertRow", "deleteProcessedBefore");

    /** Declares a local or field reference to the repository, capturing the name it is given. */
    private static final Pattern REPOSITORY_REFERENCE = Pattern.compile(
            "StatementTransactionRepository\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    @Test
    @DisplayName("no shipped source calls an inherited writer of the statement read model")
    void noShippedSourceCallsAnInheritedWriterOfTheStatementReadModel() {
        Set<String> inherited = inheritedWriters();
        assertFalse(inherited.isEmpty(),
                "the interface inherits at least save and delete, so an empty set means this test "
                        + "measures nothing");

        List<String> offending = new ArrayList<>();
        int scanned = 0;
        for (Path source : shippedSources()) {
            String text = read(source);
            for (String reference : referencesIn(text)) {
                scanned++;
                for (String writer : inherited) {
                    String call = reference + "." + writer + "(";
                    if (text.contains(call)) {
                        offending.add(source.getFileName() + " calls " + call);
                    }
                }
            }
        }

        assertTrue(scanned > 0,
                "no shipped source names the repository, so this scan found nothing to check and "
                        + "its verdict would mean nothing");

        assertEquals(List.of(), offending,
                "these calls write the read model without moving statement_card_total, so the count "
                        + "and the totals the history route publishes would stop describing the rows "
                        + "the card holds: " + offending);
    }

    @Test
    @DisplayName("both declared writers are still declared, and each maintains the totals")
    void bothDeclaredWritersAreStillDeclaredAndEachMaintainsTheTotals() {
        Set<String> declared = new TreeSet<>();
        for (Method method : StatementTransactionRepository.class.getDeclaredMethods()) {
            declared.add(method.getName());
        }
        assertTrue(declared.containsAll(DECLARED_WRITERS),
                "the two writers this contract names are " + DECLARED_WRITERS
                        + " and the interface declares " + declared);

        String repository = read(mainSourceRoot()
                .resolve("com/carddemo/notification/repository/StatementTransactionRepository.java"));
        for (String writer : DECLARED_WRITERS) {
            int at = repository.indexOf(" " + writer + "(");
            assertTrue(at > 0, writer + " is not declared in the interface source");
            String statement = repository.substring(0, at);
            int query = statement.lastIndexOf("@Query");
            assertTrue(query > 0, writer + " carries no statement of its own");
            assertTrue(statement.substring(query).contains("statement_card_total"),
                    writer + " is a writer of statement_transaction whose statement never names "
                            + "statement_card_total, so it moves rows without moving the totals "
                            + "that describe them");
        }
    }

    /** Returns the inherited method names that write, derived from the base interface. */
    private static Set<String> inheritedWriters() {
        Set<String> declared = new LinkedHashSet<>();
        for (Method method : StatementTransactionRepository.class.getDeclaredMethods()) {
            declared.add(method.getName());
        }
        return Arrays.stream(StatementTransactionRepository.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("save") || name.startsWith("delete"))
                .filter(name -> !declared.contains(name))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    /** Returns every name a shipped source gives the repository. */
    private static Set<String> referencesIn(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher reference = REPOSITORY_REFERENCE.matcher(text);
        while (reference.find()) {
            names.add(reference.group(1));
        }
        return names;
    }

    /** Returns every shipped Java source of this service. */
    private static List<Path> shippedSources() {
        try (Stream<Path> tree = Files.walk(mainSourceRoot())) {
            List<Path> sources = tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
            assertFalse(sources.isEmpty(), "no shipped source was found under " + mainSourceRoot());
            return sources;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + mainSourceRoot(), unreadable);
        }
    }

    /** Returns this module's main source root, resolved from the directory the suite runs in. */
    private static Path mainSourceRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolve("src/main/java");
            if (Files.isDirectory(candidate)
                    && Files.isDirectory(candidate.resolve("com/carddemo/notification"))) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("cannot locate the notification service source root");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
