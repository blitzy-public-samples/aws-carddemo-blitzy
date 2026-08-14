package com.carddemo.authorization.entity;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Credit and expiry values the authorization decline rules read for one account.
 *
 * <p>Eleven columns are mapped: five carrying fields of {@code app/cpy/CVACT01Y.cpy}, three carrying
 * the provenance and age of the copy, and three carrying the exposure this service has approved and
 * the account service has not yet reported back. The account service owns the whole account record.
 * {@code AccountStateChangedConsumer} keeps these rows current after the fixture-backed seed load, and
 * releases a reservation as the posting it anticipates is reported. The table is
 * {@code account_credit_snapshot}, created by
 * {@code src/main/resources/db/migration/V1__schema.sql}, which is authoritative for the column
 * and constraint definitions.</p>
 *
 * <p>The five source-derived columns, in source order:</p>
 *
 * <pre>
 * account_id               ACCT-ID               PIC 9(11)      L5
 * credit_limit             ACCT-CREDIT-LIMIT     PIC S9(10)V99  L8
 * account_expiration_date  ACCT-EXPIRAION-DATE   PIC X(10)      L11
 * current_cycle_credit     ACCT-CURR-CYC-CREDIT  PIC S9(10)V99  L13
 * current_cycle_debit      ACCT-CURR-CYC-DEBIT   PIC S9(10)V99  L14
 * </pre>
 *
 * <p>The primary key comes from {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}: eleven
 * bytes at offset zero of the 300-byte record that {@code RECORDSIZE(300 300)} at L41 fixes.
 * Nothing generates the identifier.</p>
 *
 * <p>Paragraph {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:L393-L422} reads the four
 * business fields. Lines L403 to L405 compute a working balance from the two cycle accumulators and
 * the transaction amount, and L407 compares the credit limit against it. Line L414 compares
 * {@code ACCT-EXPIRAION-DATE} against the first ten characters of the transaction origin timestamp,
 * character by character. The working field {@code WS-TEMP-BAL PIC S9(09)V99} at L187 is one
 * integer digit narrower than the two accumulators and the credit limit. {@code CreditLimitRule}
 * under {@code domain/rules} carries that narrower width.</p>
 *
 * <p>Both accumulators are signed and unconstrained. Line L551 of the same program adds a negative
 * amount to {@code ACCT-CURR-CYC-DEBIT}, and 50 of the 300 records in
 * {@code app/data/ASCII/dailytran.txt} carry one. The account service zeroes both accumulators at
 * its cycle-close operation, reproducing {@code app/cbl/CBACT04C.cbl:L353-L354}.</p>
 *
 * <p>Eight source fields carry no column. Three of them sit outside every decline rule:
 * {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at L9, {@code ACCT-OPEN-DATE PIC X(10)} at L10 and
 * {@code ACCT-REISSUE-DATE PIC X(10)} at L12. The five others:</p>
 *
 * <ul>
 *   <li>{@code ACCT-ACTIVE-STATUS PIC X(01)} at L6. No program reads it before posting.</li>
 *   <li>{@code ACCT-CURR-BAL PIC S9(10)V99} at L7. The formula at L403 to L405 omits it.</li>
 *   <li>{@code ACCT-ADDR-ZIP PIC X(10)} at L15 and {@code ACCT-GROUP-ID PIC X(10)} at L16. The
 *       account service holds both.</li>
 *   <li>{@code FILLER PIC X(178)} at L17. No column models these bytes.</li>
 * </ul>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "account_credit_snapshot",
        indexes = @Index(name = "ix_account_credit_snapshot_observed_at",
                columnList = "observed_at"))
public class AccountCreditSnapshotEntity {

    /**
     * Zero reserved exposure, at the scale both cycle accumulators carry.
     *
     * <p>Both reservation columns are declared {@code NOT NULL DEFAULT 0}, so a row read from the
     * database always carries a value. This constant is what an instance built by the constructor
     * carries before any reservation is written, so a caller reading the effective figures of a
     * freshly built row receives zero rather than an absence.
     */
    public static final BigDecimal NO_RESERVED_EXPOSURE =
            BigDecimal.ZERO.setScale(PicClause.ACCT_CURR_CYC_CREDIT_SCALE);

