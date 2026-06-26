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
package com.aws.carddemo.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolStringUtils;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

/**
 * Parity-archetype unit tests for {@link TransactionPostingService}, the Java translation of the
 * legacy COBOL daily-transaction posting batch program {@code CBTRN02C} (source {@code
 * legacy/app/cbl/CBTRN02C.cbl}, JCL driver {@code legacy/app/jcl/POSTTRAN.jcl}).
 *
 * <p>{@code CBTRN02C} is the single most parity-critical batch program in the migration, and this
 * is its most rigorous test. It pins, with 100% behavioral parity (Agent Action Plan &sect;0.4.1,
 * &sect;0.6.1, &sect;0.6.4, &sect;0.6.6, &sect;0.7.1), every observable rule of the posting loop:
 *
 * <ul>
 *   <li><strong>PERFORM-order control flow</strong> &mdash; {@code 2000-POST-TRANSACTION} performs
 *       {@code 2700-UPDATE-TCATBAL} &rarr; {@code 2800-UPDATE-ACCOUNT-REC} &rarr; {@code
 *       2900-WRITE-TRANSACTION-FILE} in exactly that order (Mockito {@link InOrder}).
 *   <li><strong>Validation reason codes</strong> &mdash; {@code 1500-VALIDATE-TRAN} sets {@code
 *       100} (invalid card), {@code 101} (account not found), {@code 102} (overlimit), {@code 103}
 *       (after expiration); a non-zero reason rejects and never posts.
 *   <li><strong>102/103 last-wins</strong> &mdash; the overlimit and expiration checks are two
 *       sequential, independent {@code IF}s, so a record that is BOTH overlimit and expired is
 *       rejected with {@code 103} (it overwrites {@code 102}).
 *   <li><strong>430-character reject</strong> &mdash; {@code 2500-WRITE-REJECT-REC} emits the
 *       350-byte {@code DALYTRAN} image plus the 80-byte trailer ({@code PIC 9(04)} reason + {@code
 *       PIC X(76)} description), modeled here as the {@link java.util.function.Consumer} reject
 *       sink ({@code DALYREJS}, {@code LRECL=430}).
 *   <li><strong>TCATBAL upsert</strong> &mdash; {@code 2700-UPDATE-TCATBAL} accepts FILE STATUS
 *       {@code '00' OR '23'}: a missing balance is created from zero and the amount added; an
 *       existing balance has the amount added.
 *   <li><strong>Account sign routing</strong> &mdash; {@code 2800-UPDATE-ACCOUNT-REC} adds the
 *       amount to {@code ACCT-CURR-BAL}, then a non-negative amount to {@code ACCT-CURR-CYC-CREDIT}
 *       and a negative amount (added as-is, never negated) to {@code ACCT-CURR-CYC-DEBIT}.
 *   <li><strong>26-character DB2 timestamp</strong> &mdash; {@code Z-GET-DB2-FORMAT-TIMESTAMP}
 *       renders {@code YYYY-MM-DD-HH.MM.SS.NN0000}; an injected fixed {@link Clock} makes it
 *       deterministic.
 *   <li><strong>Return code</strong> &mdash; {@code RETURN-CODE} is {@code 4} when any record was
 *       rejected, otherwise {@code 0}.
 *   <li><strong>Abend mapping</strong> &mdash; an unexpected I/O failure surfaces as {@link
 *       IoStatusException} (the {@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM} path,
 *       batch abend code {@link IoStatusException#BATCH_ABEND_CODE 999}).
 * </ul>
 *
 * <p>These are pure Mockito unit tests: the five repositories are mocked, the service is
 * constructed directly through its package-private clock-injecting constructor (no Spring context,
 * no Testcontainers, no database), and rejects are captured through the {@code Consumer<String>}
 * sink passed to {@link TransactionPostingService#run(java.util.function.Consumer)}. Every monetary
 * assertion uses {@link BigDecimal} compared with {@code isEqualByComparingTo} (never {@code
 * equals}/{@code isEqualTo}) so scale differences never mask a value match (AAP &sect;0.6.1).
 *
 * <p>Mockito strict stubbing is in effect, so each test stubs only the repository interactions its
 * scenario actually reaches: the validation-failure tests deliberately do not stub the account or
 * category-balance reads that their short-circuited control flow never performs.
 */
