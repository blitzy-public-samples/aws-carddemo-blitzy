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

import java.math.BigDecimal;

/**
 * Response DTO for the CardDemo <strong>Transaction Add</strong> screen.
 *
 * <p>This record is the Java re-platform of the BMS output symbolic map
 * {@code COTRN2AO} (BMS map {@code COTRN02}), which is driven by the online
 * program {@code COTRN02C} (CICS transaction {@code CT02}) and surfaced through
 * {@code TransactionAddController}. It carries the fields sent back to the client
 * after a Transaction Add submit, echoing the entered/derived transaction values
 * for redisplay together with the standard screen header and a single
 * error/informational message line.</p>
 *
 * <p>The Transaction Add screen follows a <em>confirm-then-commit</em> flow: the
 * operator enters the transaction detail, the values are echoed back with the
 * {@code confirm} flag prompt ("Y"/"N"), and the record is only persisted once the
 * operator confirms. Accordingly, every field on this response mirrors an entered
 * or derived input value so the screen can be faithfully redisplayed; any field may
 * be {@code null} or blank when it has not yet been populated.</p>
 *
 * <p><strong>Field contract.</strong> Field names, lengths, and types are preserved
 * one-for-one from {@code COTRN2AO} to maintain the 3270 screen contract (no feature
 * expansion). The 3270 attribute/length control sub-fields (the {@code *C},
 * {@code *P}, {@code *H}, {@code *V}, {@code *L}, {@code *F}, {@code *A} companions
 * of each map field) are intentionally omitted because there is no terminal
 * rendering in the re-platformed application. The monetary amount, held on the 3270
 * map as display text {@code PIC X(12)}, is modeled here as a
 * {@link java.math.BigDecimal} at scale 2 (never {@code double}/{@code float}) to
 * preserve exact decimal fidelity. The {@code errorMessage} line surfaces the common
 * message constants defined by copybook {@code CSMSG01Y} (for example the "Thank you
 * for using CardDemo..." and "Invalid key pressed..." texts) as well as
 * screen-specific validation messages.</p>
 *
 * @param transactionName the transaction/screen name shown in the header; maps to {@code TRNNAMEO} {@code PIC X(4)}
 * @param title01         the first title line of the screen header; maps to {@code TITLE01O} {@code PIC X(40)}
 * @param currentDate     the current date shown in the header (display format {@code mm/dd/yy}); maps to {@code CURDATEO} {@code PIC X(8)}
 * @param programName     the owning program name shown in the header; maps to {@code PGMNAMEO} {@code PIC X(8)}
 * @param title02         the second title line of the screen header; maps to {@code TITLE02O} {@code PIC X(40)}
 * @param currentTime     the current time shown in the header (display format {@code hh:mm:ss}); maps to {@code CURTIMEO} {@code PIC X(8)}
 * @param accountId       the echoed account identifier the transaction is being added for; maps to {@code ACTIDINO} {@code PIC X(11)}
 * @param cardNumber      the echoed card number the transaction is being added for; maps to {@code CARDNINO} {@code PIC X(16)}
 * @param typeCode        the echoed transaction type code; maps to {@code TTYPCDO} {@code PIC X(2)}
 * @param categoryCode    the echoed transaction category code; maps to {@code TCATCDO} {@code PIC X(4)}
 * @param source          the echoed transaction source; maps to {@code TRNSRCO} {@code PIC X(10)}
 * @param description     the echoed transaction description; maps to {@code TDESCO} {@code PIC X(60)}
 * @param amount          the echoed transaction amount as an exact scale-2 decimal (3270 display hint {@code -99999999.99}); maps to {@code TRNAMTO} {@code PIC X(12)}
 * @param originDate      the echoed transaction origination date (ISO-8601 {@code YYYY-MM-DD} display); maps to {@code TORIGDTO} {@code PIC X(10)}
 * @param processDate     the echoed transaction processing date (ISO-8601 {@code YYYY-MM-DD} display); maps to {@code TPROCDTO} {@code PIC X(10)}
 * @param merchantId      the echoed merchant identifier; maps to {@code MIDO} {@code PIC X(9)}
 * @param merchantName    the echoed merchant name; maps to {@code MNAMEO} {@code PIC X(30)}
 * @param merchantCity    the echoed merchant city; maps to {@code MCITYO} {@code PIC X(25)}
 * @param merchantZip     the echoed merchant ZIP/postal code; maps to {@code MZIPO} {@code PIC X(10)}
 * @param confirm         the echoed confirmation flag ("Y"/"N") for the confirm-then-commit prompt; maps to {@code CONFIRMO} {@code PIC X(1)}
 * @param errorMessage    the error/informational message line (from {@code CSMSG01Y} constants and validation); maps to {@code ERRMSGO} {@code PIC X(78)}
 */
public record TransactionAddResponse(
        String transactionName, // TRNNAMEO  PIC X(4)   header: transaction/screen name
        String title01,         // TITLE01O  PIC X(40)  header: title line 1
        String currentDate,     // CURDATEO  PIC X(8)   header: current date (mm/dd/yy)
        String programName,     // PGMNAMEO  PIC X(8)   header: owning program name
        String title02,         // TITLE02O  PIC X(40)  header: title line 2
        String currentTime,     // CURTIMEO  PIC X(8)   header: current time (hh:mm:ss)
        String accountId,       // ACTIDINO  PIC X(11)  echoed account id
        String cardNumber,      // CARDNINO  PIC X(16)  echoed card number
        String typeCode,        // TTYPCDO   PIC X(2)   echoed transaction type code
        String categoryCode,    // TCATCDO   PIC X(4)   echoed transaction category code
        String source,          // TRNSRCO   PIC X(10)  echoed transaction source
        String description,     // TDESCO    PIC X(60)  echoed transaction description
        BigDecimal amount,      // TRNAMTO   PIC X(12)  echoed amount -> BigDecimal scale 2 (never double/float)
        String originDate,      // TORIGDTO  PIC X(10)  echoed origination date (YYYY-MM-DD)
        String processDate,     // TPROCDTO  PIC X(10)  echoed processing date (YYYY-MM-DD)
        String merchantId,      // MIDO      PIC X(9)   echoed merchant id
        String merchantName,    // MNAMEO    PIC X(30)  echoed merchant name
        String merchantCity,    // MCITYO    PIC X(25)  echoed merchant city
        String merchantZip,     // MZIPO     PIC X(10)  echoed merchant ZIP
        String confirm,         // CONFIRMO  PIC X(1)   echoed confirm flag (Y/N)
        String errorMessage) {  // ERRMSGO   PIC X(78)  error/informational message line
}
