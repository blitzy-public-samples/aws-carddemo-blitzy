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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.TransactionAddRequest;
import com.aws.carddemo.dto.TransactionAddResponse;
import com.aws.carddemo.dto.TransactionListResponse;
import com.aws.carddemo.dto.TransactionViewResponse;

/**
 * Hand-written entity&harr;DTO mapper for the three CardDemo <em>transaction</em>
 * screens, bridging the {@link Transaction} JPA entity (the Java re-platform of
 * copybook {@code legacy/cpy/CVTRA05Y.cpy}, {@code TRAN-RECORD}) and the
 * request/response DTOs of the transaction list, view and add screens.
 *
 * <p>Source lineage &mdash; each mapping method reproduces the field-level
 * contract of a legacy 3270/CICS program and its BMS symbolic map:</p>
 * <ul>
 *   <li><strong>List</strong> ({@code CT00} / {@code COTRN00C} / map
 *       {@code COTRN00}, copybook {@code legacy/cpy-bms/COTRN00.CPY}) &rarr;
 *       {@link #toListRow(Transaction)} and
 *       {@link #toListResponse(List, String, String, LocalDateTime)}.</li>
 *   <li><strong>View</strong> ({@code CT01} / {@code COTRN01C} / map
 *       {@code COTRN01}, copybook {@code legacy/cpy-bms/COTRN01.CPY}) &rarr;
 *       {@link #toViewResponse(Transaction, String, LocalDateTime)}.</li>
 *   <li><strong>Add</strong> ({@code CT02} / {@code COTRN02C} / map
 *       {@code COTRN02}, copybook {@code legacy/cpy-bms/COTRN02.CPY}) &rarr;
 *       {@link #toEntity(TransactionAddRequest)},
 *       {@link #toAddResponse(TransactionAddRequest, String, LocalDateTime)} and
 *       the post-persist overload
 *       {@link #toAddResponse(Transaction, String, String, LocalDateTime)}.</li>
 * </ul>
 *
 * <p><strong>Design contract (must-hold invariants).</strong></p>
 * <ul>
 *   <li>The mapping is <em>explicit and hand-written</em>: no annotation-processor
 *       or mapping library is used, so every field assignment is visible and
 *       traceable to its COBOL counterpart.</li>
 *   <li>The component is <em>stateless</em> and therefore thread-safe; it may be
 *       shared as a singleton Spring bean and holds no mutable state.
 *       {@link DateUtils} is consumed only through its {@code static} API.</li>
 *   <li>Monetary values are always {@link BigDecimal} carried at
 *       {@value #MONEY_SCALE}-digit scale via {@link RoundingMode#HALF_UP};
 *       binary floating-point ({@code double}/{@code float}) is never used. The
 *       value itself is preserved &mdash; only the scale is normalized.</li>
 *   <li>The screen <em>header</em> (transaction name, the two title lines,
 *       program name, current date and current time) is reproduced from the
 *       legacy {@code POPULATE-HEADER-INFO} paragraph: the titles are the fixed
 *       {@code CCDA-TITLE01}/{@code CCDA-TITLE02} literals of copybook
 *       {@code legacy/cpy/COTTL01Y.cpy}; the current date/time are derived from
 *       the supplied {@code now} through {@link DateUtils} using the
 *       {@code MM/dd/uu} and {@code HH:mm:ss} masks.</li>
 * </ul>
 *
 * <p>This class contains presentation/transport mapping only; it performs no
 * persistence, no validation of business rules, and no navigation. The
 * authoritative edit logic, transaction-id generation and account/card
 * resolution live in the service layer ({@code service.TransactionService} and
 * {@code service.rule}).</p>
 */
@Component
public class TransactionMapper {

    /**
     * Fixed scale for every monetary {@link BigDecimal} produced by this mapper,
     * matching the COBOL {@code PIC S9(09)V99} packed-decimal definition of
     * {@code TRAN-AMT}.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * First screen title line &mdash; the verbatim 40-character
     * {@code CCDA-TITLE01} literal from copybook {@code legacy/cpy/COTTL01Y.cpy},
     * preserved with its original leading/trailing padding so the field contract
     * stays byte-faithful to the 3270 screen.
     */
    private static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen title line &mdash; the verbatim 40-character
     * {@code CCDA-TITLE02} literal from copybook {@code legacy/cpy/COTTL01Y.cpy}.
     */
    private static final String TITLE_02 = "              CardDemo                  ";

