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
package com.aws.carddemo.service.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.AccountUpdateScreen;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link AccountUpdateService}, the online
 * account-update (and associated customer-update) business-logic service migrated from the legacy
 * CICS COBOL program {@code COACTUPC} (CICS transaction {@code CAUP}) — the largest and most
 * complex online program in AWS CardDemo. Behavioral spec: {@code legacy/app/cbl/COACTUPC.cbl};
 * record layouts {@code legacy/app/cpy/CVACT01Y.cpy} (account, five monetary fields), {@code
 * CVCUS01Y.cpy} (customer) and {@code CVACT03Y.cpy} (card cross-reference); COMMAREA {@code
 * COCOM01Y.cpy}.
 *
 * <p><strong>Collaborators.</strong> The service injects three Spring Data repositories — {@link
 * AccountRepository}, {@link CustomerRepository} and {@link CardXrefRepository} — which are mocked
 * here so the tests run with no Spring context and no database. The date edits delegate to the
 * <em>static</em> utility {@link com.aws.carddemo.util.DateValidationService}; because it is a
 * non-instantiable, stateless JDK-only function (separately unit-tested) it is exercised for real
 * with genuinely valid / invalid date strings rather than mocked — the faithful adaptation of the
 * agent prompt's "mock DateValidationService (adapt if static)" instruction. The state-code, ZIP
 * and phone area-code lookups similarly delegate to the static {@code LookupCodes} and are used for
 * real.
 *
 * <p><strong>Byte-exact message parity (AAP §0.7.1, §0.7.3).</strong> Every field-edit,
 * cross-field, not-found, optimistic-conflict and success literal is asserted against the
 * production constants declared on {@link AccountUpdateService} (package-private, hence visible to
 * this same-package test), so a single-byte drift fails the build. The date-edit literals originate
 * in {@code DateValidationService} and are asserted as exact strings.
 *
 * <p><strong>Control-flow parity (AAP §0.6.5).</strong> The read chain (xref &rarr; account &rarr;
 * customer) and the optimistic <em>read-for-update &rarr; compare &rarr; REWRITE</em> ordering are
 * asserted with Mockito {@link InOrder}. The five monetary fields are fixed-width {@code PIC X(15)}
 * text on the screen (the Issue-2 parity fix — validated char-by-char before {@code NUMVAL-C}
 * parsing) and are asserted as scale-2 plain strings on display; the persisted account entity still
 * stores them as {@link BigDecimal} truncated to scale 2, asserted on the captured save (AAP §0.6.1
 * — no floating-point for decimal data).
 *
 * <p><strong>Optimistic update parity (AAP §0.3.3, §0.6.5, §0.6.6).</strong> The entities carry no
 * {@code @Version}; the service captures the display-turn snapshot in hidden {@code old*} fields
 * and carries it across the pseudo-conversational boundary, then on the confirm turn re-reads the
 * record <em>once</em> for update and compares it, field by field, against that carried snapshot —
 * <em>not</em> a fresh same-turn re-read (the Issue-1 lost-update fix). Tests seed the carried
 * snapshot and drive the {@code DATA-WAS-CHANGED} branch by stubbing a divergent read-for-update,
 * asserting that no overwrite occurs.
 *
 * <p><strong>Read-only redisplay vs. abend (AAP §0.6.4).</strong> Field-validation failures and
 * record-not-found conditions set an on-screen message and redisplay (return {@code null}); only an
 * unexpected {@link org.springframework.dao.DataAccessException} on the read-for-update is mapped
 * to an {@link IoStatusException}. Account update is <em>not</em> admin-gated, so no role check is
 * asserted.
 */
class AccountUpdateServiceTest {

  /** Resolved account id used across the lookup / update tests (filter {@code "00000000001"}). */
  private static final long ACCT_ID = 1L;

  /** Account-id filter exactly as a user would key it into {@code ACCTSID} (11 digits). */
  private static final String ACCT_SID = "00000000001";

  /** Resolved customer id (from the cross-reference) used across the update tests. */
  private static final long CUST_ID = 9L;

  /** A representative 16-character card number stored on the cross-reference record. */
  private static final String CARD_NUM = "1234567890123456";

  /**
   * A monetary text input one order of magnitude beyond the legacy {@code PIC S9(10)V99} capacity
   * ({@code 9999999999.99}); a syntactically valid {@code TEST-NUMVAL-C} number whose parsed
   * magnitude exceeds the field capacity, exercising the {@code 1250-EDIT-SIGNED-9V2} "is not
   * valid" trigger. Monetary fields are now fixed-width {@code PIC X(15)} text (the parity fix), so
   * this is supplied as a {@code String} exactly as a user would key it.
   */
  private static final String OVER_CAPACITY = "99999999999.99";

  /**
   * Byte-exact open-date error produced by the real {@code DateValidationService.editDateCcyymmdd}
   * for a non-{@code 1..12} month (field label {@code "Open Date"}). Retained as a constant so the
   * date-edit parity assertions read clearly.
   */
  private static final String OPEN_DATE_BAD_MONTH =
      "Open Date: Month must be a number between 1 and 12.";

  /**
   * The {@code DateValidationService} structural-edit suffix for an out-of-range month ({@code name
   * + this}). Used to assemble the byte-exact open/expiry/reissue/DOB date messages.
   */
  private static final String DATE_BAD_MONTH_SFX = ": Month must be a number between 1 and 12.";

  // Mocked collaborators (the migrated VSAM read/REWRITE paths).
  private AccountRepository accountRepository;
  private CustomerRepository customerRepository;
  private CardXrefRepository cardXrefRepository;

  /** Service under test. Stateless singleton, so a fresh instance per test is sufficient. */
  private AccountUpdateService service;

