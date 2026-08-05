package com.carddemo.card.outbox;

import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.serde.EventContracts;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores one {@code CardUpdated} event in {@code outbox_event}, and publishes nothing.
 *
 * <p>No CardDemo program, copybook or job stores an event row. The one ancestor construct
 * is the transient data queue write of the Customer Information Control System (CICS) at
 * {@code app/cbl/CORPT00C.cbl:L515-L523}. Paragraph {@code WIRTE-JOBSUB-TDQ.} hands one record to a
 * job that runs later.
 *
 * <p>The card row and this row commit together, or neither commits. {@link
 * #writeCardUpdated(CardEntity)} joins the transaction its caller opened and starts none, so the
 * caller owns that boundary. Neither that atomicity nor the idempotency the primary key of the
 * table carries has a COBOL ancestor.
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
     * Writes one event to JavaScript Object Notation (JSON) text. Jackson 3, matching the platform.
     *
     * <p>The mapper belongs to this class. A payload is therefore shaped by the contract of its own
     * event, and by no setting of the web layer. {@code messaging/KafkaEventPublisher} owns its
     * mapper on the same footing.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * <p>{@code @Transactional} carries the default propagation, so this method joins the
     * transaction its caller opened and starts none of its own. A failure anywhere in that unit of
     * work leaves no row.
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
     */
    @Transactional
    public OutboxEventEntity writeCardUpdated(CardEntity card) {
        Objects.requireNonNull(card, "card must be present");

        String accountKey = accountKeyOf(card);
        CardUpdated event = CardUpdated.ofUnmaskedCardNumber(card.getCardNumber(), accountKey,
                expirationTextOf(card.getExpirationDate()), card.getActiveStatus());
        String payload = writeAndCheck(event);

        OutboxEventEntity row = outboxEvents.save(new OutboxEventEntity(event.eventId(),
                event.eventType(), event.aggregateId(), payload, clock.instant()));

        log.info("Stored card event {} of type {}, payload length {}", row.getEventId(),
                row.getEventType(), payload.length());
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
     * Writes one event to text and measures that text against its contract document.
     *
     * <p>{@link EventContracts} holds the one registry pairing an event type with its schema
     * document. The check runs before the row is saved, so a payload the contract refuses leaves
     * the caller's transaction able to roll back with nothing stored.
     *
     * <p>The document is flat. Five envelope properties sit beside four payload properties, and
     * {@code card-updated-v2.json} names all nine in one {@code required} array. That document
     * closes its property set, so an undeclared property fails this check.
     *
     * <p>A refusal message holds JSON pointers and broken keywords, so no card number and no
     * account identifier reaches a log through it. {@link OutboxEventEntity} holds the stored text
     * to the width of its own column and never shortens it.
     *
     * @param event the event to write
     * @return the event as JSON text
     * @throws IllegalArgumentException when the written payload breaks its contract document
     */
    private String writeAndCheck(CardUpdated event) {
        String payload = objectMapper.writeValueAsString(event);

        List<String> violations = EventContracts.violationsOf(event.eventType(), payload);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    EventContracts.describeViolations(event.eventType(), violations));
        }
        return payload;
    }
}