    /** Transaction-name header ({@code WS-TRANID}) of the list screen {@code COTRN00C}. */
    private static final String TXN_NAME_LIST = "CT00";

    /** Program-name header ({@code WS-PGMNAME}) of the list screen {@code COTRN00C}. */
    private static final String PGM_NAME_LIST = "COTRN00C";

    /** Transaction-name header ({@code WS-TRANID}) of the view screen {@code COTRN01C}. */
    private static final String TXN_NAME_VIEW = "CT01";

    /** Program-name header ({@code WS-PGMNAME}) of the view screen {@code COTRN01C}. */
    private static final String PGM_NAME_VIEW = "COTRN01C";

    /** Transaction-name header ({@code WS-TRANID}) of the add screen {@code COTRN02C}. */
    private static final String TXN_NAME_ADD = "CT02";

    /** Program-name header ({@code WS-PGMNAME}) of the add screen {@code COTRN02C}. */
    private static final String PGM_NAME_ADD = "COTRN02C";

    // ------------------------------------------------------------------------
    // Transaction List screen (CT00 / COTRN00C / map COTRN00)
    // ------------------------------------------------------------------------

    /**
     * Projects a single {@link Transaction} onto one summary row of the
     * transaction-list screen, reproducing the legacy {@code POPULATE-TRAN-DATA}
     * paragraph of {@code COTRN00C} (one of the ten fixed
     * {@code TRNIDnn}/{@code TDATEnn}/{@code TDESCnn}/{@code TAMT00n} slots).
     *
     * <p>The display date is the origination date rendered {@code MM/DD/YY}: the
     * program moves {@code TRAN-ORIG-TS} into the timestamp working area and
     * emits its {@code YYYY-MM-DD} portion as {@code MM/DD/YY}. The amount is
     * carried as a scale-{@value #MONEY_SCALE} {@link BigDecimal}; the COBOL
     * {@code +99999999.99} edit picture is a terminal-rendering concern and is
     * therefore not applied in the REST projection.</p>
     *
     * @param t the source transaction; must not be {@code null}
     * @return a populated {@link TransactionListResponse.TransactionListRow}
     * @throws NullPointerException if {@code t} is {@code null}
     */
    public TransactionListResponse.TransactionListRow toListRow(Transaction t) {
        Objects.requireNonNull(t, "transaction must not be null");
        return new TransactionListResponse.TransactionListRow(
                t.getTranId(),               // TRNIDnnO  <- TRAN-ID
                toListDate(t.getOrigTs()),   // TDATEnnO  <- MM/DD/YY of TRAN-ORIG-TS
                t.getTranDesc(),             // TDESCnnO  <- TRAN-DESC
                scale2(t.getTranAmt()));     // TAMT00nO  <- TRAN-AMT (scale 2)
    }

    /**
     * Builds the full transaction-list response: the standard screen header, the
     * page indicator, up to ten summary rows and the message line. This is the
     * output side of {@code COTRN00C} (symbolic map {@code COTRN0AO}).
     *
     * <p>The {@code transactions} collection is always an immutable, non-{@code null}
     * projection &mdash; a {@code null} {@code txns} argument yields an empty page,
     * and each element is mapped through {@link #toListRow(Transaction)} in the
     * supplied order (the browse order the service establishes).</p>
     *
     * @param txns        the page of transactions to render; may be {@code null}
     *                    (treated as an empty page) and is never mutated
     * @param pageNumber  the page indicator for {@code PAGENUMO} (X(8)); passed
     *                    through verbatim and may be {@code null}
     * @param errorMessage the message-line text for {@code ERRMSGO} (X(78)); may
     *                    be {@code null} when there is no message
     * @param now         the current timestamp used to render the header date and
     *                    time; must not be {@code null}
     * @return a fully-populated, immutable {@link TransactionListResponse}
     * @throws NullPointerException if {@code now} is {@code null}, or if any
     *                              element of {@code txns} is {@code null}
     */
    public TransactionListResponse toListResponse(List<Transaction> txns,
                                                  String pageNumber,
                                                  String errorMessage,
                                                  LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        List<TransactionListResponse.TransactionListRow> rows =
                (txns == null) ? List.of() : txns.stream().map(this::toListRow).toList();
        return new TransactionListResponse(
                TXN_NAME_LIST,                                    // TRNNAMEO
                TITLE_01,                                         // TITLE01O
                TITLE_02,                                         // TITLE02O
                DateUtils.formatDateMmDdYy(now.toLocalDate()),    // CURDATEO
                PGM_NAME_LIST,                                    // PGMNAMEO
                DateUtils.formatTimeHhMmSs(now.toLocalTime()),    // CURTIMEO
                pageNumber,                                       // PAGENUMO
                rows,                                             // TRNIDnn/TDATEnn/TDESCnn/TAMT00n
                errorMessage);                                    // ERRMSGO
    }

