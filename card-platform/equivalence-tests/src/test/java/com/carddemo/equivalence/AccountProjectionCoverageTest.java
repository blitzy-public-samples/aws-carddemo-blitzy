package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import jakarta.persistence.Column;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Holds the correspondence between the account state-change event and the projection the
 * authorization service reads on every decision.
 *
 * <p>{@code account_credit_snapshot} is a projection of another service's aggregate. Its rows arrive
 * from a seed migration, and only consuming {@link AccountStateChanged} can refresh them. That
 * refresh is possible while the event carries every value the projection holds. Drop one component
 * and that column can never be brought current, which leaves reason codes 102 and 103 at
 * {@code app/cbl/CBTRN02C.cbl:L403-L420} testing account state as it stood at deployment.
 *
 * <p>No compiler notices that. The event belongs to the account service, the projection to the
 * authorization service, and a Kafka topic joins them. Neither module may depend on the other, which
 * is what makes the coupling real and invisible at the same time. This class lives in
 * {@code equivalence-tests}, the one module permitted to see both sides, so the contract is asserted
 * without either service gaining a dependency.
 *
 * <p>Versioning is asserted too. A consumer that cannot tell which contract version it received
 * cannot decide whether it understands the payload.
 */
class AccountProjectionCoverageTest {

    /**
     * The columns that record when this replica was last refreshed, rather than what it holds.
     *
     * <p>with no COBOL ancestor: the source reads the account dataset directly at
     * {@code app/cbl/CBTRN02C.cbl:L396}, so it has nothing to go stale. No event component refreshes
     * these three, because the consumer writes them as it applies an event, so they are excluded
     * from the coverage comparison rather than listed in it.
     */
    private static final Set<String> FRESHNESS_COLUMNS =
            Set.of("source_event_id", "source_occurred_at", "observed_at");

    /**
     * The columns holding exposure the authorization service reserved itself and this event has not
     * reported back yet.
     *
     * <p>These three are not values the event supplies, so no component can cover them and listing
     * them in {@link #COVERAGE} would assert a correspondence that does not exist. They exist because
     * {@code app/cbl/CBTRN02C.cbl} rewrote the account at {@code :L545-L560} before it validated the
     * next record, so {@code :L403-L405} always read accumulators carrying every earlier approval.
     * Here the account service owns those accumulators, and an approval reserves its own amount until
     * this event arrives.
     *
     * <p>The event still governs them, which is why {@link #theEventReleasesTheExposureItReports()}
     * reads the statement that applies it. The event does not refresh a reserved figure: it releases
     * one, by the advance it reports on the matching accumulator.
     */
    private static final Set<String> RESERVATION_COLUMNS =
            Set.of("pending_cycle_credit", "pending_cycle_debit", "pending_expires_at");

    /**
     * Every value the projection holds, as the column name and the event component supplying it.
     *
     * <p>The projection carries five columns. The account identifier is its identity, and the other
     * four are what the credit-limit and expiry rules test.
     */
    private static final List<Coverage> COVERAGE = List.of(
            new Coverage("account_id", "accountId", String.class),
            new Coverage("credit_limit", "creditLimit", BigDecimal.class),
            new Coverage("account_expiration_date", "expirationDate", String.class),
            new Coverage("current_cycle_credit", "currentCycleCredit", BigDecimal.class),
            new Coverage("current_cycle_debit", "currentCycleDebit", BigDecimal.class));

    /**
     * One projection column and the event component that refreshes it.
     *
     * @param column    column name on {@code account_credit_snapshot}
     * @param component record component name on {@link AccountStateChanged}
     * @param type      type the component carries
     */
    private record Coverage(String column, String component, Class<?> type) {
    }

