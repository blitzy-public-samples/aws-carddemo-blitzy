/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.service;

import com.carddemo.dto.transaction.TransactionDto;
import com.carddemo.dto.transaction.TransactionListResponse;
import com.carddemo.dto.transaction.TransactionRequest;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.exception.OverlimitException;
import com.carddemo.exception.TransactionValidationException;
import com.carddemo.mapper.TransactionMapper;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.util.TransactionIdGenerator;
import com.carddemo.validation.TransactionValidator;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link TransactionService#addTransaction(TransactionRequest)} — the online
 * add-transaction flow (COBOL {@code COTRN02C} {@code ADD-TRANSACTION}) — focused on the
 * {@code CBTRN02C} {@code 1500-VALIDATE-TRAN} validation chain and its EXACT reason-code messages
 * (PR-03): code 100 {@code "INVALID CARD NUMBER FOUND"}, code 101 {@code "ACCOUNT RECORD NOT FOUND"},
 * code 102 {@code "OVERLIMIT TRANSACTION"}, code 103
 * {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}.
 *
 * <h2>Why this test mirrors the COMMITTED service, not the migration-plan sketch</h2>
 * <p>The class-under-test is verified against the actual committed
 * {@code com.carddemo.service.TransactionService}. That service differs from the illustrative
 * snippet in the file's agent prompt in three ways that this test faithfully reflects:</p>
 * <ul>
 *   <li><b>Method name:</b> the public creation method is {@code addTransaction(TransactionRequest)}
 *       returning {@link TransactionDto} (not {@code createTransaction}).</li>
 *   <li><b>Real constructor collaborators (8):</b> {@code TransactionRepository},
 *       {@code CardXrefRepository}, <b>{@code TransactionValidator}</b>,
 *       {@code TransactionTypeRepository}, {@code TransactionCategoryRepository},
 *       {@code TransactionCategoryBalanceRepository}, {@code TransactionIdGenerator} and
 *       <b>{@code TransactionMapper}</b>. {@code TransactionValidator} and {@code TransactionMapper}
 *       are genuine, committed dependencies of the service (required so {@code @InjectMocks} can
 *       build it) even though they are absent from the originally-declared dependency set — mocking
 *       them here is the only faithful way to exercise the real service API. Conversely the service
 *       does <em>not</em> depend on {@code AccountRepository} directly (the validator owns it), so no
 *       {@code Account} entity / {@code AccountRepository} is referenced here.</li>
 *   <li><b>Posting is deferred to batch:</b> the online {@code addTransaction} path only validates and
 *       inserts the {@code TRAN-RECORD}. The TCATBAL upsert (PR-06, {@code CBTRN02C}
 *       {@code 2700-UPDATE-TCATBAL}) and the sign-based account-balance bucket update (PR-07,
 *       {@code 2800-UPDATE-ACCOUNT-REC}) are intentionally performed by the POSTTRAN batch job, not
 *       online. Those behaviours are therefore covered by {@code TransactionPostingParityTest}; here
 *       we assert the online path's <em>non-interaction</em> with the posting collaborators to lock
 *       the deferral in place.</li>
 * </ul>
 *
 * <h2>Codes 101/102/103 are produced by the (mocked) validator</h2>
 * <p>In the committed service, code 100 (card cross-reference miss) is raised directly by the service
 * via {@code cardXrefRepository}; codes 101/102/103 are delegated VERBATIM to the shared
 * {@link TransactionValidator#validate} (so the online and batch paths run the identical chain). In
 * this unit test the validator is a mock, so 101/102/103 are simulated by stubbing
 * {@code validate(..)} to throw the corresponding exception — which lets us assert the service
 * propagates the exact COBOL message unchanged and skips persistence.</p>
 *
 * <h2>Conventions (matching the sibling service tests)</h2>
 * <ul>
 *   <li>{@code @ExtendWith(MockitoExtension.class)} in default <b>strict-stubs</b> mode: every stub
 *       declared in a test must be exercised by that test, so each test stubs only what it uses (the
 *       happy-path helper stubs exactly the five collaborators the success flow touches).</li>
 *   <li>Money is asserted with AssertJ {@code isEqualByComparingTo} (PR-16) so scale never affects
 *       equality.</li>
 *   <li>Exact messages are asserted with {@code hasMessage(..)} (PR-03).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService - CRITICAL PR-03 validation chain 100/101/102/103 (CBTRN02C parity)")
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionValidator transactionValidator;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    @Mock
    private TransactionCategoryBalanceRepository tcatBalRepository;

    @Mock
    private TransactionIdGenerator transactionIdGenerator;

    @Mock
    private TransactionMapper transactionMapper;

    @InjectMocks
    private TransactionService transactionService;

    @Captor
    private ArgumentCaptor<Transaction> transactionCaptor;

    /** A well-formed 16-digit card number that exists in the cross-reference (happy path). */
    private static final String VALID_CARD = "4111111111111111";

    /** A non-existent 16-digit card number used to trigger the code-100 cross-reference miss. */
    private static final String MISSING_CARD = "9999999999999999";

    /** Primary account identifier (&le; 11 digits per the DTO contract). */
    private static final Long VALID_ACCOUNT_ID = 11_111_111_111L;

    /** Customer identifier carried by the cross-reference record. */
    private static final Long VALID_CUST_ID = 100_000_001L;

    /** The 6-digit online suffix the (mocked) DB sequence hands to the id generator. */
    private static final long ONLINE_SUFFIX = 1L;

    /** A canonical 16-character transaction id (parmDate(10) + suffix(6)) per PR-10. */
    private static final String SIXTEEN_CHAR_ID = "2024011500000001";

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a fully-populated, valid {@link TransactionRequest} that passes every field-level
     * required-input check in {@code TransactionService.validateInputFields}. {@code origTimestamp}
     * is intentionally left {@code null} so the service stamps "now" via {@code DateConversionUtil}
     * (a real, self-consistent value) — keeping these unit tests independent of any fixed DB2 string.
     */
    private TransactionRequest buildValidRequest() {
        TransactionRequest request = new TransactionRequest();
        request.setAccountId(VALID_ACCOUNT_ID);
        request.setCardNumber(VALID_CARD);
        request.setTypeCd("01");
        request.setCategoryCd("0001");
        request.setSource("POS");
        request.setDescription("TEST TRANSACTION");
        request.setAmount(new BigDecimal("100.00"));
        request.setMerchantId(123_456_789L);
        request.setMerchantName("TEST MERCHANT");
        request.setMerchantCity("AUSTIN");
        request.setMerchantZip("78701");
        // origTimestamp left null on purpose -> service supplies DateConversionUtil.nowAsDb2Timestamp()
        return request;
    }

    /** Builds the cross-reference record returned for {@link #VALID_CARD}. */
    private CardXref buildXref() {
        CardXref xref = new CardXref();
        xref.setXrefCardNum(VALID_CARD);
        xref.setCustId(VALID_CUST_ID);
        xref.setAccountId(VALID_ACCOUNT_ID);
        return xref;
    }

    /** Stubs only the card cross-reference success (first link of the chain) for {@link #VALID_CARD}. */
    private void stubCardLookupSuccess() {
        when(cardXrefRepository.findById(VALID_CARD)).thenReturn(Optional.of(buildXref()));
    }

    /**
     * Stubs the complete success flow — and ONLY the five collaborators the success path actually
     * invokes (strict-stubs friendly): card lookup, the online id suffix sequence, the id generator,
     * the request-to-entity mapping, and the persisting save. {@code transactionMapper.toDto(..)} is
     * deliberately NOT stubbed here (tests that assert on the returned DTO stub it themselves); when
     * unstubbed it returns {@code null}, which success-flow tests that only inspect the captured saved
     * entity simply ignore. The (void) {@code transactionValidator.validate(..)} is left unstubbed so
     * it is a no-op pass.
     */
    private void stubHappyPath() {
        stubCardLookupSuccess();
        when(transactionRepository.nextTransactionIdSuffix()).thenReturn(ONLINE_SUFFIX);
        when(transactionIdGenerator.nextOnlineId(anyString(), anyLong())).thenReturn(SIXTEEN_CHAR_ID);
        when(transactionMapper.toEntity(any(TransactionRequest.class))).thenReturn(new Transaction());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==========================================================================================
    // Field-level required-input validation (COTRN02C VALIDATE-INPUT-DATA-FIELDS)
    // ==========================================================================================

    @Nested
    @DisplayName("Field-level input validation (COTRN02C VALIDATE-INPUT-DATA-FIELDS)")
    class InputFieldValidation {

        @Test
        @DisplayName("Rejects an explicitly-supplied-but-blank origin timestamp with the COBOL date message")
        void shouldRejectBlankOriginTimestamp() {
            TransactionRequest request = buildValidRequest();
            request.setOrigTimestamp("   ");

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Date in CCYY-MM-DD format must be supplied...");
        }

        @Test
        @DisplayName("Rejects a missing amount with the COBOL amount message")
        void shouldRejectMissingAmount() {
            TransactionRequest request = buildValidRequest();
            request.setAmount(null);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount in -99999999.99 format must be supplied...");
        }

        @Test
        @DisplayName("Rejects a missing description")
        void shouldRejectMissingDescription() {
            TransactionRequest request = buildValidRequest();
            request.setDescription(null);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Description must be supplied...");
        }

        @Test
        @DisplayName("Rejects a missing transaction type code")
        void shouldRejectMissingTypeCd() {
            TransactionRequest request = buildValidRequest();
            request.setTypeCd(null);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Type CD must be supplied...");
        }

        @Test
        @DisplayName("Rejects a missing transaction category code")
        void shouldRejectMissingCategoryCd() {
            TransactionRequest request = buildValidRequest();
            request.setCategoryCd(null);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category CD must be supplied...");
        }

        @Test
        @DisplayName("Rejects a missing transaction source")
        void shouldRejectMissingSource() {
            TransactionRequest request = buildValidRequest();
            request.setSource(null);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tran. Source must be supplied...");
        }

        @Test
        @DisplayName("Rejects when BOTH account id and card number are absent (COTRN02C key fields)")
        void shouldRejectWhenNeitherAccountIdNorCardNumberSupplied() {
            TransactionRequest request = buildValidRequest();
            request.setCardNumber(null);
            request.setAccountId(null);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account ID OR Card Number must be supplied...");
        }

        @Test
        @DisplayName("Rejects a missing merchant id")
        void shouldRejectMissingMerchantId() {
            TransactionRequest request = buildValidRequest();
            request.setMerchantId(null);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Merchant ID must be supplied...");
        }
    }

    // ==========================================================================================
    // Code 100 — INVALID CARD NUMBER FOUND (CBTRN02C 1500-A-LOOKUP-XREF, L380-L392)
    // ==========================================================================================

    @Nested
    @DisplayName("Code 100: INVALID CARD NUMBER FOUND (CBTRN02C L380-L392)")
    class Code100InvalidCard {

        @Test
        @DisplayName("Throws InvalidCardException with the EXACT message when the card xref lookup misses")
        void shouldRejectWithCode100WhenCardXrefMissing() {
            when(cardXrefRepository.findById(MISSING_CARD)).thenReturn(Optional.empty());

            TransactionRequest request = buildValidRequest();
            request.setCardNumber(MISSING_CARD);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(InvalidCardException.class)
                .hasMessage("INVALID CARD NUMBER FOUND");
        }

        @Test
        @DisplayName("On a card miss, never runs the validator and never persists (short-circuit precedence)")
        void shouldNotProceedPastCardLookupOnCardFailure() {
            when(cardXrefRepository.findById(MISSING_CARD)).thenReturn(Optional.empty());

            TransactionRequest request = buildValidRequest();
            request.setCardNumber(MISSING_CARD);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(InvalidCardException.class);

            verify(transactionValidator, never()).validate(any());
            verifyNoInteractions(transactionRepository, transactionMapper, transactionIdGenerator);
        }
    }

    // ==========================================================================================
    // QA Issue 3 — account id / card number consistency check (REST-only; no COBOL code).
    // When BOTH identifiers are supplied they must agree; a card that does not belong to the
    // supplied account is rejected with a 400 TransactionValidationException and nothing is saved.
    // ==========================================================================================

    @Nested
    @DisplayName("QA Issue 3: account id / card number mismatch is rejected (HTTP 400)")
    class AccountCardMismatch {

        /** A valid, seeded account id that is DIFFERENT from the one the resolved card belongs to. */
        private static final Long MISMATCHED_ACCOUNT_ID = 99_999_999_999L;

        @Test
        @DisplayName("Rejects when the supplied card resolves to a DIFFERENT account than accountId")
        void shouldRejectWhenCardDoesNotBelongToSuppliedAccount() {
            // The card xref resolves successfully but to VALID_ACCOUNT_ID, while the request supplies
            // a different (mismatched) accountId. accountId is authoritative, so this is a deterministic
            // validation failure -> 400 with a PAN-free message; no transaction is created.
            stubCardLookupSuccess(); // findById(VALID_CARD) -> xref with accountId == VALID_ACCOUNT_ID

            TransactionRequest request = buildValidRequest();
            request.setCardNumber(VALID_CARD);
            request.setAccountId(MISMATCHED_ACCOUNT_ID);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Card number does not belong to the supplied account id");

            verify(transactionValidator, never()).validate(any());
            verify(transactionRepository, never()).save(any(Transaction.class));
        }

        @Test
        @DisplayName("Accepts when the supplied card and accountId are consistent (no mismatch)")
        void shouldNotRejectWhenCardMatchesSuppliedAccount() {
            // Sanity counter-test: when both identifiers agree, the consistency check passes and the
            // flow proceeds normally (the happy-path stubs drive it through to a successful save).
            stubHappyPath();

            TransactionRequest request = buildValidRequest();
            request.setCardNumber(VALID_CARD);
            request.setAccountId(VALID_ACCOUNT_ID); // matches the xref's account

            transactionService.addTransaction(request);

            verify(transactionRepository).save(any(Transaction.class));
        }
    }

    // ==========================================================================================
    // Code 101 — ACCOUNT RECORD NOT FOUND (CBTRN02C 1500-B-LOOKUP-ACCT, L393-L399)
    // Delegated to the (mocked) TransactionValidator: simulated by stubbing validate(..) to throw.
    // ==========================================================================================

    @Nested
    @DisplayName("Code 101: ACCOUNT RECORD NOT FOUND (CBTRN02C L393-L399)")
    class Code101AccountNotFound {

        @Test
        @DisplayName("Propagates AccountNotFoundException with the EXACT message from the validator")
        void shouldRejectWithCode101WhenAccountMissing() {
            stubCardLookupSuccess();
            doThrow(new AccountNotFoundException())
                .when(transactionValidator).validate(any());

            assertThatThrownBy(() -> transactionService.addTransaction(buildValidRequest()))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("Does not persist the transaction when the account lookup fails")
        void shouldNotPersistWhenAccountMissing() {
            stubCardLookupSuccess();
            doThrow(new AccountNotFoundException())
                .when(transactionValidator).validate(any());

            assertThatThrownBy(() -> transactionService.addTransaction(buildValidRequest()))
                .isInstanceOf(AccountNotFoundException.class);

            verify(transactionRepository, never()).save(any(Transaction.class));
        }

        @Test
        @DisplayName("QA Issue 5: account id supplied with no card xref -> 404 AccountNotFoundException "
                + "(NOT code 100 / 'INVALID CARD NUMBER FOUND')")
        void shouldReturnNotFoundWhenAccountHasNoCardXref() {
            // An existing account that has no card cross-reference is a NOT-FOUND condition, not an
            // invalid-explicit-card condition. The account-id resolution branch must surface a 404
            // (AccountNotFoundException carries COBOL code 101 -> HTTP 404) with a domain-accurate
            // message, distinguishing it from an explicitly supplied bad card number (code 100 / 400).
            when(cardXrefRepository.findByAccountId(VALID_ACCOUNT_ID)).thenReturn(List.of());

            TransactionRequest request = buildValidRequest();
            request.setCardNumber(null); // force the account-id resolution branch

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("No card found for account: " + VALID_ACCOUNT_ID);

            verify(transactionRepository, never()).save(any(Transaction.class));
        }
    }

    // ==========================================================================================
    // Code 102 — OVERLIMIT TRANSACTION (CBTRN02C 1500-B-LOOKUP-ACCT, L403-L413)
    // ==========================================================================================

    @Nested
    @DisplayName("Code 102: OVERLIMIT TRANSACTION (CBTRN02C L403-L413)")
    class Code102Overlimit {

        @Test
        @DisplayName("Propagates OverlimitException with the EXACT message from the validator")
        void shouldRejectWithCode102WhenOverlimit() {
            stubCardLookupSuccess();
            doThrow(new OverlimitException())
                .when(transactionValidator).validate(any());

            assertThatThrownBy(() -> transactionService.addTransaction(buildValidRequest()))
                .isInstanceOf(OverlimitException.class)
                .hasMessage("OVERLIMIT TRANSACTION");
        }

        @Test
        @DisplayName("Does not persist the transaction when the credit-limit check fails")
        void shouldNotPersistWhenOverlimit() {
            stubCardLookupSuccess();
            doThrow(new OverlimitException())
                .when(transactionValidator).validate(any());

            assertThatThrownBy(() -> transactionService.addTransaction(buildValidRequest()))
                .isInstanceOf(OverlimitException.class);

            verify(transactionRepository, never()).save(any(Transaction.class));
        }
    }

    // ==========================================================================================
    // Code 103 — TRANSACTION RECEIVED AFTER ACCT EXPIRATION (CBTRN02C 1500-B-LOOKUP-ACCT, L414-L420)
    // ==========================================================================================

    @Nested
    @DisplayName("Code 103: TRANSACTION RECEIVED AFTER ACCT EXPIRATION (CBTRN02C L414-L420)")
    class Code103Expired {

        @Test
        @DisplayName("Propagates ExpiredAccountException with the EXACT message (note: 'ACCT', not 'ACCOUNT')")
        void shouldRejectWithCode103WhenExpired() {
            stubCardLookupSuccess();
            doThrow(new ExpiredAccountException())
                .when(transactionValidator).validate(any());

            assertThatThrownBy(() -> transactionService.addTransaction(buildValidRequest()))
                .isInstanceOf(ExpiredAccountException.class)
                .hasMessage("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        }

        @Test
        @DisplayName("Does not persist the transaction when the account is expired")
        void shouldNotPersistWhenExpired() {
            stubCardLookupSuccess();
            doThrow(new ExpiredAccountException())
                .when(transactionValidator).validate(any());

            assertThatThrownBy(() -> transactionService.addTransaction(buildValidRequest()))
                .isInstanceOf(ExpiredAccountException.class);

            verify(transactionRepository, never()).save(any(Transaction.class));
        }
    }

    // ==========================================================================================
    // Validation chain order — card (100) precedes account/limit/expiration (101/102/103),
    // and the validator runs before any persistence (mirrors CBTRN02C 1500-VALIDATE-TRAN).
    // ==========================================================================================

    @Nested
    @DisplayName("Validation chain order (CBTRN02C 1500-VALIDATE-TRAN)")
    class ValidationChainOrder {

        @Test
        @DisplayName("Card miss (100) short-circuits BEFORE the account/limit/expiration validator")
        void shouldRaiseCode100BeforeInvokingValidator() {
            when(cardXrefRepository.findById(MISSING_CARD)).thenReturn(Optional.empty());

            TransactionRequest request = buildValidRequest();
            request.setCardNumber(MISSING_CARD);

            assertThatThrownBy(() -> transactionService.addTransaction(request))
                .isInstanceOf(InvalidCardException.class);

            verify(transactionValidator, never()).validate(any());
        }

        @Test
        @DisplayName("On success the validator runs BEFORE the transaction is saved")
        void shouldValidateBeforePersisting() {
            stubHappyPath();

            transactionService.addTransaction(buildValidRequest());

            InOrder ordered = inOrder(transactionValidator, transactionRepository);
            ordered.verify(transactionValidator).validate(any());
            ordered.verify(transactionRepository).save(any(Transaction.class));
        }
    }

    // ==========================================================================================
    // Transaction id generation (PR-10 / AAP §0.6.10) — 16 chars: parmDate(10) + suffix(6),
    // the online suffix drawn from the DB sequence (transactionRepository.nextTransactionIdSuffix).
    // ==========================================================================================

    @Nested
    @DisplayName("Transaction ID generation (PR-10)")
    class TransactionIdGeneration {

        @Test
        @DisplayName("Stamps the saved transaction with the generated 16-character id")
        void shouldGenerate16CharacterTransactionId() {
            stubHappyPath();

            transactionService.addTransaction(buildValidRequest());

            verify(transactionRepository).save(transactionCaptor.capture());
            Transaction saved = transactionCaptor.getValue();
            assertThat(saved.getTranId()).isEqualTo(SIXTEEN_CHAR_ID);
            assertThat(saved.getTranId()).hasSize(16);
        }

        @Test
        @DisplayName("Feeds the DB-sequence online suffix into the id generator (online, not batch, path)")
        void shouldUseOnlineSequenceSuffixForId() {
            stubHappyPath();

            transactionService.addTransaction(buildValidRequest());

            verify(transactionRepository).nextTransactionIdSuffix();
            verify(transactionIdGenerator).nextOnlineId(anyString(), eq(ONLINE_SUFFIX));
        }
    }

    // ==========================================================================================
    // Posting deferred to batch (PR-06 / PR-07) — the ONLINE add path validates and inserts the
    // TRAN-RECORD only. TCATBAL upsert (CBTRN02C 2700) and the sign-based account-balance bucket
    // (CBTRN02C 2800) are the POSTTRAN batch job's responsibility (see TransactionPostingParityTest).
    // We assert the online path leaves the posting collaborators untouched.
    // ==========================================================================================

    @Nested
    @DisplayName("Posting deferred to POSTTRAN batch (PR-06/PR-07)")
    class PostingDeferredToBatch {

        @Test
        @DisplayName("Online add does NOT upsert the transaction-category-balance (TCATBAL)")
        void shouldNotUpsertTcatbalOnline() {
            stubHappyPath();

            transactionService.addTransaction(buildValidRequest());

            verifyNoInteractions(tcatBalRepository);
        }

        @Test
        @DisplayName("Online add does NOT touch the reference-data repositories (type/category)")
        void shouldNotTouchReferenceDataRepositoriesOnline() {
            stubHappyPath();

            transactionService.addTransaction(buildValidRequest());

            verifyNoInteractions(transactionTypeRepository, transactionCategoryRepository);
        }
    }

    // ==========================================================================================
    // Successful creation — full happy path persists the record and returns the mapped DTO.
    // ==========================================================================================

    @Nested
    @DisplayName("Successful transaction creation")
    class SuccessfulCreation {

        @Test
        @DisplayName("Persists exactly one transaction carrying the resolved card number")
        void shouldSaveTransactionWithResolvedCardNumber() {
            stubHappyPath();

            transactionService.addTransaction(buildValidRequest());

            verify(transactionRepository).save(transactionCaptor.capture());
            Transaction saved = transactionCaptor.getValue();
            assertThat(saved.getCardNum()).isEqualTo(VALID_CARD);
        }

        @Test
        @DisplayName("Stamps both the originating and processing timestamps before persisting (PR-11)")
        void shouldStampOriginAndProcessingTimestamps() {
            stubHappyPath();

            transactionService.addTransaction(buildValidRequest());

            verify(transactionRepository).save(transactionCaptor.capture());
            Transaction saved = transactionCaptor.getValue();
            assertThat(saved.getOrigTimestamp()).isNotNull();
            assertThat(saved.getProcTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("Returns the DTO produced by the mapper from the persisted entity")
        void shouldReturnMappedDto() {
            stubHappyPath();
            TransactionDto expected = TransactionDto.builder()
                .tranId(SIXTEEN_CHAR_ID)
                .cardNumber(VALID_CARD)
                .typeCd("01")
                .categoryCd("0001")
                .amount(new BigDecimal("100.00"))
                .build();
            when(transactionMapper.toDto(any(Transaction.class))).thenReturn(expected);

            TransactionDto result = transactionService.addTransaction(buildValidRequest());

            assertThat(result).isSameAs(expected);
            assertThat(result.getTranId()).hasSize(16);
            assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    /**
     * Filter-routing coverage for the schema-authoritative 3-argument
     * {@code listTransactions(Long accountId, String cardNumber, Pageable)} — the paginated COTRN00C
     * list flow that {@code TransactionController.listTransactions(Long, String, Pageable)} delegates
     * to. These tests lock the branch selection that distinguishes the three filter modes:
     * <ul>
     *   <li>a non-blank {@code cardNumber} routes to
     *       {@link TransactionRepository#findByCardNum(String, Pageable)} and never touches the
     *       cross-reference repository;</li>
     *   <li>a non-null {@code accountId} resolves the account's card numbers via
     *       {@link CardXrefRepository#findByAccountId(Long)} (entity field {@code xrefCardNum}) then
     *       routes to {@link TransactionRepository#findByCardNumIn(java.util.Collection, Pageable)};</li>
     *   <li>an {@code accountId} with no cross-referenced cards yields an empty page WITHOUT issuing a
     *       transaction query (the short-circuit guard);</li>
     *   <li>no filters route to {@link TransactionRepository#findAll(Pageable)};</li>
     *   <li>the single-argument {@code listTransactions(Pageable)} overload delegates to the
     *       no-filter path.</li>
     * </ul>
     * In every branch the resulting {@link Page} is handed to
     * {@link TransactionMapper#toListResponse(Page)} and its result returned verbatim. This durable
     * unit coverage complements the end-to-end {@code FullStackIT} section&nbsp;8
     * ({@code GET /api/transactions?cardNumber=X}) exercising the same routing through the full
     * REST + database stack.
     */
    @Nested
    @DisplayName("listTransactions(Long, String, Pageable) — filter routing (COTRN00C list)")
    class ListTransactionsFiltering {

        private final Pageable pageable = PageRequest.of(0, 10);
        private final TransactionListResponse sentinel =
            new TransactionListResponse(List.of(), 0L, 0, 0, 10, false, false);

        private Page<Transaction> onePage() {
            return new PageImpl<>(List.of(new Transaction()), pageable, 1);
        }

        @Test
        @DisplayName("cardNumber filter routes to findByCardNum and never queries the xref repository")
        void shouldFilterByCardNumber() {
            Page<Transaction> page = onePage();
            when(transactionRepository.findByCardNum(VALID_CARD, pageable)).thenReturn(page);
            when(transactionMapper.toListResponse(page)).thenReturn(sentinel);

            TransactionListResponse result =
                transactionService.listTransactions(null, VALID_CARD, pageable);

            assertThat(result).isSameAs(sentinel);
            verify(transactionRepository).findByCardNum(VALID_CARD, pageable);
            verifyNoInteractions(cardXrefRepository);
        }

        @Test
        @DisplayName("accountId filter resolves cards via xref then routes to findByCardNumIn")
        void shouldFilterByAccountIdWithCards() {
            CardXref xref = new CardXref();
            xref.setXrefCardNum(VALID_CARD);
            Page<Transaction> page = onePage();
            when(cardXrefRepository.findByAccountId(VALID_ACCOUNT_ID)).thenReturn(List.of(xref));
            when(transactionRepository.findByCardNumIn(List.of(VALID_CARD), pageable)).thenReturn(page);
            when(transactionMapper.toListResponse(page)).thenReturn(sentinel);

            TransactionListResponse result =
                transactionService.listTransactions(VALID_ACCOUNT_ID, null, pageable);

            assertThat(result).isSameAs(sentinel);
            verify(transactionRepository).findByCardNumIn(List.of(VALID_CARD), pageable);
            verify(transactionRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("accountId with no cross-referenced cards yields an empty page without querying transactions")
        void shouldReturnEmptyForAccountWithoutCards() {
            when(cardXrefRepository.findByAccountId(VALID_ACCOUNT_ID)).thenReturn(List.of());
            when(transactionMapper.toListResponse(any())).thenReturn(sentinel);

            TransactionListResponse result =
                transactionService.listTransactions(VALID_ACCOUNT_ID, null, pageable);

            assertThat(result).isSameAs(sentinel);
            verify(transactionRepository, never()).findByCardNumIn(any(), any());
            verify(transactionRepository, never()).findByCardNum(any(), any());
            verify(transactionRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("no filters route to findAll(Pageable)")
        void shouldListAllWithoutFilters() {
            Page<Transaction> page = onePage();
            when(transactionRepository.findAll(pageable)).thenReturn(page);
            when(transactionMapper.toListResponse(page)).thenReturn(sentinel);

            TransactionListResponse result =
                transactionService.listTransactions(null, null, pageable);

            assertThat(result).isSameAs(sentinel);
            verify(transactionRepository).findAll(pageable);
            verifyNoInteractions(cardXrefRepository);
        }

        @Test
        @DisplayName("single-arg listTransactions(Pageable) delegates to the no-filter path")
        void shouldDelegateSingleArgOverload() {
            Page<Transaction> page = onePage();
            when(transactionRepository.findAll(pageable)).thenReturn(page);
            when(transactionMapper.toListResponse(page)).thenReturn(sentinel);

            TransactionListResponse result = transactionService.listTransactions(pageable);

            assertThat(result).isSameAs(sentinel);
            verify(transactionRepository).findAll(pageable);
            verifyNoInteractions(cardXrefRepository);
        }
    }
}
