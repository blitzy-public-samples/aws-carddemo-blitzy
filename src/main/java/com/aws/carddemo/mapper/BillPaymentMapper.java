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
package com.aws.carddemo.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.dto.BillPaymentResponse;

/**
 * Hand-written mapper that assembles the {@link BillPaymentResponse} output
 * contract for the CardDemo <em>Bill Payment</em> screen from an {@link Account}
 * entity plus caller-supplied screen context.
 *
 * <p>This component is the Java re-platform of the screen-population logic in the
 * COBOL online program {@code COBIL00C} (CICS transaction {@code CB00}). In the
 * legacy program the {@code POPULATE-HEADER-INFO} paragraph fills the map header
 * (transaction / titles / date / program / time) and {@code SEND-BILLPAY-SCREEN}
 * places the current balance and status message onto the {@code COBIL0AO}
 * symbolic output map (BMS symbolic copybook
 * {@code legacy/cpy-bms/COBIL00.CPY}). Those two paragraphs are the sole source
 * of the field assignments performed here.</p>
 *
 * <h2>Field mapping ({@code COBIL0AO} &rarr; {@link BillPaymentResponse})</h2>
 * <table border="1">
 *   <caption>Symbolic-map-to-DTO field mapping</caption>
 *   <tr><th>BMS field (PIC)</th><th>Response component</th><th>Legacy source</th></tr>
 *   <tr><td>{@code TRNNAMEO} X(4)</td><td>{@code transactionName}</td>
 *       <td>{@code WS-TRANID} = {@value #TRANSACTION_NAME}</td></tr>
 *   <tr><td>{@code TITLE01O} X(40)</td><td>{@code title01}</td>
 *       <td>{@code CCDA-TITLE01} (caller context; default {@link #TITLE01})</td></tr>
 *   <tr><td>{@code CURDATEO} X(8)</td><td>{@code currentDate}</td>
 *       <td>{@code FUNCTION CURRENT-DATE} &rarr; {@code MM/DD/YY}</td></tr>
 *   <tr><td>{@code PGMNAMEO} X(8)</td><td>{@code programName}</td>
 *       <td>{@code WS-PGMNAME} = {@value #PROGRAM_NAME}</td></tr>
 *   <tr><td>{@code TITLE02O} X(40)</td><td>{@code title02}</td>
 *       <td>{@code CCDA-TITLE02} (caller context; default {@link #TITLE02})</td></tr>
 *   <tr><td>{@code CURTIMEO} X(8)</td><td>{@code currentTime}</td>
 *       <td>{@code EXEC CICS FORMATTIME} &rarr; {@code HH:MM:SS}</td></tr>
 *   <tr><td>{@code ACTIDINO} X(11)</td><td>{@code accountId}</td>
 *       <td>{@code ACCT-ID} (or the operator-entered account id)</td></tr>
 *   <tr><td>{@code CURBALO} X(14)</td><td>{@code currentBalance}</td>
 *       <td>{@code ACCT-CURR-BAL} (as a scaled {@link BigDecimal})</td></tr>
 *   <tr><td>{@code ERRMSGO} X(78)</td><td>{@code errorMessage}</td>
 *       <td>{@code WS-MESSAGE}</td></tr>
 * </table>
 *
 * <h2>Migration rules honored</h2>
 * <ul>
 *   <li><strong>Monetary fidelity.</strong> The balance is carried as a
 *       {@link BigDecimal} at scale {@value #MONETARY_SCALE} using
 *       {@link RoundingMode#HALF_UP}; {@code double}/{@code float} are never used.
 *       Only the scale is guaranteed &mdash; the numeric value is copied without
 *       alteration, reproducing the {@code V99} precision of
 *       {@code ACCT-CURR-BAL PIC S9(10)V99}. (The COBOL program renders that value
 *       through the numeric-edited field {@code WS-CURR-BAL PIC +9999999999.99}
 *       for the 3270 display; the REST contract models the underlying numeric
 *       value instead of the edited display string.)</li>
 *   <li><strong>Date/time via {@link DateUtils}.</strong> {@code currentDate} and
 *       {@code currentTime} are formatted exclusively through the shared
 *       {@code java.time}-based helpers ({@code MM/dd/uu} and {@code HH:mm:ss}),
 *       the Java re-platform of {@code CSDAT01Y}.</li>
 *   <li><strong>Identifier rendering.</strong> The {@code Long} account key is
 *       rendered with {@link Long#toString(long)}, yielding plain digits with no
 *       locale or grouping artifacts.</li>
 *   <li><strong>No business logic.</strong> Balance posting and account updates
 *       are owned by {@code BillPaymentService} within its transactional
 *       boundary; this mapper is a pure, side-effect-free projection. There is
 *       deliberately no {@code toEntity} method &mdash; the inbound request DTO is
 *       consumed directly by the service.</li>
 * </ul>
 *
 * <p>The instance is stateless (it holds no mutable fields, performs no I/O, and
 * keeps no request state), so the single Spring-managed singleton is safe to
 * share across threads. Every accessor is null-safe: a {@code null} account,
 * account id, balance, or timestamp yields {@code null} for the corresponding
 * response component rather than throwing.</p>
 */
