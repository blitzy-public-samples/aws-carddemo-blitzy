/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.aws.carddemo.common.util.IdGenerator;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link TransactionService}, the Java
 * re-platform of the three CardDemo online transaction programs (relocated under
 * {@code legacy/cbl/}): {@code COTRN00C} (list, {@code CT00}), {@code COTRN01C}
 * (view, {@code CT01}) and {@code COTRN02C} (add, {@code CT02}).
 *
 * <p>The service's three collaborators &mdash; {@link TransactionRepository},
 * {@link CardXrefRepository} and {@link DateValidationService} &mdash; are mocked so
 * the tests exercise the service's own behavior in isolation: no Spring context, no
 * database, no Testcontainers. They lock down the behavioral-parity contract the
 * migration must preserve exactly (AAP &sect;0.9.2 field-contract parity,
 * &sect;0.7.1&nbsp;H5 id-generation parity, and &sect;0.8.3 "preserve public/observable
 * contracts"):</p>
 * <ul>
 *   <li><strong>Transaction-id generation</strong> &mdash; {@code COTRN02C}
 *       {@code ADD-TRANSACTION} derives the next id by a reverse browse from
 *       {@code HIGH-VALUES} ({@code MOVE HIGH-VALUES} &rarr; {@code STARTBR} &rarr;
 *       {@code READPREV} &rarr; {@code ENDBR}) then {@code ADD 1}, zero-padded to
 *       {@value com.aws.carddemo.common.util.IdGenerator#TRAN_ID_LENGTH} digits. The
 *       migration expresses this as a max-key lookup plus one:
 *       {@link TransactionRepository#findMaxTranId()} feeds the pure static utility
 *       {@link IdGenerator#nextTransactionId(String)}. Because {@code IdGenerator} is a
 *       {@code final} class of {@code static} methods it is <em>not</em> mocked; instead
 *       the max-id lookup is stubbed and the <em>real</em> generated id is asserted.</li>
 *   <li><strong>Short-circuit edit order</strong> &mdash; the key-field edits, the eleven
 *       data-field empty edits, the numeric/format edits and the calendar-validity edits
 *       run in the exact COBOL order, each throwing on the <em>first</em> failure with the
 *       verbatim {@code WS-MESSAGE} literal.</li>
 *   <li><strong>Monetary fidelity</strong> &mdash; the amount is modeled with
 *       {@link BigDecimal} at scale&nbsp;2; assertions use {@code isEqualByComparingTo}
 *       and check the scale, never {@code double}/{@code float}.</li>
 *   <li><strong>Timestamp fidelity</strong> &mdash; {@code COTRN02C} stores the entered
 *       {@code YYYY-MM-DD} origination/processing dates verbatim into the {@code X(26)}
 *       timestamp fields (no time-of-day synthesis), so the persisted {@code origTs} and
 *       {@code procTs} are asserted equal to the submitted dates.</li>
 * </ul>
 *
 * <h2>Construction</h2>
 * {@code TransactionService} uses constructor injection; the system under test is built
 * explicitly in {@link #setUp()} in the authored collaborator order
 * ({@code transactionRepository}, {@code cardXrefRepository}, {@code dateValidationService}).
 *
 * <h2>Mockito strictness</h2>
 * The default {@code STRICT_STUBS} of {@link MockitoExtension} is used, so each test stubs
 * only the collaborators its code path actually reaches; the key-resolution and edit
 * short-circuit paths therefore assert {@code save}-free behavior with
 * {@code verifyNoInteractions}/{@code never().saveAndFlush(...)} rather than stubbing
 * unreached calls. The persist path uses {@code saveAndFlush} (the authored insert verb).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService — COTRN00C/01C/02C list / view / add parity")
public class TransactionServiceTest {

    /** A representative 16-digit card number used across the add/list/view fixtures. */
    private static final String VALID_CARD = "4111111111111111";

    /** A representative numeric account id supplied on the key-field of the add path. */
    private static final String VALID_ACCOUNT = "11";

    /** {@link #VALID_ACCOUNT} as the {@code long} the service parses it to for the xref lookup. */
    private static final long VALID_ACCOUNT_LONG = 11L;

    /** The only date mask the service passes to {@link DateValidationService}. */
    private static final String DATE_FMT = "YYYY-MM-DD";

    /** A structurally valid origination date ({@code YYYY-MM-DD}). */
    private static final String ORIG_DATE = "2024-01-15";

    /** A structurally valid, distinct processing date so an orig/proc swap would be caught. */
    private static final String PROC_DATE = "2024-01-16";

    /** A structurally valid signed amount ({@code [+-]99999999.99}, exactly 12 chars) worth 100.00. */
    private static final String VALID_AMOUNT = "+00000100.00";

    /** Mocked posted-transaction master repository ({@code TRANSACT.VSAM.KSDS}). */
    @Mock
    private TransactionRepository transactionRepository;

    /** Mocked card cross-reference repository ({@code CARDXREF.VSAM.KSDS} + account index). */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Mocked date-validation service ({@code CSUTLDTC} re-platform). */
    @Mock
    private DateValidationService dateValidationService;

    /** System under test, built in {@link #setUp()} in the authored constructor order. */
    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(transactionRepository, cardXrefRepository, dateValidationService);
    }

    // ------------------------------------------------------------------------
    // A. listTransactions(String, Pageable) — COTRN00C card-scoped browse
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions(card, pageable) returns the card-scoped page in the repository's (processing-timestamp) order")
    void listTransactionsReturnsCardScopedPageInChronologicalOrder() {
        Pageable pageable = PageRequest.of(0, 10);
        Transaction t1 = tran("0000000000000001", "2024-01-01-00.00.00.000000");
        Transaction t2 = tran("0000000000000002", "2024-01-02-00.00.00.000000");
        Transaction t3 = tran("0000000000000003", "2024-01-03-00.00.00.000000");
        List<Transaction> ordered = List.of(t1, t2, t3);
        when(transactionRepository.findByCardNumOrderByProcTsAscTranIdAsc(VALID_CARD, pageable))
                .thenReturn(new PageImpl<>(ordered, pageable, ordered.size()));

        Page<Transaction> result = service.listTransactions(VALID_CARD, pageable);

        assertThat(result.getContent()).containsExactly(t1, t2, t3);
        assertThat(result.getTotalElements()).isEqualTo(3L);
        verify(transactionRepository).findByCardNumOrderByProcTsAscTranIdAsc(VALID_CARD, pageable);
    }

    @Test
    @DisplayName("listTransactions(null card, pageable) returns an empty page without querying")
    void listTransactionsReturnsEmptyPageForNullCardWithoutQuerying() {
        Page<Transaction> result = service.listTransactions(null, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("listTransactions(blank card, pageable) returns an empty page without querying")
    void listTransactionsReturnsEmptyPageForBlankCardWithoutQuerying() {
        Page<Transaction> result = service.listTransactions("   ", PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("listTransactions(card, null) treats a null pageable as unpaged")
    void listTransactionsTreatsNullPageableAsUnpaged() {
        when(transactionRepository.findByCardNumOrderByProcTsAscTranIdAsc(VALID_CARD, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.<Transaction>of()));

        service.listTransactions(VALID_CARD, null);

        verify(transactionRepository).findByCardNumOrderByProcTsAscTranIdAsc(VALID_CARD, Pageable.unpaged());
    }

    // ------------------------------------------------------------------------
    // B. listTransactions(Pageable) — COTRN00C primary-key (tran_id) browse
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("listTransactions(pageable) browses all rows in ascending tran_id order (COTRN00C key browse)")
    void listTransactionsAllUsesAscendingTranIdOrder() {
        Pageable requested = PageRequest.of(0, 5);
        Transaction t1 = tran("0000000000000001", "2024-01-01-00.00.00.000000");
        List<Transaction> content = List.of(t1);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, requested, content.size()));

        Page<Transaction> result = service.listTransactions(requested);

        assertThat(result.getContent()).containsExactly(t1);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(pageableCaptor.capture());
        Pageable used = pageableCaptor.getValue();
        assertThat(used.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "tranId"));
        assertThat(used.getPageNumber()).isEqualTo(0);
        assertThat(used.getPageSize()).isEqualTo(5);
    }

    // ------------------------------------------------------------------------
    // C. viewTransaction(String) — COTRN01C keyed read
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("viewTransaction returns the matching record; amount is a scale-2 BigDecimal")
    void viewTransactionReturnsMatchingRecord() {
        Transaction tran = new Transaction(
                "0000000000000005", "05", 2, "POS", "Coffee shop",
                new BigDecimal("100.00"), 987654321L, "Cafe Java", "Seattle", "98101",
                VALID_CARD, ORIG_DATE, PROC_DATE);
        when(transactionRepository.findById("0000000000000005")).thenReturn(Optional.of(tran));

        Transaction result = service.viewTransaction("0000000000000005");

        assertThat(result.getTranId()).isEqualTo("0000000000000005");
        assertThat(result.getCardNum()).isEqualTo(VALID_CARD);
        assertThat(result.getTranAmt()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("viewTransaction throws RecordNotFoundException with the verbatim COBOL NOTFND message")
    void viewTransactionThrowsWhenNotFound() {
        when(transactionRepository.findById("9999999999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewTransaction("9999999999999999"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Transaction ID NOT found...");
    }

    @Test
    @DisplayName("viewTransaction rejects a blank id before any read")
    void viewTransactionRejectsBlankId() {
        assertThatThrownBy(() -> service.viewTransaction("   "))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Tran ID can NOT be empty...");

        verifyNoInteractions(transactionRepository);
    }

    // ------------------------------------------------------------------------
    // D. addTransaction — key-field resolution (COTRN02C VALIDATE-INPUT-KEY-FIELDS)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("addTransaction rejects a non-numeric account id before touching any collaborator")
    void addTransactionRejectsNonNumericAccountId() {
        TransactionService.AddTransactionCommand command = new TransactionService.AddTransactionCommand(
                "12A45", null, "05", "0002", "POS", "Desc", VALID_AMOUNT,
                ORIG_DATE, PROC_DATE, "1", "M", "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Account ID must be Numeric...");

        verifyNoInteractions(transactionRepository, cardXrefRepository, dateValidationService);
    }

    @Test
    @DisplayName("addTransaction rejects a non-numeric card number before touching any collaborator")
    void addTransactionRejectsNonNumericCardNumber() {
        TransactionService.AddTransactionCommand command = new TransactionService.AddTransactionCommand(
                null, "4111-XXXX", "05", "0002", "POS", "Desc", VALID_AMOUNT,
                ORIG_DATE, PROC_DATE, "1", "M", "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Card Number must be Numeric...");

        verifyNoInteractions(transactionRepository, cardXrefRepository, dateValidationService);
    }

    @Test
    @DisplayName("addTransaction rejects a submission with neither key entered")
    void addTransactionRejectsWhenNeitherKeyEntered() {
        TransactionService.AddTransactionCommand command = new TransactionService.AddTransactionCommand(
                null, "   ", "05", "0002", "POS", "Desc", VALID_AMOUNT,
                ORIG_DATE, PROC_DATE, "1", "M", "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Account or Card Number must be entered...");

        verifyNoInteractions(transactionRepository, cardXrefRepository, dateValidationService);
    }

    @Test
    @DisplayName("addTransaction throws RecordNotFoundException when the account key has no cross-reference")
    void addTransactionThrowsWhenAccountKeyHasNoCrossReference() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addTransaction(validAccountKeyedCommand()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account ID NOT found...");

        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("addTransaction throws RecordNotFoundException when the card key has no cross-reference")
    void addTransactionThrowsWhenCardKeyHasNoCrossReference() {
        when(cardXrefRepository.findById(VALID_CARD)).thenReturn(Optional.empty());
        TransactionService.AddTransactionCommand command = new TransactionService.AddTransactionCommand(
                null, VALID_CARD, "05", "0002", "POS", "Desc", VALID_AMOUNT,
                ORIG_DATE, PROC_DATE, "1", "M", "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Card Number NOT found...");

        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(dateValidationService);
    }

    // ------------------------------------------------------------------------
    // E. addTransaction — data-field empty edits (short-circuit order, 11 fields)
    // ------------------------------------------------------------------------

    /**
     * One case per data field, each valid up to the field under test which is blanked; the
     * first (and only) empty field must produce the verbatim {@code "<label> can NOT be
     * empty..."} message in the authored COBOL order.
     *
     * @return {@code (expectedMessage, command)} pairs for the short-circuit empty edits
     */
    static Stream<Arguments> emptyFieldCases() {
        return Stream.of(
                Arguments.of("Type CD can NOT be empty...",
                        accountKeyed("", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Category CD can NOT be empty...",
                        accountKeyed("05", "", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Source can NOT be empty...",
                        accountKeyed("05", "0002", "", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Description can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Amount can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "Desc", "", ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Orig Date can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, "", PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Proc Date can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, "", "1", "M", "C", "98101")),
                Arguments.of("Merchant ID can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "", "M", "C", "98101")),
                Arguments.of("Merchant Name can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "", "C", "98101")),
                Arguments.of("Merchant City can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "", "98101")),
                Arguments.of("Merchant Zip can NOT be empty...",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "C", "")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("emptyFieldCases")
    @DisplayName("addTransaction stops at the first empty data field with the verbatim COBOL message")
    void addTransactionEmptyFieldShortCircuits(String expectedMessage,
                                               TransactionService.AddTransactionCommand command) {
        // The key resolves first, so the data-field edit is reached; the empty field then wins.
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage(expectedMessage);

        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(dateValidationService);
    }

    // ------------------------------------------------------------------------
    // F. addTransaction — numeric / positional-format edits (before date validity)
    // ------------------------------------------------------------------------

    /**
     * Cases that fail a numeric or positional-format edit; none of these reach the
     * calendar-validity ({@link DateValidationService}) step, so no date stub is needed.
     *
     * @return {@code (expectedMessage, command)} pairs for the numeric/format edits
     */
    static Stream<Arguments> numericAndFormatCases() {
        return Stream.of(
                Arguments.of("Type CD must be Numeric...",
                        accountKeyed("0A", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Category CD must be Numeric...",
                        accountKeyed("05", "00X2", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Amount should be in format -99999999.99",
                        accountKeyed("05", "0002", "POS", "Desc", "100.00", ORIG_DATE, PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Orig Date should be in format YYYY-MM-DD",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, "2024/01/15", PROC_DATE, "1", "M", "C", "98101")),
                Arguments.of("Proc Date should be in format YYYY-MM-DD",
                        accountKeyed("05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, "2024/01/16", "1", "M", "C", "98101")));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("numericAndFormatCases")
    @DisplayName("addTransaction rejects numeric/format-edit failures with the verbatim COBOL message")
    void addTransactionNumericAndFormatEdits(String expectedMessage,
                                             TransactionService.AddTransactionCommand command) {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage(expectedMessage);

        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("addTransaction rejects a non-numeric merchant id (last edit, after date validity)")
    void addTransactionRejectsNonNumericMerchantId() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));
        when(dateValidationService.isValid(ORIG_DATE, DATE_FMT)).thenReturn(true);
        when(dateValidationService.isValid(PROC_DATE, DATE_FMT)).thenReturn(true);
        TransactionService.AddTransactionCommand command = accountKeyed(
                "05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE, "12A45", "M", "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Merchant ID must be Numeric...");

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addTransaction rejects a structurally valid but non-calendar origination date")
    void addTransactionRejectsInvalidOrigDate() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));
        when(dateValidationService.isValid("2024-02-30", DATE_FMT)).thenReturn(false);
        TransactionService.AddTransactionCommand command = accountKeyed(
                "05", "0002", "POS", "Desc", VALID_AMOUNT, "2024-02-30", PROC_DATE, "1", "M", "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Orig Date - Not a valid date...");

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------------
    // G. addTransaction — successful add, id generation and amount (COTRN02C ADD-TRANSACTION)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("addTransaction on an empty table generates id 0000000000000001 and persists every field")
    void addTransactionGeneratesFirstIdAndPersistsAllFieldsForEmptyTable() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));
        when(dateValidationService.isValid(ORIG_DATE, DATE_FMT)).thenReturn(true);
        when(dateValidationService.isValid(PROC_DATE, DATE_FMT)).thenReturn(true);
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Transaction.class));

        Transaction result = service.addTransaction(validAccountKeyedCommand());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getTranId()).isEqualTo("0000000000000001").hasSize(IdGenerator.TRAN_ID_LENGTH);
        // The card number is resolved from the cross-reference, not the entered account id.
        assertThat(saved.getCardNum()).isEqualTo(VALID_CARD);
        assertThat(saved.getTypeCd()).isEqualTo("05");
        assertThat(saved.getCatCd()).isEqualTo(2);
        assertThat(saved.getTranSource()).isEqualTo("POS");
        assertThat(saved.getTranDesc()).isEqualTo("Coffee shop");
        assertThat(saved.getTranAmt()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(saved.getTranAmt().scale()).isEqualTo(2);
        assertThat(saved.getTranMerchantId()).isEqualTo(987654321L);
        assertThat(saved.getTranMerchantName()).isEqualTo("Cafe Java");
        assertThat(saved.getTranMerchantCity()).isEqualTo("Seattle");
        assertThat(saved.getTranMerchantZip()).isEqualTo("98101");
        // The entered dates are stored verbatim into the timestamp fields (no synthesis).
        assertThat(saved.getOrigTs()).isEqualTo(ORIG_DATE);
        assertThat(saved.getProcTs()).isEqualTo(PROC_DATE);
        assertThat(result).isSameAs(saved);
    }

    /**
     * The parity-critical id-generation cases: given the current maximum id (or none), the
     * next id is that maximum plus one, zero-padded to sixteen digits. {@code null} models
     * the empty-table {@code READPREV}/{@code ENDFILE} path.
     *
     * @return {@code (currentMaxId, expectedNextId)} pairs
     */
    static Stream<Arguments> tranIdGenerationCases() {
        return Stream.of(
                Arguments.of(null, "0000000000000001"),
                Arguments.of("0000000000000311", "0000000000000312"),
                Arguments.of("0000000000000009", "0000000000000010"));
    }

    @ParameterizedTest(name = "[{index}] max={0} -> id={1}")
    @MethodSource("tranIdGenerationCases")
    @DisplayName("addTransaction derives the next id from the current maximum (COTRN02C reverse-browse + 1)")
    void addTransactionGeneratesNextIdFromCurrentMax(String currentMaxId, String expectedId) {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));
        when(dateValidationService.isValid(ORIG_DATE, DATE_FMT)).thenReturn(true);
        when(dateValidationService.isValid(PROC_DATE, DATE_FMT)).thenReturn(true);
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.ofNullable(currentMaxId));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Transaction.class));

        Transaction result = service.addTransaction(validAccountKeyedCommand());

        assertThat(result.getTranId()).isEqualTo(expectedId).hasSize(IdGenerator.TRAN_ID_LENGTH);
    }

    @Test
    @DisplayName("addTransaction via a card-number key normalizes the key to 16 zero-padded digits")
    void addTransactionResolvesAndNormalizesCardNumberKey() {
        String enteredCard = "12345";
        String normalizedCard = "0000000000012345";
        when(cardXrefRepository.findById(normalizedCard))
                .thenReturn(Optional.of(new CardXref(normalizedCard, 1L, VALID_ACCOUNT_LONG)));
        when(dateValidationService.isValid(ORIG_DATE, DATE_FMT)).thenReturn(true);
        when(dateValidationService.isValid(PROC_DATE, DATE_FMT)).thenReturn(true);
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.of("0000000000000100"));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Transaction.class));

        TransactionService.AddTransactionCommand command = new TransactionService.AddTransactionCommand(
                null, enteredCard, "05", "0002", "POS", "Coffee shop", VALID_AMOUNT,
                ORIG_DATE, PROC_DATE, "987654321", "Cafe Java", "Seattle", "98101");

        Transaction result = service.addTransaction(command);

        assertThat(result.getCardNum()).isEqualTo(normalizedCard);
        assertThat(result.getTranId()).isEqualTo("0000000000000101");
    }

    // ------------------------------------------------------------------------
    // H. addTransaction — field-length edits and narrowed data-integrity mapping
    //    (QA MAJOR-2: overlength fields must be rejected as validation errors, and
    //    only a genuine duplicate tran_id may be reported as a DuplicateKeyException;
    //    QA CRITICAL-1: the eager INSERT surfaces a real key collision here.)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("addTransaction rejects a 101-character description as a field-length validation error, never a duplicate-key error")
    void addTransactionRejectsOverlengthDescriptionAsValidationError() {
        // The key resolves so the data-field edits are reached; only the cross-reference
        // lookup is stubbed (STRICT_STUBS: the persist path is never reached).
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));

        // 101 characters: one over the BMS TDESC width (60) and also over the DB
        // column (VARCHAR(100)). It must be rejected as a length edit BEFORE any insert.
        String overlengthDescription = "D".repeat(101);
        TransactionService.AddTransactionCommand command = accountKeyed(
                "05", "0002", "POS", overlengthDescription, VALID_AMOUNT, ORIG_DATE, PROC_DATE,
                "1", "M", "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Description must not exceed 60 characters...");

        // Never reaches (or misclassifies at) the database.
        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("addTransaction rejects an overlength merchant name as a field-length validation error (the same catch previously masked it)")
    void addTransactionRejectsOverlengthMerchantNameAsValidationError() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));

        // 31 characters: one over the BMS MNAME width (30).
        String overlengthMerchantName = "M".repeat(31);
        TransactionService.AddTransactionCommand command = accountKeyed(
                "05", "0002", "POS", "Desc", VALID_AMOUNT, ORIG_DATE, PROC_DATE,
                "1", overlengthMerchantName, "C", "98101");

        assertThatThrownBy(() -> service.addTransaction(command))
                .isInstanceOf(TransactionService.TransactionValidationException.class)
                .hasMessage("Merchant Name must not exceed 30 characters...");

        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("addTransaction translates ONLY a genuine duplicate tran_id (SQLState 23505 on pk_transaction) to DuplicateKeyException")
    void addTransactionTranslatesTranIdCollisionToDuplicateKey() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));
        when(dateValidationService.isValid(ORIG_DATE, DATE_FMT)).thenReturn(true);
        when(dateValidationService.isValid(PROC_DATE, DATE_FMT)).thenReturn(true);
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.of("0000000000000100"));
        // The eager INSERT (Persistable forces persist) fails the pk_transaction unique
        // constraint: PostgreSQL SQLState 23505 naming the primary-key constraint.
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement",
                        new SQLException(
                                "ERROR: duplicate key value violates unique constraint \"pk_transaction\"",
                                "23505")));

        assertThatThrownBy(() -> service.addTransaction(validAccountKeyedCommand()))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessage("Tran ID already exist...");
    }

    @Test
    @DisplayName("addTransaction re-throws a NON-tran_id integrity violation unchanged (not mislabelled as a duplicate key)")
    void addTransactionReThrowsNonTranIdIntegrityViolation() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(VALID_ACCOUNT_LONG))
                .thenReturn(Optional.of(new CardXref(VALID_CARD, 1L, VALID_ACCOUNT_LONG)));
        when(dateValidationService.isValid(ORIG_DATE, DATE_FMT)).thenReturn(true);
        when(dateValidationService.isValid(PROC_DATE, DATE_FMT)).thenReturn(true);
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.of("0000000000000100"));
        // A foreign-key violation (SQLState 23503) is a different integrity error; it must
        // NOT be reported as a duplicate tran_id.
        DataIntegrityViolationException foreignKeyViolation = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException(
                        "ERROR: insert or update on table \"transaction\" violates "
                                + "foreign key constraint \"fk_transaction_card\"",
                        "23503"));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(foreignKeyViolation);

        assertThatThrownBy(() -> service.addTransaction(validAccountKeyedCommand()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class)
                .isSameAs(foreignKeyViolation);
    }


    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /**
     * Builds a {@link Transaction} fixture for the list/view tests.
     *
     * @param id     the 16-character transaction id
     * @param procTs the 26-character processing-timestamp text
     * @return a populated {@link Transaction}
     */
    private static Transaction tran(String id, String procTs) {
        return new Transaction(id, "05", 2, "POS", "Desc",
                new BigDecimal("10.00"), 1L, "M", "C", "98101", VALID_CARD, ORIG_DATE, procTs);
    }

    /**
     * Builds an account-keyed {@link TransactionService.AddTransactionCommand} (account id
     * {@link #VALID_ACCOUNT}, no card number) from the eleven data fields.
     *
     * @param typeCd       transaction type code
     * @param categoryCd   transaction category code
     * @param source       transaction source
     * @param description  transaction description
     * @param amount       signed amount text
     * @param origDate     origination date text
     * @param procDate     processing date text
     * @param merchantId   merchant id
     * @param merchantName merchant name
     * @param merchantCity merchant city
     * @param merchantZip  merchant postal code
     * @return the assembled command
     */
    private static TransactionService.AddTransactionCommand accountKeyed(
            String typeCd, String categoryCd, String source, String description,
            String amount, String origDate, String procDate, String merchantId,
            String merchantName, String merchantCity, String merchantZip) {
        return new TransactionService.AddTransactionCommand(
                VALID_ACCOUNT, null, typeCd, categoryCd, source, description, amount,
                origDate, procDate, merchantId, merchantName, merchantCity, merchantZip);
    }

    /**
     * A fully valid, account-keyed command whose amount is {@link #VALID_AMOUNT} (100.00).
     *
     * @return a command that passes every edit
     */
    private static TransactionService.AddTransactionCommand validAccountKeyedCommand() {
        return accountKeyed("05", "0002", "POS", "Coffee shop", VALID_AMOUNT, ORIG_DATE, PROC_DATE,
                "987654321", "Cafe Java", "Seattle", "98101");
    }
}
