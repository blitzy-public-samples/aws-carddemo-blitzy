package com.carddemo.card.outbox;

import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.correlation.EventCorrelation;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores one {@code CardUpdated} event in {@code outbox_event}, and publishes nothing.
 *
 * <p>No CardDemo program, copybook or job stores an event row. The one ancestor construct
 * is the transient data queue write of the Customer Information Control System (CICS) at
 * {@code app/cbl/CORPT00C.cbl:L515-L523}. Paragraph {@code WIRTE-JOBSUB-TDQ.} hands one record to a
 * job that runs later.
 *
 * <p>The card row and this row commit together, or neither commits. {@link
 * #writeCardUpdated(CardEntity)} carries {@code @Transactional(propagation = MANDATORY)}, so it
 * joins the transaction its caller opened and cannot start one: a call arriving with no transaction
 * in progress is refused rather than committing an outbox row on its own, which would leave an event
 * behind with no card change under it. The caller owns that boundary. Neither that atomicity nor the
 * idempotency the primary key of the table carries has a COBOL ancestor.
 *
 * <p>The source offers no atomicity to reproduce. {@code app/cbl/CBTRN02C.cbl:L440-L442} runs three
 * writes under no condition and tests no status between them. All eight file definitions of
 * {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, eight
 * occurrences of each.
 *
 * <p>The source offers no duplicate detection either. Paragraph
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} writes at
 * {@code :L564} and tests the file status against {@code '00'} at {@code :L566}. Any other status
 * reaches {@code PERFORM 9999-ABEND-PROGRAM} at {@code :L577}, and a duplicate key is one such
 * status.
 *
 * <p>One call stores one row. The card list path and the card detail path store nothing here.
 *
 * <p>The masked card number has no COBOL ancestor. The source masks nothing: {@code
 * app/bms/COCRDSL.bms:L96} defines the card detail field unprotected, with {@code LENGTH=16} at
 * {@code :L99}. A card read keys on all sixteen characters of the Primary Account Number (PAN), so
 * this class takes the stored value and {@link CardUpdated#ofUnmaskedCardNumber} masks it. The card
 * verification value reaches no row and no log line here, and {@link CardEntity} publishes no
 * accessor for it.
 *
 * <p>This class holds no broker type, starts no thread, and writes no {@code processed_event}
 * marker. That marker guards an inbound delivery, and this service registers no consumer.
 */
@Component
public class OutboxWriter {

    /** Records one line per stored row, naming no value the card or its holder carries. */
    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    /**
     * Characters the account key holds, from {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}.
     *
     * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} carries the same width,
     * and column {@code aggregate_id} is fixed-width character storage of it. One name holds the
     * width for this class.
     */
    private static final int ACCOUNT_KEY_WIDTH = PicClause.CARD_ACCT_ID_WIDTH;

    /** The digit this class pads an account key with on the left. */
    private static final String ACCOUNT_KEY_PAD = "0";

    /** {@link CardUpdated#ACCOUNT_ID_PATTERN} compiled once: exactly eleven decimal digits. */
    private static final Pattern ACCOUNT_KEY_MATCHER =
            Pattern.compile(CardUpdated.ACCOUNT_ID_PATTERN);

    /**
     * Writes the ten characters of {@code CARD-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1505-L1507} slices that field into a four-character year, a
     * two-character month and a two-character day, which fixes the layout this formatter writes.
     */
    private static final DateTimeFormatter EXPIRATION_DATE_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE;

    /** Stores the row, through the {@code save} it inherits. */
    private final OutboxEventRepository outboxEvents;

    /**
     * Stamps {@code created_at}, in Coordinated Universal Time.
     *
     * <p>The relay reads unpublished rows oldest first, so one zone governs every row.
     */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the store this writer saves through.
     *
     * @param outboxEvents store of unpublished events
     * @throws NullPointerException when {@code outboxEvents} is {@code null}
     */
    public OutboxWriter(OutboxEventRepository outboxEvents) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
    }

    /**
     * Stores one card update as an unpublished {@code outbox_event} row.
     *
     * <p>The card update path calls this method with the card as it stands after
     * {@link CardEntity#applyUpdate(String, LocalDate, String)}. A card list, a card read, a
     * rejected update and a conflicting update store nothing. {@code app/cbl/COCRDUPC.cbl:L188}
     * answers an unchanged record with
     * {@code 'No change detected with respect to values fetched.'}, and
     * {@code app/cbl/COCRDUPC.cbl:L1511} sets {@code DATA-WAS-CHANGED-BEFORE-UPDATE} on a
     * conflict.
     *
     * <p>{@code @Transactional(propagation = MANDATORY)} makes the caller's transaction a
     * precondition: this method joins it and starts none of its own, and a call made with no
     * transaction in progress raises
     * {@link org.springframework.transaction.IllegalTransactionStateException} before any row is
     * built. A failure anywhere in that unit of work leaves no row.
     *
     * <p>Four payload values come from the card, and the event stamps its own identifier, type,
     * contract version and moment. The row takes that event identifier as its primary key, so one
     * event yields one row and a second store of the same event fails on the key. On insert
     * {@code published} is {@code false}, and the relay alone turns it true.
     *
     * <p>The account key reaches the message key column, the two account properties of the payload,
     * and nothing else. No message thrown here holds a card number, an account identifier or an
     * cardholder name.
     *
     * @param card the card as it stands after the update, carrying the stored card number
     * @return the row saved, keyed on the event identifier
     * @throws NullPointerException     when {@code card} is {@code null}, or when the card carries
     *                                  no expiry date
     * @throws IllegalArgumentException when the card carries no eleven-digit account identifier, or
     *                                  when the written payload breaks its contract document
     * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
     *                                  in progress
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEventEntity writeCardUpdated(CardEntity card) {
        Objects.requireNonNull(card, "card must be present");

        String accountKey = accountKeyOf(card);
        CardUpdated event = CardUpdated.ofUnmaskedCardNumber(card.getCardNumber(), accountKey,
                expirationTextOf(card.getExpirationDate()), card.getActiveStatus());
        String payload = writeAndCheck(event);

        OutboxEventEntity row = outboxEvents.save(correlated(new OutboxEventEntity(
                event.eventId(), event.eventType(), event.aggregateId(), payload,
                clock.instant())));

        log.info("Stored card event {} of type {}, payload length {}", row.getEventId(),
                row.getEventType(), payload.length());
        return row;
    }

    /**
     * Stamps one row with the two correlation identifiers the writing thread is working under.
     *
     * <p>ADDITIVE. The values come from the ambient scope
     * {@code config/CorrelationContextFilter} or the listener opened, rather than from a parameter,
     * so no domain method between that scope and this writer carries an identifier it does not
     * otherwise use.
     *
     * <p>A row written outside any scope starts its own trace: it adopts its own event identifier
     * as the correlation identifier, so every published record carries one and a reader can always
     * join a record to what followed it. Causation stays absent on such a row, because nothing
     * caused it, and an absent causation contributes no record header when
     * {@code outbox/OutboxRelay} publishes the row.
     *
     * @param row the row about to be saved
     * @return the same row, stamped
     */
    private static OutboxEventEntity correlated(OutboxEventEntity row) {
        row.recordCorrelation(
                EventCorrelation.currentCorrelationId().orElseGet(row::getEventId),
                EventCorrelation.currentEventId().orElse(null));
        return row;
    }

    /**
     * Reads the eleven-digit account key the row records.
     *
     * <p>The key travels as text, so a leading zero belongs to the value and survives. Account
     * seven renders as ten zeros followed by a seven. Column {@code account_id} is fixed-width
     * character storage, so this method drops the padding it returns, then pads on the left.
     *
     * <p>Keying every event of one account on this value keeps those events on one partition and in
     * store order.
     *
     * @param card the card as it stands after the update
     * @return the account key, exactly eleven decimal digits
     * @throws IllegalArgumentException when the card carries another key form. The message names
     *                                  the pattern and the length, and never the value
     */
    private static String accountKeyOf(CardEntity card) {
        String stored = card.getAccountId();
        String key = stored == null ? "" : stored.trim();
        if (key.length() < ACCOUNT_KEY_WIDTH) {
            key = ACCOUNT_KEY_PAD.repeat(ACCOUNT_KEY_WIDTH - key.length()) + key;
        }
        if (!ACCOUNT_KEY_MATCHER.matcher(key).matches()) {
            throw new IllegalArgumentException("the account identifier must match "
                    + CardUpdated.ACCOUNT_ID_PATTERN + ", the form column aggregate_id records,"
                    + " and the supplied card holds " + key.length() + " characters");
        }
        return key;
    }

    /**
     * Writes one expiry date as the ten characters the contract carries.
     *
     * <p>Column {@code expiration_date} holds a date, and
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9} holds ten
     * characters. {@link CardUpdated} refuses any other width.
     *
     * @param expirationDate the expiry date the card carries after the update
     * @return the expiry date as ten characters: a four-digit year, a month and a day
     * @throws NullPointerException when {@code expirationDate} is {@code null}
     */
    private static String expirationTextOf(LocalDate expirationDate) {
        Objects.requireNonNull(expirationDate, "expirationDate must be present");

        return EXPIRATION_DATE_FORMAT.format(expirationDate);
    }

    /**
     * Writes one event to text through the one publish-side gate every event of this platform
     * passes.
     *
     * <p>{@link CardUpdated#toValidatedJson()} does the work. It serializes through
     * {@code com.carddemo.events.serde.JsonSchemaValidatingSerializer}, which screens the document
     * for a forbidden property and for a card number or government identifier in free text, checks
     * it against {@code card-updated-v1.json}, and refuses one past the platform byte ceiling. The
     * check runs before the row is saved, so a payload the contract refuses leaves the caller's
     * transaction able to roll back with nothing stored.
     *
     * <p>The document is flat. Five envelope properties sit beside four payload properties, and
     * {@code card-updated-v1.json} names all nine in one {@code required} array. That document
     * closes its property set, so an undeclared property fails this check.
     *
     * <p>The gate reports a refusal as a {@code SerializationException}, which names a Kafka
     * concern this transaction has not reached: nothing is published here, and the caller is a
     * request handler. This method therefore reports the same refusal as an
     * {@link IllegalArgumentException}, keeping the cause. Every message the gate writes holds JSON
     * pointers, broken keywords and property names, so no card number and no account identifier
     * reaches a log through it. {@link OutboxEventEntity} holds the stored text to the width of its
     * own column and never shortens it.
     *
     * @param event the event to write
     * @return the event as JSON text, validated
     * @throws IllegalArgumentException when the written payload breaks its contract document
     */
    private String writeAndCheck(CardUpdated event) {
        try {
            return event.toValidatedJson();
        } catch (SerializationException refused) {
            throw new IllegalArgumentException(refused.getMessage(), refused);
        }
    }
}
