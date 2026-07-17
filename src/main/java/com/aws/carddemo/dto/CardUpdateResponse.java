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
package com.aws.carddemo.dto;

/**
 * Response DTO for the CardDemo <em>Card Update</em> screen.
 *
 * <p>This immutable record is the Java re-expression of the BMS symbolic output
 * map {@code CCRDUPAO} (BMS map {@code COCRDUP}, mapset {@code COCRDUP}), which is
 * emitted by the online CICS program {@code COCRDUPC} (transaction {@code CCUP})
 * and surfaced by {@code CardUpdateController}. It carries the echoed card detail
 * together with the informational and error message lines and the split
 * function-key legend that the 3270 screen presented to the operator.
 *
 * <p>Per the migration contract (AAP section 0.5.3 / section 0.9.2) every field
 * preserves the <strong>name, maximum length, and type</strong> of its
 * originating COBOL field so the field-level screen contract is retained exactly.
 * Each component maps one-to-one to an {@code -O} (output) field of the
 * {@code CCRDUPAO} group; the field's original {@code PIC X(n)} clause defines the
 * documented maximum character length. Values are surfaced as {@link String}
 * because the 3270 map transmitted every field as display characters.
 *
 * <p>Deliberately excluded, in line with the migration scope:
 * <ul>
 *   <li>the BMS attribute-byte and length sub-fields (the {@code -C}, {@code -P},
 *       {@code -H}, {@code -V} and {@code -L} siblings of each field): there is no
 *       terminal rendering in the Java target (AAP section 0.3.3); and</li>
 *   <li>any card verification value (CVV): it is neither part of this screen's
 *       output map nor ever echoed to the client.</li>
 * </ul>
 *
 * <p>The {@code errorMessage} line is the 80-character message slot populated from
 * the common-message constants defined in the legacy copybook {@code CSMSG01Y}
 * (for example the "Invalid key pressed" and "Thank you for using CardDemo"
 * texts) as well as the screen-specific validation messages produced by
 * {@code COCRDUPC}. This record carries no business logic and performs no
 * validation; it is a pure data carrier populated by the mapping layer.
 *
 * @param transactionName       transaction identifier shown in the header;
 *                              COBOL {@code TRNNAMEO}, {@code PIC X(4)}
 * @param title01               first application title line; COBOL
 *                              {@code TITLE01O}, {@code PIC X(40)}
 * @param currentDate           current date shown in the header (formatted
 *                              {@code mm/dd/yy}); COBOL {@code CURDATEO},
 *                              {@code PIC X(8)}
 * @param programName           program identifier shown in the header; COBOL
 *                              {@code PGMNAMEO}, {@code PIC X(8)}
 * @param title02               second application title line; COBOL
 *                              {@code TITLE02O}, {@code PIC X(40)}
 * @param currentTime           current time shown in the header (formatted
 *                              {@code hh:mm:ss}); COBOL {@code CURTIMEO},
 *                              {@code PIC X(8)}
 * @param accountId             echoed 11-digit account number; COBOL
 *                              {@code ACCTSIDO}, {@code PIC X(11)}
 * @param cardId                echoed 16-digit card number; COBOL
 *                              {@code CARDSIDO}, {@code PIC X(16)}
 * @param cardName              embossed name on the card; COBOL
 *                              {@code CRDNAMEO}, {@code PIC X(50)}
 * @param cardStatus            card active flag ({@code Y}/{@code N}); COBOL
 *                              {@code CRDSTCDO}, {@code PIC X(1)}
 * @param expiryMonth           card expiry month; COBOL {@code EXPMONO},
 *                              {@code PIC X(2)}
 * @param expiryYear            card expiry year; COBOL {@code EXPYEARO},
 *                              {@code PIC X(4)}
 * @param expiryDay             display-only card expiry day; COBOL
 *                              {@code EXPDAYO}, {@code PIC X(2)}. Present on the
 *                              Update screen (rendered dark / protected) although
 *                              not on the View screen.
 * @param infoMessage           informational message line; COBOL
 *                              {@code INFOMSGO}, {@code PIC X(40)}
 * @param errorMessage          error message line (80 characters on this screen);
 *                              COBOL {@code ERRMSGO}, {@code PIC X(80)};
 *                              constants trace to copybook {@code CSMSG01Y}
 * @param functionKeys          first segment of the PF-key legend (for example
 *                              {@code ENTER=Process F3=Exit}); COBOL
 *                              {@code FKEYSO}, {@code PIC X(21)}
 * @param functionKeysContinued second segment of the split PF-key legend (for
 *                              example {@code F5=Save F12=Cancel}); COBOL
 *                              {@code FKEYSCO}, {@code PIC X(18)}
 */
public record CardUpdateResponse(
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
        String expiryDay,
        String infoMessage,
        String errorMessage,
        String functionKeys,
        String functionKeysContinued) {
}