  @BeforeEach
  void setUp() {
    accountRepository = mock(AccountRepository.class);
    customerRepository = mock(CustomerRepository.class);
    cardXrefRepository = mock(CardXrefRepository.class);
    service = new AccountUpdateService(accountRepository, customerRepository, cardXrefRepository);
  }

  // ===== Fixtures / helpers
  // =======================================================================

  /**
   * The persisted "OLD" account snapshot. All five monetary fields are {@link BigDecimal} at scale
   * 2 ({@code PIC S9(10)V99}); the dates are the entity {@code yyyy-MM-dd} form. The current
   * balance ({@code 1234.56}) deliberately differs from {@link #validScreen()}'s {@code 2000.00} so
   * that the change-detection edit ({@code 1205-COMPARE-OLD-NEW}) always reports a change and the
   * field edits run.
   */
  private static Account account(long id) {
    Account account = new Account();
    account.setAcctId(id);
    account.setAcctActiveStatus("Y");
    account.setAcctCurrBal(new BigDecimal("1234.56"));
    account.setAcctCreditLimit(new BigDecimal("5000.00"));
    account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
    account.setAcctCurrCycCredit(new BigDecimal("250.00"));
    account.setAcctCurrCycDebit(new BigDecimal("75.00"));
    account.setAcctOpenDate("2010-01-15");
    account.setAcctExpiraionDate("2030-12-31");
    account.setAcctReissueDate("2015-06-01");
    account.setAcctGroupId("GRP1");
    account.setAcctAddrZip("90210");
    return account;
  }

  /**
   * The persisted "OLD" customer snapshot — fully valid name / SSN / FICO / address / state / ZIP /
   * phones so that the read path paints a complete screen and the change-detection comparison has a
   * faithful baseline.
   */
  private static Customer customer(long id) {
    Customer customer = new Customer();
    customer.setCustId(id);
    customer.setCustFirstName("JOHN");
    customer.setCustMiddleName("Q");
    customer.setCustLastName("PUBLIC");
    customer.setCustAddrLine1("123 MAIN STREET");
    customer.setCustAddrLine2("APT 4");
    customer.setCustAddrLine3("LOS ANGELES");
    customer.setCustAddrStateCd("CA");
    customer.setCustAddrCountryCd("USA");
    customer.setCustAddrZip("90210");
    customer.setCustPhoneNum1("(212)555-0123");
    customer.setCustPhoneNum2("(415)555-0199");
    customer.setCustSsn(123456789L);
    customer.setCustGovtIssuedId("G12345678");
    customer.setCustDobYyyyMmDd("1980-05-15");
    customer.setCustEftAccountId("1234567890");
    customer.setCustPriCardHolderInd("Y");
    customer.setCustFicoCreditScore(700L);
    return customer;
  }

  /** A card cross-reference row resolving {@code acctId} to {@code custId} (and a card number). */
  private static CardXref xref(long acctId, long custId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(CARD_NUM);
    xref.setXrefCustId(custId);
    xref.setXrefAcctId(acctId);
    return xref;
  }

  /**
   * A fully populated, fully <em>valid</em> set of NEW screen inputs that passes every one of the
   * 24 field edits and the State/ZIP cross-field edit. Individual tests mutate exactly one field to
   * pin a specific validation stopping point. The monetary fields differ from {@link
   * #account(long)} so change detection always fires.
   */
  private static AccountUpdateScreen validScreen() {
    AccountUpdateScreen screen = new AccountUpdateScreen();
    screen.setAcctSid(ACCT_SID);
    // 1. Account status (Y/N).
    screen.setAcstTus("Y");
    // 2/4/6. Account dates (CCYYMMDD split) — all structurally valid.
    screen.setOpnYear("2010");
    screen.setOpnMon("01");
    screen.setOpnDay("15");
    screen.setExpYear("2030");
    screen.setExpMon("12");
    screen.setExpDay("31");
    screen.setRisYear("2015");
    screen.setRisMon("06");
    screen.setRisDay("01");
    // 3/5/7/8/9. Five monetary fields, fixed-width PIC X(15) text exactly as keyed, within the
    // PIC S9(10)V99 capacity (the Issue-2 parity fix types these as String, validated char-by-char
    // before NUMVAL-C parsing rather than bound straight to BigDecimal).
    screen.setAcrdLim("6000.00");
    screen.setAcshLim("1500.00");
    screen.setAcurBal("2000.00");
    screen.setAcrCycr("300.00");
    screen.setAcrCydb("90.00");
    screen.setAaddGrp("GRP1");
    // 10. SSN parts (part 1 not 000/666/900-999).
    screen.setActSsn1("123");
    screen.setActSsn2("45");
    screen.setActSsn3("6789");
    // 11. Date of birth (valid + in the past).
    screen.setDobYear("1980");
    screen.setDobMon("05");
    screen.setDobDay("15");
    // 12. FICO score in [300, 850].
    screen.setAcstFco("700");
    // 13/14/15. Names (alphabetic; middle optional).
    screen.setAcsFnam("JOHN");
    screen.setAcsMnam("Q");
    screen.setAcsLnam("PUBLIC");
    // 16. Address line 1 (mandatory). Line 2 is intentionally not edited.
    screen.setAcsAdl1("456 OAK AVENUE");
    screen.setAcsAdl2("APT 4");
    // 17/18/19/20. State (valid code), ZIP (numeric), City (alpha), Country (alpha).
    screen.setAcsStte("CA");
    screen.setAcsZipc("90001");
    screen.setAcsCity("LOS ANGELES");
    screen.setAcsCtry("USA");
    // 21/22. Phone numbers (valid general-purpose area codes, numeric prefix/line).
    screen.setAcsPh1a("212");
    screen.setAcsPh1b("555");
    screen.setAcsPh1c("0123");
    screen.setAcsPh2a("415");
    screen.setAcsPh2b("555");
    screen.setAcsPh2c("0199");
    // Government id (not edited here).
    screen.setAcsGovt("G12345678");
    // 23. EFT account id (numeric). 24. Primary card holder (Y/N).
    screen.setAcsEftc("1234567890");
    screen.setAcsPflg("Y");
    // Carry the display-turn OLD snapshot (the factory baseline) in the hidden old* fields so the
    // optimistic-lock reconstruction (loadOldSnapshot) has the original values to compare against
    // without re-reading the database (Issue 1 fix; AAP §0.6.5). The NEW monetary / address values
    // above deliberately differ from this baseline so 1205-COMPARE-OLD-NEW always reports a change.
    carryOldSnapshot(screen, account(ACCT_ID), customer(CUST_ID));
    return screen;
  }

