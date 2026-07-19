      ************************************************************
      * Program     : COTRSVCC.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : REST/JSON API transaction list + detail service
      *               Read-only browse/filter; channel/container list
      ************************************************************
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
      * language governing permissions and limitations under the License
      ************************************************************
      *--------------------------------------------------------*
      * PROGRAM OVERVIEW
      *--------------------------------------------------------*
      * COTRSVCC is a read-only business-logic service for the additive
      * CardDemo REST/JSON API. It is LINKed by the router COAPIRTR in
      * one of two mutually exclusive transport modes:
      *   DETAIL - GET /carddemo/api/v1/transactions/{tranId}
      *            a single record returned over the COMMAREA payload.
      *   LIST   - GET /carddemo/api/v1/accounts/{acctId}/transactions
      *            a list returned over channel CDEMOAPILISTCH.
      * The mode is chosen by inspecting the current CICS channel; a
      * COMMAREA detail call is never mistaken for a list call.
      *
      * Because there is no card/account-keyed transaction index (the
      * only TRANSACT alternate index is keyed on the processed time),
      * the per-account list resolves the account to its card number(s)
      * through the CXACAIX cross-reference path and then browses the
      * base TRANSACT file, filtering records by card number in
      * application logic. Data access is strictly READ / STARTBR /
      * READNEXT / ENDBR - there is no WRITE, REWRITE or DELETE.
      *
      * Security: the card number is always masked to the last four
      * digits before it leaves this program and the card security
      * code is never referenced. RESP2 values are never returned to
      * the caller; failures surface only a generic message.
      *--------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COTRSVCC.
       AUTHOR.     AWS.
      *
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      *
       DATA DIVISION.
      *--------------------------------------------------------*
      *                     WORKING-STORAGE SECTION
      *--------------------------------------------------------*
       WORKING-STORAGE SECTION.
      *
       01  WS-VARIABLES.
           05  WS-PGMNAME           PIC X(08) VALUE 'COTRSVCC'.
           05  WS-TRANSACT-FILE     PIC X(08) VALUE 'TRANSACT'.
           05  WS-CXACAIX-FILE      PIC X(08) VALUE 'CXACAIX '.
           05  WS-LIST-CHANNEL      PIC X(16) VALUE 'CDEMOAPILISTCH'.
           05  WS-RESP-CD           PIC S9(09) COMP VALUE ZEROS.
           05  WS-REAS-CD           PIC S9(09) COMP VALUE ZEROS.
           05  WS-TRAN-KEY          PIC X(16) VALUE SPACES.
           05  WS-CHANNEL-NAME      PIC X(16) VALUE SPACES.
           05  WS-CXREF-KEY         PIC X(11) VALUE SPACES.
           05  WS-CONT-LEN          PIC S9(08) COMP VALUE ZEROS.
           05  WS-LIST-LEN          PIC S9(08) COMP VALUE ZEROS.
           05  WS-PAN-INPUT         PIC X(16) VALUE SPACES.
           05  WS-MASKED-PAN        PIC X(16) VALUE SPACES.
           05  WS-PAN-LAST4         PIC X(04) VALUE SPACES.
           05  WS-CARD-IDX          PIC S9(04) COMP VALUE ZEROS.
           05  WS-ENTRY-IDX         PIC S9(04) COMP VALUE ZEROS.
           05  WS-CARD-COUNT        PIC 9(03) VALUE ZEROS.
           05  WS-CARD-MATCH-FLG    PIC X(01) VALUE 'N'.
               88  WS-CARD-MATCHED            VALUE 'Y'.
               88  WS-CARD-NOT-MATCHED        VALUE 'N'.
           05  WS-XREF-DONE-FLG     PIC X(01) VALUE 'N'.
               88  WS-XREF-DONE               VALUE 'Y'.
               88  WS-XREF-NOT-DONE           VALUE 'N'.
           05  WS-BROWSE-DONE-FLG   PIC X(01) VALUE 'N'.
               88  WS-BROWSE-DONE             VALUE 'Y'.
               88  WS-BROWSE-NOT-DONE         VALUE 'N'.
           05  WS-BROWSE-ERR-FLG    PIC X(01) VALUE 'N'.
               88  WS-BROWSE-ERROR            VALUE 'Y'.
               88  WS-BROWSE-NO-ERROR         VALUE 'N'.
           05  WS-CARD-TABLE.
               10  WS-CT-CARD-NUM     PIC X(16) OCCURS 50 TIMES.
      *
      * Request container payload (router -> service, list mode)
       01  WS-LIST-REQUEST.
           05  WS-LR-ACCT-ID        PIC 9(11) VALUE ZEROS.
           05  WS-LR-ACCT-ID-X REDEFINES WS-LR-ACCT-ID
                                    PIC X(11).
      *
      * Status container payload (service -> router, list mode)
       01  WS-LIST-STATUS.
           05  WS-LS-HTTP-STATUS    PIC 9(03) VALUE 200.
           05  WS-LS-RETURN-CODE    PIC S9(04) VALUE ZEROS.
           05  WS-LS-ERR-CODE       PIC X(08) VALUE SPACES.
           05  WS-LS-ERR-MESSAGE    PIC X(120) VALUE SPACES.
      *
      * Transaction record layout (base TRANSACT / browse target)
           COPY CVTRA05Y.
      *
      * Card cross-reference layout (CXACAIX account path)
           COPY CVACT03Y.
      *
      * API transaction detail + list response contracts
           COPY COAPTRNY.
      *
      *--------------------------------------------------------*
      *                       LINKAGE SECTION
      *--------------------------------------------------------*
       LINKAGE SECTION.
      *
      * Detail-mode COMMAREA contract (absent in list mode)
           COPY COAPICOM.
      *
      *--------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *--------------------------------------------------------*
       PROCEDURE DIVISION USING API-COMMAREA.
      *
      *--------------------------------------------------------*
      * 0000-MAIN : mode detection and dispatch
      *--------------------------------------------------------*
       0000-MAIN.
      *
      * Determine the invocation transport. A list request arrives on
      * the CDEMOAPILISTCH channel; a detail request arrives with a
      * COMMAREA (EIBCALEN > 0). Anything else is ignored safely.
           EXEC CICS ASSIGN
                CHANNEL   (WS-CHANNEL-NAME)
                RESP      (WS-RESP-CD)
           END-EXEC
      *
           IF WS-CHANNEL-NAME = WS-LIST-CHANNEL
               PERFORM 3000-LIST-MODE
           ELSE
               IF EIBCALEN > 0
                  PERFORM 1000-DETAIL-MODE
               ELSE
                  PERFORM 1900-INVALID-CALL
               END-IF
           END-IF
      *
           EXEC CICS RETURN
           END-EXEC
           .
      *
      *--------------------------------------------------------*
      * 1000-DETAIL-MODE : single transaction over the COMMAREA
      *--------------------------------------------------------*
       1000-DETAIL-MODE.
      *
           MOVE API-REQ-TRAN-ID TO WS-TRAN-KEY
      *
           EXEC CICS READ
                DATASET   (WS-TRANSACT-FILE)
                INTO      (TRAN-RECORD)
                LENGTH    (LENGTH OF TRAN-RECORD)
                RIDFLD    (WS-TRAN-KEY)
                KEYLENGTH (LENGTH OF WS-TRAN-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  PERFORM 1100-MAP-DETAIL
                  SET API-HTTP-OK TO TRUE
                  MOVE ZERO TO API-RETURN-CODE
               WHEN DFHRESP(NOTFND)
                  SET API-HTTP-NOT-FOUND TO TRUE
                  MOVE +4 TO API-RETURN-CODE
                  MOVE 'NOTFOUND' TO API-ERR-CODE
                  MOVE 'Transaction not found'
                      TO API-ERR-MESSAGE
               WHEN OTHER
                  SET API-HTTP-SERVER-ERROR TO TRUE
                  MOVE -1 TO API-RETURN-CODE
                  MOVE 'APIERR' TO API-ERR-CODE
                  MOVE 'Internal server error'
                      TO API-ERR-MESSAGE
           END-EVALUATE
           .
      *
      *--------------------------------------------------------*
      * 1100-MAP-DETAIL : copy TRAN-RECORD into the detail contract.
      * Every clashing name is qualified; the PAN is masked and the
      * card security code is never referenced.
      *--------------------------------------------------------*
       1100-MAP-DETAIL.
      *
           MOVE TRAN-ID OF TRAN-RECORD
               TO TRAN-ID OF API-TRAN-RESPONSE
           MOVE TRAN-TYPE-CD OF TRAN-RECORD
               TO TRAN-TYPE-CD OF API-TRAN-RESPONSE
           MOVE TRAN-CAT-CD OF TRAN-RECORD
               TO TRAN-CAT-CD OF API-TRAN-RESPONSE
           MOVE TRAN-SOURCE OF TRAN-RECORD
               TO TRAN-SOURCE OF API-TRAN-RESPONSE
           MOVE TRAN-DESC OF TRAN-RECORD
               TO TRAN-DESC OF API-TRAN-RESPONSE
           MOVE TRAN-AMT OF TRAN-RECORD
               TO TRAN-AMT OF API-TRAN-RESPONSE
           MOVE TRAN-MERCHANT-ID OF TRAN-RECORD
               TO TRAN-MERCHANT-ID OF API-TRAN-RESPONSE
           MOVE TRAN-MERCHANT-NAME OF TRAN-RECORD
               TO TRAN-MERCHANT-NAME OF API-TRAN-RESPONSE
           MOVE TRAN-MERCHANT-CITY OF TRAN-RECORD
               TO TRAN-MERCHANT-CITY OF API-TRAN-RESPONSE
           MOVE TRAN-MERCHANT-ZIP OF TRAN-RECORD
               TO TRAN-MERCHANT-ZIP OF API-TRAN-RESPONSE
           MOVE TRAN-ORIG-TS OF TRAN-RECORD
               TO TRAN-ORIG-TS OF API-TRAN-RESPONSE
           MOVE TRAN-PROC-TS OF TRAN-RECORD
               TO TRAN-PROC-TS OF API-TRAN-RESPONSE
      *
           MOVE TRAN-CARD-NUM OF TRAN-RECORD TO WS-PAN-INPUT
           PERFORM 2100-MASK-PAN
           MOVE WS-MASKED-PAN
               TO TRAN-CARD-NUM-MASKED OF API-TRAN-RESPONSE
      *
      * Publish the populated detail contract into the COMMAREA payload
           MOVE API-TRAN-RESPONSE TO API-PAYLOAD
           .
      *
      *--------------------------------------------------------*
      * 2100-MASK-PAN : 12 asterisks + last four digits of a 16-char
      * PAN. Reused for the detail record and every list entry so the
      * masking is byte-consistent across the whole API layer.
      *--------------------------------------------------------*
       2100-MASK-PAN.
      *
           MOVE ALL '*' TO WS-MASKED-PAN
           MOVE WS-PAN-INPUT(13:4) TO WS-PAN-LAST4
           MOVE WS-PAN-LAST4 TO WS-MASKED-PAN(13:4)
           .
      *
      *--------------------------------------------------------*
      * 1900-INVALID-CALL : neither a list channel nor a COMMAREA was
      * supplied. No response area is addressable, so return quietly.
      *--------------------------------------------------------*
       1900-INVALID-CALL.
      *
           CONTINUE
           .
      *
      *--------------------------------------------------------*
      * 3000-LIST-MODE : per-account transaction list over the channel
      *--------------------------------------------------------*
       3000-LIST-MODE.
      *
      * Initialise the response and status blocks for a clean 200
           MOVE 200 TO WS-LS-HTTP-STATUS
           MOVE ZERO TO WS-LS-RETURN-CODE
           MOVE SPACES TO WS-LS-ERR-CODE
           MOVE SPACES TO WS-LS-ERR-MESSAGE
           MOVE ZERO TO TRAN-LIST-COUNT
           MOVE ZERO TO WS-CARD-COUNT
           SET TRAN-LIST-COMPLETE TO TRUE
           SET WS-BROWSE-NO-ERROR TO TRUE
      *
      * Read the requested account id from the input container
           MOVE LENGTH OF WS-LIST-REQUEST TO WS-CONT-LEN
           EXEC CICS GET
                CONTAINER ('TRANLISTREQ')
                CHANNEL   ('CDEMOAPILISTCH')
                INTO      (WS-LIST-REQUEST)
                FLENGTH   (WS-CONT-LEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  PERFORM 3100-RESOLVE-CARDS
                  IF WS-BROWSE-ERROR
                      PERFORM 3900-SET-LIST-ERROR
                  ELSE
                      IF WS-CARD-COUNT > ZERO
                          PERFORM 3200-BROWSE-TRANS
                          IF WS-BROWSE-ERROR
                              PERFORM 3900-SET-LIST-ERROR
                          END-IF
                      END-IF
                  END-IF
               WHEN OTHER
                  PERFORM 3900-SET-LIST-ERROR
           END-EVALUATE
      *
      * Return the list contract (only the used ODO length) and status
           MOVE LENGTH OF API-TRAN-LIST TO WS-LIST-LEN
           EXEC CICS PUT
                CONTAINER ('TRANLISTRSP')
                CHANNEL   ('CDEMOAPILISTCH')
                FROM      (API-TRAN-LIST)
                FLENGTH   (WS-LIST-LEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           MOVE LENGTH OF WS-LIST-STATUS TO WS-CONT-LEN
           EXEC CICS PUT
                CONTAINER ('TRANLISTSTA')
                CHANNEL   ('CDEMOAPILISTCH')
                FROM      (WS-LIST-STATUS)
                FLENGTH   (WS-CONT-LEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           .
      *
      *--------------------------------------------------------*
      * 3100-RESOLVE-CARDS : collect the account card number(s) by
      * browsing the CXACAIX account-path cross reference. A separate
      * RID field is used so READNEXT cannot clobber the target id.
      *--------------------------------------------------------*
       3100-RESOLVE-CARDS.
      *
           SET WS-XREF-NOT-DONE TO TRUE
           MOVE WS-LR-ACCT-ID TO WS-CXREF-KEY
      *
           EXEC CICS STARTBR
                DATASET   (WS-CXACAIX-FILE)
                RIDFLD    (WS-CXREF-KEY)
                KEYLENGTH (LENGTH OF WS-CXREF-KEY)
                GTEQ
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  PERFORM 3110-READ-XREF-NEXT
                      UNTIL WS-XREF-DONE
                  EXEC CICS ENDBR
                       DATASET   (WS-CXACAIX-FILE)
                       RESP      (WS-RESP-CD)
                       RESP2     (WS-REAS-CD)
                  END-EXEC
               WHEN DFHRESP(NOTFND)
                  CONTINUE
               WHEN DFHRESP(ENDFILE)
                  CONTINUE
               WHEN OTHER
                  SET WS-BROWSE-ERROR TO TRUE
           END-EVALUATE
           .
      *
      *--------------------------------------------------------*
      * 3110-READ-XREF-NEXT : one CXACAIX record. Stop when the record
      * account id no longer equals the requested account, on ENDFILE,
      * or on an unexpected error.
      *--------------------------------------------------------*
       3110-READ-XREF-NEXT.
      *
           EXEC CICS READNEXT
                DATASET   (WS-CXACAIX-FILE)
                INTO      (CARD-XREF-RECORD)
                LENGTH    (LENGTH OF CARD-XREF-RECORD)
                RIDFLD    (WS-CXREF-KEY)
                KEYLENGTH (LENGTH OF WS-CXREF-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  IF XREF-ACCT-ID OF CARD-XREF-RECORD
                      = WS-LR-ACCT-ID
                      PERFORM 3120-APPEND-CARD
                  ELSE
                      SET WS-XREF-DONE TO TRUE
                  END-IF
               WHEN DFHRESP(ENDFILE)
                  SET WS-XREF-DONE TO TRUE
               WHEN OTHER
                  SET WS-BROWSE-ERROR TO TRUE
                  SET WS-XREF-DONE TO TRUE
           END-EVALUATE
           .
      *
      *--------------------------------------------------------*
      * 3120-APPEND-CARD : add a card number to the working table,
      * capped at the table size (50).
      *--------------------------------------------------------*
       3120-APPEND-CARD.
      *
           IF WS-CARD-COUNT < 50
               ADD 1 TO WS-CARD-COUNT
               MOVE XREF-CARD-NUM OF CARD-XREF-RECORD
                  TO WS-CT-CARD-NUM (WS-CARD-COUNT)
           ELSE
               SET WS-XREF-DONE TO TRUE
           END-IF
           .
      *
      *--------------------------------------------------------*
      * 3200-BROWSE-TRANS : browse the base TRANSACT KSDS from the
      * start and hand every record to the filter. There is no keyed
      * path from account to transaction, so a full browse is required.
      *--------------------------------------------------------*
       3200-BROWSE-TRANS.
      *
           SET WS-BROWSE-NOT-DONE TO TRUE
           MOVE LOW-VALUES TO WS-TRAN-KEY
      *
           EXEC CICS STARTBR
                DATASET   (WS-TRANSACT-FILE)
                RIDFLD    (WS-TRAN-KEY)
                KEYLENGTH (LENGTH OF WS-TRAN-KEY)
                GTEQ
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  PERFORM 3210-READ-TRAN-NEXT
                      UNTIL WS-BROWSE-DONE
                  EXEC CICS ENDBR
                       DATASET   (WS-TRANSACT-FILE)
                       RESP      (WS-RESP-CD)
                       RESP2     (WS-REAS-CD)
                  END-EXEC
               WHEN DFHRESP(NOTFND)
                  CONTINUE
               WHEN DFHRESP(ENDFILE)
                  CONTINUE
               WHEN OTHER
                  SET WS-BROWSE-ERROR TO TRUE
           END-EVALUATE
           .
      *
      *--------------------------------------------------------*
      * 3210-READ-TRAN-NEXT : one browsed transaction. ENDFILE ends
      * the browse; an unexpected error ends it and flags a 500.
      *--------------------------------------------------------*
       3210-READ-TRAN-NEXT.
      *
           EXEC CICS READNEXT
                DATASET   (WS-TRANSACT-FILE)
                INTO      (TRAN-RECORD)
                LENGTH    (LENGTH OF TRAN-RECORD)
                RIDFLD    (WS-TRAN-KEY)
                KEYLENGTH (LENGTH OF WS-TRAN-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  PERFORM 3220-FILTER-AND-ADD
               WHEN DFHRESP(ENDFILE)
                  SET WS-BROWSE-DONE TO TRUE
               WHEN OTHER
                  SET WS-BROWSE-ERROR TO TRUE
                  SET WS-BROWSE-DONE TO TRUE
           END-EVALUATE
           .
      *
      *--------------------------------------------------------*
      * 3220-FILTER-AND-ADD : keep the record only when its card number
      * belongs to the requested account. The list is capped at 500
      * entries; a 501st match flags truncation and stops the browse.
      *--------------------------------------------------------*
       3220-FILTER-AND-ADD.
      *
           SET WS-CARD-NOT-MATCHED TO TRUE
           PERFORM VARYING WS-CARD-IDX FROM 1 BY 1
                  UNTIL WS-CARD-IDX > WS-CARD-COUNT
                      OR WS-CARD-MATCHED
               IF TRAN-CARD-NUM OF TRAN-RECORD
                  = WS-CT-CARD-NUM (WS-CARD-IDX)
                  SET WS-CARD-MATCHED TO TRUE
               END-IF
           END-PERFORM
      *
           IF WS-CARD-MATCHED
               IF TRAN-LIST-COUNT >= 500
                  SET TRAN-LIST-WAS-TRUNCATED TO TRUE
                  SET WS-BROWSE-DONE TO TRUE
               ELSE
                  ADD 1 TO TRAN-LIST-COUNT
                  MOVE TRAN-LIST-COUNT TO WS-ENTRY-IDX
                  PERFORM 3230-ADD-ENTRY
               END-IF
           END-IF
           .
      *
      *--------------------------------------------------------*
      * 3230-ADD-ENTRY : copy the browsed record into list entry
      * WS-ENTRY-IDX. All clashing names are qualified; the PAN is
      * masked and TRAN-AMT is carried as-is (S9(09)V99, scale 2).
      *--------------------------------------------------------*
       3230-ADD-ENTRY.
      *
           MOVE TRAN-ID OF TRAN-RECORD
               TO TRNL-ID (WS-ENTRY-IDX)
           MOVE TRAN-TYPE-CD OF TRAN-RECORD
               TO TRNL-TYPE-CD (WS-ENTRY-IDX)
           MOVE TRAN-CAT-CD OF TRAN-RECORD
               TO TRNL-CAT-CD (WS-ENTRY-IDX)
           MOVE TRAN-SOURCE OF TRAN-RECORD
               TO TRNL-SOURCE (WS-ENTRY-IDX)
           MOVE TRAN-DESC OF TRAN-RECORD
               TO TRNL-DESC (WS-ENTRY-IDX)
           MOVE TRAN-AMT OF TRAN-RECORD
               TO TRNL-AMT (WS-ENTRY-IDX)
           MOVE TRAN-MERCHANT-ID OF TRAN-RECORD
               TO TRNL-MERCHANT-ID (WS-ENTRY-IDX)
           MOVE TRAN-MERCHANT-NAME OF TRAN-RECORD
               TO TRNL-MERCHANT-NAME (WS-ENTRY-IDX)
           MOVE TRAN-MERCHANT-CITY OF TRAN-RECORD
               TO TRNL-MERCHANT-CITY (WS-ENTRY-IDX)
           MOVE TRAN-MERCHANT-ZIP OF TRAN-RECORD
               TO TRNL-MERCHANT-ZIP (WS-ENTRY-IDX)
           MOVE TRAN-ORIG-TS OF TRAN-RECORD
               TO TRNL-ORIG-TS (WS-ENTRY-IDX)
           MOVE TRAN-PROC-TS OF TRAN-RECORD
               TO TRNL-PROC-TS (WS-ENTRY-IDX)
      *
           MOVE TRAN-CARD-NUM OF TRAN-RECORD TO WS-PAN-INPUT
           PERFORM 2100-MASK-PAN
           MOVE WS-MASKED-PAN
               TO TRNL-CARD-NUM-MASKED (WS-ENTRY-IDX)
           .
      *
      *--------------------------------------------------------*
      * 3900-SET-LIST-ERROR : uniform 500 for the list transport. The
      * list is emptied and no RESP2 value is exposed.
      *--------------------------------------------------------*
       3900-SET-LIST-ERROR.
      *
           MOVE 500 TO WS-LS-HTTP-STATUS
           MOVE -1 TO WS-LS-RETURN-CODE
           MOVE 'APIERR' TO WS-LS-ERR-CODE
           MOVE 'Internal server error' TO WS-LS-ERR-MESSAGE
           MOVE ZERO TO TRAN-LIST-COUNT
           SET TRAN-LIST-COMPLETE TO TRUE
           .
      *
      * Ver: CardDemo_v1.0 REST/JSON API layer - COTRSVCC
      *
