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
package com.carddemo.batch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.testsupport.AsciiFixtures;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for :class:`CobolRecordFormatter`.
 *
 * :purpose: Verify that the data-management jobs reproduce byte-identical legacy
 *     output. Every fixed-width renderer is asserted against the COMMITTED
 *     ``app/data/ASCII`` fixtures the legacy loaders consumed - a fixture line is
 *     parsed field by field into the domain object and the formatter must render
 *     exactly that line back - so any drift in a field width, order, pad
 *     direction or zoned-decimal sign overpunch fails immediately. The labelled
 *     ``CBACT01C`` account dump is asserted line by line, including the
 *     preserved misspelling ``ACCT-EXPIRAION-DATE`` and the 49-hyphen separator.
 * :output: JUnit 5 / AssertJ assertions only; the formatter is static and stateless,
 *     so no Spring context, database or file is involved.
 */
class CobolRecordFormatterTest {

    @Nested
    @DisplayName("cardRecord: CVACT02Y, 150 bytes (CBACT02C DISPLAY CARD-RECORD)")
    class CardRecord {

        @Test
        @DisplayName("re-renders every committed carddata.txt record byte-for-byte")
        void reRendersEveryCommittedCardFixture() {
            List<String> fixtures = AsciiFixtures.lines("carddata.txt");
            assertThat(fixtures).hasSize(50).allSatisfy(line -> assertThat(line).hasSize(150));

            for (String line : fixtures) {
                Card card = new Card();
                card.setCardNum(line.substring(0, 16));
                card.setCardAcctId(Long.valueOf(line.substring(16, 27)));
                card.setCardCvvCd(line.substring(27, 30));
                card.setCardEmbossedName(AsciiFixtures.rtrim(line.substring(30, 80)));
                card.setCardExpiraionDate(line.substring(80, 90));
                card.setCardActiveStatus(line.substring(90, 91));

                assertThat(CobolRecordFormatter.cardRecord(card))
                        .as("card %s", card.getCardNum())
                        .isEqualTo(line);
            }
        }

        @Test
        @DisplayName("renders the first fixture record field by field at its declared width")
        void rendersTheFirstFixtureFieldByField() {
            String line = AsciiFixtures.lines("carddata.txt").get(0);

            assertThat(line.substring(0, 16)).isEqualTo("0500024453765740");
            assertThat(line.substring(16, 27)).isEqualTo("00000000050");
            assertThat(line.substring(27, 30)).isEqualTo("747");
            assertThat(line.substring(30, 80)).isEqualTo(AsciiFixtures.padRight("Aniya Von", 50));
            assertThat(line.substring(80, 90)).isEqualTo("2023-03-09");
            assertThat(line.substring(90, 91)).isEqualTo("Y");
            assertThat(line.substring(91)).isEqualTo(" ".repeat(59));
        }
    }

    @Nested
    @DisplayName("cardXrefRecord: CVACT03Y, 50 bytes (CBACT03C DISPLAY CARD-XREF-RECORD)")
    class CardXrefRecord {

        @Test
        @DisplayName("re-renders every committed cardxref.txt record, restoring the copybook trailing filler")
        void reRendersEveryCommittedXrefFixture() {
            List<String> fixtures = AsciiFixtures.lines("cardxref.txt");
            // The loader fixture carries only the three key fields (36 bytes); CVACT03Y
            // declares RECLN 50, so the formatter restores the 14-byte trailing FILLER.
            assertThat(fixtures).hasSize(50).allSatisfy(line -> assertThat(line).hasSize(36));

            for (String line : fixtures) {
                CardXref xref = new CardXref(line.substring(0, 16),
                        Long.valueOf(line.substring(16, 25)),
                        Long.valueOf(line.substring(25, 36)));

                assertThat(CobolRecordFormatter.cardXrefRecord(xref))
                        .as("xref %s", xref.getXrefCardNum())
                        .hasSize(50)
                        .isEqualTo(line + " ".repeat(14));
            }
        }
    }