    /**
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}, held as {@code CHAR(11)}:
     * exactly eleven digit characters.
     *
     * <p>The field is a {@link String} and not a number. {@code PIC 9(11)} is a display field
     * eleven characters wide, and account record 7 of {@code app/data/ASCII/acctdata.txt} writes
     * the identifier as {@code 00000000007}. A numeric column stores that as seven and returns
     * {@code 7}. Every caller would then have to re-pad it to reach the eleven-character key at
     * {@code KEYS(11 0)} in {@code app/jcl/ACCTFILE.jcl:L40}, or the aggregate identifier an event
     * envelope carries. A caller that forgot would produce a key matching nothing. The column
     * holds the padded form once, and the check constraint
     * {@code ck_account_credit_snapshot_account_id_digits} holds the width and the digit class.
     */
    @Id
    @Column(name = "account_id", nullable = false,
            length = PicClause.ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.ACCT_ID_WIDTH + ")")
    private String accountId;

    /**
     * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L8}, held as
     * {@code NUMERIC(12,2)}. Ten integer digits and two fractional digits total twelve.
     */
    @Column(name = "credit_limit", nullable = false,
            precision = PicClause.ACCT_CREDIT_LIMIT_PRECISION,
            scale = PicClause.ACCT_CREDIT_LIMIT_SCALE)
    private BigDecimal creditLimit;

    /**
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}, held as
     * {@code VARCHAR(10)} text. The column name and this field name correct the transposed
     * spelling the copybook carries. {@code app/cbl/CBTRN02C.cbl:L414} compares the ten characters
     * against the leading ten characters of a 26-character timestamp.
     */
    @Column(name = "account_expiration_date", nullable = false,
            length = PicClause.ACCT_EXPIRATION_DATE_WIDTH)
    private String accountExpirationDate;

