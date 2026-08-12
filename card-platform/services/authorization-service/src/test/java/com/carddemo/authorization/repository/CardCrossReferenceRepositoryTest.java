package com.carddemo.authorization.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.parser.PartTree;

/**
 * Contract tests for {@link CardCrossReferenceRepository}, the cross-reference reader.
 *
 * <p>One account holds many cards. {@code KEYS(11,25)} at {@code app/jcl/XREFFILE.jcl:L74-L76}
 * declares the alternate index {@code NONUNIQUEKEY}, and index
 * {@code idx_card_xref_account_id} carries no unique constraint either. A query over that column
 * with no ordering therefore returns rows in whatever order the database chooses, and that order can
 * change between two runs after a vacuum, an index rebuild or a plan change.
 *
 * <p>These tests parse each declared method name the way Spring Data parses it, using
 * {@link PartTree}. Parsing proves the derived query carries the ordering the contract states, and it
 * needs no database, no application context and no container.
 */
final class CardCrossReferenceRepositoryTest {

    /** The column the ordering runs on, and the primary key of the table. */
    private static final String ORDERING_PROPERTY = "cardNumber";

    /** The ordered list method, which returns every card of one account. */
    private static final String ORDERED_LIST_METHOD = "findByAccountIdOrderByCardNumberAsc";

    /** The single-row method, which reproduces the one keyed read the source performs. */
    private static final String SINGLE_ROW_METHOD = "findFirstByAccountIdOrderByCardNumberAsc";

    /** Asserts the account lookup carries an ascending ordering on the card number. */
    @Test
    void theAccountLookupOrdersByCardNumberAscending() {
        Sort sort = new PartTree(ORDERED_LIST_METHOD, CardCrossReferenceEntity.class).getSort();

        assertTrue(sort.isSorted(), "the derived query carries an ordering");
        assertEquals(Sort.by(Sort.Direction.ASC, ORDERING_PROPERTY), sort,
                "rows arrive ordered by card number ascending, which is the order the alternate "
                        + "index returns duplicate keys in");
    }

    @Test
    void theSingleRowLookupCarriesTheSameOrderingAndReadsOneRow() {
        PartTree tree = new PartTree(SINGLE_ROW_METHOD, CardCrossReferenceEntity.class);

        assertEquals(Sort.by(Sort.Direction.ASC, ORDERING_PROPERTY), tree.getSort(),
                "the single-row lookup reads the first row of the same ordering");
        assertTrue(tree.isLimiting(), "the derived query reads one row rather than every row");
        assertEquals(1, tree.getMaxResults(),
                "app/cbl/COTRN02C.cbl:L577-L585 issues one READ and never a browse");
    }

    /** Asserts the card-number lookup needs no ordering, because its key holds one row. */
    @Test
    void theCardNumberLookupNeedsNoOrdering() {
        PartTree tree = new PartTree("findByCardNumber", CardCrossReferenceEntity.class);

        assertFalse(tree.getSort().isSorted(),
                "KEYS(16 0) at app/jcl/XREFFILE.jcl:L43 is the primary key, so one card number "
                        + "matches at most one row");
    }

    @Test
    void theTwoAccountLookupsReturnTheShapesTheirCallersRead() {
        assertEquals(List.class, returnTypeOf(ORDERED_LIST_METHOD),
                "a caller needing every card of the account reads a list");
        assertEquals(Optional.class, returnTypeOf(SINGLE_ROW_METHOD),
                "a caller resolving one card reads an optional, and an account with no cards "
                        + "yields an empty one");
        assertEquals(String.class, parameterTypeOf(ORDERED_LIST_METHOD),
                "XREF-ACCT-ID PIC 9(11) is numeric display, so CHAR(11) text keeps its leading "
                        + "zeros through every hop");
        assertEquals(String.class, parameterTypeOf(SINGLE_ROW_METHOD),
                "both lookups take the identifier in one type");
    }

    /**
     * Asserts the repository declares no account lookup without an ordering.
     *
     * <p>An unordered lookup reintroduces the non-determinism these methods exist to remove, so its
     * absence is part of the contract rather than an accident of the current code.
     *
     * <p>The set is closed on purpose. Beside the three reads sit the three writes and counts that
     * keep this replica current: an ordered idempotent upsert applied from an event, an ordered
     * observation refresh applied from a card update, and the freshness count a service reports
     * rather than authorizing against a stale row. A seventh method has to be weighed against this
     * test instead of appearing silently.
     */
    @Test
    void theRepositoryDeclaresNoAccountLookupWithoutAnOrdering() {
        Set<String> declared = new TreeSet<>();
        for (Method method : CardCrossReferenceRepository.class.getDeclaredMethods()) {
            declared.add(method.getName());
        }

        assertEquals(Set.of("findByCardNumber", ORDERED_LIST_METHOD, SINGLE_ROW_METHOD,
                        "applyStateChange", "refreshObservation", "countByObservedAtBefore"),
                declared,
                "the repository declares the card-number read, the two ordered account reads, the "
                        + "ordered upsert that applies an event, the observation refresh a masked "
                        + "card update performs and the freshness count");
        for (String name : declared) {
            assertFalse(name.startsWith("findByAccountId") && !name.contains("OrderBy"),
                    name + " reads by account with no ordering");
        }
    }

    /**
     * Returns the declared return type of one method of the repository.
     *
     * @param name the method name
     * @return the raw return type
     */
    private static Class<?> returnTypeOf(String name) {
        return methodNamed(name).getReturnType();
    }

    /**
     * Returns the single declared parameter type of one method of the repository.
     *
     * @param name the method name
     * @return the raw parameter type
     */
    private static Class<?> parameterTypeOf(String name) {
        return methodNamed(name).getParameterTypes()[0];
    }

    /**
     * Finds one declared method of the repository by name.
     *
     * @param name the method name
     * @return the method
     * @throws AssertionError when the repository declares no method of that name
     */
    private static Method methodNamed(String name) {
        return Arrays.stream(CardCrossReferenceRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "CardCrossReferenceRepository declares no method named " + name));
    }
}