@ExtendWith(MockitoExtension.class)
class TransactionPostingServiceTest {

  /** Card number of the canonical valid daily transaction and its cross-reference key. */
  private static final String CARD_NUM = "4111111111111111";

  /** Account id the canonical cross-reference resolves to ({@code XREF-ACCT-ID}, {@code 9(11)}). */
  private static final Long ACCT_ID = 12345678901L;

  /** Transaction type code of the canonical valid daily transaction ({@code PIC X(02)}). */
  private static final String TYPE_CD = "01";

  /** Transaction category code of the canonical valid daily transaction ({@code PIC 9(04)}). */
  private static final String CAT_CD = "0001";

  /**
   * Identifier of the canonical valid daily transaction ({@code DALYTRAN-ID}, {@code PIC X(16)}).
   */
  private static final String DALYTRAN_ID = "TXN0000000000001";

  /**
   * Origination timestamp of the canonical valid daily transaction ({@code DALYTRAN-ORIG-TS}, 26
   * chars). Its {@code YYYY-MM-DD} prefix {@code "2023-01-15"} drives the expiration comparison.
   */
  private static final String ORIG_TS = "2023-01-15-12.30.00.000000";

  /**
   * The exact processing timestamp the service renders under {@link #FIXED_INSTANT}: {@code
   * YYYY-MM-DD-HH.MM.SS} plus the two-digit hundredths {@code 12} and the literal {@code 0000}.
   */
  private static final String EXPECTED_PROC_TS = "2022-07-18-13.45.30.120000";

  /**
   * Fixed instant backing the injected {@link Clock}; {@code .120} seconds &rarr; hundredths 12.
   */
  private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T13:45:30.120Z");

  @Mock private DailyTransactionRepository dailyTransactionRepository;
  @Mock private TransactionRepository transactionRepository;
  @Mock private CardXrefRepository cardXrefRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private TransactionCategoryBalanceRepository tranCatBalanceRepository;

  /** System under test, built in {@link #setUp()} from the five mocks and a fixed UTC clock. */
  private TransactionPostingService service;

  @BeforeEach
  void setUp() {
    // A fixed UTC clock makes Z-GET-DB2-FORMAT-TIMESTAMP deterministic for the posted-transaction
    // parity assertion. The package-private six-arg constructor is the test seam for the clock.
    Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    service =
        new TransactionPostingService(
            dailyTransactionRepository,
            transactionRepository,
            cardXrefRepository,
            accountRepository,
            tranCatBalanceRepository,
            fixedClock);
  }

  // -------------------------------------------------------------------------------------------
  // Phase A — PERFORM-order control flow: TCATBAL -> ACCOUNT -> TRANSACTION.
  // -------------------------------------------------------------------------------------------

  @Test
  void posting_updates_tcatbal_then_account_then_transaction_in_order() {
    DailyTransaction txn = validDailyTransaction("50.00");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(validAccount()));
    when(tranCatBalanceRepository.findById(any())).thenReturn(Optional.empty());

    List<String> rejects = new ArrayList<>();
    int rc = service.run(rejects::add);

    // 2000-POST-TRANSACTION performs 2700 -> 2800 -> 2900 in this exact order.
    InOrder inOrder = inOrder(tranCatBalanceRepository, accountRepository, transactionRepository);
    inOrder.verify(tranCatBalanceRepository).save(any());
    inOrder.verify(accountRepository).save(any());
    inOrder.verify(transactionRepository).save(any());
    assertThat(rc).isZero();
    assertThat(rejects).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Phase B — validation reason codes (each rejects and never posts).
  // -------------------------------------------------------------------------------------------

