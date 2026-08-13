package com.carddemo.authorization.api;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.authorization.api.GlobalExceptionHandler.ApiErrorResponse;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.domain.AuthenticatedActor;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.authorization.domain.ReplicaSynchronization;
import com.carddemo.authorization.domain.CallerNotEntitledException;
import com.carddemo.authorization.domain.CycleExposureReservation;
import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.domain.RequestCaller;
import com.carddemo.authorization.domain.TransactionIdentifierSource;
import com.carddemo.authorization.domain.rules.AccountExistsRule;
import com.carddemo.authorization.domain.rules.AccountExpirationRule;
import com.carddemo.authorization.domain.rules.CardCrossReferenceRule;
import com.carddemo.authorization.domain.rules.CreditLimitRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.outbox.OutboxWriter;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.authorization.repository.ReplicaGapRepository;
import com.carddemo.cobol.CobolDateValidator;
import com.carddemo.cobol.NumvalParser;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import io.micrometer.core.instrument.Metrics;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Contract tests for {@code POST /authorizations} and the three types the endpoint reads and writes.
 *
 * <p>The endpoint replaces one Customer Information Control System (CICS) transaction. The online
 * capture program {@code app/cbl/COTRN02C.cbl} sends a mapset, receives it back and re-sends it on
 * each failure. This Representational State Transfer (REST) endpoint reads one body and answers with
 * one body.
 *
 * <p>An approval answers {@code 200} and a decline answers {@code 422}.
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} tests the reject count and moves 4 into the return code of a
 * run that rejected records, so a decline is expected traffic. A request whose fields fail validation
 * also answers {@code 422}, carrying the texts {@code app/cbl/COTRN02C.cbl} moves into
 * {@code WS-MESSAGE}. Those two bodies differ in shape.
 *
 * <p>Reject codes come from {@code app/cbl/CBTRN02C.cbl:L385}, {@code :L397}, {@code :L410} and
 * {@code :L417}, and this class reads each text from {@link DeclineReason}. The trailer at
 * {@code app/cbl/CBTRN02C.cbl:L181-L182} fixes the four-digit code and its seventy-six character
 * text.
 *
 * <p>Three measured pitfalls have assertions of their own. The two twenty-six character timestamp
 * formats reject one another. A card number is a text key and an account identifier is a numeric one.
 * A fixture value is sliced by copybook offset.
 *
 * <p>No database, no broker and no container takes part, so a clean machine holding a Java
 * Development Kit runs the whole class.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
final class AuthorizationControllerTest {

    /**
     * Card number sliced at positions 263 through 278 of record 1 of
     * {@code app/data/ASCII/dailytran.txt}, filling {@code DALYTRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy:L15}.
     */
    private static final String WORKED_EXAMPLE_CARD = "4859452612877065";

    /** Customer identifier at the {@code XREF-CUST-ID PIC 9(09)} offset of {@code app/cpy/CVACT03Y.cpy:L6}. */
    private static final String WORKED_EXAMPLE_CUSTOMER = "000000007";

    /** Account identifier at the {@code XREF-ACCT-ID PIC 9(11)} offset of {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final String WORKED_EXAMPLE_ACCOUNT = "00000000007";

    /**
     * Amount of record 1, at positions 133 through 143. The stored field reads {@code 0000005047G},
     * and the trailing overpunch carries a positive 7, so {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10} holds this value.
     */
    private static final String WORKED_EXAMPLE_AMOUNT = "504.77";

    /**
     * Credit limit of the worked example's account, from
     * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L8}.
     */
    private static final BigDecimal WORKED_EXAMPLE_CREDIT_LIMIT = new BigDecimal("2065.00");

    /**
     * Expiry date of the worked example's account, from {@code ACCT-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L11}. The source spells the field name that way.
     */
    private static final String WORKED_EXAMPLE_EXPIRY = "2024-12-13";

    /**
     * Cycle accumulator value held by both {@code ACCT-CURR-CYC-CREDIT} at
     * {@code app/cpy/CVACT01Y.cpy:L13} and {@code ACCT-CURR-CYC-DEBIT} at
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    private static final BigDecimal ZERO_CYCLE = new BigDecimal("0.00");

    /**
     * Card of row 1 of {@code app/data/ASCII/cardxref.txt}, which opens with a zero digit. The stored
     * key is text, so that digit counts.
     */
    private static final String LEADING_ZERO_CARD = "0500024453765740";

    /** Account row 1 of {@code app/data/ASCII/cardxref.txt} resolves {@link #LEADING_ZERO_CARD} to. */
    private static final String LEADING_ZERO_ACCOUNT = "00000000050";

    /** Customer row 1 of {@code app/data/ASCII/cardxref.txt} carries. */
    private static final String LEADING_ZERO_CUSTOMER = "000000050";

    /** {@link #LEADING_ZERO_CARD} with its opening zero dropped, fifteen digits wide. */
    private static final String SHORTENED_CARD = "500024453765740";

    /**
     * Sixteen digits that fail the Luhn checksum, built from {@link #WORKED_EXAMPLE_CARD} with its
     * last digit raised by one. All fifty cards of {@code app/data/ASCII/cardxref.txt} pass that
     * checksum, so this value is constructed.
     */
    private static final String CHECKSUM_FAILING_CARD = "4859452612877066";

    /** Sixteen digits no row of {@code app/data/ASCII/cardxref.txt} carries. */
    private static final String UNKNOWN_CARD = "9999999999999999";

    /**
     * Account 30 of {@code app/data/ASCII/acctdata.txt}, whose credit limit is the tightest of the
     * fifty rows.
     */
    private static final String TIGHT_LIMIT_ACCOUNT = "00000000030";

    /** Card {@code app/data/ASCII/cardxref.txt} resolves to {@link #TIGHT_LIMIT_ACCOUNT}. */
    private static final String TIGHT_LIMIT_CARD = "6509230362553816";

    /** Customer {@code app/data/ASCII/cardxref.txt} pairs with {@link #TIGHT_LIMIT_CARD}. */
    private static final String TIGHT_LIMIT_CUSTOMER = "000000030";

    /** Credit limit of account 30 in {@code app/data/ASCII/acctdata.txt}. */
    private static final BigDecimal TIGHT_CREDIT_LIMIT = new BigDecimal("120.00");

    /** Expiry date of account 30 in {@code app/data/ASCII/acctdata.txt}. */
    private static final String TIGHT_LIMIT_EXPIRY = "2024-06-27";

    /**
     * One of the four amounts the feed presents against account 30 that exceed
     * {@link #TIGHT_CREDIT_LIMIT}.
     */
    private static final String OVER_LIMIT_AMOUNT = "805.77";

    /**
     * Capture moment of record 1, at positions 279 through 304, from
     * {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}. Position 11 holds a
     * space in all three hundred records.
     */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * Processing moment in the shape {@code DALYTRAN-PROC-TS PIC X(26)} at
     * {@code app/cpy/CVTRA06Y.cpy:L17} holds. Position 11 holds a third dash, declared as
     * {@code DB2-STREEP-3} inside the redefinition at {@code app/cbl/CBTRN02C.cbl:L160-L174}, and the
     * last four characters are the zeros {@code app/cbl/CBTRN02C.cbl:L701} writes. Positions 305
     * through 330 of the feed are blank in all three hundred records.
     */
    private static final String PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.000000";

    /** First ten characters of {@link #ORIGIN_TIMESTAMP}, which reject code {@code 0103} compares. */
    private static final String CAPTURE_DATE = "2022-06-10";

    /** An expiry date sorting below {@link #CAPTURE_DATE}, so the expiry test declines. */
    private static final String EXPIRY_BEFORE_CAPTURE = "2020-01-31";

    /** The identifier the sequence allocates, at the width of {@code DALYTRAN-ID PIC X(16)}. */
    private static final String ALLOCATED_ID = "0000001000000001";

    /** The identity a call carries into the audit column. */
    private static final String ACTOR = "user0001";

    /**
     * The caller every request through the real decision path presents.
     *
     * <p>It reaches every subject, and it is supplied as the default principal of that path because
     * those requests measure statuses and bodies rather than entitlement: a caller owning nothing is
     * refused 403 before the media type, the body ceiling or a reject code is ever reached.
     * {@code CallerEntitlementTest} measures entitlement, and the nested class below measures the 403
     * this endpoint answers with.
     */
    private static final Authentication ENTITLED_CALLER = new UsernamePasswordAuthenticationToken(
            ACTOR, "n/a", List.of(new SimpleGrantedAuthority(
                    RequestCaller.ADMINISTRATOR_AUTHORITY)));

    /** Transaction type code of record 1, at positions 17 and 18. */
    private static final String TYPE_CODE = "01";

    /** Transaction category code of record 1, at positions 19 through 22. */
    private static final String CATEGORY_CODE = "0001";

    /** Merchant identifier of record 1, at positions 144 through 152. */
    private static final String MERCHANT_ID = "800000000";

    /** Name of the {@code cardNumber} member of the request body. */
    private static final String CARD_NUMBER_FIELD = "cardNumber";

    /** Name of the {@code accountId} member of the request body. */
    private static final String ACCOUNT_ID_FIELD = "accountId";

    /** Name of the {@code amount} member of the request body. */
    private static final String AMOUNT_FIELD = "amount";

    /** Name of the {@code originTimestamp} member of the request body. */
    private static final String ORIGIN_FIELD = "originTimestamp";

    /** Name of the {@code processingTimestamp} member of the request body. */
    private static final String PROCESSING_FIELD = "processingTimestamp";

    /** Path of the one route this service publishes. */
    private static final String ROUTE = "/authorizations";