@Component
public class BillPaymentMapper {

    /**
     * Transaction identifier shown in the screen header. Mirrors
     * {@code WS-TRANID PIC X(04) VALUE 'CB00'} in {@code COBIL00C} and populates
     * {@link BillPaymentResponse#transactionName()} ({@code TRNNAMEO},
     * {@code PIC X(4)}).
     */
    public static final String TRANSACTION_NAME = "CB00";

    /**
     * Program identifier shown in the screen header. Mirrors
     * {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} in {@code COBIL00C} and
     * populates {@link BillPaymentResponse#programName()} ({@code PGMNAMEO},
     * {@code PIC X(8)}).
     */
    public static final String PROGRAM_NAME = "COBIL00C";

    /**
     * Default first header title line. The active {@code CCDA-TITLE01} value from
     * the shared copybook {@code legacy/cpy/COTTL01Y.cpy}, preserved at its full
     * 40-character width ({@code TITLE01O}, {@code PIC X(40)}). Used by the
     * convenience overloads; callers that own the shared title source may supply
     * their own value through the five-argument overloads.
     */
    public static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Default second header title line. The active {@code CCDA-TITLE02} value from
     * the shared copybook {@code legacy/cpy/COTTL01Y.cpy} (the
     * {@code 'Credit Card Demo Application (CCDA)'} alternative is commented out in
     * the copybook), preserved at its full 40-character width ({@code TITLE02O},
     * {@code PIC X(40)}).
     */
    public static final String TITLE02 = "              CardDemo                  ";

    /**
     * Fixed number of decimal places applied to the monetary balance, matching the
     * {@code V99} of {@code ACCT-CURR-BAL PIC S9(10)V99}.
     */
    private static final int MONETARY_SCALE = 2;

    /**
     * Builds the Bill Payment response for a successfully looked-up account, using
     * caller-supplied header title lines.
     *
     * <p>The account key and current balance are taken from {@code account}; the
     * transaction and program names are the fixed screen constants
     * ({@link #TRANSACTION_NAME} / {@link #PROGRAM_NAME}); the date and time are
     * derived from {@code now}; and the two title lines and the error/status
     * message are supplied by the caller.</p>
     *
     * @param account      the account being presented for payment; may be
     *                     {@code null}, in which case both the account id and the
     *                     balance are rendered as {@code null}
     * @param errorMessage the status/error message line ({@code ERRMSGO}); may be
     *                     {@code null} or blank when there is no message
     * @param now          the timestamp used to render the header date and time;
     *                     may be {@code null}, in which case both are {@code null}
     * @param title01      the first header title line ({@code TITLE01O})
     * @param title02      the second header title line ({@code TITLE02O})
     * @return a fully-populated {@link BillPaymentResponse}
     */
    public BillPaymentResponse toResponse(Account account,
                                          String errorMessage,
                                          LocalDateTime now,
                                          String title01,
                                          String title02) {
        String accountId = null;
        BigDecimal currentBalance = null;
        if (account != null) {
            Long acctId = account.getAcctId();
            accountId = (acctId == null) ? null : Long.toString(acctId);
            currentBalance = scaleToMonetary(account.getCurrBal());
        }
        return build(accountId, currentBalance, errorMessage, now, title01, title02);
    }