  @Test
  void reason_100_invalid_card_number_when_xref_missing() {
    DailyTransaction txn = validDailyTransaction("50.00");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

    List<String> rejects = new ArrayList<>();
    int rc = service.run(rejects::add);

    assertThat(rc).isEqualTo(4);
    assertThat(rejects).hasSize(1);
    String reject = rejects.get(0);
    assertThat(reject.substring(350, 354)).isEqualTo("0100");
    assertThat(reject.substring(354, 430)).contains("INVALID CARD NUMBER FOUND");
    assertNoPosting();
  }

  @Test
  void reason_101_account_record_not_found_when_account_missing() {
    DailyTransaction txn = validDailyTransaction("50.00");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

    List<String> rejects = new ArrayList<>();
    int rc = service.run(rejects::add);

    assertThat(rc).isEqualTo(4);
    assertThat(rejects).hasSize(1);
    String reject = rejects.get(0);
    assertThat(reject.substring(350, 354)).isEqualTo("0101");
    assertThat(reject.substring(354, 430)).startsWith("ACCOUNT RECORD NOT FOUND");
    assertNoPosting();
  }

  @Test
  void reason_102_overlimit_when_credit_limit_exceeded() {
    DailyTransaction txn = validDailyTransaction("50.00");
    // WS-TEMP-BAL = 10 - 5 + 50 = 55; credit limit 20 < 55 -> overlimit. Not expired.
    Account account = account("100.00", "20.00", "10.00", "10.00", "5.00", "2025-12-31");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

    List<String> rejects = new ArrayList<>();
    int rc = service.run(rejects::add);

    assertThat(rc).isEqualTo(4);
    assertThat(rejects).hasSize(1);
    String reject = rejects.get(0);
    assertThat(reject.substring(350, 354)).isEqualTo("0102");
    assertThat(reject.substring(354, 430)).startsWith("OVERLIMIT TRANSACTION");
    assertNoPosting();
  }

  @Test
  void reason_103_after_expiration_when_account_expired() {
    DailyTransaction txn = validDailyTransaction("50.00");
    // Within limit (10000 >= 55) but expired: expiration 2020-01-01 < origination 2023-01-15.
    Account account = account("100.00", "10000.00", "5000.00", "10.00", "5.00", "2020-01-01");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

    List<String> rejects = new ArrayList<>();
    int rc = service.run(rejects::add);

    assertThat(rc).isEqualTo(4);
    assertThat(rejects).hasSize(1);
    String reject = rejects.get(0);
    assertThat(reject.substring(350, 354)).isEqualTo("0103");
    assertThat(reject.substring(354, 430)).startsWith("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    assertNoPosting();
  }

  // -------------------------------------------------------------------------------------------
  // Phase C — 102/103 last-wins: sequential independent checks let 103 overwrite 102.
  // -------------------------------------------------------------------------------------------

  @Test
  void when_overlimit_and_expired_reason_103_overwrites_102() {
    DailyTransaction txn = validDailyTransaction("50.00");
    // BOTH overlimit (limit 20 < 55) AND expired (2020-01-01 < 2023-01-15).
    Account account = account("100.00", "20.00", "10.00", "10.00", "5.00", "2020-01-01");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

    List<String> rejects = new ArrayList<>();
    int rc = service.run(rejects::add);

    assertThat(rejects).hasSize(1);
    String reject = rejects.get(0);
    // The later expiration check overwrote the earlier overlimit reason.
    assertThat(reject.substring(350, 354)).isEqualTo("0103");
    assertThat(reject.substring(354, 430)).startsWith("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    assertThat(reject.substring(354, 430)).doesNotContain("OVERLIMIT");
    assertThat(rc).isEqualTo(4);
    assertNoPosting();
  }

  // -------------------------------------------------------------------------------------------
  // Phase D — 430-character reject layout (image + zero-padded reason + padded description).
  // -------------------------------------------------------------------------------------------

  @Test
  void reject_record_is_exactly_430_chars_with_zero_padded_reason_and_padded_description() {
    DailyTransaction txn = validDailyTransaction("50.00");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty()); // reason 100

    List<String> rejects = new ArrayList<>();
    service.run(rejects::add);

    assertThat(rejects).hasSize(1);
    String r = rejects.get(0);
    assertThat(r).hasSize(430);
    // The first 350 chars are the fixed-width DALYTRAN image.
    assertThat(r.substring(0, 350)).hasSize(350);
    // DALYTRAN-ID occupies [0:16] and DALYTRAN-CARD-NUM occupies [262:278] in copybook order.
    assertThat(r.substring(0, 16)).isEqualTo(DALYTRAN_ID);
    assertThat(r.substring(262, 278)).isEqualTo(CARD_NUM);
    // The 80-byte trailer is PIC 9(04) reason followed by PIC X(76) description.
    assertThat(r.substring(350, 354)).isEqualTo("0100");
    assertThat(r.substring(354, 430))
        .isEqualTo(CobolStringUtils.padRight("INVALID CARD NUMBER FOUND", 76));
  }

  // -------------------------------------------------------------------------------------------
  // Phase E — TCATBAL upsert: create when not found, update when found.
  // -------------------------------------------------------------------------------------------

  @Test
  void tcatbal_created_when_not_found_starts_from_zero_plus_amount() {
    DailyTransaction txn = validDailyTransaction("25.00");
    TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD);
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(validAccount()));
    when(tranCatBalanceRepository.findById(key)).thenReturn(Optional.empty()); // FILE STATUS '23'

