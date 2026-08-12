package com.carddemo.card.domain;

import com.carddemo.card.entity.CardCrossReferenceEntity;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardCrossReferenceRepository;
import com.carddemo.cobol.PanMasker;
import io.micrometer.core.instrument.Counter;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps this service's {@code card_xref} replica in step with the {@code card} rows that own the
 * card-to-account mapping.
 *
 * <p>This service owns {@code card}, and {@code CARD-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT02Y.cpy:L6} is the authoritative mapping from a card to an account.
 * {@code card_xref} replicates that mapping from {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}, loaded from {@code app/data/ASCII/cardxref.txt}. No source
 * program keeps the two in step: {@code app/cbl/COCRDUPC.cbl:L356} reads {@code *COPY CVACT03Y.}
 * commented out, so the update program never opened the cross-reference, and
 * {@code app/cbl/COCRDLIC.cbl} names that copybook nowhere.
 *
 * <p>{@code domain/CardUpdateService.applyUpdate} calls {@link #reconcile(CardEntity)} inside the
 * one transaction that saves the card row and writes the outbox row, so the replica, the card row
 * and the stored event either all move or none of them do.
 *
 * <p>One comparison reads the replica row for the card and measures one column against the card
 * row. An agreeing row keeps its mapping and moves its observation time. A disagreeing row has its
 * account identifier corrected. A card number holding no replica row is reported and no row is
 * created: {@code customer_id} is mandatory, taken from {@code XREF-CUST-ID PIC 9(09)} at
 * {@code app/cpy/CVACT03Y.cpy:L6}, and {@code app/cpy/CVACT02Y.cpy} carries no customer identifier.
 * Each of the three outcomes carries its own counter, named on
 * {@code config/ObservabilityConfig}.
 *
 * <p>Both tables are keyed on the full card number, this class publishes no event and returns no
 * value to a caller, and the one log line each divergent outcome writes carries the masked number
 * from {@link PanMasker#maskCardNumber(String)}. The three counters carry no card number, no
 * account identifier and no tag.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}, the source-to-target mapping in
 * {@code card-platform/docs/traceability-matrix.md}, and flagged source findings in
 * {@code card-platform/docs/business-rule-flags.md}.
 */
@Component
public class CardCrossReferenceReconciler {

    /** Names this class, so a corrected mapping is attributable without naming a card. */
    private static final Logger LOG =
            LoggerFactory.getLogger(CardCrossReferenceReconciler.class);

    /** The replica this class reads and corrects. */
    private final CardCrossReferenceRepository crossReferences;

    /** Counts a row that already agreed. */
    private final Counter agreed;

    /** Counts a row whose mapping had diverged and was corrected. */
    private final Counter corrected;

    /** Counts a card number holding no replica row. */
    private final Counter missing;

    /**
     * Supplies the observation time. A field rather than a constructor argument, matching
     * {@code outbox/OutboxWriter}: no {@link Clock} bean is declared in this module, and the value
     * this clock produces is a timestamp column rather than anything a caller reads.
     */
    private final Clock clock = Clock.systemUTC();

    /**
     * Builds the reconciler.
     *
     * @param crossReferences the replica store
     * @param agreed          counter of rows already in step
     * @param corrected       counter of rows corrected
     * @param missing         counter of card numbers holding no row
     * @throws NullPointerException if any argument is null
     */
    public CardCrossReferenceReconciler(CardCrossReferenceRepository crossReferences,
            @Qualifier("cardCrossReferenceAgreedCounter") Counter agreed,
            @Qualifier("cardCrossReferenceCorrectedCounter") Counter corrected,
            @Qualifier("cardCrossReferenceMissingCounter") Counter missing) {
        this.crossReferences = Objects.requireNonNull(crossReferences, "crossReferences");
        this.agreed = Objects.requireNonNull(agreed, "agreed");
        this.corrected = Objects.requireNonNull(corrected, "corrected");
        this.missing = Objects.requireNonNull(missing, "missing");
    }

    /**
     * Compares the replica row for one card against the card row that owns the mapping, and corrects
     * the replica when the two disagree.
     *
     * <p>Propagation is {@code MANDATORY}, as it is on {@code outbox/OutboxWriter}: the correction
     * joins the caller's transaction beside the card row and the outbox row, and a call arriving
     * with no transaction in progress is refused rather than run on its own.
     *
     * @param card the card row this service owns, already read for update
     * @return the outcome, which the caller may read and is not required to
     * @throws NullPointerException if {@code card} is null
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *         in progress
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome reconcile(CardEntity card) {
        Objects.requireNonNull(card, "card");

        String cardNumber = card.getCardNumber();
        Optional<CardCrossReferenceEntity> replica =
                crossReferences.findByCardNumber(cardNumber);
        if (replica.isEmpty()) {
            missing.increment();
            LOG.warn("Card {} holds no cross-reference replica row, so its mapping could not be"
                    + " confirmed. A row cannot be created here: customer_id is mandatory and"
                    + " app/cpy/CVACT02Y.cpy carries no customer identifier.",
                    PanMasker.maskCardNumber(cardNumber));
            return Outcome.NO_REPLICA_ROW;
        }

        Instant observedAt = clock.instant();
        CardCrossReferenceEntity row = replica.get();
        if (!row.reconcileAccountId(card.getAccountId(), observedAt)) {
            crossReferences.save(row);
            agreed.increment();
            return Outcome.ALREADY_IN_STEP;
        }

        crossReferences.save(row);
        corrected.increment();
        LOG.warn("The cross-reference replica row of card {} named a different account from the"
                + " card row that owns the mapping, and was brought into step. Neither identifier"
                + " is recorded here.",
                PanMasker.maskCardNumber(cardNumber));
        return Outcome.CORRECTED;
    }

    /** What one comparison found. */
    public enum Outcome {

        /** The replica already named the account the card row names. */
        ALREADY_IN_STEP,

        /** The replica named a different account, and now names the card row's. */
        CORRECTED,

        /** The card number holds no replica row, and none was created. */
        NO_REPLICA_ROW
    }
}