    /**
     * Builds the members of a complete request body, in the order
     * {@code app/cpy/CVTRA06Y.cpy:L5-L18} declares them.
     *
     * <p>{@code transactionId} is absent. {@code app/cbl/COTRN02C.cbl:L444-L451} browses the file
     * backwards from high values and adds one, so no screen field carries the identifier, and
     * {@link AuthorizationRequest#TRANSACTION_ID_NOT_ACCEPTED_MESSAGE} refuses a supplied one.
     *
     * @return a mutable map of member name to value, carrying a card number and no account identifier
     */
    private static Map<String, String> completeBody() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("transactionTypeCode", TYPE_CODE);
        body.put("transactionCategoryCode", CATEGORY_CODE);
        body.put("source", "POS TERM");
        body.put("description", "Purchase at Abshire-Lowe");
        body.put(AMOUNT_FIELD, WORKED_EXAMPLE_AMOUNT);
        body.put("merchantId", MERCHANT_ID);
        body.put("merchantName", "Abshire-Lowe");
        body.put("merchantCity", "North Enoshaven");
        body.put("merchantZip", "72112");
        body.put(CARD_NUMBER_FIELD, WORKED_EXAMPLE_CARD);
        body.put(ORIGIN_FIELD, ORIGIN_TIMESTAMP);
        body.put(PROCESSING_FIELD, PROCESSING_TIMESTAMP);
        return body;
    }

    /**
     * @param field name of the member to change
     * @param value the value to set, or {@code null} to send a JavaScript Object Notation null
     * @return the rendered body
     */
    private static String bodyWith(String field, String value) {
        Map<String, String> body = completeBody();
        body.put(field, value);
        return json(body);
    }

    /**
     * @param field name of the member to drop
     * @return the rendered body
     */
    private static String bodyWithout(String field) {
        Map<String, String> body = completeBody();
        body.remove(field);
        return json(body);
    }

    /**
     * Builds a body naming its subject by account identifier and carrying no card number, which is
     * the account branch of {@code app/cbl/COTRN02C.cbl:L196-L209}.
     *
     * @param accountId the eleven-digit identifier to send
     * @return the rendered body
     */
    private static String bodyNamingAccount(String accountId) {
        Map<String, String> body = completeBody();
        body.remove(CARD_NUMBER_FIELD);
        body.put(ACCOUNT_ID_FIELD, accountId);
        return json(body);
    }

    /**
     * Renders members as one JavaScript Object Notation object.
     *
     * <p>A {@code null} value renders as a literal null. No value below holds a quotation mark or a
     * backslash, so the members need no escaping.
     *
     * @param fields member names mapped to values, in the order to render them
     * @return the rendered object
     */
    private static String json(Map<String, String> fields) {
        StringBuilder text = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append('"').append(field.getKey()).append("\":");
            if (field.getValue() == null) {
                text.append("null");
            } else {
                text.append('"').append(field.getValue()).append('"');
            }
        }
        return text.append('}').toString();
    }

    /**
     * @return a template that executes its callback directly
     */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus(true));
            }
        };
    }

    /**
     * @return configuration holding the replica and decision blocks and nothing else
     */
    private static AuthorizationProperties tolerantProperties() {
        AuthorizationProperties properties = mock(AuthorizationProperties.class);
        when(properties.replica()).thenReturn(new AuthorizationProperties.Replica(0L));
        when(properties.decision())
                .thenReturn(new AuthorizationProperties.Decision(3_000L, Duration.ofMinutes(15)));
        return properties;
    }

    /**
     * Supplies a gap store that owes no account a change.
     *
     * <p>What a decision does with a standing gap belongs to {@code domain/AuthorizationServiceTest}.
     * This file exercises the route, so the replica is held usable throughout.
     *
     * @return a store reporting no gap for any account
     */
    private static ReplicaGapRepository noReplicaGaps() {
        ReplicaGapRepository gaps = mock(ReplicaGapRepository.class);
        when(gaps.existsForAggregate(any())).thenReturn(false);
        return gaps;
    }

    /**
     * Builds one cross-reference row, sliced the way {@code app/cpy/CVACT03Y.cpy:L5-L7} lays the
     * record out. The fourteen-byte {@code FILLER} at {@code app/cpy/CVACT03Y.cpy:L8} is dropped.
     *
     * @param cardNumber the sixteen-character key
     * @param customerId the nine-digit customer identifier
     * @param accountId  the eleven-digit account identifier
     * @return the row, observed at the current moment
     */
    private static CardCrossReferenceEntity crossReference(String cardNumber, String customerId,
            String accountId) {
        return new CardCrossReferenceEntity(cardNumber, customerId, accountId, Instant.now());
    }

    /**
     * Builds one account credit projection, carrying the four values the credit and expiry tests
     * read.
     *
     * @param accountId   the eleven-digit key
     * @param creditLimit {@code ACCT-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy:L8}
     * @param expiry      {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11}
     * @return the row, observed at the current moment, with both cycle accumulators at zero
     */
    private static AccountCreditSnapshotEntity creditSnapshot(String accountId,
            BigDecimal creditLimit, String expiry) {
        return new AccountCreditSnapshotEntity(accountId, creditLimit, expiry, ZERO_CYCLE,
                ZERO_CYCLE, Instant.now());
    }

    /** The decision service the endpoint calls, stubbed for the slices that assert one status. */
    private AuthorizationService authorizations;

    /** The endpoint under test, standing alone with its advice and no application context. */
    private MockMvc mockMvc;

    /** Stands the endpoint and its advice up ahead of each test. */
    @BeforeEach
    void standUpEndpoint() {
        authorizations = mock(AuthorizationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthorizationController(authorizations))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * Stubs the decision service to approve one call.
     *
     * @param transactionId the identifier the decision names
     */
    private void stubApproval(String transactionId) {
        when(authorizations.authorize(any(), any())).thenReturn(AuthorizationService.Outcome
                .approved(new BigDecimal(WORKED_EXAMPLE_ACCOUNT), transactionId));
    }

    /**
     * Stubs the decision service to decline one call, against the account the reject code has one.
     *
     * <p>Three of the four reject codes resolve an account before they are assigned and are decided
     * against it. Reject code {@code 0100} is assigned by the read that resolves the account, so it has
     * none, and {@code domain/AuthorizationService.Outcome} refuses to carry an account with it — the
     * two directions of that invariant are what {@code domain/AuthorizationServiceTest} measures. This
     * helper therefore hands the controller whichever shape the reject code permits.
     *
     * @param reason the one reject code that stands
     */
    private void stubDecline(DeclineReason reason) {
        BigDecimal subject = reason.resolvesAccount() ? new BigDecimal(WORKED_EXAMPLE_ACCOUNT) : null;
        when(authorizations.authorize(any(), any())).thenReturn(AuthorizationService.Outcome
                .declined(reason, subject, ALLOCATED_ID));
    }

    /**
     * Posts one body to the one route and returns the completed exchange.
     *
     * @param body the request body to send
     * @return the exchange, for assertions the matchers do not cover
     * @throws Exception when the exchange itself fails
     */
    private MvcResult postBody(String body) throws Exception {
        return mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** Cross-reference store the chain reads, one stubbed row at a time. */
    private CardCrossReferenceRepository cardCrossReferences;

    /** Account credit projection the chain reads, one stubbed row at a time. */
    private AccountCreditSnapshotRepository accountSnapshots;

    /** Allocator of transaction identifiers, standing in for the database sequence. */
    private TransactionIdentifierSource identifiers;

    /** The endpoint over the real rules, the real writer and the two stubbed stores. */
    private MockMvc decisionPath;

    /**
     * Stands the endpoint up over the real decision chain and the stores this service owns.
     *
     * <p>The four rules, the outbox writer and the decision service are the production classes. The
     * repositories are stubbed, so a decision follows from the rows a test supplies. The capture
     * window and the freshness ceiling are widened, since the fixtures carry moments from 2022.
     */
    private void standUpDecisionPath() {
        cardCrossReferences = mock(CardCrossReferenceRepository.class);
        accountSnapshots = mock(AccountCreditSnapshotRepository.class);
        identifiers = mock(TransactionIdentifierSource.class);
        OutboxEventRepository outboxEvents = mock(OutboxEventRepository.class);

        when(identifiers.nextIdentifier()).thenReturn(ALLOCATED_ID);
        when(outboxEvents.save(any(OutboxEventEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(cardCrossReferences.findByCardNumber(any())).thenReturn(Optional.empty());
        when(cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(any()))
                .thenReturn(Optional.empty());
        when(accountSnapshots.findForUpdateByAccountId(any())).thenReturn(Optional.empty());
        when(accountSnapshots.reserveCycleExposure(any(), any(), any(), any())).thenReturn(1);

        CycleExposureReservation cycleExposure =
                new CycleExposureReservation(accountSnapshots, tolerantProperties());
        List<DeclineRule> rules = List.of(new CardCrossReferenceRule(cardCrossReferences),
                new AccountExistsRule(accountSnapshots), new CreditLimitRule(cycleExposure),
                new AccountExpirationRule());

        AuthorizationService service = new AuthorizationService(rules, cardCrossReferences,
                identifiers, new OutboxWriter(outboxEvents),
                mock(AuthorizationDecisionRepository.class),
                Metrics.globalRegistry, immediateTransactions(), tolerantProperties(),
                cycleExposure, () -> ReplicaSynchronization.Verdict.synchronizedAt(0L),
                noReplicaGaps());

        decisionPath = MockMvcBuilders
                .standaloneSetup(new AuthorizationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(post(ROUTE).principal(ENTITLED_CALLER))
                .build();
    }

    /**
     * @param cardNumber the sixteen-character key
     * @param customerId the nine-digit customer identifier
     * @param accountId  the eleven-digit account identifier
     */
    private void resolveCard(String cardNumber, String customerId, String accountId) {
        CardCrossReferenceEntity row = crossReference(cardNumber, customerId, accountId);
        when(cardCrossReferences.findByCardNumber(cardNumber)).thenReturn(Optional.of(row));
        when(cardCrossReferences.findFirstByAccountIdOrderByCardNumberAsc(accountId))
                .thenReturn(Optional.of(row));
    }

    /**
     * @param accountId   the eleven-digit key
     * @param creditLimit the limit the credit test compares
     * @param expiry      the ten-character date the expiry test compares
     */
    private void resolveAccount(String accountId, BigDecimal creditLimit, String expiry) {
        when(accountSnapshots.findForUpdateByAccountId(accountId))
                .thenReturn(Optional.of(creditSnapshot(accountId, creditLimit, expiry)));
    }

    /**
     * Posts one body to the decision path and returns the completed exchange.
     *
     * @param body the request body to send
     * @return the exchange
     * @throws Exception when the exchange itself fails
     */
    private MvcResult decide(String body) throws Exception {
        return decisionPath
                .perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /**
     * Status mapping of one decision, from {@code app/cbl/CBTRN02C.cbl:L229-L230}.
     *
     * <p>Return code 4 marks a run that rejected records, so the source treats a rejection as a
     * normal ending. A decline therefore answers {@code 422} and never {@code 500} or {@code 503}.
     */
    @Nested
    @DisplayName("Status of one decision")
    final class DecisionStatus {

        @Test
        void anApprovalAnswersTwoHundredAndNamesNoCreatedResource() throws Exception {
            stubApproval(ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.approved").value(true))
                    .andExpect(jsonPath("$.accountId").value(WORKED_EXAMPLE_ACCOUNT))
                    .andExpect(jsonPath("$.transactionId").value(ALLOCATED_ID))
                    .andExpect(jsonPath("$.declineReasonCode").doesNotExist())
                    .andExpect(jsonPath("$.declineReasonDescription").doesNotExist());
        }

        /**
         * Every reject code the enum carries renders as one 422 body.
         *
         * <p>The status, the {@code approved} flag, the reject code and its text are uniform across all
         * four. The account is not, and that is the one difference this loop holds: reject code
         * {@code 0100} is assigned by the cross-reference read itself, so no account was resolved for
         * it and the body carries {@code accountId} as JSON null. The other three resolved an account
         * before their rule ran and name it. {@code openapi.yaml} says the same thing as four
         * {@code oneOf} branches, one of which pins {@code accountId} to the null type.
         */
        @Test
        void eachDeclineAnswersFourTwentyTwo() throws Exception {
            for (DeclineReason reason : DeclineReason.values()) {
                stubDecline(reason);

                mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                                .content(json(completeBody())))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.approved").value(false))
                        .andExpect(reason.resolvesAccount()
                                ? jsonPath("$.accountId").value(WORKED_EXAMPLE_ACCOUNT)
                                : jsonPath("$.accountId").value(nullValue()))
                        .andExpect(jsonPath("$.declineReasonCode").value(reason.code()))
                        .andExpect(jsonPath("$.declineReasonDescription")
                                .value(reason.description()));
            }
        }

        /**
         * A card resolving no cross-reference row answers 422 with the reject code and text the source
         * assigns for it, and with no account.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L385-L387} assigns reject reason {@code 0100} inside the
         * {@code INVALID KEY} limb of the cross-reference read, and {@code :L446-L465} writes the reject
         * record for it as for the other three. So the body is a decline body: it carries
         * {@code approved} false, the code, and the verbatim text. What it carries as null is the
         * account, because the read that would have resolved one is the read that failed. Every caller
         * receives the same answer whatever the request body declared, which
         * {@code domain/AuthorizationServiceTest} measures on the decision side.
         */
        @Test
        void aCardResolvingNoRowAnswersItsOwnCodeAndNoAccount() throws Exception {
            stubDecline(DeclineReason.INVALID_CARD_NUMBER);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.approved").value(false))
                    .andExpect(jsonPath("$.accountId").value(nullValue()))
                    .andExpect(jsonPath("$.transactionId").value(ALLOCATED_ID))
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(DeclineReason.INVALID_CARD_NUMBER.code()))
                    .andExpect(jsonPath("$.declineReasonDescription")
                            .value("INVALID CARD NUMBER FOUND"))
                    .andExpect(jsonPath("$.messages").doesNotExist());
        }

        @Test
        void noDeclineAnswersFiveHundredOrFiveHundredAndThree() throws Exception {
            for (DeclineReason reason : DeclineReason.values()) {
                stubDecline(reason);

                int answered = postBody(json(completeBody())).getResponse().getStatus();

                assertEquals(422, answered,
                        "a decline is a committed outcome under app/cbl/CBTRN02C.cbl:L229-L230");
            }
        }

        /**
         * A declined call produces no error body, so the advice never sees a decline.
         *
         * <p>{@link AuthorizationController} returns a status and a value. It raises nothing for a
         * decline, so {@link GlobalExceptionHandler} has no part in that path.
         */
        @Test
        void aDeclineCarriesNoneOfTheErrorBodyMembers() throws Exception {
            stubDecline(DeclineReason.OVER_CREDIT_LIMIT);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.status").doesNotExist())
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.messages").doesNotExist())
                    .andExpect(jsonPath("$.timestamp").doesNotExist());
        }
    }

    /**
     * Wire form of the reject code and its text, from the trailer at
     * {@code app/cbl/CBTRN02C.cbl:L181-L182}.
     *
     * <p>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} holds four digits and
     * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} holds the text. Both fill the eighty-byte
     * trailer at {@code app/cbl/CBTRN02C.cbl:L178}, which closes the four-hundred-and-thirty-byte
     * reject record allocated at {@code app/jcl/POSTTRAN.jcl:L36} as a Generation Data Group (GDG).
     */
    @Nested
    @DisplayName("Wire form of one reject code")
    final class RejectCodeWireForm {

        @Test
        void aRejectCodeTravelsAsFourZeroPaddedCharacters() throws Exception {
            for (DeclineReason reason : DeclineReason.values()) {
                stubDecline(reason);

                String body = postBody(json(completeBody())).getResponse().getContentAsString();

                assertTrue(body.contains("\"declineReasonCode\":\"" + reason.code() + "\""),
                        "the four-digit form of app/cbl/CBTRN02C.cbl:L181 travels as text");
                assertFalse(body.contains("\"declineReasonCode\":" + reason.numericCode()),
                        "a bare number would break the enumerated schema of the declined event");
                assertEquals(4, reason.code().length(),
                        "PIC 9(04) at app/cbl/CBTRN02C.cbl:L181 holds four digits");
            }
        }

        @Test
        void eachRejectTextFitsSeventySixCharacters() {
            for (DeclineReason reason : DeclineReason.values()) {
                assertTrue(reason.description().length()
                                <= AuthorizationResponse.DECLINE_REASON_DESCRIPTION_MAX_LENGTH,
                        "PIC X(76) at app/cbl/CBTRN02C.cbl:L182 bounds the text of "
                                + reason.code());
            }
            assertEquals(76, AuthorizationResponse.DECLINE_REASON_DESCRIPTION_MAX_LENGTH,
                    "the bound is the width of WS-VALIDATION-FAIL-REASON-DESC");
        }

        /**
         * The served text is the one {@link DeclineReason} holds, with nothing added.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L417-L419} spells the longest of the four as
         * {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}, with the account abbreviated.
         */
        @Test
        void theServedTextIsTheEnumTextUnaltered() throws Exception {
            stubDecline(DeclineReason.ACCOUNT_EXPIRED);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(jsonPath("$.declineReasonDescription")
                            .value(DeclineReason.ACCOUNT_EXPIRED.description()));

            assertTrue(DeclineReason.ACCOUNT_EXPIRED.description().contains("ACCT"),
                    "app/cbl/CBTRN02C.cbl:L417-L419 abbreviates the word");
            assertFalse(DeclineReason.ACCOUNT_EXPIRED.description().contains("ACCOUNT EXPIRATION"),
                    "the text is reproduced and not expanded");
        }

        /**
         * The served body carries five members and none of the values the source keeps
         * elsewhere.
         *
         * <p>The three-hundred-and-fifty-byte payload beside the trailer,
         * {@code REJECT-TRAN-DATA PIC X(350)} at {@code app/cbl/CBTRN02C.cbl:L177}, belongs to the
         * ledger posting service. The card verification value at {@code app/cpy/CVACT02Y.cpy:L7}
         * belongs to the card service.
         */
        @Test
        void theServedBodyCarriesFiveMembersAndNoCardholderValue() throws Exception {
            stubDecline(DeclineReason.OVER_CREDIT_LIMIT);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(jsonPath("$.*", Matchers.hasSize(5)))
                    .andExpect(jsonPath("$." + CARD_NUMBER_FIELD).doesNotExist())
                    .andExpect(jsonPath("$.cardVerificationValue").doesNotExist())
                    .andExpect(jsonPath("$.maskedCardNumber").doesNotExist())
                    .andExpect(jsonPath("$." + AMOUNT_FIELD).doesNotExist())
                    .andExpect(jsonPath("$.merchantId").doesNotExist())
                    .andExpect(jsonPath("$.merchantName").doesNotExist())
                    .andExpect(jsonPath("$." + ORIGIN_FIELD).doesNotExist())
                    .andExpect(jsonPath("$." + PROCESSING_FIELD).doesNotExist())
                    .andExpect(jsonPath("$.rejectTransactionData").doesNotExist())
                    .andExpect(jsonPath("$.accountStatus").doesNotExist())
                    .andExpect(jsonPath("$.cardStatus").doesNotExist())
                    .andExpect(jsonPath("$.filler").doesNotExist());

            String body = postBody(json(completeBody())).getResponse().getContentAsString();

            assertFalse(body.contains(WORKED_EXAMPLE_CARD),
                    "no served body repeats the full Primary Account Number (PAN)");
            assertFalse(body.contains("Exception"), "no served body names a failure type");
        }
    }

    /**
     * Refusal texts of {@code app/cbl/COTRN02C.cbl}, carried through unaltered.
     *
     * <p>The program moves one text into {@code WS-MESSAGE} and re-sends the screen. This endpoint
     * carries the same text to a caller that has no screen, and adds no prefix and no suffix.
     */
    @Nested
    @DisplayName("Refusal texts of the capture program")
    final class RefusalTexts {

        /**
         * Posts a body and asserts the served refusal holds one exact text.
         *
         * @param body     the request body to send
         * @param expected the text the source moves into {@code WS-MESSAGE}
         * @param locator  the source line the text is read from
         * @throws Exception when the exchange itself fails
         */
        private void assertRefusedWith(String body, String expected, String locator)
                throws Exception {

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.VALIDATION_FAILED))
                    .andExpect(jsonPath("$.messages", Matchers.hasItem(expected)));

            verify(authorizations, never()).authorize(any(), any());
            assertNotNull(locator, "each refusal names the source line it reproduces");
        }

        /** Asserts a non-numeric account identifier reports the text of app/cbl/COTRN02C.cbl:L199. */
        @Test
        void aNonNumericAccountIdentifierReportsItsOwnText() throws Exception {
            assertEquals("Account ID must be Numeric...",
                    AuthorizationRequest.ACCOUNT_ID_NOT_NUMERIC_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L199, character for character");
            assertRefusedWith(bodyNamingAccount("0000000000X"),
                    AuthorizationRequest.ACCOUNT_ID_NOT_NUMERIC_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L199");
        }

        /** Asserts a card number of another width reports the text of app/cbl/COTRN02C.cbl:L213. */
        @Test
        void aCardNumberOfAnotherWidthReportsItsOwnText() throws Exception {
            assertEquals("Card Number must be Numeric...",
                    AuthorizationRequest.CARD_NUMBER_NOT_NUMERIC_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L213, character for character");
            assertRefusedWith(bodyWith(CARD_NUMBER_FIELD, SHORTENED_CARD),
                    AuthorizationRequest.CARD_NUMBER_NOT_NUMERIC_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L213");
        }

        /**
         * A body naming neither identifier reports the text of app/cbl/COTRN02C.cbl:L226.
         *
         * <p>That text belongs to the {@code WHEN OTHER} limb of the {@code EVALUATE TRUE} at
         * {@code app/cbl/COTRN02C.cbl:L195}, which closes at {@code app/cbl/COTRN02C.cbl:L230}.
         */
        @Test
        void aBodyNamingNeitherIdentifierReportsItsOwnText() throws Exception {
            assertEquals("Account or Card Number must be entered...",
                    AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L226, character for character");
            assertRefusedWith(bodyWithout(CARD_NUMBER_FIELD),
                    AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L226");
        }

        /** Asserts an amount the grammar refuses reports the text of app/cbl/COTRN02C.cbl:L345. */
        @Test
        void anUnreadableAmountReportsItsOwnText() throws Exception {
            assertEquals("Amount should be in format -99999999.99",
                    AuthorizationRequest.AMOUNT_FORMAT_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L345, character for character and no trailing ellipsis");
            assertRefusedWith(bodyWith(AMOUNT_FIELD, "+00000504:77"),
                    AuthorizationRequest.AMOUNT_FORMAT_MESSAGE, "app/cbl/COTRN02C.cbl:L345");
        }

        /** Asserts a misshapen capture moment reports the text of app/cbl/COTRN02C.cbl:L360. */
        @Test
        void aMisshapenCaptureMomentReportsItsOwnText() throws Exception {
            assertEquals("Orig Date should be in format YYYY-MM-DD",
                    AuthorizationRequest.ORIGIN_DATE_FORMAT_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L360, character for character and no trailing ellipsis");
            assertRefusedWith(bodyWith(ORIGIN_FIELD, PROCESSING_TIMESTAMP),
                    AuthorizationRequest.ORIGIN_DATE_FORMAT_MESSAGE, "app/cbl/COTRN02C.cbl:L360");
        }

        /**
         * A misshapen processing moment reports the text of app/cbl/COTRN02C.cbl:L375.
         *
         * <p>That text carries no trailing ellipsis, and a copy that adds one is a defect.
         */
        @Test
        void aMisshapenProcessingMomentReportsItsOwnText() throws Exception {
            assertEquals("Proc Date should be in format YYYY-MM-DD",
                    AuthorizationRequest.PROCESSING_DATE_FORMAT_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L375, character for character and no trailing ellipsis");
            assertRefusedWith(bodyWith(PROCESSING_FIELD, ORIGIN_TIMESTAMP),
                    AuthorizationRequest.PROCESSING_DATE_FORMAT_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L375");
        }

        /**
         * A capture date the tolerant policy declines reports the text of
         * app/cbl/COTRN02C.cbl:L401.
         */
        @Test
        void aCaptureDateThePolicyDeclinesReportsItsOwnText() throws Exception {
            assertEquals("Orig Date - Not a valid date...",
                    AuthorizationRequest.ORIGIN_DATE_INVALID_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L401, character for character");
            assertRefusedWith(bodyWith(ORIGIN_FIELD, "2022-02-30 19:27:53.000000"),
                    AuthorizationRequest.ORIGIN_DATE_INVALID_MESSAGE, "app/cbl/COTRN02C.cbl:L401");
        }

        /**
         * A processing date the tolerant policy declines reports the text of
         * app/cbl/COTRN02C.cbl:L421.
         */
        @Test
        void aProcessingDateThePolicyDeclinesReportsItsOwnText() throws Exception {
            assertEquals("Proc Date - Not a valid date...",
                    AuthorizationRequest.PROCESSING_DATE_INVALID_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L421, character for character");
            assertRefusedWith(bodyWith(PROCESSING_FIELD, "2022-02-30-19.27.53.000000"),
                    AuthorizationRequest.PROCESSING_DATE_INVALID_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L421");
        }

        /**
         * A non-numeric merchant identifier reports the text of app/cbl/COTRN02C.cbl:L432.
         */
        @Test
        void aNonNumericMerchantIdentifierReportsItsOwnText() throws Exception {
            assertEquals("Merchant ID must be Numeric...",
                    AuthorizationRequest.MERCHANT_ID_NOT_NUMERIC_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L432, character for character");
            assertRefusedWith(bodyWith("merchantId", "80000000X"),
                    AuthorizationRequest.MERCHANT_ID_NOT_NUMERIC_MESSAGE,
                    "app/cbl/COTRN02C.cbl:L432");
        }

        /**
         * Three of the nine texts close without an ellipsis and six close with one.
         *
         * <p>Read first-hand, {@code app/cbl/COTRN02C.cbl:L345}, {@code :L360} and {@code :L375}
         * carry no trailing dots, while {@code :L199}, {@code :L213}, {@code :L226}, {@code :L401},
         * {@code :L421} and {@code :L432} carry three.
         */
        @Test
        void threeTextsCloseWithoutAnEllipsisAndSixCloseWithOne() {
            List<String> withoutEllipsis = List.of(
                    AuthorizationRequest.AMOUNT_FORMAT_MESSAGE,
                    AuthorizationRequest.ORIGIN_DATE_FORMAT_MESSAGE,
                    AuthorizationRequest.PROCESSING_DATE_FORMAT_MESSAGE);
            List<String> withEllipsis = List.of(
                    AuthorizationRequest.ACCOUNT_ID_NOT_NUMERIC_MESSAGE,
                    AuthorizationRequest.CARD_NUMBER_NOT_NUMERIC_MESSAGE,
                    AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE,
                    AuthorizationRequest.ORIGIN_DATE_INVALID_MESSAGE,
                    AuthorizationRequest.PROCESSING_DATE_INVALID_MESSAGE,
                    AuthorizationRequest.MERCHANT_ID_NOT_NUMERIC_MESSAGE);

            for (String text : withoutEllipsis) {
                assertFalse(text.endsWith("..."), "the source line holding " + text
                        + " closes on the mask and nothing else");
            }
            for (String text : withEllipsis) {
                assertTrue(text.endsWith("..."), "the source line holding " + text
                        + " closes on three dots");
            }
            assertEquals(9, withoutEllipsis.size() + withEllipsis.size(),
                    "app/cbl/COTRN02C.cbl carries nine texts on the authorization request path");
        }

        /**
         * A refused field and a decline share {@code 422} and differ in body shape.
         *
         * <p>A refused field answers {@link ApiErrorResponse}. A decline answers
         * {@link AuthorizationResponse}. A caller reads the two apart by shape.
         */
        @Test
        void aRefusedFieldAndADeclineShareTheStatusAndDifferInShape() throws Exception {
            String refused = postBody(bodyWith("merchantId", "80000000X")).getResponse()
                    .getContentAsString();

            stubDecline(DeclineReason.OVER_CREDIT_LIMIT);
            String declined = postBody(json(completeBody())).getResponse().getContentAsString();

            assertTrue(refused.contains("\"error\":\"" + ApiErrorResponse.VALIDATION_FAILED + "\""),
                    "a refused field names the class of failure");
            assertTrue(refused.contains("\"messages\""), "a refused field lists one text per field");
            assertFalse(refused.contains("\"approved\""), "a refused field carries no verdict");

            assertTrue(declined.contains("\"approved\":false"), "a decline carries a verdict");
            assertTrue(declined.contains("\"declineReasonCode\""), "a decline names one reject code");
            assertFalse(declined.contains("\"messages\""), "a decline carries no text list");
        }
    }

    /**
     * Statuses that replace the one abend of {@code app/cbl/CBTRN02C.cbl:L707-L711}.
     *
     * <p>That paragraph displays a line, zeroes a timing field, moves 999 into an abend code and
     * calls the language-environment abend service. More than twenty call sites reach it and it
     * cleans nothing up. One handler per class of failure replaces that single ending.
     */
    @Nested
    @DisplayName("Statuses replacing the abend")
    final class FailureStatuses {

        @Test
        void anUnreadableBodyAnswersFourHundred() throws Exception {
            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.UNPROCESSABLE))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(GlobalExceptionHandler.UNREADABLE_BODY_MESSAGE));

            verify(authorizations, never()).authorize(any(), any());
        }

        /**
         * Asserts a method this route does not serve answers 405 with an Allow header.
         *
         * <p>The catch-all arm claimed the checked exception the framework raises for an unsupported
         * method before the protocol arm existed, so a read of this route answered {@code 500} and
         * the fault text. A client cannot tell that answer from a fault of this service, and a retry
         * of it repeats the mistake. {@code Allow} names the one method this route serves.
         */
        @Test
        void aMethodThisRouteDoesNotServeAnswersFourOhFive() throws Exception {
            for (RequestBuilder wrongMethod : List.of(get(ROUTE), put(ROUTE), patch(ROUTE),
                    delete(ROUTE))) {

                mockMvc.perform(wrongMethod)
                        .andExpect(status().isMethodNotAllowed())
                        .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                        .andExpect(jsonPath("$.status").value(405))
                        .andExpect(jsonPath("$.error").value(ApiErrorResponse.UNPROCESSABLE))
                        .andExpect(jsonPath("$.messages[0]")
                                .value(GlobalExceptionHandler.UNSUPPORTED_REQUEST_MESSAGE));
            }
            verify(authorizations, never()).authorize(any(), any());
        }

        /** Asserts a call naming a media type this endpoint does not read answers 415. */
        @Test
        void anUnreadableMediaTypeAnswersFourFifteen() throws Exception {
            mockMvc.perform(post(ROUTE).contentType(MediaType.TEXT_PLAIN)
                            .content(json(completeBody())))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.UNPROCESSABLE))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(GlobalExceptionHandler.MEDIA_TYPE_MESSAGE));

            verify(authorizations, never()).authorize(any(), any());
        }

        /**
         * An unreachable datastore answers 503, which is the retryable signal.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L707-L711} answers the same class of fault with an abend,
         * and the file-status formatter at {@code app/cbl/CBTRN02C.cbl:L714-L727} is all the
         * diagnosis it leaves.
         */
        @Test
        void anUnreachableDatastoreAnswersFiveHundredAndThree() throws Exception {
            when(authorizations.authorize(any(), any())).thenThrow(
                    new DataAccessResourceFailureException(
                            "the connection to the store holding " + WORKED_EXAMPLE_CARD
                                    + " was lost"));

            String body = mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.INTERNAL_FAILURE))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(GlobalExceptionHandler.INTERNAL_FAILURE_MESSAGE))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertFalse(body.contains(WORKED_EXAMPLE_CARD),
                    "the failure text of a driver reaches no caller");
        }

        /**
         * A projection too old to authorize against answers 503.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L556} assigns a reject code inside the invalid-key limb of
         * the account rewrite, and nothing in that program reads the value before
         * {@code app/cbl/CBTRN02C.cbl:L208} clears it for the next record. Here the same class of
         * fault is observable, and a caller may present the request again.
         */
        @Test
        void aProjectionTooOldToReadAnswersFiveHundredAndThree() throws Exception {
            when(authorizations.authorize(any(), any())).thenThrow(
                    new AuthorizationService.StaleReplicaException("replica-stream-behind"));

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.INTERNAL_FAILURE));
        }

        /**
         * Asserts a caller refused the subject it named answers 403, and that the body discloses
         * nothing.
         *
         * <p>ADDITIVE, and not a decline. The four reject reasons at
         * {@code app/cbl/CBTRN02C.cbl:L385-L420} are outcomes of the transaction, each published as an
         * event. This is an outcome of the caller: no decision was taken, so the body carries no
         * approved member and no reject code, and the detail names neither the account nor the card.
         */
        @Test
        void anUnentitledCallerAnswersFourHundredAndThree() throws Exception {
            when(authorizations.authorize(any(), any()))
                    .thenThrow(new CallerNotEntitledException());

            String body = mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.FORBIDDEN))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(CallerNotEntitledException.DETAIL))
                    .andExpect(jsonPath("$.approved").doesNotExist())
                    .andExpect(jsonPath("$.declineReasonCode").doesNotExist())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertFalse(body.contains(WORKED_EXAMPLE_CARD),
                    "the card number the caller sent reaches no response body");
            assertFalse(body.contains(WORKED_EXAMPLE_ACCOUNT),
                    "and neither does an account identifier it may not have");
        }

        /** Asserts a fault no handler claimed answers 500 and repeats no value from the request. */
        @Test
        void anUnclaimedFaultAnswersFiveHundred() throws Exception {
            when(authorizations.authorize(any(), any())).thenThrow(
                    new IllegalStateException("the datasource holds " + WORKED_EXAMPLE_CARD));

            String body = mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.INTERNAL_FAILURE))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertFalse(body.contains(WORKED_EXAMPLE_CARD),
                    "the message of an unclaimed fault reaches no caller");
        }

        /**
         * A request this service refused after binding answers 422 with one fixed text.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L591-L592} answers the not-found limb of the account read
         * the same way, by re-sending the screen with a text and capturing nothing.
         */
        @Test
        void aRefusalTakenAfterBindingAnswersFourTwentyTwo() throws Exception {
            when(authorizations.authorize(any(), any())).thenThrow(new IllegalArgumentException(
                    AuthorizationRequest.ACCOUNT_ID_NOT_FOUND_MESSAGE));

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.UNPROCESSABLE))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(GlobalExceptionHandler.REFUSED_REQUEST_MESSAGE));
        }

        @Test
        void aRefusedBodyRepeatsNoValueItCarried() throws Exception {
            Map<String, String> misplaced = completeBody();
            misplaced.put("source", WORKED_EXAMPLE_CARD);
            misplaced.put("merchantId", "80000000X");

            String body = postBody(json(misplaced)).getResponse().getContentAsString();

            assertFalse(body.contains(WORKED_EXAMPLE_CARD),
                    "no served body repeats a value read from the request");
            assertFalse(body.contains(ROUTE), "no served body echoes the request path");
        }
    }

    /**
     * Surface of the endpoint: one route, one collaborator and no conversation state.
     *
     * <p>The source is pseudo-conversational, and the seven navigation fields of
     * {@code app/cpy/COCOM01Y.cpy} carry its state between screens. Four sit at
     * {@code app/cpy/COCOM01Y.cpy:L21-L24}, one at {@code app/cpy/COCOM01Y.cpy:L29} with its two
     * condition names below it, and two at {@code app/cpy/COCOM01Y.cpy:L43-L44}. None has a
     * counterpart here.
     */
    @Nested
    @DisplayName("Surface of the endpoint")
    final class EndpointSurface {

        @Test
        void theControllerPublishesOneRouteAndOneMethod() {
            List<Method> mapped = new ArrayList<>();
            for (Method method : AuthorizationController.class.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostMapping.class)) {
                    mapped.add(method);
                }
                assertFalse(method.isAnnotationPresent(GetMapping.class),
                        "no read route belongs to this service");
                assertFalse(method.isAnnotationPresent(PutMapping.class),
                        "no replace route belongs to this service");
                assertFalse(method.isAnnotationPresent(PatchMapping.class),
                        "no amend route belongs to this service");
                assertFalse(method.isAnnotationPresent(DeleteMapping.class),
                        "no remove route belongs to this service");
                assertFalse(method.isAnnotationPresent(RequestMapping.class),
                        "the one route names its method through PostMapping");
            }

            assertEquals(1, mapped.size(), "one endpoint answers this service");

            PostMapping mapping = mapped.get(0).getAnnotation(PostMapping.class);
            assertEquals(List.of(ROUTE), List.of(mapping.path()), "the route is fixed and has no "
                    + "path variable, so no full Primary Account Number reaches a request line");
            assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), List.of(mapping.consumes()),
                    "the endpoint reads one media type");
            assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), List.of(mapping.produces()),
                    "the endpoint writes one media type");
        }

        /**
         * The controller holds one collaborator, the decision service, and reaches no other
         * component.
         *
         * <p>The endpoint reads no table of its own and calls no other service over the network. The
         * four decline rules read the two projections this service owns.
         */
        @Test
        void theControllerHoldsOneCollaboratorAndReachesNoOtherComponent() {
            Constructor<?>[] constructors = AuthorizationController.class.getDeclaredConstructors();
            assertEquals(1, constructors.length, "construction takes one path");
            assertEquals(List.of(AuthorizationService.class),
                    List.of(constructors[0].getParameterTypes()),
                    "the decision service is the one collaborator injected");

            List<Field> fields = new ArrayList<>();
            for (Field field : AuthorizationController.class.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    fields.add(field);
                }
            }

            assertEquals(1, fields.size(), "one collaborator is held");
            assertEquals(AuthorizationService.class, fields.get(0).getType(),
                    "the held collaborator is the decision service and nothing else");
            assertTrue(Modifier.isFinal(fields.get(0).getModifiers()),
                    "the held collaborator cannot be replaced after construction");
            assertTrue(Modifier.isPrivate(fields.get(0).getModifiers()),
                    "the held collaborator is not readable from outside");
        }

        @Test
        void twoCallsShareNoServerSideState() throws Exception {
            stubApproval(ALLOCATED_ID);

            MvcResult first = postBody(json(completeBody()));
            MvcResult second = postBody(json(completeBody()));

            assertEquals(200, first.getResponse().getStatus(), "the first call is answered");
            assertEquals(200, second.getResponse().getStatus(), "the second call is answered");
            assertNull(first.getRequest().getSession(false), "the first call opens no conversation");
            assertNull(second.getRequest().getSession(false),
                    "the second call opens no conversation");
            assertNull(first.getResponse().getHeader("Set-Cookie"),
                    "no state travels back for a caller to return");
            assertEquals(first.getResponse().getContentAsString(),
                    second.getResponse().getContentAsString(),
                    "one body answered twice reads the same, so nothing accumulated between calls");
        }

        /**
         * The identity behind a call reaches the decision service, and that a call carrying
         * none still names an actor.
         *
         * <p>{@code app/cbl/COMEN01C.cbl:L149-L150} holds two commented-out statements over the
         * signed-on identifier, so no identity travels forward there. This endpoint carries one on
         * each call.
         */
        @Test
        void theIdentityBehindTheCallReachesTheService() throws Exception {
            stubApproval(ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody()))
                            .principal(() -> ACTOR))
                    .andExpect(status().isOk());
            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isOk());

            ArgumentCaptor<RequestCaller> callers = ArgumentCaptor.forClass(RequestCaller.class);
            verify(authorizations, times(2)).authorize(any(), callers.capture());

            assertEquals(ACTOR, callers.getAllValues().get(0).actor(),
                    "the resolved identity travels into the decision");
            assertEquals(AuthenticatedActor.UNAUTHENTICATED_ACTOR,
                    callers.getAllValues().get(1).actor(),
                    "a call carrying no identity still names an actor the audit column holds");
        }

        /**
         * The advice answers failures alone and publishes nothing.
         *
         * <p>The advice is a Representational State Transfer handler. It holds no producer, subscribes
         * to no stream and routes nothing to the dead-letter topic.
         */
        @Test
        void theAdviceHandlesFailuresAndPublishesNothing() {
            List<String> handled = new ArrayList<>();
            for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    assertFalse(annotation.annotationType().getSimpleName().contains("Listener"),
                            "the advice subscribes to no stream");
                }
                ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
                if (handler == null) {
                    continue;
                }
                for (Class<?> claimed : handler.value()) {
                    handled.add(claimed.getSimpleName());
                    assertFalse(claimed.getName().startsWith("org.apache.kafka"),
                            "no broker failure reaches a synchronous caller");
                    assertFalse(claimed.getName().startsWith("org.springframework.kafka"),
                            "no stream failure reaches a synchronous caller");
                }
            }

            assertTrue(handled.containsAll(List.of("MethodArgumentNotValidException",
                            "HandlerMethodValidationException", "ConstraintViolationException",
                            "HttpMessageNotReadableException", "IllegalArgumentException",
                            "CallerNotEntitledException", "StaleReplicaException", "Exception")),
                    "each class of failure this service can raise has one handler");

            for (Field field : GlobalExceptionHandler.class.getDeclaredFields()) {
                assertFalse(field.getType().getName().startsWith("org.apache.kafka"),
                        "the advice holds no producer");
                assertFalse(field.getType().getName().startsWith("org.springframework.kafka"),
                        "the advice holds no stream component");
            }
        }
    }

    /**
     * The three limbs of {@code VALIDATE-INPUT-KEY-FIELDS} at
     * {@code app/cbl/COTRN02C.cbl:L193-L230}.
     *
     * <p>The {@code EVALUATE TRUE} at {@code app/cbl/COTRN02C.cbl:L195} branches three ways. An
     * account identifier reads the alternate index and takes the card number the row carries, at
     * {@code app/cbl/COTRN02C.cbl:L208-L209}. A card number reads the primary key and takes the
     * account identifier, at {@code app/cbl/COTRN02C.cbl:L222-L223}. Neither identifier reaches the
     * {@code WHEN OTHER} limb at {@code app/cbl/COTRN02C.cbl:L224}.
     *
     * <p>The two paths name two entries of the Customer Information Control System definition file.
     * {@code CCXREF} at {@code app/csd/CARDDEMO.CSD:L37} is the primary key, and {@code CXACAIX} at
     * {@code app/csd/CARDDEMO.CSD:L63} is the alternate index over the account key.
     */
    @Nested
    @DisplayName("The three key-field limbs")
    final class KeyFieldLimbs {

        @Test
        void aCardNumberAloneIsAccepted() throws Exception {
            stubApproval(ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isOk());

            ArgumentCaptor<AuthorizationRequest> sent =
                    ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authorizations).authorize(sent.capture(), any());

            assertTrue(sent.getValue().isCardNumberSupplied(),
                    "the card limb of app/cbl/COTRN02C.cbl:L210-L223 is taken");
            assertEquals(WORKED_EXAMPLE_CARD, sent.getValue().canonicalCardNumber(),
                    "the sixteen-character key travels whole");
            assertNull(sent.getValue().accountId(),
                    "the account identifier is derived from the row and not supplied");
        }

        @Test
        void anAccountIdentifierAloneIsAccepted() throws Exception {
            stubApproval(ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyNamingAccount(WORKED_EXAMPLE_ACCOUNT)))
                    .andExpect(status().isOk());

            ArgumentCaptor<AuthorizationRequest> sent =
                    ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authorizations).authorize(sent.capture(), any());

            assertFalse(sent.getValue().isCardNumberSupplied(),
                    "the account limb of app/cbl/COTRN02C.cbl:L196-L209 is taken");
            assertEquals(WORKED_EXAMPLE_ACCOUNT, sent.getValue().canonicalAccountId(),
                    "the eleven-digit key travels whole");
            assertNull(sent.getValue().cardNumber(),
                    "the card number is derived from the row and not supplied");
        }

        @Test
        void bothIdentifiersTogetherAreAccepted() throws Exception {
            stubApproval(ALLOCATED_ID);
            Map<String, String> both = completeBody();
            both.put(ACCOUNT_ID_FIELD, WORKED_EXAMPLE_ACCOUNT);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(both)))
                    .andExpect(status().isOk());

            ArgumentCaptor<AuthorizationRequest> sent =
                    ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authorizations).authorize(sent.capture(), any());

            assertTrue(sent.getValue().isCardNumberSupplied(),
                    "the card number remains the lookup key when both arrive");
        }

        /**
         * An absent, an empty and an all-whitespace identifier read alike, for each of the
         * two fields.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L196} tests {@code NOT = SPACES AND LOW-VALUES}, so a field
         * of spaces reads as an omitted field. A test for absence alone is not the same test.
         */
        @Test
        void absentEmptyAndBlankIdentifiersReadAlike() throws Exception {
            List<String> blankForms = new ArrayList<>();
            blankForms.add(null);
            blankForms.add("");
            blankForms.add("   ");

            for (String form : blankForms) {
                Map<String, String> noCard = completeBody();
                noCard.put(CARD_NUMBER_FIELD, form);
                if (form == null) {
                    noCard.remove(CARD_NUMBER_FIELD);
                }

                mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                                .content(json(noCard)))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.messages",
                                Matchers.hasItem(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE)));

                Map<String, String> noAccount = completeBody();
                noAccount.remove(CARD_NUMBER_FIELD);
                noAccount.put(ACCOUNT_ID_FIELD, form);
                if (form == null) {
                    noAccount.remove(ACCOUNT_ID_FIELD);
                }

                mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                                .content(json(noAccount)))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.messages",
                                Matchers.hasItem(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE)));
            }

            verify(authorizations, never()).authorize(any(), any());
        }

        /**
         * Asserts a body failing several edits reports the one text the source would have reported.
         *
         * <p>Each edit of {@code app/cbl/COTRN02C.cbl} moves its text into {@code WS-MESSAGE} and
         * performs {@code SEND-TRNADD-SCREEN}, which returns to the terminal, so the edits after it
         * never run. The body below empties the source, the description and the merchant name, and
         * {@code app/cbl/COTRN02C.cbl:L265} is the earliest of the three the source reaches.
         */
        @Test
        void aBodyFailingSeveralEditsReportsOneSourceOrderedText() throws Exception {
            Map<String, String> body = completeBody();
            body.put("source", "");
            body.put("description", "");
            body.put("merchantName", "");

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.messages", Matchers.hasSize(1)))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(AuthorizationRequest.SOURCE_EMPTY_MESSAGE));

            verify(authorizations, never()).authorize(any(), any());
        }

        /**
         * Asserts the earliest edit reported is the earliest the source reaches, not the earliest
         * alphabetically.
         *
         * <p>The two texts below are ordered one way by the source and the other way as text. The
         * merchant city is empty and the type code is not all digits;
         * {@code app/cbl/COTRN02C.cbl:L307} is reached before
         * {@code app/cbl/COTRN02C.cbl:L324}, while "Merchant City can NOT be empty..." sorts after
         * "Type CD must be Numeric...".
         */
        @Test
        void theTextReportedFollowsTheSourceAndNotTheAlphabet() throws Exception {
            Map<String, String> body = completeBody();
            body.put("merchantCity", "");
            body.put("transactionTypeCode", "AB");

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.messages", Matchers.hasSize(1)))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(AuthorizationRequest.MERCHANT_CITY_EMPTY_MESSAGE));
        }

        /**
         * Asserts both timestamps are accepted at the ten-character width the source screen carries.
         *
         * <p>{@code TORIGDTI} and {@code TPROCDTI} are validated at
         * {@code app/cbl/COTRN02C.cbl:L389-L423} as ten-character dates, and
         * {@code app/cbl/COTRN02C.cbl:L360} states that shape. A caller of a synchronous surface has
         * the same value the operator of the screen has.
         */
        @Test
        void bothTimestampsAreAcceptedAtTheTenCharacterSourceWidth() throws Exception {
            stubApproval(ALLOCATED_ID);
            Map<String, String> body = completeBody();
            body.put(ORIGIN_FIELD, "2022-06-10");
            body.put(PROCESSING_FIELD, "2022-06-10");

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isOk());

            verify(authorizations).authorize(any(), any());
        }

        /**
         * Asserts a ten-character capture moment reaches the rules at the record width.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L469} moves the screen field into
         * {@code TRAN-ORIG-TS PIC X(26)}, and reject reason {@code 0103} at
         * {@code app/cbl/CBTRN02C.cbl:L414-L420} compares the first ten characters of that field, so
         * the widened value carries the same decision the ten-character value names.
         */
        @Test
        void aTenCharacterCaptureMomentReachesTheRulesAtTheRecordWidth() {
            AuthorizationRequest request = new AuthorizationRequest(null, TYPE_CODE, CATEGORY_CODE,
                    "POS TERM", "Purchase", WORKED_EXAMPLE_AMOUNT, MERCHANT_ID, "Abshire-Lowe",
                    "North Enoshaven", "72112", WORKED_EXAMPLE_CARD, "2022-06-10", "2022-06-10",
                    null);

            assertEquals(PicClause.TRAN_ORIG_TS_WIDTH, request.recordOriginTimestamp().length(),
                    "the capture moment reaches the rules at the width the record field holds");
            assertEquals("2022-06-10", request.recordOriginTimestamp().substring(0, 10),
                    "the ten characters reject reason 0103 compares are unchanged");
            assertEquals(PicClause.TRAN_PROC_TS_WIDTH,
                    request.recordProcessingTimestamp().length(),
                    "the processing moment is recorded at the width the record field holds");
        }

        /**
         * Asserts a supplied transaction identifier is refused and an omitted one is accepted.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L444-L451} moves high values into the key, browses
         * backwards, reads the previous record and adds one, so no screen field offers the value.
         * {@code ADD-TRANSACTION} at {@code app/cbl/COTRN02C.cbl:L442-L466} then stores thirteen
         * fields. All three hundred records of {@code app/data/ASCII/dailytran.txt} carry an
         * identifier at positions 1 through 16, and a sequence supplies it here.
         */
        @Test
        void anOmittedTransactionIdentifierAuthorizesAndASuppliedOneIsRefused() throws Exception {
            stubApproval(ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value(ALLOCATED_ID));

            Map<String, String> supplied = completeBody();
            supplied.put("transactionId", ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(supplied)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.messages", Matchers.hasItem(
                            AuthorizationRequest.TRANSACTION_ID_NOT_ACCEPTED_MESSAGE)));

            assertEquals(PicClause.DALYTRAN_ID_WIDTH, ALLOCATED_ID.length(),
                    "DALYTRAN-ID PIC X(16) at app/cpy/CVTRA06Y.cpy:L5 fixes the width");
        }
    }

    /**
     * The two identifier boundaries, which are not the same boundary.
     *
     * <p>A card number is text. {@code XREF-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT03Y.cpy:L5} declares it, {@code KEYS(16 0)} at
     * {@code app/jcl/XREFFILE.jcl:L43} keys the dataset on it, and the target column holds sixteen
     * characters, so an opening zero counts.
     *
     * <p>An account identifier is a number. {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7} declares it, and the alternate index at
     * {@code app/jcl/XREFFILE.jcl:L72-L77} is {@code NONUNIQUEKEY}, so one account holds many cards.
     */
    @Nested
    @DisplayName("The two identifier boundaries")
    final class IdentifierBoundaries {

        @Test
        void aCardOpeningWithZeroKeepsThatDigit() throws Exception {
            stubApproval(ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWith(CARD_NUMBER_FIELD, LEADING_ZERO_CARD)))
                    .andExpect(status().isOk());

            ArgumentCaptor<AuthorizationRequest> sent =
                    ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authorizations).authorize(sent.capture(), any());

            assertEquals(LEADING_ZERO_CARD, sent.getValue().canonicalCardNumber(),
                    "row 1 of app/data/ASCII/cardxref.txt opens with a zero digit that is part of "
                            + "the key");
            assertEquals(PicClause.XREF_CARD_NUM_WIDTH,
                    sent.getValue().canonicalCardNumber().length(),
                    "the key reaching the lookup fills PIC X(16)");
        }

        /**
         * A card of another width is refused ahead of the lookup, so no width problem is ever
         * read as an unknown card.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L385} assigns its reject code when the keyed read misses. A
         * fifteen-character value would miss a sixteen-character key and report that code, naming a
         * card the caller never sent. The width test at ingress answers first.
         *
         * <p>The reject code is looked for in the served texts and in the absence of a decision
         * property, rather than anywhere in the body. An error body carries {@code timestamp}, and
         * {@link GlobalExceptionHandler.ApiErrorResponse#of} stamps it with the current moment: about
         * one rendered instant in sixteen hundred holds the four characters of this code among its
         * nanoseconds. A bare search over the body therefore answered the clock as well as the
         * endpoint, and failed for a reason this test is not about. The instant asserted below is one
         * of those renderings.
         */
        @Test
        void aCardOfAnotherWidthIsRefusedAheadOfTheLookup() throws Exception {
            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWith(CARD_NUMBER_FIELD, SHORTENED_CARD)))
                    .andExpect(jsonPath("$.messages", Matchers.hasItem(
                            AuthorizationRequest.CARD_NUMBER_NOT_NUMERIC_MESSAGE)))
                    .andExpect(jsonPath("$.messages", Matchers.not(Matchers.hasItem(
                            Matchers.containsString(DeclineReason.INVALID_CARD_NUMBER.code())))))
                    .andExpect(jsonPath("$.declineReasonCode").doesNotExist());

            assertTrue(Instant.parse("2026-08-12T11:28:06.824510100Z").toString()
                            .contains(DeclineReason.INVALID_CARD_NUMBER.code()),
                    "a rendered instant carries these four characters often enough to fail a build,"
                            + " which is why the two expectations above read the served texts and the"
                            + " decision property rather than the whole body");
            assertEquals(PicClause.XREF_CARD_NUM_WIDTH - 1, SHORTENED_CARD.length(),
                    "the refused value is one character short of the key width");
            verify(authorizations, never()).authorize(any(), any());
        }

        /**
         * The served account identifier is eleven digits, filled from the left with zeros.
         *
         * <p>The decision names the account as a scale-zero number, and this endpoint widens it to the
         * digits {@code XREF-ACCT-ID PIC 9(11)} holds.
         */
        @Test
        void theServedAccountIdentifierIsElevenDigits() throws Exception {
            when(authorizations.authorize(any(), any())).thenReturn(
                    AuthorizationService.Outcome.approved(new BigDecimal("7"), ALLOCATED_ID));

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(WORKED_EXAMPLE_ACCOUNT))
                    .andExpect(jsonPath("$.accountId")
                            .value(Matchers.matchesPattern(
                                    AuthorizationResponse.ACCOUNT_ID_PATTERN)));

            assertEquals(PicClause.XREF_ACCT_ID_WIDTH, WORKED_EXAMPLE_ACCOUNT.length(),
                    "the served form fills PIC 9(11)");
        }

        /**
         * The target column holds a number, so a bare and a zero-padded form of one account
         * identifier compare equal. The served form is text and always eleven digits wide.
         */
        @Test
        void bothFormsOfOneAccountNumberAnswerAlike() throws Exception {
            BigDecimal bare = new BigDecimal("7");
            BigDecimal padded = new BigDecimal(WORKED_EXAMPLE_ACCOUNT);

            assertEquals(0, bare.compareTo(padded),
                    "a numeric column reads the two forms as one account");
            assertEquals(0, bare.scale(), "the identifier carries no scale on the way in");
            assertEquals(0, padded.scale(), "the padded form carries no scale either");

            when(authorizations.authorize(any(), any())).thenReturn(
                    AuthorizationService.Outcome.approved(bare, ALLOCATED_ID));
            String fromBare = postBody(json(completeBody())).getResponse().getContentAsString();

            when(authorizations.authorize(any(), any())).thenReturn(
                    AuthorizationService.Outcome.approved(padded, ALLOCATED_ID));
            String fromPadded = postBody(json(completeBody())).getResponse().getContentAsString();

            assertEquals(fromBare, fromPadded, "one account answers with one served form");
        }

        /**
         * An account identifier of another width is refused, and that the numeric grammar
         * reads the accepted width.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L204-L207} converts the field with {@code FUNCTION NUMVAL}
         * and moves the value back into a {@code PIC 9(11)} field.
         */
        @Test
        void anAccountIdentifierOfAnotherWidthIsRefused() throws Exception {
            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyNamingAccount("7")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.messages", Matchers.hasItem(
                            AuthorizationRequest.ACCOUNT_ID_NOT_NUMERIC_MESSAGE)));

            assertTrue(NumvalParser.isValidNumval(WORKED_EXAMPLE_ACCOUNT),
                    "the plain grammar of app/cbl/COTRN02C.cbl:L204 reads the accepted width");
            verify(authorizations, never()).authorize(any(), any());
        }

        /**
         * A decision refuses an account identifier carrying a scale.
         *
         * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} declares eleven digits
         * and no decimal position, so the numeric boundary holds whole digits alone. A scaled value
         * reaching the served eleven-digit form would carry a decimal point into the message key.
         */
        @Test
        void aDecisionRefusesAnAccountIdentifierCarryingAScale() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> AuthorizationService.Outcome.approved(new BigDecimal("7.00"),
                            ALLOCATED_ID));

            assertTrue(refusal.getMessage().contains("scale"),
                    "the refusal names the property that broke, which is the scale");
            assertEquals(0, new BigDecimal(WORKED_EXAMPLE_ACCOUNT).scale(),
                    "the accepted form of app/cpy/CVACT03Y.cpy:L7 carries no scale");
        }

        /**
         * The request carries the fourteen values the record declares and no filler.
         *
         * <p>{@code app/cpy/CVTRA06Y.cpy:L5-L17} declares thirteen fields and
         * {@code app/cpy/CVTRA06Y.cpy:L18} closes the record with twenty unused bytes. The account
         * identifier of {@code app/cpy/CVACT03Y.cpy:L7} is the fourteenth value, and the filler has no
         * counterpart.
         */
        @Test
        void theRequestCarriesFourteenValuesAndNoFiller() {
            List<String> members = new ArrayList<>();
            for (RecordComponent member : AuthorizationRequest.class.getRecordComponents()) {
                members.add(member.getName());
                assertFalse(member.getName().toLowerCase().contains("filler"),
                        "the twenty unused bytes of app/cpy/CVTRA06Y.cpy:L18 are dropped");
            }

            assertEquals(14, members.size(),
                    "thirteen fields of app/cpy/CVTRA06Y.cpy plus the account identifier of "
                            + "app/cpy/CVACT03Y.cpy:L7");
            assertTrue(members.containsAll(List.of("transactionId", "transactionTypeCode",
                            "transactionCategoryCode", "source", "description", AMOUNT_FIELD,
                            "merchantId", "merchantName", "merchantCity", "merchantZip",
                            CARD_NUMBER_FIELD, ORIGIN_FIELD, PROCESSING_FIELD, ACCOUNT_ID_FIELD)),
                    "each declared field has one request value");
        }
    }

    /**
     * The amount grammar, which is currency-tolerant and not a plain number reader.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L383-L384} and {@code app/cbl/COTRN02C.cbl:L456-L457} convert the
     * field with {@code FUNCTION NUMVAL-C}, which reads a currency sign and a grouping comma. The
     * account identifier and the card number take the plain function at
     * {@code app/cbl/COTRN02C.cbl:L204} and {@code app/cbl/COTRN02C.cbl:L218}.
     * {@code app/cbl/COACTUPC.cbl:L2201} gates a field on the currency-tolerant test ahead of its own
     * conversion, and zero marks a value the grammar reads.
     */
    @Nested
    @DisplayName("The currency-tolerant amount grammar")
    final class AmountGrammar {

        /**
         * Posts a body carrying one amount and returns the request the endpoint bound.
         *
         * @param amount the value to send
         * @return the bound request
         * @throws Exception when the exchange itself fails
         */
        private AuthorizationRequest boundRequestFor(String amount) throws Exception {
            stubApproval(ALLOCATED_ID);

            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWith(AMOUNT_FIELD, amount)))
                    .andExpect(status().isOk());

            ArgumentCaptor<AuthorizationRequest> sent =
                    ArgumentCaptor.forClass(AuthorizationRequest.class);
            verify(authorizations).authorize(sent.capture(), any());
            return sent.getValue();
        }

        @Test
        void anAmountOpeningWithACurrencySignIsRead() throws Exception {
            AuthorizationRequest bound = boundRequestFor("$504.77");

            assertEquals(new BigDecimal(WORKED_EXAMPLE_AMOUNT), bound.amountValue(),
                    "FUNCTION NUMVAL-C at app/cbl/COTRN02C.cbl:L456-L457 reads a currency sign");
            assertEquals(PicClause.DALYTRAN_AMT_SCALE, bound.amountValue().scale(),
                    "DALYTRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA06Y.cpy:L10 fixes the scale");
        }

        @Test
        void anAmountCarryingGroupingCommasIsRead() throws Exception {
            AuthorizationRequest bound = boundRequestFor("$1,234.56");

            assertEquals(new BigDecimal("1234.56"), bound.amountValue(),
                    "the currency-tolerant grammar drops a grouping comma");
            assertEquals(PicClause.DALYTRAN_AMT_SCALE, bound.amountValue().scale(),
                    "the stored scale holds at two digits");
        }

        /**
         * A negative amount is ordinary traffic.
         *
         * <p>The sign byte at position 143 of {@code app/data/ASCII/dailytran.txt} is positive in 250
         * records and negative in 50.
         */
        @Test
        void aNegativeAmountIsOrdinaryTraffic() throws Exception {
            AuthorizationRequest bound = boundRequestFor("-930.33");

            assertEquals(new BigDecimal("-930.33"), bound.amountValue(),
                    "a refund reaches this endpoint as a negative value");
            assertTrue(bound.amountValue().signum() < 0, "the sign survives the conversion");
        }

        /**
         * The four positions {@code app/cbl/COTRN02C.cbl:L339-L351} tests each refuse a
         * value.
         *
         * <p>Position 1 holds a sign at {@code app/cbl/COTRN02C.cbl:L340}, and positions 2 through 9
         * hold digits at {@code app/cbl/COTRN02C.cbl:L341}. Position 10 holds the point at
         * {@code app/cbl/COTRN02C.cbl:L342}, and positions 11 and 12 hold digits at
         * {@code app/cbl/COTRN02C.cbl:L343}.
         */
        @Test
        void eachOfTheFourTestedPositionsRefusesAValue() throws Exception {
            Map<String, String> byPosition = new LinkedHashMap<>();
            byPosition.put("*00000504.77", "position 1 holds a sign");
            byPosition.put("+000A0504.77", "positions 2 through 9 hold digits");
            byPosition.put("+00000504:77", "position 10 holds the decimal point");
            byPosition.put("+00000504.7X", "positions 11 and 12 hold digits");

            for (Map.Entry<String, String> refused : byPosition.entrySet()) {
                mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                                .content(bodyWith(AMOUNT_FIELD, refused.getKey())))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.messages", Matchers.hasItem(
                                AuthorizationRequest.AMOUNT_FORMAT_MESSAGE)));

                assertFalse(NumvalParser.isValidNumvalCurrency(refused.getKey()),
                        refused.getValue());
            }

            verify(authorizations, never()).authorize(any(), any());
        }

        /**
         * An amount too wide for the record field is refused ahead of the decision.
         *
         * <p>{@code DALYTRAN-AMT PIC S9(09)V99} holds nine digits ahead of the point. A wider value
         * would lose its high-order digit on the store.
         */
        @Test
        void anAmountTooWideForTheRecordFieldIsRefused() throws Exception {
            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWith(AMOUNT_FIELD, "1000000000.00")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.messages", Matchers.hasItem(
                            AuthorizationRequest.AMOUNT_FORMAT_MESSAGE)));

            assertEquals(9, PicClause.DALYTRAN_AMT_PRECISION - PicClause.DALYTRAN_AMT_SCALE,
                    "nine digits sit ahead of the point in app/cpy/CVTRA06Y.cpy:L10");
            verify(authorizations, never()).authorize(any(), any());
        }

        @Test
        void theAmountTakesTheTolerantPairAndTheKeysTakeThePlainPair() {
            assertTrue(NumvalParser.isValidNumvalCurrency("$1,234.56"),
                    "FUNCTION NUMVAL-C reads a currency sign and a grouping comma");
            assertFalse(NumvalParser.isValidNumval("$1,234.56"),
                    "FUNCTION NUMVAL at app/cbl/COTRN02C.cbl:L204 reads neither");
            assertTrue(NumvalParser.isValidNumval(WORKED_EXAMPLE_ACCOUNT),
                    "the account identifier takes the plain function");
            assertTrue(NumvalParser.isValidNumval(WORKED_EXAMPLE_CARD),
                    "the card number takes the plain function at app/cbl/COTRN02C.cbl:L218");
            assertEquals(new BigDecimal("1234.56"),
                    NumvalParser.numvalCurrency("$1,234.56").setScale(
                            PicClause.DALYTRAN_AMT_SCALE),
                    "the tolerant conversion yields the value the store holds");
        }
    }

    /**
     * The capture and processing dates, tested over their first ten characters alone.
     *
     * <p>{@code CSUTLDTC-DATE PIC X(10)} at {@code app/cbl/COTRN02C.cbl:L63} holds ten characters,
     * and {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} sits at
     * {@code app/cbl/COTRN02C.cbl:L60}. Reject code {@code 0103} compares the same ten at
     * {@code app/cbl/CBTRN02C.cbl:L414}. The sixteen characters that follow are never read as part of
     * a date.
     */
    @Nested
    @DisplayName("The two dates and one undocumented tolerance")
    final class CaptureDates {

        /**
         * A date the endpoint accepts only through the tolerated message number.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L397} accepts severity {@code '0000'}, and
         * {@code app/cbl/COTRN02C.cbl:L400} also accepts one message number, with no comment
         * explaining the second condition. {@code app/cbl/COTRN02C.cbl:L417} and
         * {@code app/cbl/COTRN02C.cbl:L420} repeat the pair for the processing date. A well-formed
         * date below the earliest supported day reaches that second condition.
         */
        @Test
        void aDateAcceptedOnlyThroughTheToleratedMessageNumber() throws Exception {
            String unsupportedDay = "1500-01-01";
            CobolDateValidator.DateValidationResult outcome = CobolDateValidator.validateDate(
                    unsupportedDay, CobolDateValidator.TOLERANT_POLICY_DATE_MASK);

            assertFalse(CobolDateValidator.ACCEPTED_SEVERITY_TEXT.equals(outcome.severityText()),
                    "the severity of app/cbl/COTRN02C.cbl:L397 does not accept this date");
            assertEquals(CobolDateValidator.TOLERATED_MESSAGE_NUMBER_TEXT,
                    outcome.messageNumberText(),
                    "the message number of app/cbl/COTRN02C.cbl:L400 does accept it");
            assertTrue(CobolDateValidator.isAcceptedByTolerantPolicy(outcome),
                    "the tolerant policy carries this date through");
            assertFalse(CobolDateValidator.isAcceptedByStrictPolicy(outcome),
                    "the policy holding no second condition refuses the same date");

            stubApproval(ALLOCATED_ID);
            mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWith(ORIGIN_FIELD, unsupportedDay + " 00:00:00.000000")))
                    .andExpect(status().isOk());
        }

        /**
         * The endpoint tests ten characters against the ten-character mask.
         *
         * <p>The other mask holds eight characters, compares severity as a number and carries no
         * second condition, so it belongs to a different call site.
         */
        @Test
        void theEndpointTestsTenCharactersAgainstTheTenCharacterMask() {
            assertEquals(10, CobolDateValidator.TESTED_DATE_WIDTH,
                    "CSUTLDTC-DATE PIC X(10) at app/cbl/COTRN02C.cbl:L63 holds ten characters");
            assertEquals("YYYY-MM-DD", CobolDateValidator.TOLERANT_POLICY_DATE_MASK,
                    "WS-DATE-FORMAT at app/cbl/COTRN02C.cbl:L60 holds this mask");
            assertEquals(8, CobolDateValidator.STRICT_POLICY_DATE_MASK.length(),
                    "the other mask is narrower and belongs to another call site");
            assertEquals(CAPTURE_DATE, ORIGIN_TIMESTAMP.substring(0,
                            CobolDateValidator.TESTED_DATE_WIDTH),
                    "reject code 0103 at app/cbl/CBTRN02C.cbl:L414 compares these ten characters");
        }

        /**
         * The condition named for an invalid date reports success.
         *
         * <p>{@code app/cbl/CSUTLDTC.cbl:L62} declares {@code FC-INVALID-DATE} over the all-zero
         * feedback token, and {@code app/cbl/CSUTLDTC.cbl:L128-L130} moves {@code Date is valid}
         * under that condition.
         */
        @Test
        void theConditionNamedForAnInvalidDateReportsSuccess() {
            String allZeroToken = "0000000000000000";

            assertEquals("Date is valid",
                    CobolDateValidator.resultTextForFeedbackToken(allZeroToken).strip(),
                    "app/cbl/CSUTLDTC.cbl:L128-L130 reads the all-zero token as success");
            assertTrue(CobolDateValidator.isAcceptedByTolerantPolicy(CAPTURE_DATE,
                            CobolDateValidator.TOLERANT_POLICY_DATE_MASK),
                    "the capture date of the fixtures passes the policy");
        }
    }

    /**
     * The two twenty-six character timestamp formats, which reject one another.
     *
     * <p>{@code DALYTRAN-ORIG-TS} at {@code app/cpy/CVTRA06Y.cpy:L16} holds a space at position 11.
     * {@code DALYTRAN-PROC-TS} at {@code app/cpy/CVTRA06Y.cpy:L17} holds a third dash there, declared
     * as {@code DB2-STREEP-3} inside the redefinition at {@code app/cbl/CBTRN02C.cbl:L160-L174}.
     */
    @Nested
    @DisplayName("The two timestamp formats")
    final class TimestampFormats {

        @Test
        void eachShapeRefusesTheValueOfTheOther() {
            assertFalse(AuthorizationRequest.ORIGIN_TIMESTAMP_PATTERN.equals(
                            AuthorizationRequest.PROCESSING_TIMESTAMP_PATTERN),
                    "two fields of the same width carry two shapes");
            assertTrue(ORIGIN_TIMESTAMP.matches(AuthorizationRequest.ORIGIN_TIMESTAMP_PATTERN),
                    "record 1 of app/data/ASCII/dailytran.txt carries this capture shape");
            assertFalse(ORIGIN_TIMESTAMP.matches(AuthorizationRequest.PROCESSING_TIMESTAMP_PATTERN),
                    "the capture value is not a processing value");
            assertTrue(PROCESSING_TIMESTAMP.matches(
                            AuthorizationRequest.PROCESSING_TIMESTAMP_PATTERN),
                    "the processing shape carries a third dash at position 11");
            assertFalse(PROCESSING_TIMESTAMP.matches(
                            AuthorizationRequest.ORIGIN_TIMESTAMP_PATTERN),
                    "the processing value is not a capture value");
            assertEquals(' ', ORIGIN_TIMESTAMP.charAt(10), "position 11 of the capture shape");
            assertEquals('-', PROCESSING_TIMESTAMP.charAt(10),
                    "position 11 of the processing shape");
        }

        /**
         * The processing value carries two significant fraction digits and four fixed zeros.
         *
         * <p>{@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBTRN02C.cbl:L692-L705} takes two
         * hundredths characters at {@code app/cbl/CBTRN02C.cbl:L700} and writes four zeros at
         * {@code app/cbl/CBTRN02C.cbl:L701}. A comparison of raw values would fail on each record for
         * a cause unrelated to the logic under test.
         */
        @Test
        void theProcessingValueCarriesTwoSignificantFractionDigits() {
            assertEquals(26, PROCESSING_TIMESTAMP.length(),
                    "DALYTRAN-PROC-TS PIC X(26) at app/cpy/CVTRA06Y.cpy:L17 fixes the width");
            assertTrue(PROCESSING_TIMESTAMP.endsWith("0000"),
                    "app/cbl/CBTRN02C.cbl:L701 writes four zeros into DB2-REST");
            assertEquals("00", PROCESSING_TIMESTAMP.substring(20, 22),
                    "DB2-MIL PIC 9(002) at app/cbl/CBTRN02C.cbl:L173 holds the two that count");
        }

        @Test
        void theServedBodyCarriesNoTimestamp() throws Exception {
            stubApproval(ALLOCATED_ID);

            String body = postBody(json(completeBody())).getResponse().getContentAsString();

            assertFalse(body.contains(ORIGIN_TIMESTAMP), "no served body repeats a capture moment");
            assertFalse(body.contains(PROCESSING_TIMESTAMP),
                    "no served body repeats a processing moment");
            assertFalse(body.contains(CAPTURE_DATE), "no served body repeats a capture date");
        }
    }

    /**
     * Decisions the two stores this service owns drive, end to end from one body to one status.
     *
     * <p>The chain is paragraph {@code 1500-VALIDATE-TRAN} at
     * {@code app/cbl/CBTRN02C.cbl:L370-L378}. {@code app/cbl/CBTRN02C.cbl:L372} gates the account
     * read on a reject code still holding zero, so the first two codes stop the chain. The credit
     * test at {@code app/cbl/CBTRN02C.cbl:L407} and the expiry test at
     * {@code app/cbl/CBTRN02C.cbl:L414} run one after the other with no gate between them, so the
     * later code stands.
     *
     * <p>The fixtures reach one of the four codes. Measured over all three hundred records of
     * {@code app/data/ASCII/dailytran.txt}, 287 approve and 13 exceed a credit limit, while the
     * cross-reference, account and expiry codes are reached zero times. The three unreached codes
     * therefore take constructed rows.
     */
    @Nested
    @DisplayName("Decisions the owned stores drive")
    final class StoreDrivenDecisions {

            @BeforeEach
        void standUpChain() {
            standUpDecisionPath();
        }

        /**
         * The worked example of record 1 of {@code app/data/ASCII/dailytran.txt} approves: both cycle
         * accumulators hold zero, so {@code app/cbl/CBTRN02C.cbl:L403-L405} computes an amount below
         * the credit limit and {@code :L407} accepts it.
         */
        @Test
        void theWorkedExampleApproves() throws Exception {
            resolveCard(WORKED_EXAMPLE_CARD, WORKED_EXAMPLE_CUSTOMER, WORKED_EXAMPLE_ACCOUNT);
            resolveAccount(WORKED_EXAMPLE_ACCOUNT, WORKED_EXAMPLE_CREDIT_LIMIT,
                    WORKED_EXAMPLE_EXPIRY);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approved").value(true))
                    .andExpect(jsonPath("$.accountId").value(WORKED_EXAMPLE_ACCOUNT))
                    .andExpect(jsonPath("$.transactionId").value(ALLOCATED_ID))
                    .andExpect(jsonPath("$.declineReasonCode").doesNotExist());

            assertTrue(WORKED_EXAMPLE_EXPIRY.compareTo(CAPTURE_DATE) >= 0,
                    "the expiry test of app/cbl/CBTRN02C.cbl:L414 compares two texts");
        }

        /**
         * The tightest credit limit of the fixtures declines with the credit code.
         *
         * <p>Account 30 of {@code app/data/ASCII/acctdata.txt} carries a limit of 120.00, and the
         * feed presents four amounts above it against that account.
         */
        @Test
        void theTightestCreditLimitDeclinesWithTheCreditCode() throws Exception {
            resolveCard(TIGHT_LIMIT_CARD, TIGHT_LIMIT_CUSTOMER, TIGHT_LIMIT_ACCOUNT);
            resolveAccount(TIGHT_LIMIT_ACCOUNT, TIGHT_CREDIT_LIMIT, TIGHT_LIMIT_EXPIRY);

            Map<String, String> body = completeBody();
            body.put(CARD_NUMBER_FIELD, TIGHT_LIMIT_CARD);
            body.put(AMOUNT_FIELD, OVER_LIMIT_AMOUNT);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.approved").value(false))
                    .andExpect(jsonPath("$.accountId").value(TIGHT_LIMIT_ACCOUNT))
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(DeclineReason.OVER_CREDIT_LIMIT.code()))
                    .andExpect(jsonPath("$.declineReasonDescription")
                            .value(DeclineReason.OVER_CREDIT_LIMIT.description()));
        }

        /**
         * A card no row carries declines under {@code 0100}, and the account the request declared
         * reaches no part of the response.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L385-L387} assigns reject code {@code 0100} inside the
         * invalid-key limb of a keyed read, before {@code app/cbl/CBTRN02C.cbl:L394} has any account to
         * move, and {@code :L446-L465} writes the reject record for it as for the other three reasons.
         * So the outcome is a decline and this route answers it as one.
         *
         * <p>The account on the request is what this test watches. The body carries the reject code,
         * its verbatim text and {@code accountId} as JSON null, never the declared value. Answering
         * {@code 0100} against the declared account would let a caller holding a broad role write a
         * decision, an audit row and a declined event against an account it had merely typed. The card
         * number is constructed, since the code is reached zero times over the fixtures.
         */
        @Test
        void aCardNoRowCarriesDeclinesAndNamesNoDeclaredAccount() throws Exception {
            Map<String, String> body = completeBody();
            body.put(CARD_NUMBER_FIELD, UNKNOWN_CARD);
            body.put(ACCOUNT_ID_FIELD, WORKED_EXAMPLE_ACCOUNT);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.approved").value(false))
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(DeclineReason.INVALID_CARD_NUMBER.code()))
                    .andExpect(jsonPath("$.declineReasonDescription")
                            .value(DeclineReason.INVALID_CARD_NUMBER.description()))
                    .andExpect(jsonPath("$.accountId").value(nullValue()))
                    .andExpect(jsonPath("$.messages").doesNotExist());
        }

        /**
         * A card no row carries, on a request declaring no account, declines all the same.
         *
         * <p>Nothing names an account here, and the outcome does not need one:
         * {@code transaction-declined-v2.json} is the one declined document that declares no
         * {@code accountId} property, and the identifier this service allocated is the subject the
         * decision and its event are keyed on. So the answer is the same body the declared-account case
         * receives, which is the point — the declared value never mattered.
         */
        @Test
        void aCardNoRowCarriesWithNoDeclaredAccountStillDeclines() throws Exception {
            Map<String, String> body = completeBody();
            body.put(CARD_NUMBER_FIELD, UNKNOWN_CARD);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(DeclineReason.INVALID_CARD_NUMBER.code()))
                    .andExpect(jsonPath("$.transactionId").value(ALLOCATED_ID))
                    .andExpect(jsonPath("$.accountId").value(nullValue()));
        }

        /**
         * A resolved card with no projection row declines with the account code.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L397-L399} assigns that code inside the invalid-key limb of
         * the account read. The rows are constructed, since the code is reached zero times over the
         * fixtures.
         */
        @Test
        void aResolvedCardWithNoProjectionRowDeclinesWithTheAccountCode() throws Exception {
            resolveCard(WORKED_EXAMPLE_CARD, WORKED_EXAMPLE_CUSTOMER, WORKED_EXAMPLE_ACCOUNT);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(DeclineReason.ACCOUNT_NOT_FOUND.code()))
                    .andExpect(jsonPath("$.declineReasonDescription")
                            .value(DeclineReason.ACCOUNT_NOT_FOUND.description()))
                    .andExpect(jsonPath("$.accountId").value(WORKED_EXAMPLE_ACCOUNT));
        }

        /**
         * The cross-reference miss stops the chain ahead of the account read.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L372} runs the account read only while the reject code holds
         * zero, so a card that resolves nothing never reaches it. The short-circuit is what this test
         * measures, and it is visible in the read that does not happen: the response carries reject
         * code {@code 0100} rather than {@code 0101}, and no account row is read for the account the
         * request declared.
         */
        @Test
        void theCrossReferenceMissStopsTheChainAheadOfTheAccountRead() throws Exception {
            Map<String, String> body = completeBody();
            body.put(CARD_NUMBER_FIELD, UNKNOWN_CARD);
            body.put(ACCOUNT_ID_FIELD, WORKED_EXAMPLE_ACCOUNT);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(DeclineReason.INVALID_CARD_NUMBER.code()));

            verify(accountSnapshots, never()).findForUpdateByAccountId(any());
        }

        /**
         * The expiry code stands when the credit test and the expiry test both fail.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L410} assigns the credit code and
         * {@code app/cbl/CBTRN02C.cbl:L417} assigns the expiry code, with no gate between them, so
         * the second assignment overwrites the first in the one {@code PIC 9(04)} field.
         */
        @Test
        void theExpiryCodeStandsWhenBothTestsFail() throws Exception {
            resolveCard(TIGHT_LIMIT_CARD, TIGHT_LIMIT_CUSTOMER, TIGHT_LIMIT_ACCOUNT);
            resolveAccount(TIGHT_LIMIT_ACCOUNT, TIGHT_CREDIT_LIMIT, EXPIRY_BEFORE_CAPTURE);

            Map<String, String> body = completeBody();
            body.put(CARD_NUMBER_FIELD, TIGHT_LIMIT_CARD);
            body.put(AMOUNT_FIELD, OVER_LIMIT_AMOUNT);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(DeclineReason.ACCOUNT_EXPIRED.code()))
                    .andExpect(jsonPath("$.declineReasonCode")
                            .value(Matchers.not(DeclineReason.OVER_CREDIT_LIMIT.code())));

            assertTrue(EXPIRY_BEFORE_CAPTURE.compareTo(CAPTURE_DATE) < 0,
                    "the expiry date sorts below the ten characters the capture moment opens with");
        }

        /**
         * A body naming an account alone resolves its card and approves.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L208-L209} reads the alternate index and takes the card
         * number the row carries.
         */
        @Test
        void aBodyNamingAnAccountAloneResolvesItsCard() throws Exception {
            resolveCard(WORKED_EXAMPLE_CARD, WORKED_EXAMPLE_CUSTOMER, WORKED_EXAMPLE_ACCOUNT);
            resolveAccount(WORKED_EXAMPLE_ACCOUNT, WORKED_EXAMPLE_CREDIT_LIMIT,
                    WORKED_EXAMPLE_EXPIRY);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyNamingAccount(WORKED_EXAMPLE_ACCOUNT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approved").value(true))
                    .andExpect(jsonPath("$.accountId").value(WORKED_EXAMPLE_ACCOUNT));

            verify(cardCrossReferences)
                    .findFirstByAccountIdOrderByCardNumberAsc(WORKED_EXAMPLE_ACCOUNT);
        }

        /**
         * Asserts an account identifier resolving no card is refused with the source text.
         *
         * <p>{@code app/cbl/COTRN02C.cbl:L591-L592} answers the not-found limb of that read by moving
         * {@value AuthorizationRequest#ACCOUNT_ID_NOT_FOUND_MESSAGE} into {@code WS-MESSAGE} and
         * re-sending the screen, capturing nothing. The text is a fixed constant and holds no value
         * read from the request, so the refused identifier reaches no caller.
         */
        @Test
        void anAccountIdentifierResolvingNoCardIsRefused() throws Exception {
            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyNamingAccount(WORKED_EXAMPLE_ACCOUNT)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(ApiErrorResponse.UNPROCESSABLE))
                    .andExpect(jsonPath("$.messages[0]")
                            .value(AuthorizationRequest.ACCOUNT_ID_NOT_FOUND_MESSAGE))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString(WORKED_EXAMPLE_ACCOUNT))));

            verify(identifiers, never()).nextIdentifier();
        }

        /**
         * The account finder answers with a list, since one account holds many cards.
         *
         * <p>{@code app/jcl/XREFFILE.jcl:L75} declares the alternate index {@code NONUNIQUEKEY}, so a
         * single-valued answer would not hold the rows that key returns.
         */
        @Test
        void theAccountFinderAnswersWithAList() throws Exception {
            Method finder = CardCrossReferenceRepository.class
                    .getMethod("findByAccountIdOrderByCardNumberAsc", String.class);

            assertEquals(List.class, finder.getReturnType(),
                    "app/jcl/XREFFILE.jcl:L75 declares the account key non-unique");
            assertEquals(Optional.class, CardCrossReferenceRepository.class
                            .getMethod("findByCardNumber", String.class).getReturnType(),
                    "KEYS(16 0) at app/jcl/XREFFILE.jcl:L43 keys one row per card");
        }

        /**
         * A projection whose card resolves through the endpoint answers with a served
         * exchange, so the decision path reads the two stores and nothing else.
         */
        @Test
        void theDecisionPathReadsTheTwoOwnedStores() throws Exception {
            resolveCard(WORKED_EXAMPLE_CARD, WORKED_EXAMPLE_CUSTOMER, WORKED_EXAMPLE_ACCOUNT);
            resolveAccount(WORKED_EXAMPLE_ACCOUNT, WORKED_EXAMPLE_CREDIT_LIMIT,
                    WORKED_EXAMPLE_EXPIRY);

            assertEquals(200, decide(json(completeBody())).getResponse().getStatus(),
                    "the two owned stores answer the whole decision");

            verify(cardCrossReferences).findByCardNumber(WORKED_EXAMPLE_CARD);
            verify(accountSnapshots).findForUpdateByAccountId(WORKED_EXAMPLE_ACCOUNT);
        }
    }

    /**
     * Checks a competent engineer would add and this service must not, since each one changes an
     * outcome the source produced.
     */
    @Nested
    @DisplayName("Checks deliberately not added")
    final class ChecksNotAdded {

        @BeforeEach
        void standUpChain() {
            standUpDecisionPath();
        }

        /**
         * Sixteen digits that fail a checksum still authorize.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L193-L194} names the one card test the source performs, and
         * that test reads a sixteen digit number and nothing else. All fifty cards of
         * {@code app/data/ASCII/cardxref.txt} pass the checksum, so this value is constructed.
         */
        @Test
        void sixteenDigitsFailingAChecksumStillAuthorize() throws Exception {
            resolveCard(CHECKSUM_FAILING_CARD, WORKED_EXAMPLE_CUSTOMER, WORKED_EXAMPLE_ACCOUNT);
            resolveAccount(WORKED_EXAMPLE_ACCOUNT, WORKED_EXAMPLE_CREDIT_LIMIT,
                    WORKED_EXAMPLE_EXPIRY);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWith(CARD_NUMBER_FIELD, CHECKSUM_FAILING_CARD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approved").value(true));

            assertEquals(PicClause.XREF_CARD_NUM_WIDTH, CHECKSUM_FAILING_CARD.length(),
                    "the one card test reads sixteen digits");
        }

        /**
         * A closed account still authorizes, since no status reaches the decision.
         *
         * <p>{@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6} exists, and no
         * program reads it ahead of posting. {@code CARD-ACTIVE-STATUS PIC X(01)} at
         * {@code app/cpy/CVACT02Y.cpy:L10} is unreachable from this path: the file control section at
         * {@code app/cbl/CBTRN02C.cbl:L28-L61} declares six datasets and none is the card file, and
         * {@code app/jcl/POSTTRAN.jcl} allocates none either. All fifty accounts of
         * {@code app/data/ASCII/acctdata.txt} carry an open status, so a closed one is constructed by
         * omission: the projection holds no status column to carry it.
         */
        @Test
        void aClosedAccountStillAuthorizes() throws Exception {
            for (Method accessor : AccountCreditSnapshotEntity.class.getDeclaredMethods()) {
                assertFalse(accessor.getName().toLowerCase().contains("status"),
                        "the account projection holds no status for a rule to read");
            }
            for (Method accessor : CardCrossReferenceEntity.class.getDeclaredMethods()) {
                assertFalse(accessor.getName().toLowerCase().contains("status"),
                        "the cross-reference row holds no status for a rule to read");
            }

            resolveCard(WORKED_EXAMPLE_CARD, WORKED_EXAMPLE_CUSTOMER, WORKED_EXAMPLE_ACCOUNT);
            resolveAccount(WORKED_EXAMPLE_ACCOUNT, WORKED_EXAMPLE_CREDIT_LIMIT,
                    WORKED_EXAMPLE_EXPIRY);

            decisionPath.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON)
                            .content(json(completeBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approved").value(true))
                    .andExpect(jsonPath("$.accountStatus").doesNotExist())
                    .andExpect(jsonPath("$.cardStatus").doesNotExist());
        }

        /**
         * The four published codes are the whole set, so no fifth code reaches a caller.
         *
         * <p>The reject code census over {@code app/cbl/} returns five assignments, at
         * {@code app/cbl/CBTRN02C.cbl:L385}, {@code :L397}, {@code :L410}, {@code :L417} and
         * {@code :L556}. The fifth sits inside the invalid-key limb of the account rewrite and
         * nothing reads it, so four codes are published and the fifth class of fault answers
         * {@code 503}.
         */
        @Test
        void theFourPublishedCodesAreTheWholeSet() {
            assertEquals(4, DeclineReason.values().length,
                    "app/cbl/CBTRN02C.cbl assigns four codes a caller can be told about");
            assertEquals(List.of("0100", "0101", "0102", "0103"),
                    List.of(DeclineReason.INVALID_CARD_NUMBER.code(),
                            DeclineReason.ACCOUNT_NOT_FOUND.code(),
                            DeclineReason.OVER_CREDIT_LIMIT.code(),
                            DeclineReason.ACCOUNT_EXPIRED.code()),
                    "each code is the four-digit form of its source assignment");
        }
    }

    /**
     * Invariants of {@link AuthorizationResponse}, which hold one reject code or none.
     *
     * <p>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} is one
     * field, overwritten by whichever test fails last, so one call carries one code at most.
     */
    @Nested
    @DisplayName("Invariants of the served body")
    final class ServedBodyInvariants {

        @Test
        void anApprovedBodyCarryingARejectCodeIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new AuthorizationResponse(ALLOCATED_ID, WORKED_EXAMPLE_ACCOUNT, true,
                            DeclineReason.OVER_CREDIT_LIMIT, null));

            assertTrue(refusal.getMessage().contains("declineReasonCode"),
                    "the refusal names the member that broke the invariant");
            assertTrue(refusal.getMessage().contains(DeclineReason.OVER_CREDIT_LIMIT.code()),
                    "the refusal reports the four-digit code that arrived");
        }

        @Test
        void aDeclinedBodyCarryingNoRejectCodeIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new AuthorizationResponse(ALLOCATED_ID, WORKED_EXAMPLE_ACCOUNT, false,
                            null, null));

            assertTrue(refusal.getMessage().contains("declineReasonCode"),
                    "the refusal names the member that broke the invariant");
        }

        @Test
        void theTwoFactoriesBuildTheTwoServedShapes() {
            AuthorizationResponse approved =
                    AuthorizationResponse.approve(ALLOCATED_ID, WORKED_EXAMPLE_ACCOUNT);
            AuthorizationResponse declined = AuthorizationResponse.decline(ALLOCATED_ID,
                    WORKED_EXAMPLE_ACCOUNT, DeclineReason.OVER_CREDIT_LIMIT);

            assertTrue(approved.approved(), "the approving factory sets the verdict");
            assertNull(approved.declineReasonCode(), "an approval carries no code");
            assertNull(approved.declineReasonDescription(), "an approval carries no text");

            assertFalse(declined.approved(), "the declining factory clears the verdict");
            assertEquals(DeclineReason.OVER_CREDIT_LIMIT, declined.declineReasonCode(),
                    "the declining factory holds one code");
            assertEquals(DeclineReason.OVER_CREDIT_LIMIT.description(),
                    declined.declineReasonDescription(),
                    "the text is read from the code and not restated");
        }

        @Test
        void theRenderedBodyWithholdsTheAccountIdentifier() {
            String rendered =
                    AuthorizationResponse.approve(ALLOCATED_ID, WORKED_EXAMPLE_ACCOUNT).toString();

            assertFalse(rendered.contains(WORKED_EXAMPLE_ACCOUNT),
                    "a rendered body names no account, so a log line carries none");
            assertTrue(rendered.contains(ALLOCATED_ID),
                    "a reader diagnosing one call still reads the transaction identifier");
        }
    }
}
