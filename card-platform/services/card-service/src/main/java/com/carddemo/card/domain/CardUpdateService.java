package com.carddemo.card.domain;

import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.outbox.OutboxWriter;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>{@link #updateCard(String, CardUpdateRequest)} opens no transaction. It reads the stored card through
 * {@link CardQueryService}, which opens a read-only transaction of its own. It then calls
 * {@link #applyUpdate} through {@link #self}, so that call crosses the proxy and opens the writing
 * transaction. Two transactions leave a window between the read and the lock, which is the window
 * step six inspects. One transaction shares one persistence context, and the locked read then
 * answers with the instance the first read had already loaded.
 *
 * <p>The card row and the event row commit together or neither commits.
 * {@link OutboxWriter#writeCardUpdated(CardEntity)} carries
 * {@code @Transactional(propagation = MANDATORY)}, so it joins the transaction this class opens and
 * refuses a caller that opened none. That atomicity is ADDITIVE: every file definition in
 * {@code app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 *
 * <h2>What this class does not add</h2>
 *
 * <p>No card-number arithmetic, no account-status test and no card-status test.
 * {@code app/cbl/COCRDUPC.cbl:L784} tests a card number for sixteen digits and nothing more.
 * {@code app/jcl/POSTTRAN.jcl:L23} allocates nine data definitions to the posting program and names
 * no card file, so no posting decision reads the status this class stores.
 *
 * <p>The source performs no expiry-day edit and no calendar-validity edit. The paragraph sequence
 * runs 1230, 1240, 1250, 1260 and then {@code 2000-DECIDE-ACTION.} at
 * {@code app/cbl/COCRDUPC.cbl:L948}, with no 1270 paragraph anywhere in the program. It writes the
 * day it read back: {@code app/cbl/COCRDUPC.cbl:L621} moves the screen field into
 * {@code CCUP-NEW-EXPDAY} and {@code app/cbl/COCRDUPC.cbl:L1471} writes that item into the record.
 *
 * <p>Two additions follow from the transport and the column rather than from a rule. The day carries
 * a width constraint, because a two-character 3270 field could not deliver a third character and a
 * request body can. The assembled triple carries a calendar check, because
 * {@code CARD-EXPIRAION-DATE PIC X(10)} held {@code 2028-02-30} as ten characters of text and column
 * {@code expiration_date} is a {@code DATE} that cannot hold it. Both are marked ADDITIVE by
 * {@link CardValidationMessages}, and {@code card-platform/docs/business-rule-flags.md} carries the
 * second as this route's one behavioural divergence.
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
    /** Causes rendered into one failure line before the chain is cut. */
    private static final int FAILURE_TYPE_DEPTH = 3;

    /**
     * Shape step one requires of the card number, from {@code 1220-EDIT-CARD.} at
     * {@code app/cbl/COCRDUPC.cbl:L762-L800}.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L784} tests the value for sixteen numeric digits and nothing
     * more. The card number is no component of {@link CardUpdateRequest} and no path value either:
     * {@code api/CardController} resolves the card token the path carries to a row and hands that
     * row's number in, so step one reads this shape rather than a constraint on a bean. A resolved
     * number always satisfies it, which is why {@code domain/CardUpdateServiceTest} is where the
     * refusal is exercised.
     */
    static final java.util.regex.Pattern SEARCH_KEY_SHAPE =
            java.util.regex.Pattern.compile("^[0-9]{" + PicClause.CARD_NUM_WIDTH + "}$");

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

    /**
     * Sends the queued statements to the database inside {@link #applyUpdate}.
     *
     * <p>{@link CardRepository} extends {@code ListCrudRepository}, which publishes no {@code flush},
     * so the flush cannot be reached through the repository. Without it the statements of
     * {@code save} and of the outbox write leave for the database at commit, which is after
     * {@link #applyUpdate} has returned, and a database refusing either one raises its exception where
     * that method's own {@code catch} cannot see it.
     */
    private final EntityManager entityManager;

    /**
     * Bound on how long the locked read of one update waits, as a PostgreSQL interval string.
     *
     * <p>Rendered once at construction from {@code carddemo.write.lock-wait-ms}, because it is the
     * same text on every request.
     */
    private final String lockWaitBound;

    /** Reads the stored card in a transaction of its own, before the lock is taken. */
    private final CardQueryService cardQueries;

    /** Stores the event row that commits with the card row. */
    private final OutboxWriter outboxWriter;

    /**
     * Brings the {@code card_xref} replica of this service into step with the card row inside the one
     * transaction that saves that row and writes the event.
     */
    private final CardCrossReferenceReconciler crossReferenceReconciler;

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
     * @param crossReferenceReconciler brings the cross-reference replica into step with the card row
     * @param validator              the validator that applies the request constraints
     * @param entityManager          the unit of work whose flush sends the queued statements
     * @param updatesApplied         counter of updates that committed
     * @param updateConflicts        counter of updates refused after a concurrent change
     * @param infrastructureFailures counter of updates that failed after the lock was held
     * @param timers                 the latency timers of this service
     * @param properties             the bound {@code carddemo} block, read for its lock-wait bound
     * @param self                   provider of this bean through its own proxy
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardUpdateService(CardRepository cards, CardQueryService cardQueries,
            OutboxWriter outboxWriter, CardCrossReferenceReconciler crossReferenceReconciler,
            Validator validator, EntityManager entityManager,
            @Qualifier("cardUpdatesAppliedCounter") Counter updatesApplied,
            @Qualifier("cardUpdateConflictCounter") Counter updateConflicts,
            @Qualifier("cardInfrastructureFailureCounter") Counter infrastructureFailures,
            CardLatencyTimers timers, CardProperties properties,
            ObjectProvider<CardUpdateService> self) {
        this.cards = Objects.requireNonNull(cards, "cards is required");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager is required");
        this.cardQueries = Objects.requireNonNull(cardQueries, "cardQueries is required");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter is required");
        this.crossReferenceReconciler = Objects.requireNonNull(crossReferenceReconciler,
                "crossReferenceReconciler is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.updatesApplied = Objects.requireNonNull(updatesApplied, "updatesApplied is required");
        this.updateConflicts =
                Objects.requireNonNull(updateConflicts, "updateConflicts is required");
        this.infrastructureFailures = Objects.requireNonNull(infrastructureFailures,
                "infrastructureFailures is required");
        this.updateLatency = Objects.requireNonNull(timers, "timers is required").cardUpdate();
        this.lockWaitBound = Objects.requireNonNull(properties, "properties is required")
                .write().lockWaitMs() + "ms";
        this.self = Objects.requireNonNull(self, "self is required");
    }

    /**
     * Applies one card update and answers with the outcome the source displays.
     *
     * <p>Every one of the seven outcomes is timed, so a refused update is measured alongside an
     * applied one.
     *
     * @param cardNumber the card the update names, which arrives in the request path
     * @param request    the submitted update, as it arrived
     * @return the outcome, never {@code null}
     * @throws NullPointerException if {@code cardNumber} or {@code request} is {@code null}
     */
    public CardUpdateResponse updateCard(String cardNumber, CardUpdateRequest request) {
        return updateCard(cardNumber, request, null);
    }

    /**
     * Applies one card update against a row the caller has already read.
     *
     * <p>Same outcomes and same order as {@link #updateCard(String, CardUpdateRequest)}, with one
     * statement fewer. A caller that resolved the card to reach its number is holding the row this
     * method would otherwise read a second time: {@code api/CardController} resolves a token to a
     * card before it can name the card at all, so the read it performs and the read this method
     * performed answered the same key with the same row.
     *
     * <p>{@code resolved} replaces the unlocked read and nothing else. Every edit still runs, in
     * source order, and the locked read of {@link #applyUpdate} still happens: that read is the
     * compare-and-swap of {@code app/cbl/COCRDUPC.cbl:L1429}, and a row read before the transaction
     * opened cannot stand in for a row held under a lock.
     *
     * <p>A {@code resolved} row naming another card is not used. The number this method was given is
     * the one that decides, so a mismatch falls back to the read rather than acting on a row the
     * caller did not name.
     *
     * @param cardNumber the card the update names, which arrives in the request path
     * @param request    the submitted update, as it arrived
     * @param resolved   the row the caller already read for {@code cardNumber}, or {@code null} to
     *                   have this method read it
     * @return the outcome, never {@code null}
     * @throws NullPointerException if {@code cardNumber} or {@code request} is {@code null}
     */
    public CardUpdateResponse updateCard(String cardNumber, CardUpdateRequest request,
            CardEntity resolved) {
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        Objects.requireNonNull(request, "request is required");

        long startedAt = System.nanoTime();
        try {
            return decide(cardNumber, request, resolved);
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
     * @param cardNumber the card the update names
     * @param request    the submitted update
     * @param resolved   the row the caller already read for {@code cardNumber}, or {@code null} to
     *                   have this method read it. A row naming another card is not used
     * @return the outcome
     */
    private CardUpdateResponse decide(String cardNumber, CardUpdateRequest request,
            CardEntity resolved) {
        if (isAbsent(cardNumber, CARD_NUMBER_WIDTH)) {
            log.info("A card update supplied no card number");
            return CardUpdateResponse.validationRejected(CardValidationMessages.PROMPT_FOR_CARD);
        }

        String masked = PanMasker.maskCardNumber(cardNumber);
        if (!SEARCH_KEY_SHAPE.matcher(cardNumber.strip()).matches()) {
            log.info("A card update supplied a card number the search-key edit refused");
            return CardUpdateResponse.validationRejected(
                    CardValidationMessages.CARD_FILTER_NOT_NUMERIC);
        }

        // The row the caller already read, when it read this card. Compared on the padded key the
        // read below would have used, so a supplied row for another card is ignored rather than
        // trusted: the number this method was given is the one that decides.
        String key = padKey(cardNumber);
        Optional<CardEntity> stored =
                resolved != null && key.equals(resolved.getCardNumber())
                        ? Optional.of(resolved)
                        : cardQueries.findByCardNumber(key);
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

        LocalDate expiration = expirationDateOf(request);
        if (expiration == null) {
            log.info("A card update named a year, month and day that are no calendar day, {}",
                    masked);
            return CardUpdateResponse.validationRejected(
                    CardValidationMessages.ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE);
        }

        try {
            return self.getObject().applyUpdate(cardNumber, request, fetched, expiration);
        } catch (LockNotTaken notTaken) {
            updateConflicts.increment();
            log.warn("A card update could not take the row for update, {}. The failure was {}."
                    + " Its message is not recorded, because a message quotes the row or the"
                    + " statement.", masked, failureType(notTaken));
            return CardUpdateResponse.lockNotAcquired();
        } catch (UpdateFailedAfterLock failed) {
            infrastructureFailures.increment();
            log.warn("A card update failed after its row was locked, {}. The failure was {}."
                    + " Its message is not recorded, because a message quotes the row or the"
                    + " statement.", masked, failureType(failed));
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
     * this method opens the writing transaction when {@link #updateCard(String, CardUpdateRequest)}
     * calls it through {@link #self}. The card row and the event row commit inside it.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1461-L1475} rewrites all six fields of the record. Here the
     * persistence layer issues the full-row statement and {@link CardEntity#applyUpdate} moves the
     * three values the map admits. The card number, the account identifier and the card verification
     * value are carried through, as {@code app/cbl/COCRDUPC.cbl:L1462-L1465} carries them.
     *
     * <p>One snapshot serves both purposes. {@link #snapshotOf} folds the embossed name, the
     * comparison reads it, and a refusal answers with the same values. That is what the source does:
     * {@code 9300-CHECK-CHANGE-IN-REC.} folds the record field in place at
     * {@code app/cbl/COCRDUPC.cbl:L1499-L1501}, so the {@code MOVE} at
     * {@code app/cbl/COCRDUPC.cbl:L1513} refreshes the operator's field from the folded value.
     *
     * <p>{@link EntityManager#flush()} closes the guarded block, so the statements of the row write
     * and the event write reach the database while this method can still see a refusal of either one.
     *
     * @param cardNumber the card the update names, which arrives in the request path
     * @param request    the submitted update, whose components have passed every edit
     * @param fetched    the five values the caller last saw, read before this transaction opened
     * @param expiration the expiry date the three submitted parts name
     * @return the outcome: applied, refused for a concurrent change, or refused for a lock this
     *         method could not take
     * @throws NullPointerException  if any argument is {@code null}
     * @throws LockNotTaken          when the locking statement ends in a lock the database will
     *                               not grant, or in a wait that ran out. Either leaves this
     *                               transaction unusable and rolls it back. A fault that is
     *                               neither is left unhandled, so a broken dependency is not
     *                               reported as a conflict
     * @throws UpdateFailedAfterLock when the rewrite or the event row fails once the row is held,
     *                               which rolls this transaction back
     */
    @Transactional
    public CardUpdateResponse applyUpdate(String cardNumber, CardUpdateRequest request,
            RefreshedCard fetched, LocalDate expiration) {
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(fetched, "fetched is required");
        Objects.requireNonNull(expiration, "expiration is required");

        String masked = PanMasker.maskCardNumber(cardNumber);
        // Bound the wait before the locked read runs. PostgreSQL waits forever by default, so a row
        // another writer held kept this request open for as long as that writer held it, and the
        // outcome below was unreachable through contention: the wait ended in a lock or it did not
        // end. Transaction-local, so it governs this one read and nothing else.
        cards.applyLockWaitBound(lockWaitBound);

        Optional<CardEntity> locked;
        try {
            locked = cards.findForUpdateByCardNumber(padKey(cardNumber));
        } catch (PessimisticLockingFailureException | QueryTimeoutException notTaken) {
            // A row the database will not hand over inside the wait it was given is the same
            // outcome to a caller as a row that is no longer there: the lock was not taken. Both
            // families caught here say that and nothing else. A lock_timeout expiry arrives as
            // CannotAcquireLockException and a broken deadlock as DeadlockLoserDataAccessException,
            // both PessimisticLockingFailureException; a statement_timeout expiry arrives as
            // QueryTimeoutException. repository/CardRepositoryIT contends two real transactions and
            // asserts the first of those types, so the mapping is measured rather than assumed.
            //
            // Nothing wider belongs here. A fault that is not a lock or a timeout is a dependency
            // failure, and JpaSystemException is where the persistence layer puts every Hibernate
            // error it has no specific translation for: a revoked privilege, a driver fault, a
            // mapping error. Reporting one of those as a lock tells the caller to retry a request
            // that will never succeed, and counts a broken database as a user conflict. Such a
            // fault leaves this method unhandled and api/CardApiExceptionHandler answers 500.
            throw new LockNotTaken(notTaken);
        }
        if (locked.isEmpty()) {
            log.info("A card update could not lock the row it had just read, {}", masked);
            return CardUpdateResponse.lockNotAcquired();
        }

        CardEntity card = locked.get();
        RefreshedCard current = snapshotOf(card);
        if (!current.equals(fetched)) {
            updateConflicts.increment();
            log.info("A card update lost a race against another writer, {}", masked);
            // The answer carries the same folded snapshot the comparison read, which is what the
            // source answers with. 9300-CHECK-CHANGE-IN-REC. runs
            // INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER over the record field
            // IN PLACE at app/cbl/COCRDUPC.cbl:L1499-L1501, so the
            // MOVE CARD-EMBOSSED-NAME TO CCUP-OLD-CRDNAME at :L1513 carries the already-folded
            // value into the field the operator reviews.
            return CardUpdateResponse.changedBeforeUpdate(current);
        }

        try {
            card.applyUpdate(padded(request.embossedName(), EMBOSSED_NAME_WIDTH), expiration,
                    request.activeStatus());
            cards.save(card);
            outboxWriter.writeCardUpdated(card);
            // The replica of the card-to-account mapping this service owns is brought into step
            // here, in the transaction that saves the card row and queues the event, so the three
            // either all move or none of them do. app/cbl/COCRDUPC.cbl:L356 reads
            // *COPY CVACT03Y. commented out, so the source update program never opened the
            // cross-reference and nothing kept the two copies in step.
            crossReferenceReconciler.reconcile(card);
            // Both statements reach the database here rather than at commit. save() and the outbox
            // write only queue their statements, so a database refusing either one raised its
            // exception after this block had been left and after the catch below could see it: the
            // documented outcome was unreachable and a caller read the fault body of
            // api/CardApiExceptionHandler instead. The transaction still rolls back either way, so
            // this changes what a caller is told and not what the database holds.
            entityManager.flush();
        } catch (RuntimeException failure) {
            throw new UpdateFailedAfterLock(failure);
        }

        recordAppliedAfterCommit(masked);
        return CardUpdateResponse.updated();
    }

    /**
     * Counts one applied update and says so, once the transaction this method runs in has
     * committed.
     *
     * <p>The count and the sentence both claim a commit, and this method is called before one. The
     * transactional proxy commits after {@link #applyUpdate} returns, so a counter incremented here
     * would move for an update that a deferred constraint, a lost connection or a rollback-only
     * marker then discarded, and the log line would report a commit that never happened.
     * {@link EntityManager#flush()} above does not close that gap: it sends the statements and
     * leaves the commit where it was.
     *
     * <p>{@link TransactionSynchronization#afterCommit()} runs only on the successful path, so the
     * count and the sentence follow the commit rather than predicting it. A failed transaction
     * reaches {@link TransactionSynchronization#afterCompletion(int)} instead, which is not
     * registered here, so nothing is counted.
     *
     * <p>The guard covers the caller that reaches this class directly rather than through the
     * proxy, which is how the unit tests exercise {@link #applyUpdate}. Registering a
     * synchronization without an active one raises, so with no transaction in progress there is
     * no commit to wait for and the count is taken in place.
     *
     * @param masked the card number as it may be logged, from {@link PanMasker}
     */
    private void recordAppliedAfterCommit(String masked) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            updatesApplied.increment();
            log.info("A card update applied and produced one event, {}", masked);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                updatesApplied.increment();
                log.info("A card update committed and produced one event, {}", masked);
            }
        });
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
     * Reads the five values the concurrency comparison uses, and that a refusal answers with, name
     * folded.
     *
     * <p>One snapshot serves both. The source has no second one: the {@code INSPECT ... CONVERTING}
     * at {@code app/cbl/COCRDUPC.cbl:L1499-L1501} names {@code CARD-EMBOSSED-NAME} itself rather than
     * a copy of it, so the record field is upper case by the time the comparison at
     * {@code app/cbl/COCRDUPC.cbl:L1504} reads it and by the time the {@code MOVE} at
     * {@code app/cbl/COCRDUPC.cbl:L1513} carries it into {@code CCUP-OLD-CRDNAME}. The value the
     * operator reviews after a lost race is therefore the folded one.
     *
     * <p>Answering with the unfolded value because {@code GET /cards/&#123;cardToken&#125;} returns
     * the mixed-case name for the same row would trade source fidelity for internal consistency, and
     * would contradict the decision recorded under "The embossed name is folded on both sides of the
     * concurrency comparison" in {@code card-platform/docs/decision-log.md}. The account service
     * legitimately does the opposite, because {@code app/cbl/COACTUPC.cbl:L4109-L4193} folds a copy
     * rather than the record area.
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
     * <p>The day of the submitted group is the submitted day, which is the day
     * {@code CCUP-NEW-EXPDAY} carries. {@code app/cbl/COCRDUPC.cbl:L621} moves the screen field into
     * that item and {@code app/cbl/COCRDUPC.cbl:L1471} writes that same item into the record, so the
     * day the program stores is the day it read back.
     *
     * <p>On a 3270 the day read back was always the stored day, and that is a property of the map
     * rather than of the program: {@code app/bms/COCRDUP.bms:L142} declares
     * {@code EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT)} where the four editable fields at L107, L117, L127
     * and L135 declare {@code UNPROT}, so an operator could not type into it, and
     * {@code app/cbl/COCRDUPC.cbl:L1110}, {@code :L1123} and {@code :L1127} sent
     * {@code CCUP-OLD-EXPDAY} to it on every path. A request body has no protected field. Reading the
     * stored day here instead of the submitted one would put the submitted day in no comparison and
     * in no column, so a caller changing the day alone would read the no-change text for ever and a
     * caller changing the day beside the month would read success while its day was dropped.
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
     * Joins the three submitted parts into one date.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1467-L1474} joins {@code CCUP-NEW-EXPYEAR},
     * {@code CCUP-NEW-EXPMON} and {@code CCUP-NEW-EXPDAY} with hyphens into
     * {@code CARD-UPDATE-EXPIRAION-DATE} and rewrites the record with it. All three are the submitted
     * values: {@code app/cbl/COCRDUPC.cbl:L621} moves the day the screen sent into the third of them.
     * This method assembles the same three.
     *
     * <p>{@code CARD-UPDATE-EXPIRAION-DATE} is ten characters of text and holds an impossible day as
     * readily as a real one, so the source stored {@code 2028-02-30} and reported nothing. Column
     * {@code expiration_date} is a {@code DATE} and cannot hold one, so a triple naming no day of the
     * calendar has no value to store and
     * {@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE} carries that outcome as
     * ADDITIVE. That divergence is the column type's, not this platform's, and
     * {@code card-platform/docs/business-rule-flags.md} carries it.
     *
     * <p>The rule now reads the triple a caller submitted rather than the submitted year and month
     * beside the stored day. Reading the stored day made the refusal fire on a combination no caller
     * had sent and stay silent on one it had: a caller submitting the thirtieth of February read
     * success, and the column took the stored day instead.
     *
     * @param request the submitted update, whose three parts have passed their constraints
     * @return the date, or {@code null} when the three submitted parts name no day of the calendar
     */
    private static LocalDate expirationDateOf(CardUpdateRequest request) {
        try {
            return LocalDate.of(Integer.parseInt(request.expiryYear()),
                    Integer.parseInt(request.expiryMonth()), Integer.parseInt(request.expiryDay()));
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
     * Reports that the row could not be taken for update at all.
     *
     * <p>Carries the same outcome as the branch {@code app/cbl/COCRDUPC.cbl:L1441} reports, which
     * sets {@code COULD-NOT-LOCK-FOR-UPDATE}, the text {@code 'Could not lock record for update'}
     * at {@code app/cbl/COCRDUPC.cbl:L209}. The source reached it on a {@code READ UPDATE} that
     * came back with anything other than a normal response.
     *
     * <p>{@link #applyUpdate} reaches that branch two ways. A row no longer present is the empty
     * result, answered in place. A database that refuses the locking statement raises instead, and
     * the statement leaves a PostgreSQL transaction unusable, so this exception carries the outcome
     * out through the transaction boundary rather than returning across it.
     */
    public static final class LockNotTaken extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Wraps the failure the locking read reported.
         *
         * @param cause the failure, whose message reaches no response body
         */
        LockNotTaken(Throwable cause) {
            super("the card row could not be taken for update", cause);
        }
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

    /**
     * Renders one failure as its type and the types of its causes, and never as its message.
     *
     * <p>A type is code and safe to record. An exception message is not: a constraint violation
     * quotes the value that violated it, a query timeout quotes the statement, and a connection
     * failure quotes the data-source URL. Passing the throwable to the logger emits both, so this
     * method emits the half that is code and drops the half that is data.
     *
     * <p>The chain is bounded because a wrapped failure can nest deeply and one log line is not the
     * place to render all of it. Three levels reach the framework wrapper, the driver exception and
     * the cause underneath it, which is what a reader needs to tell a timeout from a constraint from
     * a broken connection.
     *
     * @param failure the failure that reached this handler
     * @return the type chain as text, never null and never a message
     */
    private static String failureType(Throwable failure) {
        StringBuilder types = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < FAILURE_TYPE_DEPTH; depth++) {
            if (depth > 0) {
                types.append(" caused by ");
            }
            types.append(current.getClass().getName());
            current = current.getCause() == current ? null : current.getCause();
        }
        return types.toString();
    }
}