  /**
   * Builds a screen whose every editable field equals the supplied OLD snapshot, exactly as the
   * service's {@code 9500-STORE-FETCHED-DATA} / {@code 3202-SHOW-ORIGINAL-VALUES} would paint it,
   * and carries that same snapshot in the hidden {@code old*} fields. Used by the "no changes
   * detected" test: the reconstructed OLD (from {@code old*}) equals the visible NEW values so
   * {@code 1205-COMPARE-OLD-NEW} reports no change.
   */
  private static AccountUpdateScreen screenFromEntities(Account account, Customer customer) {
    AccountUpdateScreen screen = new AccountUpdateScreen();
    screen.setAcctSid(String.valueOf(account.getAcctId()));
    screen.setAcstNum(String.valueOf(customer.getCustId()));
    screen.setAcstTus(account.getAcctActiveStatus());
    screen.setAcurBal(moneyStr(account.getAcctCurrBal()));
    screen.setAcrdLim(moneyStr(account.getAcctCreditLimit()));
    screen.setAcshLim(moneyStr(account.getAcctCashCreditLimit()));
    screen.setAcrCycr(moneyStr(account.getAcctCurrCycCredit()));
    screen.setAcrCydb(moneyStr(account.getAcctCurrCycDebit()));
    screen.setOpnYear("2010");
    screen.setOpnMon("01");
    screen.setOpnDay("15");
    screen.setExpYear("2030");
    screen.setExpMon("12");
    screen.setExpDay("31");
    screen.setRisYear("2015");
    screen.setRisMon("06");
    screen.setRisDay("01");
    screen.setAaddGrp(account.getAcctGroupId());
    screen.setActSsn1("123");
    screen.setActSsn2("45");
    screen.setActSsn3("6789");
    screen.setAcstFco("700");
    screen.setDobYear("1980");
    screen.setDobMon("05");
    screen.setDobDay("15");
    screen.setAcsFnam(customer.getCustFirstName());
    screen.setAcsMnam(customer.getCustMiddleName());
    screen.setAcsLnam(customer.getCustLastName());
    screen.setAcsAdl1(customer.getCustAddrLine1());
    screen.setAcsAdl2(customer.getCustAddrLine2());
    screen.setAcsCity(customer.getCustAddrLine3());
    screen.setAcsStte(customer.getCustAddrStateCd());
    screen.setAcsZipc(customer.getCustAddrZip());
    screen.setAcsCtry(customer.getCustAddrCountryCd());
    screen.setAcsPh1a("212");
    screen.setAcsPh1b("555");
    screen.setAcsPh1c("0123");
    screen.setAcsPh2a("415");
    screen.setAcsPh2b("555");
    screen.setAcsPh2c("0199");
    screen.setAcsGovt(customer.getCustGovtIssuedId());
    screen.setAcsEftc(customer.getCustEftAccountId());
    screen.setAcsPflg(customer.getCustPriCardHolderInd());
    carryOldSnapshot(screen, account, customer);
    return screen;
  }

  /**
   * Populates the screen's hidden {@code old*} snapshot fields from the supplied account /
   * customer, mirroring the service's {@code captureOldSnapshot} (the Java equivalent of {@code
   * 9500-STORE-FETCHED-DATA} which copies the fetched record into {@code ACUP-OLD-DETAILS} in the
   * COMMAREA). The optimistic-lock fix (Issue&nbsp;1) carries the <em>display-turn</em> snapshot
   * across the pseudo-conversational boundary in these hidden fields rather than re-reading the
   * database on the confirm turn; the unit tests must therefore seed the same snapshot the browser
   * would round-trip so {@code 9700-CHECK-CHANGE-IN-REC} compares the fresh read-for-update against
   * the original display-turn values (AAP §0.6.5). The canonical text forms match the service
   * exactly: monetary fields are scale-2 plain strings, the SSN is nine zero-padded digits, and ids
   * / FICO are decimal strings.
   */
  private static void carryOldSnapshot(AccountUpdateScreen screen, Account a, Customer c) {
    screen.setOldAcctId(a.getAcctId() == null ? null : String.valueOf(a.getAcctId()));
    screen.setOldActiveStatus(a.getAcctActiveStatus());
    screen.setOldCurrBal(moneyStr(a.getAcctCurrBal()));
    screen.setOldCreditLimit(moneyStr(a.getAcctCreditLimit()));
    screen.setOldCashCreditLimit(moneyStr(a.getAcctCashCreditLimit()));
    screen.setOldCurrCycCredit(moneyStr(a.getAcctCurrCycCredit()));
    screen.setOldCurrCycDebit(moneyStr(a.getAcctCurrCycDebit()));
    screen.setOldOpenDate(a.getAcctOpenDate());
    screen.setOldExpiryDate(a.getAcctExpiraionDate());
    screen.setOldReissueDate(a.getAcctReissueDate());
    screen.setOldGroupId(a.getAcctGroupId());
    screen.setOldCustId(c.getCustId() == null ? null : String.valueOf(c.getCustId()));
    screen.setOldFirstName(c.getCustFirstName());
    screen.setOldMiddleName(c.getCustMiddleName());
    screen.setOldLastName(c.getCustLastName());
    screen.setOldAddrLine1(c.getCustAddrLine1());
    screen.setOldAddrLine2(c.getCustAddrLine2());
    screen.setOldAddrLine3(c.getCustAddrLine3());
    screen.setOldStateCd(c.getCustAddrStateCd());
    screen.setOldCountryCd(c.getCustAddrCountryCd());
    screen.setOldZip(c.getCustAddrZip());
    screen.setOldPhone1(c.getCustPhoneNum1());
    screen.setOldPhone2(c.getCustPhoneNum2());
    screen.setOldSsn(c.getCustSsn() == null ? null : String.format("%09d", c.getCustSsn()));
    screen.setOldGovtId(c.getCustGovtIssuedId());
    screen.setOldDob(c.getCustDobYyyyMmDd());
    screen.setOldEftId(c.getCustEftAccountId());
    screen.setOldPriHolder(c.getCustPriCardHolderInd());
    screen.setOldFico(
        c.getCustFicoCreditScore() == null ? null : String.valueOf(c.getCustFicoCreditScore()));
  }

