package com.carddemo.ledger.domain;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.ledger.entity.RejectedTransactionEntity;
import com.carddemo.ledger.outbox.OutboxWriter;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts the 430-byte reject row and enqueues one {@code TransactionDeclined} event.
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
 */
@Service
public class RejectRecorder {

    /** Stores one reject row. The target of the {@code WRITE} at {@code :L451}. */
    private final RejectedTransactionRepository rejectedTransactions;

    /** Enqueues the declined event beside that row in this method's transaction. */
    private final OutboxWriter outbox;

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
     * Takes the store this recorder saves through and the writer it enqueues through.
     *
     * @param rejectedTransactions store over {@code rejected_transaction}
     * @param outbox               writer over {@code outbox_event}
     * @throws NullPointerException when either argument is {@code null}
     */
    public RejectRecorder(RejectedTransactionRepository rejectedTransactions, OutboxWriter outbox) {
        this.rejectedTransactions =
                Objects.requireNonNull(rejectedTransactions, "rejectedTransactions is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
    }

    /**
     * Records one refused transaction: the row first, then the event.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L447-L448} assembles the two halves and {@code :L451} writes
     * them as one record. A store failure travels on to the caller, which is the target form of the
     * status ladder at {@code :L452-L464}. The reject row and event row share this transaction, so
     * either both commit or both roll back.
     *
     * <p>{@link DeclineReason#INVALID_CARD_NUMBER} cannot reach this method. That reason is
     * assigned inside the {@code INVALID KEY} limb of the cross-reference read at
     * {@code app/cbl/CBTRN02C.cbl:L383-L387}, and the gate at {@code :L372} then keeps the account
     * read from running. Every {@link TransactionAuthorized} carries the eleven-digit account
     * identifier that same read resolved, so the two states are mutually exclusive. The guard below
     * refuses that reason, and derives no account identity for it.
     *
     * @param event  the authorized transaction the ledger refused to post
     * @param reason the validation failure the caller established
     * @throws NullPointerException     when {@code event} or {@code reason} is {@code null}
     * @throws IllegalArgumentException when {@code reason} answers {@code false} to
     *                                  {@link DeclineReason#resolvesAccount()}
     * @throws IllegalStateException    when a rendered half misses its declared width
     */
    @Transactional
    public void recordReject(TransactionAuthorized event, DeclineReason reason) {
        Objects.requireNonNull(event, "event is required");
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
                event.maskedCardNumber(),
                event.amount(),
                alphanumeric(event.transactionTypeCode(), PicClause.DALYTRAN_TYPE_CD_WIDTH),
                numeric(event.merchantCategoryCode(), PicClause.DALYTRAN_CAT_CD_WIDTH),
                numeric(event.merchantId(), PicClause.DALYTRAN_MERCHANT_ID_WIDTH),
                alphanumeric(event.authorizedAt(), PicClause.DALYTRAN_ORIG_TS_WIDTH),
                clock.instant()));

        outbox.write(declined(event, reason));
    }

    /**
     * Renders {@code REJECT-TRAN-DATA PIC X(350)} in {@code app/cpy/CVTRA06Y.cpy:L5-L18} order.
     *
     * <p>{@code DALYTRAN-PROC-TS} stays blank: {@code app/cbl/CBTRN02C.cbl:L437-L438} stamps it on
     * the posting path alone, and the field is blank in all 300 records of
     * {@code app/data/ASCII/dailytran.txt}.
     *
     * @param event the refused transaction
     * @return 350 characters
     */
    private static String rejectTranData(TransactionAuthorized event) {
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
                .append(alphanumeric(event.authorizedAt(), PicClause.DALYTRAN_ORIG_TS_WIDTH))
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
     * Builds the declined event, keyed on the account identifier the refused event carries.
     *
     * <p>{@code recordReject} has already refused a reason that resolves no account, so the account
     * identifier here is the one the cross-reference read at
     * {@code app/cbl/CBTRN02C.cbl:L383} held.
     *
     * @param event  the refused transaction
     * @param reason the validation failure the caller established
     * @return the event, carrying a freshly stamped envelope
     */
    private static TransactionDeclined declined(TransactionAuthorized event, DeclineReason reason) {
        return TransactionDeclined.of(event.accountId(), event.transactionId(), reason,
                event.amount(), event.maskedCardNumber());
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
}