    @Nested
    @DisplayName("customerRecord: CVCUS01Y, 500 bytes (CBCUS01C DISPLAY CUSTOMER-RECORD)")
    class CustomerRecord {

        @Test
        @DisplayName("re-renders every committed custdata.txt record byte-for-byte")
        void reRendersEveryCommittedCustomerFixture() {
            List<String> fixtures = AsciiFixtures.lines("custdata.txt");
            assertThat(fixtures).hasSize(50).allSatisfy(line -> assertThat(line).hasSize(500));

            for (String line : fixtures) {
                Customer customer = new Customer();
                customer.setCustId(Long.valueOf(line.substring(0, 9)));
                customer.setCustFirstName(AsciiFixtures.rtrim(line.substring(9, 34)));
                customer.setCustMiddleName(AsciiFixtures.rtrim(line.substring(34, 59)));
                customer.setCustLastName(AsciiFixtures.rtrim(line.substring(59, 84)));
                customer.setCustAddrLine1(AsciiFixtures.rtrim(line.substring(84, 134)));
                customer.setCustAddrLine2(AsciiFixtures.rtrim(line.substring(134, 184)));
                customer.setCustAddrLine3(AsciiFixtures.rtrim(line.substring(184, 234)));
                customer.setCustAddrStateCd(line.substring(234, 236));
                customer.setCustAddrCountryCd(line.substring(236, 239));
                customer.setCustAddrZip(AsciiFixtures.rtrim(line.substring(239, 249)));
                customer.setCustPhoneNum1(AsciiFixtures.rtrim(line.substring(249, 264)));
                customer.setCustPhoneNum2(AsciiFixtures.rtrim(line.substring(264, 279)));
                customer.setCustSsn(line.substring(279, 288));
                customer.setCustGovtIssuedId(AsciiFixtures.rtrim(line.substring(288, 308)));
                customer.setCustDobYyyyMmDd(line.substring(308, 318));
                customer.setCustEftAccountId(AsciiFixtures.rtrim(line.substring(318, 328)));
                customer.setCustPriCardHolderInd(line.substring(328, 329));
                customer.setCustFicoCreditScore(Integer.valueOf(line.substring(329, 332)));

                assertThat(CobolRecordFormatter.customerRecord(customer))
                        .as("customer %s", customer.getCustId())
                        .isEqualTo(line);
            }
        }

        @Test
        @DisplayName("the record ends with the 168-byte CVCUS01Y filler")
        void recordEndsWithTheDeclaredFiller() {
            String line = AsciiFixtures.lines("custdata.txt").get(0);
            assertThat(line.substring(332)).isEqualTo(" ".repeat(168));
            assertThat(line.substring(0, 9)).isEqualTo("000000001");
        }
    }

    @Nested
    @DisplayName("accountDump: the labelled CBACT01C 1100-DISPLAY-ACCT-RECORD output")
    class AccountDump {

        /**
         * Builds the account whose fields the first ``acctdata.txt`` record carries.
         *
         * :output: the account under test.
         */
        private Account firstFixtureAccount() {
            String line = AsciiFixtures.lines("acctdata.txt").get(0);
            Account account = new Account();
            account.setAcctId(Long.valueOf(line.substring(0, 11)));
            account.setAcctActiveStatus(line.substring(11, 12));
            account.setAcctCurrBal(AsciiFixtures.decodeZoned(line.substring(12, 24)));
            account.setAcctCreditLimit(AsciiFixtures.decodeZoned(line.substring(24, 36)));
            account.setAcctCashCreditLimit(AsciiFixtures.decodeZoned(line.substring(36, 48)));
            account.setAcctOpenDate(line.substring(48, 58));
            account.setAcctExpiraionDate(line.substring(58, 68));
            account.setAcctReissueDate(line.substring(68, 78));
            account.setAcctCurrCycCredit(AsciiFixtures.decodeZoned(line.substring(78, 90)));
            account.setAcctCurrCycDebit(AsciiFixtures.decodeZoned(line.substring(90, 102)));
            account.setAcctGroupId(line.substring(102, 112));
            return account;
        }

