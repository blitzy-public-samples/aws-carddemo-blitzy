package com.carddemo.authorization.domain;

import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.events.DeclineReason;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * One decline rule of the authorization decision chain.
 *
 * <p>The chain comes from paragraph {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}. The comment {@code * ADD MORE VALIDATIONS HERE} at
 * {@code app/cbl/CBTRN02C.cbl:L377} marks its extension point, and this interface is that point.
 * One more decline rule is one more class in {@code domain/rules} implementing this interface, and
 * {@code AuthorizationService} collects every implementation it finds.
 *
 * <p>An implementation answers with the one reject reason it assigns, or with nothing. It also
 * declares its {@link Segment}, which fixes what the chain does with that answer.
 *
 * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the batch job with
 * return code 4 when any record was rejected, so {@link #evaluate(Context)} returns a value and
 * throws nothing when a rule declines.
 *
 * <p>Design decisions behind this seam: {@code card-platform/docs/decision-log.md}.
 */
public interface DeclineRule {

    /**
     * How the chain treats a rule's answer.
     *
     * <p>Paragraph {@code 1500-VALIDATE-TRAN} carries one gate and paragraph
     * {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:L393-L422} carries none, so the
     * four source reject reasons fall into two groups.
     */
    public enum Segment {

        /**
         * The chain stops at the first rule that declines and reports that rule's answer.
         *
         * <p>{@link DeclineReason#INVALID_CARD_NUMBER} and {@link DeclineReason#ACCOUNT_NOT_FOUND}
         * belong here. The gate at {@code app/cbl/CBTRN02C.cbl:L372} runs the account lookup only
         * while the reject reason still holds zero. A card that fails cross-reference lookup
         * therefore never reaches the account lookup.
         */
        STOP_ON_FIRST_DECLINE,

        /**
         * Every rule of the segment runs, and the last one that declines supplies the answer.
         *
         * <p>{@link DeclineReason#OVER_CREDIT_LIMIT} and {@link DeclineReason#ACCOUNT_EXPIRED}
         * belong here. {@code app/cbl/CBTRN02C.cbl:L407-L420} holds two sequential {@code IF}
         * blocks with no gate between them. A transaction failing both tests therefore carries
         * {@link DeclineReason#ACCOUNT_EXPIRED}, and the earlier assignment at
         * {@code app/cbl/CBTRN02C.cbl:L410} does not survive.
         */
        LAST_DECLINE_WINS
    }

    /**
     * Values one authorization call carries through the chain.
     *
     * <p>Three values arrive from {@code AuthorizationService} and hold steady. Two more start
     * absent, and a rule fills each one in through
     * {@link #setCardCrossReference(CardCrossReferenceEntity)} or
     * {@link #setAccountCreditSnapshot(AccountCreditSnapshotEntity)}.
     *
     * <p>Those two filled values follow the source's shared record areas.
     * {@code READ XREF-FILE INTO CARD-XREF-RECORD} at {@code app/cbl/CBTRN02C.cbl:L383} and
     * {@code READ ACCOUNT-FILE INTO ACCOUNT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L395} each read
     * into working storage that later statements then read.
     *
     * <p>One instance serves one authorization call on one thread, and no member is thread safe.
     */
    public class Context {

        /**
         * Full card number, zero-padded to the sixteen characters
         * {@code DALYTRAN-CARD-NUM PIC X(16)} holds at {@code app/cpy/CVTRA06Y.cpy:L15}.
         *
         * <p>The value is unmasked, and the cross-reference lookup keys on all sixteen characters
         * of the Primary Account Number (PAN). Masking happens at the serialization boundary,
         * after the decision.
         */
        private final String cardNumber;

        /**
         * Transaction amount at two digits after the decimal point, from
         * {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:L10}.
         */
        private final BigDecimal amount;

        /**
         * Moment the transaction was captured, the twenty-six characters
         * {@code DALYTRAN-ORIG-TS PIC X(26)} holds at {@code app/cpy/CVTRA06Y.cpy:L16}.
         *
         * <p>The value stays text. {@code app/cbl/CBTRN02C.cbl:L414} compares its leading ten
         * characters against {@code ACCT-EXPIRAION-DATE PIC X(10)} at
         * {@code app/cpy/CVACT01Y.cpy:L11}, character by character.
         */
        private final String originTimestamp;

        /**
         * Cross-reference row {@link #getCardNumber()} resolved to, or {@code null} until a rule
         * sets it.
         *
         * <p>Source analogue {@code CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy:L4}, filled
         * by the read at {@code app/cbl/CBTRN02C.cbl:L383}. The rule assigning
         * {@link DeclineReason#INVALID_CARD_NUMBER} sets this row, and the rule assigning
         * {@link DeclineReason#ACCOUNT_NOT_FOUND} reads
         * {@link CardCrossReferenceEntity#getAccountId()} from it.
         */
        private CardCrossReferenceEntity cardCrossReference;

        /**
         * Credit and expiry values for the account this card belongs to, or {@code null} until a
         * rule sets them.
         *
         * <p>Source analogue {@code ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy:L4}, filled by
         * the read at {@code app/cbl/CBTRN02C.cbl:L395}. The rule assigning
         * {@link DeclineReason#ACCOUNT_NOT_FOUND} sets this row, and the rules assigning
         * {@link DeclineReason#OVER_CREDIT_LIMIT} and {@link DeclineReason#ACCOUNT_EXPIRED} read
         * it.
         */
        private AccountCreditSnapshotEntity accountCreditSnapshot;

        /**
         * Builds the context for one authorization call from the three transaction values.
         *
         * @param cardNumber      full card number, zero-padded to sixteen characters
         * @param amount          transaction amount at two digits after the decimal point
         * @param originTimestamp twenty-six character capture timestamp, held as text
         * @throws NullPointerException if any argument is {@code null}
         */
        public Context(String cardNumber, BigDecimal amount, String originTimestamp) {
            if (cardNumber == null) {
                throw new NullPointerException("cardNumber must not be null");
            }
            if (amount == null) {
                throw new NullPointerException("amount must not be null");
            }
            if (originTimestamp == null) {
                throw new NullPointerException("originTimestamp must not be null");
            }
            this.cardNumber = cardNumber;
            this.amount = amount;
            this.originTimestamp = originTimestamp;
        }

        /**
         * Returns the full card number the cross-reference lookup keys on.
         *
         * @return sixteen characters, never {@code null}
         */
        public String getCardNumber() {
            return cardNumber;
        }

        /**
         * Returns the transaction amount. A refund carries a negative amount.
         *
         * @return the amount at two digits after the decimal point, never {@code null}
         */
        public BigDecimal getAmount() {
            return amount;
        }

        /**
         * Returns the capture timestamp as text.
         *
         * @return twenty-six characters, never {@code null}
         */
        public String getOriginTimestamp() {
            return originTimestamp;
        }

        /**
         * Returns the cross-reference row a rule resolved for this card number.
         *
         * @return the resolved row, or {@code null} until a rule sets it
         */
        public CardCrossReferenceEntity getCardCrossReference() {
            return cardCrossReference;
        }

        /**
         * Sets the cross-reference row a keyed lookup returned, for the rules that follow.
         *
         * @param cardCrossReference the resolved row
         * @throws NullPointerException if {@code cardCrossReference} is {@code null}
         */
        public void setCardCrossReference(CardCrossReferenceEntity cardCrossReference) {
            if (cardCrossReference == null) {
                throw new NullPointerException("cardCrossReference must not be null");
            }
            this.cardCrossReference = cardCrossReference;
        }

        /**
         * Returns the credit and expiry values a rule resolved for this account.
         *
         * @return the resolved values, or {@code null} until a rule sets them
         */
        public AccountCreditSnapshotEntity getAccountCreditSnapshot() {
            return accountCreditSnapshot;
        }

        /**
         * Sets the credit and expiry values a keyed lookup returned, for the rules that follow.
         *
         * @param accountCreditSnapshot the resolved values
         * @throws NullPointerException if {@code accountCreditSnapshot} is {@code null}
         */
        public void setAccountCreditSnapshot(AccountCreditSnapshotEntity accountCreditSnapshot) {
            if (accountCreditSnapshot == null) {
                throw new NullPointerException("accountCreditSnapshot must not be null");
            }
            this.accountCreditSnapshot = accountCreditSnapshot;
        }
    }

    /**
     * Answers with the reject reason this rule assigns for one authorization call.
     *
     * <p>An empty result means this rule does not decline. A present result carries the one reject
     * reason this rule assigns, and {@link #segment()} fixes what the chain does with it.
     *
     * <p>A rule may also set a resolved value on {@code context} for the rules that follow it.
     *
     * @param context values for one authorization call, never {@code null}
     * @return the reject reason this rule assigns, or an empty {@link Optional} when it assigns
     *         none
     */
    Optional<DeclineReason> evaluate(Context context);

    /**
     * Declares where in the chain this rule sits.
     *
     * @return the segment this rule belongs to, never {@code null}
     */
    Segment segment();
}
