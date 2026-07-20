/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

/**
 * Response DTO for the CardDemo <em>View Credit Card Detail</em> screen.
 *
 * <p>This immutable carrier is the Java re-platform of the output half of the
 * 3270 screen driven by BMS map {@code COCRDSL} (mapset {@code COCRDSLA}) and
 * online program {@code COCRDSLC} (CICS transaction {@code CCDL}). Each
 * component corresponds one-to-one with an output ({@code -O} suffix) field of
 * the BMS symbolic map {@code CCRDSLAO} defined in {@code app/cpy-bms/COCRDSL.CPY}
 * (relocated to {@code legacy/**}). Field names, maximum lengths, and character
 * types are preserved exactly to maintain the field-level UI contract of the
 * migrated screen (no terminal is rendered; the screen is expressed as a REST
 * request/response contract).</p>
 *
 * <p>The screen is a read-only card detail view: {@code CardViewController}
 * populates this response after {@code CardService} resolves the requested card.
 * All fields are plain, fixed-width character values in the legacy layout and are
 * therefore modeled as {@link String} (there are no monetary/decimal fields on
 * this screen, so no {@link java.math.BigDecimal} is required). Attribute-byte
 * fields ({@code -C}/{@code -P}/{@code -H}/{@code -V} colour, highlight, and
 * validation control bytes) and the card verification value (CVV) are
 * intentionally omitted: the CVV is not part of this screen, and the attribute
 * bytes only drove 3270 terminal rendering, which is out of scope for the REST
 * contract.</p>
 *
 * <p>Message fields ({@code infoMessage}, {@code errorMessage}) are plain
 * strings; the legacy error text traces to the shared message copybook
 * {@code app/cpy/CSMSG01Y.cpy}, but no shared constants type is introduced here.</p>
 *
 * <p>The declaration order below mirrors the physical top-to-bottom, left-to-right
 * order of the fields on the BMS map for maximum traceability to the legacy
 * screen.</p>
 *
 * @param transactionName the transaction identifier shown in the screen header;
 *                         legacy field {@code TRNNAMEO}, {@code PIC X(4)}
 * @param title01          the first application title line; legacy field
 *                         {@code TITLE01O}, {@code PIC X(40)}
 * @param currentDate      the current date shown in the header ({@code mm/dd/yy});
 *                         legacy field {@code CURDATEO}, {@code PIC X(8)}
 * @param programName      the originating program name shown in the header;
 *                         legacy field {@code PGMNAMEO}, {@code PIC X(8)}
 * @param title02          the second application title line; legacy field
 *                         {@code TITLE02O}, {@code PIC X(40)}
 * @param currentTime      the current time shown in the header ({@code hh:mm:ss});
 *                         legacy field {@code CURTIMEO}, {@code PIC X(8)}
 * @param accountId        the echoed 11-digit account identifier; legacy field
 *                         {@code ACCTSIDO}, {@code PIC X(11)}
 * @param cardId           the 16-digit card number; legacy field
 *                         {@code CARDSIDO}, {@code PIC X(16)}
 * @param cardName         the embossed name on the card; legacy field
 *                         {@code CRDNAMEO}, {@code PIC X(50)}
 * @param cardStatus       the card active status code ({@code Y}/{@code N});
 *                         legacy field {@code CRDSTCDO}, {@code PIC X(1)}
 * @param expiryMonth      the card expiry month; legacy field {@code EXPMONO},
 *                         {@code PIC X(2)}
 * @param expiryYear       the card expiry year; legacy field {@code EXPYEARO},
 *                         {@code PIC X(4)}
 * @param infoMessage      the informational message line; legacy field
 *                         {@code INFOMSGO}, {@code PIC X(40)}
 * @param errorMessage     the error message line; legacy field {@code ERRMSGO},
 *                         {@code PIC X(80)} on this screen
 * @param functionKeys     the PF-key legend line (display chrome retained for
 *                         parity, e.g. {@code ENTER=Search Cards  F3=Exit});
 *                         legacy field {@code FKEYSO}, {@code PIC X(75)}
 */
public record CardViewResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String accountId,
        String cardId,
        String cardName,
        String cardStatus,
        String expiryMonth,
        String expiryYear,
        String infoMessage,
        String errorMessage,
        String functionKeys) {
}