        @Test
        @DisplayName("emits eleven labelled lines then the 49-hyphen separator")
        void emitsElevenLabelledLinesAndTheSeparator() {
            String[] lines = CobolRecordFormatter.accountDump(firstFixtureAccount()).split("\n", -1);

            assertThat(lines).hasSize(12);
            assertThat(lines[11]).isEqualTo("-".repeat(49));
            for (int i = 0; i < 11; i++) {
                // 24-character field name, ':' at column 25, then the value.
                assertThat(lines[i].charAt(24)).as("colon of line %d", i).isEqualTo(':');
            }
        }

        @Test
        @DisplayName("every label and value matches the fixture, including ACCT-EXPIRAION-DATE")
        void everyLabelAndValueMatchesTheFixture() {
            String[] lines = CobolRecordFormatter.accountDump(firstFixtureAccount()).split("\n", -1);

            assertThat(lines[0]).isEqualTo(AsciiFixtures.padRight("ACCT-ID", 24) + ":00000000001");
            assertThat(lines[1]).isEqualTo(AsciiFixtures.padRight("ACCT-ACTIVE-STATUS", 24) + ":Y");
            assertThat(lines[2]).isEqualTo(AsciiFixtures.padRight("ACCT-CURR-BAL", 24) + ":00000001940{");
            assertThat(lines[3]).isEqualTo(AsciiFixtures.padRight("ACCT-CREDIT-LIMIT", 24) + ":00000020200{");
            assertThat(lines[4])
                    .isEqualTo(AsciiFixtures.padRight("ACCT-CASH-CREDIT-LIMIT", 24) + ":00000010200{");
            assertThat(lines[5]).isEqualTo(AsciiFixtures.padRight("ACCT-OPEN-DATE", 24) + ":2014-11-20");
            // Frozen legacy misspelling: EXPIRAION, not EXPIRATION.
            assertThat(lines[6])
                    .isEqualTo(AsciiFixtures.padRight("ACCT-EXPIRAION-DATE", 24) + ":2025-05-20");
            assertThat(lines[6]).doesNotContain("EXPIRATION");
            assertThat(lines[7]).isEqualTo(AsciiFixtures.padRight("ACCT-REISSUE-DATE", 24) + ":2025-05-20");
            assertThat(lines[8])
                    .isEqualTo(AsciiFixtures.padRight("ACCT-CURR-CYC-CREDIT", 24) + ":00000000000{");
            assertThat(lines[9])
                    .isEqualTo(AsciiFixtures.padRight("ACCT-CURR-CYC-DEBIT", 24) + ":00000000000{");
            assertThat(lines[10]).isEqualTo(AsciiFixtures.padRight("ACCT-GROUP-ID", 24) + ":A000000000");
        }

        @Test
        @DisplayName("the rendered zoned amounts round-trip back to the fixture values")
        void renderedZonedAmountsRoundTrip() {
            Account account = firstFixtureAccount();
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo("194.00");
            assertThat(account.getAcctCreditLimit()).isEqualByComparingTo("2020.00");
            assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo("1020.00");
        }

        @Test
        @DisplayName("every committed acctdata.txt record renders its three amounts back byte-for-byte")
        void everyFixtureAmountRendersBack() {
            List<String> fixtures = AsciiFixtures.lines("acctdata.txt");
            assertThat(fixtures).hasSize(50).allSatisfy(line -> assertThat(line).hasSize(300));

            for (String line : fixtures) {
                for (int start : new int[] {12, 24, 36, 78, 90}) {
                    String field = line.substring(start, start + 12);
                    assertThat(CobolRecordFormatter.encodeSignedZonedDecimal(
                            AsciiFixtures.decodeZoned(field), 10, 2))
                            .as("zoned field at %d of account %s", start, line.substring(0, 11))
                            .isEqualTo(field);
                }
            }
        }
    }

