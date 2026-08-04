package com.carddemo.authorization.domain;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides one authorization call and writes the one event that call produces.
 *
 * <p>Source: paragraph {@code 1500-VALIDATE-TRAN} at {@code app/cbl/CBTRN02C.cbl:L370-L422}. The
 * four reject reasons that paragraph reaches split into two segments. Reasons {@code 0100} and
 * {@code 0101} stop the chain at the first decline, gated at {@code app/cbl/CBTRN02C.cbl:L372}.
 * Reasons {@code 0102} and {@code 0103} both run, and the last decline stands, ungated at
 * {@code app/cbl/CBTRN02C.cbl:L407-L420}.
 *
 * <p>Approval is reject reason zero. {@code app/cbl/CBTRN02C.cbl:L208} clears that field before
 * validation, and {@code app/cbl/CBTRN02C.cbl:L211} posts a record whose field still holds zero.
 *
 * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the batch job with
 * return code 4 once any record was rejected, so a declining rule yields an {@link Outcome} and
 * raises nothing.
 *
 * <p>One call writes one event, and the event row commits with the decision that produced it. Add a
 * fifth reject reason by adding one {@link DeclineRule} bean; this class needs no edit for it.
 *
 * <p>Decisions behind this class are recorded in {@code card-platform/docs/decision-log.md}.
 */
@Service
public class AuthorizationService {

    /** Name of the counter carrying one authorization outcome. */
    static final String DECISION_COUNTER = "carddemo.authorization.decisions";

    /** Name of the counter carrying events written to the outbox. */
    static final String EVENT_COUNTER = "carddemo.authorization.events.written";

    /** Name of the timer over one decision. */
    static final String DECISION_TIMER = "carddemo.authorization.decision.duration";

    /** Tag value marking the counted outcome of an approved call. */
    static final String APPROVED_OUTCOME_TAG = "approved";

    /** Name of the tag carrying the outcome of one counted call. */
    private static final String OUTCOME_TAG_NAME = "outcome";

    /** Name of the tag carrying the type of one written event. */
    private static final String EVENT_TYPE_TAG_NAME = "eventType";

    /** Scale a scale-0 account identifier holds, from {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_IDENTIFIER_SCALE = 0;

    /**
     * One authorization decision, as one value.
     *
     * <p>ADDITIVE. The batch program keeps its answer in {@code WS-VALIDATION-FAIL-REASON PIC
     * 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} and has no object to migrate. This record
     * replaces that field.
     *
     * <p>One call yields one reject reason at most. {@code app/cbl/CBTRN02C.cbl:L208} resets the
     * source field per record, and every later assignment overwrites it, so no call carries two.
     *
     * <p>Build a value through {@link #approved(BigDecimal, String)} or through
     * {@link #declined(DeclineReason, BigDecimal, String)}. The canonical constructor rejects a
     * combination those two cannot produce.
     *
     * @param approved      whether the call may proceed, true only when every rule accepted
     * @param declineReason the one reject reason that stands, empty on an approval
     * @param accountId     the account the card resolved to, at scale zero and never negative, or
     *                      {@code null} when the cross-reference resolved none
     * @param transactionId the identifier this decision applies to
     */
    public record Outcome(boolean approved, Optional<DeclineReason> declineReason,
            BigDecimal accountId, String transactionId) {

        /**
         * Checks the combination, and rejects one the two factory methods cannot produce.
         *
         * @throws NullPointerException     when {@code declineReason} or {@code transactionId} is
         *                                  {@code null}
         * @throws IllegalArgumentException when {@code transactionId} is blank or wider than
         *                                  {@code DALYTRAN-ID PIC X(16)}, when {@code approved}
         *                                  disagrees with {@code declineReason}, when an approval
         *                                  names no account, or when {@code accountId} is negative
         *                                  or carries a scale
         */
        public Outcome {
            Objects.requireNonNull(declineReason, "declineReason must be present, empty or filled");
            Objects.requireNonNull(transactionId, "transactionId must be present");

            if (transactionId.isBlank()
                    || transactionId.length() > PicClause.DALYTRAN_ID_WIDTH) {
                throw new IllegalArgumentException("transactionId holds one to "
                        + PicClause.DALYTRAN_ID_WIDTH + " characters and the supplied value holds "
                        + transactionId.length());
            }
            if (approved && declineReason.isPresent()) {
                throw new IllegalArgumentException(
                        "declineReason must be empty on an approval and the supplied value names "
                                + declineReason.map(DeclineReason::code).orElse(""));
            }
            if (!approved && declineReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "declineReason must name a reject reason on a decline and the supplied"
                                + " value is empty");
            }
            if (approved && accountId == null) {
                throw new IllegalArgumentException(
                        "accountId must name the resolved account on an approval and the supplied"
                                + " value is absent");
            }
            if (accountId != null && accountId.scale() != ACCOUNT_IDENTIFIER_SCALE) {
                throw new IllegalArgumentException("accountId must carry scale "
                        + ACCOUNT_IDENTIFIER_SCALE + " and the supplied value carries scale "
                        + accountId.scale());
            }
            if (accountId != null && accountId.signum() < 0) {
                throw new IllegalArgumentException(
                        "accountId must not be negative and the supplied value is");
            }
        }