    /**
     * Builds the Bill Payment response for the not-found / pre-lookup case, echoing
     * the operator-entered account id and using caller-supplied header title lines.
     *
     * <p>No {@link Account} is available, so the current balance is left
     * {@code null}. This reproduces the legacy behavior in which the account key is
     * echoed on the screen alongside a validation or not-found message before (or
     * instead of) a balance being displayed.</p>
     *
     * @param accountId    the operator-entered account id to echo
     *                     ({@code ACTIDINO}); may be {@code null} or blank
     * @param errorMessage the status/error message line ({@code ERRMSGO}); may be
     *                     {@code null} or blank
     * @param now          the timestamp used to render the header date and time;
     *                     may be {@code null}, in which case both are {@code null}
     * @param title01      the first header title line ({@code TITLE01O})
     * @param title02      the second header title line ({@code TITLE02O})
     * @return a {@link BillPaymentResponse} with a {@code null} current balance
     */
    public BillPaymentResponse toResponse(String accountId,
                                          String errorMessage,
                                          LocalDateTime now,
                                          String title01,
                                          String title02) {
        return build(accountId, null, errorMessage, now, title01, title02);
    }

    /**
     * Convenience overload of
     * {@link #toResponse(Account, String, LocalDateTime, String, String)} that uses
     * the default header title lines ({@link #TITLE01} / {@link #TITLE02}).
     *
     * @param account      the account being presented for payment; may be
     *                     {@code null}
     * @param errorMessage the status/error message line; may be {@code null}
     * @param now          the timestamp for the header date/time; may be
     *                     {@code null}
     * @return a fully-populated {@link BillPaymentResponse}
     */
    public BillPaymentResponse toResponse(Account account, String errorMessage, LocalDateTime now) {
        return toResponse(account, errorMessage, now, TITLE01, TITLE02);
    }

    /**
     * Convenience overload of
     * {@link #toResponse(String, String, LocalDateTime, String, String)} that uses
     * the default header title lines ({@link #TITLE01} / {@link #TITLE02}) for the
     * not-found / pre-lookup case.
     *
     * @param accountId    the operator-entered account id to echo; may be
     *                     {@code null}
     * @param errorMessage the status/error message line; may be {@code null}
     * @param now          the timestamp for the header date/time; may be
     *                     {@code null}
     * @return a {@link BillPaymentResponse} with a {@code null} current balance
     */
    public BillPaymentResponse toResponse(String accountId, String errorMessage, LocalDateTime now) {
        return toResponse(accountId, errorMessage, now, TITLE01, TITLE02);
    }

    /**
     * Single field-by-field assembly point shared by every public overload. The
     * component order matches the {@link BillPaymentResponse} record canonical
     * constructor exactly.
     *
     * @param accountId      the echoed account id ({@code ACTIDINO})
     * @param currentBalance the balance already scaled to
     *                       {@value #MONETARY_SCALE}, or {@code null}
     * @param errorMessage   the status/error message ({@code ERRMSGO})
     * @param now            the timestamp for the header date/time, or {@code null}
     * @param title01        the first header title line ({@code TITLE01O})
     * @param title02        the second header title line ({@code TITLE02O})
     * @return the assembled response
     */
    private BillPaymentResponse build(String accountId,
                                      BigDecimal currentBalance,
                                      String errorMessage,
                                      LocalDateTime now,
                                      String title01,
                                      String title02) {
        String currentDate = (now == null) ? null : DateUtils.formatDateMmDdYy(now.toLocalDate());
        String currentTime = (now == null) ? null : DateUtils.formatTimeHhMmSs(now.toLocalTime());
        return new BillPaymentResponse(
                TRANSACTION_NAME,
                title01,
                currentDate,
                PROGRAM_NAME,
                title02,
                currentTime,
                accountId,
                currentBalance,
                errorMessage);
    }

    /**
     * Guarantees the fixed monetary scale ({@value #MONETARY_SCALE}) for a balance
     * without altering its numeric value, using {@link RoundingMode#HALF_UP} to
     * mirror the legacy packed-decimal arithmetic. A {@code null} value is returned
     * unchanged.
     *
     * @param value the balance to normalize; may be {@code null}
     * @return the value at scale {@value #MONETARY_SCALE}, or {@code null}
     */
    private static BigDecimal scaleToMonetary(BigDecimal value) {
        return (value == null) ? null : value.setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
    }
}
