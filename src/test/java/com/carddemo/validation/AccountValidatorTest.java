package com.carddemo.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.entity.Account;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.OverlimitException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit / parity test for {@link AccountValidator}.
 *
 * <p>Verifies the validator reproduces the CBTRN02C {@code 1500-B-LOOKUP-ACCT} account
 * checks (L403-L420) EXACTLY:</p>
 * <ul>
 *   <li><b>PR-03 last-writer-wins</b> &mdash; the two checks are independent (no early
 *       throw); when both fail, expiration (code 103) overwrites overlimit (code 102),
 *       so the thrown exception is {@link ExpiredAccountException}.</li>
 *   <li><b>PR-03 exact messages</b> &mdash; the no-arg constructors are thrown, so
 *       {@code getMessage()} is the verbatim COBOL literal with no appended diagnostics.</li>
 *   <li><b>PR-04</b> &mdash; credit-limit formula {@code creditLimit < (currCycCredit -
 *       currCycDebit + amount)} with exact operand order; failure only when strictly less.</li>
 *   <li><b>PR-05</b> &mdash; expiration compares {@code expirationDate} to the first 10
 *       characters (yyyy-MM-dd) of the transaction timestamp; failure only when strictly
 *       earlier.</li>
 * </ul>
 *
 * <p>{@link AccountValidator} is a stateless component, so it is instantiated directly with
 * no Spring context or mocks.</p>
 */
@DisplayName("AccountValidator — CBTRN02C 1500-B-LOOKUP-ACCT parity (PR-03/04/05)")
class AccountValidatorTest {

    private static final String OVERLIMIT_MSG = "OVERLIMIT TRANSACTION";
    private static final String EXPIRED_MSG = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    private final AccountValidator validator = new AccountValidator();

    /**
     * Builds an account with the given credit limit, cycle buckets and expiration date.
     * Other fields are irrelevant to the two validated checks.
     */
    private Account account(String creditLimit, String currCycCredit, String currCycDebit,
                            LocalDate expirationDate) {
        return Account.builder()
                .creditLimit(new BigDecimal(creditLimit))
                .currCycCredit(new BigDecimal(currCycCredit))
                .currCycDebit(new BigDecimal(currCycDebit))
                .expirationDate(expirationDate)
                .build();
    }

    /** Builds a daily transaction with the given amount and original timestamp. */
    private DailyTransaction tran(String amount, LocalDateTime origTimestamp) {
        return DailyTransaction.builder()
                .amount(new BigDecimal(amount))
                .origTimestamp(origTimestamp)
                .build();
    }

    @Nested
    @DisplayName("Happy path")
    class Valid {

        @Test
        @DisplayName("Within limit and before expiration → no exception")
        void passesWhenWithinLimitAndNotExpired() {
            // projected = 100 - 50 + 10 = 60 <= 1000 (OK); expiration 2030 >= 2022 (OK).
            Account account = account("1000.00", "100.00", "50.00", LocalDate.of(2030, 1, 1));
            DailyTransaction tran = tran("10.00", LocalDateTime.of(2022, 7, 18, 12, 0));

            assertThatCode(() -> validator.validateForTransaction(account, tran))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PR-04 boundary: creditLimit == projectedBalance → not overlimit (>= passes)")
        void boundaryEqualLimitPasses() {
            // projected = 110 - 0 + 0 = 110; creditLimit 110 == 110 -> compareTo == 0, not < 0.
            Account account = account("110.00", "110.00", "0.00", LocalDate.of(2030, 1, 1));
            DailyTransaction tran = tran("0.00", LocalDateTime.of(2022, 7, 18, 12, 0));

            assertThatCode(() -> validator.validateForTransaction(account, tran))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PR-05 boundary: expirationDate == tranDate → not expired (>= passes)")
        void boundaryEqualDatePasses() {
            // expiration 2022-07-18 == tranDate 2022-07-18 -> compareTo == 0, not < 0.
            Account account = account("1000.00", "0.00", "0.00", LocalDate.of(2022, 7, 18));
            DailyTransaction tran = tran("1.00", LocalDateTime.of(2022, 7, 18, 23, 59));

            assertThatCode(() -> validator.validateForTransaction(account, tran))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Single-failure cases")
    class SingleFailure {

        @Test
        @DisplayName("Only overlimit fails → code 102 with exact COBOL message")
        void overlimitOnly() {
            // projected = 90 - 0 + 20 = 110 > 100 (overlimit); expiration 2030 >= 2022 (OK).
            Account account = account("100.00", "90.00", "0.00", LocalDate.of(2030, 1, 1));
            DailyTransaction tran = tran("20.00", LocalDateTime.of(2022, 7, 18, 12, 0));

            assertThatThrownBy(() -> validator.validateForTransaction(account, tran))
                    .isInstanceOf(OverlimitException.class)
                    .hasMessage(OVERLIMIT_MSG);
        }

        @Test
        @DisplayName("Only expiration fails → code 103 with exact COBOL message")
        void expiredOnly() {
            // projected = 0 - 0 + 10 = 10 <= 1000 (OK); expiration 2020 < 2022 (expired).
            Account account = account("1000.00", "0.00", "0.00", LocalDate.of(2020, 1, 1));
            DailyTransaction tran = tran("10.00", LocalDateTime.of(2022, 7, 18, 12, 0));

            assertThatThrownBy(() -> validator.validateForTransaction(account, tran))
                    .isInstanceOf(ExpiredAccountException.class)
                    .hasMessage(EXPIRED_MSG);
        }
    }

    @Nested
    @DisplayName("Both-fail precedence (PR-03 last-writer-wins)")
    class BothFail {

        @Test
        @DisplayName("Overlimit AND expired → expiration (103) wins, exact message, no diagnostics")
        void expirationWinsWhenBothFail() {
            // projected = 90 - 0 + 20 = 110 > 100 (overlimit) AND expiration 2020 < 2022 (expired).
            Account account = account("100.00", "90.00", "0.00", LocalDate.of(2020, 1, 1));
            DailyTransaction tran = tran("20.00", LocalDateTime.of(2022, 7, 18, 12, 0));

            assertThatThrownBy(() -> validator.validateForTransaction(account, tran))
                    .isInstanceOf(ExpiredAccountException.class)
                    .hasMessage(EXPIRED_MSG)
                    // PR-03 / F4: no diagnostic text (limit/attempted/dates) leaks into the message.
                    .matches(ex -> ex.getMessage().equals(EXPIRED_MSG));
        }
    }
}