        /**
         * Builds the outcome of a call every rule accepted.
         *
         * @param accountId     the account the card resolved to, at scale zero
         * @param transactionId the identifier this decision applies to
         * @return the approved outcome
         * @throws NullPointerException     when {@code transactionId} is {@code null}
         * @throws IllegalArgumentException when {@code accountId} is absent, negative or scaled, or
         *                                  when {@code transactionId} breaks its width
         */
        public static Outcome approved(BigDecimal accountId, String transactionId) {
            return new Outcome(true, Optional.empty(), accountId, transactionId);
        }

        /**
         * Builds the outcome of a call one rule declined.
         *
         * @param declineReason the one reject reason that stands
         * @param accountId     the account the card resolved to, or {@code null} when the
         *                      cross-reference resolved none
         * @param transactionId the identifier this decision applies to
         * @return the declined outcome
         * @throws NullPointerException     when {@code declineReason} or {@code transactionId} is
         *                                  {@code null}
         * @throws IllegalArgumentException when {@code accountId} is negative or scaled, or when
         *                                  {@code transactionId} breaks its width
         */
        public static Outcome declined(DeclineReason declineReason, BigDecimal accountId,
                String transactionId) {
            Objects.requireNonNull(declineReason, "declineReason must name a reject reason");

            return new Outcome(false, Optional.of(declineReason), accountId, transactionId);
        }
    }

    /**
     * Rules that end the chain at their own decline, in the order the framework supplied them.
     *
     * <p>Segment {@link DeclineRule.Segment#STOP_ON_FIRST_DECLINE}, from the gate at
     * {@code app/cbl/CBTRN02C.cbl:L372}.
     */
    private final List<DeclineRule> stoppingRules;

    /**
     * Rules that all run, with the last decline standing, in the order the framework supplied them.
     *
     * <p>Segment {@link DeclineRule.Segment#LAST_DECLINE_WINS}, from the two ungated tests at
     * {@code app/cbl/CBTRN02C.cbl:L407-L420}.
     */
    private final List<DeclineRule> overwritingRules;

    /** Resolves a card number from an account identifier, per {@code app/cbl/COTRN02C.cbl:L206}. */
    private final CardCrossReferenceRepository cardCrossReferences;

    /** Allocates an identifier when the request supplies none. */
    private final TransactionIdentifierSource transactionIdentifiers;

    /** Writes the one event this call produces. */
    private final OutboxWriter outboxWriter;

    /** Records the one outcome that names no account. */
    private final UnresolvedCardAttemptRepository unresolvedCardAttempts;

    /** Counts outcomes and events, and times the decision. */
    private final MeterRegistry meters;

    /** Supplies the moment an unresolved-card attempt records. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the whole rule chain and splits it into its two segments.
     *
     * <p>Each rule declares its own {@link DeclineRule.Segment}, and this constructor reads that
     * declaration. No rule class is named here, so one more rule bean joins the chain on its own.
     *
     * @param rules                  every rule of the chain, in the order the framework supplies
     * @param cardCrossReferences    store the card number resolves through
     * @param transactionIdentifiers allocator of transaction identifiers
     * @param outboxWriter           writer of the one event per call
     * @param unresolvedCardAttempts store of attempts that name no account
     * @param meters                 registry the three measurements register with
     * @throws NullPointerException     when an argument or a rule of {@code rules} is {@code null}
     * @throws IllegalArgumentException when a rule declares a segment this class cannot place
     */
    public AuthorizationService(List<DeclineRule> rules,
            CardCrossReferenceRepository cardCrossReferences,
            TransactionIdentifierSource transactionIdentifiers, OutboxWriter outboxWriter,
            UnresolvedCardAttemptRepository unresolvedCardAttempts, MeterRegistry meters) {
        Objects.requireNonNull(rules, "rules must be present");

        List<DeclineRule> stopping = new ArrayList<>();
        List<DeclineRule> overwriting = new ArrayList<>();
        for (DeclineRule rule : rules) {
            Objects.requireNonNull(rule, "rules must hold no absent rule");
            DeclineRule.Segment segment =
                    Objects.requireNonNull(rule.segment(), "every rule must declare its segment");

            if (segment == DeclineRule.Segment.STOP_ON_FIRST_DECLINE) {
                stopping.add(rule);
            } else if (segment == DeclineRule.Segment.LAST_DECLINE_WINS) {
                overwriting.add(rule);
            } else {
                throw new IllegalArgumentException("segment " + segment
                        + " has no place in the chain, which runs "
                        + DeclineRule.Segment.STOP_ON_FIRST_DECLINE + " then "
                        + DeclineRule.Segment.LAST_DECLINE_WINS);
            }
        }

        this.stoppingRules = List.copyOf(stopping);
        this.overwritingRules = List.copyOf(overwriting);
        this.cardCrossReferences =
                Objects.requireNonNull(cardCrossReferences, "cardCrossReferences must be present");
        this.transactionIdentifiers = Objects.requireNonNull(transactionIdentifiers,
                "transactionIdentifiers must be present");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must be present");
        this.unresolvedCardAttempts = Objects.requireNonNull(unresolvedCardAttempts,
                "unresolvedCardAttempts must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
    }

    /**
     * Decides one authorization call.
     *
     * <p>The whole method runs in one transaction, so the decision and its event row commit
     * together or neither does.
     *
     * @param request the validated request body
     * @return the decision, naming the reject reason that stands on a decline
     * @throws NullPointerException when {@code request} is {@code null}, or when it carries no
     *                             amount the tolerant numeric grammar reads
     */
    @Transactional
    public Outcome authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request must be present");

        Timer.Sample sample = Timer.start(meters);
        try {
            return decide(request);
        } finally {
            sample.stop(meters.timer(DECISION_TIMER));
        }
    }

    /**
     * Resolves the transaction values, runs the chain and turns its answer into one outcome.
     *
     * @param request the validated request body
     * @return the decision
     */
    private Outcome decide(AuthorizationRequest request) {
        String transactionId = resolveTransactionId(request);
        BigDecimal amount = Objects.requireNonNull(request.amountValue(),
                "amount must read as a number under the tolerant currency grammar");
        String cardNumber = resolveCardNumber(request);

        if (cardNumber == null) {
            return recordUnresolvedCard(transactionId, null, amount, null);
        }

        DeclineRule.Context context =
                new DeclineRule.Context(cardNumber, amount, request.originTimestamp());
        DeclineReason standing = runChain(context);
        String resolvedAccountId = context.getResolvedAccountId();

        if (resolvedAccountId == null) {
            return recordUnresolvedCard(transactionId, cardNumber, amount, standing);
        }
        if (standing == null) {
            return approve(request, context, transactionId, resolvedAccountId, amount);
        }
        return decline(context, transactionId, resolvedAccountId, amount, standing);
    }

    /**
     * Runs both segments and answers with the reject reason that stands.
     *
     * <p>The first segment stops at its first decline, which is the gate at
     * {@code app/cbl/CBTRN02C.cbl:L372}: the account read runs only while the reject reason still
     * holds zero. A card that fails cross-reference lookup therefore never reaches the account
     * lookup.
     *
     * <p>The second segment runs every one of its rules and keeps the last decline, which is the
     * pair of ungated tests at {@code app/cbl/CBTRN02C.cbl:L407-L420}. A call failing both carries
     * what the later test assigns at {@code app/cbl/CBTRN02C.cbl:L417}.
     *
     * @param context values for this call, which the rules fill in as they resolve rows
     * @return the reject reason that stands, or {@code null} when every rule accepted
     */
    private DeclineReason runChain(DeclineRule.Context context) {
        for (DeclineRule rule : stoppingRules) {
            DeclineReason answer = rule.evaluate(context).orElse(null);
            if (answer != null) {
                return answer;
            }
        }

        DeclineReason standing = null;
        for (DeclineRule rule : overwritingRules) {
            DeclineReason answer = rule.evaluate(context).orElse(null);
            if (answer != null) {
                standing = answer;
            }
        }
        return standing;
    }

    /**
     * Writes the approval event and answers with the approved outcome.
     *
     * @param request           the validated request body
     * @param context           values this call resolved
     * @param transactionId     the identifier this decision applies to
     * @param resolvedAccountId the account the cross-reference named, eleven digits
     * @param amount            the amount at two digits after the decimal point
     * @return the approved outcome
     */
    private Outcome approve(AuthorizationRequest request, DeclineRule.Context context,
            String transactionId, String resolvedAccountId, BigDecimal amount) {
        TransactionAuthorized event = TransactionAuthorized.of(resolvedAccountId, transactionId,
                request.transactionTypeCode(), request.transactionCategoryCode(), request.source(),
                request.description(), amount, request.merchantId(), request.merchantName(),
                request.merchantCity(), request.merchantZip(),
                PanMasker.maskCardNumber(context.getCardNumber()), request.originTimestamp());

        outboxWriter.writeAuthorized(event);
        countEvent(TransactionAuthorized.EVENT_TYPE);
        countOutcome(APPROVED_OUTCOME_TAG);
        return Outcome.approved(accountIdentifierOf(resolvedAccountId), transactionId);
    }

    /**
     * Writes the decline event and answers with the declined outcome.
     *
     * @param context           values this call resolved
     * @param transactionId     the identifier this decision applies to
     * @param resolvedAccountId the account the cross-reference named, eleven digits
     * @param amount            the amount at two digits after the decimal point
     * @param standing          the reject reason that stands
     * @return the declined outcome
     */
    private Outcome decline(DeclineRule.Context context, String transactionId,
            String resolvedAccountId, BigDecimal amount, DeclineReason standing) {
        TransactionDeclined event = TransactionDeclined.of(resolvedAccountId, transactionId,
                standing, amount, PanMasker.maskCardNumber(context.getCardNumber()));

        outboxWriter.writeDeclined(event);
        countEvent(TransactionDeclined.EVENT_TYPE);
        countOutcome(standing.code());
        return Outcome.declined(standing, accountIdentifierOf(resolvedAccountId), transactionId);
    }

    /**
     * Records the one outcome that names no account, and answers with it.
     *
     * <p>Every event contract keys on an eleven-digit account identifier, and this outcome holds
     * none. The attempt lands in {@code unresolved_card_attempt}, which carries the reject reason
     * {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns.
     *
     * @param transactionId the identifier this decision applies to
     * @param cardNumber    the card number the lookup missed on, or {@code null} when the request
     *                      named none
     * @param amount        the amount at two digits after the decimal point
     * @param standing      the reject reason a rule assigned, or {@code null} when no rule ran
     * @return the declined outcome, naming no account
     */
    private Outcome recordUnresolvedCard(String transactionId, String cardNumber, BigDecimal amount,
            DeclineReason standing) {
        DeclineReason reason = standing == null ? DeclineReason.INVALID_CARD_NUMBER : standing;

        unresolvedCardAttempts.save(new UnresolvedCardAttemptEntity(transactionId,
                PanMasker.maskCardNumber(cardNumber), amount, reason.code(), reason.description(),
                clock.instant()));
        countOutcome(reason.code());
        return Outcome.declined(reason, null, transactionId);
    }

    /**
     * Reads the card number this call authorizes against, at the width the cross-reference key
     * holds.
     *
     * <p>A request names one of the two identifiers, and
     * {@code app/cbl/COTRN02C.cbl:L195-L223} reads the other through the cross-reference. The
     * account branch of that paragraph reads the alternate index at
     * {@code app/cbl/COTRN02C.cbl:L206-L209}.
     *
     * <p>The value returns unmasked and padded to sixteen characters. A shorter value would miss on
     * a key of {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5} and report a
     * reject reason the card never earned.
     *
     * @param request the validated request body
     * @return the full sixteen-character Primary Account Number (PAN), or {@code null} when neither
     *         the request nor the cross-reference names one
     */
    private String resolveCardNumber(AuthorizationRequest request) {
        String supplied = request.canonicalCardNumber();
        if (supplied != null) {
            return leftPadWithZeros(supplied, PicClause.XREF_CARD_NUM_WIDTH);
        }

        String accountId = request.canonicalAccountId();
        if (accountId == null) {
            return null;
        }
        return cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(accountId)
                .map(CardCrossReferenceEntity::getCardNumber)
                .map(cardNumber -> leftPadWithZeros(cardNumber, PicClause.XREF_CARD_NUM_WIDTH))
                .orElse(null);
    }

    /**
     * Reads the identifier the request named, or allocates one.
     *
     * <p>The online capture screen names none. {@code app/cbl/COTRN02C.cbl:L444-L451} allocates one
     * there, and {@link TransactionIdentifierSource} replaces that mechanism.
     *
     * @param request the validated request body
     * @return the identifier this decision applies to
     */
    private String resolveTransactionId(AuthorizationRequest request) {
        String supplied = request.transactionId();
        if (supplied != null && !supplied.isBlank()) {
            return supplied;
        }
        return transactionIdentifiers.nextIdentifier();
    }

    /**
     * Reads an eleven-digit account identifier as a number at scale zero.
     *
     * <p>Source width {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The
     * eleven-character form travels on the event and stays with the row that carries it; callers of
     * this class read the number.
     *
     * @param resolvedAccountId eleven decimal digits
     * @return the same identifier as a number at scale zero
     */
    private static BigDecimal accountIdentifierOf(String resolvedAccountId) {
        return new BigDecimal(resolvedAccountId.strip());
    }

    /**
     * Widens a value to a fixed width with leading zeros, and returns a value already that wide
     * unchanged.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L218-L221} moves a parsed number back into a display field of
     * fixed width, which pads it the same way.
     *
     * @param value the value to widen
     * @param width the width to reach
     * @return the widened value
     */
    private static String leftPadWithZeros(String value, int width) {
        String stripped = value.strip();
        if (stripped.length() >= width) {
            return stripped;
        }
        return "0".repeat(width - stripped.length()) + stripped;
    }

    /**
     * Counts one outcome, tagged with the approval marker or with the reject reason.
     *
     * @param outcome the outcome tag
     */
    private void countOutcome(String outcome) {
        Counter.builder(DECISION_COUNTER)
                .tag(OUTCOME_TAG_NAME, outcome)
                .register(meters)
                .increment();
    }

    /**
     * Counts one event written to the outbox, tagged with its type.
     *
     * @param eventType the event type tag
     */
    private void countEvent(String eventType) {
        Counter.builder(EVENT_COUNTER)
                .tag(EVENT_TYPE_TAG_NAME, eventType)
                .register(meters)
                .increment();
    }
}