  /** Renders a monetary {@link BigDecimal} as the canonical scale-2, truncated plain string. */
  private static String moneyStr(BigDecimal v) {
    return v == null ? "" : v.setScale(2, RoundingMode.DOWN).toPlainString();
  }

  /**
   * A re-entry COMMAREA with the account/customer keys already resolved — i.e. details have been
   * fetched on a prior turn. {@code CDEMO-PGM-REENTER} is set so the program is not treated as a
   * fresh from-menu entry.
   */
  private static CardDemoCommarea editCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmReenter();
    commarea.setAcctId(ACCT_ID);
    commarea.setCustId(CUST_ID);
    return commarea;
  }

  /**
   * A re-entry COMMAREA with no account fetched yet — drives the {@code DETAILS-NOT-FETCHED} fetch
   * branch (the user keys an account id and presses ENTER).
   */
  private static CardDemoCommarea fetchCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmReenter();
    return commarea;
  }

  // ===== Entry / navigation
  // =======================================================================

  @Test
  @DisplayName("Fresh entry (PGM-ENTER) paints a blank search prompt and touches no repository")
  void freshEntry_paintsBlankSearchPrompt_noIo() {
    // A brand-new commarea defaults to CDEMO-PGM-ENTER, which 0000-MAIN treats as a fresh entry.
    CardDemoCommarea commarea = new CardDemoCommarea();

    String result = service.processAccountUpdate(validScreen(), commarea, CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    // 3201-SHOW-INITIAL-VALUES blanks every detail field and 3250 prompts for the search key.
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(commarea.getAcctId()).isNull();
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  @Test
  @DisplayName("Fresh entry shows the search-keys prompt and a cleared error line")
  void freshEntry_setsSearchKeysPromptAndClearsError() {
    AccountUpdateScreen screen = validScreen();

    service.processAccountUpdate(screen, new CardDemoCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(screen.getInfoMsg()).isEqualTo(AccountUpdateService.INFO_PROMPT_FOR_SEARCH_KEYS);
    assertThat(screen.getErrMsg()).isEmpty();
    // The detail fields are blanked (3201-SHOW-INITIAL-VALUES).
    assertThat(screen.getAcurBal()).isNull();
    assertThat(screen.getAcsFnam()).isNull();
  }

  @Test
  @DisplayName("Blank account filter on the search screen redisplays 'No input received'")
  void blankFilter_redisplaysNoSearchCriteria_noIo() {
    AccountUpdateScreen screen = validScreen();
    screen.setAcctSid(
        "   "); // all-blank filter -> 1210-EDIT-ACCOUNT "not supplied" -> 1200 override

    String result = service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    // The unconditional SET in 1200-EDIT-MAP-INPUTS overrides the 1210 message.
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_NO_SEARCH_CRITERIA);
    assertThat(screen.getInfoMsg()).isEqualTo(AccountUpdateService.INFO_PROMPT_FOR_SEARCH_KEYS);
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  @Test
  @DisplayName("Non-numeric account filter redisplays the 11-digit message and reads nothing")
  void nonNumericFilter_redisplays11DigitMessage_noIo() {
    AccountUpdateScreen screen = validScreen();
    screen.setAcctSid("ABCDEFGHIJK"); // 11 chars but not numeric -> 1210-EDIT-ACCOUNT failure

    String result = service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_ACCT_NOT_11_DIGIT);
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  @Test
  @DisplayName("Zero account filter redisplays the 11-digit message and reads nothing")
  void zeroFilter_redisplays11DigitMessage_noIo() {
    AccountUpdateScreen screen = validScreen();
    screen.setAcctSid("00000000000"); // 11 zero digits -> "Non-Zero" failure in 1210-EDIT-ACCOUNT

    String result = service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_ACCT_NOT_11_DIGIT);
    verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
  }

  @Test
  @DisplayName("PF3 exits to the calling program (main menu by default) with the exit message")
  void pfk03_exitsToMainMenu_withExitMessage_noSave() {
    AccountUpdateScreen screen = validScreen();

    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.PFK03);

    assertThat(result).isEqualTo(AccountUpdateService.MENU_PROGRAM);
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_EXIT);
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("PF3 returns to the recorded caller when the commarea carries a from-program")
  void pfk03_returnsToRecordedCaller() {
    AccountUpdateScreen screen = validScreen();
    CardDemoCommarea commarea = editCommarea();
    commarea.setFromProgram("COCRDLIC");
    commarea.setFromTranId("CCLI");

    String result = service.processAccountUpdate(screen, commarea, CardWorkArea.Aid.PFK03);

    assertThat(result).isEqualTo("COCRDLIC");
    assertThat(commarea.getToProgram()).isEqualTo("COCRDLIC");
    assertThat(commarea.getToTranId()).isEqualTo("CCLI");
    // This program stamps itself as the new "from" context for the next turn.
    assertThat(commarea.getFromProgram()).isEqualTo(AccountUpdateService.PGM_NAME);
    assertThat(commarea.getFromTranId()).isEqualTo(AccountUpdateService.TRAN_ID);
  }

  // ===== Read chain (9000-READ-ACCT) — xref -> account -> customer
  // ================================

  @Test
  @DisplayName(
      "ENTER on a known account reads xref->account->customer IN ORDER and prompts for changes")
  void enter_knownAccount_readsChainInOrder_promptsForChanges_noSave() {
    Account account = account(ACCT_ID);
    Customer customer = customer(CUST_ID);
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    // 9000-READ-ACCT chain order: 9200-GETCARDXREF -> 9300-GETACCTDATA -> 9400-GETCUSTDATA.
    InOrder inOrder = inOrder(cardXrefRepository, accountRepository, customerRepository);
    inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
    inOrder.verify(accountRepository).findById(ACCT_ID);
    inOrder.verify(customerRepository).findById(CUST_ID);
    // 3250 prompts the user to make changes once details are shown.
    assertThat(screen.getInfoMsg()).isEqualTo(AccountUpdateService.INFO_PROMPT_FOR_CHANGES);
    assertThat(screen.getErrMsg()).isEmpty();
    // No write on a read-only display turn.
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName(
      "Loaded screen paints all five monetary fields as scale-2 text (PIC X(15) decimal fidelity)")
  void enter_knownAccount_populatesFiveMoneyFieldsAtScale2() {
    Account account = account(ACCT_ID);
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen();
    service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    // Each of the five PIC S9(10)V99 fields is painted as a canonical scale-2 plain string
    // (moneyToString) — the underlying BigDecimal scale fidelity is asserted on the saved entity in
    // confirm_savedAccount_hasFiveMoneyFieldsAtScale2; here we pin the rendered display text.
    assertThat(screen.getAcurBal()).isEqualTo("1234.56");
    assertThat(screen.getAcrdLim()).isEqualTo("5000.00");
    assertThat(screen.getAcshLim()).isEqualTo("1000.00");
    assertThat(screen.getAcrCycr()).isEqualTo("250.00");
    assertThat(screen.getAcrCydb()).isEqualTo("75.00");
  }

  @Test
  @DisplayName(
      "Loaded screen decomposes the split SSN / phone / date fields exactly as the COBOL moves")
  void enter_knownAccount_populatesSplitFields() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen();
    service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    // Open date "2010-01-15" -> (yyyy)(mm)(dd).
    assertThat(screen.getOpnYear()).isEqualTo("2010");
    assertThat(screen.getOpnMon()).isEqualTo("01");
    assertThat(screen.getOpnDay()).isEqualTo("15");
    // SSN 123456789 -> (3)(2)(4).
    assertThat(screen.getActSsn1()).isEqualTo("123");
    assertThat(screen.getActSsn2()).isEqualTo("45");
    assertThat(screen.getActSsn3()).isEqualTo("6789");
    // Phone "(212)555-0123" -> area / prefix / line.
    assertThat(screen.getAcsPh1a()).isEqualTo("212");
    assertThat(screen.getAcsPh1b()).isEqualTo("555");
    assertThat(screen.getAcsPh1c()).isEqualTo("0123");
    // FICO and date of birth.
    assertThat(screen.getAcstFco()).isEqualTo("700");
    assertThat(screen.getDobYear()).isEqualTo("1980");
    assertThat(screen.getDobMon()).isEqualTo("05");
    assertThat(screen.getDobDay()).isEqualTo("15");
  }

  @Test
  @DisplayName(
      "Cross-reference miss redisplays the xref not-found message; account/customer never read")
  void xrefNotFound_redisplays_noDownstreamReads() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_DID_NOT_FIND_ACCT_XREF);
    verify(accountRepository, never()).findById(any());
    verify(customerRepository, never()).findById(any());
  }

  @Test
  @DisplayName(
      "Account miss (after a good xref) redisplays the account not-found message; customer never read")
  void accountNotFound_redisplays_customerNeverRead() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_DID_NOT_FIND_ACCT);
    verify(customerRepository, never()).findById(any());
  }

  @Test
  @DisplayName("Customer miss (after good xref+account) redisplays the customer not-found message")
  void customerNotFound_redisplays() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, fetchCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_DID_NOT_FIND_CUST);
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Re-displaying unchanged inputs reports 'no changes' and performs no write")
  void noChangesDetected_redisplays_noSave() {
    Account account = account(ACCT_ID);
    Customer customer = customer(CUST_ID);

    // Every editable field equals the OLD snapshot, which is reconstructed from the carried hidden
    // old* fields (no DB read on the compare turn) -> 1205-COMPARE-OLD-NEW finds NO-CHANGES.
    AccountUpdateScreen screen = screenFromEntities(account, customer);
    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_NO_CHANGES_DETECTED);
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  // ===== 24-field validation order (1200-EDIT-MAP-INPUTS)
  // =========================================

  /**
   * The full 24-field edit order of {@code 1200-EDIT-MAP-INPUTS}, one case per field, each spoiling
   * exactly one field while leaving every earlier field valid so that the asserted message pins the
   * precise stopping point. The expected text is assembled from the production constants (the
   * {@code STRING TRIM(name) suffix} construct) so a single-byte drift fails the build. The list is
   * in the exact legacy order; the cross-field state/zip edit (which runs only when both passed) is
   * covered by {@link #crossFieldStateZip_mismatch_redisplays()}.
   */
  private static Stream<Arguments> fieldValidationCases() {
    return Stream.of(
        // 1. Account Status (1220-EDIT-YESNO).
        arguments(
            "01 account status not Y/N",
            (Consumer<AccountUpdateScreen>) s -> s.setAcstTus("X"),
            AccountUpdateService.FLD_ACCT_STATUS + AccountUpdateService.SFX_MUST_BE_Y_OR_N),
        // 2. Open Date (EDIT-DATE-CCYYMMDD).
        arguments(
            "02 open date bad month",
            (Consumer<AccountUpdateScreen>) s -> s.setOpnMon("13"),
            AccountUpdateService.FLD_OPEN_DATE + DATE_BAD_MONTH_SFX),
        // 3. Credit Limit (1250-EDIT-SIGNED-9V2).
        arguments(
            "03 credit limit over capacity",
            (Consumer<AccountUpdateScreen>) s -> s.setAcrdLim(OVER_CAPACITY),
            AccountUpdateService.FLD_CREDIT_LIMIT + AccountUpdateService.SFX_NOT_VALID),
        // 4. Expiry Date.
        arguments(
            "04 expiry date bad month",
            (Consumer<AccountUpdateScreen>) s -> s.setExpMon("13"),
            AccountUpdateService.FLD_EXPIRY_DATE + DATE_BAD_MONTH_SFX),
        // 5. Cash Credit Limit.
        arguments(
            "05 cash credit limit over capacity",
            (Consumer<AccountUpdateScreen>) s -> s.setAcshLim(OVER_CAPACITY),
            AccountUpdateService.FLD_CASH_CREDIT_LIMIT + AccountUpdateService.SFX_NOT_VALID),
        // 6. Reissue Date.
        arguments(
            "06 reissue date bad month",
            (Consumer<AccountUpdateScreen>) s -> s.setRisMon("13"),
            AccountUpdateService.FLD_REISSUE_DATE + DATE_BAD_MONTH_SFX),
        // 7. Current Balance.
        arguments(
            "07 current balance over capacity",
            (Consumer<AccountUpdateScreen>) s -> s.setAcurBal(OVER_CAPACITY),
            AccountUpdateService.FLD_CURRENT_BALANCE + AccountUpdateService.SFX_NOT_VALID),
        // 8. Current Cycle Credit Limit.
        arguments(
            "08 current cycle credit over capacity",
            (Consumer<AccountUpdateScreen>) s -> s.setAcrCycr(OVER_CAPACITY),
            AccountUpdateService.FLD_CURR_CYC_CREDIT + AccountUpdateService.SFX_NOT_VALID),
        // 9. Current Cycle Debit Limit.
        arguments(
            "09 current cycle debit over capacity",
            (Consumer<AccountUpdateScreen>) s -> s.setAcrCydb(OVER_CAPACITY),
            AccountUpdateService.FLD_CURR_CYC_DEBIT + AccountUpdateService.SFX_NOT_VALID),
        // 10. SSN part 1 invalid range (1265-EDIT-US-SSN).
        arguments(
            "10 ssn part1 in forbidden range",
            (Consumer<AccountUpdateScreen>) s -> s.setActSsn1("666"),
            AccountUpdateService.FLD_SSN_PART1 + AccountUpdateService.SFX_SSN_PART1_INVALID),
        // 11. Date of Birth (structural edit).
        arguments(
            "11 dob bad month",
            (Consumer<AccountUpdateScreen>) s -> s.setDobMon("13"),
            AccountUpdateService.FLD_DOB + DATE_BAD_MONTH_SFX),
        // 12. FICO Score range (1275-EDIT-FICO-SCORE).
        arguments(
            "12 fico below range",
            (Consumer<AccountUpdateScreen>) s -> s.setAcstFco("200"),
            AccountUpdateService.FLD_FICO + AccountUpdateService.SFX_FICO_RANGE),
        // 13. First Name required (1225-EDIT-ALPHA-REQD).
        arguments(
            "13 first name blank",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsFnam(""),
            AccountUpdateService.FLD_FIRST_NAME + AccountUpdateService.SFX_MUST_BE_SUPPLIED),
        // 14. Middle Name optional but alpha-only when supplied (1235-EDIT-ALPHA-OPT).
        arguments(
            "14 middle name non-alpha",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsMnam("Q1"),
            AccountUpdateService.FLD_MIDDLE_NAME + AccountUpdateService.SFX_ALPHA_ONLY),
        // 15. Last Name required.
        arguments(
            "15 last name blank",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsLnam(""),
            AccountUpdateService.FLD_LAST_NAME + AccountUpdateService.SFX_MUST_BE_SUPPLIED),
        // 16. Address Line 1 mandatory (1215-EDIT-MANDATORY).
        arguments(
            "16 address line 1 blank",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsAdl1(""),
            AccountUpdateService.FLD_ADDR_LINE_1 + AccountUpdateService.SFX_MUST_BE_SUPPLIED),
        // 17. State code invalid (1270-EDIT-US-STATE-CD).
        arguments(
            "17 state code invalid",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsStte("ZZ"),
            AccountUpdateService.FLD_STATE + AccountUpdateService.SFX_STATE_INVALID),
        // 18. Zip numeric (1245-EDIT-NUM-REQD).
        arguments(
            "18 zip non-numeric",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsZipc("ABCDE"),
            AccountUpdateService.FLD_ZIP + AccountUpdateService.SFX_MUST_BE_ALL_NUMERIC),
        // 19. City required.
        arguments(
            "19 city blank",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsCity(""),
            AccountUpdateService.FLD_CITY + AccountUpdateService.SFX_MUST_BE_SUPPLIED),
        // 20. Country required.
        arguments(
            "20 country blank",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsCtry(""),
            AccountUpdateService.FLD_COUNTRY + AccountUpdateService.SFX_MUST_BE_SUPPLIED),
        // 21. Phone Number 1 area code numeric (1260-EDIT-US-PHONE-NUM).
        arguments(
            "21 phone1 area non-digit",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsPh1a("12X"),
            AccountUpdateService.FLD_PHONE_1 + AccountUpdateService.SFX_AREA_3DIGIT),
        // 22. Phone Number 2 area code numeric.
        arguments(
            "22 phone2 area non-digit",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsPh2a("12X"),
            AccountUpdateService.FLD_PHONE_2 + AccountUpdateService.SFX_AREA_3DIGIT),
        // 23. EFT Account Id numeric.
        arguments(
            "23 eft non-numeric",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsEftc("ABC123"),
            AccountUpdateService.FLD_EFT + AccountUpdateService.SFX_MUST_BE_ALL_NUMERIC),
        // 24. Primary Card Holder Y/N.
        arguments(
            "24 primary holder not Y/N",
            (Consumer<AccountUpdateScreen>) s -> s.setAcsPflg("X"),
            AccountUpdateService.FLD_PRI_HOLDER + AccountUpdateService.SFX_MUST_BE_Y_OR_N));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("fieldValidationCases")
  @DisplayName(
      "Each field is edited in the exact legacy order; the first failure redisplays its message")
  void validationOrder_eachInvalidField_redisplaysExactMessage_noSave(
      String caseName, Consumer<AccountUpdateScreen> spoiler, String expectedMessage) {
    // validScreen() carries the OLD snapshot (factory baseline) in the hidden old* fields, which
    // loadOldSnapshot reconstructs without a DB read; the OLD current balance (1234.56) differs
    // from validScreen (2000.00) so 1205-COMPARE-OLD-NEW always reports a change and the field
    // edits run on every case.
    AccountUpdateScreen screen = validScreen();
    spoiler.accept(screen); // spoil exactly one field

    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(expectedMessage);
    // A validation failure never writes.
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Cross-field state/zip mismatch (valid state + valid zip, invalid combo) redisplays")
  void crossFieldStateZip_mismatch_redisplays() {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen();
    // CA is a valid state and 99999 is a valid 5-digit zip, but the "CA99" combo is not valid, so
    // only the cross-field 1280-EDIT-US-STATE-ZIP-CD edit fails.
    screen.setAcsStte("CA");
    screen.setAcsZipc("99999");

    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_STATE_ZIP_INVALID);
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("An invalid open date redisplays the exact DateValidationService message; no write")
  void dateValidation_openDateInvalid_redisplays_noSave() {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen();
    screen.setOpnMon("13"); // month out of 1..12

    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(OPEN_DATE_BAD_MONTH);
    // Because a date edit fails, the write path is never entered.
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("A future date of birth redisplays the COBOL 'cannot be in the future' message")
  void dateOfBirth_inFuture_redisplays() {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen();
    screen.setDobYear("2999"); // structurally parseable but in the future

    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(result).isNull();
    // EDIT-DATE-OF-BIRTH future check (trailing space preserved verbatim).
    assertThat(screen.getErrMsg()).isEqualTo("Date of Birth:cannot be in the future ");
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  // ===== Confirm / optimistic write (9600-WRITE-PROCESSING)
  // =======================================

  @Test
  @DisplayName("PF5 confirm re-reads BOTH records for update before writing account THEN customer")
  void confirm_validEdits_reReadsThenSavesBothInOrder_successMessage() {
    // The OLD snapshot is reconstructed from the carried hidden old* fields (validScreen seeds the
    // factory baseline); the single read-for-update returns the same values, so
    // 9700-CHECK-CHANGE-IN-REC sees no concurrent change and the write proceeds.
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.PFK05);

    assertThat(result).isNull();
    assertThat(screen.getInfoMsg()).isEqualTo(AccountUpdateService.INFO_CONFIRM_UPDATE_SUCCESS);

    // Account: the single read-for-update precedes the account REWRITE. (The display-turn snapshot
    // is carried in old*, not re-read — the Issue-1 optimistic-lock fix.)
    InOrder acctInOrder = inOrder(accountRepository);
    acctInOrder.verify(accountRepository, times(1)).findById(ACCT_ID);
    acctInOrder.verify(accountRepository).saveAndFlush(any(Account.class));
    // Customer: the single read-for-update precedes the customer REWRITE.
    InOrder custInOrder = inOrder(customerRepository);
    custInOrder.verify(customerRepository, times(1)).findById(CUST_ID);
    custInOrder.verify(customerRepository).saveAndFlush(any(Customer.class));
    // The account REWRITE precedes the customer REWRITE (9600 order).
    InOrder saveInOrder = inOrder(accountRepository, customerRepository);
    saveInOrder.verify(accountRepository).saveAndFlush(any(Account.class));
    saveInOrder.verify(customerRepository).saveAndFlush(any(Customer.class));
  }

  @Test
  @DisplayName("Saved account carries all five monetary fields as BigDecimal truncated to scale 2")
  void confirm_savedAccount_hasFiveMoneyFieldsAtScale2() {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    service.processAccountUpdate(validScreen(), editCommarea(), CardWorkArea.Aid.PFK05);

    ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).saveAndFlush(acctCaptor.capture());
    Account saved = acctCaptor.getValue();

    assertThat(saved.getAcctActiveStatus()).isEqualTo("Y");
    assertThat(saved.getAcctCreditLimit()).isEqualByComparingTo("6000.00");
    assertThat(saved.getAcctCreditLimit().scale()).isEqualTo(2);
    assertThat(saved.getAcctCashCreditLimit()).isEqualByComparingTo("1500.00");
    assertThat(saved.getAcctCashCreditLimit().scale()).isEqualTo(2);
    assertThat(saved.getAcctCurrBal()).isEqualByComparingTo("2000.00");
    assertThat(saved.getAcctCurrBal().scale()).isEqualTo(2);
    assertThat(saved.getAcctCurrCycCredit()).isEqualByComparingTo("300.00");
    assertThat(saved.getAcctCurrCycCredit().scale()).isEqualTo(2);
    assertThat(saved.getAcctCurrCycDebit()).isEqualByComparingTo("90.00");
    assertThat(saved.getAcctCurrCycDebit().scale()).isEqualTo(2);
    // Dates are reassembled into the entity yyyy-MM-dd form.
    assertThat(saved.getAcctOpenDate()).isEqualTo("2010-01-15");
    assertThat(saved.getAcctExpiraionDate()).isEqualTo("2030-12-31");
    assertThat(saved.getAcctReissueDate()).isEqualTo("2015-06-01");
  }

  @Test
  @DisplayName("Saved customer carries the edited name/address/SSN/FICO/phone/DOB values")
  void confirm_savedCustomer_carriesEditedFields() {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    service.processAccountUpdate(validScreen(), editCommarea(), CardWorkArea.Aid.PFK05);

    ArgumentCaptor<Customer> custCaptor = ArgumentCaptor.forClass(Customer.class);
    verify(customerRepository).saveAndFlush(custCaptor.capture());
    Customer saved = custCaptor.getValue();

    assertThat(saved.getCustFirstName()).isEqualTo("JOHN");
    assertThat(saved.getCustLastName()).isEqualTo("PUBLIC");
    // The edited address differs from the OLD snapshot's "123 MAIN STREET".
    assertThat(saved.getCustAddrLine1()).isEqualTo("456 OAK AVENUE");
    assertThat(saved.getCustAddrStateCd()).isEqualTo("CA");
    assertThat(saved.getCustAddrZip()).isEqualTo("90001");
    // SSN parts concatenated into the 9(09) numeric.
    assertThat(saved.getCustSsn()).isEqualTo(123456789L);
    // FICO concatenated into the numeric score.
    assertThat(saved.getCustFicoCreditScore()).isEqualTo(700L);
    // Phone assembled into the (aaa)bbb-cccc display form.
    assertThat(saved.getCustPhoneNum1()).isEqualTo("(212)555-0123");
    // Date of birth reassembled into yyyy-MM-dd.
    assertThat(saved.getCustDobYyyyMmDd()).isEqualTo("1980-05-15");
    assertThat(saved.getCustPriCardHolderInd()).isEqualTo("Y");
  }

  @Test
  @DisplayName(
      "Optimistic conflict: the read-for-update diverges from the snapshot -> redisplay, no write")
  void confirm_optimisticConflictOnReRead_redisplays_noOverwrite() {
    Account changed = account(ACCT_ID);
    changed.setAcctCurrBal(new BigDecimal("9999.99")); // a concurrent change since the display
    // The OLD snapshot is the display-turn baseline carried in old* (validScreen seeds factory,
    // curr-bal 1234.56). The single read-for-update returns the concurrently CHANGED record
    // (curr-bal 9999.99) so 9700-CHECK-CHANGE-IN-REC detects divergence — exactly the lost-update
    // anomaly Issue 1 must prevent (a fresh same-turn re-read would have masked it).
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(changed));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.PFK05);

    assertThat(result).isNull();
    // 9700 detects the change and 2000-DECIDE-ACTION redisplays without overwriting (no @Version).
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_DATA_CHANGED);
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Read-for-update finds the account gone -> 'could not lock' redisplay, no write")
  void confirm_accountVanishedOnReRead_couldNotLock_redisplays() {
    // The OLD snapshot is carried in old* (validScreen); the single read-for-update finds nothing
    // to lock, so 9600 reports "could not lock" before the customer is ever re-read.
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.PFK05);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_COULD_NOT_LOCK_ACCT);
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName(
      "Account REWRITE failure redisplays 'Update of record failed'; customer is not written")
  void confirm_accountSaveFails_redisplaysUpdateFailed_customerNotWritten() {
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));
    when(accountRepository.saveAndFlush(any(Account.class)))
        .thenThrow(new DataAccessResourceFailureException("rewrite failed"));

    AccountUpdateScreen screen = validScreen();
    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.PFK05);

    assertThat(result).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AccountUpdateService.MSG_UPDATE_FAILED);
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName(
      "A DataAccessException on the read-for-update maps to IoStatusException (FILE STATUS)")
  void confirm_dataAccessExceptionOnReRead_mapsToIoStatusException() {
    // The OLD snapshot is carried in old* (validScreen); the single read-for-update raises a
    // DataAccessException, which maps to an IoStatusException before the customer is re-read.
    when(accountRepository.findById(ACCT_ID))
        .thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatThrownBy(
            () ->
                service.processAccountUpdate(validScreen(), editCommarea(), CardWorkArea.Aid.PFK05))
        .isInstanceOf(IoStatusException.class);

    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("PF12 cancel discards the edits, re-reads the record, and performs no write")
  void pfk12_cancel_discardsEdits_reReads_noSave() {
    when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(ACCT_ID, CUST_ID)));
    when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account(ACCT_ID)));
    when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer(CUST_ID)));

    AccountUpdateScreen screen = validScreen(); // carries edits (e.g. current balance 2000.00)
    String result = service.processAccountUpdate(screen, editCommarea(), CardWorkArea.Aid.PFK12);

    assertThat(result).isNull();
    // The edits are discarded: the screen is repainted from the re-read record (1234.56).
    assertThat(screen.getAcurBal()).isEqualTo("1234.56");
    assertThat(screen.getErrMsg()).isEmpty();
    verify(accountRepository, never()).saveAndFlush(any());
    verify(customerRepository, never()).saveAndFlush(any());
  }
}