    @Nested
    @DisplayName("transactionRecord: CVTRA05Y, 350 bytes (COMBTRAN output)")
    class TransactionRecord {

        /**
         * Builds a fully populated transaction fixture.
         *
         * :param amount: the ``TRAN-AMT`` value.
         * :output: the transaction under test.
         */
        private static Transaction transaction(String amount) {
            Transaction transaction = new Transaction();
            transaction.setTranId("0000000000683580");
            transaction.setTranTypeCd("01");
            transaction.setTranCatCd(1);
            transaction.setTranSource("POS TERM");
            transaction.setTranDesc("Purchase at Abshire-Lowe");
            transaction.setTranAmt(new BigDecimal(amount));
            transaction.setTranMerchantId(123456789L);
            transaction.setTranMerchantName("Mercado Central");
            transaction.setTranMerchantCity("Springfield");
            transaction.setTranMerchantZip("22770");
            transaction.setTranCardNum("4859452612877065");
            transaction.setTranOrigTs("2022-06-10-19.27.53.000000");
            transaction.setTranProcTs("2022-06-11-01.00.00.000000");
            return transaction;
        }

        @Test
        @DisplayName("renders exactly 350 characters with every field at its declared offset")
        void rendersEveryFieldAtItsDeclaredOffset() {
            String record = CobolRecordFormatter.transactionRecord(transaction("504.77"));

            assertThat(record).hasSize(350);
            assertThat(record.substring(0, 16)).isEqualTo("0000000000683580");
            assertThat(record.substring(16, 18)).isEqualTo("01");
            assertThat(record.substring(18, 22)).isEqualTo("0001");
            assertThat(record.substring(22, 32)).isEqualTo(AsciiFixtures.padRight("POS TERM", 10));
            assertThat(record.substring(32, 132))
                    .isEqualTo(AsciiFixtures.padRight("Purchase at Abshire-Lowe", 100));
            assertThat(record.substring(132, 143)).isEqualTo("0000005047G");
            assertThat(record.substring(143, 152)).isEqualTo("123456789");
            assertThat(record.substring(152, 202))
                    .isEqualTo(AsciiFixtures.padRight("Mercado Central", 50));
            assertThat(record.substring(202, 252))
                    .isEqualTo(AsciiFixtures.padRight("Springfield", 50));
            assertThat(record.substring(252, 262)).isEqualTo(AsciiFixtures.padRight("22770", 10));
            assertThat(record.substring(262, 278)).isEqualTo("4859452612877065");
            assertThat(record.substring(278, 304)).isEqualTo("2022-06-10-19.27.53.000000");
            assertThat(record.substring(304, 330)).isEqualTo("2022-06-11-01.00.00.000000");
            assertThat(record.substring(330)).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the rendered amount matches the committed dailytran.txt zoned field for the same value")
        void amountMatchesTheCommittedFixtureField() {
            // dailytran.txt record 1 carries DALYTRAN-AMT '0000005047G' == 504.77; the
            // COMBTRAN transaction record uses the identical S9(09)V99 encoding.
            String fixtureField = AsciiFixtures.lines("dailytran.txt").get(0).substring(132, 143);
            assertThat(fixtureField).isEqualTo("0000005047G");
            assertThat(CobolRecordFormatter.transactionRecord(transaction("504.77")).substring(132, 143))
                    .isEqualTo(fixtureField);
        }

        @Test
        @DisplayName("a null-valued record still renders exactly 350 characters")
        void nullValuesStillRender350Characters() {
            Transaction sparse = new Transaction();
            sparse.setTranId("0000000000000001");

            String record = CobolRecordFormatter.transactionRecord(sparse);

            assertThat(record).hasSize(350);
            assertThat(record.substring(18, 22)).isEqualTo("0000");
            assertThat(record.substring(132, 143)).isEqualTo("0000000000{");
            assertThat(record.substring(143, 152)).isEqualTo("000000000");
        }
    }

