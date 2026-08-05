package com.carddemo.authorization.domain;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides one authorization call and writes the one event a resolved call produces.
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
 * <p>A call whose card resolves to an account writes exactly one event, and the event row commits
 * with the decision that produced it. A call whose card resolves to no account writes no event: it
 * records an {@code unresolved_card_attempt} row instead, because every event contract keys on an
 * account identifier that outcome does not have. Add a fifth reject reason by adding one
 * {@link DeclineRule} bean; this class needs no edit for it.
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

    /** Name of the counter carrying one infrastructure fault. No decline reaches it. */
    static final String FAILURES_COUNTER = "carddemo.authorization.failures";

    /** The {@link #FAILURES_COUNTER} stage of a decision and event row that could not commit. */
    static final String PERSIST_STAGE = "persist";

    /** The {@link #FAILURES_COUNTER} stage of a call refused because its replica rows were too old. */
    static final String REPLICA_STAGE = "replica";

    /** Name of the tag carrying the outcome of one counted call. */
    private static final String OUTCOME_TAG_NAME = "outcome";

    /** Name of the tag carrying the type of one written event. */
    private static final String EVENT_TYPE_TAG_NAME = "eventType";

    /** Name of the tag carrying the stage that raised one counted fault. */
    private static final String STAGE_TAG_NAME = "stage";

    /** Scale a scale-0 account identifier holds, from {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_IDENTIFIER_SCALE = 0;

    /** Fixed refusal for a caller-supplied account cross-check that names another account. */
    static final String ACCOUNT_CROSS_CHECK_MISMATCH_MESSAGE =
            "accountId does not match the account resolved from cardNumber";

    /**
     * One authorization decision, as one value.
     *
     * <p>No COBOL ancestor. The batch program keeps its answer in {@code WS-VALIDATION-FAIL-REASON
     * PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} and has no object to migrate. This record
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
         * @return the approved decision, holding the measurements it earned
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
         * @return the declined decision, holding the measurements it earned
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

    /** Writes the one event a resolved call produces. */
    private final OutboxWriter outboxWriter;

    /** Records the one outcome that names no account. */
    private final UnresolvedCardAttemptRepository unresolvedCardAttempts;

    /** Records each decision and the identity that asked for it. */
    private final AuthorizationDecisionRepository authorizationDecisions;

    /** Bounds how far from the service clock a capture moment may sit. */
    private final OriginTimestampWindow originTimestampWindow;

    /** Counts outcomes and events, and times the decision. */
    private final MeterRegistry meters;

    /**
     * How old the observation on a replica row may be and still be authorized against.
     *
     * <p>{@code carddemo.replica.max-staleness} supplies it. Past that age the call is refused as an
     * infrastructure fault rather than declined, because a stale replica is not a decision the source
     * ever took: {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395} read the
     * cross-reference and account datasets themselves.
     */
    private final Duration replicaMaxStaleness;

    /**
     * Opens the one transaction a decision commits in.
     *
     * <p>The boundary is explicit rather than declarative because every meter has to be touched after
     * the commit, and a declarative boundary ends when the method returns, which is after the last
     * statement of the method body has already run.
     */
    private final TransactionTemplate transactionTemplate;

    /** Supplies the moment an attempt and a decision record. */
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
     * @param outboxWriter           writer of the one event a resolved call produces
     * @param unresolvedCardAttempts store of attempts that name no account
     * @param authorizationDecisions store of each decision and the identity behind it
     * @param originTimestampWindow  bound on the capture moment a caller may supply
     * @param meters                 registry the three measurements register with
     * @param transactionTemplate    opens the one transaction a decision commits in, so every meter
     *                               is touched after that transaction has committed
     * @param properties             the validated service configuration, including replica freshness
     * @throws NullPointerException     when an argument or a rule of {@code rules} is {@code null}
     * @throws IllegalArgumentException when a rule declares a segment this class cannot place
     */
    public AuthorizationService(List<DeclineRule> rules,
            CardCrossReferenceRepository cardCrossReferences,
            TransactionIdentifierSource transactionIdentifiers, OutboxWriter outboxWriter,
            UnresolvedCardAttemptRepository unresolvedCardAttempts,
            AuthorizationDecisionRepository authorizationDecisions,
            OriginTimestampWindow originTimestampWindow, MeterRegistry meters,
            TransactionTemplate transactionTemplate, AuthorizationProperties properties) {
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
        this.authorizationDecisions = Objects.requireNonNull(authorizationDecisions,
                "authorizationDecisions must be present");
        this.originTimestampWindow = Objects.requireNonNull(originTimestampWindow,
                "originTimestampWindow must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate,
                "transactionTemplate must be present");

        AuthorizationProperties.Replica replica =
                Objects.requireNonNull(
                        Objects.requireNonNull(properties, "properties must be present").replica(),
                        "properties.replica must be present");
        this.replicaMaxStaleness = Objects.requireNonNull(replica.maxStaleness(),
                "properties.replica.maxStaleness must be present");

        if (replicaMaxStaleness.isNegative() || replicaMaxStaleness.isZero()) {
            throw new IllegalArgumentException("replicaMaxStaleness must be positive, found "
                    + replicaMaxStaleness);
        }
    }

    /**
     * Decides one authorization call.
     *
     * <p>The decision and its event row commit together or neither does, inside the transaction
     * {@link #transactionTemplate} opens. Everything measured is measured outside that transaction,
     * and the ordering is the point of this method.
     *
     * <p>A counter named for committed work has to be incremented after the commit. Incrementing
     * inside the transaction reports an approval that a later constraint violation, a lock timeout or
     * a lost connection then rolls back, and the series keeps the increment because a meter has no
     * part in a database transaction and nothing rolls it back. The reported approval count would
     * then exceed the events the outbox actually holds, and the difference would be invisible.
     * {@link #decide} therefore returns the measurements it earned and records none of them, and this
     * method applies them only once {@link TransactionTemplate#execute} has returned.
     *
     * <p>The timer covers the commit for the same reason. It is described as running to commit, and a
     * sample stopped inside the transaction excludes the flush and the commit, which is where a slow
     * decision spends its time.
     *
     * <p>A fault that prevents the commit increments the persist stage of the failure counter, and a
     * refusal to authorize against a stale replica increments the replica stage. Both are counted here
     * rather than where they are raised, which keeps one rule with no exceptions: nothing inside the
     * transactional unit touches a meter. Neither stage ever carries a decline, because a decline is a
     * committed outcome and expected traffic per {@code app/cbl/CBTRN02C.cbl:L229-L230}. The publish
     * stage is recorded by the relay, which is the only component that publishes.
     *
     * @param request the validated request body
     * @param actor   the request identity the decision row records, bounded by
     *                {@link AuthenticatedActor#actorOf(java.security.Principal)}
     * @return the decision, naming the reject reason that stands on a decline
     * @throws NullPointerException when {@code request} or {@code actor} is {@code null}, or when the
     *                             request carries no amount the tolerant numeric grammar reads
     */
    public Outcome authorize(AuthorizationRequest request, String actor) {
        Objects.requireNonNull(request, "request must be present");
        Objects.requireNonNull(actor, "actor must be present");

        Timer.Sample sample = Timer.start(meters);
        try {
            Decision decision = transactionTemplate.execute(status -> decide(request, actor));
            Objects.requireNonNull(decision, "the transaction must answer with one decision");

            decision.record(this);
            return decision.outcome();
        } catch (StaleReplicaException staleReplica) {
            countFailure(REPLICA_STAGE);
            throw staleReplica;
        } catch (DataAccessException | TransactionException persistFailure) {
            countFailure(PERSIST_STAGE);
            throw persistFailure;
        } finally {
            sample.stop(meters.timer(DECISION_TIMER));
        }
    }

    /**
     * One committed decision and the measurements it earned, held until the commit has happened.
     *
     * <p>This record is what keeps {@link #decide} free of meter calls. It carries the outcome the
     * caller receives, the outcome tag to count, and the type of the event written to the outbox, or
     * {@code null} where the call wrote none. An unresolved card writes no event and still counts an
     * outcome, so the two are separate components rather than one.
     *
     * @param outcome    the decision the caller receives
     * @param outcomeTag the {@code outcome} tag value this decision counts under
     * @param eventType  the type of the event written to the outbox, or {@code null} when the call
     *                   wrote none
     */
    private record Decision(Outcome outcome, String outcomeTag, String eventType) {

        /**
         * Applies the held measurements. Called only after the transaction has committed.
         *
         * @param service the service whose meters these measurements belong to
         */
        void record(AuthorizationService service) {
            service.countOutcome(outcomeTag);
            if (eventType != null) {
                service.countEvent(eventType);
            }
        }
    }

    /**
     * Resolves the transaction values, runs the chain and turns its answer into one decision.
     *
     * <p>Runs inside the transaction and records no measurement. Every measurement travels back on
     * the {@link Decision} and is applied by {@link #authorize} after the commit.
     *
     * <p>Freshness is checked after the chain has run and before the decision is turned into an
     * event. The chain is what resolves the two replica rows onto the context, so there is nothing to
     * check before it, and the check has to happen before an event is written because the event is
     * what makes the decision real. A stale replica raises
     * {@link StaleReplicaException} rather than declining: see that class for why it cannot be a
     * fifth reject reason.
     *
     * <p>Two checks run before the chain. The request must have named its subject, by card number or
     * by account identifier, and an account-only request resolves its card through the cross-reference
     * before the chain runs. The capture moment must sit inside the window
     * {@link OriginTimestampWindow} holds, because reject reason {@code 0103} compares that value
     * against the account expiry and a caller who backdates it authorizes against an expired account.
     * Either refusal is an {@link IllegalArgumentException}, which {@code api/GlobalExceptionHandler}
     * answers {@code 422} to, and neither consumes an identifier from the sequence.
     *
     * <p>After the cross-reference rule resolves an account, a caller-supplied account identifier
     * must agree with it. Where the caller supplied both identifiers the card remains the lookup key
     * and the account is a cross-check only; where the caller supplied the account alone the two
     * agree by construction, because the card was read from the row that account keys.
     *
     * <p>Two refusals precede any of that, and they are the two limbs
     * {@code app/cbl/COTRN02C.cbl:L195-L230} ends on.
     * {@link #resolveCardNumber(AuthorizationRequest)} raises
     * {@value AuthorizationRequest#ACCOUNT_ID_NOT_FOUND_MESSAGE} where an account resolved no card,
     * and this method raises {@value AuthorizationRequest#IDENTIFIER_REQUIRED_MESSAGE} where no
     * usable identifier arrived at all. Neither allocates an identifier, writes an event or records
     * a decision.
     *
     * @param request the validated request body
     * @param actor   the request identity the decision row records
     * @return the decision and the measurements it earned
     */
    private Decision decide(AuthorizationRequest request, String actor) {
        String cardNumber = resolveCardNumber(request);
        if (cardNumber == null) {
            throw new IllegalArgumentException(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE);
        }
        originTimestampWindow.require(request.originTimestamp(), clock.instant());

        BigDecimal amount = Objects.requireNonNull(request.amountValue(),
                "amount must read as a number under the tolerant currency grammar");

        DeclineRule.Context context =
                new DeclineRule.Context(cardNumber, amount, request.originTimestamp());
        DeclineReason standing = runChain(context);
        String resolvedAccountId = context.getResolvedAccountId();

        requireAccountCrossCheck(request, resolvedAccountId);
        String transactionId = allocateTransactionId();

        if (resolvedAccountId == null) {
            return recordUnresolvedCard(transactionId, cardNumber, amount, standing, actor);
        }

        requireFreshReplicaData(context, resolvedAccountId);

        if (standing == null) {
            return approve(request, context, transactionId, resolvedAccountId, amount, actor);
        }
        return decline(context, transactionId, resolvedAccountId, amount, standing, actor);
    }

    /**
     * Refuses to authorize against a replica that has not been observed recently enough.
     *
     * <p>ADDITIVE, and it has no COBOL ancestor because the source has nothing that can go stale.
     * {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395} issue keyed reads
     * against the cross-reference and account datasets themselves. This service reads
     * {@code card_xref} and {@code account_credit_snapshot}, which are replicas kept current by
     * state-change events, and a replica whose events stopped arriving keeps answering with whatever
     * it last knew.
     *
     * <p>That failure is silent, which is what makes it dangerous. A stale credit limit or a stale
     * pair of cycle accumulators raises nothing: the credit-limit rule at
     * {@code app/cbl/CBTRN02C.cbl:L403-L407} computes an answer from obsolete numbers and approves a
     * transaction the current numbers would have declined. Refusing the call is the only outcome that
     * does not silently authorize against numbers this service cannot vouch for.
     *
     * <p>The check applies to the rows the decision was computed from, and only to those. Where a rule
     * declined because a row is absent, the decline came from the absence and not from any value, so
     * there is no observation to be too old: an absent cross-reference row is reject reason
     * {@link DeclineReason#INVALID_CARD_NUMBER} at {@code app/cbl/CBTRN02C.cbl:L385-L387} and an absent
     * account row is {@link DeclineReason#ACCOUNT_NOT_FOUND} at
     * {@code app/cbl/CBTRN02C.cbl:L397-L399}. Both are reject reasons the source defines, and refusing
     * those calls instead would replace a defined outcome with a service fault and break equivalence.
     * The same holds for a chain that stopped early: a rule that never ran read nothing.
     *
     * <p>Both rows present means the decision did read replica values. That covers every approval,
     * every over-limit decline computed at {@code app/cbl/CBTRN02C.cbl:L403-L413} and every expiry
     * decline at {@code app/cbl/CBTRN02C.cbl:L414-L420}, which is the whole set of outcomes an obsolete
     * copy could get wrong.
     *
     * @param context           the resolved values for this call
     * @param resolvedAccountId the account the cross-reference named
     * @throws StaleReplicaException when both rows were read and either one is missing an observation
     *                               or carries one older than {@link #replicaMaxStaleness}
     */
    private void requireFreshReplicaData(DeclineRule.Context context, String resolvedAccountId) {
        boolean decisionReadReplicaValues = context.getCardCrossReference() != null
                && context.getAccountCreditSnapshot() != null;

        if (!decisionReadReplicaValues
                || context.isReplicaDataFresh(clock.instant(), replicaMaxStaleness)) {
            return;
        }
        throw new StaleReplicaException(resolvedAccountId, replicaMaxStaleness);
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
     * @param actor             the request identity the decision row records
     * @return the approved decision, holding the measurements it earned
     */
    private Decision approve(AuthorizationRequest request, DeclineRule.Context context,
            String transactionId, String resolvedAccountId, BigDecimal amount, String actor) {
        TransactionAuthorized event = TransactionAuthorized.of(resolvedAccountId, transactionId,
                request.transactionTypeCode(), canonicalCategoryCode(request), request.source(),
                request.description(), amount, request.merchantId(), request.merchantName(),
                request.merchantCity(), request.merchantZip(),
                PanMasker.maskCardNumber(context.getCardNumber()),
                PanMasker.cardToken(context.getCardNumber()), request.originTimestamp());

        outboxWriter.writeAuthorized(event);
        authorizationDecisions.save(AuthorizationDecisionEntity.approved(transactionId, actor,
                resolvedAccountId, event.maskedCardNumber(), event.cardToken(), amount,
                clock.instant(), event.eventId()));
        return new Decision(Outcome.approved(accountIdentifierOf(resolvedAccountId), transactionId),
                APPROVED_OUTCOME_TAG, TransactionAuthorized.EVENT_TYPE);
    }

    /**
     * Writes the decline event and answers with the declined outcome.
     *
     * @param context           values this call resolved
     * @param transactionId     the identifier this decision applies to
     * @param resolvedAccountId the account the cross-reference named, eleven digits
     * @param amount            the amount at two digits after the decimal point
     * @param standing          the reject reason that stands
     * @param actor             the request identity the decision row records
     * @return the declined decision, holding the measurements it earned
     */
    private Decision decline(DeclineRule.Context context, String transactionId,
            String resolvedAccountId, BigDecimal amount, DeclineReason standing, String actor) {
        TransactionDeclined event = TransactionDeclined.of(resolvedAccountId, transactionId,
                standing, amount, PanMasker.maskCardNumber(context.getCardNumber()));

        outboxWriter.writeDeclined(event);
        authorizationDecisions.save(AuthorizationDecisionEntity.declined(transactionId, actor,
                resolvedAccountId, event.maskedCardNumber(),
                PanMasker.cardToken(context.getCardNumber()), amount, standing.code(),
                standing.description(), clock.instant(), event.eventId()));
        return new Decision(
                Outcome.declined(standing, accountIdentifierOf(resolvedAccountId), transactionId),
                standing.code(), TransactionDeclined.EVENT_TYPE);
    }

    /**
     * Records the one outcome that names no account, publishes its decline event, and answers with
     * it.
     *
     * <p>The attempt lands in {@code unresolved_card_attempt}, which carries the reject reason
     * {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns, and the event row lands in the outbox beside
     * it. Both writes and the decision row join the transaction {@link #authorize(AuthorizationRequest)}
     * opened, so one call still produces exactly one event and an attempt that reached no consumer
     * cannot exist.
     *
     * <p>The event is version {@value TransactionDeclined#UNRESOLVED_ACCOUNT_SCHEMA_VERSION}, which
     * {@code schemas/transaction-declined-v2.json} governs. That contract declares no
     * {@code accountId} and keys the event on the transaction identifier, because no account
     * identifier exists to key it on: the cross-reference read missed and the short-circuit at
     * {@code app/cbl/CBTRN02C.cbl:L376-L378} stops the account read from running. Version one, which
     * carries an account identifier, is untouched, so a consumer reading only version one keeps
     * working.
     *
     * @param transactionId the identifier this decision applies to
     * @param cardNumber    the card number the lookup missed on
     * @param amount        the amount at two digits after the decimal point
     * @param standing      the reject reason a rule assigned, or {@code null} when no rule ran
     * @param actor         the request identity the decision row records
     * @return the declined decision, naming no account and holding the measurements it earned
     */
    private Decision recordUnresolvedCard(String transactionId, String cardNumber, BigDecimal amount,
            DeclineReason standing, String actor) {
        DeclineReason reason = standing == null ? DeclineReason.INVALID_CARD_NUMBER : standing;
        String maskedCardNumber = PanMasker.maskCardNumber(cardNumber);

        unresolvedCardAttempts.save(new UnresolvedCardAttemptEntity(transactionId,
                maskedCardNumber, amount, reason.code(), reason.description(), clock.instant()));
        TransactionDeclined event = TransactionDeclined.ofUnresolvedAccount(transactionId, amount,
                maskedCardNumber);
        outboxWriter.writeDeclined(event);
        authorizationDecisions.save(AuthorizationDecisionEntity.declined(transactionId, actor, null,
                maskedCardNumber, PanMasker.tokenOf(cardNumber), amount, reason.code(),
                reason.description(), clock.instant(), event.eventId()));
        return new Decision(Outcome.declined(reason, null, transactionId), reason.code(),
                TransactionDeclined.EVENT_TYPE);
    }

    /**
     * Reads the card number this call authorizes against, at the width the cross-reference key
     * holds.
     *
     * <p>{@code VALIDATE-INPUT-KEY-FIELDS} at {@code app/cbl/COTRN02C.cbl:L195-L230} accepts either
     * identifier, and both of its branches are reproduced here. A request carrying a card number
     * uses it, which is the card branch at {@code :L210-L223}. A request carrying an account
     * identifier alone reads the cross-reference by that identifier and takes the card number the
     * row carries, which is {@code PERFORM READ-CXACAIX-FILE} at {@code :L208} followed by
     * {@code MOVE XREF-CARD-NUM TO CARDNINI} at {@code :L209}. The alternate index that read uses is
     * defined at {@code app/jcl/XREFFILE.jcl:L72-L77} over the account identifier at offset 25, and
     * {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc} is the target
     * form of it: a keyed read of one row, taken in ascending card-number order so one account
     * always resolves the same card.
     *
     * <p>An account that holds no cross-reference row resolves no card, and this method raises
     * {@value AuthorizationRequest#ACCOUNT_ID_NOT_FOUND_MESSAGE} where the read missed rather than
     * returning {@code null}. That is the source shape: the {@code NOTFND} limb of that same read
     * answers with the identical text at {@code app/cbl/COTRN02C.cbl:L591-L592} and re-sends the
     * screen, so nothing is captured. It is not decline reason {@code 0100}, which belongs to a
     * request that did name a card number, because a request naming no card has no card number to
     * record, mask or tokenize on the declined event.
     *
     * <p>A {@code null} answer therefore means one thing only: no usable identifier arrived. Either
     * the caller named neither field, which is the {@code WHEN OTHER} limb at
     * {@code app/cbl/COTRN02C.cbl:L224-L229}, or the field it named is narrower than the key it
     * would be. {@link AuthorizationRequest#isIdentifierSupplied()} refuses the first case at the
     * interface and {@link AuthorizationRequest#CARD_NUMBER_PATTERN} refuses the second, and the
     * caller of this method raises {@value AuthorizationRequest#IDENTIFIER_REQUIRED_MESSAGE} for
     * either, so a caller reaching the domain directly is refused on the same terms.
     *
     * <p>The value returns unmasked and already sixteen characters wide, whichever branch supplied
     * it. A shorter value is rejected by {@link AuthorizationRequest#CARD_NUMBER_PATTERN} on the
     * card branch and cannot arise on the account branch, where the width is the column's own;
     * widening it would name a different card and could authorize against another cardholder's
     * account.
     *
     * @param request the validated request body
     * @return the full sixteen-character Primary Account Number (PAN), or {@code null} when the
     *         account the request named resolves no card
     */
    private String resolveCardNumber(AuthorizationRequest request) {
        if (request.isCardNumberSupplied()) {
            return request.canonicalCardNumber();
        }

        String accountId = request.canonicalAccountId();
        if (accountId == null) {
            return null;
        }
        return cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(accountId)
                .map(CardCrossReferenceEntity::getCardNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        AuthorizationRequest.ACCOUNT_ID_NOT_FOUND_MESSAGE));
    }

    /**
     * Refuses an optional account cross-check that differs from the account the card resolved.
     *
     * <p>The comparison runs only after a cross-reference row has supplied an authoritative
     * account. A missing-card decline has no account to compare and publishes its transaction-keyed
     * version 2 event without attributing the caller's untrusted value to any account.
     *
     * @param request           the validated request body
     * @param resolvedAccountId the account the card cross-reference supplied, or {@code null}
     * @throws IllegalArgumentException when a supplied account identifier differs from the
     *                                  resolved one
     */
    private static void requireAccountCrossCheck(AuthorizationRequest request,
            String resolvedAccountId) {
        String suppliedAccountId = request.canonicalAccountId();
        if (resolvedAccountId != null && suppliedAccountId != null
                && !resolvedAccountId.equals(suppliedAccountId)) {
            throw new IllegalArgumentException(ACCOUNT_CROSS_CHECK_MISMATCH_MESSAGE);
        }
    }

    /**
     * Allocates the identifier this decision applies to.
     *
     * <p>The identifier is always allocated and never read from the request.
     * {@link AuthorizationRequest#TRANSACTION_ID_NOT_ACCEPTED_MESSAGE} is what refuses a supplied one
     * at the interface, so a caller cannot name the transaction another caller's decision was keyed
     * on, and cannot decide the same transaction twice by repeating a value it chose.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L444-L451} allocates an identifier by browsing the transaction
     * file backwards from high values and adding one, which is a read-modify-write two callers can
     * interleave. {@link TransactionIdentifierSource} replaces it with a database sequence, and the
     * sequence renders exactly {@value PicClause#DALYTRAN_ID_WIDTH} characters, the width
     * {@code DALYTRAN-ID PIC X(16)} at {@code app/cpy/CVTRA06Y.cpy:L5} declares and the width every
     * record of {@code app/data/ASCII/dailytran.txt} holds. Nothing pads, because nothing arrives
     * narrow.
     *
     * @return the identifier this decision applies to, at
     *         {@value PicClause#DALYTRAN_ID_WIDTH} characters
     */
    private String allocateTransactionId() {
        return transactionIdentifiers.nextIdentifier();
    }

    /**
     * Reads the category code this call authorizes under, at the width the record field holds.
     *
     * <p>{@code DALYTRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA06Y.cpy:L7} is a numeric display
     * field of four characters. A {@code MOVE} into it right-aligns the value and fills the left
     * with zeros, so the source stores {@code 0001} for a category a screen supplied as {@code 1}.
     * The eighteen codes of {@code app/data/ASCII/trancatg.txt} each hold four digits.
     *
     * <p>{@code AuthorizationRequest.CATEGORY_CODE_PATTERN} accepts one to four digits, because
     * {@code app/cbl/COTRN02C.cbl:L330} applies the COBOL numeric class test and nothing narrower.
     * Every event contract requires the stored width. Widening here is what keeps a request the
     * interface accepts from failing after the decision is already made.
     *
     * @param request the validated request body
     * @return exactly {@value PicClause#DALYTRAN_CAT_CD_WIDTH} digits
     */
    private static String canonicalCategoryCode(AuthorizationRequest request) {
        return leftPadWithZeros(request.transactionCategoryCode(),
                PicClause.DALYTRAN_CAT_CD_WIDTH);
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

    /**
     * Counts one infrastructure fault, tagged with the stage that raised it.
     *
     * <p>No decline reaches this counter. A decline is a committed outcome and expected traffic per
     * {@code app/cbl/CBTRN02C.cbl:L229-L230}, and it is counted on {@link #DECISION_COUNTER} under
     * its reject reason. This counter answers a different question: how often the service could not
     * reach a decision at all.
     *
     * @param stage the stage that raised the fault
     */
    private void countFailure(String stage) {
        Counter.builder(FAILURES_COUNTER)
                .tag(STAGE_TAG_NAME, stage)
                .register(meters)
                .increment();
    }

    /**
     * Raised when a rule resolved a replica row this service cannot vouch for the age of.
     *
     * <p>This is not a reject reason, and it must never become one.
     * {@code app/cbl/CBTRN02C.cbl:L385-L420} defines exactly four, {@link DeclineReason} holds
     * exactly those four, and the published contract at
     * {@code libs/event-contracts/src/main/resources/schemas/transaction-declined-v1.json} enumerates
     * them. A fifth value would break every consumer that reads the enumeration, and it would be
     * wrong on its own terms: the card did nothing wrong and the account did nothing wrong. This
     * service is the component that is unfit to answer.
     *
     * <p>It is therefore an infrastructure fault, which the exception handler turns into a retryable
     * response. A caller retrying after the replica catches up gets a real decision. A caller told
     * "declined" would have been told something false about the cardholder.
     */
    public static class StaleReplicaException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Names the account and the window, and never the card number.
         *
         * @param accountId the account whose replica rows were too old, eleven digits
         * @param maxAge    the window the observation had to fall within
         */
        public StaleReplicaException(String accountId, Duration maxAge) {
            super("Replica data for account " + accountId + " was not observed within " + maxAge
                    + ", so this service cannot authorize against it");
        }
    }
}