    /**
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13}, held as
     * {@code NUMERIC(12,2)}. {@code app/cbl/CBTRN02C.cbl:L549} adds an amount of zero or more to
     * the accumulator, and L403 reads it.
     */
    @Column(name = "current_cycle_credit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
    private BigDecimal currentCycleCredit;

    /**
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L14}, held as
     * {@code NUMERIC(12,2)}. {@code app/cbl/CBTRN02C.cbl:L551} adds a negative amount to the
     * accumulator, and L404 subtracts it. Any sign is valid.
     */
    @Column(name = "current_cycle_debit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
    private BigDecimal currentCycleDebit;

    // ------------------------------------------------------------------------------------
    // Reserved cycle exposure. No COBOL ancestor, and the reason is a difference in timing
    // rather than in arithmetic. app/cbl/CBTRN02C.cbl posts each record before it validates
    // the next one, because paragraph 2000-POST-TRANSACTION at :L424-L444 runs inside the read
    // loop and the account rewrite at :L545-L560 has already moved both accumulators by the
    // time :L403-L405 reads them again. Every earlier approval of the run is therefore visible
    // to the overlimit test at :L407.
    //
    // Here the account service owns the accumulators and reports them back through an event,
    // four asynchronous hops after the decision. Between the two, the columns above still
    // report the exposure the account carried BEFORE this service approved. The two columns
    // below carry what this service has approved and not yet had reported back, so
    // domain/rules/CreditLimitRule reads the figures the account would carry had every
    // approved transaction already posted. Nothing about the arithmetic changes: the source
    // truncation, the narrower WS-TEMP-BAL working field and the refund sign convention all
    // stay exactly where they were.
    // ------------------------------------------------------------------------------------

    /**
     * Approved exposure of zero or more this service has committed and the account service has not
     * yet reported back, accumulated as {@code app/cbl/CBTRN02C.cbl:L549} accumulates
     * {@code ACCT-CURR-CYC-CREDIT}: an amount of zero or more is added, so the column is never
     * negative and a check constraint in
     * {@code src/main/resources/db/migration/V7__cycle_exposure_reservation.sql} holds that.
     *
     * <p>Read through {@link #effectivePendingCycleCredit(Instant)}, which answers zero once
     * {@link #getPendingExpiresAt()} has passed. {@code domain/CycleExposureReservation} is the only
     * writer.
     */
    @Column(name = "pending_cycle_credit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
    private BigDecimal pendingCycleCredit = NO_RESERVED_EXPOSURE;

    /**
     * Approved exposure of zero or less, accumulated as {@code app/cbl/CBTRN02C.cbl:L551} accumulates
     * {@code ACCT-CURR-CYC-DEBIT}: a negative amount is added, making the accumulator more negative,
     * and {@code app/cbl/CBTRN02C.cbl:L404} then subtracts it. The column is never positive and a
     * check constraint holds that.
     *
     * <p>The sign convention is the source's, refund defect included:
     * {@code card-platform/docs/business-rule-flags.md} carries it. Read through
     * {@link #effectivePendingCycleDebit(Instant)}.
     */
    @Column(name = "pending_cycle_debit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
    private BigDecimal pendingCycleDebit = NO_RESERVED_EXPOSURE;

    /**
     * When the reserved figures stop counting, or null for a row that has never held a reservation.
     *
     * <p>A reservation is released when the posting it anticipates is reported back. An approval whose
     * posting never arrives — its event routed to the dead-letter topic, or consumed by a ledger that
     * could not apply it — would otherwise hold its exposure for ever, and available credit would
     * shrink monotonically until every call declined. That is the failure mode
     * {@code card-platform/docs/onboarding.md} warns about for the accumulators themselves, whose only
     * source-side reset is {@code app/cbl/CBACT04C.cbl:L353-L354}. This column bounds it.
     */
    @Column(name = "pending_expires_at")
    private Instant pendingExpiresAt;

    // ------------------------------------------------------------------------------------
    // Replica freshness. No COBOL ancestor: the source has no replica to keep current.
    // app/cbl/CBTRN02C.cbl:L396 reads the account dataset itself, so it cannot be stale.
    // A copy that cannot say how old it is cannot be refused when it is too old, which is the
    // whole point of the three columns below.
    // ------------------------------------------------------------------------------------

    /**
     * The state-change event that last wrote this row, or null for a row loaded by
     * {@code V2__seed.sql} that no event has superseded yet.
     * {@code messaging/AccountStateChangedConsumer} sets it on every apply.
     *
     * <p>A check constraint in {@code src/main/resources/db/migration/V1__schema.sql} ties this
     * column to {@link #getSourceOccurredAt()}: a row carries both halves of its provenance or
     * neither.
     */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    /**
     * When the event that last wrote this row occurred, or null for a seeded row.
     *
     * <p>This is the ordering value, and it is the producer's clock rather than this service's. An
     * update whose event did not occur after the stored one is discarded, which is how an
     * out-of-order delivery leaves the row alone instead of moving it backwards.
     */
    @Column(name = "source_occurred_at")
    private Instant sourceOccurredAt;

    /**
     * When this row was last written, by seed or by event. Never null.
     *
     * <p>A freshness check reads this column and nothing else, so it always has a value to compare.
     * A seeded row carries the moment the migration ran, which is the truthful answer for an
     * initial load.
     */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    /**
     * No-argument constructor for the persistence provider, which assigns all eight mapped fields
     * by reflection after it builds the instance.
     */
    protected AccountCreditSnapshotEntity() {
        // The provider populates every field. Callers use the six-argument constructor.
    }

    /**
     * Builds one snapshot row.
     *
     * <p>Each argument is mandatory. The three monetary arguments carry scale 2 and the
     * identifier carries exactly {@value PicClause#ACCT_ID_WIDTH} digits, matching the picture
     * clauses at {@code app/cpy/CVACT01Y.cpy:L5}, {@code :L8}, {@code :L13} and {@code :L14}. The
     * expiration date carries exactly ten characters, matching {@code PIC X(10)} at {@code :L11}.
     * An argument that misses one of those shapes fails here.</p>
     *
     * <p>Account record 7 of {@code app/data/ASCII/acctdata.txt} supplies a worked set of values:
     * identifier {@code 00000000007}, credit limit {@code 2065.00}, expiration date
     * {@code 2024-12-13}, and {@code 0.00} in each accumulator.</p>
     *
     * @param accountId             {@code ACCT-ID}, exactly
     *                              {@value PicClause#ACCT_ID_WIDTH} digits
     * @param creditLimit           {@code ACCT-CREDIT-LIMIT}, scale 2, either sign
     * @param accountExpirationDate {@code ACCT-EXPIRAION-DATE}, exactly ten characters
     * @param currentCycleCredit    {@code ACCT-CURR-CYC-CREDIT}, scale 2, either sign
     * @param currentCycleDebit     {@code ACCT-CURR-CYC-DEBIT}, scale 2, either sign
     * @param observedAt            the moment these values were observed at the source
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when a scale, a length or a character class does not
     *                                  match the source picture clause
     */
    public AccountCreditSnapshotEntity(String accountId, BigDecimal creditLimit,
            String accountExpirationDate, BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit, Instant observedAt) {

        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(creditLimit, "creditLimit is required");
        Objects.requireNonNull(accountExpirationDate, "accountExpirationDate is required");
        Objects.requireNonNull(currentCycleCredit, "currentCycleCredit is required");
        Objects.requireNonNull(currentCycleDebit, "currentCycleDebit is required");

        requireDigits("accountId", accountId, PicClause.ACCT_ID_WIDTH);

        requireScale(creditLimit, PicClause.ACCT_CREDIT_LIMIT_SCALE, "creditLimit");
        requireScale(currentCycleCredit, PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                "currentCycleCredit");
        requireScale(currentCycleDebit, PicClause.ACCT_CURR_CYC_DEBIT_SCALE, "currentCycleDebit");

        if (accountExpirationDate.length() != PicClause.ACCT_EXPIRATION_DATE_WIDTH) {
            throw new IllegalArgumentException("accountExpirationDate must hold exactly "
                    + PicClause.ACCT_EXPIRATION_DATE_WIDTH + " characters, found "
                    + accountExpirationDate.length());
        }

        this.accountId = accountId;
        this.creditLimit = creditLimit;
        this.accountExpirationDate = accountExpirationDate;
        this.currentCycleCredit = currentCycleCredit;
        this.currentCycleDebit = currentCycleDebit;
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt is required");
    }

    /**
     * Checks one argument against the scale its source picture clause declares.
     *
     * <p>The check reads {@link BigDecimal#scale()} and adjusts nothing. A value at another scale
     * signals a mapping defect, and it stops here.</p>
     *
     * @param value the argument under test
     * @param scale the scale the source picture clause declares
     * @param field the field name the message reports
     * @throws IllegalArgumentException when the scales differ
     */
    private static void requireScale(BigDecimal value, int scale, String field) {
        if (value.scale() != scale) {
            throw new IllegalArgumentException(field + " must carry scale " + scale + ", found "
                    + value.scale());
        }
    }

    /**
     * Rejects an identifier that is the wrong width or carries a character outside {@code 0}
     * through {@code 9}.
     *
     * <p>A {@code PIC 9(n)} display field is exactly n characters wide and holds only digits, and
     * the column check constraint repeats both halves in the database. Neither message carries a
     * character of the rejected value: the width message reports a length and the digit message
     * reports a position.</p>
     *
     * @param field the field name the message reports
     * @param value the identifier under test
     * @param width the exact number of digits the source picture clause declares
     * @throws IllegalArgumentException when the width is wrong or a character is not a digit
     */
    private static void requireDigits(String field, String value, int width) {
        if (value.length() != width) {
            throw new IllegalArgumentException(field + " must be exactly " + width
                    + " digits wide, found width " + value.length());
        }
        for (int position = 0; position < width; position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(field
                        + " must hold digits only, found a character outside 0 through 9 at "
                        + "position " + (position + 1));
            }
        }
    }

    /**
     * Returns {@code ACCT-ID}, the primary key, as the
     * {@value PicClause#ACCT_ID_WIDTH} digit characters the column holds.
     *
     * @return the account identifier
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns {@code ACCT-CREDIT-LIMIT}, the value {@code app/cbl/CBTRN02C.cbl:L407} compares
     * against the working balance.
     *
     * @return the credit limit at scale 2
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Returns the account expiration date as the ten characters
     * {@code app/cbl/CBTRN02C.cbl:L414} compares as text.
     *
     * @return the expiration date, ten characters
     */
    public String getAccountExpirationDate() {
        return accountExpirationDate;
    }

    /**
     * Returns {@code ACCT-CURR-CYC-CREDIT}, the first operand of the working balance at
     * {@code app/cbl/CBTRN02C.cbl:L403}.
     *
     * @return the cycle credit accumulator at scale 2
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Returns {@code ACCT-CURR-CYC-DEBIT}, the operand {@code app/cbl/CBTRN02C.cbl:L404}
     * subtracts. The value carries either sign.
     *
     * @return the cycle debit accumulator at scale 2
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Returns the reserved cycle credit as stored, whether or not its expiry has passed.
     *
     * <p>{@code domain/CycleExposureReservation} reads this when it writes the next reservation, so
     * that an expired figure is replaced rather than added to. A rule reads
     * {@link #effectivePendingCycleCredit(Instant)} instead.
     *
     * @return the stored reserved cycle credit at scale 2, never negative
     */
    public BigDecimal getPendingCycleCredit() {
        return pendingCycleCredit;
    }

    /**
     * Returns the reserved cycle debit as stored, whether or not its expiry has passed.
     *
     * @return the stored reserved cycle debit at scale 2, never positive
     */
    public BigDecimal getPendingCycleDebit() {
        return pendingCycleDebit;
    }

    /**
     * Returns when the reserved figures stop counting.
     *
     * @return the expiry, or null for a row that has never held a reservation
     */
    public Instant getPendingExpiresAt() {
        return pendingExpiresAt;
    }

    /**
     * Reports whether a reservation on this row still counts at one moment.
     *
     * <p>A row that has never held a reservation carries no expiry and holds nothing to count. A row
     * whose expiry has passed anticipates a posting that never arrived, and counting it for ever would
     * shrink available credit until every call declined.
     *
     * @param now the moment under test
     * @return true when this row carries an expiry that has not yet passed
     * @throws NullPointerException if {@code now} is null
     */
    public boolean holdsReservationAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return pendingExpiresAt != null && pendingExpiresAt.isAfter(now);
    }

    /**
     * Returns the reserved cycle credit that counts at one moment.
     *
     * @param now the moment the decision is being taken
     * @return the stored figure while the reservation stands, and zero once it has expired
     * @throws NullPointerException if {@code now} is null
     */
    public BigDecimal effectivePendingCycleCredit(Instant now) {
        return holdsReservationAt(now) ? pendingCycleCredit : NO_RESERVED_EXPOSURE;
    }

    /**
     * Returns the reserved cycle debit that counts at one moment.
     *
     * @param now the moment the decision is being taken
     * @return the stored figure while the reservation stands, and zero once it has expired
     * @throws NullPointerException if {@code now} is null
     */
    public BigDecimal effectivePendingCycleDebit(Instant now) {
        return holdsReservationAt(now) ? pendingCycleDebit : NO_RESERVED_EXPOSURE;
    }

    /**
     * Compares on {@code accountId} alone, the primary key of
     * {@code account_credit_snapshot}.
     *
     * @param other the object under comparison
     * @return {@code true} when both hold the same account identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountCreditSnapshotEntity that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId);
    }

    /**
     * Hashes {@code accountId} alone, matching {@link #equals(Object)}.
     *
     * @return the hash of the account identifier
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }

    /**
     * Names all seven business columns and withholds every value.
     *
     * <p>Each column carries either an account identifier or a monetary value that the
     * authorization decision reads, so the text names the column and prints
     * {@link EventEnvelope#WITHHELD} in place of the value. That marker is the platform-wide
     * redaction marker, and this rendering reaches a log line the moment any code concatenates
     * the entity into a message. The credit limit, the expiration date, the two cycle
     * accumulators and the two figures reserved against them are the account holder's financial
     * position, and the identifier names the person the position belongs to. Every one of the seven
     * stays behind its accessor, which is where a caller states its intent to read it. The
     * reservation expiry is the one mapped column no line here names, because a moment in time names
     * no person and discloses no position.</p>
     *
     * @return a single-line rendering that names all seven columns and discloses none
     */
    @Override
    public String toString() {
        return "AccountCreditSnapshotEntity[accountId=" + EventEnvelope.WITHHELD
                + ", creditLimit=" + EventEnvelope.WITHHELD
                + ", accountExpirationDate=" + EventEnvelope.WITHHELD
                + ", currentCycleCredit=" + EventEnvelope.WITHHELD
                + ", currentCycleDebit=" + EventEnvelope.WITHHELD
                + ", pendingCycleCredit=" + EventEnvelope.WITHHELD
                + ", pendingCycleDebit=" + EventEnvelope.WITHHELD
                + "]";
    }

    /**
     * Returns the state-change event that last wrote this row.
     *
     * @return the event identifier, or null for a seeded row
     */
    public UUID getSourceEventId() {
        return sourceEventId;
    }

    /**
     * Returns when the event that last wrote this row occurred.
     *
     * @return the producer-side time, or null for a seeded row
     */
    public Instant getSourceOccurredAt() {
        return sourceOccurredAt;
    }

    /**
     * Returns when this row was last written.
     *
     * @return the observation time, never null once the row has been read from the database
     */
    public Instant getObservedAt() {
        return observedAt;
    }


    /**
     * Records that a state-change event wrote this row, unless that event is not newer than the one
     * already recorded.
     *
     * <p>Kafka orders messages within a partition and every event for one account carries that
     * account as its key, so an out-of-order delivery is unusual rather than routine. It is still
     * possible: a redelivery after a rebalance can arrive behind a newer event that a different
     * consumer instance already applied. Comparing the producer's clock is what makes applying the
     * older one a no-op instead of a regression.
     *
     * @param eventId    the event that carried the change
     * @param occurredAt when that event occurred, from its envelope
     * @param observedAt when this service wrote the row
     * @return true when the row was marked, and false when the event was not newer than the one
     *         already recorded and nothing changed
     * @throws NullPointerException if any argument is null
     */
    public boolean markObserved(UUID eventId, Instant occurredAt, Instant observedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(observedAt, "observedAt");
        if (this.sourceOccurredAt != null && !occurredAt.isAfter(this.sourceOccurredAt)) {
            return false;
        }
        this.sourceEventId = eventId;
        this.sourceOccurredAt = occurredAt;
        this.observedAt = observedAt;
        return true;
    }
}
