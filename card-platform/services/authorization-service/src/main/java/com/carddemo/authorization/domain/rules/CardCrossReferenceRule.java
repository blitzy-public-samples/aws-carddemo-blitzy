package com.carddemo.authorization.domain.rules;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.events.DeclineReason;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves a card number through the cross-reference table, and assigns reject code {@code 0100}
 * when no row carries it.
 *
 * <p>Transformed from paragraph {@code 1500-A-LOOKUP-XREF} at
 * {@code app/cbl/CBTRN02C.cbl:L380-L392}. {@code app/cbl/CBTRN02C.cbl:L382} moves the card number
 * into the key field, {@code app/cbl/CBTRN02C.cbl:L383} reads the cross-reference dataset, and the
 * {@code INVALID KEY} branch at {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns the code and its
 * text.
 *
 * <p>The lookup keys on all sixteen characters of the Primary Account Number (PAN), the width
 * {@code XREF-CARD-NUM PIC X(16)} holds at {@code app/cpy/CVACT03Y.cpy:L5}. The comparison is
 * text, so a leading zero counts.
 *
 * <p>A resolved row reaches {@link DeclineRule.Context}, which mirrors {@code CARD-XREF-RECORD},
 * the working-storage area {@code app/cbl/CBTRN02C.cbl:L383} reads into. The row carries the
 * account identifier {@code app/cbl/CBTRN02C.cbl:L394} moves into the account key field.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
@Order(10)
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
     * Reads the cross-reference row for the card number this call carries.
     *
     * <p>A resolved row reaches {@code context}, for the rules that follow.
     *
     * @param context values for one authorization call
     * @return {@link DeclineReason#INVALID_CARD_NUMBER} when no row carries the card number, and an
     *         empty result when one does
     */
    @Override
    public Optional<DeclineReason> evaluate(Context context) {
        Optional<CardCrossReferenceEntity> resolved =
                cardCrossReferences.findByCardNumber(context.getCardNumber());
        resolved.ifPresent(context::setCardCrossReference);
        if (resolved.isEmpty()) {
            return Optional.of(DeclineReason.INVALID_CARD_NUMBER);
        }
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