    // ------------------------------------------------------------------------
    // Transaction View screen (CT01 / COTRN01C / map COTRN01)
    // ------------------------------------------------------------------------

    /**
     * Projects a single {@link Transaction} onto the transaction-view detail
     * response, reproducing the field moves of {@code COTRN01C} into symbolic map
     * {@code COTRN1AO}. Every observable detail field is carried name-for-name.
     *
     * <p>Type conversions preserve the legacy display contract: the numeric
     * category code ({@code TRAN-CAT-CD PIC 9(04)}) is rendered as a zero-padded
     * four-digit string, the numeric merchant id ({@code TRAN-MERCHANT-ID PIC
     * 9(09)}) as a zero-padded nine-digit string, and the two 26-character
     * timestamps ({@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS}) are truncated to
     * their leading ten characters ({@code YYYY-MM-DD}), exactly as the COBOL
     * moves them into the {@code X(10)} display fields. The amount is a
     * scale-{@value #MONEY_SCALE} {@link BigDecimal}.</p>
     *
     * @param t            the source transaction; must not be {@code null}
     * @param errorMessage the message-line text for {@code ERRMSGO} (X(78)); may
     *                     be {@code null} when there is no message
     * @param now          the current timestamp used to render the header date
     *                     and time; must not be {@code null}
     * @return a fully-populated {@link TransactionViewResponse}
     * @throws NullPointerException if {@code t} or {@code now} is {@code null}
     */
    public TransactionViewResponse toViewResponse(Transaction t,
                                                  String errorMessage,
                                                  LocalDateTime now) {
        Objects.requireNonNull(t, "transaction must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new TransactionViewResponse(
                TXN_NAME_VIEW,                                    // TRNNAMEO
                TITLE_01,                                         // TITLE01O
                TITLE_02,                                         // TITLE02O
                DateUtils.formatDateMmDdYy(now.toLocalDate()),    // CURDATEO
                PGM_NAME_VIEW,                                    // PGMNAMEO
                DateUtils.formatTimeHhMmSs(now.toLocalTime()),    // CURTIMEO
                t.getTranId(),                                    // TRNIDO   <- TRAN-ID
                t.getCardNum(),                                   // CARDNUMO <- TRAN-CARD-NUM
                t.getTypeCd(),                                    // TTYPCDO  <- TRAN-TYPE-CD
                formatCatCd(t.getCatCd()),                        // TCATCDO  <- TRAN-CAT-CD (zero-padded)
                t.getTranSource(),                                // TRNSRCO  <- TRAN-SOURCE
                t.getTranDesc(),                                  // TDESCO   <- TRAN-DESC
                scale2(t.getTranAmt()),                           // TRNAMTO  <- TRAN-AMT (scale 2)
                leadingTen(t.getOrigTs()),                        // TORIGDTO <- TRAN-ORIG-TS[0:10]
                leadingTen(t.getProcTs()),                        // TPROCDTO <- TRAN-PROC-TS[0:10]
                formatMerchantId(t.getTranMerchantId()),          // MIDO     <- TRAN-MERCHANT-ID (zero-padded)
                t.getTranMerchantName(),                          // MNAMEO   <- TRAN-MERCHANT-NAME
                t.getTranMerchantCity(),                          // MCITYO   <- TRAN-MERCHANT-CITY
                t.getTranMerchantZip(),                           // MZIPO    <- TRAN-MERCHANT-ZIP
                errorMessage);                                    // ERRMSGO
    }

    // ------------------------------------------------------------------------
    // Transaction Add screen (CT02 / COTRN02C / map COTRN02)
    // ------------------------------------------------------------------------

