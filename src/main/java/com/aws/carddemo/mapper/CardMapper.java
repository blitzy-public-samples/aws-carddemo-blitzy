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

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardListResponse;
import com.aws.carddemo.dto.CardUpdateRequest;
import com.aws.carddemo.dto.CardUpdateResponse;
import com.aws.carddemo.dto.CardViewResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written, stateless mapper bridging the {@link Card} JPA entity and the
 * three CardDemo card-screen DTOs.
 *
 * <p>This component is the Java re-platform of the field-level presentation
 * mapping performed by the three online COBOL card programs (relocated under
 * {@code legacy/**} during migration):</p>
 * <ul>
 *   <li>{@code COCRDLIC} (transaction {@code CCLI}) &mdash; the card
 *       <strong>list</strong> screen, BMS map {@code COCRDLI}
 *       ({@code legacy/cpy-bms/COCRDLI.CPY});</li>
 *   <li>{@code COCRDSLC} (transaction {@code CCDL}) &mdash; the card
 *       <strong>view / detail</strong> screen, BMS map {@code COCRDSL}
 *       ({@code legacy/cpy-bms/COCRDSL.CPY});</li>
 *   <li>{@code COCRDUPC} (transaction {@code CCUP}) &mdash; the card
 *       <strong>update</strong> screen, BMS map {@code COCRDUP}
 *       ({@code legacy/cpy-bms/COCRDUP.CPY}).</li>
 * </ul>
 *
 * <p>The {@link Card} entity itself is the re-platform of the {@code CARD-RECORD}
 * copybook ({@code legacy/cpy/CVACT02Y.cpy}). Every assignment below is written
 * out explicitly (no MapStruct or other mapping library is used) so that the
 * one-to-one field correspondence between the COBOL record, the relational
 * entity, and the screen contract remains directly auditable for the mandated
 * 100&nbsp;% traceability.</p>
 *
 * <h2>Sensitive-data discipline (CVV)</h2>
 * <p>The {@link Card} entity carries a card verification value (the legacy
 * {@code CARD-CVV-CD} field). Persisting it at all is a demonstration
 * anti-pattern. <strong>None</strong> of the card response DTOs contains a card
 * verification field, and this mapper <strong>never</strong> reads that value on
 * any path and never logs it. On the update-in-place path the
 * {@link CardUpdateRequest} carries no card verification value, so the persisted
 * entity value is deliberately left untouched (preserved) &mdash; it is never
 * nulled out.</p>
 *
 * <h2>Header and message chrome</h2>
 * <p>The screen header identity fields ({@code transactionName}, {@code title01},
 * {@code title02}, {@code programName}), the informational / error message lines,
 * and the program-function-key legend are presentation policy owned by the
 * controller / service layer (the Java equivalents of the COBOL screen-title and
 * message {@code MOVE}s). They are therefore supplied to the response builders as
 * caller parameters rather than being hard-coded here, keeping this mapper a pure,
 * stateless data-shuffler. The header {@code currentDate} / {@code currentTime}
 * fields are derived from the supplied {@code now} timestamp through
 * {@link DateUtils} so that a single request renders a consistent, testable clock
 * value (mirroring the single {@code FUNCTION CURRENT-DATE} read per screen
 * build).</p>
 *
 * <h2>Expiration-date decomposition</h2>
 * <p>The entity stores the expiration date as the fixed-width text
 * {@code "YYYY-MM-DD"} ({@code CARD-EXPIRAION-DATE PIC X(10)}). The view and
 * update screens present the year, month, and (update only) day as separate
 * fields; this mapper splits the text with null-/length-safe substring helpers
 * and, on update, recomposes it while preserving the day component the screen
 * does not edit.</p>
 *
 * <p>The class is stateless and thread-safe; {@link DateUtils} is used only
 * through its {@code static} members.</p>
 */
@Component
public class CardMapper {

    /**
     * Length of the fixed-width {@code "YYYY-MM-DD"} expiration-date text
     * ({@code CARD-EXPIRAION-DATE PIC X(10)}).
     */
    private static final int EXPIRATION_LENGTH = 10;

    /**
     * Day component fallback used when the existing entity expiration date is
     * absent or too short to yield a day on the update-in-place path.
     */
    private static final String DEFAULT_DAY = "01";

    /** Width of the zero-padded expiry-month component ({@code EXPMON PIC X(2)}). */
    private static final int MONTH_WIDTH = 2;

    /** Width of the zero-padded expiry-year component ({@code EXPYEAR PIC X(4)}). */
    private static final int YEAR_WIDTH = 4;

    /**
     * Maps a single {@link Card} entity to one {@link CardListResponse.CardListRow}
     * of the card-list browse page (COBOL {@code COCRDLIC}, one screen occurrence).
     *
     * <p>Only the three display columns rendered on the 3270 list screen are
     * populated: the owning account number, the card number, and the active /
     * status indicator. The card verification value is deliberately never
     * referenced.</p>
     *
     * @param card the card entity to project onto a list row; must not be
     *             {@code null}
     * @return the populated list row
     * @throws NullPointerException if {@code card} is {@code null}
     */
    public CardListResponse.CardListRow toListRow(Card card) {
        Objects.requireNonNull(card, "card must not be null");
        return new CardListResponse.CardListRow(
                String.valueOf(card.getAcctId()),  // ACCTNOnO  <- CARD-ACCT-ID
                card.getCardNum(),                 // CRDNUMnO  <- CARD-NUM
                card.getCardActiveStatus());       // CRDSTSnO  <- CARD-ACTIVE-STATUS
        // NOTE: the sensitive card verification value is intentionally never read here.
    }

    /**
     * Builds the {@link CardListResponse} for the card-list screen
     * (COBOL {@code COCRDLIC}) from a page of card entities plus the caller-supplied
     * header, page indicator, and message chrome.
     *
     * <p>Each non-{@code null} entity is projected via {@link #toListRow(Card)}
     * into an <em>immutable</em> list; a {@code null} input list yields an empty
     * list. The header {@code currentDate} / {@code currentTime} fields are
     * formatted from {@code now} through {@link DateUtils}; the remaining header
     * and message fields are taken from the caller parameters.</p>
     *
     * @param cards           the page of card entities to display (up to seven
     *                        rows on the legacy screen); may be {@code null},
     *                        which is treated as an empty page
     * @param pageNumber      the current browse-page indicator ({@code PAGENOO},
     *                        {@code PIC X(3)})
     * @param infoMessage     the informational message line ({@code INFOMSGO},
     *                        {@code PIC X(45)})
     * @param errorMessage    the error message line ({@code ERRMSGO})
     * @param now             the timestamp used to render the header date / time;
     *                        when {@code null} the current system time is used
     * @param transactionName the header transaction identifier ({@code TRNNAMEO},
     *                        {@code PIC X(4)})
     * @param title01         the first header title line ({@code TITLE01O},
     *                        {@code PIC X(40)})
     * @param title02         the second header title line ({@code TITLE02O},
     *                        {@code PIC X(40)})
     * @param programName     the header program identifier ({@code PGMNAMEO},
     *                        {@code PIC X(8)})
     * @return the fully populated card-list response
     */
    public CardListResponse toListResponse(List<Card> cards,
                                           String pageNumber,
                                           String infoMessage,
                                           String errorMessage,
                                           LocalDateTime now,
                                           String transactionName,
                                           String title01,
                                           String title02,
                                           String programName) {
        LocalDateTime effectiveNow = resolveNow(now);
        List<CardListResponse.CardListRow> rows = (cards == null)
                ? List.of()
                : cards.stream()
                        .filter(Objects::nonNull)
                        .map(this::toListRow)
                        .toList(); // Stream.toList() is unmodifiable -> immutable page
        return new CardListResponse(
                transactionName,                                        // TRNNAMEO
                title01,                                                // TITLE01O
                title02,                                                // TITLE02O
                DateUtils.formatDateMmDdYy(effectiveNow.toLocalDate()), // CURDATEO
                programName,                                            // PGMNAMEO
                DateUtils.formatTimeHhMmSs(effectiveNow.toLocalTime()), // CURTIMEO
                pageNumber,                                             // PAGENOO
                rows,                                                   // ACCTNOnO/CRDNUMnO/CRDSTSnO
                infoMessage,                                            // INFOMSGO
                errorMessage);                                          // ERRMSGO
    }

    /**
     * Builds the {@link CardViewResponse} for the read-only card-detail screen
     * (COBOL {@code COCRDSLC}) from a resolved {@link Card} entity plus the
     * caller-supplied message and header chrome.
     *
     * <p>The account and card identifiers, embossed name, and status are copied
     * straight from the entity; the expiry month and year are decomposed from the
     * entity's {@code "YYYY-MM-DD"} expiration text with null-/length-safe
     * helpers. The card verification value is never referenced. The header
     * {@code currentDate} / {@code currentTime} are rendered from {@code now} via
     * {@link DateUtils}.</p>
     *
     * @param card            the resolved card entity; must not be {@code null}
     * @param infoMessage     the informational message line ({@code INFOMSGO},
     *                        {@code PIC X(40)})
     * @param errorMessage    the error message line ({@code ERRMSGO},
     *                        {@code PIC X(80)})
     * @param now             the timestamp used to render the header date / time;
     *                        when {@code null} the current system time is used
     * @param transactionName the header transaction identifier ({@code TRNNAMEO},
     *                        {@code PIC X(4)})
     * @param title01         the first header title line ({@code TITLE01O},
     *                        {@code PIC X(40)})
     * @param title02         the second header title line ({@code TITLE02O},
     *                        {@code PIC X(40)})
     * @param programName     the header program identifier ({@code PGMNAMEO},
     *                        {@code PIC X(8)})
     * @param functionKeys    the program-function-key legend ({@code FKEYSO},
     *                        {@code PIC X(75)}), supplied by the caller
     * @return the fully populated card-view response
     * @throws NullPointerException if {@code card} is {@code null}
     */
    public CardViewResponse toViewResponse(Card card,
                                           String infoMessage,
                                           String errorMessage,
                                           LocalDateTime now,
                                           String transactionName,
                                           String title01,
                                           String title02,
                                           String programName,
                                           String functionKeys) {
        Objects.requireNonNull(card, "card must not be null");
        LocalDateTime effectiveNow = resolveNow(now);
        String expiration = card.getCardExpirationDate();
        return new CardViewResponse(
                transactionName,                                        // TRNNAMEO
                title01,                                                // TITLE01O
                DateUtils.formatDateMmDdYy(effectiveNow.toLocalDate()), // CURDATEO
                programName,                                            // PGMNAMEO
                title02,                                                // TITLE02O
                DateUtils.formatTimeHhMmSs(effectiveNow.toLocalTime()), // CURTIMEO
                String.valueOf(card.getAcctId()),                       // ACCTSIDO <- CARD-ACCT-ID
                card.getCardNum(),                                      // CARDSIDO <- CARD-NUM
                card.getCardEmbossedName(),                             // CRDNAMEO <- CARD-EMBOSSED-NAME
                card.getCardActiveStatus(),                             // CRDSTCDO <- CARD-ACTIVE-STATUS
                expiryMonth(expiration),                                // EXPMONO  <- MM of expiration
                expiryYear(expiration),                                 // EXPYEARO <- YYYY of expiration
                infoMessage,                                            // INFOMSGO
                errorMessage,                                           // ERRMSGO
                functionKeys);                                          // FKEYSO
        // NOTE: the sensitive card verification value is intentionally never read here.
    }

    /**
     * Builds the {@link CardUpdateResponse} for the card-update screen
     * (COBOL {@code COCRDUPC}) from a resolved {@link Card} entity plus the
     * caller-supplied message and header chrome.
     *
     * <p>Identical to {@link #toViewResponse} in the fields it copies, with the
     * addition of the display-only {@code expiryDay} column decomposed from the
     * entity's expiration text, and the split program-function-key legend the
     * update screen presents. The card verification value is never referenced.</p>
     *
     * @param card                  the resolved card entity; must not be {@code null}
     * @param infoMessage           the informational message line
     *                              ({@code INFOMSGO}, {@code PIC X(40)})
     * @param errorMessage          the error message line ({@code ERRMSGO},
     *                              {@code PIC X(80)})
     * @param now                   the timestamp used to render the header date /
     *                              time; when {@code null} the current system time
     *                              is used
     * @param transactionName       the header transaction identifier
     *                              ({@code TRNNAMEO}, {@code PIC X(4)})
     * @param title01               the first header title line ({@code TITLE01O},
     *                              {@code PIC X(40)})
     * @param title02               the second header title line ({@code TITLE02O},
     *                              {@code PIC X(40)})
     * @param programName           the header program identifier
     *                              ({@code PGMNAMEO}, {@code PIC X(8)})
     * @param functionKeys          the first segment of the PF-key legend
     *                              ({@code FKEYSO}, {@code PIC X(21)})
     * @param functionKeysContinued the second segment of the split PF-key legend
     *                              ({@code FKEYSCO}, {@code PIC X(18)})
     * @return the fully populated card-update response
     * @throws NullPointerException if {@code card} is {@code null}
     */
    public CardUpdateResponse toUpdateResponse(Card card,
                                               String infoMessage,
                                               String errorMessage,
                                               LocalDateTime now,
                                               String transactionName,
                                               String title01,
                                               String title02,
                                               String programName,
                                               String functionKeys,
                                               String functionKeysContinued) {
        Objects.requireNonNull(card, "card must not be null");
        LocalDateTime effectiveNow = resolveNow(now);
        String expiration = card.getCardExpirationDate();
        return new CardUpdateResponse(
                transactionName,                                        // TRNNAMEO
                title01,                                                // TITLE01O
                DateUtils.formatDateMmDdYy(effectiveNow.toLocalDate()), // CURDATEO
                programName,                                            // PGMNAMEO
                title02,                                                // TITLE02O
                DateUtils.formatTimeHhMmSs(effectiveNow.toLocalTime()), // CURTIMEO
                String.valueOf(card.getAcctId()),                       // ACCTSIDO <- CARD-ACCT-ID
                card.getCardNum(),                                      // CARDSIDO <- CARD-NUM
                card.getCardEmbossedName(),                             // CRDNAMEO <- CARD-EMBOSSED-NAME
                card.getCardActiveStatus(),                             // CRDSTCDO <- CARD-ACTIVE-STATUS
                expiryMonth(expiration),                                // EXPMONO  <- MM of expiration
                expiryYear(expiration),                                 // EXPYEARO <- YYYY of expiration
                expiryDay(expiration),                                  // EXPDAYO  <- DD of expiration (display-only)
                infoMessage,                                            // INFOMSGO
                errorMessage,                                           // ERRMSGO
                functionKeys,                                           // FKEYSO
                functionKeysContinued);                                 // FKEYSCO
        // NOTE: the sensitive card verification value is intentionally never read here.
    }

    /**
     * Applies the editable fields of a {@link CardUpdateRequest} onto an existing,
     * managed {@link Card} entity in place, reproducing the COBOL {@code COCRDUPC}
     * READ-UPDATE-REWRITE cycle for the three operator-editable attributes.
     *
     * <p>The embossed name and active status are copied directly. The expiration
     * date is <em>recomposed</em> into the entity's {@code "YYYY-MM-DD"} text form
     * from the request's year and month (each zero-padded to its screen width)
     * while <strong>preserving the existing day</strong> component that the update
     * screen does not expose; when the existing value has no usable day the day
     * defaults to {@value #DEFAULT_DAY}. The calendar validity of the year / month
     * supplied by the operator is enforced upstream by the service layer
     * (the {@code COCRDUPC} edit paragraphs, backed by {@link DateUtils}) before
     * this method is invoked, so no re-validation or exception is raised here; the
     * recomposition only guarantees the {@code YYYY-MM-DD} <em>shape</em>.</p>
     *
     * <p><strong>Preserved fields.</strong> The sensitive card verification
     * value, the primary key ({@code cardNum}), the owning account
     * ({@code acctId}), and the optimistic-lock {@code version} are intentionally
     * left untouched &mdash; they are neither read nor written here, so the
     * persisted verification value and key / lock state survive the update
     * unchanged.</p>
     *
     * @param request the update request carrying the new name, status, month, and
     *                year; must not be {@code null}
     * @param entity  the managed card entity to mutate in place; must not be
     *                {@code null}
     * @throws NullPointerException if {@code request} or {@code entity} is
     *                              {@code null}
     */
    public void updateEntity(CardUpdateRequest request, Card entity) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(entity, "entity must not be null");

        // Editable card attributes (COCRDUPC edit-and-rewrite path).
        entity.setCardEmbossedName(request.cardName());    // CRDNAMEI -> CARD-EMBOSSED-NAME
        entity.setCardActiveStatus(request.cardStatus());  // CRDSTCDI -> CARD-ACTIVE-STATUS

        // Recompose "YYYY-MM-DD", preserving the day the update screen never edits.
        String preservedDay = expiryDay(entity.getCardExpirationDate());
        if (preservedDay.isEmpty()) {
            preservedDay = DEFAULT_DAY;
        }
        String year = leftPadZeros(request.expiryYear(), YEAR_WIDTH);   // EXPYEARI
        String month = leftPadZeros(request.expiryMonth(), MONTH_WIDTH); // EXPMONI
        entity.setCardExpirationDate(year + "-" + month + "-" + preservedDay);

        // The sensitive verification value, cardNum, acctId, and version are
        // deliberately NOT touched here so the persisted verification value,
        // primary key, foreign key, and optimistic-lock state are preserved by
        // the update-in-place.
    }

    // ------------------------------------------------------------------------
    // Private, stateless helpers
    // ------------------------------------------------------------------------

    /**
     * Returns the supplied timestamp, or the current system time when it is
     * {@code null}. Every card screen always renders a header date / time (the
     * legacy {@code FUNCTION CURRENT-DATE} population), so a missing value falls
     * back to "now" rather than failing.
     *
     * @param now the caller-supplied timestamp; may be {@code null}
     * @return {@code now} if non-{@code null}, otherwise {@link LocalDateTime#now()}
     */
    private static LocalDateTime resolveNow(LocalDateTime now) {
        return (now == null) ? LocalDateTime.now() : now;
    }

    /**
     * Extracts the four-character year (positions 0&ndash;3) from a
     * {@code "YYYY-MM-DD"} expiration string.
     *
     * @param expirationDate the entity expiration text; may be {@code null} or short
     * @return the year component, or {@code ""} when the source is {@code null} or
     *         too short
     */
    private static String expiryYear(String expirationDate) {
        return safeSubstring(expirationDate, 0, 4);
    }

    /**
     * Extracts the two-character month (positions 5&ndash;6) from a
     * {@code "YYYY-MM-DD"} expiration string.
     *
     * @param expirationDate the entity expiration text; may be {@code null} or short
     * @return the month component, or {@code ""} when the source is {@code null} or
     *         too short
     */
    private static String expiryMonth(String expirationDate) {
        return safeSubstring(expirationDate, 5, 7);
    }

    /**
     * Extracts the two-character day (positions 8&ndash;9) from a
     * {@code "YYYY-MM-DD"} expiration string.
     *
     * @param expirationDate the entity expiration text; may be {@code null} or short
     * @return the day component, or {@code ""} when the source is {@code null} or
     *         too short
     */
    private static String expiryDay(String expirationDate) {
        return safeSubstring(expirationDate, 8, EXPIRATION_LENGTH);
    }

    /**
     * Null-/length-safe substring: returns {@code source.substring(beginIndex,
     * endIndex)} when {@code source} is non-{@code null} and at least
     * {@code endIndex} characters long, otherwise the empty string. This performs a
     * faithful, lossless decomposition of the fixed-width expiration text without
     * ever throwing {@link IndexOutOfBoundsException}.
     *
     * @param source     the string to slice; may be {@code null}
     * @param beginIndex the (inclusive) start index
     * @param endIndex   the (exclusive) end index
     * @return the requested slice, or {@code ""} when the source is {@code null} or
     *         shorter than {@code endIndex}
     */
    private static String safeSubstring(String source, int beginIndex, int endIndex) {
        if (source == null || source.length() < endIndex) {
            return "";
        }
        return source.substring(beginIndex, endIndex);
    }

    /**
     * Left-pads a value with {@code '0'} up to {@code width} characters. A
     * {@code null} value is treated as empty; a value already at least
     * {@code width} characters long is returned unchanged (never truncated).
     * Reproduces the fixed-width, right-justified numeric presentation of the
     * COBOL expiry components.
     *
     * @param value the value to pad; may be {@code null}
     * @param width the target minimum width
     * @return the zero-left-padded value
     */
    private static String leftPadZeros(String value, int width) {
        String safe = (value == null) ? "" : value;
        if (safe.length() >= width) {
            return safe;
        }
        StringBuilder padded = new StringBuilder(width);
        for (int i = safe.length(); i < width; i++) {
            padded.append('0');
        }
        return padded.append(safe).toString();
    }
}
