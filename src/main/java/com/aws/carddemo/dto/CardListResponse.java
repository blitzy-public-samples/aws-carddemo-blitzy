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

import java.util.List;

/**
 * Response DTO for the CardDemo <strong>Card List</strong> screen.
 *
 * <p>This immutable record is the Java re-expression of the BMS map
 * {@code COCRDLI} output symbolic group {@code CCRDLIAO} (legacy source
 * {@code app/cpy-bms/COCRDLI.CPY} and {@code app/bms/COCRDLI.bms}), as driven by
 * the online program {@code COCRDLIC} and surfaced by {@code CardListController}.
 * It carries a single browse page of up to seven credit-card rows together with
 * the screen header, the current page indicator, and the informational / error
 * message lines.</p>
 *
 * <p>Field names, lengths, and types are preserved from the originating 3270
 * screen contract (behavioural-parity requirement). Only <em>display data</em>
 * is modelled: the 3270 presentation attribute bytes (the {@code C}/{@code P}/
 * {@code H}/{@code V} suffixed sub-fields of the symbolic map) are intentionally
 * omitted because the migrated application performs no terminal rendering.
 * The account and card search-filter echo fields ({@code ACCTSIDO} and
 * {@code CARDSIDO}) and the program-function key legend belong to the
 * request/action contract ({@code CardListRequest}) and are therefore not part
 * of this response.</p>
 *
 * <p>All message text is carried as plain {@link String} values; the legacy
 * message constants (for example those defined in {@code app/cpy/CSMSG01Y.cpy})
 * are supplied by the service / controller layer rather than embedded here.</p>
 *
 * @param transactionName the four-character transaction identifier shown in the
 *                        header; maps to {@code TRNNAMEO} (COBOL {@code PIC X(4)})
 * @param title01         the first title line; maps to {@code TITLE01O}
 *                        (COBOL {@code PIC X(40)})
 * @param title02         the second title line; maps to {@code TITLE02O}
 *                        (COBOL {@code PIC X(40)})
 * @param currentDate     the formatted current date shown in the header; maps to
 *                        {@code CURDATEO} (COBOL {@code PIC X(8)}, {@code mm/dd/yy})
 * @param programName     the eight-character program identifier shown in the
 *                        header; maps to {@code PGMNAMEO} (COBOL {@code PIC X(8)})
 * @param currentTime     the formatted current time shown in the header; maps to
 *                        {@code CURTIMEO} (COBOL {@code PIC X(8)}, {@code hh:mm:ss})
 * @param pageNumber      the current browse page indicator; maps to
 *                        {@code PAGENOO} (COBOL {@code PIC X(3)})
 * @param cards           the ordered list of up to seven card rows displayed on
 *                        the page; each row maps to the {@code ACCTNOnO},
 *                        {@code CRDNUMnO}, and {@code CRDSTSnO} occurrences
 *                        ({@code n = 1..7}) of {@code CCRDLIAO}
 * @param infoMessage     the informational message line; maps to {@code INFOMSGO}
 *                        (COBOL {@code PIC X(45)})
 * @param errorMessage    the error message line; maps to {@code ERRMSGO}
 *                        (COBOL {@code PIC X(78)})
 */
public record CardListResponse(
        String transactionName,   // TRNNAMEO  PIC X(4)
        String title01,           // TITLE01O  PIC X(40)
        String title02,           // TITLE02O  PIC X(40)
        String currentDate,       // CURDATEO  PIC X(8)
        String programName,       // PGMNAMEO  PIC X(8)
        String currentTime,       // CURTIMEO  PIC X(8)
        String pageNumber,        // PAGENOO   PIC X(3)
        List<CardListRow> cards,  // ACCTNOnO / CRDNUMnO / CRDSTSnO, n = 1..7
        String infoMessage,       // INFOMSGO  PIC X(45)
        String errorMessage       // ERRMSGO   PIC X(78)
) {

    /**
     * A single credit-card row within the {@link CardListResponse} browse page.
     *
     * <p>Each row corresponds to one {@code CRDSELn} occurrence of the
     * {@code CCRDLIAO} output group and exposes only the three display columns
     * rendered on the screen: the owning account number, the card number, and
     * the one-character active / status indicator.</p>
     *
     * <p><strong>Deliberate omissions (not oversights):</strong></p>
     * <ul>
     *   <li>The per-row {@code CRDSTPn} card stop / protect indicator present on
     *       rows two through seven is a 3270 attribute-style flag (defined
     *       {@code ATTRB=(ASKIP,DRK,FSET)} in {@code COCRDLI.bms}), not display
     *       data, and is therefore excluded from this data contract.</li>
     *   <li>The {@code CRDSELn} selection field is an input / action element of
     *       the request contract and is not echoed as response display data.</li>
     *   <li>There is no card verification value (CVV) on this screen; none is
     *       added here.</li>
     * </ul>
     *
     * @param accountNumber the eleven-character owning account number; maps to
     *                      {@code ACCTNOnO} (COBOL {@code PIC X(11)})
     * @param cardNumber    the sixteen-character card number; maps to
     *                      {@code CRDNUMnO} (COBOL {@code PIC X(16)})
     * @param cardStatus    the one-character active / status indicator; maps to
     *                      {@code CRDSTSnO} (COBOL {@code PIC X(1)})
     */
    public record CardListRow(
            String accountNumber,  // ACCTNOnO  PIC X(11)
            String cardNumber,     // CRDNUMnO  PIC X(16)
            String cardStatus      // CRDSTSnO  PIC X(1)
    ) {
    }
}
