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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
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
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolStringUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Golden-file parity test for {@link InterestCalculationService} (the Java port of legacy COBOL
 * {@code CBACT04C}, driven by {@code legacy/app/jcl/INTCALC.jcl} with {@code PARM='2022071800'}).
 *
 * <p><strong>What this test pins (and why it exists).</strong> The shipped golden fixtures {@code
 * src/test/resources/golden/interest/interest-smoke.interest-tran.dat} and {@code
 * interest-smoke.account-balances.txt} encode the byte-exact expected output of the documented
 * {@code interest-smoke} scenario (see {@code golden/interest/README.md}). Before this test those
 * fixtures were not consumed by any test, so the interest job's <em>output-file byte parity</em>
 * was asserted nowhere. This test wires them: it runs the real service over the curated scenario,
 * renders every written interest {@code TRAN-RECORD} into the fixed-width {@code CVTRA05Y} 350-byte
 * layout, and compares it byte-for-byte to {@code interest-smoke.interest-tran.dat}; it then
 * compares every resulting {@code ACCT-CURR-BAL} to {@code interest-smoke.account-balances.txt}.
 *
 * <p><strong>Parity points pinned.</strong>
 *
 * <ul>
 *   <li><em>Truncation</em> (AAP &sect;0.6.1) — record&nbsp;1 is the truncation proof: {@code
 *       1234.56 &times; 12.00 / 1200 = 12.3456} must become {@code 12.34} (zoned {@code
 *       0000000123D}), never {@code 12.35}. A rounding implementation would fail the byte compare.
 *   <li><em>Zero-rate skip</em> — account {@code 00000000022} maps only to the {@code ZEROAPR}
 *       group (rate {@code 0.00}); the {@code IF DIS-INT-RATE NOT = 0} gate is false, so no
 *       interest transaction is written and the global suffix is not incremented (suffixes run
 *       {@code 000001}-{@code 000003} with no gap).
 *   <li><em>{@code DEFAULT}-group fallback</em> — account {@code 00000000033} uses group {@code
 *       XYZ}, absent from {@code DISCGRP}, so the {@code DEFAULT} group rate ({@code 9.00})
 *       applies.
 *   <li><em>Fixed-width / overpunch / sentinel-timestamp fidelity</em> — exactly 350 ASCII bytes
 *       per record with the trailing-overpunch signed {@code TRAN-AMT}; the {@link #FIXED_CLOCK}
 *       reproduces the deterministic DB2 sentinel {@code 2022-07-18-00.00.00.000000} stored in the
 *       golden, so the full 350 bytes (including both timestamps) compare byte-for-byte with no
 *       masking.
 *   <li><em>Resulting balances</em> — {@code 00000000011 &rarr; 1042.34} ({@code 1000.00 + 12.34 +
 *       30.00}), {@code 00000000022 &rarr; 500.00} (unchanged), {@code 00000000033 &rarr; 104.50}
 *       ({@code 100.00 + 4.50}).
 * </ul>
 *
 * <p><strong>Wiring.</strong> This is a <em>pure</em> Mockito unit test (no Spring context, no
 * Testcontainers, no database — AAP &sect;0.6.7 local-only validation): the five repository
 * collaborators are mocked to return the curated {@code interest-smoke} inputs, a fixed {@link
 * Clock} is injected through the package-private {@code setClock} seam so the generated timestamps
 * are deterministic, and the service is exercised through its public {@link
 * InterestCalculationService#run(String)} entry point exactly as the {@code interestCalculationJob}
 * tasklet invokes it. The interest job writes each {@code TRAN-RECORD} to the database via {@link
 * TransactionRepository#save(Object)} (not to a flat file), so the written rows are captured with
 * an {@link ArgumentCaptor} and serialized here through the same {@code CVTRA05Y} fixed-width
 * layout the legacy file used.
 */
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InterestCalculationGoldenParityTest {

  /** The run-date {@code PARM} from {@code INTCALC.jcl} ({@code PARM='2022071800'}). */
  private static final String PARM_DATE = "2022071800";

  /** Transaction type code carried by the category-balance rows (drives the disclosure lookup). */
  private static final String REC_TYPE = "01";

  /**
   * {@code ZEROAPR} account-group id space-padded to the {@code DIS-ACCT-GROUP-ID PIC X(10)} width.
   * The service builds the disclosure key with {@code padRight(acctGroupId, 10)}, so the stubbed
   * key must use the padded form.
   */
  private static final String ZEROAPR_GROUP = "ZEROAPR" + " ".repeat(3);

  /**
   * {@code DEFAULT} account-group id (COBOL {@code DEFAULT}) space-padded to the {@code X(10)}
   * width — the key re-read by {@code 1200-A-GET-DEFAULT-INT-RATE} when the account's own group is
   * absent.
   */
  private static final String DEFAULT_GROUP = "DEFAULT" + " ".repeat(3);

  /**
   * {@code XYZ} account-group id (account {@code 00000000033}'s own group) space-padded to the
   * {@code X(10)} width. This group is deliberately absent from {@code DISCGRP}: the {@code
   * 1200-GET-INTEREST-RATE} keyed read returns {@code Optional.empty()} (COBOL FILE STATUS {@code
   * '23'}), which drives the {@code 1200-A} {@code DEFAULT}-group fallback.
   */
  private static final String XYZ_GROUP = "XYZ" + " ".repeat(7);

  /**
   * Fixed clock pinned to {@code 2022-07-18T00:00:00Z} in UTC. The service derives its DB2-format
   * timestamp from {@code LocalDateTime.now(clock)}, so this clock makes {@code
   * TRAN-ORIG-TS}/{@code TRAN-PROC-TS} the deterministic sentinel {@code
   * 2022-07-18-00.00.00.000000} stored in the golden, enabling a full 350-byte comparison with no
   * masking.
   */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2022-07-18T00:00:00Z"), ZoneOffset.UTC);

  /** Exact fixed-width length, in characters, of a {@code CVTRA05Y} transaction record. */
  private static final int RECORD_LENGTH = 350;

  /**
   * Trailing-overpunch characters for a non-negative final digit ({@code 0 -> '{'}, {@code 1..9 ->
   * 'A'..'I'}). Mirrors {@code TransactionCombineStep} so the rendered record is byte-identical to
   * the legacy {@code PIC S9(09)V99} display field.
   */
  private static final char[] POS_OVERPUNCH = {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

  /**
   * Trailing-overpunch characters for a negative final digit ({@code 0 -> '}'}, {@code 1..9 ->
   * 'J'..'R'}).
   */
  private static final char[] NEG_OVERPUNCH = {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

  /** Classpath location of the expected interest {@code TRAN-RECORD} golden. */
  private static final String GOLDEN_TRAN = "/golden/interest/interest-smoke.interest-tran.dat";

  /** Classpath location of the expected resulting-balances golden. */
  private static final String GOLDEN_BALANCES =
      "/golden/interest/interest-smoke.account-balances.txt";

  @Mock private TransactionCategoryBalanceRepository tranCatBalanceRepository;
  @Mock private DisclosureGroupRepository disclosureGroupRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private CardXrefRepository cardXrefRepository;
  @Mock private TransactionRepository transactionRepository;

  /** System under test, constructed in {@link #setUp()} from the five mocked repositories. */
  private InterestCalculationService service;

  @BeforeEach
  void setUp() {
    service =
        new InterestCalculationService(
            tranCatBalanceRepository,
            disclosureGroupRepository,
            accountRepository,
            cardXrefRepository,
            transactionRepository);
    service.setClock(FIXED_CLOCK);
  }

  @Test
  void interest_smoke_output_matches_golden_tran_records_and_balances() throws Exception {
    // ----- Curated interest-smoke inputs (golden/interest/README.md)
    // ------------------------------
    // Category balances in ascending composite-key order (account, type, category).
    when(tranCatBalanceRepository.findAll(any(Sort.class)))
        .thenReturn(
            List.of(
                tcb(11L, REC_TYPE, "0001", "1234.56"),
                tcb(11L, REC_TYPE, "0002", "2000.00"),
                tcb(22L, REC_TYPE, "0001", "999.99"),
                tcb(33L, REC_TYPE, "0001", "600.00")));

    // Accounts (opening balance + disclosure account-group id; the service pads the group to 10).
    when(accountRepository.findById(11L))
        .thenReturn(Optional.of(account(11L, "1000.00", "A000000000")));
    when(accountRepository.findById(22L))
        .thenReturn(Optional.of(account(22L, "500.00", "ZEROAPR")));
    when(accountRepository.findById(33L)).thenReturn(Optional.of(account(33L, "100.00", "XYZ")));

    // Card cross-reference (alternate-index read by account id supplies TRAN-CARD-NUM).
    when(cardXrefRepository.findByXrefAcctId(11L))
        .thenReturn(List.of(xref("4111111111111111", 11L)));
    when(cardXrefRepository.findByXrefAcctId(22L))
        .thenReturn(List.of(xref("4222222222222222", 22L)));
    when(cardXrefRepository.findByXrefAcctId(33L))
        .thenReturn(List.of(xref("4333333333333333", 33L)));

    // Disclosure-group rates keyed by (padded group id, type, category).
    when(disclosureGroupRepository.findById(new DisclosureGroupId("A000000000", REC_TYPE, "0001")))
        .thenReturn(Optional.of(discGroup("12.00")));
    when(disclosureGroupRepository.findById(new DisclosureGroupId("A000000000", REC_TYPE, "0002")))
        .thenReturn(Optional.of(discGroup("18.00")));
    when(disclosureGroupRepository.findById(new DisclosureGroupId(ZEROAPR_GROUP, REC_TYPE, "0001")))
        .thenReturn(Optional.of(discGroup("0.00")));
    // Account 00000000033's own group XYZ is absent from DISCGRP: the keyed read returns
    // Optional.empty() (COBOL FILE STATUS '23'), driving the COBOL 1200-A DEFAULT-group fallback.
    // The empty result is stubbed explicitly so the fallback path is exercised faithfully under
    // Mockito strict stubbing (an unstubbed call on an otherwise-stubbed method would instead raise
    // PotentialStubbingProblem rather than returning the empty Optional the COBOL relies on).
    when(disclosureGroupRepository.findById(new DisclosureGroupId(XYZ_GROUP, REC_TYPE, "0001")))
        .thenReturn(Optional.empty());
    when(disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP, REC_TYPE, "0001")))
        .thenReturn(Optional.of(discGroup("9.00")));

    // ----- Run CBACT04C interest calculation for the documented run date -------------------------
    service.run(PARM_DATE);

    // ----- Byte-exact parity of the written interest TRAN-RECORDs --------------------------------
    ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository, times(3)).save(txCaptor.capture());

    StringBuilder rendered = new StringBuilder();
    for (Transaction tx : txCaptor.getAllValues()) {
      rendered.append(toCvtra05yRecord(tx)).append('\n');
    }
    byte[] actualBytes = rendered.toString().getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals(
        readClasspathBytes(GOLDEN_TRAN),
        actualBytes,
        "interest TRAN-RECORD output must match golden/interest/interest-smoke.interest-tran.dat"
            + " byte-for-byte");

    // ----- Resulting account balances match the balances golden ----------------------------------
    ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository, times(3)).save(acctCaptor.capture());
    Map<Long, BigDecimal> savedBalances = new HashMap<>();
    for (Account account : acctCaptor.getAllValues()) {
      savedBalances.put(account.getAcctId(), account.getAcctCurrBal());
    }

    for (String line : readClasspathLines(GOLDEN_BALANCES)) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.split("\\|");
      Long acctId = Long.parseLong(parts[0]);
      BigDecimal expectedBalance = new BigDecimal(parts[1]);
      assertThat(savedBalances)
          .as("balances golden references account %s", parts[0])
          .containsKey(acctId);
      assertThat(savedBalances.get(acctId))
          .as("resulting ACCT-CURR-BAL for account %s", parts[0])
          .isEqualByComparingTo(expectedBalance);
    }
  }

  // ===== fixed-width CVTRA05Y renderer (mirrors TransactionCombineStep.toFixedWidthRecord)
  // ========

  /**
   * Serializes a {@link Transaction} into exactly {@value #RECORD_LENGTH} characters following the
   * {@code CVTRA05Y} fixed-width layout, byte-identical to the production {@code
   * TransactionCombineStep} writer (the interest job writes to the database, so the golden's flat
   * file layout is reconstructed here for the parity comparison).
   *
   * @param t the transaction to serialize (must not be {@code null})
   * @return the 350-character fixed-width record
   */
  private static String toCvtra05yRecord(Transaction t) {
    long merchantId = (t.getTranMerchantId() == null) ? 0L : t.getTranMerchantId();
    StringBuilder record =
        new StringBuilder(RECORD_LENGTH)
            .append(CobolStringUtils.fixedWidth(t.getTranId(), 16))
            .append(CobolStringUtils.fixedWidth(t.getTranTypeCd(), 2))
            .append(CobolStringUtils.padLeftZeros(t.getTranCatCd(), 4))
            .append(CobolStringUtils.fixedWidth(t.getTranSource(), 10))
            .append(CobolStringUtils.fixedWidth(t.getTranDesc(), 100))
            .append(encodeSignedAmount(t.getTranAmt()))
            .append(CobolStringUtils.padLeftZeros(merchantId, 9))
            .append(CobolStringUtils.fixedWidth(t.getTranMerchantName(), 50))
            .append(CobolStringUtils.fixedWidth(t.getTranMerchantCity(), 50))
            .append(CobolStringUtils.fixedWidth(t.getTranMerchantZip(), 10))
            .append(CobolStringUtils.fixedWidth(t.getTranCardNum(), 16))
            .append(CobolStringUtils.fixedWidth(t.getTranOrigTs(), 26))
            .append(CobolStringUtils.fixedWidth(t.getTranProcTs(), 26))
            .append(CobolStringUtils.spaces(20));
    String result = record.toString();
    assertThat(result).as("assembled CVTRA05Y record length").hasSize(RECORD_LENGTH);
    return result;
  }

  /**
   * Encodes a {@link BigDecimal} amount as an 11-byte COBOL zoned-decimal {@code PIC S9(09)V99}
   * display field with a trailing overpunch sign, truncating (never rounding) to scale 2 with
   * {@link RoundingMode#DOWN} — byte-identical to the production {@code encodeSignedAmount}.
   *
   * @param amount the amount to encode; {@code null} is treated as {@link BigDecimal#ZERO}
   * @return the 11-character zoned-decimal string
   */
  private static String encodeSignedAmount(BigDecimal amount) {
    BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
    BigDecimal scaled = value.setScale(2, RoundingMode.DOWN);
    boolean negative = scaled.signum() < 0;
    String digits =
        CobolStringUtils.padLeftZeros(scaled.abs().movePointRight(2).toBigInteger().toString(), 11);
    int lastDigit = digits.charAt(digits.length() - 1) - '0';
    char overpunch = negative ? NEG_OVERPUNCH[lastDigit] : POS_OVERPUNCH[lastDigit];
    return digits.substring(0, digits.length() - 1) + overpunch;
  }

  // ===== classpath golden loaders
  // =================================================================

  /**
   * Reads a golden fixture from the classpath as raw bytes, hard-failing if it is absent so a
   * deleted fixture can never silently erode parity coverage.
   *
   * @param resource the absolute classpath resource path
   * @return the fixture bytes
   * @throws Exception if the resource cannot be located or read
   */
  private static byte[] readClasspathBytes(String resource) throws Exception {
    URL url = InterestCalculationGoldenParityTest.class.getResource(resource);
    assertNotNull(url, "golden fixture must ship on the classpath: " + resource);
    return Files.readAllBytes(Path.of(url.toURI()));
  }

  /**
   * Reads a golden fixture from the classpath as ASCII lines, hard-failing if it is absent.
   *
   * @param resource the absolute classpath resource path
   * @return the fixture lines
   * @throws Exception if the resource cannot be located or read
   */
  private static List<String> readClasspathLines(String resource) throws Exception {
    URL url = InterestCalculationGoldenParityTest.class.getResource(resource);
    assertNotNull(url, "golden fixture must ship on the classpath: " + resource);
    return Files.readAllLines(Path.of(url.toURI()), StandardCharsets.US_ASCII);
  }

  // ===== entity factories
  // =========================================================================

  /**
   * Builds a category-balance row ({@code TRAN-CAT-BAL-RECORD}) with the given composite key and
   * balance.
   *
   * @param acctId the account id ({@code TRANCAT-ACCT-ID})
   * @param typeCd the transaction type code ({@code TRANCAT-TYPE-CD})
   * @param catCd the transaction category code ({@code TRANCAT-CD})
   * @param bal the category balance ({@code TRAN-CAT-BAL})
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
   * Builds an account-master row ({@code ACCOUNT-RECORD}) with the fields the interest job reads
   * and rewrites. The current-cycle credit/debit fields are seeded to zero (the service overwrites
   * them to zero on update); fields the service never touches are left unset.
   *
   * @param acctId the account id ({@code ACCT-ID})
   * @param currBal the current balance ({@code ACCT-CURR-BAL})
   * @param groupId the disclosure account-group id ({@code ACCT-GROUP-ID}); the service pads it to
   *     the {@code X(10)} width before the disclosure lookup
   * @return a populated {@link Account}
   */
  private static Account account(Long acctId, String currBal, String groupId) {
    Account account = new Account();
    account.setAcctId(acctId);
    account.setAcctCurrBal(new BigDecimal(currBal));
    account.setAcctGroupId(groupId);
    account.setAcctCurrCycCredit(new BigDecimal("0.00"));
    account.setAcctCurrCycDebit(new BigDecimal("0.00"));
    return account;
  }

  /**
   * Builds a card cross-reference row ({@code CARD-XREF-RECORD}) keyed to the given account.
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
   * Builds a disclosure-group row ({@code DIS-GROUP-RECORD}) carrying the interest rate the service
   * reads ({@code DIS-INT-RATE}).
   *
   * @param rate the disclosure interest rate ({@code DIS-INT-RATE})
   * @return a populated {@link DisclosureGroup}
   */
  private static DisclosureGroup discGroup(String rate) {
    DisclosureGroup group = new DisclosureGroup();
    group.setDisIntRate(new BigDecimal(rate));
    return group;
  }
}
