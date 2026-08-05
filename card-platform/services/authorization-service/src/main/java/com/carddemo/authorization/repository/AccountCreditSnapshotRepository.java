package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
 * account dataset for input at {@code :L209} and serves one keyed read at {@code :L213-L216}. The
 * decision path reads and never writes, which is why {@link #findByAccountId(String)} is the only
 * method a decline rule calls.</p>
 *
 * <h2>Who keeps the rows current</h2>
 *
 * <p>This service owns {@code account_credit_snapshot} and performs every write to it. The account
 * service cannot: it holds a different database and a different schema, and none of its three
 * migrations declares this table. Database-per-service is the point, so a projection is refreshed by
 * its owner consuming an event and never by another service reaching across.</p>
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
 * <p>No consumer calls {@link #save(AccountCreditSnapshotEntity)} today: this service registers no
 * listener, so the method is the refresh path and nothing exercises it. A consumer that is added
 * must apply the event through that method and record the event identifier through
 * {@code ProcessedEventRepository} in the SAME local transaction. Splitting the two leaves a window
 * where the projection has moved and the marker has not, and a redelivery inside that window
 * applies the same change twice.</p>
 *
 * <p>{@link AccountCreditSnapshotEntity} carries no setter, so a refresh builds a replacement row
 * with the same identifier and saves it. The identifier is already present, so the save is an
 * update and never a second row.</p>
 *
 * <p>Until a consumer exists, every row is the one the seed migration wrote, and a decline rule
 * reading this projection is reading account state as it stood at deployment. That is the state this
 * interface makes refreshable; it does not by itself make the rows fresh.</p>
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
     * strips anything: {@code 00000000007} matches and {@code 7} matches nothing. {@code CardCrossReferenceRepository} resolves a card number
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
     * Writes one snapshot row, refreshing the account it already holds.
     *
     * <p>This is the only write to {@code account_credit_snapshot}, and no current code calls it. A
     * consumer of {@code AccountStateChanged} is to build a row from the event payload and save it
     * here, in the same local transaction as the idempotency marker that guards the apply.
     *
     * <p>The row carries the account identifier as its identity, so saving an account already
     * present updates that row. Saving an account absent from the projection inserts one, which is
     * how an account created after deployment first appears.
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
