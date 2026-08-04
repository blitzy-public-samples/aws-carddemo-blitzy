package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.events.DeclineReason;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves a card number to an account, and assigns reject code {@code 0100} when none resolves.
 *
 * <p>Transformed from paragraph {@code 1500-A-LOOKUP-XREF} at
 * {@code app/cbl/CBTRN02C.cbl:L380-L392}. {@code app/cbl/CBTRN02C.cbl:L382} moves the card number
 * into the key field, {@code app/cbl/CBTRN02C.cbl:L383} reads the cross-reference dataset, and the
 * {@code INVALID KEY} branch at {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns the code and its
 * text.
 *
 * <p>A lookup miss is a decline and not an exception. Both source paths agree:
 * {@code app/cbl/CBTRN02C.cbl:L384} takes its {@code INVALID KEY} branch and
 * {@code app/cbl/COTRN02C.cbl:L624} takes its {@code DFHRESP(NOTFND)} branch, and neither ends the
 * run.
 *
 * <p>The lookup keys on the full sixteen-character Primary Account Number (PAN), exactly as
 * {@code app/cbl/CBTRN02C.cbl:L382} does. Masking happens where an event is written, after the
 * decision.
 *
 * <p>A resolved row reaches {@link DeclineRule.Context}, so the rules that follow read the account
 * identifier this read supplied rather than reading the dataset again. That mirrors
 * {@code CARD-XREF-RECORD}, the working-storage area the source reads into.
 *
 * <p>This rule runs first. The gate at {@code app/cbl/CBTRN02C.cbl:L372} runs the account lookup
 * only while the reject reason still holds zero, so a card that fails here never reaches the account
 * read.
 */
@Component
@Order(100)
public class CardCrossReferenceRule implements DeclineRule {

    /** Reads {@code card_xref} on the full card number. */
    private final CardCrossReferenceRepository cardCrossReferences;

    /**
     * Takes the repository this rule reads.
     *
     * @param cardCrossReferences reader of the cross-reference table
     */
    public CardCrossReferenceRule(CardCrossReferenceRepository cardCrossReferences) {
        this.cardCrossReferences = cardCrossReferences;
    }

    /**
     * Reads the cross-reference row for the card number, and declines when none carries it.
     *
     * @param context values for one authorization call
     * @return {@link DeclineReason#INVALID_CARD_NUMBER} when no row carries the card number, and an
     *         empty result when one does
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        Optional<CardCrossReferenceEntity> resolved =
                cardCrossReferences.findByCardNumber(context.getCardNumber());
        if (resolved.isEmpty()) {
            return Optional.of(DeclineReason.INVALID_CARD_NUMBER);
        }
        context.setCardCrossReference(resolved.get());
        return Optional.empty();
    }

    /**
     * Declares that the chain stops here when this rule declines.
     *
     * @return {@link DeclineRule.Segment#STOP_ON_FIRST_DECLINE}
     */
    @Override
    public Segment segment() {
        return Segment.STOP_ON_FIRST_DECLINE;
    }
}
