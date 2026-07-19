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
      * application logic. This costs O(records x cards) per request
      * and is bounded by WS-MAX-SCAN; a keyed index is a known backlog
      * item (see docs/decision-log.md). Data access is strictly READ /
      * STARTBR / READNEXT / ENDBR - there is no WRITE, REWRITE or
      * DELETE.
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
           05  WS-ACCTDAT-FILE      PIC X(08) VALUE 'ACCTDAT '.
           05  WS-LIST-CHANNEL      PIC X(16) VALUE 'CDEMOAPILISTCH'.
           05  WS-RESP-CD           PIC S9(09) COMP VALUE ZEROS.
           05  WS-REAS-CD           PIC S9(09) COMP VALUE ZEROS.
           05  WS-TRAN-KEY          PIC X(16) VALUE SPACES.
           05  WS-CHANNEL-NAME      PIC X(16) VALUE SPACES.
           05  WS-CXREF-KEY         PIC X(11) VALUE SPACES.
           05  WS-ACCT-KEY          PIC X(11) VALUE SPACES.
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
      *
      * Named capacity / guard limits (no magic numbers). WS-MAX-CARDS
      * MUST equal the WS-CT-CARD-NUM OCCURS count below. WS-MAX-TRANS
      * MUST equal the TRAN-LIST-ENTRY OCCURS max in COAPTRNY so a full
      * list fits the JSON buffer. Reaching WS-MAX-TRANS discloses a
      * truncated list (truncated='Y', HTTP 200); a WS-MAX-CARDS or
      * WS-MAX-SCAN breach surfaces a deterministic 500.
           05  WS-MAX-CARDS         PIC 9(03) VALUE 50.
           05  WS-MAX-TRANS         PIC 9(04) VALUE 50.
           05  WS-MAX-SCAN          PIC 9(09) VALUE 1000000.
           05  WS-SCAN-COUNT        PIC 9(09) VALUE ZEROS.
           05  WS-PUT-ERR-FLG       PIC X(01) VALUE 'N'.
               88  WS-PUT-ERROR               VALUE 'Y'.
               88  WS-PUT-NO-ERROR            VALUE 'N'.
           05  WS-ACCT-CHK-FLG      PIC X(01) VALUE 'N'.
               88  WS-ACCT-OK                 VALUE 'Y'.
               88  WS-ACCT-NOT-OK             VALUE 'N'.
           05  WS-CARD-TABLE.
               10  WS-CT-CARD-NUM     PIC X(16) OCCURS 50 TIMES.
      *
      * The list request (TRLR-ACCT-ID) and status (TRLS-*) container
      * layouts come from COAPTRNY - shared, not redefined here - so
      * this producer and the COAPIRTR consumer bind one identical
      * copy of each contract (see F02 reconciliation).
      *
      * Transaction record layout (base TRANSACT / browse target)
           COPY CVTRA05Y.
      *
      * Card cross-reference layout (CXACAIX account path)
           COPY CVACT03Y.
      *
      * Account record layout (ACCTDAT read-only existence proof, list)
           COPY CVACT01Y.
      *
      * API transaction detail + list response contracts
           COPY COAPTRNY.
      *
      *--------------------------------------------------------*
      *                       LINKAGE SECTION
      *--------------------------------------------------------*
       LINKAGE SECTION.
      *
      * DFHCOMMAREA is the CICS-addressed anchor for the COMMAREA the
      * router passes on EXEC CICS LINK for a DETAIL call. In LIST mode
      * the service is LINKed with a channel and NO commarea, so the
      * commarea is never addressed. COPY COAPICOM brings in
      * 01 API-COMMAREA; detail mode overlays it onto DFHCOMMAREA only
      * after EIBCALEN proves the full 1334-byte contract is present.
       01  DFHCOMMAREA                 PIC X(01).
      *
           COPY COAPICOM.
      *
      *--------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *--------------------------------------------------------*
       PROCEDURE DIVISION.
      *
      *--------------------------------------------------------*
      * 0000-MAIN : mode detection and dispatch
      *--------------------------------------------------------*
       0000-MAIN.
      *
      * Determine the invocation transport. A LIST request arrives on
      * the CDEMOAPILISTCH channel; a DETAIL request arrives with the
      * full API-COMMAREA and no channel. The ASSIGN response is
      * checked (F51): only a NORMAL response lets the returned channel
      * name be trusted, so a failed ASSIGN can never be mistaken for a
      * list call.
           MOVE SPACES TO WS-CHANNEL-NAME
           EXEC CICS ASSIGN
                CHANNEL   (WS-CHANNEL-NAME)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           IF WS-RESP-CD = DFHRESP(NORMAL)
               AND WS-CHANNEL-NAME = WS-LIST-CHANNEL
               PERFORM 3000-LIST-MODE
           ELSE
      *
      * DETAIL mode requires the exact 1334-byte contract before the
      * commarea is made addressable; any other length is rejected
      * without ever dereferencing DFHCOMMAREA (F50).
               IF EIBCALEN = LENGTH OF API-COMMAREA
                  SET ADDRESS OF API-COMMAREA TO ADDRESS OF DFHCOMMAREA
                  PERFORM 1000-DETAIL-MODE
               ELSE
                  PERFORM 1900-INVALID-CALL
               END-IF
           END-IF
      *
      * Scrub every work area that held a full card number or a raw
      * VSAM record before returning to the caller (F60).
           PERFORM 9000-SCRUB-WORKAREAS
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
      * F56: begin from a clean, known output state so a 400 / 404 / 500
      * can never inherit a previous payload or error text.
           PERFORM 1050-INIT-DETAIL-OUT
      *
      * F52: the transaction id must be exactly sixteen numeric digits
      * before any file access; a malformed id is a 400, not a lookup.
           IF API-REQ-TRAN-ID IS NOT NUMERIC
               SET API-HTTP-BAD-REQUEST TO TRUE
               MOVE +4 TO API-RETURN-CODE
               SET API-ERR-BAD-REQUEST TO TRUE
               MOVE 'Transaction id must be 16 numeric digits'
                   TO API-ERR-MESSAGE
           ELSE
               MOVE API-REQ-TRAN-ID TO WS-TRAN-KEY
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
                      SET API-ERR-NOT-FOUND TO TRUE
                      MOVE 'Transaction not found'
                          TO API-ERR-MESSAGE
                   WHEN OTHER
                      SET API-HTTP-SERVER-ERROR TO TRUE
                      MOVE -1 TO API-RETURN-CODE
                      SET API-ERR-SERVER-ERROR TO TRUE
                      MOVE 'Internal server error'
                          TO API-ERR-MESSAGE
               END-EVALUATE
           END-IF
           .
      *
      *--------------------------------------------------------*
      * 1050-INIT-DETAIL-OUT : reset the COMMAREA response status,
      * error envelope and payload to a clean state at the start of
      * every detail call. The router-owned request id is deliberately
      * left untouched (F56, F36).
      *--------------------------------------------------------*
       1050-INIT-DETAIL-OUT.
      *
           SET API-HTTP-OK TO TRUE
           MOVE ZERO TO API-RETURN-CODE
           MOVE SPACES TO API-ERR-CODE
           MOVE SPACES TO API-ERR-MESSAGE
           MOVE SPACES TO API-PAYLOAD
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
      * Always start fully masked. Only when the source PAN is exactly
      * sixteen numeric digits are the last four revealed; any other
      * shape (spaces, low-values, short or non-numeric data) stays
      * fully masked so a malformed value can never leak digits (F44).
           MOVE ALL '*' TO WS-MASKED-PAN
           IF WS-PAN-INPUT IS NUMERIC
               MOVE WS-PAN-INPUT(13:4) TO WS-PAN-LAST4
               MOVE WS-PAN-LAST4 TO WS-MASKED-PAN(13:4)
           ELSE
               MOVE SPACES TO WS-PAN-LAST4
           END-IF
           .
      *
      *--------------------------------------------------------*
      * 1900-INVALID-CALL : neither the list channel nor a full detail
      * COMMAREA was supplied, so no response area is addressable. A
      * silent return would hide a broken router contract; instead the
      * task ends deterministically with a controlled ABEND so the
      * mis-call is visible and auditable (F59). No dump is taken and
      * no storage is dereferenced.
      *--------------------------------------------------------*
       1900-INVALID-CALL.
      *
           EXEC CICS ABEND
                ABCODE ('TSNC')
                NODUMP
           END-EXEC
           .
      *
      *--------------------------------------------------------*
      * 3000-LIST-MODE : per-account transaction list over the channel
      *--------------------------------------------------------*
       3000-LIST-MODE.
      *
      * Initialise the response and status blocks for a clean 200 and
      * reset every guard counter / flag used by the browse.
           MOVE 200 TO TRLS-HTTP-STATUS
           MOVE ZERO TO TRLS-RETURN-CODE
           MOVE SPACES TO TRLS-ERR-CODE
           MOVE SPACES TO TRLS-ERR-MESSAGE
           MOVE ZERO TO TRAN-LIST-COUNT
           MOVE ZERO TO TRAN-LIST-ACCT-ID
           MOVE ZERO TO WS-CARD-COUNT
           MOVE ZERO TO WS-SCAN-COUNT
           SET TRAN-LIST-COMPLETE TO TRUE
           SET WS-BROWSE-NO-ERROR TO TRUE
           SET WS-PUT-NO-ERROR TO TRUE
           MOVE ZEROS TO TRLR-ACCT-ID
      *
      * Read the requested account id from the input container. The
      * request container is entirely the caller's responsibility, so a
      * missing, wrong-length or non-numeric account id is a 400 - the
      * FLENGTH must equal the 11-byte contract and the id must be
      * numeric before any file access (F52).
           MOVE LENGTH OF API-TRAN-LIST-REQUEST TO WS-CONT-LEN
           EXEC CICS GET
                CONTAINER ('TRANLISTREQ')
                CHANNEL   ('CDEMOAPILISTCH')
                INTO      (API-TRAN-LIST-REQUEST)
                FLENGTH   (WS-CONT-LEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               PERFORM 3910-SET-LIST-BADREQ
           ELSE
               IF WS-CONT-LEN NOT = LENGTH OF API-TRAN-LIST-REQUEST
                   OR TRLR-ACCT-ID IS NOT NUMERIC
                   PERFORM 3910-SET-LIST-BADREQ
               ELSE
      *
      * Echo the resolved account id into the list contract (F06), then
      * prove the account exists before listing anything (F53).
                   MOVE TRLR-ACCT-ID TO TRAN-LIST-ACCT-ID
                   PERFORM 3050-CHECK-ACCT
                   IF WS-ACCT-OK
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
                   END-IF
               END-IF
           END-IF
      *
      * Return the list contract (only the used ODO length). A failed
      * response PUT is downgraded to a 500 before the status container
      * is written; 3900 never downgrades an existing error, so the
      * first failure is preserved (F51).
           MOVE LENGTH OF API-TRAN-LIST TO WS-LIST-LEN
           EXEC CICS PUT
                CONTAINER ('TRANLISTRSP')
                CHANNEL   ('CDEMOAPILISTCH')
                FROM      (API-TRAN-LIST)
                FLENGTH   (WS-LIST-LEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               SET WS-PUT-ERROR TO TRUE
               PERFORM 3900-SET-LIST-ERROR
           END-IF
      *
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-CONT-LEN
           EXEC CICS PUT
                CONTAINER ('TRANLISTSTA')
                CHANNEL   ('CDEMOAPILISTCH')
                FROM      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-CONT-LEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           .
      *
      *--------------------------------------------------------*
      * 3050-CHECK-ACCT : prove the account exists (read-only) before
      * returning a list. A missing account is a 404; any other CICS
      * response is a 500. An account that exists but has no matching
      * transactions still returns 200 with an empty list, but only
      * after its existence is confirmed here (F53).
      *--------------------------------------------------------*
       3050-CHECK-ACCT.
      *
           SET WS-ACCT-NOT-OK TO TRUE
           MOVE TRLR-ACCT-ID TO WS-ACCT-KEY
      *
           EXEC CICS READ
                DATASET   (WS-ACCTDAT-FILE)
                INTO      (ACCOUNT-RECORD)
                LENGTH    (LENGTH OF ACCOUNT-RECORD)
                RIDFLD    (WS-ACCT-KEY)
                KEYLENGTH (LENGTH OF WS-ACCT-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  SET WS-ACCT-OK TO TRUE
               WHEN DFHRESP(NOTFND)
                  PERFORM 3920-SET-LIST-NOTFOUND
               WHEN OTHER
                  PERFORM 3900-SET-LIST-ERROR
           END-EVALUATE
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
           MOVE TRLR-ACCT-ID TO WS-CXREF-KEY
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
                  IF WS-RESP-CD NOT = DFHRESP(NORMAL)
                      SET WS-BROWSE-ERROR TO TRUE
                  END-IF
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
      * CXACAIX is a NON-UNIQUE account-path index: the second and
      * subsequent card records for a multi-card account are returned
      * with a DUPKEY response, which is a normal browse condition here
      * and MUST be handled exactly like NORMAL - otherwise multi-card
      * accounts fail with a spurious 500 (F58).
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
               WHEN DFHRESP(DUPKEY)
                  IF XREF-ACCT-ID OF CARD-XREF-RECORD
                      = TRLR-ACCT-ID
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
      * 3120-APPEND-CARD : add a card number to the working table. The
      * table holds WS-MAX-CARDS entries; an account with more cards
      * than that cannot be listed correctly, so rather than silently
      * dropping cards (and their transactions) the request fails with
      * a deterministic 500 (F54).
      *--------------------------------------------------------*
       3120-APPEND-CARD.
      *
           IF WS-CARD-COUNT < WS-MAX-CARDS
               ADD 1 TO WS-CARD-COUNT
               MOVE XREF-CARD-NUM OF CARD-XREF-RECORD
                  TO WS-CT-CARD-NUM (WS-CARD-COUNT)
           ELSE
               SET WS-BROWSE-ERROR TO TRUE
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
                  IF WS-RESP-CD NOT = DFHRESP(NORMAL)
                      SET WS-BROWSE-ERROR TO TRUE
                  END-IF
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
      * A scan guard bounds the full base-file browse: because there is
      * no account-keyed transaction path, every record is examined, so
      * an unbounded loop is capped at WS-MAX-SCAN and a breach fails
      * fast with a 500 rather than running without limit (F57).
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                  ADD 1 TO WS-SCAN-COUNT
                  IF WS-SCAN-COUNT > WS-MAX-SCAN
                      SET WS-BROWSE-ERROR TO TRUE
                      SET WS-BROWSE-DONE TO TRUE
                  ELSE
                      PERFORM 3220-FILTER-AND-ADD
                  END-IF
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
      * belongs to the requested account. The list is capped at
      * WS-MAX-TRANS entries; a match beyond the cap flags truncation
      * (truncated='Y') and stops the browse - a disclosed partial list.
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
      * A matching record beyond the supported list size cannot be
      * represented in the fixed ODO table. Rather than a silent stop,
      * the list is flagged truncated (truncated='Y') and the browse
      * ends, so the caller receives a disclosed partial list with
      * HTTP 200 - never an undisclosed partial result (F55).
           IF WS-CARD-MATCHED
               IF TRAN-LIST-COUNT >= WS-MAX-TRANS
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
      * list is emptied, the canonical 'INTERNAL' code is published and
      * no RESP2 value is ever exposed. 3900 only ever raises severity
      * to 500, so an earlier error is never downgraded (F51). The code
      * strings mirror the API-ERR-CODE canonical vocabulary (F36).
      *--------------------------------------------------------*
       3900-SET-LIST-ERROR.
      *
           MOVE 500 TO TRLS-HTTP-STATUS
           MOVE -1 TO TRLS-RETURN-CODE
           MOVE 'INTERNAL' TO TRLS-ERR-CODE
           MOVE 'Internal server error' TO TRLS-ERR-MESSAGE
           MOVE ZERO TO TRAN-LIST-COUNT
           SET TRAN-LIST-COMPLETE TO TRUE
           .
      *
      *--------------------------------------------------------*
      * 3910-SET-LIST-BADREQ : 400 for a malformed list request (a
      * missing / wrong-length request container or a non-numeric
      * account id). The list is emptied; the canonical 'BADREQ' code
      * is published (F52, F36).
      *--------------------------------------------------------*
       3910-SET-LIST-BADREQ.
      *
           MOVE 400 TO TRLS-HTTP-STATUS
           MOVE +4 TO TRLS-RETURN-CODE
           MOVE 'BADREQ' TO TRLS-ERR-CODE
           MOVE 'Account id must be 11 numeric digits'
               TO TRLS-ERR-MESSAGE
           MOVE ZERO TO TRAN-LIST-COUNT
           SET TRAN-LIST-COMPLETE TO TRUE
           .
      *
      *--------------------------------------------------------*
      * 3920-SET-LIST-NOTFOUND : 404 when the requested account does
      * not exist. The list is emptied; the canonical 'NOTFOUND' code
      * is published (F53, F36).
      *--------------------------------------------------------*
       3920-SET-LIST-NOTFOUND.
      *
           MOVE 404 TO TRLS-HTTP-STATUS
           MOVE +4 TO TRLS-RETURN-CODE
           MOVE 'NOTFOUND' TO TRLS-ERR-CODE
           MOVE 'Account not found' TO TRLS-ERR-MESSAGE
           MOVE ZERO TO TRAN-LIST-COUNT
           SET TRAN-LIST-COMPLETE TO TRUE
           .
      *
      *--------------------------------------------------------*
      * 9000-SCRUB-WORKAREAS : before returning to the router, clear
      * every work area that held a full (unmasked) card number or a
      * raw VSAM record so no sensitive data lingers in this program's
      * storage across the pseudo-conversational return (F60).
      *--------------------------------------------------------*
       9000-SCRUB-WORKAREAS.
      *
           MOVE LOW-VALUES TO TRAN-RECORD
           MOVE LOW-VALUES TO CARD-XREF-RECORD
           MOVE LOW-VALUES TO ACCOUNT-RECORD
           MOVE SPACES TO WS-CARD-TABLE
           MOVE SPACES TO WS-PAN-INPUT
           MOVE SPACES TO WS-MASKED-PAN
           MOVE SPACES TO WS-PAN-LAST4
           MOVE SPACES TO WS-TRAN-KEY
           .
      *
      * Ver: CardDemo_v1.0 REST/JSON API layer - COTRSVCC
      *