    @Nested
    @DisplayName("Field-primitive rules")
    class FieldPrimitives {

        @ParameterizedTest
        @DisplayName("padText left-justifies, space-pads and truncates like a PIC X(n) MOVE")
        @CsvSource({
                "AB,     5, 'AB   '",
                "ABCDE,  5, ABCDE",
                "ABCDEFG, 5, ABCDE",
                "'',     3, '   '"
        })
        void padTextFollowsThePicXRule(String value, int width, String expected) {
            assertThat(CobolRecordFormatter.padText(value, width)).isEqualTo(expected);
        }

        @Test
        @DisplayName("padText treats null as an empty field")
        void padTextTreatsNullAsEmpty() {
            assertThat(CobolRecordFormatter.padText(null, 4)).isEqualTo("    ");
        }

        @ParameterizedTest
        @DisplayName("padNumericZeros right-justifies with zero fill and keeps the low-order digits")
        @CsvSource({
                "7,       4, 0007",
                "1234,    4, 1234",
                "123456,  4, 3456",
                "-25,     4, 0025"
        })
        void padNumericZerosFollowsThePic9Rule(long value, int width, String expected) {
            assertThat(CobolRecordFormatter.padNumericZeros(value, width)).isEqualTo(expected);
        }

        @Test
        @DisplayName("padNumericZeros treats null as zero for both boxed types")
        void padNumericZerosTreatsNullAsZero() {
            assertThat(CobolRecordFormatter.padNumericZeros((Long) null, 3)).isEqualTo("000");
            assertThat(CobolRecordFormatter.padNumericZeros((Integer) null, 3)).isEqualTo("000");
        }

        @ParameterizedTest
        @DisplayName("padNumericText strips non-digits before zero-padding")
        @CsvSource({
                "747,          3, 747",
                "12-34,        6, 001234",
                "'',           3, 000",
                "020973888,    9, 020973888"
        })
        void padNumericTextStripsNonDigits(String value, int width, String expected) {
            assertThat(CobolRecordFormatter.padNumericText(value, width)).isEqualTo(expected);
        }

        @ParameterizedTest
        @DisplayName("encodeSignedZonedDecimal carries the sign as a trailing overpunch")
        @CsvSource({
                "194.00,        00000001940{",
                "-194.00,       00000001940}",
                "0.00,          00000000000{",
                "504.77,        00000005047G",
                "-504.77,       00000005047P",
                "9999999999.99, 99999999999I",
                "0.01,          00000000000A",
                "-0.01,         00000000000J"
        })
        void encodeSignedZonedDecimalUsesOverpunchSigns(String value, String expected) {
            assertThat(CobolRecordFormatter.encodeSignedZonedDecimal(new BigDecimal(value), 10, 2))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("encodeSignedZonedDecimal treats null as zero and rounds HALF_UP to the field scale")
        void encodeSignedZonedDecimalNormalizesTheScale() {
            assertThat(CobolRecordFormatter.encodeSignedZonedDecimal(null, 10, 2))
                    .isEqualTo("00000000000{");
            assertThat(CobolRecordFormatter.encodeSignedZonedDecimal(new BigDecimal("1.005"), 10, 2))
                    .isEqualTo("00000000010A");
        }

        @Test
        @DisplayName("the formatter is a static utility and cannot be instantiated meaningfully")
        void formatterIsAStaticUtility() throws Exception {
            Constructor<CobolRecordFormatter> constructor =
                    CobolRecordFormatter.class.getDeclaredConstructor();
            assertThat(constructor.canAccess(null)).isFalse();
        }
    }
}
