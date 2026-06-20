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
package com.aws.carddemo.batch.config;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Card;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Package-private test-support helper that seeds the five master / transaction PostgreSQL tables
 * from the byte-exact legacy fixed-width ASCII fixtures shipped under {@code
 * src/test/resources/fixtures/ascii/} (classpath {@code /fixtures/ascii/}).
 *
 * <p>This is <strong>not</strong> a Spring bean and <strong>not</strong> a {@code @Test} class: it
 * carries no Spring stereotype/test annotations, so it never pollutes the application context. It
 * is a plain utility invoked statically by the Spring Batch integration tests in this package
 * (Agent Action Plan &sect;0.6.7 — local-only validation against the legacy fixtures with a real
 * Testcontainers PostgreSQL, no running mainframe required).
 *
 * <p><strong>What is seeded here.</strong> Only the five master / transaction stores are loaded
 * from fixtures: {@code account}, {@code customer}, {@code card}, {@code card_xref} and {@code
 * daily_transaction}. The four <em>reference</em> tables ({@code tran_type}, {@code tran_category},
 * {@code disclosure_group}, {@code tran_cat_balance}) are populated by the Flyway migration {@code
 * V2__seed_reference_data.sql} and are deliberately <strong>never</strong> touched here — seeding
 * them again would double-insert and violate their primary keys (AAP &sect;0.4.1).
 *
 * <p><strong>COBOL zoned-decimal (overpunch) amounts.</strong> Signed COBOL {@code S9(n)V99}
 * numerics are stored as zoned decimal with the sign encoded in the <em>last</em> byte (trailing
 * overpunch). {@link #decodeOverpunch(String)} reproduces that encoding exactly and yields a {@link
 * BigDecimal} scaled at 2 (the implied {@code V99} decimal point). Monetary values use {@link
 * BigDecimal} only — never {@code float}/{@code double} (AAP &sect;0.6.1).
 *
 * <p><strong>Fixed-width parsing.</strong> Records are sliced with 1-indexed COBOL offsets; the
 * authoritative source for every offset is the matching copybook ({@code CVACT01Y}, {@code
 * CVACT02Y}, {@code CVACT03Y}, {@code CVCUS01Y}, {@code CVTRA06Y}). Descriptive text fields are
 * right-trimmed of their COBOL blank padding; key fields (card numbers, transaction ids) are kept
 * verbatim because they are full-width digit strings with no padding. Every persisted column is
 * {@code NOT NULL} in {@code V1__schema.sql}, so blank inputs (for example the inbound {@code
 * DALYTRAN-PROC-TS}, which the posting job fills later) map to an empty string rather than {@code
 * null}; PostgreSQL {@code char(n)} blank-pads it back to full width on read.
 *
 * <p>All members are {@code static} and package-private so the sibling integration tests can call
 * the seeders and unit-test the helpers (notably {@link #decodeOverpunch(String)}). The class is
 * {@code final} with a private constructor to prevent instantiation.
 */
final class FixtureSeeder {

  /** Classpath location of the account-master fixture (50 rows, LRECL 300; copybook CVACT01Y). */
  static final String ACCT = "/fixtures/ascii/acctdata.txt";

  /** Classpath location of the customer-master fixture (50 rows, LRECL 500; copybook CVCUS01Y). */
  static final String CUST = "/fixtures/ascii/custdata.txt";

  /** Classpath location of the card-master fixture (50 rows, LRECL 150; copybook CVACT02Y). */
  static final String CARD = "/fixtures/ascii/carddata.txt";

  /**
   * Classpath location of the card cross-reference fixture (50 rows, LRECL 36; copybook CVACT03Y
   * with its trailing {@code FILLER} omitted — the fixture line is only 36 characters long).
   */
  static final String XREF = "/fixtures/ascii/cardxref.txt";

  /**
   * Classpath location of the daily-transaction fixture (300 rows, LRECL 350; copybook CVTRA06Y).
   */
  static final String DALY = "/fixtures/ascii/dailytran.txt";

  /**
   * Positive trailing-overpunch sign characters indexed by units digit: index 0 is {@code '{'} (=0),
   * index 1 is {@code 'A'} (=1), &hellip; index 9 is {@code 'I'} (=9).
   */
  private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

  /**
   * Negative trailing-overpunch sign characters indexed by units digit: index 0 is {@code '}'}
   * (=0), index 1 is {@code 'J'} (=1), &hellip; index 9 is {@code 'R'} (=9).
   */
  private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

  private FixtureSeeder() {
    // Utility class — not instantiable.
  }

  /**
   * Decodes a COBOL zoned-decimal {@code S9(n)V99} field (sign carried as a trailing overpunch on
   * the last byte) into a {@link BigDecimal} scaled at 2.
   *
   * <p>The last character selects the sign and the units digit per the COBOL overpunch convention;
   * a plain trailing digit {@code '0'}–{@code '9'} is treated as positive with that digit. The
   * preceding characters are the higher-order digits. For example {@code "0000005047G"} decodes to
   * {@code 504.77} ({@code 'G'} = +7 &rarr; digits {@code "00000050477"}), {@code "0000009190}"}
   * decodes to {@code -919.00}, and the 12-character account amount {@code "00000001940{"} decodes
   * to {@code 194.00} ({@code '{'} = +0).
   *
   * @param field the raw fixed-width field exactly as sliced (never trimmed); must be non-empty
   * @return the decoded value as a {@link BigDecimal} with scale 2
   * @throws IllegalArgumentException if the trailing byte is not a recognized overpunch character or
   *     plain digit
   */
  static BigDecimal decodeOverpunch(String field) {
    char last = field.charAt(field.length() - 1);
    int sign;
    int unitsDigit;
    int positiveIndex = POSITIVE_OVERPUNCH.indexOf(last);
    int negativeIndex = NEGATIVE_OVERPUNCH.indexOf(last);
    if (positiveIndex >= 0) {
      sign = 1;
      unitsDigit = positiveIndex;
    } else if (negativeIndex >= 0) {
      sign = -1;
      unitsDigit = negativeIndex;
    } else if (last >= '0' && last <= '9') {
      sign = 1;
      unitsDigit = last - '0';
    } else {
      throw new IllegalArgumentException(
          "Unrecognized COBOL overpunch sign character '" + last + "' in field \"" + field + "\"");
    }
    String allDigits = field.substring(0, field.length() - 1) + unitsDigit;
    BigInteger magnitude = new BigInteger(allDigits);
    if (sign < 0) {
      magnitude = magnitude.negate();
    }
    return new BigDecimal(magnitude, 2);
  }

  /**
   * Extracts a fixed-width sub-field using 1-indexed, inclusive COBOL offsets.
   *
   * <p>If the source line is shorter than the requested window it is first right-padded with spaces
   * so the slice is always exactly {@code len} characters; fixtures are normally full LRECL, but
   * this keeps parsing defensive against a truncated final record.
   *
   * @param line the full record line
   * @param start1 the 1-indexed starting column of the field
   * @param len the field length in characters
   * @return the {@code len}-character field value
   */
  static String slice(String line, int start1, int len) {
    int end = (start1 - 1) + len;
    String padded = line.length() >= end ? line : line + " ".repeat(end - line.length());
    return padded.substring(start1 - 1, end);
  }

  /**
   * Removes trailing ASCII spaces (the COBOL fixed-width right-padding) from {@code s}, leaving
   * leading characters and any embedded spaces untouched.
   *
   * @param s the value to right-trim
   * @return {@code s} without its trailing spaces (possibly the empty string)
   */
  static String rtrim(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == ' ') {
      end--;
    }
    return s.substring(0, end);
  }

  /**
   * Parses a COBOL genuine-numeric ({@code 9(n)}) field into a {@link Long}, trimming surrounding
   * whitespace first. Leading zeros are insignificant for the numeric value.
   *
   * @param s the numeric field value
   * @return the parsed {@link Long}
   * @throws NumberFormatException if the trimmed value is not a valid {@code long}
   */
  static Long parseLongTrim(String s) {
    return Long.parseLong(s.trim());
  }

  /**
   * Reads every record line of a classpath fixture, preserving fixed-width interior content.
   *
   * <p>The resource is loaded via {@link Class#getResourceAsStream(String)} and decoded as {@link
   * StandardCharsets#UTF_8} (the fixtures are an ASCII subset, so UTF-8 is byte-faithful). Lines
   * are split on {@code '\n'}; a trailing empty element from the file-final newline is dropped, and
   * a single trailing carriage return is stripped from each line so a CRLF checkout still yields
   * the exact LRECL. No interior content is trimmed.
   *
   * @param classpathResource the absolute classpath resource path (e.g. {@code
   *     "/fixtures/ascii/acctdata.txt"})
   * @return the ordered list of record lines
   * @throws IllegalStateException if the resource cannot be located on the classpath
   * @throws UncheckedIOException if the resource cannot be read
   */
  static List<String> readFixtureLines(String classpathResource) {
    try (InputStream in = FixtureSeeder.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalStateException(
            "Required fixture resource not found on the test classpath: " + classpathResource);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      String[] rawLines = content.split("\n", -1);
      List<String> lines = new ArrayList<>(rawLines.length);
      for (int i = 0; i < rawLines.length; i++) {
        String line = rawLines[i];
        // A file-final newline yields a trailing empty element; drop it.
        if (i == rawLines.length - 1 && line.isEmpty()) {
          continue;
        }
        // Strip a single trailing CR (CRLF checkout) — removes only the terminator, never the
        // fixed-width interior content.
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
          line = line.substring(0, line.length() - 1);
        }
        lines.add(line);
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read fixture resource: " + classpathResource, e);
    }
  }

  /**
   * Seeds the {@code account} table from {@code acctdata.txt} (copybook {@code CVACT01Y}, RECLN
   * 300). The five monetary fields are zoned-decimal overpunch amounts; all dates are stored as
   * text. The sample data intentionally leaves {@code ACCT-GROUP-ID} blank with {@code A000000000}
   * landing in the ZIP column — the offsets are honored verbatim, not "corrected".
   *
   * @param repo the account repository to persist into
   */
  static void seedAccounts(AccountRepository repo) {
    for (String line : readFixtureLines(ACCT)) {
      Account account = new Account();
      account.setAcctId(parseLongTrim(slice(line, 1, 11)));
      account.setAcctActiveStatus(rtrim(slice(line, 12, 1)));
      account.setAcctCurrBal(decodeOverpunch(slice(line, 13, 12)));
      account.setAcctCreditLimit(decodeOverpunch(slice(line, 25, 12)));
      account.setAcctCashCreditLimit(decodeOverpunch(slice(line, 37, 12)));
      account.setAcctOpenDate(rtrim(slice(line, 49, 10)));
      account.setAcctExpiraionDate(rtrim(slice(line, 59, 10)));
      account.setAcctReissueDate(rtrim(slice(line, 69, 10)));
      account.setAcctCurrCycCredit(decodeOverpunch(slice(line, 79, 12)));
      account.setAcctCurrCycDebit(decodeOverpunch(slice(line, 91, 12)));
      account.setAcctAddrZip(rtrim(slice(line, 103, 10)));
      account.setAcctGroupId(rtrim(slice(line, 113, 10)));
      repo.save(account);
    }
  }

  /**
   * Seeds the {@code customer} table from {@code custdata.txt} (copybook {@code CVCUS01Y}, RECLN
   * 500). {@code CUST-ID}, {@code CUST-SSN} and {@code CUST-FICO-CREDIT-SCORE} are genuine numerics
   * ({@link Long}); the date of birth and all text fields are stored as right-trimmed strings.
   *
   * @param repo the customer repository to persist into
   */
  static void seedCustomers(CustomerRepository repo) {
    for (String line : readFixtureLines(CUST)) {
      Customer customer = new Customer();
      customer.setCustId(parseLongTrim(slice(line, 1, 9)));
      customer.setCustFirstName(rtrim(slice(line, 10, 25)));
      customer.setCustMiddleName(rtrim(slice(line, 35, 25)));
      customer.setCustLastName(rtrim(slice(line, 60, 25)));
      customer.setCustAddrLine1(rtrim(slice(line, 85, 50)));
      customer.setCustAddrLine2(rtrim(slice(line, 135, 50)));
      customer.setCustAddrLine3(rtrim(slice(line, 185, 50)));
      customer.setCustAddrStateCd(rtrim(slice(line, 235, 2)));
      customer.setCustAddrCountryCd(rtrim(slice(line, 237, 3)));
      customer.setCustAddrZip(rtrim(slice(line, 240, 10)));
      customer.setCustPhoneNum1(rtrim(slice(line, 250, 15)));
      customer.setCustPhoneNum2(rtrim(slice(line, 265, 15)));
      customer.setCustSsn(parseLongTrim(slice(line, 280, 9)));
      customer.setCustGovtIssuedId(rtrim(slice(line, 289, 20)));
      customer.setCustDobYyyyMmDd(rtrim(slice(line, 309, 10)));
      customer.setCustEftAccountId(rtrim(slice(line, 319, 10)));
      customer.setCustPriCardHolderInd(rtrim(slice(line, 329, 1)));
      customer.setCustFicoCreditScore(parseLongTrim(slice(line, 330, 3)));
      repo.save(customer);
    }
  }

  /**
   * Seeds the {@code card} table from {@code carddata.txt} (copybook {@code CVACT02Y}, RECLN 150).
   * The card number is the primary key and is kept verbatim; {@code CARD-ACCT-ID} is a {@link
   * Long}; the CVV is preserved as fixed-width text so leading zeros survive.
   *
   * @param repo the card repository to persist into
   */
  static void seedCards(CardRepository repo) {
    for (String line : readFixtureLines(CARD)) {
      Card card = new Card();
      card.setCardNum(slice(line, 1, 16));
      card.setCardAcctId(parseLongTrim(slice(line, 17, 11)));
      card.setCardCvvCd(rtrim(slice(line, 28, 3)));
      card.setCardEmbossedName(rtrim(slice(line, 31, 50)));
      card.setCardExpiraionDate(rtrim(slice(line, 81, 10)));
      card.setCardActiveStatus(rtrim(slice(line, 91, 1)));
      repo.save(card);
    }
  }

  /**
   * Seeds the {@code card_xref} table from {@code cardxref.txt} (copybook {@code CVACT03Y}; the
   * fixture line is only 36 characters because the trailing {@code FILLER} is omitted). The card
   * number is the primary key (kept verbatim); the customer and account ids are {@link Long}s.
   *
   * @param repo the card cross-reference repository to persist into
   */
  static void seedCardXrefs(CardXrefRepository repo) {
    for (String line : readFixtureLines(XREF)) {
      CardXref xref = new CardXref();
      xref.setXrefCardNum(slice(line, 1, 16));
      xref.setXrefCustId(parseLongTrim(slice(line, 17, 9)));
      xref.setXrefAcctId(parseLongTrim(slice(line, 26, 11)));
      repo.save(xref);
    }
  }

  /**
   * Seeds the {@code daily_transaction} table from {@code dailytran.txt} (copybook {@code
   * CVTRA06Y}, RECLN 350). The transaction id and card number are kept verbatim; the amount is a
   * zoned-decimal overpunch value; the merchant id is a {@link Long}; the category code and both
   * timestamps are stored as fixed-width text. The inbound {@code DALYTRAN-PROC-TS} is blank (the
   * posting job fills it later), so it maps to an empty string rather than a fabricated timestamp.
   *
   * @param repo the daily-transaction repository to persist into
   */
  static void seedDailyTransactions(DailyTransactionRepository repo) {
    for (String line : readFixtureLines(DALY)) {
      DailyTransaction tran = new DailyTransaction();
      tran.setDalytranId(slice(line, 1, 16));
      tran.setDalytranTypeCd(rtrim(slice(line, 17, 2)));
      tran.setDalytranCatCd(rtrim(slice(line, 19, 4)));
      tran.setDalytranSource(rtrim(slice(line, 23, 10)));
      tran.setDalytranDesc(rtrim(slice(line, 33, 100)));
      tran.setDalytranAmt(decodeOverpunch(slice(line, 133, 11)));
      tran.setDalytranMerchantId(parseLongTrim(slice(line, 144, 9)));
      tran.setDalytranMerchantName(rtrim(slice(line, 153, 50)));
      tran.setDalytranMerchantCity(rtrim(slice(line, 203, 50)));
      tran.setDalytranMerchantZip(rtrim(slice(line, 253, 10)));
      tran.setDalytranCardNum(slice(line, 263, 16));
      tran.setDalytranOrigTs(rtrim(slice(line, 279, 26)));
      tran.setDalytranProcTs(rtrim(slice(line, 305, 26)));
      repo.save(tran);
    }
  }

  /**
   * Convenience seeder that loads the entire master / transaction graph in foreign-key-safe order
   * (customers &rarr; accounts &rarr; cards &rarr; card cross-references &rarr; daily
   * transactions). Use this from integration tests that need the whole data set in place.
   *
   * @param accountRepo the account repository
   * @param customerRepo the customer repository
   * @param cardRepo the card repository
   * @param cardXrefRepo the card cross-reference repository
   * @param dailyTransactionRepo the daily-transaction repository
   */
  static void seedMasters(
      AccountRepository accountRepo,
      CustomerRepository customerRepo,
      CardRepository cardRepo,
      CardXrefRepository cardXrefRepo,
      DailyTransactionRepository dailyTransactionRepo) {
    seedCustomers(customerRepo);
    seedAccounts(accountRepo);
    seedCards(cardRepo);
    seedCardXrefs(cardXrefRepo);
    seedDailyTransactions(dailyTransactionRepo);
  }
}
