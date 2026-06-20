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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.DisclosureGroupId;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/**
 * Pure JUnit&nbsp;5 + AssertJ + Mockito unit tests for {@link InterestCalculationService}, the
 * batch interest-and-fee calculation service migrated with 100% behavioral parity from the legacy
 * z/OS COBOL program {@code CBACT04C} (behavioral spec {@code legacy/app/cbl/CBACT04C.cbl}, driven
 * by {@code legacy/app/jcl/INTCALC.jcl} with {@code PARM='2022071800'}).
 *
 * <p><strong>The single most parity-critical assertion in the whole migration lives here.</strong>
 * The COBOL {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (CBACT04C
 * L464-465) carries <em>no {@code ROUNDED} phrase</em>, so the result is <em>truncated</em> to the
 * receiving field's scale of 2. These tests pin that truncation with decimal vectors whose third
 * fraction digit would round <em>up</em> under {@link java.math.RoundingMode#HALF_UP} but must
 * truncate <em>down</em> under {@link java.math.RoundingMode#DOWN}: {@code (1000.00 * 15.07) / 1200
 * = 12.5583…} must yield {@code 12.55} (not {@code 12.56}), and {@code (500.00 * 19.99) / 1200 =
 * 8.3291…} must yield {@code 8.32} (not {@code 8.33}). Every monetary assertion uses {@link
 * BigDecimal} compared with {@code isEqualByComparingTo}; floating point is never used (Agent
 * Action Plan &sect;0.6.1, &sect;0.7.1).
 *
 * <p>These are <em>pure</em> Mockito unit tests: the five repository collaborators are mocked, the
 * service is constructed directly, and a fixed {@link Clock} is injected so the DB2-format
 * timestamps are deterministic — no Spring context, no Testcontainers, and no database is required
 * (AAP &sect;0.6.7, local-only validation). The parity invariants pinned are:
 *
 * <ul>
 *   <li><strong>Truncation</strong> — monthly interest truncates {@code DOWN} at scale 2 with the
 *       {@code /1200} monthly divisor (the headline rule).
 *   <li><strong>Control break + final EOF post</strong> — on each change of account id the previous
 *       account's accumulated interest is posted, and the last account is posted once after the
 *       loop, so {@code N} distinct accounts produce exactly {@code N} account updates, each
 *       zeroing the current-cycle credit/debit fields.
 *   <li><strong>Disclosure {@code DEFAULT} fallback</strong> — a missing specific disclosure group
 *       falls back to the {@code DEFAULT} group; only when the {@code DEFAULT} group is also
 *       missing does the program abend.
 *   <li><strong>Abend-on-missing</strong> — a missing account or card cross-reference is a hard
 *       {@link IoStatusException} abend (FILE STATUS {@code '23'}), never a silent skip.
 *   <li><strong>Conditional interest transaction</strong> — an interest {@link Transaction} is
 *       written <em>only</em> when the disclosure rate is non-zero; {@code 1400-COMPUTE-FEES} is a
 *       no-op.
 *   <li><strong>Transaction fields + 26-char DB2 timestamp</strong> — the generated interest
 *       transaction's fixed-width fields reproduce the COBOL {@code MOVE}s exactly.
 *   <li><strong>Cursor order</strong> — the {@code TCATBALF} cursor reads in ascending
 *       composite-key order ({@code id.trancatAcctId}, {@code id.trancatTypeCd}, {@code
 *       id.trancatCd}).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InterestCalculationServiceTest {

  /** The run-date {@code PARM} from {@code INTCALC.jcl} ({@code PARM='2022071800'}). */
  private static final String PARM_DATE = "2022071800";

  /**
   * A 10-character disclosure account-group id. Because it is already exactly {@code
   * DIS-ACCT-GROUP- ID PIC X(10)} wide, the service's {@code padRight(acctGroupId, 10)} is the
   * identity, so the disclosure-group lookup key equals {@code new DisclosureGroupId(ACCT_GROUP,
   * type, cat)} with no dependency on the padding utility.
   */
  private static final String ACCT_GROUP = "GROUP00001";

  /**
   * The {@code 'DEFAULT'} account-group id (COBOL {@code DEFAULT}) space-padded to the {@code
   * X(10)} width. {@code "DEFAULT"} is seven characters, so three trailing spaces complete the
   * fixed-width key used by the disclosure {@code DEFAULT} fallback.
   */
  private static final String DEFAULT_GROUP = "DEFAULT" + " ".repeat(3);

  /** Transaction type code carried by the category-balance rows (drives the disclosure lookup). */
  private static final String REC_TYPE = "01";

  /**
   * Transaction category code carried by the category-balance rows. Deliberately {@code "0001"} —
   * distinct from the constant {@code "0005"} that the service hard-codes into the interest
   * transaction — so the field tests prove the written {@code TRAN-CAT-CD} is the constant, not the
   * row's category.
   */
  private static final String REC_CAT = "0001";

  /** A 16-character card number from the cross-reference, copied to {@code TRAN-CARD-NUM}. */
  private static final String CARD_NUM = "1234567890123456";

  /**
   * Fixed clock pinned to {@code 2022-07-18T00:00:00Z} in UTC. The service derives its DB2-format
   * timestamp from {@code LocalDateTime.now(clock)}, so this clock makes {@code
   * TRAN-ORIG-TS}/{@code TRAN-PROC-TS} the deterministic sentinel {@link #EXPECTED_DB2_TS}.
   */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2022-07-18T00:00:00Z"), ZoneOffset.UTC);

  /**
   * The 26-character DB2-format timestamp produced for {@link #FIXED_CLOCK}: {@code
   * YYYY-MM-DD-HH.MM.SS.<hundredths>0000} with zero hundredths at midnight.
   */
  private static final String EXPECTED_DB2_TS = "2022-07-18-00.00.00.000000";

  @Mock private TransactionCategoryBalanceRepository tranCatBalanceRepository;
  @Mock private DisclosureGroupRepository disclosureGroupRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private CardXrefRepository cardXrefRepository;
  @Mock private TransactionRepository transactionRepository;

  /** System under test, constructed in {@link #setUp()} from the five mocked repositories. */
  private InterestCalculationService service;

  @BeforeEach
  void setUp() {
    // The production constructor takes exactly the five repositories (no Clock); the clock is a
    // package-private test seam set immediately afterwards so every timestamp is deterministic.
    service =
        new InterestCalculationService(
            tranCatBalanceRepository,
            disclosureGroupRepository,
            accountRepository,
            cardXrefRepository,
            transactionRepository);
    service.setClock(FIXED_CLOCK);
  }

  // ===== Phase A: truncation parity (the headline assertion) =====================================

  @Test
  void monthly_interest_is_truncated_down_not_rounded_half_up() {
    // One category-balance row: balance 1000.00 at disclosure rate 15.07.
    // (1000.00 * 15.07) / 1200 = 15070 / 1200 = 12.5583... -> truncate DOWN at scale 2 = 12.55.
    // HALF_UP would have produced 12.56, so this vector distinguishes DOWN from HALF_UP.
    Long acctId = 1L;
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    Account account = account(acctId, "0.00", ACCT_GROUP, "5.00", "3.00");
    when(accountRepository.findById(acctId)).thenReturn(Optional.of(account));
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of(xref(CARD_NUM, acctId)));
    when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.of(discGroup("15.07")));

    service.run(PARM_DATE);

    // The written interest transaction carries the truncated amount...
    Transaction interestTx = captureSavedTransaction();
    assertThat(interestTx.getTranAmt()).isEqualByComparingTo("12.55");
    // ...and it is explicitly NOT the HALF_UP value, proving RoundingMode.DOWN.
    assertThat(interestTx.getTranAmt()).isNotEqualByComparingTo("12.56");

    // wsTotalInt = 12.55, so the EOF account update adds exactly 12.55 to the 0.00 opening balance.
    Account saved = captureSavedAccount();
    assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("12.55");
  }

  @Test
  void monthly_interest_truncation_second_vector() {
    // A second independent vector hardening the truncation rule.
    // (500.00 * 19.99) / 1200 = 9995 / 1200 = 8.3291... -> truncate DOWN at scale 2 = 8.32.
    // HALF_UP would have produced 8.33.
    Long acctId = 2L;
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "500.00")));
    when(accountRepository.findById(acctId))
        .thenReturn(Optional.of(account(acctId, "0.00", ACCT_GROUP, "0.00", "0.00")));
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of(xref(CARD_NUM, acctId)));
    when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.of(discGroup("19.99")));

    service.run(PARM_DATE);

    Transaction interestTx = captureSavedTransaction();
    assertThat(interestTx.getTranAmt()).isEqualByComparingTo("8.32");
    assertThat(interestTx.getTranAmt()).isNotEqualByComparingTo("8.33");
  }

  // ===== Phase B: control break + final EOF update (N accounts = N updates) ======================

  @Test
  void control_break_updates_previous_account_and_eof_updates_final_account() {
    // Two accounts in ascending order (111 then 222), one category row each, both non-zero rate.
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(
            List.of(
                tcb(111L, REC_TYPE, REC_CAT, "1000.00"), tcb(222L, REC_TYPE, REC_CAT, "2000.00")));
    Account acct111 = account(111L, "100.00", ACCT_GROUP, "5.00", "3.00");
    Account acct222 = account(222L, "200.00", ACCT_GROUP, "7.00", "9.00");
    when(accountRepository.findById(111L)).thenReturn(Optional.of(acct111));
    when(accountRepository.findById(222L)).thenReturn(Optional.of(acct222));
    when(cardXrefRepository.findByXrefAcctId(111L)).thenReturn(List.of(xref(CARD_NUM, 111L)));
    when(cardXrefRepository.findByXrefAcctId(222L)).thenReturn(List.of(xref(CARD_NUM, 222L)));
    // Both rows share the same (group, type, cat) disclosure key, so a single stub serves both.
    when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.of(discGroup("12.00")));

    service.run(PARM_DATE);

    // Exactly two account updates: 111 on the control break to 222, then 222 at end-of-file.
    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository, times(2)).save(accountCaptor.capture());
    List<Account> savedAccounts = accountCaptor.getAllValues();
    assertThat(savedAccounts).hasSize(2);

    // First save = account 111: 100.00 + (1000.00 * 12.00 / 1200 = 10.00) = 110.00; cyc fields
    // zero.
    Account savedFirst = savedAccounts.get(0);
    assertThat(savedFirst.getAcctId()).isEqualTo(111L);
    assertThat(savedFirst.getAcctCurrBal()).isEqualByComparingTo("110.00");
    assertThat(savedFirst.getAcctCurrCycCredit()).isEqualByComparingTo("0");
    assertThat(savedFirst.getAcctCurrCycDebit()).isEqualByComparingTo("0");

    // Second save = account 222: 200.00 + (2000.00 * 12.00 / 1200 = 20.00) = 220.00; cyc zeroed.
    Account savedSecond = savedAccounts.get(1);
    assertThat(savedSecond.getAcctId()).isEqualTo(222L);
    assertThat(savedSecond.getAcctCurrBal()).isEqualByComparingTo("220.00");
    assertThat(savedSecond.getAcctCurrCycCredit()).isEqualByComparingTo("0");
    assertThat(savedSecond.getAcctCurrCycDebit()).isEqualByComparingTo("0");
  }

  @Test
  void single_account_is_updated_once_at_eof() {
    Long acctId = 42L;
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    when(accountRepository.findById(acctId))
        .thenReturn(Optional.of(account(acctId, "0.00", ACCT_GROUP, "0.00", "0.00")));
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of(xref(CARD_NUM, acctId)));
    when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.of(discGroup("12.00")));

    service.run(PARM_DATE);

    // The first-time guard prevents a premature update; only the post-loop EOF post fires.
    verify(accountRepository, times(1)).save(any(Account.class));
  }

  // ===== Phase C: DISCGRP DEFAULT fallback =======================================================

  @Test
  void uses_default_disclosure_group_when_specific_group_missing() {
    Long acctId = 7L;
    String missingGroup = "NOSUCHGRP1"; // 10 chars; no specific disclosure row exists for it.
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    when(accountRepository.findById(acctId))
        .thenReturn(Optional.of(account(acctId, "0.00", missingGroup, "0.00", "0.00")));
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of(xref(CARD_NUM, acctId)));
    // The account's specific group lookup misses (FILE STATUS '23')...
    when(disclosureGroupRepository.findById(new DisclosureGroupId(missingGroup, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.empty());
    // ...so the service falls back to the DEFAULT group, which supplies a 12.00 rate.
    when(disclosureGroupRepository.findById(
            new DisclosureGroupId(DEFAULT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.of(discGroup("12.00")));

    service.run(PARM_DATE);

    // Interest is computed from the DEFAULT rate: (1000.00 * 12.00) / 1200 = 12000 / 1200 = 10.00.
    Transaction interestTx = captureSavedTransaction();
    assertThat(interestTx.getTranAmt()).isEqualByComparingTo("10.00");
  }

  @Test
  void abends_when_specific_and_default_disclosure_groups_both_missing() {
    Long acctId = 8L;
    String missingGroup = "NOSUCHGRP2"; // 10 chars; neither it nor DEFAULT has a disclosure row.
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    when(accountRepository.findById(acctId))
        .thenReturn(Optional.of(account(acctId, "0.00", missingGroup, "0.00", "0.00")));
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of(xref(CARD_NUM, acctId)));
    when(disclosureGroupRepository.findById(new DisclosureGroupId(missingGroup, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.empty());
    when(disclosureGroupRepository.findById(
            new DisclosureGroupId(DEFAULT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.empty());

    // The missing DEFAULT row is a hard abend (1200-A accepts only '00'), never a silent skip.
    assertThatThrownBy(() -> service.run(PARM_DATE))
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("DISCGRP");
              assertThat(ex.getFileStatus()).isEqualTo("23");
            });
  }

  // ===== Phase D: abend on missing account / cross-reference =====================================

  @Test
  void abends_with_acctfile_status_23_when_account_missing() {
    Long acctId = 9L;
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    // 1100-GET-ACCT-DATA accepts only FILE STATUS '00'; a missing account ('23') abends.
    when(accountRepository.findById(acctId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.run(PARM_DATE))
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("ACCTFILE");
              assertThat(ex.getFileStatus()).isEqualTo("23");
              assertThat(ex.getOperation()).isEqualTo("READ");
            });
  }

  @Test
  void abends_when_xref_missing_for_account() {
    Long acctId = 10L;
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    when(accountRepository.findById(acctId))
        .thenReturn(Optional.of(account(acctId, "0.00", ACCT_GROUP, "0.00", "0.00")));
    // 1110-GET-XREF-DATA accepts only FILE STATUS '00'; an empty alternate-index read abends.
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of());

    assertThatThrownBy(() -> service.run(PARM_DATE))
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("XREFFILE");
              assertThat(ex.getFileStatus()).isEqualTo("23");
            });
  }

  // ===== Phase E: interest transaction written ONLY when rate != 0 ===============================

  @Test
  void zero_rate_record_writes_no_interest_transaction_but_still_updates_account() {
    Long acctId = 11L;
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    Account account = account(acctId, "250.00", ACCT_GROUP, "5.00", "3.00");
    when(accountRepository.findById(acctId)).thenReturn(Optional.of(account));
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of(xref(CARD_NUM, acctId)));
    // A zero disclosure rate suppresses both 1300-COMPUTE-INTEREST and the 1300-B-WRITE-TX write.
    when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.of(discGroup("0.00")));

    service.run(PARM_DATE);

    // No interest transaction is ever written when the rate is zero.
    verify(transactionRepository, never()).save(any(Transaction.class));

    // The account is still posted once at EOF: balance unchanged (wsTotalInt = 0), cyc zeroed.
    Account saved = captureSavedAccount();
    assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("250.00");
    assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("0");
    assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("0");
  }

  // ===== Phase F: interest transaction fields + DB2 timestamp + cursor order =====================

  @Test
  void interest_transaction_has_expected_fields_and_db2_timestamp() {
    Long acctId = 111L;
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(List.of(tcb(acctId, REC_TYPE, REC_CAT, "1000.00")));
    when(accountRepository.findById(acctId))
        .thenReturn(Optional.of(account(acctId, "0.00", ACCT_GROUP, "0.00", "0.00")));
    when(cardXrefRepository.findByXrefAcctId(acctId)).thenReturn(List.of(xref(CARD_NUM, acctId)));
    when(disclosureGroupRepository.findById(new DisclosureGroupId(ACCT_GROUP, REC_TYPE, REC_CAT)))
        .thenReturn(Optional.of(discGroup("15.07")));

    service.run(PARM_DATE);

    Transaction tx = captureSavedTransaction();

    // TRAN-ID = PARM-DATE(10) + zero-padded 6-digit suffix (first write -> "000001"); 16 chars.
    assertThat(tx.getTranId()).hasSize(16).isEqualTo("2022071800000001");
    // MOVE '01' TO TRAN-TYPE-CD.
    assertThat(tx.getTranTypeCd()).isEqualTo("01");
    // MOVE '05' TO TRAN-CAT-CD (PIC 9(04)) stores "0005" -- NOT the row's category code "0001".
    assertThat(tx.getTranCatCd()).isEqualTo("0005");
    // MOVE 'System' TO TRAN-SOURCE (PIC X(10)) -> left-justified, space-padded to width 10.
    assertThat(tx.getTranSource()).isEqualTo("System" + " ".repeat(4));
    // STRING 'Int. for a/c ', ACCT-ID -> 'Int. for a/c ' + zero-padded 11-digit id, padded to 100.
    assertThat(tx.getTranDesc()).hasSize(100).startsWith("Int. for a/c 00000000111");
    assertThat(tx.getTranDesc().strip()).isEqualTo("Int. for a/c 00000000111");
    // TRAN-AMT = the truncated monthly interest (1000.00 * 15.07 / 1200 = 12.55).
    assertThat(tx.getTranAmt()).isEqualByComparingTo("12.55");
    // MOVE 0 TO TRAN-MERCHANT-ID.
    assertThat(tx.getTranMerchantId()).isEqualTo(0L);
    // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM.
    assertThat(tx.getTranCardNum()).isEqualTo(CARD_NUM);

    // TRAN-ORIG-TS / TRAN-PROC-TS: the 26-character DB2-format timestamp from the fixed clock.
    assertThat(tx.getTranProcTs())
        .hasSize(26)
        .matches("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}$")
        .endsWith("0000")
        .isEqualTo(EXPECTED_DB2_TS);
    assertThat(tx.getTranOrigTs()).isEqualTo(tx.getTranProcTs());
  }

  @Test
  void cursor_reads_tcatbal_in_ascending_composite_order() {
    // An empty cursor suffices: the run reads nothing, but findAll is still invoked with the
    // ascending composite Sort that guarantees records are grouped by account for the control
    // break.
    when(tranCatBalanceRepository.findAll(any(Sort.class))).thenReturn(List.of());

    service.run(PARM_DATE);

    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
    verify(tranCatBalanceRepository).findAll(sortCaptor.capture());

    Sort usedSort = sortCaptor.getValue();
    assertThat(usedSort).isEqualTo(Sort.by("id.trancatAcctId", "id.trancatTypeCd", "id.trancatCd"));

    List<Sort.Order> orders = usedSort.stream().toList();
    assertThat(orders)
        .extracting(Sort.Order::getProperty)
        .containsExactly("id.trancatAcctId", "id.trancatTypeCd", "id.trancatCd");
    assertThat(orders).allMatch(Sort.Order::isAscending);
  }

  // ===== capture helpers + entity factories ======================================================

  /** Verifies a single interest {@link Transaction} was written and returns the captured value. */
  private Transaction captureSavedTransaction() {
    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    return captor.getValue();
  }

  /** Verifies a single {@link Account} update was written and returns the captured value. */
  private Account captureSavedAccount() {
    ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(captor.capture());
    return captor.getValue();
  }

  /**
   * Builds a category-balance row (COBOL {@code TRAN-CAT-BAL-RECORD}) with the given composite key
   * and balance.
   *
   * @param acctId the account id ({@code TRANCAT-ACCT-ID})
   * @param typeCd the transaction type code ({@code TRANCAT-TYPE-CD})
   * @param catCd the transaction category code ({@code TRANCAT-CD})
   * @param bal the category balance ({@code TRAN-CAT-BAL}), parsed as a {@link BigDecimal}
   * @return a populated {@link TransactionCategoryBalance}
   */
  private static TransactionCategoryBalance tcb(
      Long acctId, String typeCd, String catCd, String bal) {
    TransactionCategoryBalance row = new TransactionCategoryBalance();
    row.setId(new TransactionCategoryBalanceId(acctId, typeCd, catCd));
    row.setTranCatBal(new BigDecimal(bal));
    return row;
  }

  /**
   * Builds an account-master row (COBOL {@code ACCOUNT-RECORD}) with the fields the interest job
   * reads and rewrites. Fields the service never touches are left unset.
   *
   * @param acctId the account id ({@code ACCT-ID})
   * @param currBal the current balance ({@code ACCT-CURR-BAL})
   * @param groupId the disclosure account-group id ({@code ACCT-GROUP-ID})
   * @param cycCredit the current-cycle credit ({@code ACCT-CURR-CYC-CREDIT})
   * @param cycDebit the current-cycle debit ({@code ACCT-CURR-CYC-DEBIT})
   * @return a populated {@link Account}
   */
  private static Account account(
      Long acctId, String currBal, String groupId, String cycCredit, String cycDebit) {
    Account account = new Account();
    account.setAcctId(acctId);
    account.setAcctCurrBal(new BigDecimal(currBal));
    account.setAcctGroupId(groupId);
    account.setAcctCurrCycCredit(new BigDecimal(cycCredit));
    account.setAcctCurrCycDebit(new BigDecimal(cycDebit));
    return account;
  }

  /**
   * Builds a card cross-reference row (COBOL {@code CARD-XREF-RECORD}) keyed to the given account.
   *
   * @param cardNum the card number ({@code XREF-CARD-NUM}), copied to {@code TRAN-CARD-NUM}
   * @param acctId the account id ({@code XREF-ACCT-ID}) used by the alternate-index read
   * @return a populated {@link CardXref}
   */
  private static CardXref xref(String cardNum, Long acctId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(cardNum);
    xref.setXrefAcctId(acctId);
    return xref;
  }

  /**
   * Builds a disclosure-group row (COBOL {@code DIS-GROUP-RECORD}) carrying only the interest rate;
   * the composite key is irrelevant because the service reads only {@code DIS-INT-RATE} from the
   * looked-up record.
   *
   * @param rate the disclosure interest rate ({@code DIS-INT-RATE}), parsed as a {@link BigDecimal}
   * @return a populated {@link DisclosureGroup}
   */
  private static DisclosureGroup discGroup(String rate) {
    DisclosureGroup group = new DisclosureGroup();
    group.setDisIntRate(new BigDecimal(rate));
    return group;
  }
}