    /**
     * Builds a new {@link Transaction} entity from an add-screen request,
     * reproducing the record assembly of {@code COTRN02C} prior to the write.
     *
     * <p><strong>The primary key is intentionally left {@code null}:</strong> the
     * 16-character {@code TRAN-ID} is assigned by the service layer
     * ({@code service.TransactionService} via {@code common.util.IdGenerator},
     * the increment-from-max parity of the COBOL reverse browse), never by this
     * mapper.</p>
     *
     * <p>The category code and merchant id are parsed from their display strings
     * to the entity's {@link Integer}/{@link Long} types (a blank value becomes
     * {@code null}); the amount is normalized to scale {@value #MONEY_SCALE}. The
     * entered origination and processing dates are assigned verbatim to the
     * timestamp fields, matching the {@code MOVE TORIGDTI TO TRAN-ORIG-TS} /
     * {@code MOVE TPROCDTI TO TRAN-PROC-TS} convention of {@code COTRN02C}; no
     * time component is fabricated here.</p>
     *
     * <p>The request's {@code accountId} and {@code confirm} are deliberately
     * <em>not</em> mapped onto the entity: {@code accountId} is a lookup key the
     * service uses to resolve and validate the card/account cross-reference, and
     * {@code confirm} is the confirm-then-commit control flag &mdash; neither is a
     * field of {@code TRAN-RECORD}.</p>
     *
     * @param req the add-screen request; must not be {@code null}
     * @return a new, unpersisted {@link Transaction} with a {@code null} id
     * @throws NullPointerException  if {@code req} is {@code null}
     * @throws NumberFormatException if the category code or merchant id is
     *                               non-blank but not numeric (a condition the
     *                               service validates before mapping)
     */
    public Transaction toEntity(TransactionAddRequest req) {
        Objects.requireNonNull(req, "request must not be null");
        return new Transaction(
                null,                                 // TRAN-ID assigned by service (IdGenerator); never here
                req.typeCode(),                       // TRAN-TYPE-CD     <- TTYPCDI
                parseCatCd(req.categoryCode()),       // TRAN-CAT-CD      <- TCATCDI (parsed)
                req.source(),                         // TRAN-SOURCE      <- TRNSRCI
                req.description(),                    // TRAN-DESC        <- TDESCI
                scale2(req.amount()),                 // TRAN-AMT         <- TRNAMTI (scale 2)
                parseMerchantId(req.merchantId()),    // TRAN-MERCHANT-ID <- MIDI (parsed)
                req.merchantName(),                   // TRAN-MERCHANT-NAME <- MNAMEI
                req.merchantCity(),                   // TRAN-MERCHANT-CITY <- MCITYI
                req.merchantZip(),                    // TRAN-MERCHANT-ZIP  <- MZIPI
                req.cardNumber(),                     // TRAN-CARD-NUM    <- CARDNINI
                req.originDate(),                     // TRAN-ORIG-TS     <- TORIGDTI (entered date, verbatim)
                req.processDate());                   // TRAN-PROC-TS     <- TPROCDTI (entered date, verbatim)
    }

    /**
     * Echoes an add-screen request back onto the add response for the
     * confirm-then-commit redisplay of {@code COTRN02C} (symbolic map
     * {@code COTRN2AO}). Every entered field is returned verbatim &mdash; the
     * category code and merchant id are the strings the client supplied and are
     * not re-derived &mdash; together with the standard header and the message
     * line. The amount is normalized to scale {@value #MONEY_SCALE}.
     *
     * @param req          the submitted add-screen request; must not be {@code null}
     * @param errorMessage the message-line text for {@code ERRMSGO} (X(78)); may
     *                     be {@code null} when there is no message
     * @param now          the current timestamp used to render the header date
     *                     and time; must not be {@code null}
     * @return a populated {@link TransactionAddResponse} echoing the input
     * @throws NullPointerException if {@code req} or {@code now} is {@code null}
     */
    public TransactionAddResponse toAddResponse(TransactionAddRequest req,
                                                String errorMessage,
                                                LocalDateTime now) {
        Objects.requireNonNull(req, "request must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new TransactionAddResponse(
                TXN_NAME_ADD,                                     // TRNNAMEO
                TITLE_01,                                         // TITLE01O
                DateUtils.formatDateMmDdYy(now.toLocalDate()),    // CURDATEO (add header order)
                PGM_NAME_ADD,                                     // PGMNAMEO
                TITLE_02,                                         // TITLE02O
                DateUtils.formatTimeHhMmSs(now.toLocalTime()),    // CURTIMEO
                req.accountId(),                                  // ACTIDINO <- ACTIDINI
                req.cardNumber(),                                 // CARDNINO <- CARDNINI
                req.typeCode(),                                   // TTYPCDO  <- TTYPCDI
                req.categoryCode(),                               // TCATCDO  <- TCATCDI (echo verbatim)
                req.source(),                                     // TRNSRCO  <- TRNSRCI
                req.description(),                                // TDESCO   <- TDESCI
                scale2(req.amount()),                             // TRNAMTO  <- TRNAMTI (scale 2)
                req.originDate(),                                 // TORIGDTO <- TORIGDTI
                req.processDate(),                                // TPROCDTO <- TPROCDTI
                req.merchantId(),                                 // MIDO     <- MIDI (echo verbatim)
                req.merchantName(),                               // MNAMEO   <- MNAMEI
                req.merchantCity(),                               // MCITYO   <- MCITYI
                req.merchantZip(),                                // MZIPO    <- MZIPI
                req.confirm(),                                    // CONFIRMO <- CONFIRMI
                errorMessage);                                    // ERRMSGO
    }

