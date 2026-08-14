package com.carddemo.ledger.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no rendering of a ledger row carries a monetary value.
 *
 * <p>{@link Object#toString()} lands in a log line the moment code concatenates an entity into a
 * message, and it is also what an assertion failure, a debugger view and the message of an
 * exception all reach for. A balance and its two cycle accumulators are the financial position of
 * one cardholder, and an amount beside a type code, a category code and an origin timestamp is the
 * whole of a statement line in everything but the merchant's name. Neither belongs in a log.
 *
 * <p>The identifiers are kept on purpose and asserted as kept, because a row that diverges has to
 * be nameable. Withholding the key along with the value would make a rendering useless rather than
 * safe.
 *
 * <p>Every test runs in memory. None opens a database connection or contacts a broker.
 */
@DisplayName("ledger row renderings carry no monetary value")
class EntityRenderingRedactionTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, columns 1 through 11. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Row one of {@code app/data/ASCII/dailytran.txt}, columns 1 through 16. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** The masked form the {@code transaction} table stores, twelve asterisks and four digits. */
    private static final String MASKED_CARD_NUMBER = "*".repeat(12) + "5740";

    @Nested
    @DisplayName("The balance projection withholds all three amounts and the account it holds")
    class BalanceProjectionRendering {

        @Test
        @DisplayName("no amount reaches the rendering, and neither does the account identifier")
        void noAmountReachesTheRendering() {
            String rendered = new AccountBalanceProjectionEntity(ACCOUNT_ID,
                    new BigDecimal("1234.56"), new BigDecimal("7654.32"),
                    new BigDecimal("-99.01")).toString();

            for (String amount : List.of("1234.56", "7654.32", "-99.01", "99.01")) {
                assertFalse(rendered.contains(amount),
                        "the rendering carries the amount " + amount + ": " + rendered);
            }
            assertFalse(rendered.contains("accountId=" + ACCOUNT_ID),
                    "the account identifier is the key every event of this account carries, so a"
                            + " rendering naming it beside a balance is a step toward correlating a"
                            + " cardholder: " + rendered);
            assertTrue(rendered.contains("accountId=" + EventEnvelope.WITHHELD),
                    "the withheld identifier still names its column, so a reader knows which row"
                            + " this is a rendering of: " + rendered);
            assertTrue(rendered.contains("currentBalance=" + EventEnvelope.WITHHELD)
                            && rendered.contains("cycleCredit=" + EventEnvelope.WITHHELD)
                            && rendered.contains("cycleDebit=" + EventEnvelope.WITHHELD),
                    "each withheld amount still names its column: " + rendered);
        }

        @Test
        @DisplayName("a zero balance is withheld too, because zero is a position like any other")
        void aZeroBalanceIsWithheldToo() {
            String rendered = new AccountBalanceProjectionEntity(ACCOUNT_ID,
                    new BigDecimal("0.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00")).toString();

            assertFalse(rendered.contains("0.00"),
                    "a rendering that printed zero would disclose a settled account: " + rendered);
        }
    }

    @Nested
    @DisplayName("The transaction row withholds the amount along with the spending detail")
    class TransactionRendering {

        @Test
        @DisplayName("the amount, the description, the card number and the merchant stay out")
        void theSpendingDetailStaysOut() {
            String rendered = new TransactionEntity(TRANSACTION_ID, "01", "0001", "POS",
                    "PARSER FIXTURE DESCRIPTION", new BigDecimal("-1234.56"), "000000000123456",
                    "PARSER FIXTURE MERCHANT", "PARSER FIXTURE CITY", "820019999",
                    MASKED_CARD_NUMBER, "2031-08-31 10:11:12.13", "2031-09-01 00:00:00").toString();

            for (String withheld : List.of("1234.56", "PARSER FIXTURE DESCRIPTION",
                    "PARSER FIXTURE MERCHANT", "PARSER FIXTURE CITY", "820019999",
                    MASKED_CARD_NUMBER)) {
                assertFalse(rendered.contains(withheld),
                        "the rendering carries " + withheld + ": " + rendered);
            }
            assertTrue(rendered.contains("amount=" + EventEnvelope.WITHHELD),
                    "the withheld amount still names its column: " + rendered);
            assertTrue(rendered.contains("transactionId=" + TRANSACTION_ID),
                    "the rendering keeps the transaction identifier: " + rendered);
            assertTrue(rendered.contains("typeCode=" + EventEnvelope.WITHHELD)
                            && rendered.contains("categoryCode=" + EventEnvelope.WITHHELD),
                    "the two classification codes name no cardholder on their own, and they are"
                            + " withheld all the same: a type code and a category code beside a"
                            + " transaction identifier say what was bought, and each still names its"
                            + " column so a reader can see the row is complete: " + rendered);
        }
    }
}