    /**
     * Returns the component names the event declares.
     *
     * @return the component names
     */
    private static Set<String> eventComponents() {
        return Arrays.stream(AccountStateChanged.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("The event carries a component for every column the projection holds")
    void eventCarriesEveryProjectionColumn() {
        Set<String> components = eventComponents();
        for (Coverage covered : COVERAGE) {
            assertTrue(components.contains(covered.component()),
                    "AccountStateChanged carries no " + covered.component()
                            + ", so account_credit_snapshot." + covered.column()
                            + " can never be refreshed");
        }
    }

    @Test
    @DisplayName("Every covered component carries the type its column stores")
    void everyComponentCarriesItsColumnType() {
        for (Coverage covered : COVERAGE) {
            Class<?> actual = Arrays.stream(AccountStateChanged.class.getRecordComponents())
                    .filter(component -> covered.component().equals(component.getName()))
                    .map(RecordComponent::getType)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "AccountStateChanged carries no " + covered.component()));
            assertEquals(covered.type(), actual, covered.component()
                    + " has to carry the type account_credit_snapshot." + covered.column()
                    + " stores");
        }
    }

    @Test
    @DisplayName("The covered list names exactly the business columns the projection maps")
    void coveredListMatchesTheMappedColumns() {
        Set<String> mapped = Arrays.stream(AccountCreditSnapshotEntity.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Column.class))
                .map(field -> field.getAnnotation(Column.class).name())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> listed = COVERAGE.stream().map(Coverage::column).collect(Collectors.toSet());

        assertTrue(mapped.containsAll(FRESHNESS_COLUMNS),
                "the projection stopped recording when it was last refreshed, so a row that has "
                        + "gone stale can no longer be refused: " + mapped);
        assertTrue(mapped.containsAll(RESERVATION_COLUMNS),
                "the projection stopped holding the exposure of an approval this event has not "
                        + "reported back yet, so two calls inside one propagation window can "
                        + "together exceed the credit limit: " + mapped);
        mapped.removeAll(FRESHNESS_COLUMNS);
        mapped.removeAll(RESERVATION_COLUMNS);