    /**
     * Renders a persisted {@link Transaction} onto the add response for
     * post-commit redisplay. Because {@code accountId} is not a field of
     * {@code TRAN-RECORD}, the caller supplies the account identifier the service
     * resolved from the card cross-reference.
     *
     * <p>Field derivations mirror {@link #toViewResponse(Transaction, String,
     * LocalDateTime)}: the category code and merchant id are zero-padded from the
     * entity's numeric types, the timestamps are truncated to their leading ten
     * characters, and the amount is normalized to scale {@value #MONEY_SCALE}.
     * The {@code confirm} flag is returned blank because the confirm-then-commit
     * prompt no longer applies once the record is persisted.</p>
     *
     * @param t            the persisted transaction; must not be {@code null}
     * @param accountId    the account identifier the service resolved for this
     *                     card; echoed into {@code ACTIDINO} and may be
     *                     {@code null}
     * @param errorMessage the message-line text for {@code ERRMSGO} (X(78)); may
     *                     be {@code null} (typically a success confirmation)
     * @param now          the current timestamp used to render the header date
     *                     and time; must not be {@code null}
     * @return a populated {@link TransactionAddResponse} for the persisted record
     * @throws NullPointerException if {@code t} or {@code now} is {@code null}
     */
    public TransactionAddResponse toAddResponse(Transaction t,
                                                String accountId,
                                                String errorMessage,
                                                LocalDateTime now) {
        Objects.requireNonNull(t, "transaction must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new TransactionAddResponse(
                TXN_NAME_ADD,                                     // TRNNAMEO
                TITLE_01,                                         // TITLE01O
                DateUtils.formatDateMmDdYy(now.toLocalDate()),    // CURDATEO (add header order)
                PGM_NAME_ADD,                                     // PGMNAMEO
                TITLE_02,                                         // TITLE02O
                DateUtils.formatTimeHhMmSs(now.toLocalTime()),    // CURTIMEO
                accountId,                                        // ACTIDINO (resolved by service)
                t.getCardNum(),                                   // CARDNINO <- TRAN-CARD-NUM
                t.getTypeCd(),                                    // TTYPCDO  <- TRAN-TYPE-CD
                formatCatCd(t.getCatCd()),                        // TCATCDO  <- TRAN-CAT-CD (zero-padded)
                t.getTranSource(),                                // TRNSRCO  <- TRAN-SOURCE
                t.getTranDesc(),                                  // TDESCO   <- TRAN-DESC
                scale2(t.getTranAmt()),                           // TRNAMTO  <- TRAN-AMT (scale 2)
                leadingTen(t.getOrigTs()),                        // TORIGDTO <- TRAN-ORIG-TS[0:10]
                leadingTen(t.getProcTs()),                        // TPROCDTO <- TRAN-PROC-TS[0:10]
                formatMerchantId(t.getTranMerchantId()),          // MIDO     <- TRAN-MERCHANT-ID (zero-padded)
                t.getTranMerchantName(),                          // MNAMEO   <- TRAN-MERCHANT-NAME
                t.getTranMerchantCity(),                          // MCITYO   <- TRAN-MERCHANT-CITY
                t.getTranMerchantZip(),                           // MZIPO    <- TRAN-MERCHANT-ZIP
                "",                                               // CONFIRMO cleared after persist
                errorMessage);                                    // ERRMSGO
    }

    // ------------------------------------------------------------------------
    // Private field-conversion helpers
    // ------------------------------------------------------------------------

