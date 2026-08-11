package com.carddemo.authorization.domain;

import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.config.ObservabilityConfig;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.ReplicaGapRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.correlation.CorrelationScope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Every decided call writes exactly one event, and the event row commits with the decision that
 * produced it. Every event is keyed on the eleven-digit account the cross-reference resolved, which is
 * the only subject a decision of this service has: a call whose card resolves no row is refused rather
 * than decided, because the account a caller named in the request body is a value nothing corroborates.
 * Add a fifth reject reason by adding one {@link DeclineRule} bean; this class needs no edit for it.
 */
@Service
public class AuthorizationService {

    /** Records the one committed decision line per call, naming no card number and no account. */
    private static final Logger LOG = LoggerFactory.getLogger(AuthorizationService.class);

    /**
     * Every meter name, tag key and stage value below is the one
     * {@code config/ObservabilityConfig} registers.
     *
     * <p>The names are read from that class rather than written again here. A registered series reads
     * zero from start-up and a recorded series appears on first use, so two spellings of one name
     * produce two series and a dashboard reading the registered one shows a service that never
     * records anything. Deriving them cannot drift.
     */
    static final String DECISION_COUNTER = ObservabilityConfig.DECISIONS_COUNTER;

    /** Name of the counter carrying events written to the outbox. */
    static final String EVENT_COUNTER = ObservabilityConfig.EVENTS_WRITTEN_COUNTER;

    /** Name of the timer over one decision. */
    static final String DECISION_TIMER = ObservabilityConfig.DECISION_TIMER;

    /** Tag value marking the counted outcome of an approved call. */
    static final String APPROVED_OUTCOME_TAG = ObservabilityConfig.APPROVED_OUTCOME;

    /** Name of the counter carrying one infrastructure fault. No decline reaches it. */
    static final String FAILURES_COUNTER = ObservabilityConfig.FAILURES_COUNTER;

    /** The {@link #FAILURES_COUNTER} stage of a decision and event row that could not commit. */
    static final String PERSIST_STAGE = ObservabilityConfig.PERSIST_STAGE;

    /** The {@link #FAILURES_COUNTER} stage of a call refused because its replica rows were too old. */
    static final String REPLICA_STAGE = ObservabilityConfig.REPLICA_STAGE;

    /**
     * The {@link #FAILURES_COUNTER} stage of a caller refused the subject its request resolved to.
     *
     * <p>A refusal and not a fault, exactly as {@link #REPLICA_STAGE} is. What both stages have in
     * common, and what separates them from a decline, is that the call produced no decision: a decline
     * is a committed outcome and expected traffic per {@code app/cbl/CBTRN02C.cbl:L229-L230}.
     */
    static final String ENTITLEMENT_STAGE = ObservabilityConfig.ENTITLEMENT_STAGE;

    /**
     * The {@link #FAILURES_COUNTER} stage of a call whose card resolved no cross-reference row.
     *
     * <p>A refusal and not a fault, exactly as {@link #ENTITLEMENT_STAGE} is, and it is counted for
     * the same reason: a caller presenting unknown card numbers one after another is invisible if the
     * refusal is counted nowhere. It replaces the {@code unresolved_card_attempt} row this service
     * used to write, which named an account the caller had merely declared.
     */
    static final String UNRESOLVED_CARD_STAGE = ObservabilityConfig.UNRESOLVED_CARD_STAGE;

    /** Scale a scale-0 account identifier holds, from {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_IDENTIFIER_SCALE = 0;

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
     * @param accountId     the account this decision applies to, at scale zero and never negative,
     *                      always the one the cross-reference resolved
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
         *                                  disagrees with {@code declineReason}, when the outcome
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
            if (accountId == null) {
                throw new IllegalArgumentException(
                        "accountId must name the account this decision applies to, and the supplied"
                                + " value is absent");
            }
            if (accountId.scale() != ACCOUNT_IDENTIFIER_SCALE) {
                throw new IllegalArgumentException("accountId must carry scale "
                        + ACCOUNT_IDENTIFIER_SCALE + " and the supplied value carries scale "
                        + accountId.scale());
            }
            if (accountId.signum() < 0) {
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
         * @param accountId     the account this decision applies to, at scale zero, always the
         *                      one the cross-reference resolved
         * @param transactionId the identifier this decision applies to
         * @return the declined decision, holding the measurements it earned
         * @throws NullPointerException     when {@code declineReason} or {@code transactionId} is
         *                                  {@code null}
         * @throws IllegalArgumentException when {@code accountId} is absent, negative or scaled, or
         *                                  when {@code transactionId} breaks its width
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

    /** Records each decision and the identity that asked for it. */
    private final AuthorizationDecisionRepository authorizationDecisions;

