package com.carddemo.card.domain;

import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.CardCrossReferenceEntity;
import com.carddemo.card.outbox.OutboxWriter;
import com.carddemo.card.repository.CardCrossReferenceRepository;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PicClause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates one card, and writes the event that update produces.
 *
 * <p>Transformed from the card update program {@code app/cbl/COCRDUPC.cbl}. Its edit chain sits in
 * {@code 1200-EDIT-MAP-INPUTS} at {@code app/cbl/COCRDUPC.cbl:L641}, its write in
 * {@code 9600-WRITE-PROCESSING} at {@code app/cbl/COCRDUPC.cbl:L1418}, and its concurrency
 * comparison in {@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:L1498}.
 *
 * <h2>The order of the steps is the contract</h2>
 *
 * <p>The source runs its checks in one order and this class runs them in the same one, because the
 * order decides which of seven answers a caller receives. A caller that submits a bad month for a
 * card number that names no row reads {@code Did not find cards for this search condition} and not
 * the month text, because the read precedes the field edits.
 *
 * <ol>
 * <li><b>The search key.</b> {@code 1220-EDIT-CARD} at {@code app/cbl/COCRDUPC.cbl:L762} refuses a
 * missing or malformed card number before anything is read.</li>
 * <li><b>The read.</b> {@code 9000-READ-DATA} reaches the card read at
 * {@code app/cbl/COCRDUPC.cbl:L1394}, whose not-found branch at
 * {@code app/cbl/COCRDUPC.cbl:L1399-L1400} sets the text. Nothing below runs when no row
 * answers.</li>
 * <li><b>The no-change comparison.</b> {@code app/cbl/COCRDUPC.cbl:L680-L682} compares the whole
 * submitted card group against the whole stored one, upper-cased on both sides, and
 * {@code app/cbl/COCRDUPC.cbl:L685-L693} then skips every field edit. A caller who submits the
 * stored values therefore receives no field message even when a field would have failed one.</li>
 * <li><b>The field edits.</b> {@code 1230-EDIT-NAME}, {@code 1240-EDIT-CARDSTATUS},
 * {@code 1250-EDIT-EXPIRY-MON} and {@code 1260-EDIT-EXPIRY-YEAR} run in that order from
 * {@code app/cbl/COCRDUPC.cbl:L698-L708}. The first failing edit owns the message, because every
 * write to {@code WS-RETURN-MSG} after the first sits behind the {@code WS-RETURN-MSG-OFF}
 * guard.</li>
 * <li><b>The lock.</b> {@code app/cbl/COCRDUPC.cbl:L1427} reads the row for update and
 * {@code app/cbl/COCRDUPC.cbl:L1441-L1446} reports a failure to acquire it.</li>
 * <li><b>The concurrency comparison.</b> {@code app/cbl/COCRDUPC.cbl:L1503-L1508} compares the
 * locked row against the values the caller last saw, and
 * {@code app/cbl/COCRDUPC.cbl:L1511-L1517} reports the difference and refreshes those values.</li>
 * <li><b>The write.</b> {@code app/cbl/COCRDUPC.cbl:L1477-L1483} rewrites the row and
 * {@code app/cbl/COCRDUPC.cbl:L1488-L1491} reports a rewrite that failed after the lock was
 * held.</li>
 * </ol>
 *
 * <h2>Why the field edits run here and not at the boundary</h2>
 *
 * <p>{@link CardUpdateRequest} carries one constraint per edit and names the source text of each,
 * and its documentation hands the edit order to its caller. This class is that caller. It validates
 * one property at a time, in the order above, so the first failing edit owns the answer exactly as
 * the guarded writes to {@code WS-RETURN-MSG} arrange. A single {@code @Valid} at the boundary would
 * report an unordered set of violations and would report it before the read, which changes the
 * answer for two of the seven outcomes.
 *
 * <h2>Where the transaction boundary sits, and why</h2>
 *
 * <p>{@link #updateCard(CardUpdateRequest)} opens no transaction. It reads the stored card through
 * {@link CardQueryService}, which opens and closes a read-only transaction of its own, and then
 * calls {@link #applyUpdate} through {@link #self}, so that call crosses the proxy and opens the
 * writing transaction.
 *
 * <p>That split is what makes step six mean anything. Both reads inside one transaction would share
 * one persistence context, the locked read would return the instance the first read had already
 * loaded, and the comparison would compare a value against itself. Two transactions put a real
 * window between the read and the lock, which is the window the source has between its display read
 * and its read for update.
 *
 * <p>The card row and the event row commit together, or neither commits.
 * {@link OutboxWriter#writeCardUpdated(CardEntity)} joins the transaction this class opened. That
 * atomicity is ADDITIVE: {@code app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)} and
 * {@code JOURNAL(NO)} on all eight of its file definitions, so the source offers none to reproduce.
 *
 * <h2>What this class does not add</h2>
 *
 * <p>No checksum rule, no account-status test and no card-status test.
 * {@code app/cbl/COCRDUPC.cbl:L784} tests a card number for sixteen digits and nothing else, and the
 * update path reads no account row at all. Each addition would refuse an update the source applies.
 *
 * <p>The account identifier and the card verification value are never written.
 * {@code app/bms/COCRDUP.bms} declares five editable fields, {@code CRDNAME} at L107,
 * {@code CRDSTCD} at L117, {@code EXPMON} at L127, {@code EXPYEAR} at L135 and {@code EXPDAY} at
 * L142, and {@link CardEntity#applyUpdate} changes exactly the three values those five carry.
 *
 * <h2>The cross-reference replica</h2>
 *
 * <p>This path writes no cross-reference row and can write none. Every column of
 * {@code app/cpy/CVACT03Y.cpy} is a card number, a customer identifier or an account identifier, and
 * this path changes none of the three, so there is nothing to keep current. A row it found missing
 * could not be created either, because {@code XREF-CUST-ID PIC 9(09)} at
 * {@code app/cpy/CVACT03Y.cpy:L6} is a column the card record does not carry. The replica's opening
 * state comes from {@code V2__seed.sql} and nothing in this platform writes it afterwards, because
 * no operation in scope issues a card or moves one between accounts.
 *
 * <p>What this path does owe the replica is a comparison, and
 * {@link #recordCrossReferenceDivergence} makes it inside the writing transaction. This service is
 * the only one holding both the account of the card row and the account the replica names for that
 * card, and a disagreement between them puts the published event on one account's partition while
 * every authorization decision about the card reads another. The comparison changes no outcome; it
 * moves a counter and writes one line.
 *
 * <p>One cross-field rule of the source is unreachable here and is deliberately not reproduced.
 * {@code app/cbl/COCRDUPC.cbl:L656-L659} answers {@code No input received} when the account filter
 * and the card filter are both absent. {@link CardUpdateRequest} carries no account component,
 * because {@code app/cbl/COCRDUPC.cbl:L1427-L1430} keys the read for update on the card number
 * alone, so the account half of that condition can never hold.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Service
public class CardUpdateService {

    /** Records one line per outcome, naming no card number and no cardholder name. */
    private static final Logger log = LoggerFactory.getLogger(CardUpdateService.class);

    /**
     * The one property step one validates, reproducing {@code 1220-EDIT-CARD} at
     * {@code app/cbl/COCRDUPC.cbl:L762}.
     */
    static final List<String> SEARCH_KEY_PROPERTIES = List.of("cardNumber");

    /**
     * The properties step four validates, in the order the source performs their edits.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L698-L708} performs {@code 1230-EDIT-NAME} at L806,
     * {@code 1240-EDIT-CARDSTATUS} at L845, {@code 1250-EDIT-EXPIRY-MON} at L877 and
     * {@code 1260-EDIT-EXPIRY-YEAR} at L913 in exactly that sequence. The day comes last and carries a
     * width constraint alone: the source edits no day, which
     * {@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_DAY_WIDTH} records.
     */
    static final List<String> DATA_PROPERTIES =
            List.of("embossedName", "activeStatus", "expiryMonth", "expiryYear", "expiryDay");

    /**
     * Digits a card number holds, from {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5}.
     */
    private static final int CARD_NUMBER_WIDTH = PicClause.CARD_NUM_WIDTH;

    /**
     * Characters the embossed name holds, from {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     *
     * <p>Column {@code embossed_name} is {@code bpchar(50)}, so a stored value returns padded to
     * this width. The comparison below pads the submitted value to the same width, which is what
     * {@code app/cbl/COCRDUPC.cbl:L680-L681} compares: two fixed-width groups, not two trimmed
     * strings.
     */
    private static final int EMBOSSED_NAME_WIDTH = PicClause.CARD_EMBOSSED_NAME_WIDTH;

    /** Characters the expiry year holds, from {@code CCUP-NEW-EXPYEAR PIC X(4)} at L310. */
    private static final int EXPIRY_YEAR_WIDTH = 4;

    /** Characters the expiry month holds, from {@code CCUP-NEW-EXPMON PIC X(2)} at L311. */
    private static final int EXPIRY_MONTH_WIDTH = 2;

    /** Characters the expiry day holds, from {@code CCUP-NEW-EXPDAY PIC X(2)} at L312. */
    private static final int EXPIRY_DAY_WIDTH = 2;

    /** Characters the active status holds, from {@code CARD-ACTIVE-STATUS PIC X(01)} at L10. */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /** The character a fixed-width alphanumeric field pads with on the right. */
    private static final String FIELD_PAD = " ";

    /** The digit a numeric display field pads with on the left. */
    private static final String KEY_PAD = "0";

    /**
     * Reject reason a cross-reference miss produces in the authorization path, named in the
     * diagnostic a missing replica row writes.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L385-L387} moves 100 into the failure reason and the text
     * {@code INVALID CARD NUMBER FOUND} beside it. The value is held as text here because it is
     * written into a log line and never compared, and because the platform carries the same code as
     * {@code 0100} in its event contract.
     */
    private static final String XREF_MISS_DECLINE_REASON = "100";

    /** Reads and writes the card table. */
    private final CardRepository cards;

    /**
     * Reads the {@code card_xref} replica, to compare the account the two copies name.
     *
     * <p>This is the only reader of that table in this service, and the comparison it feeds is the
     * only reason the table is here: no operation this platform serves changes a cross-reference
     * row, so the replica has no writer and needs none. It could not have one even if an operation
     * wanted it, because {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6} is a
     * column the card record does not carry, so this service cannot construct a cross-reference row
     * from a card row. {@code V2__seed.sql} loads the opening state from
     * {@code app/data/ASCII/cardxref.txt} and nothing writes it afterwards.
     */
    private final CardCrossReferenceRepository crossReferences;

    /** Reads the stored card in a transaction of its own, before the lock is taken. */
    private final CardQueryService cardQueries;

    /** Stores the {@code CardUpdated} row that commits with the card row. */
    private final OutboxWriter outboxWriter;

    /** Applies the constraints {@link CardUpdateRequest} declares, one property at a time. */
    private final Validator validator;

    /** Counts updates that committed. */
    private final Counter updatesApplied;

    /** Counts updates refused because another writer changed the row first. */
    private final Counter updateConflicts;

    /** Counts updates that failed on infrastructure after the lock was held. */
    private final Counter infrastructureFailures;

    /** Counts updates whose row and cross-reference replica named different accounts. */
    private final Counter crossReferenceDivergences;

    /** Times one update, from entry to commit. */
    private final Timer updateLatency;

    /**
     * Supplies this bean through its own proxy, so {@link #applyUpdate} runs inside a transaction.
     *
     * <p>A direct call from {@link #updateCard} would bypass the proxy and run the annotated method
     * with no transaction at all, and the locked read, the rewrite and the event row would then
     * commit one at a time. Every consumer of this platform uses the same indirection for the same
     * reason.
     */
    private final ObjectProvider<CardUpdateService> self;

    /**
     * Takes the two repositories, the read side, the outbox writer, the validator and the five
     * meters.
     *
     * @param cards                     the repository over table {@code card}
     * @param crossReferences           the repository over the {@code card_xref} replica
     * @param cardQueries               the read side, which fetches the stored card
     * @param outboxWriter              the writer of the {@code CardUpdated} row
     * @param validator                 the validator that applies the request constraints
     * @param updatesApplied            counter of updates that committed
     * @param updateConflicts           counter of updates refused after a concurrent change
     * @param infrastructureFailures    counter of updates that failed after the lock was held
     * @param crossReferenceDivergences counter of updates whose two copies named different accounts
     * @param timers                    the latency timers of this service
     * @param self                      provider of this bean through its own proxy
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardUpdateService(CardRepository cards, CardCrossReferenceRepository crossReferences,
            CardQueryService cardQueries, OutboxWriter outboxWriter, Validator validator,
            @Qualifier("cardUpdatesAppliedCounter") Counter updatesApplied,
            @Qualifier("cardUpdateConflictCounter") Counter updateConflicts,
            @Qualifier("cardInfrastructureFailureCounter") Counter infrastructureFailures,
            @Qualifier("cardCrossReferenceDivergenceCounter") Counter crossReferenceDivergences,
            CardLatencyTimers timers, ObjectProvider<CardUpdateService> self) {
        this.cards = Objects.requireNonNull(cards, "cards is required");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences is required");
        this.cardQueries = Objects.requireNonNull(cardQueries, "cardQueries is required");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.updatesApplied = Objects.requireNonNull(updatesApplied, "updatesApplied is required");
        this.updateConflicts =
                Objects.requireNonNull(updateConflicts, "updateConflicts is required");
        this.infrastructureFailures = Objects.requireNonNull(infrastructureFailures,
                "infrastructureFailures is required");
        this.crossReferenceDivergences = Objects.requireNonNull(crossReferenceDivergences,
                "crossReferenceDivergences is required");
        this.updateLatency = Objects.requireNonNull(timers, "timers is required").cardUpdate();
        this.self = Objects.requireNonNull(self, "self is required");
    }

    /**
     * Applies one card update and answers with the outcome the source would have displayed.
     *
     * <p>The whole call is timed, whichever of the seven outcomes it reaches, so the latency of a
     * refused update is measured alongside the latency of an applied one.
     *
     * @param request the submitted update, as it arrived
     * @return the outcome, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public CardUpdateResponse updateCard(CardUpdateRequest request) {
        Objects.requireNonNull(request, "request is required");

        long startedAt = System.nanoTime();
        try {
            return decide(request);
        } finally {
            updateLatency.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Runs the seven steps in order and answers with the first outcome one of them reaches.
     *
     * @param request the submitted update
     * @return the outcome
     */
    private CardUpdateResponse decide(CardUpdateRequest request) {
        if (isAbsent(request.cardNumber(), CARD_NUMBER_WIDTH)) {
            log.info("A card update supplied no card number");
            return CardUpdateResponse.validationRejected(CardValidationMessages.PROMPT_FOR_CARD);
        }
        String searchKeyFailure = firstFailingMessage(request, SEARCH_KEY_PROPERTIES);
        if (searchKeyFailure != null) {
            log.info("A card update supplied a card number the search-key edit refused");
            return CardUpdateResponse.validationRejected(searchKeyFailure);
        }

        Optional<CardEntity> stored =
                cardQueries.findByCardNumber(padKey(request.cardNumber()));
        if (stored.isEmpty()) {
            log.info("A card update named no stored card");
            return CardUpdateResponse.cardNotFound();
        }

        RefreshedCard fetched = snapshotOf(stored.get());
        if (submittedMatches(request, fetched)) {
            log.info("A card update submitted the values already stored");
            return CardUpdateResponse.noChangeDetected();
        }

        String dataFailure = firstFailingMessage(request, DATA_PROPERTIES);
        if (dataFailure != null) {
            log.info("A card update failed one field edit");
            return CardUpdateResponse.validationRejected(dataFailure);
        }

        LocalDate expiration = expirationDateOf(request);
        if (expiration == null) {
            log.info("A card update named a day that is not on the calendar");
            return CardUpdateResponse.validationRejected(
                    CardValidationMessages.ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE);
        }

        try {
            return self.getObject().applyUpdate(request, fetched, expiration);
        } catch (UpdateFailedAfterLock failed) {
            infrastructureFailures.increment();
            log.warn("A card update failed after its row was locked", failed);
            return CardUpdateResponse.updateFailedAfterLock();
        }
    }

    /**
     * Locks the row, compares it against what the caller last saw, and rewrites it.
     *
     * <p>{@code @Transactional} carries the default propagation and the default read-write mode, so
     * this method opens the writing transaction when {@link #updateCard} calls it through
     * {@link #self}. The card row and the event row commit inside it.
     *
     * @param request    the submitted update, whose components have passed every edit
     * @param fetched    the five values the caller last saw, read before this transaction opened
     * @param expiration the expiry date the three submitted parts name
     * @return the outcome: applied, refused for a concurrent change, or refused for a lock this
     *         method could not take
     * @throws NullPointerException  if any argument is {@code null}
     * @throws UpdateFailedAfterLock when the rewrite or the event row fails once the row is held,
     *                               which rolls this transaction back
     */
    @Transactional
    public CardUpdateResponse applyUpdate(CardUpdateRequest request, RefreshedCard fetched,
            LocalDate expiration) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(fetched, "fetched is required");
        Objects.requireNonNull(expiration, "expiration is required");

        Optional<CardEntity> locked =
                cards.findForUpdateByCardNumber(padKey(request.cardNumber()));
        if (locked.isEmpty()) {
            log.info("A card update could not lock the row it had just read");
            return CardUpdateResponse.lockNotAcquired();
        }

        CardEntity card = locked.get();
        RefreshedCard current = snapshotOf(card);
        if (!current.equals(fetched)) {
            updateConflicts.increment();
            log.info("A card update lost a race against another writer");
            return CardUpdateResponse.changedBeforeUpdate(current);
        }

        recordCrossReferenceDivergence(card);

        try {
            card.applyUpdate(padded(request.embossedName(), EMBOSSED_NAME_WIDTH), expiration,
                    request.activeStatus());
            cards.save(card);
            outboxWriter.writeCardUpdated(card);
        } catch (RuntimeException failure) {
            throw new UpdateFailedAfterLock(failure);
        }

        updatesApplied.increment();
        log.info("A card update committed and produced one event");
        return CardUpdateResponse.updated();
    }

    /**
     * Compares the account the locked card row names against the account the {@code card_xref}
     * replica names for the same card, and records a disagreement without changing the outcome.
     *
     * <p>This service is the only one holding both values.
     * {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6} is the account of the card
     * row, and it is the value {@code outbox/OutboxWriter} uses as the message key of the
     * {@code CardUpdated} event. {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
     * is the account the authorization decision resolves the same card to, reproducing the keyed
     * read at {@code app/cbl/CBTRN02C.cbl:L383-L387}. When the two differ, the event for a card
     * lands on one account's partition while every decision about that card reads another, and the
     * per-account ordering the platform rests on is gone with nothing reporting it.
     *
     * <p>The comparison runs inside the writing transaction, on the row this transaction has locked,
     * so the two values it compares are the two values that were true at the moment of the write.
     * Reading the replica outside the transaction would compare against a row that could have moved.
     *
     * <p>It decides nothing. The source performs no such comparison: {@code app/cbl/COCRDUPC.cbl}
     * reads {@code *COPY CVACT03Y.} commented out at {@code :L356} and its update path never opens
     * the cross-reference dataset, so refusing an update here would answer a text no card program
     * writes and would break equivalence. Transformation rule T7 governs this case, which is to
     * reproduce behaviour and surface what looks wrong rather than silently correct it.
     *
     * <p>An absent cross-reference row counts as a disagreement, because a card with no
     * cross-reference is a card every authorization declines with reason 100,
     * {@code INVALID CARD NUMBER FOUND} at {@code app/cbl/CBTRN02C.cbl:L385-L387}, while this
     * service continues to serve it as an active card. No row is written to repair it: the replica
     * needs {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6}, which the card record
     * does not carry, so this service cannot construct the row it is missing. The gap is reported
     * and left for the owner of the cross-reference to fill.
     *
     * <p>No log line and no meter tag carries a card number. The line names the account of the card
     * row and the account the replica named, both of which are account identifiers, and the counter
     * carries no identifier at all.
     *
     * @param card the locked card row, whose account is the authoritative one for this service
     */
    private void recordCrossReferenceDivergence(CardEntity card) {
        Optional<CardCrossReferenceEntity> crossReference =
                crossReferences.findByCardNumber(card.getCardNumber());

        if (crossReference.isEmpty()) {
            crossReferenceDivergences.increment();
            log.warn("A card update wrote a card the cross-reference replica does not hold, so"
                    + " every authorization for it declines with reason {} while this service"
                    + " serves it. Account {} on the card row.",
                    XREF_MISS_DECLINE_REASON, card.getAccountId());
            return;
        }

        String replicaAccount = crossReference.get().getAccountId();
        if (!card.getAccountId().equals(replicaAccount)) {
            crossReferenceDivergences.increment();
            log.warn("A card update wrote a card whose row names account {} and whose"
                    + " cross-reference replica names account {}, so the event key and the"
                    + " authorization decision disagree about which account the card belongs to.",
                    card.getAccountId(), replicaAccount);
        }
    }

    /**
     * Returns the text of the first failing edit among the named properties, in the order given.
     *
     * <p>Within one property a missing value is reported ahead of a malformed one. Every edit of
     * {@code app/cbl/COCRDUPC.cbl} tests {@code EQUAL LOW-VALUES} before it tests the character
     * class, at {@code app/cbl/COCRDUPC.cbl:L725} then {@code L740} for the account, at
     * {@code L768} then {@code L784} for the card, at {@code L811} then {@code L828} for the name,
     * at {@code L851} then {@code L863} for the status, and in the same shape for both expiry
     * parts. The constraint that carries a missing value is {@link NotBlank}, so that annotation
     * decides the precedence.
     *
     * <p>Two violations of one property that carry the same text collapse, and the remaining order
     * is the text itself. That keeps the answer of one request stable across runs, which an
     * unordered violation set does not.
     *
     * @param request    the submitted update
     * @param properties the properties to validate, in source edit order
     * @return the text of the first failing edit, or {@code null} when every named property passes
     */
    private String firstFailingMessage(CardUpdateRequest request, List<String> properties) {
        for (String property : properties) {
            Set<ConstraintViolation<CardUpdateRequest>> violations =
                    validator.validateProperty(request, property);
            if (violations.isEmpty()) {
                continue;
            }

            Set<String> missingValueTexts = new TreeSet<>();
            Set<String> malformedValueTexts = new TreeSet<>();
            for (ConstraintViolation<CardUpdateRequest> violation : violations) {
                if (violation.getConstraintDescriptor().getAnnotation() instanceof NotBlank) {
                    missingValueTexts.add(violation.getMessage());
                } else {
                    malformedValueTexts.add(violation.getMessage());
                }
            }
            return missingValueTexts.isEmpty() ? malformedValueTexts.iterator().next()
                    : missingValueTexts.iterator().next();
        }
        return null;
    }

    /**
     * Reports whether a numeric display field carries no value at all.
     *
     * <p>The blank test of every edit reads three conditions, and the third is the numeric redefine
     * against zero: {@code CC-CARD-NUM-N EQUAL ZEROS} at {@code app/cbl/COCRDUPC.cbl:L770}. Sixteen
     * zeros are therefore absent rather than malformed, and they take
     * {@link CardValidationMessages#PROMPT_FOR_CARD} rather than the character-class text.
     *
     * @param value the submitted value, which may be {@code null}
     * @param width the width the Picture clause declares
     * @return {@code true} when the value is missing, blank, or the pad digit repeated to width
     */
    private static boolean isAbsent(String value, int width) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.strip().equals(KEY_PAD.repeat(width));
    }

    /**
     * Reads the five values the concurrency comparison and the refresh both use.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1513-L1517} refreshes exactly these five once the comparison
     * fails. Every value is carried as text, matching the character slices the source compares at
     * {@code app/cbl/COCRDUPC.cbl:L1504-L1508}.
     *
     * <p>The source comparison opens on a sixth value, the card verification value at
     * {@code app/cbl/COCRDUPC.cbl:L1503}, and this snapshot carries five. The sixth is immutable
     * here: {@link CardEntity#applyUpdate} never writes it, {@link CardEntity} publishes no accessor
     * for it, and no other path of this service writes a card row, so no writer can change it
     * between the read and the lock. Carrying it would also put the value in a response body, which
     * {@code CardholderDataExposureTest} refuses. Dropping it can therefore change no outcome, and
     * it is the one difference between this comparison and the source's.
     *
     * @param card the card row to read
     * @return the snapshot
     */
    private static RefreshedCard snapshotOf(CardEntity card) {
        LocalDate expiration = card.getExpirationDate();
        return new RefreshedCard(padded(card.getEmbossedName(), EMBOSSED_NAME_WIDTH),
                digits(expiration.getYear(), EXPIRY_YEAR_WIDTH),
                digits(expiration.getMonthValue(), EXPIRY_MONTH_WIDTH),
                digits(expiration.getDayOfMonth(), EXPIRY_DAY_WIDTH),
                padded(card.getActiveStatus(), ACTIVE_STATUS_WIDTH));
    }

    /**
     * Reports whether the submitted values match the stored ones, upper-cased on both sides.
     *
     * <p>Reproduces {@code app/cbl/COCRDUPC.cbl:L680-L681}, which compares
     * {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA)} against
     * {@code FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)}. That group is opened at
     * {@code app/cbl/COCRDUPC.cbl:L307} and holds fifty characters of name, four of year, two of
     * month, two of day and one of status: fifty-nine characters, and the card number is not among
     * them.
     *
     * <p>Both sides are padded to those widths before the comparison, so a name of four letters
     * matches the same four letters stored in a fifty-character field. Both sides are upper-cased,
     * so a caller who changes only letter case reaches this branch, which is what
     * {@link CardUpdateResponse.UpdateOutcome#NO_CHANGE_DETECTED} records.
     *
     * @param request the submitted update
     * @param fetched the stored values
     * @return {@code true} when the two groups are equal
     */
    private static boolean submittedMatches(CardUpdateRequest request, RefreshedCard fetched) {
        String submitted = groupOf(request.embossedName(), request.expiryYear(),
                request.expiryMonth(), request.expiryDay(), request.activeStatus());
        String storedGroup = groupOf(fetched.embossedName(), fetched.expiryYear(),
                fetched.expiryMonth(), fetched.expiryDay(), fetched.activeStatus());
        return submitted.equals(storedGroup);
    }

    /**
     * Joins the five values into the fifty-nine characters the source compares, upper-cased.
     *
     * @param embossedName the embossed cardholder name
     * @param expiryYear   the four-character year slice
     * @param expiryMonth  the two-character month slice
     * @param expiryDay    the two-character day slice
     * @param activeStatus the one-character active status
     * @return the group, exactly fifty-nine characters when no value is wider than its field
     */
    private static String groupOf(String embossedName, String expiryYear, String expiryMonth,
            String expiryDay, String activeStatus) {
        return upperCased(padded(embossedName, EMBOSSED_NAME_WIDTH))
                + upperCased(padded(expiryYear, EXPIRY_YEAR_WIDTH))
                + upperCased(padded(expiryMonth, EXPIRY_MONTH_WIDTH))
                + upperCased(padded(expiryDay, EXPIRY_DAY_WIDTH))
                + upperCased(padded(activeStatus, ACTIVE_STATUS_WIDTH));
    }

    /**
     * Joins the three expiry parts into one date, or reports that they name no day.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1467-L1474} joins them with hyphens into ten characters of
     * text, which holds an impossible day as readily as a real one. Column {@code expiration_date}
     * is a {@code DATE}, so this platform refuses what the source would have stored, and
     * {@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE} carries the reason.
     *
     * @param request the submitted update, whose three expiry parts have passed their constraints
     * @return the date, or {@code null} when the three parts name no day of the calendar
     */
    private static LocalDate expirationDateOf(CardUpdateRequest request) {
        try {
            return LocalDate.of(Integer.parseInt(request.expiryYear()),
                    Integer.parseInt(request.expiryMonth()),
                    Integer.parseInt(request.expiryDay()));
        } catch (DateTimeException | NumberFormatException notADate) {
            return null;
        }
    }

    /**
     * Pads a numeric display value on the left with zeros to the width its key column holds.
     *
     * @param value the submitted value
     * @return the value at exactly {@value #CARD_NUMBER_WIDTH} characters
     */
    private static String padKey(String value) {
        String trimmed = value == null ? "" : value.strip();
        int missing = CARD_NUMBER_WIDTH - trimmed.length();
        return missing <= 0 ? trimmed : KEY_PAD.repeat(missing) + trimmed;
    }

    /**
     * Pads a value on the right with spaces to the width its Picture clause declares.
     *
     * @param value the value to pad, which may be {@code null}
     * @param width the width the Picture clause declares
     * @return the value at exactly {@code width} characters, or wider when it already is
     */
    private static String padded(String value, int width) {
        String present = value == null ? "" : value;
        int missing = width - present.length();
        return missing <= 0 ? present : present + FIELD_PAD.repeat(missing);
    }

    /**
     * Writes one number as fixed-width digits, left-padded with zeros.
     *
     * @param value the number to write
     * @param width the width to reach
     * @return the number as exactly {@code width} digits
     */
    private static String digits(int value, int width) {
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }

    /**
     * Upper-cases one value the way {@code FUNCTION UPPER-CASE} does.
     *
     * @param value the value to fold
     * @return the folded value
     */
    private static String upperCased(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    /**
     * Reports that the rewrite or the event row failed once the row was held.
     *
     * <p>Reproduces the condition {@code app/cbl/COCRDUPC.cbl:L1488} tests and
     * {@code app/cbl/COCRDUPC.cbl:L1491} reports. Throwing rather than returning is deliberate: the
     * transaction has to roll back, and a method that returned an outcome would commit whatever the
     * failing statement had already written.
     */
    public static final class UpdateFailedAfterLock extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Wraps the failure the write reported.
         *
         * @param cause the failure, whose message reaches no response body
         */
        UpdateFailedAfterLock(Throwable cause) {
            super("the card rewrite failed after its row was locked", cause);
        }
    }
}