    /**
     * Normalizes a monetary amount to the fixed scale {@value #MONEY_SCALE} using
     * {@link RoundingMode#HALF_UP}, preserving the value. A {@code null} input
     * maps to {@code null}.
     *
     * @param amt the amount to normalize; may be {@code null}
     * @return the amount at scale {@value #MONEY_SCALE}, or {@code null}
     */
    private static BigDecimal scale2(BigDecimal amt) {
        return (amt == null) ? null : amt.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Renders the numeric transaction category code as a zero-padded four-digit
     * string ({@code X(4)}), reproducing the COBOL move of {@code TRAN-CAT-CD PIC
     * 9(04)} into an {@code X(4)} display field. A {@code null} input yields an
     * empty string.
     *
     * @param catCd the category code; may be {@code null}
     * @return the zero-padded four-digit code, or {@code ""} when {@code null}
     */
    private static String formatCatCd(Integer catCd) {
        return (catCd == null) ? "" : String.format(Locale.ROOT, "%04d", catCd);
    }

    /**
     * Parses a display category code back to its {@link Integer} entity value.
     * The value is trimmed; a blank (or {@code null}) input maps to {@code null}.
     *
     * @param categoryCode the display category code; may be {@code null}
     * @return the parsed {@link Integer}, or {@code null} when blank
     * @throws NumberFormatException if a non-blank value is not numeric
     */
    private static Integer parseCatCd(String categoryCode) {
        if (categoryCode == null) {
            return null;
        }
        String trimmed = categoryCode.trim();
        return trimmed.isEmpty() ? null : Integer.valueOf(trimmed);
    }

    /**
     * Renders the numeric merchant id as a zero-padded nine-digit string
     * ({@code X(9)}), reproducing the COBOL move of {@code TRAN-MERCHANT-ID PIC
     * 9(09)} into an {@code X(9)} display field. A {@code null} input yields an
     * empty string.
     *
     * @param merchantId the merchant id; may be {@code null}
     * @return the zero-padded nine-digit id, or {@code ""} when {@code null}
     */
    private static String formatMerchantId(Long merchantId) {
        return (merchantId == null) ? "" : String.format(Locale.ROOT, "%09d", merchantId);
    }

    /**
     * Parses a display merchant id back to its {@link Long} entity value. The
     * value is trimmed; a blank (or {@code null}) input maps to {@code null}.
     *
     * @param merchantId the display merchant id; may be {@code null}
     * @return the parsed {@link Long}, or {@code null} when blank
     * @throws NumberFormatException if a non-blank value is not numeric
     */
    private static Long parseMerchantId(String merchantId) {
        if (merchantId == null) {
            return null;
        }
        String trimmed = merchantId.trim();
        return trimmed.isEmpty() ? null : Long.valueOf(trimmed);
    }

    /**
     * Derives the list-screen display date ({@code MM/DD/YY}) from a stored
     * origination timestamp. The leading {@code YYYY-MM-DD} portion (first ten
     * characters) is reduced to its eight digits and validated/parsed/formatted
     * through {@link DateUtils}. A {@code null}, blank, too-short, or
     * non-calendar timestamp yields an empty string, so a malformed stored value
     * can never break the rendering of a page.
     *
     * @param origTs the 26-character origination timestamp; may be {@code null}
     * @return the date rendered {@code MM/DD/YY}, or {@code ""} when absent/invalid
     */
    private static String toListDate(String origTs) {
        if (origTs == null || origTs.isBlank() || origTs.length() < 10) {
            return "";
        }
        String digits = origTs.substring(0, 10).replace("-", "");
        if (!DateUtils.isValidCcyyMmDd(digits)) {
            return "";
        }
        return DateUtils.formatDateMmDdYy(DateUtils.parseCcyyMmDd(digits));
    }

    /**
     * Returns the leading ten characters ({@code YYYY-MM-DD}) of a stored
     * timestamp for the detail/echo date fields, reproducing the COBOL move of a
     * 26-character timestamp into an {@code X(10)} display field. A {@code null}
     * input yields an empty string; an input already ten characters or shorter is
     * returned unchanged.
     *
     * @param ts the timestamp to truncate; may be {@code null}
     * @return the leading ten characters, or {@code ""} when {@code null}
     */
    private static String leadingTen(String ts) {
        if (ts == null) {
            return "";
        }
        return (ts.length() <= 10) ? ts : ts.substring(0, 10);
    }
}
