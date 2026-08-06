package com.carddemo.card.domain;

import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.outbox.OutboxWriter;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
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
 * Updates one card and writes the event that update produces.
 *
 * <p>Transformed from the card update program {@code app/cbl/COCRDUPC.cbl}. Its edit chain sits in
 * {@code 1200-EDIT-MAP-INPUTS.} at {@code app/cbl/COCRDUPC.cbl:L641-L714}, its write in
 * {@code 9200-WRITE-PROCESSING.} at {@code app/cbl/COCRDUPC.cbl:L1420-L1493}, and its concurrency
 * comparison in {@code 9300-CHECK-CHANGE-IN-REC.} at {@code app/cbl/COCRDUPC.cbl:L1498-L1523}.
 *
 * <h2>The order of the checks</h2>
 *
 * <p>Seven answers leave this class, and the order of the checks decides which one arrives. A
 * caller that submits a bad month for a card number naming no row reads
 * {@code 'Did not find cards for this search condition'}, the text at
 * {@code app/cbl/COCRDUPC.cbl:L204}, since the read runs ahead of the field edits.
 *
 * <ol>
 * <li>The search key. {@code 1220-EDIT-CARD.} at {@code app/cbl/COCRDUPC.cbl:L762-L800} refuses a
 * missing or malformed card number before anything is read.</li>
 * <li>The read. {@code 9100-GETCARD-BYACCTCARD.} at {@code app/cbl/COCRDUPC.cbl:L1376-L1417} sets
 * the not-found text at {@code app/cbl/COCRDUPC.cbl:L1400}.</li>
 * <li>The no-change comparison at {@code app/cbl/COCRDUPC.cbl:L680-L683}, which
 * {@code app/cbl/COCRDUPC.cbl:L685-L693} follows by skipping every field edit.</li>
 * <li>The four field edits, performed in order at {@code app/cbl/COCRDUPC.cbl:L698-L708}.</li>
 * <li>The lock. {@code app/cbl/COCRDUPC.cbl:L1427-L1436} reads the row for update and
 * {@code app/cbl/COCRDUPC.cbl:L1441-L1448} reports a lock it could not take.</li>
 * <li>The concurrency comparison at {@code app/cbl/COCRDUPC.cbl:L1503-L1508}, refreshed at
 * {@code app/cbl/COCRDUPC.cbl:L1512-L1517}.</li>
 * <li>The write at {@code app/cbl/COCRDUPC.cbl:L1477-L1483}, whose failure after the lock
 * {@code app/cbl/COCRDUPC.cbl:L1491} reports.</li>
 * </ol>
 *
 * <h2>Where a new edit goes</h2>
 *
 * <p>One edit is one constraint on {@link CardUpdateRequest} plus one text on
 * {@link CardValidationMessages}. A new edit needs those two additions and its property name in
 * {@link #DATA_PROPERTIES}, at the position the source performs it. This class holds no chain of
 * conditions to extend and no table of texts to keep in step.
 *
 * <p>{@code app/cbl/COCRDUPC.cbl:L377} of the batch posting program carries the comment
 * {@code * ADD MORE VALIDATIONS HERE}, which marks the same seam in the source.
 *
 * <h2>The transaction boundary</h2>
 *
 * <p>{@link #updateCard(CardUpdateRequest)} opens no transaction. It reads the stored card through
 * {@link CardQueryService}, which opens a read-only transaction of its own. It then calls
 * {@link #applyUpdate} through {@link #self}, so that call crosses the proxy and opens the writing
 * transaction. Two transactions leave a window between the read and the lock, which is the window
 * step six inspects. One transaction shares one persistence context, and the locked read then
 * answers with the instance the first read had already loaded.
 *
 * <p>The card row and the event row commit together or neither commits.
 * {@link OutboxWriter#writeCardUpdated(CardEntity)} joins the transaction this class opens. That
 * atomicity is ADDITIVE: every file definition in {@code app/csd/CARDDEMO.CSD} carries
 * {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 *
 * <h2>What this class does not add</h2>
 *
 * <p>No card-number arithmetic, no account-status test and no card-status test.
 * {@code app/cbl/COCRDUPC.cbl:L784} tests a card number for sixteen digits and nothing more.
 * {@code app/jcl/POSTTRAN.jcl:L23} allocates nine data definitions to the posting program and names
 * no card file, so no posting decision reads the status this class stores.
 *
 * <p>No expiry-day edit and no calendar-validity edit. The paragraph sequence runs 1230, 1240,
 * 1250, 1260 and then {@code 2000-DECIDE-ACTION.} at {@code app/cbl/COCRDUPC.cbl:L948}, with no
 * 1270 paragraph anywhere in the program.
 *
 * <p>No version column. The concurrency comparison is field level, over the values
 * {@code app/cbl/COCRDUPC.cbl:L1503-L1508} names.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Service
public class CardUpdateService {

    /** Records one line per outcome, carrying a masked card number and no cardholder value. */
    private static final Logger log = LoggerFactory.getLogger(CardUpdateService.class);

    /**
     * The one property step one validates, from {@code 1220-EDIT-CARD.} at
     * {@code app/cbl/COCRDUPC.cbl:L762-L800}.
     */
    static final List<String> SEARCH_KEY_PROPERTIES = List.of("cardNumber");

    /**
     * The properties step four validates, in the order the source performs their edits.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L698-L708} performs four paragraphs in this sequence:
     * {@code 1230-EDIT-NAME.} at {@code app/cbl/COCRDUPC.cbl:L806-L840},
     * {@code 1240-EDIT-CARDSTATUS.} at {@code app/cbl/COCRDUPC.cbl:L845-L873},
     * {@code 1250-EDIT-EXPIRY-MON.} at {@code app/cbl/COCRDUPC.cbl:L877-L908} and
     * {@code 1260-EDIT-EXPIRY-YEAR.} at {@code app/cbl/COCRDUPC.cbl:L913-L944}.
     *
     * <p>The name rule admits letters and spaces alone.
     * {@code app/cbl/COCRDUPC.cbl:L823-L826} converts every character of
     * {@code LIT-ALL-ALPHA-FROM PIC X(52)} at {@code app/cbl/COCRDUPC.cbl:L255-L257} to a space,
     * and {@code app/cbl/COCRDUPC.cbl:L828} then requires the field to trim to nothing. The blank
     * test at {@code app/cbl/COCRDUPC.cbl:L811-L813} runs ahead of that conversion, so a name of
     * spaces takes {@link CardValidationMessages#PROMPT_FOR_NAME}.
     *
     * <p>The day comes last and carries a width constraint alone, recorded as ADDITIVE by
     * {@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_DAY_WIDTH}. The source performs no day
     * edit, and the position of this entry keeps its text behind all four source texts.
     */
    static final List<String> DATA_PROPERTIES =
            List.of("embossedName", "activeStatus", "expiryMonth", "expiryYear", "expiryDay");

    /**
     * Digits a card number holds, from {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5}.
     */
    private static final int CARD_NUMBER_WIDTH = PicClause.CARD_NUM_WIDTH;

    /**
     * Digits an account identifier holds, from {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}. The key of the card row this class never rewrites.
     */
    static final int ACCOUNT_ID_WIDTH = PicClause.CARD_ACCT_ID_WIDTH;

    /**
     * Digits the card verification value holds, from {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}. Stored in the clear, and emitted nowhere.
     */
    static final int CARD_VERIFICATION_VALUE_WIDTH = PicClause.CARD_CVV_CD_WIDTH;

    /**
     * Characters the embossed name holds, from {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8} and {@code CARD-NAME-CHECK PIC X(50)} at
     * {@code app/cbl/COCRDUPC.cbl:L87}.
     *
     * <p>Column {@code embossed_name} is fixed-width character storage, so a stored value returns
     * padded to this width. Both sides of the comparison at
     * {@code app/cbl/COCRDUPC.cbl:L680-L681} are fixed-width groups.
     */
    private static final int EMBOSSED_NAME_WIDTH = PicClause.CARD_EMBOSSED_NAME_WIDTH;

    /**
     * Characters the expiry year slice holds, from {@code CARD-EXPIRY-YEAR PIC X(4)} at
     * {@code app/cbl/COCRDUPC.cbl:L117}.
     */
    private static final int EXPIRY_YEAR_WIDTH = PicClause.CARD_EXPIRATION_DATE_YEAR_WIDTH;

    /**
     * Characters the expiry month slice holds, from {@code CARD-EXPIRY-MONTH PIC X(2)} at
     * {@code app/cbl/COCRDUPC.cbl:L119}.
     */
    private static final int EXPIRY_MONTH_WIDTH = PicClause.CARD_EXPIRATION_DATE_MONTH_WIDTH;

    /**
     * Characters the expiry day slice holds, from {@code CARD-EXPIRY-DAY PIC X(2)} at
     * {@code app/cbl/COCRDUPC.cbl:L121}.
     */
    private static final int EXPIRY_DAY_WIDTH = PicClause.CARD_EXPIRATION_DATE_DAY_WIDTH;

    /**
     * Characters the active status holds, from {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10}.
     */
    private static final int ACTIVE_STATUS_WIDTH = PicClause.CARD_ACTIVE_STATUS_WIDTH;

    /**
     * Lowest month the expiry month edit admits, from
     * {@code 88 VALID-MONTH VALUES 1 THRU 12.} at {@code app/cbl/COCRDUPC.cbl:L95}.
     *
     * <p>{@link CardUpdateRequest} carries the bound as a constraint. This constant is what pins
     * that constraint to the source condition name.
     */
    static final int EXPIRY_MONTH_LOWER_BOUND = 1;

    /**
     * Highest month the expiry month edit admits, from
     * {@code 88 VALID-MONTH VALUES 1 THRU 12.} at {@code app/cbl/COCRDUPC.cbl:L95}.
     */
    static final int EXPIRY_MONTH_UPPER_BOUND = 12;

    /**
     * Lowest year the expiry year edit admits, from
     * {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99}.
     *
     * <p>{@link CardValidationMessages#CARD_EXPIRY_YEAR_NOT_VALID} names no bound, so only the
     * condition name states this one.
     */
    static final int EXPIRY_YEAR_LOWER_BOUND = 1950;

    /**
     * Highest year the expiry year edit admits, from
     * {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99}.
     */
    static final int EXPIRY_YEAR_UPPER_BOUND = 2099;

    /**
     * The two flags the active status edit admits, from
     * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91}.
     *
     * <p>The condition tests two upper-case literals, and no edit of the program folds the case of
     * this field. A lower-case flag fails.
     */
    static final List<String> ACTIVE_STATUS_FLAGS = List.of("Y", "N");

    /** The character a fixed-width alphanumeric field pads with on the right. */
    private static final String FIELD_PAD = " ";

    /** The digit a numeric display field pads with on the left. */
    private static final String KEY_PAD = "0";

    /** Reads and writes table {@code card}. */
    private final CardRepository cards;

    /** Reads the stored card in a transaction of its own, before the lock is taken. */
    private final CardQueryService cardQueries;

    /** Stores the event row that commits with the card row. */
    private final OutboxWriter outboxWriter;

    /** Applies the constraints {@link CardUpdateRequest} declares, one property at a time. */
    private final Validator validator;

    /** Counts updates that committed. */
    private final Counter updatesApplied;

    /** Counts updates refused after another writer changed the row first. */
    private final Counter updateConflicts;

    /** Counts updates that failed on infrastructure after the lock was held. */
    private final Counter infrastructureFailures;

    /** Times one update, from entry to commit. */
    private final Timer updateLatency;

    /**
     * Supplies this bean through its own proxy, so {@link #applyUpdate} runs inside a transaction.
     *
     * <p>A direct call from {@link #updateCard} bypasses the proxy, and the annotated method then
     * runs with no transaction at all.
     */
    private final ObjectProvider<CardUpdateService> self;

    /**
     * Takes the repository, the read side, the outbox writer, the validator and the four meters.
     *
     * @param cards                  the repository over table {@code card}
     * @param cardQueries            the read side, which fetches the stored card
     * @param outboxWriter           the writer of the event row
     * @param validator              the validator that applies the request constraints
     * @param updatesApplied         counter of updates that committed
     * @param updateConflicts        counter of updates refused after a concurrent change
     * @param infrastructureFailures counter of updates that failed after the lock was held
     * @param timers                 the latency timers of this service
     * @param self                   provider of this bean through its own proxy
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardUpdateService(CardRepository cards, CardQueryService cardQueries,
            OutboxWriter outboxWriter, Validator validator,
            @Qualifier("cardUpdatesAppliedCounter") Counter updatesApplied,
            @Qualifier("cardUpdateConflictCounter") Counter updateConflicts,
            @Qualifier("cardInfrastructureFailureCounter") Counter infrastructureFailures,
            CardLatencyTimers timers, ObjectProvider<CardUpdateService> self) {
        this.cards = Objects.requireNonNull(cards, "cards is required");
        this.cardQueries = Objects.requireNonNull(cardQueries, "cardQueries is required");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.updatesApplied = Objects.requireNonNull(updatesApplied, "updatesApplied is required");
        this.updateConflicts =
                Objects.requireNonNull(updateConflicts, "updateConflicts is required");
        this.infrastructureFailures = Objects.requireNonNull(infrastructureFailures,
                "infrastructureFailures is required");
        this.updateLatency = Objects.requireNonNull(timers, "timers is required").cardUpdate();
        this.self = Objects.requireNonNull(self, "self is required");
    }

    /**
     * Applies one card update and answers with the outcome the source displays.
     *
     * <p>Every one of the seven outcomes is timed, so a refused update is measured alongside an
     * applied one.
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
     * Runs the checks in source order and answers with the first outcome one of them reaches.
     *
     * <p>Steps one through four run here, from {@code 1200-EDIT-MAP-INPUTS.} at
     * {@code app/cbl/COCRDUPC.cbl:L641-L714}. Steps five through seven run in
     * {@link #applyUpdate}.
     *
     * @param request the submitted update
     * @return the outcome
     */
    private CardUpdateResponse decide(CardUpdateRequest request) {
        String masked = PanMasker.maskCardNumber(request.cardNumber());

        if (isAbsent(request.cardNumber(), CARD_NUMBER_WIDTH)) {
            log.info("A card update supplied no card number");
            return CardUpdateResponse.validationRejected(CardValidationMessages.PROMPT_FOR_CARD);
        }
        String searchKeyFailure = firstFailingMessage(request, SEARCH_KEY_PROPERTIES);
        if (searchKeyFailure != null) {
            log.info("A card update supplied a card number the search-key edit refused, {}",
                    masked);
            return CardUpdateResponse.validationRejected(searchKeyFailure);
        }

        Optional<CardEntity> stored = cardQueries.findByCardNumber(padKey(request.cardNumber()));
        if (stored.isEmpty()) {
            log.info("A card update named no stored card, {}", masked);
            return CardUpdateResponse.cardNotFound();
        }

        RefreshedCard fetched = snapshotOf(stored.get());
        if (submittedMatches(request, fetched)) {
            log.info("A card update submitted the values already stored, {}", masked);
            return CardUpdateResponse.noChangeDetected();
        }

        String dataFailure = firstFailingMessage(request, DATA_PROPERTIES);
        if (dataFailure != null) {
            log.info("A card update failed one field edit, {}", masked);
            return CardUpdateResponse.validationRejected(dataFailure);
        }

        LocalDate expiration = expirationDateOf(request, fetched.expiryDay());
        if (expiration == null) {
            log.info("A card update named a month the stored day does not reach, {}", masked);
            return CardUpdateResponse.validationRejected(
                    CardValidationMessages.ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE);
        }

        try {
            return self.getObject().applyUpdate(request, fetched, expiration);
        } catch (UpdateFailedAfterLock failed) {
            infrastructureFailures.increment();
            log.warn("A card update failed after its row was locked, {}", masked, failed);
            return CardUpdateResponse.updateFailedAfterLock();
        }
    }

    /**
     * Locks the row, compares it against what the caller last saw, and rewrites it.
     *
     * <p>Transformed from {@code 9200-WRITE-PROCESSING.} at
     * {@code app/cbl/COCRDUPC.cbl:L1420-L1493}. The lock is the Customer Information Control System
     * (CICS) {@code READ} carrying {@code UPDATE} at {@code app/cbl/COCRDUPC.cbl:L1429}, and the
     * rewrite is the {@code REWRITE} at {@code app/cbl/COCRDUPC.cbl:L1477-L1483}.
     *
     * <p>{@code @Transactional} carries the default propagation and the default read-write mode, so
     * this method opens the writing transaction when {@link #updateCard} calls it through
     * {@link #self}. The card row and the event row commit inside it.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1461-L1475} rewrites all six fields of the record. Here the
     * persistence layer issues the full-row statement and {@link CardEntity#applyUpdate} moves the
     * three values the map admits. The card number, the account identifier and the card verification
     * value are carried through, as {@code app/cbl/COCRDUPC.cbl:L1462-L1465} carries them.
     *
     * @param request    the submitted update, whose components have passed every edit
     * @param fetched    the five values the caller last saw, read before this transaction opened
     * @param expiration the expiry date the submitted year and month and the stored day name
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

        String masked = PanMasker.maskCardNumber(request.cardNumber());
        Optional<CardEntity> locked =
                cards.findForUpdateByCardNumber(padKey(request.cardNumber()));
        if (locked.isEmpty()) {
            log.info("A card update could not lock the row it had just read, {}", masked);
            return CardUpdateResponse.lockNotAcquired();
        }

        CardEntity card = locked.get();
        RefreshedCard current = snapshotOf(card);
        if (!current.equals(fetched)) {
            updateConflicts.increment();
            log.info("A card update lost a race against another writer, {}", masked);
            return CardUpdateResponse.changedBeforeUpdate(current);
        }

        try {
            card.applyUpdate(padded(request.embossedName(), EMBOSSED_NAME_WIDTH), expiration,
                    request.activeStatus());
            cards.save(card);
            outboxWriter.writeCardUpdated(card);
        } catch (RuntimeException failure) {
            throw new UpdateFailedAfterLock(failure);
        }

        updatesApplied.increment();
        log.info("A card update committed and produced one event, {}", masked);
        return CardUpdateResponse.updated();
    }

    /**
     * Returns the text of the first failing edit among the named properties, in the order given.
     *
     * <p>Every text the program sets sits behind {@code IF WS-RETURN-MSG-OFF}, the condition name
     * {@code VALUE SPACES} at {@code app/cbl/COCRDUPC.cbl:L174} on
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}. Fifteen such guards
     * stand across the program, and {@code app/cbl/COCRDUPC.cbl:L384} resets the field once per
     * request. Every edit runs and the first text set is the text reported.
     *
     * <p>This method runs every named property and reports one text. It does not stop at the first
     * failure and it does not answer with a list.
     *
     * <p>Within one property a missing value is reported ahead of a malformed one. Each edit tests
     * {@code EQUAL LOW-VALUES} before it tests the character class. The card number does so at
     * {@code app/cbl/COCRDUPC.cbl:L768} then {@code L784}, the name at
     * {@code app/cbl/COCRDUPC.cbl:L811} then {@code L828}, and the status at
     * {@code app/cbl/COCRDUPC.cbl:L851} then {@code L863}. The constraint carrying a missing value
     * is {@link NotBlank}, so that annotation sets the precedence.
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
     * <p>The blank test of each edit reads three conditions, and the third is the numeric redefine
     * against zero: {@code CC-CARD-NUM-N EQUAL ZEROS} at {@code app/cbl/COCRDUPC.cbl:L770}. Sixteen
     * zeros are therefore absent, and they take
     * {@link CardValidationMessages#PROMPT_FOR_CARD} and not the character-class text.
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
     * Reads the five values the concurrency comparison and the refresh both use, name folded.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1512-L1517} refreshes exactly these once the comparison at
     * {@code app/cbl/COCRDUPC.cbl:L1503-L1508} fails. Every value is carried as text, matching the
     * character slices the source compares.
     *
     * <p>The embossed name arrives upper-cased, which puts the fold on both sides of that
     * comparison. {@code 9000-READ-DATA.} folds the fetched name at
     * {@code app/cbl/COCRDUPC.cbl:L1356-L1358} and stores it at
     * {@code app/cbl/COCRDUPC.cbl:L1360}, and {@code 9300-CHECK-CHANGE-IN-REC.} folds the re-read
     * name at {@code app/cbl/COCRDUPC.cbl:L1499-L1501} before comparing it at
     * {@code app/cbl/COCRDUPC.cbl:L1504}. A stored name that changes only in letter case is
     * therefore not a concurrent change. {@code app/data/ASCII/carddata.txt} carries mixed-case
     * names such as {@code Aniya Von}, so the fold decides real rows.
     *
     * <p>The source comparison opens on a sixth value, {@code CARD-CVV-CD} at
     * {@code app/cbl/COCRDUPC.cbl:L1503}, and this snapshot carries five. That value is invariant
     * here: {@link CardEntity#applyUpdate} never writes it, {@link CardEntity} publishes no
     * accessor for it, and no other path of this service writes a card row. Five values therefore
     * cover every field a writer can change.
     *
     * @param card the card row to read
     * @return the snapshot
     */
    private static RefreshedCard snapshotOf(CardEntity card) {
        LocalDate expiration = card.getExpirationDate();
        return new RefreshedCard(
                upperCased(padded(card.getEmbossedName(), EMBOSSED_NAME_WIDTH)),
                digits(expiration.getYear(), EXPIRY_YEAR_WIDTH),
                digits(expiration.getMonthValue(), EXPIRY_MONTH_WIDTH),
                digits(expiration.getDayOfMonth(), EXPIRY_DAY_WIDTH),
                padded(card.getActiveStatus(), ACTIVE_STATUS_WIDTH));
    }

    /**
     * Reports whether the submitted values match the stored ones, upper-cased on both sides.
     *
     * <p>Reproduces {@code app/cbl/COCRDUPC.cbl:L680-L683}, which compares
     * {@code FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA)} against
     * {@code FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)} and sets
     * {@code NO-CHANGES-DETECTED}, the text {@code 'No change detected with respect to values
     * fetched.'} at {@code app/cbl/COCRDUPC.cbl:L188}. The group opens at
     * {@code app/cbl/COCRDUPC.cbl:L307} and holds fifty characters of name, four of year, two of
     * month, two of day and one of status. The card number is not among them.
     *
     * <p>Both sides are padded to those widths, so a name of four letters matches the same four
     * letters stored in a fifty-character field.
     *
     * <p>The day of the submitted group is the stored day. {@code app/bms/COCRDUP.bms:L142}
     * declares {@code EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT)} while the four editable fields at L107,
     * L117, L127 and L135 declare {@code UNPROT}, and
     * {@code app/cbl/COCRDUPC.cbl:L1110}, {@code :L1123} and {@code :L1127} send
     * {@code CCUP-OLD-EXPDAY} to that field on every path. {@code CCUP-NEW-EXPDAY} read back at
     * {@code app/cbl/COCRDUPC.cbl:L621} is an echo of the stored day.
     *
     * @param request the submitted update
     * @param fetched the stored values, whose day is the effective submitted day
     * @return {@code true} when the two groups are equal
     */
    private static boolean submittedMatches(CardUpdateRequest request, RefreshedCard fetched) {
        String submitted = groupOf(request.embossedName(), request.expiryYear(),
                request.expiryMonth(), fetched.expiryDay(), request.activeStatus());
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
     * Joins the submitted year and month with the stored day into one date.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1467-L1474} joins the three parts with hyphens into
     * {@code CARD-UPDATE-EXPIRAION-DATE}, which is ten characters of text and holds an impossible
     * day as readily as a real one. Column {@code expiration_date} is a {@code DATE}, so a month
     * the stored day does not reach has no value to store, and
     * {@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE} carries that outcome
     * as ADDITIVE.
     *
     * <p>The day is the stored one, so no submitted day reaches the column.
     *
     * @param request  the submitted update, whose year and month have passed their constraints
     * @param storedDay the two-character day slice of the stored expiry
     * @return the date, or {@code null} when the submitted month does not reach the stored day
     */
    private static LocalDate expirationDateOf(CardUpdateRequest request, String storedDay) {
        try {
            return LocalDate.of(Integer.parseInt(request.expiryYear()),
                    Integer.parseInt(request.expiryMonth()), Integer.parseInt(storedDay));
        } catch (DateTimeException | NumberFormatException notADate) {
            return null;
        }
    }

    /**
     * Pads a numeric display value on the left with zeros to the width of the key column.
     *
     * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is the key
     * {@code app/cbl/COCRDUPC.cbl:L1425} moves into the record identifier.
     *
     * @param value the submitted value, which may be {@code null}
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
     * <p>Carries the condition {@code app/cbl/COCRDUPC.cbl:L1488} tests and
     * {@code app/cbl/COCRDUPC.cbl:L1491} reports, which sets
     * {@code LOCKED-BUT-UPDATE-FAILED}, the text {@code 'Update of record failed'} at
     * {@code app/cbl/COCRDUPC.cbl:L210}. That one assignment is the program's single unguarded
     * text.
     *
     * <p>Leaving {@link #applyUpdate} by throwing is what rolls the transaction back, so the card
     * row and the event row leave the database as they were.
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