        assertEquals(mapped, listed,
                "a business column was added to or removed from the projection without a matching "
                        + "event component");
    }

    /**
     * The statement that applies this event also releases the exposure it reports.
     *
     * <p>Refreshing the two authoritative accumulators without releasing the reservation they now
     * contain would count one approval twice, and every decision taken afterwards would decline
     * against exposure that had already been posted. The release therefore belongs in the same
     * statement as the refresh, and this reads it there. A cycle close is the one event that lowers a
     * credit accumulator, because every other source statement adds, so an incoming figure that moved
     * the wrong way clears the reservation outright.
     */
    @Test
    @DisplayName("The statement applying the event releases the exposure the event reports")
    void theEventReleasesTheExposureItReports() {
        String statement = applyStateChangeStatement();

        assertTrue(statement.contains("pending_cycle_credit") && statement.contains(
                        "pending_cycle_debit"),
                "the upsert that applies this event has to release the reservation it reports, or a "
                        + "posted approval stays counted twice: " + statement);
        assertTrue(statement.contains("GREATEST(0"),
                "releasing more than was reserved would drive the reserved credit negative, which "
                        + "its CHECK constraint refuses: " + statement);
        assertTrue(statement.contains("LEAST(0"),
                "the debit reservation is zero or negative, matching the sign convention at "
                        + "app/cbl/CBTRN02C.cbl:L551: " + statement);
    }

    /**
     * Reads the native upsert the account state-change consumer runs.
     *
     * @return the statement text declared on the repository method
     */
    private static String applyStateChangeStatement() {
        Method applying = Arrays.stream(AccountCreditSnapshotRepository.class.getMethods())
                .filter(method -> "applyStateChange".equals(method.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the projection repository no longer applies an account state change"));
        Query declared = applying.getAnnotation(Query.class);
        assertNotNull(declared, "applyStateChange has to declare the statement it runs");
        assertTrue(declared.nativeQuery(),
                "the release arms are PostgreSQL expressions, so the statement stays native");
        return declared.value();
    }

    @Test
    @DisplayName("The event declares the envelope fields a consumer needs")
    void eventDeclaresTheEnvelopeFields() {
        Set<String> components = eventComponents();
        assertTrue(components.contains("eventId"),
                "a consumer cannot deduplicate without the event identifier");
        assertTrue(components.contains("eventType"), "a consumer cannot route without the type");
        assertTrue(components.contains("schemaVersion"),
                "a consumer cannot tell which contract version it received");
        assertTrue(components.contains("occurredAt"), "a consumer cannot order without a moment");
        assertTrue(components.contains("aggregateId"),
                "the account identifier is the message key, so it belongs on the envelope");
    }

    @Test
    @DisplayName("The event pins its version and its type as constants")
    void eventPinsVersionAndType() {
        assertEquals(AccountStateChanged.class.getSimpleName(), AccountStateChanged.EVENT_TYPE,
                "the event type has to name the record");
        assertTrue(AccountStateChanged.SCHEMA_VERSION >= 1,
                "a schema version counts from one");
    }

    @Test
    @DisplayName("The event distinguishes an account update from a cycle close")
    void eventDistinguishesUpdateFromCycleClose() {
        assertTrue(eventComponents().contains("changeKind"),
                "a consumer cannot tell an account update from a cycle close");

        Set<String> kinds = Arrays.stream(AccountStateChanged.ChangeKind.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertTrue(kinds.contains("ACCOUNT_UPDATED"),
                "an account update has to reach the projection: " + kinds);
        assertTrue(kinds.contains("BILLING_CYCLE_CLOSED"),
                "a cycle close zeroes both accumulators the credit-limit rule reads, reproducing "
                        + "app/cbl/CBACT04C.cbl:L353-L354, so it has to reach the projection: "
                        + kinds);
    }

    @Test
    @DisplayName("The cycle close carries both accumulators the credit-limit rule reads")
    void cycleCloseCarriesBothAccumulators() {
        Set<String> components = eventComponents();
        assertTrue(components.contains("currentCycleCredit")
                        && components.contains("currentCycleDebit"),
                "app/cbl/CBTRN02C.cbl:L403-L407 computes cycle credit minus cycle debit plus the "
                        + "amount, so a close that carried neither would leave available credit "
                        + "shrinking until every transaction declined");
    }

    @Test
    @DisplayName("The projection repository exposes the read and the write a refresh needs")
    void repositoryExposesReadAndWrite() {
        Set<String> offered = repositoryMethodNames();
        assertTrue(offered.contains("findByAccountId"),
                "a decline rule cannot read the projection");
        assertTrue(offered.contains("save"),
                "a consumer cannot refresh a projection it cannot write");
    }

    @Test
    @DisplayName("The projection repository exposes no delete")
    void repositoryExposesNoDelete() {
        List<String> deletes = repositoryMethodNames().stream()
                .filter(name -> name.startsWith("delete") || name.startsWith("remove"))
                .toList();
        assertTrue(deletes.isEmpty(), "an absent row already means reason code 101 at "
                + "app/cbl/CBTRN02C.cbl:L397-L399, so removing one would turn a stale account into "
                + "a declined account: " + deletes);
    }

    @Test
    @DisplayName("A snapshot row is built from the values the event carries")
    void snapshotIsBuildableFromTheEventPayload() {
        AccountCreditSnapshotEntity refreshed = new AccountCreditSnapshotEntity("00000000001",
                new BigDecimal("5000.00"), "2099-12-31", new BigDecimal("0.00"),
                new BigDecimal("0.00"), Instant.parse("2026-01-01T00:00:00Z"));

        assertNotNull(refreshed, "the projection has to be constructible from an event payload");
        assertEquals("00000000001", refreshed.getAccountId(),
                "the identifier keeps its leading zeros");
        assertEquals(0, new BigDecimal("5000.00").compareTo(refreshed.getCreditLimit()),
                "the credit limit survives");
        assertEquals("2099-12-31", refreshed.getAccountExpirationDate(),
                "the expiry travels as text, matching app/cbl/CBTRN02C.cbl:L414");
    }

    /**
     * Returns every method name the projection repository offers.
     *
     * @return the method names
     */
    private static Set<String> repositoryMethodNames() {
        return Arrays.stream(AccountCreditSnapshotRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
