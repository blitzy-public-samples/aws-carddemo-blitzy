package com.carddemo.ledger.domain;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.entity.RejectedTransactionEntity;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts the 430-byte reject row one refused transaction earns.
 *
 * <p>Reproduces {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}, whose
 * record is {@code 01 REJECT-RECORD} at {@code :L176-L182}. {@code REJECT-TRAN-DATA PIC X(350)}
 * carries the fourteen fields of {@code app/cpy/CVTRA06Y.cpy:L5-L18}, and
 * {@code VALIDATION-TRAILER PIC X(80)} carries {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} with
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. Both widths sum to the {@code LRECL=430} of
 * {@code app/jcl/POSTTRAN.jcl:L36}.
 *
 * <p>A reject is expected traffic: {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into the return
 * code once the reject count rises above zero. This recorder evaluates no decline rule.
 *
 * <p>The input is {@link FeedTransaction} and not {@link TransactionAuthorized}. The two describe
 * different things. A {@code FeedTransaction} is a record of the daily feed
 * {@code app/jcl/POSTTRAN.jcl:L30-L31} allocates, arriving at {@code 1500-VALIDATE-TRAN} with no
 * decision attached; a {@code TransactionAuthorized} is a decision another service already reached
 * and published. Only the first can be refused, because refusing the second would reverse an approval
 * this service does not own. The type is the boundary: there is no conversion from an authorized
 * event to a feed record, so the two cannot be confused at a call site.
 *
 * <p>Every reject this class records is therefore a feed-validation failure, which is the only kind
 * {@code 2500-WRITE-REJECT-REC} writes. A transaction the ledger cannot post for an infrastructure
 * reason is not a reject and does not reach this class: it fails the consumer, leaves the offset
 * uncommitted and reaches the dead-letter topic.
 *
 * <h2>The row alone, and why no event is published beside it</h2>
 *
 * <p>This class writes the reject row and publishes nothing. The declined event that names the same
 * refusal is published by the authorization service, which AAP 0.1.1 makes the sole writer of the
 * authorization decision, and it is that event which reaches
 * {@code messaging/TransactionDeclinedConsumer} and drives this class. Publishing a second
 * {@code TransactionDeclined} here would put two differently shaped events for one decision on
 * {@code transaction.declined}, and this service reads that topic, so it would consume its own
 * publication.
 *
 * <p>The pairing AAP 0.4.1 describes — a reject row beside a declined event — therefore still holds.
 * The event exists, the row exists, and the two commit atomically with respect to each other because
 * the arriving event's {@code processed_event} marker and this row share one transaction. What
 * changed is which service publishes, and the reason is the sole-writer rule rather than a
 * convenience. {@code card-platform/docs/decision-log.md} carries the entry.
 */
@Service
public class RejectRecorder {

    /** Stores one reject row. The target of the {@code WRITE} at {@code :L451}. */
    private final RejectedTransactionRepository rejectedTransactions;

    /**
     * Trailing characters that carry a positive sign, listed in digit order zero through nine.
     *
     * <p>The character at index zero stands for a trailing digit zero, the one at index one for a
     * digit one, and so on to index nine. {@code equivalence-tests} decodes the same list on the
     * read side.
     */
    private static final String POSITIVE_SIGN_OVERPUNCH_DIGITS = "{ABCDEFGHI";

    /**
     * Trailing characters that carry a negative sign, listed in digit order zero through nine.
     *
     * <p>The character at index zero stands for a trailing digit zero, the one at index one for a
     * digit one, and so on to index nine. The list holds as many characters as
     * {@link #POSITIVE_SIGN_OVERPUNCH_DIGITS}.
     */
    private static final String NEGATIVE_SIGN_OVERPUNCH_DIGITS = "}JKLMNOPQR";

    /** Stamps {@code rejected_at}, in Coordinated Universal Time. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the store this recorder saves through.
     *
     * <p>It takes no meter. The {@code outcome=rejected} counter is raised by
     * {@code messaging/TransactionDeclinedConsumer} after the transaction this method joins has
     * committed, so a rollback leaves no count behind. A class that records nothing takes no
     * recorder.
     *
     * @param rejectedTransactions store over {@code rejected_transaction}
     * @throws NullPointerException when {@code rejectedTransactions} is {@code null}
     */
    public RejectRecorder(RejectedTransactionRepository rejectedTransactions) {
        this.rejectedTransactions =
                Objects.requireNonNull(rejectedTransactions, "rejectedTransactions is required");
    }

    /**
     * Records one refused transaction as one reject row.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L447-L448} assembles the two halves and {@code :L451} writes
     * them as one record. A store failure travels on to the caller, which is the target form of the
     * status ladder at {@code :L452-L464}. The row joins the caller's transaction, which is the one
     * the arriving declined event's {@code processed_event} marker also commits in, so a reject row
     * without its marker cannot exist and neither can the reverse.
     *
     * <p>Nothing here is counted. The {@code outcome=rejected} counter, whose ancestor is
     * {@code WS-REJECT-COUNT} at {@code app/cbl/CBTRN02C.cbl:L186}, is raised by
     * {@code messaging/TransactionDeclinedConsumer} once the transaction this method joins has
     * committed. Counting beside the row put a durable increment behind a write a rollback could
     * still undo, so a reject the store never kept was reported as one it did.
     *
     * <p>{@link DeclineReason#INVALID_CARD_NUMBER} cannot reach this method. That reason is
     * assigned inside the {@code INVALID KEY} limb of the cross-reference read at
     * {@code app/cbl/CBTRN02C.cbl:L383-L387}, and the gate at {@code :L372} then keeps the account
     * read from running. Every {@link TransactionAuthorized} carries the eleven-digit account
     * identifier that same read resolved, so the two states are mutually exclusive. The guard below
     * refuses that reason, and derives no account identity for it.
     *
     * @param refused the feed record {@code 1500-VALIDATE-TRAN} refused
     * @param reason  the validation failure the caller established
     * @throws NullPointerException     when {@code refused} or {@code reason} is {@code null}
     * @throws IllegalArgumentException when {@code reason} answers {@code false} to
     *                                  {@link DeclineReason#resolvesAccount()}
     * @throws IllegalStateException    when a rendered half misses its declared width
     */
    @Transactional
    public void recordReject(FeedTransaction refused, DeclineReason reason) {
        FeedTransaction event = Objects.requireNonNull(refused, "refused is required");
        Objects.requireNonNull(reason, "reason is required");
        if (!reason.resolvesAccount()) {
            throw new IllegalArgumentException("reason " + reason.code()
                    + " is assigned before the cross-reference resolves an account identifier at"
                    + " app/cbl/CBTRN02C.cbl:L383-L387, and every authorized event already carries"
                    + " one, so it cannot arise on this path");
        }

        String rejectTranData = rejectTranData(event);
        String validationTrailer = validationTrailer(reason);
        requireWidth(rejectTranData, PicClause.REJECT_TRAN_DATA_WIDTH, "REJECT-TRAN-DATA");
        requireWidth(validationTrailer, PicClause.VALIDATION_TRAILER_WIDTH, "VALIDATION-TRAILER");
        requireWidth(rejectTranData + validationTrailer, PicClause.REJECT_RECORD_LENGTH,
                "REJECT-RECORD");

        rejectedTransactions.save(new RejectedTransactionEntity(
                UUID.randomUUID(),
                event.transactionId(),
                reason.code(),
                reason.description(),
                rejectTranData,
                clock.instant()));
    }

    /**
     * Renders {@code REJECT-TRAN-DATA PIC X(350)} in {@code app/cpy/CVTRA06Y.cpy:L5-L18} order.
     *
     * <p>{@code DALYTRAN-PROC-TS} stays blank: {@code app/cbl/CBTRN02C.cbl:L437-L438} stamps it on
     * the posting path alone, and the field is blank in all 300 records of
     * {@code app/data/ASCII/dailytran.txt}.
     *
     * @param event the refused feed record
     * @return 350 characters
     */
    private static String rejectTranData(FeedTransaction event) {
        return new StringBuilder(PicClause.DALYTRAN_RECORD_LENGTH)
                .append(alphanumeric(event.transactionId(), PicClause.DALYTRAN_ID_WIDTH))
                .append(alphanumeric(event.transactionTypeCode(), PicClause.DALYTRAN_TYPE_CD_WIDTH))
                .append(numeric(event.merchantCategoryCode(), PicClause.DALYTRAN_CAT_CD_WIDTH))
                .append(alphanumeric(event.source(), PicClause.DALYTRAN_SOURCE_WIDTH))
                .append(alphanumeric(event.description(), PicClause.DALYTRAN_DESC_WIDTH))
                .append(signedAmount(event.amount()))
                .append(numeric(event.merchantId(), PicClause.DALYTRAN_MERCHANT_ID_WIDTH))
                .append(alphanumeric(event.merchantName(), PicClause.DALYTRAN_MERCHANT_NAME_WIDTH))
                .append(alphanumeric(event.merchantCity(), PicClause.DALYTRAN_MERCHANT_CITY_WIDTH))
                .append(alphanumeric(event.merchantZip(), PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH))
                .append(alphanumeric(event.maskedCardNumber(), PicClause.DALYTRAN_CARD_NUM_WIDTH))
                .append(alphanumeric(event.originTimestamp(), PicClause.DALYTRAN_ORIG_TS_WIDTH))
                .append(" ".repeat(PicClause.DALYTRAN_PROC_TS_WIDTH))
                .append(" ".repeat(PicClause.DALYTRAN_RECORD_FILLER_WIDTH))
                .toString();
    }

    /**
     * Renders {@code VALIDATION-TRAILER PIC X(80)} from one decline constant, reproducing the
     * {@code MOVE} at {@code app/cbl/CBTRN02C.cbl:L448}.
     *
     * <p>Both values come off the constant, so the code and its text cannot disagree.
     *
     * @param reason the validation failure the caller established
     * @return 80 characters: four digits then 76 characters of text
     * @throws IllegalStateException when the text overruns
     *                               {@code WS-VALIDATION-FAIL-REASON-DESC}
     */
    private static String validationTrailer(DeclineReason reason) {
        String description = reason.description();
        if (description.length() > PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH) {
            throw new IllegalStateException("WS-VALIDATION-FAIL-REASON-DESC holds "
                    + PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH + " characters and reason "
                    + reason.code() + " carries " + description.length());
        }
        return numeric(reason.code(), PicClause.VALIDATION_FAIL_REASON_WIDTH)
                + alphanumeric(description, PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH);
    }

    /**
     * Renders {@code DALYTRAN-AMT PIC S9(09)V99} as eleven characters with no decimal point, the
     * sign carried by the trailing character.
     *
     * <p>{@code PIC S9(09)V99} with no {@code SIGN} clause takes the platform default, which is
     * {@code SIGN IS TRAILING INCLUDED}: the last character encodes the last digit and the sign
     * together. All 300 records of {@code app/data/ASCII/dailytran.txt} carry that encoding at
     * one-based offsets 133 through 143, and {@code 0000000009I} reads as {@code 0.99} while
     * {@code 0000000259R} reads as {@code -25.99}.
     *
     * <p>An absolute value would lose the sign, so a refused refund would be recorded as a purchase
     * of the same size, and {@code app/cbl/CBTRN02C.cbl:L447} copies the arriving record rather
     * than reshaping it.
     *
     * @param amount the signed amount the event carries
     * @return eleven characters: ten digits then one sign-carrying character
     */
    private static String signedAmount(BigDecimal amount) {
        String digits = numeric(amount.abs().movePointRight(PicClause.DALYTRAN_AMT_SCALE)
                .toBigInteger().toString(), PicClause.DALYTRAN_AMT_WIDTH);
        int lastPosition = digits.length() - 1;
        int lastDigit = digits.charAt(lastPosition) - '0';
        String overpunch = amount.signum() < 0
                ? NEGATIVE_SIGN_OVERPUNCH_DIGITS
                : POSITIVE_SIGN_OVERPUNCH_DIGITS;

        return digits.substring(0, lastPosition) + overpunch.charAt(lastDigit);
    }

    /**
     * Renders one value into a {@code PIC X(n)} field: left-justified, blank-padded on the right,
     * truncated on the right when it overruns.
     *
     * @param value the value to render, treated as blank when {@code null}
     * @param width the declared width of the field
     * @return exactly {@code width} characters
     */
    private static String alphanumeric(String value, int width) {
        String text = value == null ? "" : value;
        return text.length() >= width
                ? text.substring(0, width)
                : text + " ".repeat(width - text.length());
    }

    /**
     * Renders one value into a {@code PIC 9(n)} field: right-justified, zero-padded on the left,
     * truncated on the left when it overruns.
     *
     * @param digits the digits to render, treated as blank when {@code null}
     * @param width  the declared width of the field
     * @return exactly {@code width} characters
     */
    private static String numeric(String digits, int width) {
        String text = digits == null ? "" : digits;
        return text.length() >= width
                ? text.substring(text.length() - width)
                : "0".repeat(width - text.length()) + text;
    }

    /**
     * Checks one rendered value against the width its source field declares.
     *
     * @param value the rendered value
     * @param width the declared width
     * @param field the source field name, reported in every failure
     * @throws IllegalStateException when the two widths differ
     */
    private static void requireWidth(String value, int width, String field) {
        if (value.length() != width) {
            throw new IllegalStateException(field + " holds " + width
                    + " characters and the rendered value holds " + value.length());
        }
    }

    /**
     * One record of the daily transaction feed, as {@code 1500-VALIDATE-TRAN} receives it.
     *
     * <p>Twelve components carry {@code 01 DALYTRAN-RECORD} at {@code app/cpy/CVTRA06Y.cpy:L4-L18},
     * and the thirteenth carries the account identifier the cross-reference read at
     * {@code app/cbl/CBTRN02C.cbl:L383} resolved. {@code DALYTRAN-PROC-TS} has no component, because
     * {@code app/cbl/CBTRN02C.cbl:L437-L438} stamps it on the posting path alone and it is blank in
     * all 300 records of {@code app/data/ASCII/dailytran.txt}. The trailing
     * {@code FILLER PIC X(20)} at {@code :L18} has no component either.
     *
     * <p>This is the only input {@link RejectRecorder#recordReject} accepts, and it deliberately has
     * no factory that reads a {@link TransactionAuthorized}. A record of this type has reached no
     * decision yet, which is what makes refusing it possible.
     *
     * @param accountId           XREF-ACCT-ID, eleven digits, the account the feed record resolved
     * @param transactionId       DALYTRAN-ID at {@code app/cpy/CVTRA06Y.cpy:L5}
     * @param transactionTypeCode DALYTRAN-TYPE-CD at {@code :L6}
     * @param categoryCode        DALYTRAN-CAT-CD at {@code :L7}
     * @param source              DALYTRAN-SOURCE at {@code :L8}
     * @param description         DALYTRAN-DESC at {@code :L9}
     * @param amount              DALYTRAN-AMT at {@code :L10}, signed, scale two
     * @param merchantId          DALYTRAN-MERCHANT-ID at {@code :L11}
     * @param merchantName        DALYTRAN-MERCHANT-NAME at {@code :L12}
     * @param merchantCity        DALYTRAN-MERCHANT-CITY at {@code :L13}
     * @param merchantZip         DALYTRAN-MERCHANT-ZIP at {@code :L14}
     * @param maskedCardNumber    DALYTRAN-CARD-NUM at {@code :L15}, masked before it left the
     *                            authorization boundary
     * @param originTimestamp     DALYTRAN-ORIG-TS at {@code :L16}
     */
    public record FeedTransaction(
            String accountId,
            String transactionId,
            String transactionTypeCode,
            String categoryCode,
            String source,
            String description,
            BigDecimal amount,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String maskedCardNumber,
            String originTimestamp) {

        /**
         * Refuses a record missing a component the reject row or the declined event needs.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L447} copies the arriving record whole, so a component the
         * feed did not carry cannot be invented here. The four checked below are the ones the reject
         * row keys on and the declined event is keyed by; the remainder render as blanks exactly as
         * the source's fixed-width move does.
         *
         * @throws NullPointerException when the account identifier, the transaction identifier or the
         *                              amount is absent
         */
        public FeedTransaction {
            Objects.requireNonNull(accountId, "accountId is required");
            Objects.requireNonNull(transactionId, "transactionId is required");
            Objects.requireNonNull(amount, "amount is required");
        }

        /**
         * Reports the merchant category code under the name the reject row uses.
         *
         * <p>{@code DALYTRAN-CAT-CD} and the event property {@code merchantCategoryCode} are the same
         * field under two names, and this accessor lets the renderer read one name for both.
         *
         * @return the category code
         */
        public String merchantCategoryCode() {
            return categoryCode;
        }
    }
}
