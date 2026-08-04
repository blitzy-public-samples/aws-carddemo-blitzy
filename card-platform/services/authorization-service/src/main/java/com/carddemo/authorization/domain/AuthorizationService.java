package com.carddemo.authorization.domain;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.api.AuthorizationResponse;
import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.UnresolvedCardAttemptRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides one authorization call, and writes the decision and its event in one transaction.
 *
 * <p>The chain comes from paragraph {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, and the four rules under {@code domain/rules} carry the four
 * reject codes that paragraph and {@code app/cbl/CBTRN02C.cbl:L380-L420} assign. This class runs them
 * in order and applies the two segment behaviours {@link DeclineRule.Segment} declares.
 *
 * <p>One call produces exactly one event. An approval produces {@link TransactionAuthorized} and a
 * decline produces {@link TransactionDeclined}, and both leave through the outbox rather than through a
 * broker call, so request handling never waits for Kafka.
 *
 * <p>One outcome produces no event. {@link DeclineReason#INVALID_CARD_NUMBER} means the
 * cross-reference read at {@code app/cbl/CBTRN02C.cbl:L383-L384} found no row, so no account
 * identifier exists and every event contract requires one. That attempt is recorded in
 * {@code unresolved_card_attempt} instead, which is what
 * {@code app/cbl/CBTRN02C.cbl:L446-L465} does in its own terms.
 *
 * <p>The decision row and the event row commit together, or neither does. The source offers no
 * atomicity to reproduce: {@code app/cbl/CBTRN02C.cbl:L440-L442} performs three writes with no
 * rollback and every file definition at {@code app/csd/CARDDEMO.CSD:L3-L9} carries
 * {@code RECOVERY(NONE) JOURNAL(NO)}.
 *
 * <p>A decline is expected traffic and not an error. {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the
 * batch job with return code 4 when any record was rejected, and this class returns a response rather
 * than throwing.
 *
 * <p>Three measurements follow the three the platform requires: one counter per outcome, one counter
 * of events written, and one timer over the decision.
 */
@Service
public class AuthorizationService {

    /** Name of the counter carrying one authorization outcome. */
    static final String DECISION_COUNTER = "carddemo.authorization.decisions";

    /** Name of the counter carrying events written to the outbox. */
    static final String EVENT_COUNTER = "carddemo.authorization.events.written";

    /** Name of the timer over one decision. */
    static final String DECISION_TIMER = "carddemo.authorization.decision.duration";

    /** The rules of the chain, ordered by the annotation each one carries. */
    private final List<DeclineRule> rules;

    /** Allocates an identifier when the request supplies none. */
    private final TransactionIdentifierSource transactionIdentifiers;

    /** Writes the one event this call produces. */
    private final OutboxWriter outboxWriter;

    /** Records the one outcome that produces no event. */
    private final UnresolvedCardAttemptRepository unresolvedCardAttempts;

    /** Counts outcomes and events, and times the decision. */
    private final MeterRegistry meters;

    /** Supplies the moment an unresolved-card attempt records. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the rule chain, the identifier source, the outbox writer, the attempt store and the
     * meters.
     *
     * @param rules                  every rule of the chain, in the order the framework supplies
     * @param transactionIdentifiers allocator of transaction identifiers
     * @param outboxWriter           writer of the one event per call
     * @param unresolvedCardAttempts store of attempts that resolve to no account
     * @param meters                 registry the three measurements register with
     */
    public AuthorizationService(List<DeclineRule> rules,
            TransactionIdentifierSource transactionIdentifiers, OutboxWriter outboxWriter,
            UnresolvedCardAttemptRepository unresolvedCardAttempts, MeterRegistry meters) {
        this.rules = List.copyOf(rules);
        this.transactionIdentifiers = transactionIdentifiers;
        this.outboxWriter = outboxWriter;
        this.unresolvedCardAttempts = unresolvedCardAttempts;
        this.meters = meters;
    }

    /**
     * Decides one authorization call.
     *
     * <p>The card number is resolved first, from the request's own card number or from the
     * cross-reference row the account identifier names. {@code app/cbl/COTRN02C.cbl:L195-L230}
     * resolves the pair the same way, taking whichever field arrived and reading the other.
     *
     * <p>The whole method runs in one transaction, so the decision and the event row commit together.
     *
     * @param request the validated request body
     * @return the decision, carrying a reject code and its text on a decline
     * @throws IllegalStateException when the request carries neither identifier, which the request
     *                               constraints already reject
     */
    @Transactional
    public AuthorizationResponse authorize(AuthorizationRequest request) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return decide(request);
        } finally {
            sample.stop(meters.timer(DECISION_TIMER));
        }
    }

    /**
     * Runs the chain and turns its answer into a response, an event and a measurement.
     *
     * @param request the validated request body
     * @return the decision
     */
    private AuthorizationResponse decide(AuthorizationRequest request) {
        String cardNumber = request.canonicalCardNumber();
        BigDecimal amount = request.amountValue();
        String transactionId = resolveTransactionId(request);

        if (cardNumber == null) {
            return declineUnresolvedCard(transactionId, cardNumber, amount);
        }

        DeclineRule.Context context =
                new DeclineRule.Context(cardNumber, amount, request.originTimestamp());
        Optional<DeclineReason> reason = runChain(context);

        if (reason.isEmpty()) {
            return approve(request, context, transactionId, amount);
        }
        if (reason.get() == DeclineReason.INVALID_CARD_NUMBER) {
            return declineUnresolvedCard(transactionId, cardNumber, amount);
        }
        return decline(context, transactionId, amount, reason.get());
    }

    /**
     * Runs every rule until a stopping rule declines, then returns the answer that stands.
     *
     * <p>A rule declaring {@link DeclineRule.Segment#STOP_ON_FIRST_DECLINE} ends the chain, which is
     * the gate at {@code app/cbl/CBTRN02C.cbl:L372}: the account read runs only while the reject reason
     * still holds zero. A rule declaring {@link DeclineRule.Segment#LAST_DECLINE_WINS} lets the rules
     * after it run and overwrite its answer, which is the pair of ungated tests at
     * {@code app/cbl/CBTRN02C.cbl:L407-L420}.
     *
     * @param context values for this call, which the rules fill in as they resolve rows
     * @return the reject reason that stands, or an empty result when every rule accepted
     */
    private Optional<DeclineReason> runChain(DeclineRule.Context context) {
        Optional<DeclineReason> standing = Optional.empty();

        for (DeclineRule rule : rules) {
            Optional<DeclineReason> answer = rule.evaluate(context);
            if (answer.isEmpty()) {
                continue;
            }
            if (rule.segment() == DeclineRule.Segment.STOP_ON_FIRST_DECLINE) {
                return answer;
            }
            standing = answer;
        }
        return standing;
    }

    /**
     * Builds the approved response and writes the approval event.
     *
     * @param request       the validated request body
     * @param context       values this call resolved, carrying the cross-reference row
     * @param transactionId the identifier this decision applies to
     * @param amount        the amount at two digits after the decimal point
     * @return the approved response
     */
    private AuthorizationResponse approve(AuthorizationRequest request, DeclineRule.Context context,
            String transactionId, BigDecimal amount) {
        String accountId = accountIdentifierOf(context);
        TransactionAuthorized event = TransactionAuthorized.of(accountId, transactionId,
                request.transactionTypeCode(), request.transactionCategoryCode(), request.source(),
                request.description(), amount, request.merchantId(), request.merchantName(),
                request.merchantCity(), request.merchantZip(),
                PanMasker.maskCardNumber(context.getCardNumber()), request.originTimestamp());

        outboxWriter.write(event.envelope(), event);
        meters.counter(EVENT_COUNTER, "eventType", TransactionAuthorized.EVENT_TYPE).increment();
        countOutcome("approved");
        return AuthorizationResponse.approve(transactionId, accountId);
    }

    /**
     * Builds a declined response and writes the decline event, for a reject code that names an
     * account.
     *
     * @param context       values this call resolved, carrying the cross-reference row
     * @param transactionId the identifier this decision applies to
     * @param amount        the amount at two digits after the decimal point
     * @param reason        the reject code that stands
     * @return the declined response
     */
    private AuthorizationResponse decline(DeclineRule.Context context, String transactionId,
            BigDecimal amount, DeclineReason reason) {
        String accountId = accountIdentifierOf(context);
        TransactionDeclined event = TransactionDeclined.of(accountId, transactionId, reason, amount,
                PanMasker.maskCardNumber(context.getCardNumber()));

        outboxWriter.write(event.envelope(), event);
        meters.counter(EVENT_COUNTER, "eventType", TransactionDeclined.EVENT_TYPE).increment();
        countOutcome(reason.code());
        return AuthorizationResponse.decline(transactionId, accountId, reason);
    }

    /**
     * Records the one outcome that names no account, and writes no event for it.
     *
     * <p>Every event contract requires an eleven-digit account identifier, and this outcome has none
     * to supply. Publishing a substitute would name an account the read did not resolve, so nothing is
     * published and the attempt lands in {@code unresolved_card_attempt} instead.
     *
     * @param transactionId the identifier this decision applies to
     * @param cardNumber    the card number the lookup failed on, or {@code null} when the request
     *                      resolved none
     * @param amount        the amount at two digits after the decimal point
     * @return the declined response carrying reject code {@code 0100} and no account identifier
     */
    private AuthorizationResponse declineUnresolvedCard(String transactionId, String cardNumber,
            BigDecimal amount) {
        DeclineReason reason = DeclineReason.INVALID_CARD_NUMBER;

        unresolvedCardAttempts.save(new UnresolvedCardAttemptEntity(transactionId,
                PanMasker.maskCardNumber(cardNumber), amount, reason.code(), reason.description(),
                clock.instant()));
        countOutcome(reason.code());
        return AuthorizationResponse.declineUnresolvedCard(transactionId);
    }

    /**
     * Returns the eleven-digit account identifier the cross-reference row carried.
     *
     * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} holds eleven digits, and
     * column {@code account_id} holds {@code NUMERIC(11,0)}, so the value is rendered back to eleven
     * characters with its leading zeros. Those zeros belong to the value: they reach the Kafka message
     * key, and a key that dropped them would name a different partition.
     *
     * @param context values this call resolved
     * @return eleven characters
     */
    private static String accountIdentifierOf(DeclineRule.Context context) {
        return context.getCardCrossReference().getAccountId();
    }

    /**
     * Reads the identifier the request supplied, or allocates one.
     *
     * <p>The online capture screen supplies none. {@code app/cbl/COTRN02C.cbl:L444-L451} allocates one
     * instead, and {@link TransactionIdentifierSource} replaces that mechanism.
     *
     * @param request the validated request body
     * @return the identifier this decision applies to
     */
    private String resolveTransactionId(AuthorizationRequest request) {
        if (request.transactionId() != null) {
            return request.transactionId();
        }
        return transactionIdentifiers.nextIdentifier();
    }

    /**
     * Counts one outcome, tagged with the word {@code approved} or with the reject code.
     *
     * @param outcome the outcome tag
     */
    private void countOutcome(String outcome) {
        Counter.builder(DECISION_COUNTER)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
