package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads the credit and expiry values that the authorization decline rules test for one account.
 *
 * <p>One parameter area at {@code app/cbl/CBSTM03B.CBL:L100-L112} carried every dataset access in
 * that program. That area held a dataset selector, a one-character operation code, a
 * two-character return code, a 25-byte key, a key length and a 1000-byte record buffer. One
 * interface per aggregate replaces the dataset selector, and the method below replaces operation
 * code {@code 'K'}. Operation codes {@code 'O'} and {@code 'C'} have no counterpart here.</p>
 *
 * <p>Paragraph {@code 4000-ACCTFILE-PROC} at {@code app/cbl/CBSTM03B.CBL:L206-L229} opens the
 * account dataset for input at {@code :L209} and serves one keyed read at {@code :L213-L216}. A
 * decline rule reads and never writes, and it reads through
 * {@link #findForUpdateByAccountId(String)} so that one account's decisions are taken one at a
 * time.</p>
 *
 * <p>The decision path does write one thing, and only after every rule has accepted:
 * {@link #reserveCycleExposure} records the exposure that approval has committed. Paragraph
 * {@code 2700-UPDATE-ACCOUNT} at {@code app/cbl/CBTRN02C.cbl:L545-L560} is the source ancestor —
 * there the account rewrite happens in the same sequential loop that validates the next record, so
 * the following overlimit test at {@code :L403-L407} already sees it. Here the account service owns
 * that rewrite and reports it back through an event, so the reservation stands in for it until the
 * report arrives.</p>
 *
 * <h2>Who keeps the rows current</h2>
 *
 * <p>This service owns {@code account_credit_snapshot} and performs every write to it. The account
 * service cannot: it holds a different database and a different schema, and none of its migrations
 * declares this table. A projection is refreshed by its owner consuming an event and never by
 * another service reaching across.</p>
 *
 * <p>The event is {@code AccountStateChanged}, which the account service publishes on an account
 * update and on the cycle close that zeroes both accumulators, reproducing
 * {@code app/cbl/CBACT04C.cbl:L353-L354}. Its payload carries every column this projection holds:
 * {@code accountId}, {@code creditLimit}, {@code expirationDate}, {@code currentCycleCredit} and
 * {@code currentCycleDebit}. {@code AccountProjectionCoverageTest}, in
 * {@code card-platform/equivalence-tests}, holds that correspondence, so dropping a component from
 * the event or a column from this projection fails the build.</p>
 *
 * <h2>The refresh contract</h2>
 *
 * <p>{@code messaging/AccountStateChangedConsumer} is the refresh path. It applies each event
 * through {@link #applyStateChange}, and it records the event identifier through
 * {@code ProcessedEventRepository} in the SAME local transaction. Splitting the two would leave a
 * window where the projection has moved and the marker has not, and a redelivery inside that window
 * would apply the same change twice.</p>
 *
 * <p>{@link #applyStateChange} rather than {@link #save(AccountCreditSnapshotEntity)} carries the
 * refresh, because the refresh has to be ordered as well as idempotent. One statement compares
 * {@code source_occurred_at} before it writes, so a redelivery arriving behind a newer event
 * discards itself rather than moving a cycle balance backwards. {@link #save} inserts or replaces
 * unconditionally and has no such comparison, so it belongs to a test that is establishing a known
 * row and not to the consumer.</p>
 *
 * <p>{@link AccountCreditSnapshotEntity} carries no setter, so a caller of {@link #save} builds a
 * replacement row with the same identifier. The identifier is already present, so that save is an
 * update and never a second row.</p>
 *
 * <p>A row whose {@code source_occurred_at} is null is the row {@code V2__seed.sql} wrote, and the
 * first event for that account supersedes it. Where events stop arriving the rows go stale rather
 * than wrong, and {@code domain/AuthorizationService} refuses the call under
 * {@code ObservabilityConfig.REPLICA_STAGE} instead of authorizing against state it cannot vouch
 * for.</p>
 *
 * <p>No {@code delete} is exposed. Nothing removes an account from this projection, because the
 * account record has no delete path in {@code app/cbl/}. A missing row also has a defined meaning
 * already: reason code 101 at {@code app/cbl/CBTRN02C.cbl:L397-L399}.</p>
 *
 */
public interface AccountCreditSnapshotRepository
        extends Repository<AccountCreditSnapshotEntity, String> {

    /** The count a projection write returns when a newer change was already recorded. */
    int NO_ROW_WRITTEN = 0;

    /**
     * Returns the snapshot row for one account identifier.
     *
     * <p>The method reproduces operation code {@code 'K'}, condition name {@code M03B-READ-K} at
     * {@code app/cbl/CBSTM03B.CBL:L106}, served at {@code :L213-L216}. Its immediate ancestor is
     * {@code app/cbl/CBTRN02C.cbl:L394-L395}, where paragraph {@code 1500-B-LOOKUP-ACCT} moves the
     * identifier into the key field and reads the account record. The key comes from
     * {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}. The Virtual Storage Access Method
     * dataset defined there carries no alternate index, so one identifier matches at most one
     * row.</p>
     *
     * <p>The identifier holds exactly eleven digit characters, matching
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. Column {@code account_id}
     * holds {@code CHAR(11)}, so the argument carries its leading zeros and no caller pads or
     * strips anything: a bare form of the same number matches nothing.
     * {@code CardCrossReferenceRepository} resolves a card number
     * to a cross-reference row, and a caller passes the account identifier from that row here. Line
     * {@code app/cbl/CBTRN02C.cbl:L394} moves {@code XREF-ACCT-ID PIC 9(11)} from
     * {@code app/cpy/CVACT03Y.cpy:L7} into the key field.</p>
     *
     * <p>An absent row yields an empty {@code Optional}, and the method throws nothing for a miss.
     * {@code app/cbl/CBTRN02C.cbl:L396-L399} answers the same miss with reason code 101 and the
     * text {@code ACCOUNT RECORD NOT FOUND}. {@code AccountExistsRule} under {@code domain/rules}
     * turns the empty {@code Optional} into that decline. A present row supplies the credit limit,
     * both cycle accumulators and the expiry text that reason codes 102 and 103 test at
     * {@code app/cbl/CBTRN02C.cbl:L403-L420}.</p>
     *
     * @param accountId the account identifier, exactly eleven digit characters
     * @return the snapshot row, or an empty {@code Optional} when no row carries the identifier
     */
    Optional<AccountCreditSnapshotEntity> findByAccountId(String accountId);

    /**
     * Returns the snapshot row for one account identifier, holding it against every other decision
     * for the same account until this transaction ends.
     *
     * <p>This is the read the decision path uses, and {@link #findByAccountId(String)} is the read a
     * report or a test uses. The difference is the whole of the guarantee that an approval is visible
     * to the next decision. {@code app/cbl/CBTRN02C.cbl} needs no lock, because one batch program
     * reads and rewrites one account record in one sequential loop and nothing else is running. This
     * service answers concurrent HTTP calls, and two calls for one account that both read the
     * accumulators before either writes its reservation would both approve against the same exposure.
     * A pessimistic lock makes them queue, so the second reads what the first reserved.</p>
     *
     * <p>The lock is taken here, at the account read inside {@code domain/rules/AccountExistsRule},
     * rather than later at the reservation write. It has to cover the read that
     * {@code domain/rules/CreditLimitRule} computes from as well as the write, because a lock taken
     * after the comparison would serialize two writes that had already both decided to approve.</p>
     *
     * <p>How long a call waits for the lock is bounded by {@link #applyLockWaitBound(String)}, which
     * the decision path applies once per transaction before this read runs. Without that bound a
     * contended account holds the request open for as long as the other decision takes.</p>
     *
     * <p>A miss returns an empty {@link Optional} and locks nothing, which is reject reason 101 at
     * {@code app/cbl/CBTRN02C.cbl:L397-L399} exactly as the unlocked read reports it. A row absent
     * from the table cannot be locked into existence, so two calls naming the same absent account both
     * decline, and neither has any exposure to reserve.</p>
     *
     * @param accountId the account identifier, exactly eleven digit characters
     * @return the locked snapshot row, or an empty {@code Optional} when no row carries the identifier
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountCreditSnapshotEntity> findForUpdateByAccountId(String accountId);

    /**
     * Bounds how long the locked read of this transaction waits for a row another decision holds.
     *
     * <p>PostgreSQL waits forever by default, so a contended account would hold an authorization call
     * open for as long as the other call took, and a stalled decision would stall every later decision
     * for the same account with no upper bound. The bound turns that into a fault the caller can act
     * on: the lock is refused rather than awaited, {@code api/GlobalExceptionHandler} answers
     * {@code 503}, and the caller presents the same request again.
     *
     * <p>{@code set_config} with its third argument true is transaction-local, so the bound governs
     * this one decision and is discarded at commit or rollback. That is the reason for a statement
     * rather than a datasource setting: a bound on every connection would also bound a schema
     * migration and the relay sweep, and a migration that gives up on a lock leaves a half-applied
     * schema. A parameter is used rather than {@code SET LOCAL} because PostgreSQL admits no
     * placeholder in a {@code SET} statement.
     *
     * @param milliseconds the bound, as a PostgreSQL interval string such as {@code 3000ms}
     * @return the value the setting now holds, which the caller reads for nothing
     */
    @Query(value = "SELECT set_config('lock_timeout', :milliseconds, true)", nativeQuery = true)
    String applyLockWaitBound(@Param("milliseconds") String milliseconds);

    /**
     * Records the exposure one approved decision has committed and the account service has not yet
     * reported back.
     *
     * <p>Called from inside the decision transaction, after {@link #findForUpdateByAccountId(String)}
     * has locked the row, so the figures written here are computed from figures no other decision can
     * have changed in between. The reservation, the decision row and the outbox row therefore commit
     * together or not at all: an approval that reached a consumer without reserving its exposure
     * cannot exist, and a reservation for an approval that was rolled back cannot either.
     *
     * <p>Both figures arrive already computed, by {@code domain/CycleExposureReservation}, through
     * {@code com.carddemo.cobol.CobolDecimal}. No arithmetic happens in this statement, and that is
     * deliberate: {@code app/cbl/} truncates toward zero at every store and the platform pins that in
     * one place. Adding an amount in SQL would put a second rounding policy, the database's own, on the
     * one value the equivalence tests exist to protect.
     *
     * <p>The statement writes rather than upserts. A reservation belongs to an account this decision
     * has already read under lock, so the row exists; an account with no row declined at reject reason
     * 101 and never reaches here. A return of {@link #NO_ROW_WRITTEN} therefore means the row vanished
     * between the locked read and this write, which nothing in this service does, and the caller treats
     * it as a fault rather than as a no-op.
     *
     * @param accountId          the eleven-digit account identifier, the primary key
     * @param pendingCycleCredit the reserved cycle credit to store, zero or more, scale 2
     * @param pendingCycleDebit  the reserved cycle debit to store, zero or less, scale 2
     * @param pendingExpiresAt   when the stored figures stop counting
     * @return 1 when the row was written, and {@link #NO_ROW_WRITTEN} when no row carries the
     *         identifier
     */
    @Modifying
    @Query(value = """
            UPDATE account_credit_snapshot
               SET pending_cycle_credit = :pendingCycleCredit,
                   pending_cycle_debit  = :pendingCycleDebit,
                   pending_expires_at   = :pendingExpiresAt
             WHERE account_id = :accountId
            """, nativeQuery = true)
    int reserveCycleExposure(@Param("accountId") String accountId,
            @Param("pendingCycleCredit") BigDecimal pendingCycleCredit,
            @Param("pendingCycleDebit") BigDecimal pendingCycleDebit,
            @Param("pendingExpiresAt") Instant pendingExpiresAt);

    /**
     * Writes one snapshot row, refreshing the account it already holds.
     *
     * <p>This write is unconditional, which is why {@code messaging/AccountStateChangedConsumer}
     * does not use it: applying an event needs the ordering comparison that {@link #applyStateChange}
     * carries, and this method has none. What it is for is establishing a known row, which is what a
     * test does before it exercises a rule.
     *
     * <p>The row carries the account identifier as its identity, so saving an account already
     * present updates that row and saving an absent one inserts it. In production an account first
     * appears through {@link #applyStateChange}, whose upsert inserts a missing row and whose
     * ordering comparison then leaves a stale redelivery alone.
     *
     * @param snapshot the row to write
     * @return the written row
     */
    AccountCreditSnapshotEntity save(AccountCreditSnapshotEntity snapshot);

    /**
     * Counts the snapshot rows.
     *
     * @return how many accounts the projection holds
     */
    long count();

    /**
     * Applies one account state change, unless the row already carries a newer one.
     *
     * <p>One statement rather than read-then-write. It is idempotent, so replaying the topic
     * converges on the same rows. It is ordered too, so a redelivery arriving behind a newer event
     * discards itself instead of moving the replica backwards.
     *
     * <p>This matters more here than anywhere else in the service. The credit-limit rule at
     * {@code app/cbl/CBTRN02C.cbl:L403-L407} authorizes against {@code current_cycle_credit} and
     * {@code current_cycle_debit}. A stale or regressed row does not fail loudly; it approves a
     * transaction that should have been declined.
     *
     * <p>{@code source_occurred_at IS NULL} on the stored row means the row came from
     * {@code V2__seed.sql}. Any event supersedes it.
     *
     * <h2>Releasing the reservation this event reports</h2>
     *
     * <p>The same statement releases as much reserved exposure as the event accounts for, because the
     * release and the authoritative figures have to move together. Releasing in a second statement
     * would leave a window in which the row carried the new authoritative figure and the old
     * reservation, and every decision landing in that window would decline against exposure counted
     * twice.
     *
     * <p>The release is driven by the advance the event reports rather than by a clock. An event
     * raising {@code current_cycle_credit} by 100.00 accounts for 100.00 of the reserved credit, so the
     * reservation drops by exactly that and any reservation for an approval still in flight survives.
     * {@code GREATEST} and {@code LEAST} clamp at zero, so an event reporting more than was reserved
     * releases everything and reserves nothing negative, which the two check constraints require.
     *
     * <p>The debit arm mirrors the sign convention of {@code app/cbl/CBTRN02C.cbl:L551}: the
     * accumulator becomes more negative as debits post, so the advance is the stored figure minus the
     * incoming one, and the reservation moves toward zero by adding it.
     *
     * <p>An event lowering a credit accumulator or raising a debit accumulator is a cycle close: the
     * account service zeroes both, reproducing {@code app/cbl/CBACT04C.cbl:L353-L354}, and nothing else
     * in the source ever moves an accumulator that way, because every other statement adds. There is
     * no advance to subtract in that case, so the reservation is cleared outright. Leaving it would
     * carry exposure from the closed cycle into the new one.
     *
     * <p>{@code pending_expires_at} is left where it stands. A reservation released to zero reads as
     * zero whether its expiry has passed or not, so clearing the column would buy nothing, and
     * {@code AccountCreditSnapshotEntity#effectivePendingCycleCredit(java.time.Instant)} is what
     * decides whether a non-zero figure still counts.
     *
     * @param accountId             the eleven-digit account identifier, the primary key
     * @param creditLimit           ACCT-CREDIT-LIMIT, scale 2
     * @param accountExpirationDate ACCT-EXPIRAION-DATE, ten characters, compared as text
     * @param currentCycleCredit    ACCT-CURR-CYC-CREDIT, scale 2
     * @param currentCycleDebit     ACCT-CURR-CYC-DEBIT, scale 2, signed
     * @param sourceEventId         the event that carried the change
     * @param sourceOccurredAt      when that event occurred, from its envelope
     * @param observedAt            when this service applied it
     * @return 1 when the row was written, and 0 when a newer change was already recorded
     */
    @Modifying
    @Query(value = """
            INSERT INTO account_credit_snapshot (account_id, credit_limit,
                                                 account_expiration_date,
                                                 current_cycle_credit, current_cycle_debit,
                                                 source_event_id, source_occurred_at, observed_at)
            VALUES (:accountId, :creditLimit, :accountExpirationDate,
                    :currentCycleCredit, :currentCycleDebit,
                    :sourceEventId, :sourceOccurredAt, :observedAt)
            ON CONFLICT (account_id) DO UPDATE SET
                credit_limit            = EXCLUDED.credit_limit,
                account_expiration_date = EXCLUDED.account_expiration_date,
                current_cycle_credit    = EXCLUDED.current_cycle_credit,
                current_cycle_debit     = EXCLUDED.current_cycle_debit,
                pending_cycle_credit    = CASE
                    WHEN EXCLUDED.current_cycle_credit
                             < account_credit_snapshot.current_cycle_credit THEN 0
                    ELSE GREATEST(0, account_credit_snapshot.pending_cycle_credit
                             - (EXCLUDED.current_cycle_credit
                                    - account_credit_snapshot.current_cycle_credit))
                END,
                pending_cycle_debit     = CASE
                    WHEN EXCLUDED.current_cycle_debit
                             > account_credit_snapshot.current_cycle_debit THEN 0
                    ELSE LEAST(0, account_credit_snapshot.pending_cycle_debit
                             + (account_credit_snapshot.current_cycle_debit
                                    - EXCLUDED.current_cycle_debit))
                END,
                source_event_id         = EXCLUDED.source_event_id,
                source_occurred_at      = EXCLUDED.source_occurred_at,
                observed_at             = EXCLUDED.observed_at
            WHERE account_credit_snapshot.source_occurred_at IS NULL
               OR account_credit_snapshot.source_occurred_at < EXCLUDED.source_occurred_at
            """, nativeQuery = true)
    int applyStateChange(@Param("accountId") String accountId,
            @Param("creditLimit") BigDecimal creditLimit,
            @Param("accountExpirationDate") String accountExpirationDate,
            @Param("currentCycleCredit") BigDecimal currentCycleCredit,
            @Param("currentCycleDebit") BigDecimal currentCycleDebit,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("sourceOccurredAt") Instant sourceOccurredAt,
            @Param("observedAt") Instant observedAt);

    /**
     * Counts rows last observed before {@code cutoff}, so a service can report how much of its
     * replica has gone stale rather than discovering it one authorization at a time.
     *
     * @param cutoff the freshness cutoff
     * @return how many rows were last observed before {@code cutoff}
     */
    long countByObservedAtBefore(Instant cutoff);
}
