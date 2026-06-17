package com.carddemo.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.TransactionAddRequest;
import com.carddemo.dto.TransactionListItem;
import com.carddemo.dto.TransactionResponse;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.mapper.TransactionMapper;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardDemoConstants;
import com.carddemo.util.TranIdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link TransactionService}, the Java replacement for the three legacy
 * CICS online transaction programs of AWS CardDemo: {@code COTRN00C} (list), {@code COTRN01C}
 * (view), and {@code COTRN02C} (add). It verifies the transaction list/view/add behavioral parity
 * mandated by the migration plan (AAP &sect;0.4.1.3, &sect;0.6.5, &sect;0.7.3&nbsp;#10).
 *
 * <h2>What this test pins</h2>
 * <ol>
 *   <li><strong>Fixed page size 7 browse</strong> ({@code COTRN00C} parity). The legacy 3270 screen
 *       window of seven rows is preserved: {@link TransactionService#listTransactions(Long, int)}
 *       MUST build {@code PageRequest.of(page, 7)}. We capture the {@link Pageable} the service hands
 *       the repository with an {@link ArgumentCaptor} and assert its size is exactly
 *       {@link CardDemoConstants#PAGE_SIZE} ({@code == 7}).</li>
 *   <li><strong>Both timestamps exposed on view</strong> (the &sect;0.7.3&nbsp;#10 point). The VSAM
 *       {@code TRAN-RECORD} ({@code CVTRA05Y}) carries two {@code X(26)} timestamps,
 *       {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}. {@link TransactionService#getTransaction(String)}
 *       MUST surface <em>both</em> {@code origTs} and {@code procTs} &mdash; non-null and distinct.</li>
 *   <li><strong>Account&nbsp;ID&nbsp;XOR&nbsp;Card&nbsp;Number key rule</strong> ({@code COTRN02C}
 *       {@code VALIDATE-INPUT-KEY-FIELDS} parity). {@link TransactionService#addTransaction} requires
 *       <em>exactly one</em> of an account id or a card number; supplying both, or neither, is
 *       rejected before any persistence occurs.</li>
 *   <li><strong>16-character generated transaction id</strong> (AAP &sect;0.6.5). The online add path
 *       derives the next id from {@link TranIdGenerator} (the database-sequence replacement for the
 *       legacy {@code STARTBR}/{@code READPREV}+1 browse), left-padded to sixteen characters.</li>
 *   <li><strong>Both timestamps persisted on add</strong>. The add path stamps and saves
 *       <em>both</em> {@code origTs} and {@code procTs} on the new entity.</li>
 * </ol>
 *
 * <h2>Discrepancy resolution &mdash; the XOR key rule maps to {@link ValidationException}</h2>
 * <p>The originating agent prompt suggested the Account/Card key-rule violation should surface as a
 * {@code BusinessRuleException}. The <em>authoritative production class</em>, however, throws a
 * {@link ValidationException} for both the "both supplied" and "neither supplied" cases (and for a
 * {@code null} request). {@code ValidationException} and {@code BusinessRuleException} are
 * <em>sibling</em> types &mdash; both extend {@link RuntimeException} directly &mdash; so a
 * {@code ValidationException} is <strong>not</strong> a {@code BusinessRuleException}. In accordance
 * with the overriding directive to align the test to the real production class (and so the suite runs
 * green), this test asserts {@link ValidationException}. This is the parity-faithful expectation: the
 * COBOL program treated the missing/ambiguous key as an input-validation failure, not a downstream
 * business-rule failure.</p>
 *
 * <h2>Collaborators</h2>
 * <p>The real {@code TransactionService} constructor declares seven collaborators, all mocked here and
 * wired through {@code @InjectMocks}: {@link TransactionRepository}, {@link CardXrefRepository},
 * {@link AccountRepository}, {@link TransactionCategoryRepository}, {@link TransactionMapper},
 * {@link TranIdGenerator}, and {@link DateValidationService}. {@link TransactionCategoryRepository} is
 * the reference repository used by the add path to pre-validate that the supplied
 * {@code (typeCd, categoryCd)} pair exists in {@code transaction_category} before the INSERT &mdash;
 * guarding the {@code fk_tran_cat} foreign key (AAP &sect;0.3.1) so a non-existent pair is reported as
 * a clean HTTP&nbsp;404 rather than surfacing as an unhandled HTTP&nbsp;500.
 * {@code TransactionMapper} is a genuine constructor dependency of the
 * service (it owns the entity&harr;DTO boundary), so it must be mocked even though it is not in this
 * test's declared dependency list &mdash; otherwise {@code @InjectMocks} would inject {@code null} and
 * every service method would dereference it. Where the service delegates to the mapper, the mock is
 * given a faithful {@code thenAnswer} behaviour that mirrors the real {@code TransactionMapper}
 * (copying only the client business attributes on {@code toEntity}, and projecting all fields on
 * {@code toResponse}/{@code toListItem}), so the captured entities and responses are meaningful
 * without coupling the test to a concrete mapper instance.</p>
 *
 * <h2>Test character</h2>
 * <p>This is a <strong>pure unit test</strong>: {@code @ExtendWith(MockitoExtension.class)} with no
 * Spring context and no database. {@code MockitoExtension} runs in strict-stubbing mode &mdash; every
 * declared stub is exercised, and argument captors are used in the {@code verify} position rather than
 * during stubbing. Transaction ids are never generated in the test; the {@link TranIdGenerator} mock
 * supplies a deterministic 16-character value so the id-generation parity can be pinned precisely.</p>
 *
 * @see TransactionService
 * @see TransactionRepository
 * @see CardXrefRepository
 * @see AccountRepository
 * @see TransactionMapper
 * @see TranIdGenerator
 * @see DateValidationService
 * @see CardDemoConstants#PAGE_SIZE
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    // ---------------------------------------------------------------------------------------------
    // Fixture constants — a single, well-known transaction and its card cross-reference. Entity
    // field names mirror the authoritative Transaction entity: the amount is carried as amt
    // (BigDecimal, TRAN-AMT PIC S9(09)V99) and the category as catCd (Integer, TRAN-CAT-CD PIC 9(04)).
    // ---------------------------------------------------------------------------------------------

    /** 16-character transaction identifier ({@code TRAN-ID PIC X(16)}); the {@link Transaction} key. */
    private static final String TRAN_ID = "0000000000000001";
    /** Deterministic 16-character id the {@link TranIdGenerator} mock yields on the add path. */
    private static final String GENERATED_TRAN_ID = "0000000000000042";
    /** Owning account id ({@code TRAN-CARD-NUM} &rarr; XREF &rarr; {@code XREF-ACCT-ID PIC 9(11)}). */
    private static final Long ACCT_ID = 10L;
    /** Owning customer id ({@code XREF-CUST-ID PIC 9(09)}) carried by the cross-reference fixture. */
    private static final Long CUST_ID = 100L;
    /** 16-digit card number ({@code TRAN-CARD-NUM PIC X(16)}); links the transaction to its account. */
    private static final String CARD_NUM = "4111111111111111";
    /** Transaction type code ({@code TRAN-TYPE-CD PIC X(02)}). */
    private static final String TYPE_CD = "01";
    /** Transaction category code ({@code TRAN-CAT-CD PIC 9(04)}); an {@link Integer}, never a String. */
    private static final Integer CATEGORY_CD = 5;
    /** Transaction source channel ({@code TRAN-SOURCE PIC X(10)}). */
    private static final String SOURCE = "POS";
    /** Full transaction description ({@code TRAN-DESC PIC X(100)}; request width is the narrower BMS 60). */
    private static final String DESCRIPTION = "POS PURCHASE - GROCERY";
    /** Transaction amount ({@code TRAN-AMT PIC S9(09)V99}); scale 2 is asserted throughout. */
    private static final BigDecimal AMOUNT = new BigDecimal("123.45");
    /** Merchant identifier ({@code TRAN-MERCHANT-ID PIC 9(09)}). */
    private static final Long MERCHANT_ID = 123456789L;
    /** Merchant name ({@code TRAN-MERCHANT-NAME PIC X(50)}; request width is the narrower BMS 30). */
    private static final String MERCHANT_NAME = "ACME STORE";
    /** Merchant city ({@code TRAN-MERCHANT-CITY PIC X(50)}; request width is the narrower BMS 25). */
    private static final String MERCHANT_CITY = "ANYTOWN";
    /** Merchant postal code ({@code TRAN-MERCHANT-ZIP PIC X(10)}). */
    private static final String MERCHANT_ZIP = "12345";

    /** Origination date supplied on the add request ({@code TORIGDT}); start-of-day becomes origTs. */
    private static final LocalDate ORIG_DATE = LocalDate.of(2023, 7, 19);
    /** Processing date supplied on the add request ({@code TPROCDT}); start-of-day becomes procTs. */
    private static final LocalDate PROC_DATE = LocalDate.of(2023, 7, 20);
    /** Origination timestamp on the persisted/viewed fixture ({@code TRAN-ORIG-TS PIC X(26)}). */
    private static final LocalDateTime ORIG_TS = LocalDateTime.of(2023, 7, 19, 10, 0, 0);
    /** Processing timestamp on the persisted/viewed fixture ({@code TRAN-PROC-TS PIC X(26)}); distinct. */
    private static final LocalDateTime PROC_TS = LocalDateTime.of(2023, 7, 20, 2, 0, 0);

    /** {@code TRANSACT} data-access boundary (list/view/add) &mdash; mocked. */
    @Mock
    private TransactionRepository transactionRepository;

    /** Card&nbsp;&rarr;&nbsp;customer&nbsp;&rarr;&nbsp;account cross-reference access &mdash; mocked. */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Account existence verification for the account-key add path &mdash; mocked. */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Transaction type/category reference repository &mdash; mocked. The add path pre-validates the
     * supplied {@code (typeCd, categoryCd)} pair via {@code existsById} so a non-existent reference is
     * reported as HTTP&nbsp;404 before the INSERT trips the {@code fk_tran_cat} foreign key.
     */
    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Entity&harr;DTO boundary mapper &mdash; mocked. A genuine constructor dependency of the service;
     * the mock is given faithful {@code thenAnswer} behaviour in the tests that exercise it.
     */
    @Mock
    private TransactionMapper transactionMapper;

    /** Online-add-only 16-character transaction-id generator &mdash; mocked (never generates in tests). */
    @Mock
    private TranIdGenerator tranIdGenerator;

    /** {@code CSUTLDTC} date-validation parity service &mdash; mocked. */
    @Mock
    private DateValidationService dateValidationService;

    /** Class under test, with all seven mocks injected through its constructor. */
    @InjectMocks
    private TransactionService transactionService;

    /** A fresh, fully-populated transaction fixture rebuilt before every test for isolation. */
    private Transaction txn;

    /** A fresh card cross-reference fixture (card &harr; customer &harr; account) for the add paths. */
    private CardXref xref;

    @BeforeEach
    void setUp() {
        txn = new Transaction();
        txn.setTranId(TRAN_ID);
        txn.setTypeCd(TYPE_CD);
        txn.setCatCd(CATEGORY_CD);
        txn.setSource(SOURCE);
        txn.setDescription(DESCRIPTION);
        txn.setAmt(AMOUNT);
        txn.setMerchantId(MERCHANT_ID);
        txn.setMerchantName(MERCHANT_NAME);
        txn.setMerchantCity(MERCHANT_CITY);
        txn.setMerchantZip(MERCHANT_ZIP);
        txn.setCardNum(CARD_NUM);
        txn.setOrigTs(ORIG_TS);
        txn.setProcTs(PROC_TS);
        txn.setAcctId(ACCT_ID);

        // Convenience constructor order: (xrefCardNum, xrefCustId, xrefAcctId).
        xref = new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Faithful mapper stand-ins — mirror the real TransactionMapper so the mocked mapper produces
    // results that reflect the actual entity/request state under assertion.
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a {@link TransactionResponse} from a {@link Transaction} exactly as the real
     * {@code TransactionMapper#toResponse} does: positional, exposing <em>both</em> timestamps.
     */
    private static TransactionResponse toResponseLikeMapper(Transaction t) {
        return new TransactionResponse(
                t.getTranId(),
                t.getTypeCd(),
                t.getCatCd(),
                t.getSource(),
                t.getDescription(),
                t.getAmt(),
                t.getMerchantId(),
                t.getMerchantName(),
                t.getMerchantCity(),
                t.getMerchantZip(),
                t.getCardNum(),
                t.getOrigTs(),
                t.getProcTs());
    }

    /**
     * Builds a {@link TransactionListItem} from a {@link Transaction} exactly as the real
     * {@code TransactionMapper#toListItem} does: the date portion of the origination timestamp, the
     * full description, and the amount (null-safe on a missing {@code origTs}).
     */
    private static TransactionListItem toListItemLikeMapper(Transaction t) {
        LocalDate origDate = (t.getOrigTs() != null) ? t.getOrigTs().toLocalDate() : null;
        return new TransactionListItem(t.getTranId(), origDate, t.getDescription(), t.getAmt());
    }

    /**
     * Builds a <strong>partial</strong> {@link Transaction} from a {@link TransactionAddRequest}
     * exactly as the real {@code TransactionMapper#toEntity} does: it copies <em>only</em> the
     * client-supplied business attributes and intentionally leaves the server-owned linkage fields
     * ({@code tranId}, {@code acctId}, {@code cardNum}, {@code origTs}, {@code procTs}) for the
     * service to populate.
     */
    private static Transaction toEntityLikeMapper(TransactionAddRequest r) {
        Transaction t = new Transaction();
        t.setTypeCd(r.typeCd());
        t.setCatCd(r.categoryCd());
        t.setSource(r.source());
        t.setDescription(r.description());
        t.setAmt(r.amount());
        t.setMerchantId(r.merchantId());
        t.setMerchantName(r.merchantName());
        t.setMerchantCity(r.merchantCity());
        t.setMerchantZip(r.merchantZip());
        return t;
    }

    /**
     * Assembles a {@link TransactionAddRequest} carrying the standard business attributes plus the
     * supplied key fields. Exactly one of {@code accountId} / {@code cardNum} is expected on the
     * happy paths; both/neither are used to exercise the XOR rejection.
     */
    private static TransactionAddRequest addRequest(Long accountId, String cardNum) {
        return new TransactionAddRequest(
                accountId,
                cardNum,
                TYPE_CD,
                CATEGORY_CD,
                SOURCE,
                DESCRIPTION,
                AMOUNT,
                // origDate/procDate are now raw YYYY-MM-DD text (String) on the DTO so that
                // DateValidationService (CSUTLDTC parity) runs and can emit a field-specific message;
                // the LocalDate constants remain for the start-of-day timestamp assertions below.
                ORIG_DATE.toString(),
                PROC_DATE.toString(),
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP);
    }

    // =============================================================================================
    // listTransactions — COTRN00C parity: the fixed 7-row browse window
    // =============================================================================================

    @Test
    @DisplayName("listTransactions: pins the legacy browse window to page size 7 and projects rows")
    void listTransactionsPinsPageSizeSevenAndProjectsRows() {
        // Arrange — the repository returns a single-element page whose Pageable carries size 7.
        Page<Transaction> repoPage = new PageImpl<>(List.of(txn), PageRequest.of(0, 7), 1);
        when(transactionRepository.findByAcctIdOrderByOrigTs(eq(ACCT_ID), any(Pageable.class)))
                .thenReturn(repoPage);
        // The mapper faithfully converts the page the service built into a PageResponse of rows.
        when(transactionMapper.toPageResponse(any()))
                .thenAnswer(inv -> {
                    Page<Transaction> p = inv.getArgument(0);
                    return PageResponse.from(p, TransactionServiceTest::toListItemLikeMapper);
                });

        // Act
        PageResponse<TransactionListItem> resp = transactionService.listTransactions(ACCT_ID, 0);

        // Assert — capture the Pageable the SERVICE constructed and pin the legacy 7-row window.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByAcctIdOrderByOrigTs(eq(ACCT_ID), pageableCaptor.capture());
        Pageable usedPageable = pageableCaptor.getValue();
        assertThat(usedPageable.getPageSize())
                .as("legacy COTRN00C browse window is a fixed 7 rows per screen")
                .isEqualTo(CardDemoConstants.PAGE_SIZE)
                .isEqualTo(7);
        assertThat(usedPageable.getPageNumber()).isZero();

        // Assert — the response reflects the size-7 window and one correctly-projected row.
        assertThat(resp).isNotNull();
        assertThat(resp.size()).isEqualTo(7);
        assertThat(resp.page()).isZero();
        assertThat(resp.totalElements()).isEqualTo(1);
        assertThat(resp.content()).hasSize(1);

        TransactionListItem firstRow = resp.content().get(0);
        assertThat(firstRow.tranId()).isEqualTo(TRAN_ID);
        assertThat(firstRow.origDate())
                .as("list row date column is the date portion of TRAN-ORIG-TS")
                .isEqualTo(txn.getOrigTs().toLocalDate());
        assertThat(firstRow.description()).isEqualTo(DESCRIPTION);
        assertThat(firstRow.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(firstRow.amount().scale()).isEqualTo(2);
    }

    // =============================================================================================
    // getTransaction — COTRN01C parity: view exposes BOTH timestamps; empty/not-found guards
    // =============================================================================================

    @Test
    @DisplayName("getTransaction: exposes BOTH the origination and processing timestamps (distinct, non-null)")
    void getTransactionExposesBothTimestamps() {
        // Arrange — a found transaction carrying two distinct X(26) timestamps.
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(txn));
        when(transactionMapper.toResponse(any(Transaction.class)))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        // Act
        TransactionResponse resp = transactionService.getTransaction(TRAN_ID);

        // Assert — both timestamps surface, are non-null, and are distinct (AAP §0.7.3 #10).
        assertThat(resp).isNotNull();
        assertThat(resp.origTs()).as("TRAN-ORIG-TS must be exposed on view").isNotNull().isEqualTo(ORIG_TS);
        assertThat(resp.procTs()).as("TRAN-PROC-TS must be exposed on view").isNotNull().isEqualTo(PROC_TS);
        assertThat(resp.origTs())
                .as("the two timestamps are independent fields, not a single value")
                .isNotEqualTo(resp.procTs());

        // Assert — the category code is the Integer value and the amount keeps scale 2.
        assertThat(resp.categoryCd()).isEqualTo(CATEGORY_CD);
        assertThat(resp.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(resp.amount().scale()).isEqualTo(2);
        assertThat(resp.tranId()).isEqualTo(TRAN_ID);
        assertThat(resp.cardNum()).isEqualTo(CARD_NUM);
    }

    @Test
    @DisplayName("getTransaction: a blank id is rejected (COTRN01C 'Tran ID can NOT be empty...') before any lookup")
    void getTransactionBlankIdThrowsValidationException() {
        assertThatThrownBy(() -> transactionService.getTransaction("   "))
                .isInstanceOf(ValidationException.class);

        // The empty-key guard short-circuits before the repository or mapper is consulted.
        verifyNoInteractions(transactionRepository, transactionMapper);
    }

    @Test
    @DisplayName("getTransaction: a null id is rejected before any lookup")
    void getTransactionNullIdThrowsValidationException() {
        assertThatThrownBy(() -> transactionService.getTransaction(null))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(transactionRepository, transactionMapper);
    }

    @Test
    @DisplayName("getTransaction: an unknown id raises ResourceNotFoundException and never touches the mapper")
    void getTransactionNotFoundThrowsResourceNotFoundException() {
        when(transactionRepository.findById("9999999999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransaction("9999999999999999"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction")
                .hasMessageContaining("9999999999999999");

        // The not-found path never reaches the entity->DTO mapping.
        verifyNoInteractions(transactionMapper);
    }

    // =============================================================================================
    // addTransaction — COTRN02C parity: Account ID XOR Card Number key rule (rejections)
    // =============================================================================================

    @Test
    @DisplayName("addTransaction: supplying BOTH account id and card number violates the XOR rule (ValidationException, no save)")
    void addTransactionBothKeysThrowsValidationException() {
        TransactionAddRequest req = addRequest(ACCT_ID, CARD_NUM);

        assertThatThrownBy(() -> transactionService.addTransaction(req))
                .isInstanceOf(ValidationException.class);

        // The XOR guard short-circuits before any collaboration or persistence.
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(cardXrefRepository, accountRepository, tranIdGenerator,
                transactionCategoryRepository, transactionMapper, dateValidationService);
    }

    @Test
    @DisplayName("addTransaction: supplying NEITHER account id nor card number violates the XOR rule (ValidationException, no save)")
    void addTransactionNeitherKeyThrowsValidationException() {
        TransactionAddRequest req = addRequest(null, null);

        assertThatThrownBy(() -> transactionService.addTransaction(req))
                .isInstanceOf(ValidationException.class);

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(cardXrefRepository, accountRepository, tranIdGenerator,
                transactionCategoryRepository, transactionMapper, dateValidationService);
    }

    @Test
    @DisplayName("addTransaction: a null request is rejected with ValidationException and no persistence")
    void addTransactionNullRequestThrowsValidationException() {
        assertThatThrownBy(() -> transactionService.addTransaction(null))
                .isInstanceOf(ValidationException.class);

        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(cardXrefRepository, accountRepository, tranIdGenerator,
                transactionCategoryRepository, transactionMapper, dateValidationService);
    }

    // =============================================================================================
    // addTransaction — COTRN02C parity: happy paths (id generation + dual timestamps + key resolution)
    // =============================================================================================

    @Test
    @DisplayName("addTransaction (account key): resolves the card via XREF, generates a 16-char id, and persists BOTH timestamps")
    void addTransactionAccountKeyPathGeneratesIdAndPersistsBothTimestamps() {
        // Arrange — account-key path: confirm the account exists, then resolve a representative card.
        TransactionAddRequest req = addRequest(ACCT_ID, null);
        when(accountRepository.existsById(ACCT_ID)).thenReturn(true);
        Page<CardXref> xrefPage = new PageImpl<>(List.of(xref), PageRequest.of(0, 1), 1);
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage);
        // The (typeCd, categoryCd) reference exists, so Step 2.5 passes and the add proceeds.
        when(transactionCategoryRepository.existsById(any())).thenReturn(true);
        // Dates pass through DateValidationService (CSUTLDTC parity), echoing the parsed calendar date.
        when(dateValidationService.validateAndParseDate(anyString(), anyString()))
                .thenAnswer(inv -> LocalDate.parse(inv.getArgument(0)));
        // Mapper copies only client attributes; the service stamps the server-owned fields.
        when(transactionMapper.toEntity(any(TransactionAddRequest.class)))
                .thenAnswer(inv -> toEntityLikeMapper(inv.getArgument(0)));
        when(tranIdGenerator.generateTransactionId()).thenReturn(GENERATED_TRAN_ID);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionMapper.toResponse(any(Transaction.class)))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        // Act
        TransactionResponse resp = transactionService.addTransaction(req);

        // Assert — capture the exact entity handed to save() and pin the add-path parity behaviours.
        ArgumentCaptor<Transaction> savedCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(savedCaptor.capture());
        Transaction saved = savedCaptor.getValue();

        // 16-character generated id (AAP §0.6.5) — the test never generates ids itself.
        assertThat(saved.getTranId()).isEqualTo(GENERATED_TRAN_ID);
        assertThat(saved.getTranId()).as("TRAN-ID is X(16)").hasSize(16);
        // BOTH timestamps persisted (AAP §0.7.3 #10), non-null and distinct.
        assertThat(saved.getOrigTs())
                .as("origTs persisted from the validated origination date")
                .isNotNull().isEqualTo(ORIG_DATE.atStartOfDay());
        assertThat(saved.getProcTs())
                .as("procTs persisted from the validated processing date")
                .isNotNull().isEqualTo(PROC_DATE.atStartOfDay());
        assertThat(saved.getOrigTs()).isNotEqualTo(saved.getProcTs());
        // Linkage and money/category fidelity.
        assertThat(saved.getAcctId()).isEqualTo(ACCT_ID);
        assertThat(saved.getCardNum())
                .as("card number resolved from the cross-reference for the account key")
                .isEqualTo(CARD_NUM);
        assertThat(saved.getAmt()).isEqualByComparingTo(AMOUNT);
        assertThat(saved.getAmt().scale()).isEqualTo(2);
        assertThat(saved.getCatCd()).isEqualTo(CATEGORY_CD);

        // The generator was used once; the date validator was used for BOTH dates.
        verify(tranIdGenerator).generateTransactionId();
        verify(dateValidationService, times(2)).validateAndParseDate(anyString(), anyString());
        // The account-key path confirms existence and does NOT read the card-number cross-reference.
        verify(accountRepository).existsById(ACCT_ID);
        verify(cardXrefRepository, never()).findByXrefCardNum(anyString());

        // The returned DTO reflects the persisted entity (both timestamps, scale-2 amount).
        assertThat(resp).isNotNull();
        assertThat(resp.tranId()).isEqualTo(GENERATED_TRAN_ID);
        assertThat(resp.origTs()).isEqualTo(ORIG_DATE.atStartOfDay());
        assertThat(resp.procTs()).isEqualTo(PROC_DATE.atStartOfDay());
        assertThat(resp.amount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("addTransaction (card key): resolves the owning account via XREF and uses it on the persisted record")
    void addTransactionCardKeyPathResolvesAccountFromXref() {
        // Arrange — card-key path: READ CCXREF by card number yields the owning account id.
        TransactionAddRequest req = addRequest(null, CARD_NUM);
        when(cardXrefRepository.findByXrefCardNum(CARD_NUM)).thenReturn(Optional.of(xref));
        // The (typeCd, categoryCd) reference exists, so Step 2.5 passes and the add proceeds.
        when(transactionCategoryRepository.existsById(any())).thenReturn(true);
        when(dateValidationService.validateAndParseDate(anyString(), anyString()))
                .thenAnswer(inv -> LocalDate.parse(inv.getArgument(0)));
        when(transactionMapper.toEntity(any(TransactionAddRequest.class)))
                .thenAnswer(inv -> toEntityLikeMapper(inv.getArgument(0)));
        when(tranIdGenerator.generateTransactionId()).thenReturn(GENERATED_TRAN_ID);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionMapper.toResponse(any(Transaction.class)))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0)));

        // Act
        TransactionResponse resp = transactionService.addTransaction(req);

        // Assert — the account id resolved from the cross-reference is stamped onto the saved record.
        ArgumentCaptor<Transaction> savedCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(savedCaptor.capture());
        Transaction saved = savedCaptor.getValue();
        assertThat(saved.getAcctId())
                .as("account id resolved from XREF-ACCT-ID on the card key path")
                .isEqualTo(ACCT_ID);
        assertThat(saved.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(saved.getTranId()).isEqualTo(GENERATED_TRAN_ID);
        assertThat(saved.getTranId()).hasSize(16);
        assertThat(saved.getOrigTs()).isNotNull();
        assertThat(saved.getProcTs()).isNotNull();

        // The card-key path reads the cross-reference by card number and never probes account existence.
        verify(cardXrefRepository).findByXrefCardNum(CARD_NUM);
        verifyNoInteractions(accountRepository);

        assertThat(resp.tranId()).isEqualTo(GENERATED_TRAN_ID);
    }

    @Test
    @DisplayName("addTransaction: an invalid date from DateValidationService propagates as ValidationException (no id, no save)")
    void addTransactionInvalidDatePropagatesValidationException() {
        // Arrange — a single, valid key (account path) so resolution succeeds and date validation runs.
        TransactionAddRequest req = addRequest(ACCT_ID, null);
        when(accountRepository.existsById(ACCT_ID)).thenReturn(true);
        Page<CardXref> xrefPage = new PageImpl<>(List.of(xref), PageRequest.of(0, 1), 1);
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage);
        // The (typeCd, categoryCd) reference exists: Step 2.5 passes so the flow reaches date validation
        // (Step 3), proving the date check is still live and runs after the category guard.
        when(transactionCategoryRepository.existsById(any())).thenReturn(true);
        // CSUTLDTC parity: a malformed/invalid calendar date is reported as a ValidationException.
        when(dateValidationService.validateAndParseDate(anyString(), anyString()))
                .thenThrow(new ValidationException("Orig Date is not a valid date"));

        // Act + Assert — the service delegates date validation and propagates the failure verbatim.
        assertThatThrownBy(() -> transactionService.addTransaction(req))
                .isInstanceOf(ValidationException.class);

        // Once date validation fails, no id is generated and nothing is persisted.
        verify(tranIdGenerator, never()).generateTransactionId();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("addTransaction: a non-existent (typeCd, categoryCd) pair is rejected with ResourceNotFoundException (404 parity) before date validation or save")
    void addTransactionUnknownTypeCategoryThrowsResourceNotFoundException() {
        // Arrange — a single valid key (account path) so key resolution succeeds and the flow reaches
        // the Step 2.5 (typeCd, categoryCd) reference guard that protects the fk_tran_cat foreign key.
        TransactionAddRequest req = addRequest(ACCT_ID, null);
        when(accountRepository.existsById(ACCT_ID)).thenReturn(true);
        Page<CardXref> xrefPage = new PageImpl<>(List.of(xref), PageRequest.of(0, 1), 1);
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage);
        // The supplied type/category pair does NOT exist in transaction_category.
        when(transactionCategoryRepository.existsById(any())).thenReturn(false);

        // Act + Assert — a missing reference is reported as a clean ResourceNotFoundException (HTTP 404),
        // mirroring the account/card not-found paths, rather than letting the fk_tran_cat foreign key
        // trip at flush and surface as HTTP 500 (QA CKPT-2 Critical #1; AAP §0.3.1 / §0.6.1).
        assertThatThrownBy(() -> transactionService.addTransaction(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction type/category");

        // The reference guard fires AFTER key resolution but BEFORE date validation, id generation,
        // mapping, and persistence — proving the 404 short-circuit never reaches the INSERT.
        verify(transactionCategoryRepository).existsById(any());
        verifyNoInteractions(dateValidationService, tranIdGenerator, transactionMapper);
        verify(transactionRepository, never()).save(any());
    }
}