    /** Counts outcomes and events, and times the decision. */
    private final MeterRegistry meters;

    /**
     * Reports whether the replica streams are caught up with their producers.
     *
     * <p>A stream that is behind means this service holds a copy its owner has already moved on
     * from, and a decision taken against it is an infrastructure fault rather than a decline:
     * {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395} read the
     * cross-reference and account datasets themselves, so the source has no such outcome.
     */
    private final ReplicaSynchronization replicaSynchronization;

    /**
     * The accounts whose replica copy is missing a change a delivery failed to apply.
     *
     * <p>The companion to {@link #replicaSynchronization} and not a duplicate of it. Lag says
     * whether records are waiting; this says whether one was delivered and lost. A recovered poison
     * record advances its offset, so lag alone reports a stream as caught up while one account's copy
     * is behind.
     */
    private final ReplicaGapRepository replicaGaps;

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
     * Bounds the lock wait of one decision and records the exposure an approval commits.
     *
     * <p>The reason both belong to one collaborator is that neither is useful without the other. A
     * reservation written without the lock is a reservation two concurrent calls can each compute from
     * the same figures, and a lock taken without a reservation serializes two calls that then approve
     * the same exposure anyway.
     */
    private final CycleExposureReservation cycleExposure;

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
     * @param authorizationDecisions store of each decision and the identity behind it
     * @param meters                 registry the three measurements register with
     * @param transactionTemplate    opens the one transaction a decision commits in, so every meter
     *                               is touched after that transaction has committed
     * @param properties             the validated service configuration
     * @param cycleExposure          bound on the lock wait of one decision, and recorder of the
     *                               exposure an approval commits
     * @param replicaSynchronization reports whether the replica streams are caught up with their
     *                               producers
     * @param replicaGaps            the accounts a delivery failed to apply a change for
     * @throws NullPointerException     when an argument or a rule of {@code rules} is {@code null}
     * @throws IllegalArgumentException when a rule declares a segment this class cannot place
     */
    public AuthorizationService(List<DeclineRule> rules,
            CardCrossReferenceRepository cardCrossReferences,
            TransactionIdentifierSource transactionIdentifiers, OutboxWriter outboxWriter,
            AuthorizationDecisionRepository authorizationDecisions, MeterRegistry meters,
            TransactionTemplate transactionTemplate, AuthorizationProperties properties,
            CycleExposureReservation cycleExposure, ReplicaSynchronization replicaSynchronization,
            ReplicaGapRepository replicaGaps) {
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
        this.authorizationDecisions = Objects.requireNonNull(authorizationDecisions,
                "authorizationDecisions must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate,
                "transactionTemplate must be present");
        this.cycleExposure =
                Objects.requireNonNull(cycleExposure, "cycleExposure must be present");

        Objects.requireNonNull(
                Objects.requireNonNull(properties, "properties must be present").replica(),
                "properties.replica must be present");
        this.replicaSynchronization = Objects.requireNonNull(replicaSynchronization,
                "replicaSynchronization must be present");
        this.replicaGaps = Objects.requireNonNull(replicaGaps, "replicaGaps must be present");
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
     * <p>A caller refused the subject its request resolved to increments the entitlement stage, and a
     * card that resolved no cross-reference row increments the unresolved-card stage. Both refusals are
     * counted here for the same reason: nothing inside the transactional unit touches a meter.
     *
     * @param request the validated request body
     * @param caller  the request identity and what it is entitled to reach, built by
     *                {@link AuthenticatedActor#callerOf(java.security.Principal, java.util.Collection)}
     * @return the decision, naming the reject reason that stands on a decline
     * @throws NullPointerException when {@code request} or {@code caller} is {@code null}, or when the
     *                             request carries no amount the tolerant numeric grammar reads
     * @throws CallerNotEntitledException when the caller owns neither the resolved account nor the
     *                             resolved card, in which case no decision was recorded and no event
     *                             was written
     * @throws CardNumberNotFoundInCrossReferenceException when the card the request named resolves no
     *                             cross-reference row, so no account can be vouched for and, again,
     *                             no identifier was drawn, no decision recorded and no event written
     */
    public Outcome authorize(AuthorizationRequest request, RequestCaller caller) {
        Objects.requireNonNull(request, "request must be present");
        Objects.requireNonNull(caller, "caller must be present");

        Timer.Sample sample = Timer.start(meters);
        try {
            Decision decision = transactionTemplate.execute(status -> decide(request, caller));
            Objects.requireNonNull(decision, "the transaction must answer with one decision");

            decision.record(this);
            return decision.outcome();
        } catch (CardNumberNotFoundInCrossReferenceException unresolvedCard) {
            countFailure(UNRESOLVED_CARD_STAGE);
            throw unresolvedCard;
        } catch (CallerNotEntitledException notEntitled) {
            countFailure(ENTITLEMENT_STAGE);
            throw notEntitled;
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
    private record Decision(Outcome outcome, String outcomeTag, String eventType,
            String transactionId, UUID eventId) {

        /**
         * Applies the held measurements and states the committed decision. Called only after the
         * transaction has committed.
         *
         * <p>The line is the join between one request and the event it produced. It carries the
         * transaction identifier, the event identifier and the event type as structured fields, and
         * the correlation identifier of the call is already on the thread from
         * {@code config/CorrelationContextFilter}, so a reader follows one authorization from this
         * record into the three services that consume its event without matching values by hand.
         *
         * <p>It is written after the commit, so it never claims a decision the datastore rolled
         * back. It names no card number, no account identifier and no amount: the outcome tag is one
         * literal from a closed set, which for a decline is the four-digit reject code
         * {@code app/cbl/CBTRN02C.cbl:L385-L420} assigns.
         *
         * @param service the service whose meters these measurements belong to
         */
        void record(AuthorizationService service) {
            service.countOutcome(outcomeTag);
            if (eventType != null) {
                service.countEvent(eventType);
            }
            try (CorrelationScope scope = CorrelationScope.open()
                    .withEvent(eventId, eventType)
                    .withTransaction(transactionId)) {
                LOG.info("An authorization decision committed under outcome {}, and it published {}.",
                        outcomeTag, eventType == null ? "no event" : "one event");
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
     * <p>One check runs before the chain. The request must have named its subject, by account
     * identifier or by card number, and {@link #resolveCard(AuthorizationRequest)} settles
     * which card the decision runs against. No clock bound applies to the capture moment: the only
     * test the source applies to it is the date validation of
     * {@code app/cbl/COTRN02C.cbl:L389-L414} and the lexical comparison reject reason {@code 0103}
     * performs at {@code app/cbl/CBTRN02C.cbl:L414-L420}.
     *
     * <p>A card number the caller supplied is the card the decision runs on, because
     * {@code app/cbl/CBTRN02C.cbl:L383} keys the cross-reference read on the card of the transaction
     * being authorized. An account identifier resolves a card only where the request carried none,
     * which is the {@code MOVE XREF-CARD-NUM} of {@code app/cbl/COTRN02C.cbl:L196-L209}.
     *
     * <p>The subject of the decision is the account the cross-reference resolved, and nothing else may
     * become one. An account named on the request is a value the caller chose, so a decision keyed on
     * it would record an outcome against an account no stored row ties to the card presented. The feed
     * record the batch path validates carries no account either: {@code app/cpy/CVTRA06Y.cpy}
     * declares twelve fields and none of them is an account identifier, which is why
     * {@code app/cbl/CBTRN02C.cbl:L394} has to move {@code XREF-ACCT-ID} out of the cross-reference
     * row before {@code app/cbl/CBTRN02C.cbl:L446-L465} can write the reject record against it.
     *
     * <p>Four refusals precede any of that, and they are the limbs
     * {@code app/cbl/COTRN02C.cbl:L195-L230} ends on.
     * {@link #resolveCard(AuthorizationRequest)} raises
     * {@link AccountNotFoundInCrossReferenceException} where an account resolved no card, this method
     * raises {@value AuthorizationRequest#IDENTIFIER_REQUIRED_MESSAGE} where no usable identifier
     * arrived at all, {@link CallerEntitlement} raises {@link CallerNotEntitledException} where the
     * caller owns neither subject, and this method raises
     * {@link CardNumberNotFoundInCrossReferenceException} where a card resolved no cross-reference
     * row, which is the {@code NOTFND} limb of {@code READ-CCXREF-FILE} at
     * {@code app/cbl/COTRN02C.cbl:L625-L626}. None of the four allocates an identifier, writes an
     * event or records a decision.
     *
     * <p>Reject reason {@code 0100} is therefore a batch outcome and not a synchronous one.
     * {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns it inside the {@code INVALID KEY} limb of a read
     * whose feed record the job already owns, and the reject file it lands in is keyed by nothing. A
     * synchronous caller gets the answer its own ancestor gives: the card is unknown, and no decision
     * exists to attribute. {@code rules/CardCrossReferenceRule} still assigns the reason, because it
     * is what leaves the account unresolved and stops the chain, and the equivalence suite still holds
     * that rule to the source code and its verbatim text.
     *
     * <p>Two more things happen here that the source has no counterpart for, and both are additions
     * this service needs because it answers concurrent calls against an account another service owns.
     * The lock wait of this transaction is bounded before any rule runs, so a decision contending for
     * an account cannot hold its request open without limit. And after the chain has resolved the card
     * and the account, the caller is refused unless it owns one of them.
     *
     * <p>The entitlement check sits between the chain and the unresolved-card refusal, and both
     * neighbours matter. It runs after the chain because the account it compares against is the one
     * the cross-reference resolved. It runs before the refusal because a caller owning nothing must
     * receive the same answer whichever way the card read went, and it runs before allocation because
     * a refused call must consume no sequence value, record no decision and produce no event.
     *
     * @param request the validated request body
     * @param caller  the request identity, whose name the decision row records and whose entitlements
     *                decide whether the resolved subject may be authorized against
     * @return the decision and the measurements it earned
     */
    private Decision decide(AuthorizationRequest request, RequestCaller caller) {
        cycleExposure.boundLockWait();

        ResolvedCard resolved = resolveCard(request);
        if (resolved == null) {
            throw new IllegalArgumentException(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE);
        }
        String cardNumber = resolved.cardNumber();
        BigDecimal amount = Objects.requireNonNull(request.amountValue(),
                "amount must read as a number under the tolerant currency grammar");

        DeclineRule.Context context = new DeclineRule.Context(cardNumber, amount,
                request.recordOriginTimestamp());
        if (resolved.crossReference() != null) {
            context.setCardCrossReference(resolved.crossReference());
        }
        DeclineReason standing = runChain(context);
        String resolvedAccountId = context.getResolvedAccountId();

        // Entitlement is decided on what the caller owns, never on what it declared, and it is
        // decided before the refusal below. A caller owning nothing is therefore refused whether or
        // not the card exists, so card existence stays unobservable to it.
        // domain/CallerEntitlement records why that ordering is the security property.
        CallerEntitlement.require(caller, resolvedAccountId, cardNumber);

        // The subject the decision applies to is the account the cross-reference row named, exactly
        // as MOVE XREF-ACCT-ID TO FD-ACCT-ID does at app/cbl/CBTRN02C.cbl:L394. Where that read
        // resolved none, this service can vouch for no subject: the account on the request is a value
        // the caller chose, and DALYTRAN-* at app/cpy/CVTRA06Y.cpy carries no account at all, so the
        // reject record of app/cbl/CBTRN02C.cbl:L446-L465 has no authoritative account to be written
        // against either. The call is refused, which is what the synchronous ancestor does at the
        // NOTFND limb of READ-CCXREF-FILE, app/cbl/COTRN02C.cbl:L625-L626: it reports the card as
        // unknown, captures nothing and decides nothing.
        if (resolvedAccountId == null) {
            throw new CardNumberNotFoundInCrossReferenceException();
        }

        String actor = caller.actor();
        String transactionId = allocateTransactionId();

        requireUsableReplica(context, resolvedAccountId);

        if (standing == null) {
            return approve(request, context, transactionId, resolvedAccountId, amount, actor);
        }
        return decline(request, context, transactionId, resolvedAccountId, amount, standing, actor,
                request.recordProcessingTimestamp());
    }

    /**
     * Refuses to authorize against a replica this service knows is behind its producer.
     *
     * <p>ADDITIVE, and it has no COBOL ancestor because the source has nothing that can fall behind.
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
     * <p><strong>What is measured, and what deliberately is not.</strong> This check reads two
     * properties of the <em>streams</em> and no property of the rows. An earlier form of it bounded
     * how old a row's last observation could be, and that bound was wrong about the ordinary case
     * rather than the failing one: the account and card services publish on a state change and on
     * nothing else, so a card nobody edits is a perfectly correct copy whose last observation recedes
     * for ever. Every such card crossed the bound within a day and every call for it was then refused,
     * while readiness still reported the service up. The two measurements that replace it separate the
     * correct copy from the damaged one:
     *
     * <ul>
     *   <li>{@link ReplicaSynchronization} reports whether each replica listener exists, runs, holds
     *       partitions, and reports lag within its ceiling. A caught-up consumer of a quiet topic
     *       reports zero lag, so an unedited card stays authorizable for as long as it stays
     *       unedited.</li>
     *   <li>{@link ReplicaGapRepository} reports whether a delivery for this account failed to apply.
     *       A recovered poison record advances its offset once its diagnostic is away, so lag returns
     *       to zero while that one account's copy is missing a change; the gap row is what makes that
     *       account, and only that account, unauthorizable.</li>
     * </ul>
     *
     * <p>The check applies to the rows the decision was computed from, and only to those. Where a rule
     * declined because a row is absent, the decline came from the absence and not from any value, so
     * there is nothing this service could be holding an obsolete copy of: an absent cross-reference
     * row is reject reason {@link DeclineReason#INVALID_CARD_NUMBER} at
     * {@code app/cbl/CBTRN02C.cbl:L385-L387} and an absent account row is
     * {@link DeclineReason#ACCOUNT_NOT_FOUND} at {@code app/cbl/CBTRN02C.cbl:L397-L399}. Both are
     * reject reasons the source defines, and refusing those calls instead would replace a defined
     * outcome with a service fault and break equivalence. The same holds for a chain that stopped
     * early: a rule that never ran read nothing.
     *
     * <p>Both rows present means the decision did read replica values. That covers every approval,
     * every over-limit decline computed at {@code app/cbl/CBTRN02C.cbl:L403-L413} and every expiry
     * decline at {@code app/cbl/CBTRN02C.cbl:L414-L420}, which is the whole set of outcomes an
     * obsolete copy could get wrong.
     *
     * @param context           the resolved values for this call
     * @param resolvedAccountId the account the cross-reference named
     * @throws StaleReplicaException when the decision read both replica rows and either a replica
     *                               stream is behind or this account is missing a change
     */
    private void requireUsableReplica(DeclineRule.Context context, String resolvedAccountId) {
        boolean decisionReadReplicaValues = context.getCardCrossReference() != null
                && context.getAccountCreditSnapshot() != null;

        if (!decisionReadReplicaValues) {
            return;
        }

        ReplicaSynchronization.Verdict verdict = replicaSynchronization.verdict();
        if (!verdict.usable()) {
            throw new StaleReplicaException(verdict.reason());
        }
        if (replicaGaps.existsForAggregate(resolvedAccountId)) {
            throw new StaleReplicaException(StaleReplicaException.UNAPPLIED_CHANGE);
        }
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
     * Reserves the approved exposure, writes the approval event and answers with the approved outcome.
     *
     * <p>The reservation is what makes this approval visible to the next decision for the same
     * account. {@code app/cbl/CBTRN02C.cbl} needs no equivalent because paragraph
     * {@code 2700-UPDATE-ACCOUNT} at {@code app/cbl/CBTRN02C.cbl:L545-L560} rewrites the account record
     * inside the same loop that validates the next record. Here the account service owns that rewrite
     * and reports it back four asynchronous hops later, so without the reservation two calls arriving
     * inside that window would both approve against the same exposure.
     *
     * <p>All three writes join the transaction {@link #authorize} opened, so an approval that reached a
     * consumer without reserving its exposure cannot exist, and a reservation for an approval that
     * rolled back cannot either.
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
                PanMasker.cardToken(context.getCardNumber()), request.recordOriginTimestamp());

        cycleExposure.reserve(context.getAccountCreditSnapshot(), amount);
        outboxWriter.writeAuthorized(event);
        authorizationDecisions.save(AuthorizationDecisionEntity.approved(transactionId, actor,
                resolvedAccountId, event.maskedCardNumber(), event.cardToken(), amount,
                clock.instant(), event.eventId(), request.recordProcessingTimestamp()));
        return new Decision(Outcome.approved(accountIdentifierOf(resolvedAccountId), transactionId),
                APPROVED_OUTCOME_TAG, TransactionAuthorized.EVENT_TYPE, transactionId,
                event.eventId());
    }

    /**
     * Writes the decline event and answers with the declined outcome.
     *
     * <p>The event is version {@value TransactionDeclined#TRANSACTION_DETAIL_SCHEMA_VERSION}, which
     * {@code schemas/transaction-declined-v3.json} governs. That contract carries the nine
     * descriptive values of the attempted transaction beside the reject reason, and it carries them
     * because {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465} writes
     * {@code REJECT-TRAN-DATA PIC X(350)} — the whole daily record — ahead of the reason code and its
     * text. A consumer that owns the reject row cannot render those 350 bytes from a reason code
     * alone, and inventing the values it was not given is the one outcome equivalence forbids.
     *
     * <p>Every one of the nine comes from the request this call refused, never from a lookup. The
     * refused request is the only place they exist: the source reads them from the daily record it is
     * rejecting, and no dataset holds a transaction that never posted.
     *
     * @param request           the validated request body this call refused
     * @param context           values this call resolved
     * @param transactionId     the identifier this decision applies to
     * @param resolvedAccountId the account the cross-reference named, eleven digits
     * @param amount            the amount at two digits after the decimal point
     * @param standing          the reject reason that stands
     * @param actor             the request identity the decision row records
     * @param declaredProcessingTimestamp the processing moment the caller declared, at the record
     *                                    width, which {@code app/cbl/COTRN02C.cbl:L470} moves into
     *                                    {@code TRAN-PROC-TS}
     * @return the declined decision, holding the measurements it earned
     */
    private Decision decline(AuthorizationRequest request, DeclineRule.Context context,
            String transactionId, String resolvedAccountId, BigDecimal amount,
            DeclineReason standing, String actor, String declaredProcessingTimestamp) {
        TransactionDeclined event = TransactionDeclined.withTransactionDetail(resolvedAccountId,
                transactionId, standing, request.transactionTypeCode(),
                canonicalCategoryCode(request), request.source(), request.description(), amount,
                request.merchantId(), request.merchantName(), request.merchantCity(),
                request.merchantZip(), PanMasker.maskCardNumber(context.getCardNumber()),
                request.recordOriginTimestamp());

        outboxWriter.writeDeclined(event);
        authorizationDecisions.save(AuthorizationDecisionEntity.declined(transactionId, actor,
                resolvedAccountId, event.maskedCardNumber(),
                PanMasker.cardToken(context.getCardNumber()), amount, standing.code(),
                standing.description(), clock.instant(), event.eventId(),
                declaredProcessingTimestamp));
        return new Decision(
                Outcome.declined(standing, accountIdentifierOf(resolvedAccountId), transactionId),
                standing.code(), TransactionDeclined.EVENT_TYPE, transactionId, event.eventId());
    }

    /**
     * Reads the card number this call authorizes against, at the width the cross-reference key
     * holds.
     *
     * <p>{@code VALIDATE-INPUT-KEY-FIELDS} at {@code app/cbl/COTRN02C.cbl:L195-L230} accepts either
     * identifier and both of its branches are reproduced here. The card branch of the source at
     * {@code :L210-L223} reads the cross-reference by the card number the caller supplied, and the
     * account branch at {@code :L196-L209} reads it by the account identifier and moves
     * {@code XREF-CARD-NUM} into the card field at {@code :L209}. The alternate index the account
     * read uses is defined at {@code app/jcl/XREFFILE.jcl:L72-L77} over the account identifier at
     * offset 25, and {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc}
     * is the target form of it: a keyed read of one row, taken in ascending card-number order so one
     * account always resolves the same card.
     *
     * <p>What this method decides is which card the rules run on, and the card the caller supplied
     * wins wherever it supplied one. {@code app/cbl/CBTRN02C.cbl:L383} keys the cross-reference read
     * on {@code DALYTRAN-CARD-NUM}, the card of the transaction being authorized, so a decision run
     * on some other card of the same account would authorize a card nobody presented. The account
     * branch resolves a card only where the request carried none, which is the case the source's own
     * {@code MOVE XREF-CARD-NUM} at {@code :L209} covers.
     *
     * <p>An account identifier the caller declared selects the card on the account branch and travels
     * no further. It is not the subject of any decision:
     * {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns reject reason {@code 0100} inside the
     * {@code INVALID KEY} limb of a read whose feed record carries no account of its own, and
     * {@code app/cbl/CBTRN02C.cbl:L394} has to take {@code XREF-ACCT-ID} out of the resolved row
     * before {@code app/cbl/CBTRN02C.cbl:L446-L465} can write the reject record against an account.
     * A card that resolves no row therefore leaves this service with nothing it can vouch for, and
     * {@code decide} refuses the call.
     *
     * <p>An account that holds no cross-reference row resolves no card, and this method raises
     * {@link AccountNotFoundInCrossReferenceException} carrying
     * {@value AuthorizationRequest#ACCOUNT_ID_NOT_FOUND_MESSAGE} where the read missed rather than
     * returning {@code null}. That is the source shape: the {@code NOTFND} limb of that same read
     * answers with the identical text at {@code app/cbl/COTRN02C.cbl:L591-L592} and re-sends the
     * screen, so nothing is captured.
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
     * <p>The account branch reads a whole row to take one column out of it, so the row travels with
     * the card number rather than being discarded. {@link CardCrossReferenceRule} reads
     * {@code card_xref} keyed on the card number, and on this branch that key came out of this very
     * row: {@code card_number} is the table's primary key, so the read would return the row already
     * in hand. Carrying it means the account branch reads the table once instead of twice, and it is
     * the same row either way rather than a cached one — nothing is held between requests.
     *
     * @param request the validated request body
     * @return the resolved card and the row that resolved it where the account branch read one, or
     *         {@code null} when the request named no usable identifier
     * @throws AccountNotFoundInCrossReferenceException when the account the request named holds no
     *                                                 cross-reference row
     */
    private ResolvedCard resolveCard(AuthorizationRequest request) {
        String declaredAccountId = request.canonicalAccountId();

        // The canonical value, not isCardNumberSupplied(). A value that is present but narrower than
        // the key is supplied and has no canonical form, and it must be refused rather than carried:
        // returning a ResolvedCard holding a null card number would pass the caller's null check and
        // fail later, where the refusal no longer names the reason.
        String cardNumber = request.canonicalCardNumber();
        if (cardNumber != null) {
            return new ResolvedCard(cardNumber, null);
        }

        if (declaredAccountId != null) {
            CardCrossReferenceEntity row =
                    cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(declaredAccountId)
                            .orElseThrow(AccountNotFoundInCrossReferenceException::new);
            return new ResolvedCard(row.getCardNumber(), row);
        }
        return null;
    }

    /**
     * The card one call decides against, and the cross-reference row that named it where one did.
     *
     * <p>No account the caller declared travels here. An account on the request selects the card on
     * the account branch and takes no further part: the subject of a decision is the account the
     * cross-reference resolved, and a call that resolves none is refused rather than decided.
     *
     * @param cardNumber        full sixteen-character Primary Account Number (PAN) the rules run on
     * @param crossReference    the row the account branch read, or {@code null} on the card branch,
     *                          where the caller named the card and no row has been read yet
     */
    private record ResolvedCard(String cardNumber, CardCrossReferenceEntity crossReference) {
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
        ObservabilityConfig.decisionCounter(meters, outcome).increment();
    }

    /**
     * Counts one event written to the outbox, tagged with its type.
     *
     * @param eventType the event type tag
     */
    private void countEvent(String eventType) {
        ObservabilityConfig.eventsWrittenCounter(meters, eventType).increment();
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
        ObservabilityConfig.failureCounter(meters, stage).increment();
    }

    /**
     * Raised when a rule resolved a replica row this service knows may be missing a change.
     *
     * <p>Two conditions raise it, and both are statements about the stream rather than about the age
     * of a row: a replica listener that is missing, stopped, unassigned or reporting lag above its
     * ceiling, or a delivery for this account that failed to apply and left a gap. An earlier form
     * fired on the elapsed time since a row was last written, which refused every unedited card once
     * the window lapsed and never detected a failed delivery at all, because a failed application
     * leaves {@code observed_at} exactly as recent as a successful one.
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
     * response. A caller retrying once the replica has caught up gets a real decision. A caller told
     * "declined" would have been told something false about the cardholder.
     *
     * <p>The message names the condition by a fixed phrase and carries no account identifier, no card
     * number and no value read from any record, so a caller or a log that repeats it records no
     * cardholder value. The earlier form named the account, which put an identifier into every
     * message this exception produced.
     */
    public static class StaleReplicaException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The condition a gap row names: a delivery for this account failed to apply. */
        public static final String UNAPPLIED_CHANGE = "replica-change-unapplied";

        /** The fixed phrase naming why the replica could not be read. */
        private final String reason;

        /**
         * Names the condition by its fixed phrase, and nothing else.
         *
         * @param reason the phrase naming what is wrong, from
         *               {@link ReplicaSynchronization.Verdict#reason()} or
         *               {@link #UNAPPLIED_CHANGE}
         * @throws NullPointerException when the phrase is absent
         */
        public StaleReplicaException(String reason) {
            super("the card_xref and account_credit_snapshot rows this decision reads cannot be"
                    + " authorized against (" + Objects.requireNonNull(reason, "reason") + "), so"
                    + " this call is refused rather than decided against a replica that may be"
                    + " missing a change its owner has already published");
            this.reason = reason;
        }

        /**
         * Returns the fixed phrase naming why the replica could not be read.
         *
         * @return the phrase, carrying no cardholder value
         */
        public String getReason() {
            return reason;
        }
    }

    /**
     * Raised when the account a request named holds no cross-reference row.
     *
     * <p>{@code READ-CXACAIX-FILE} answers its {@code NOTFND} limb with
     * {@value AuthorizationRequest#ACCOUNT_ID_NOT_FOUND_MESSAGE} at
     * {@code app/cbl/COTRN02C.cbl:L591-L592} and re-sends the screen, so the source tells the caller
     * which of its two values could not be resolved. This type carries that text, and it carries no
     * value read from the request, so {@code api/GlobalExceptionHandler} can publish the message as
     * it stands. Every other pre-decision refusal answers one fixed text, and none of them carries a
     * value read from the request either.
     */
    public static class AccountNotFoundInCrossReferenceException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /** Carries the verbatim source text and no value read from the request. */
        public AccountNotFoundInCrossReferenceException() {
            super(AuthorizationRequest.ACCOUNT_ID_NOT_FOUND_MESSAGE);
        }
    }

    /**
     * Raised when the card a request named holds no cross-reference row, so nothing this service can
     * vouch for names the subject a decision would apply to.
     *
     * <p>{@code READ-CCXREF-FILE} answers its {@code NOTFND} limb with
     * {@value AuthorizationRequest#CARD_NUMBER_NOT_FOUND_MESSAGE} at
     * {@code app/cbl/COTRN02C.cbl:L625-L626} and re-sends the screen, so the source reports the card
     * as unknown and captures nothing. This type carries that text, and it carries no value read from
     * the request, so {@code api/GlobalExceptionHandler} can publish the message as it stands.
     *
     * <p>Every caller reaches this refusal, whatever the request body declared. Reject reason
     * {@code 0100} is a decision about an account, and the account it applies to comes from the
     * resolved cross-reference row at {@code app/cbl/CBTRN02C.cbl:L394}: the feed record of
     * {@code app/cpy/CVTRA06Y.cpy} carries no account of its own, so a read that missed leaves the
     * batch with a reject file to write and no account to attribute the reject to. An account named in
     * a request body is a value the caller chose rather than a subject, which is why an earlier
     * revision that decided against it was withdrawn by a security review.
     * {@code card-platform/docs/decision-log.md} carries what the review found.
     */
    public static class CardNumberNotFoundInCrossReferenceException
            extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /** Carries the verbatim source text and no value read from the request. */
        public CardNumberNotFoundInCrossReferenceException() {
            super(AuthorizationRequest.CARD_NUMBER_NOT_FOUND_MESSAGE);
        }
    }
}