    service.run(reject -> {});

    ArgumentCaptor<TransactionCategoryBalance> captor =
        ArgumentCaptor.forClass(TransactionCategoryBalance.class);
    verify(tranCatBalanceRepository).save(captor.capture());
    TransactionCategoryBalance saved = captor.getValue();
    // 2700-A-CREATE-TCATBAL-REC: key set from XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD.
    assertThat(saved.getId()).isEqualTo(key);
    // Balance starts at zero and the amount is added: 0.00 + 25.00 = 25.00.
    assertThat(saved.getTranCatBal()).isEqualByComparingTo(new BigDecimal("25.00"));
  }

  @Test
  void tcatbal_updated_when_found_adds_amount_to_existing_balance() {
    DailyTransaction txn = validDailyTransaction("25.00");
    TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD);
    TransactionCategoryBalance existing = new TransactionCategoryBalance();
    existing.setId(key);
    existing.setTranCatBal(new BigDecimal("100.00"));
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(validAccount()));
    when(tranCatBalanceRepository.findById(key)).thenReturn(Optional.of(existing)); // STATUS '00'

    service.run(reject -> {});

    ArgumentCaptor<TransactionCategoryBalance> captor =
        ArgumentCaptor.forClass(TransactionCategoryBalance.class);
    verify(tranCatBalanceRepository).save(captor.capture());
    // 2700-B-UPDATE-TCATBAL-REC: amount added to existing balance: 100.00 + 25.00 = 125.00.
    assertThat(captor.getValue().getTranCatBal()).isEqualByComparingTo(new BigDecimal("125.00"));
  }

  // -------------------------------------------------------------------------------------------
  // Phase F — account update + sign routing (2800-UPDATE-ACCOUNT-REC).
  // -------------------------------------------------------------------------------------------

  @Test
  void positive_amount_adds_to_balance_and_cycle_credit() {
    DailyTransaction txn = validDailyTransaction("50.00");
    Account account = account("100.00", "10000.00", "5000.00", "10.00", "5.00", "2025-12-31");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
    when(tranCatBalanceRepository.findById(any())).thenReturn(Optional.empty());

    service.run(reject -> {});

    ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(captor.capture());
    Account saved = captor.getValue();
    // ACCT-CURR-BAL += amount; non-negative amount routes to ACCT-CURR-CYC-CREDIT.
    assertThat(saved.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("150.00"));
    assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("60.00"));
    assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("5.00"));
  }

  @Test
  void negative_amount_adds_to_balance_and_accumulates_negative_in_cycle_debit_without_negating() {
    DailyTransaction txn = validDailyTransaction("-30.00");
    // WS-TEMP-BAL = 10 - 5 + (-30) = -25 <= 10000 -> within limit; not expired.
    Account account = account("100.00", "10000.00", "5000.00", "10.00", "5.00", "2025-12-31");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
    when(tranCatBalanceRepository.findById(any())).thenReturn(Optional.empty());

    service.run(reject -> {});

    ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(captor.capture());
    Account saved = captor.getValue();
    // ACCT-CURR-BAL += (-30) = 70.00.
    assertThat(saved.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("70.00"));
    // Critical bug-for-bug parity: the negative amount is ADDED to ACCT-CURR-CYC-DEBIT as-is
    // (5 + (-30) = -25), never negated to +30.
    assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("-25.00"));
    // ACCT-CURR-CYC-CREDIT is untouched on the debit branch.
    assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("10.00"));
  }

  // -------------------------------------------------------------------------------------------
  // Phase G — posted transaction field copy + 26-character DB2 timestamp.
  // -------------------------------------------------------------------------------------------

  @Test
  void posted_transaction_copies_fields_and_sets_26_char_db2_timestamp() {
    DailyTransaction txn = validDailyTransaction("123.45");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(validAccount()));
    when(tranCatBalanceRepository.findById(any())).thenReturn(Optional.empty());

    service.run(reject -> {});

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    Transaction saved = captor.getValue();
    // 2000-POST-TRANSACTION copies all twelve DALYTRAN-* fields into TRAN-*.
    assertThat(saved.getTranId()).isEqualTo(txn.getDalytranId());
    assertThat(saved.getTranTypeCd()).isEqualTo(txn.getDalytranTypeCd());
    assertThat(saved.getTranCatCd()).isEqualTo(txn.getDalytranCatCd());
    assertThat(saved.getTranSource()).isEqualTo(txn.getDalytranSource());
    assertThat(saved.getTranDesc()).isEqualTo(txn.getDalytranDesc());
    assertThat(saved.getTranAmt()).isEqualByComparingTo(txn.getDalytranAmt());
    assertThat(saved.getTranMerchantId()).isEqualTo(txn.getDalytranMerchantId());
    assertThat(saved.getTranMerchantName()).isEqualTo(txn.getDalytranMerchantName());
    assertThat(saved.getTranMerchantCity()).isEqualTo(txn.getDalytranMerchantCity());
    assertThat(saved.getTranMerchantZip()).isEqualTo(txn.getDalytranMerchantZip());
    assertThat(saved.getTranCardNum()).isEqualTo(txn.getDalytranCardNum());
    assertThat(saved.getTranOrigTs()).isEqualTo(txn.getDalytranOrigTs());
    // TRAN-PROC-TS is the 26-char DB2 timestamp from Z-GET-DB2-FORMAT-TIMESTAMP (fixed clock).
    assertThat(saved.getTranProcTs())
        .hasSize(26)
        .matches("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}$")
        .endsWith("0000")
        .isEqualTo(EXPECTED_PROC_TS);
  }

  // -------------------------------------------------------------------------------------------
  // Phase H — return code & sink count (RETURN-CODE 0 when all valid, 4 when any reject).
  // -------------------------------------------------------------------------------------------

  @Test
  void run_returns_zero_when_all_valid_and_four_when_any_reject() {
    DailyTransaction valid = validDailyTransaction("10.00");
    // An unknown card (no cross-reference) is rejected with reason 100.
    DailyTransaction invalid = dailyTransaction("TXN0000000000002", "9999000000000000", "5.00");
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(valid)) // first run: all valid
        .thenReturn(List.of(valid, invalid)); // second run: one valid + one reject
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(cardXrefRepository.findById("9999000000000000")).thenReturn(Optional.empty());
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(validAccount()));
    when(tranCatBalanceRepository.findById(any())).thenReturn(Optional.empty());

    // All-valid batch: RETURN-CODE 0, no rejects.
    List<String> allValidSink = new ArrayList<>();
    int rcAllValid = service.run(allValidSink::add);
    assertThat(rcAllValid).isZero();
    assertThat(allValidSink).isEmpty();

    // Mixed batch: RETURN-CODE 4, one reject emitted (the single invalid record).
    List<String> mixedSink = new ArrayList<>();
    int rcMixed = service.run(mixedSink::add);
    assertThat(rcMixed).isEqualTo(4);
    assertThat(mixedSink).hasSize(1);
  }

  // -------------------------------------------------------------------------------------------
  // Phase I — abend mapping: unexpected I/O -> IoStatusException (batch abend code 999).
  // -------------------------------------------------------------------------------------------

  @Test
  void unexpected_io_failure_throws_io_status_exception_with_abend_code() {
    // An unrecoverable failure establishing the DALYTRAN cursor is the 0000-DALYTRAN-OPEN abend.
    when(dailyTransactionRepository.findAll(any(Sort.class)))
        .thenThrow(new DataAccessResourceFailureException("daily-transaction cursor failure"));

    assertThatThrownBy(() -> service.run(sink -> {}))
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("DALYTRAN");
              assertThat(io.getOperation()).isEqualTo("OPEN");
              assertThat(io.getFileStatus()).isEqualTo("99");
            });

    // 9999-ABEND-PROGRAM moved 999 to ABCODE; that batch abend code is preserved on the exception.
    assertThat(IoStatusException.BATCH_ABEND_CODE).isEqualTo(999);
    // The open failed before any record was validated or written.
    verify(cardXrefRepository, never()).findById(any());
    verify(transactionRepository, never()).save(any());
  }

  @Test
  void write_failure_during_posting_throws_io_status_exception_for_transact_file() {
    DailyTransaction txn = validDailyTransaction("50.00");
    when(dailyTransactionRepository.findAll(any(Sort.class))).thenReturn(List.of(txn));
    when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(validXref()));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(validAccount()));
    when(tranCatBalanceRepository.findById(any())).thenReturn(Optional.empty());
    // 2900-WRITE-TRANSACTION-FILE fails with an unexpected status -> TRANSACT WRITE abend.
    when(transactionRepository.save(any()))
        .thenThrow(new DataAccessResourceFailureException("transaction write failure"));

    assertThatThrownBy(() -> service.run(sink -> {}))
        .isInstanceOf(IoStatusException.class)
        .satisfies(
            thrown -> {
              IoStatusException io = (IoStatusException) thrown;
              assertThat(io.getFileName()).isEqualTo("TRANSACT");
              assertThat(io.getOperation()).isEqualTo("WRITE");
              assertThat(io.getFileStatus()).isEqualTo("99");
            });
  }

  // -------------------------------------------------------------------------------------------
  // Helpers — fixed-value builders for the canonical valid transaction and its collaborators.
  // -------------------------------------------------------------------------------------------

  /**
   * Builds the canonical fully-valid daily transaction (the cross-reference and account stubs in
   * each test make it post by default; individual tests perturb a single collaborator to drive a
   * specific reject or arithmetic branch).
   *
   * @param amount the signed transaction amount ({@code DALYTRAN-AMT}); a {@link BigDecimal} string
   * @return a populated, postable daily transaction
   */
  private static DailyTransaction validDailyTransaction(String amount) {
    return dailyTransaction(DALYTRAN_ID, CARD_NUM, amount);
  }

  /**
   * Builds a daily transaction with caller-supplied identity, card number, and amount and otherwise
   * representative fixed-width field values, so the 350-byte reject image is fully populated.
   *
   * @param id the daily-transaction id ({@code DALYTRAN-ID}, primary/sort key)
   * @param cardNum the card number ({@code DALYTRAN-CARD-NUM}, the cross-reference lookup key)
   * @param amount the signed transaction amount ({@code DALYTRAN-AMT}); a {@link BigDecimal} string
   * @return a populated daily transaction
   */
  private static DailyTransaction dailyTransaction(String id, String cardNum, String amount) {
    DailyTransaction txn = new DailyTransaction();
    txn.setDalytranId(id);
    txn.setDalytranCardNum(cardNum);
    txn.setDalytranTypeCd(TYPE_CD);
    txn.setDalytranCatCd(CAT_CD);
    txn.setDalytranSource("POS");
    txn.setDalytranDesc("TEST TRANSACTION");
    txn.setDalytranAmt(new BigDecimal(amount));
    txn.setDalytranMerchantId(123456789L);
    txn.setDalytranMerchantName("ACME STORE");
    txn.setDalytranMerchantCity("SEATTLE");
    txn.setDalytranMerchantZip("98101");
    txn.setDalytranOrigTs(ORIG_TS);
    txn.setDalytranProcTs("");
    return txn;
  }

  /**
   * Builds the cross-reference resolving {@link #CARD_NUM} to {@link #ACCT_ID} ({@code
   * 1500-A-LOOKUP-XREF} success).
   *
   * @return a populated card cross-reference
   */
  private static CardXref validXref() {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(CARD_NUM);
    xref.setXrefAcctId(ACCT_ID);
    xref.setXrefCustId(100000001L);
    return xref;
  }

  /**
   * Builds an account that passes both validation checks: within credit limit and not expired
   * (origination {@code 2023-01-15} is on or before the {@code 2025-12-31} expiration date).
   *
   * @return a populated, valid account
   */
  private static Account validAccount() {
    return account("100.00", "10000.00", "5000.00", "10.00", "5.00", "2025-12-31");
  }

  /**
   * Builds an account from explicit {@link BigDecimal} balances/limits and an expiration date, so a
   * test can drive the overlimit, expiration, and sign-routing branches precisely. All monetary
   * fields are {@link BigDecimal} (never {@code float}/{@code double}) per AAP &sect;0.6.1.
   *
   * @param curBal the current balance ({@code ACCT-CURR-BAL})
   * @param creditLimit the credit limit ({@code ACCT-CREDIT-LIMIT})
   * @param cashCreditLimit the cash credit limit ({@code ACCT-CASH-CREDIT-LIMIT})
   * @param cycCredit the current-cycle credit total ({@code ACCT-CURR-CYC-CREDIT})
   * @param cycDebit the current-cycle debit total ({@code ACCT-CURR-CYC-DEBIT})
   * @param expDate the expiration date ({@code ACCT-EXPIRAION-DATE}, {@code YYYY-MM-DD})
   * @return a populated account
   */
  private static Account account(
      String curBal,
      String creditLimit,
      String cashCreditLimit,
      String cycCredit,
      String cycDebit,
      String expDate) {
    Account account = new Account();
    account.setAcctId(ACCT_ID);
    account.setAcctCurrBal(new BigDecimal(curBal));
    account.setAcctCreditLimit(new BigDecimal(creditLimit));
    account.setAcctCashCreditLimit(new BigDecimal(cashCreditLimit));
    account.setAcctCurrCycCredit(new BigDecimal(cycCredit));
    account.setAcctCurrCycDebit(new BigDecimal(cycDebit));
    account.setAcctExpiraionDate(expDate);
    return account;
  }

  /** Asserts that no posting write occurred on any of the three mutated stores. */
  private void assertNoPosting() {
    verify(tranCatBalanceRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(transactionRepository, never()).save(any());
  }
}
