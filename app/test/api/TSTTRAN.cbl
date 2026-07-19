      ******************************************************************
      * Program     : TSTTRAN.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : API test driver - transaction svc COTRSVCC 2 mode
      ******************************************************************
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
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. TSTTRAN.
       AUTHOR. AWS.
      *
      ******************************************************************
      * This standalone driver LINKs to the transaction service
      * COTRSVCC and asserts BOTH of its transport contracts:
      *   DETAIL - GET /transactions/{tranId} over the COMMAREA;
      *            HTTP 200 for a known id, 404 for an unknown id,
      *            400 for a malformed id, correct money scale, and
      *            a masked PAN with no full-PAN leakage.
      *   LIST   - GET /accounts/{acctId}/transactions over channel
      *            CDEMOAPILISTCH with request/response/status
      *            containers; populated and cardless accounts return
      *            200, a missing account returns 404, and malformed
      *            input returns 400. Read-only; no VSAM writes.
      ******************************************************************
      *
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      *
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      ******************************************************************
      * Shared API COMMAREA / token / error-envelope contract that
      * carries the single-record request key and response status
      * (used by the DETAIL mode) - COAPICOM.
      ******************************************************************
       COPY COAPICOM.
      *
      ******************************************************************
      * Transaction detail contract (API-TRAN-RESPONSE) and the
      * per-account list contract (API-TRAN-LIST, ODO 0..500) plus
      * the channel request/status container layouts - COAPTRNY.
      ******************************************************************
       COPY COAPTRNY.
      *
      ******************************************************************
      * Channel and container names - MUST match COTRSVCC exactly.
      ******************************************************************
       01  WS-CHANNEL             PIC X(16) VALUE 'CDEMOAPILISTCH'.
       01  WS-CTR-REQ             PIC X(16) VALUE 'TRANLISTREQ'.
       01  WS-CTR-RSP             PIC X(16) VALUE 'TRANLISTRSP'.
       01  WS-CTR-STA             PIC X(16) VALUE 'TRANLISTSTA'.
      *
      ******************************************************************
      * Local mirror of the TRANLISTREQ input container (11 bytes):
      * the requested account id handed to COTRSVCC list mode.
      ******************************************************************
       01  WS-LIST-REQUEST.
           05  WS-LR-ACCT-ID      PIC 9(11).
       01  WS-LIST-REQUEST-RAW REDEFINES WS-LIST-REQUEST.
           05  WS-LR-ACCT-ID-RAW  PIC X(11).
      *
      ******************************************************************
      * Local mirror of the TRANLISTSTA status container (135
      * bytes): the HTTP status intent and error envelope that
      * COTRSVCC publishes for the list call.
      ******************************************************************
       01  WS-LIST-STATUS.
           05  WS-LS-HTTP-STATUS  PIC 9(03).
           05  WS-LS-RETURN-CODE  PIC S9(04).
           05  WS-LS-ERR-CODE     PIC X(08).
           05  WS-LS-ERR-MESSAGE  PIC X(120).
      *
      ******************************************************************
      * LINK target program and CICS response feedback codes,
      * container length feedback, and the list entry index.
      ******************************************************************
       01  WS-PGM-COTRSVCC        PIC X(08) VALUE 'COTRSVCC'.
       01  WS-RESP-CD             PIC S9(09) COMP VALUE ZEROS.
       01  WS-REAS-CD             PIC S9(09) COMP VALUE ZEROS.
       01  WS-FLEN                PIC S9(08) COMP VALUE 0.
       01  WS-IDX                 PIC S9(04) COMP VALUE 0.
      *
      ******************************************************************
      * Test bookkeeping: run/pass/fail tallies, process return
      * code, and a per-entry masking-failure accumulator.
      ******************************************************************
       01  WS-TESTS-RUN           PIC 9(03) VALUE 0.
       01  WS-TESTS-PASS          PIC 9(03) VALUE 0.
       01  WS-TESTS-FAIL          PIC 9(03) VALUE 0.
       01  WS-TEST-RC             PIC S9(04) VALUE 0.
       01  WS-BAD-MASK-CNT        PIC 9(04) VALUE 0.
      *
      ******************************************************************
      * Expected fixture values. dailytran rec0: tran id
      * 0000000000683580, amount 0000005047G (G = +7 overpunch)
      * decodes to +504.77 at scale 2, card masked ...7065.
      ******************************************************************
       01  WS-GOOD-TRAN           PIC X(16) VALUE '0000000000683580'.
       01  WS-BAD-TRAN            PIC X(16) VALUE '9999999999999999'.
       01  WS-BADREQ-TRAN         PIC X(16)
                                   VALUE 'NOT-NUMERIC-ID!!'.
       01  WS-EXP-TRAN-AMT        PIC S9(09)V99 VALUE +504.77.
       01  WS-EXP-TRAN-MASK       PIC X(16) VALUE '************7065'.
      *
      ******************************************************************
      * List fixtures. cardxref rec0: account 00000000050 owns
      * card ...5740 (masked) with six transactions, so the list
      * for account 50 is non-empty and every entry is masked.
      ******************************************************************
       01  WS-LIST-ACCT           PIC 9(11) VALUE 50.
       01  WS-NOTFOUND-ACCT       PIC 9(11) VALUE 98.
       01  WS-BADREQ-ACCT         PIC X(11) VALUE 'ABCDEFGHIJK'.
      *
      ******************************************************************
      * Empty-list account. The list contract returns HTTP 200
      * with count 0 (NEVER 404) for a *valid-but-cardless*
      * account - one that EXISTS in ACCTDAT yet resolves to
      * zero cross-referenced cards. COTRSVCC first proves the
      * account exists, so a truly non-existent id yields 404.
      * app/test/api/acctdata-empty.txt supplies account
      * 00000000099 as one active 300-byte ACCTDAT record. It has
      * no CCXREF row, while 00000000098 remains absent for 404.
      ******************************************************************
       01  WS-EMPTY-ACCT          PIC 9(11) VALUE 99.
      *
      ******************************************************************
      * Masking helper: the first twelve characters of every
      * masked PAN must be asterisks.
      ******************************************************************
       01  WS-STARS12             PIC X(12) VALUE ALL '*'.
      *
      ******************************************************************
      * Full-PAN leak probes. A response payload must NEVER carry
      * an unmasked 16-digit PAN; WS-PAN-COUNT tallies any hit.
      ******************************************************************
       01  WS-FULL-TRAN-PAN       PIC X(16) VALUE '4859452612877065'.
       01  WS-LIST-PAN            PIC X(16) VALUE '0500024453765740'.
       01  WS-PAN-COUNT           PIC 9(04) VALUE 0.
      *
       PROCEDURE DIVISION.
      *
      ******************************************************************
      * 0000-MAIN : run the seven transaction checks, report, and
      * return control to the caller.
      ******************************************************************
       0000-MAIN.
           MOVE 0 TO WS-TESTS-RUN
           MOVE 0 TO WS-TESTS-PASS
           MOVE 0 TO WS-TESTS-FAIL
           MOVE 0 TO WS-TEST-RC
           PERFORM 1000-TEST-DETAIL-OK
           PERFORM 2000-TEST-DETAIL-NOTFOUND
           PERFORM 2500-TEST-DETAIL-BADREQ
           PERFORM 3000-TEST-LIST-NONEMPTY
           PERFORM 4000-TEST-LIST-EMPTY
           PERFORM 5000-TEST-LIST-NOTFOUND
           PERFORM 6000-TEST-LIST-BADREQ
           PERFORM 9000-REPORT
           EXEC CICS RETURN
           END-EXEC
           .
      *
      ******************************************************************
      * 1000-TEST-DETAIL-OK : a known transaction id resolves to
      * HTTP 200 over the COMMAREA, the amount decoded to +504.77
      * (scale 2, signed), the PAN masked to the last four, and no
      * full PAN present in the response payload (leak probe = 0).
      ******************************************************************
       1000-TEST-DETAIL-OK.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           INITIALIZE API-TRAN-RESPONSE
           MOVE 'COTRSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-GOOD-TRAN TO API-REQ-TRAN-ID
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE API-PAYLOAD TO API-TRAN-RESPONSE
           MOVE 0 TO WS-PAN-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-PAN-COUNT FOR ALL WS-FULL-TRAN-PAN
           IF WS-PAN-COUNT > 0
               DISPLAY 'TSTTRAN: DETAIL PAN LEAK DETECTED'
           END-IF
           IF API-HTTP-OK
              AND TRAN-ID OF API-TRAN-RESPONSE = WS-GOOD-TRAN
              AND TRAN-AMT OF API-TRAN-RESPONSE = WS-EXP-TRAN-AMT
              AND TRAN-CARD-NUM-MASKED OF API-TRAN-RESPONSE
                  = WS-EXP-TRAN-MASK
              AND WS-PAN-COUNT = 0
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 1000 DETAIL-OK PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 1000 DETAIL-OK FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 2000-TEST-DETAIL-NOTFOUND : an unknown transaction id must
      * resolve to HTTP 404 (NOT-FOUND) over the COMMAREA.
      ******************************************************************
       2000-TEST-DETAIL-NOTFOUND.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           MOVE 'COTRSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-BAD-TRAN TO API-REQ-TRAN-ID
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF API-HTTP-NOT-FOUND
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 2000 DETAIL-NOTFOUND PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 2000 DETAIL-NOTFOUND FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 2500-TEST-DETAIL-BADREQ : a non-numeric 16-byte transaction
      * id must be rejected with HTTP 400 and canonical BADREQ before
      * COTRSVCC attempts a TRANSACT read.
      ******************************************************************
       2500-TEST-DETAIL-BADREQ.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           MOVE 'COTRSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-BADREQ-TRAN TO API-REQ-TRAN-ID
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF API-HTTP-BAD-REQUEST
              AND API-ERR-BAD-REQUEST
              AND API-RETURN-CODE = +4
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 2500 DETAIL-BADREQ PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 2500 DETAIL-BADREQ FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 3000-TEST-LIST-NONEMPTY : account 00000000050 owns a card
      * with six transactions. PUT the request container, LINK
      * COTRSVCC over the channel, then GET the status and the
      * response containers. Preset the ODO object to 500 so the
      * receive area is sized to the maximum before the GET;
      * COTRSVCC overwrites it with the true count. Assert HTTP
      * 200, a positive count, every entry masked (first twelve
      * chars asterisks), and no full PAN in the list payload.
      ******************************************************************
       3000-TEST-LIST-NONEMPTY.
           ADD 1 TO WS-TESTS-RUN
           MOVE ZERO TO TRAN-LIST-COUNT
           INITIALIZE WS-LIST-REQUEST WS-LIST-STATUS API-TRAN-LIST
           MOVE WS-LIST-ACCT TO WS-LR-ACCT-ID
           MOVE 0 TO WS-PAN-COUNT
           MOVE 0 TO WS-BAD-MASK-CNT
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (WS-LIST-REQUEST)
                FLENGTH   (LENGTH OF WS-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE LENGTH OF WS-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (WS-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE 500 TO TRAN-LIST-COUNT
           MOVE LENGTH OF API-TRAN-LIST TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-RSP)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > TRAN-LIST-COUNT
               IF TRNL-CARD-NUM-MASKED (WS-IDX) (1:12)
                   NOT = WS-STARS12
                   ADD 1 TO WS-BAD-MASK-CNT
               END-IF
           END-PERFORM
           INSPECT API-TRAN-LIST
               TALLYING WS-PAN-COUNT FOR ALL WS-LIST-PAN
           IF WS-PAN-COUNT > 0
               DISPLAY 'TSTTRAN: LIST PAN LEAK DETECTED'
           END-IF
           IF WS-LS-HTTP-STATUS = 200
              AND TRAN-LIST-COUNT > 0
              AND WS-BAD-MASK-CNT = 0
              AND WS-PAN-COUNT = 0
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 3000 LIST-NONEMPTY PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 3000 LIST-NONEMPTY FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 4000-TEST-LIST-EMPTY : a valid-but-cardless account must
      * resolve to HTTP 200 with count 0 - explicitly NOT 404.
      * Same channel/container calls as 3000 (ODO preset to 500
      * before the response GET). See WS-EMPTY-ACCT for the
      * supplied synthetic ACCTDAT fixture.
      ******************************************************************
       4000-TEST-LIST-EMPTY.
           ADD 1 TO WS-TESTS-RUN
           MOVE ZERO TO TRAN-LIST-COUNT
           INITIALIZE WS-LIST-REQUEST WS-LIST-STATUS API-TRAN-LIST
           MOVE WS-EMPTY-ACCT TO WS-LR-ACCT-ID
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (WS-LIST-REQUEST)
                FLENGTH   (LENGTH OF WS-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE LENGTH OF WS-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (WS-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE 500 TO TRAN-LIST-COUNT
           MOVE LENGTH OF API-TRAN-LIST TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-RSP)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-LS-HTTP-STATUS = 404
               DISPLAY 'TSTTRAN: EMPTY LIST RETURNED 404 (EXPECTED 200)'
           END-IF
           IF WS-LS-HTTP-STATUS = 200
              AND TRAN-LIST-COUNT = 0
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 4000 LIST-EMPTY PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 4000 LIST-EMPTY FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 5000-TEST-LIST-NOTFOUND : account 00000000098 is absent
      * from ACCTDAT, so list mode must return HTTP 404/NOTFOUND.
      * This distinguishes a missing account from test 4000's
      * valid-but-cardless HTTP 200 response.
      ******************************************************************
       5000-TEST-LIST-NOTFOUND.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE WS-LIST-REQUEST WS-LIST-STATUS
           MOVE WS-NOTFOUND-ACCT TO WS-LR-ACCT-ID
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (WS-LIST-REQUEST)
                FLENGTH   (LENGTH OF WS-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE LENGTH OF WS-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (WS-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-LS-HTTP-STATUS = 404
              AND WS-LS-RETURN-CODE = +4
              AND WS-LS-ERR-CODE = 'NOTFOUND'
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 5000 LIST-NOTFOUND PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 5000 LIST-NOTFOUND FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 6000-TEST-LIST-BADREQ : place eleven non-numeric bytes in
      * TRANLISTREQ through its alphanumeric redefine. COTRSVCC must
      * reject the request with HTTP 400/BADREQ before ACCTDAT access.
      ******************************************************************
       6000-TEST-LIST-BADREQ.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE WS-LIST-REQUEST WS-LIST-STATUS
           MOVE WS-BADREQ-ACCT TO WS-LR-ACCT-ID-RAW
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (WS-LIST-REQUEST-RAW)
                FLENGTH   (LENGTH OF WS-LIST-REQUEST-RAW)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE LENGTH OF WS-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (WS-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-LS-HTTP-STATUS = 400
              AND WS-LS-RETURN-CODE = +4
              AND WS-LS-ERR-CODE = 'BADREQ'
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 6000 LIST-BADREQ PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 6000 LIST-BADREQ FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 9000-REPORT : print the run/pass/fail tallies and the
      * overall verdict, and raise the process return code to 8
      * when any assertion failed.
      ******************************************************************
       9000-REPORT.
           DISPLAY 'TSTTRAN RESULTS RUN=' WS-TESTS-RUN
               ' PASS=' WS-TESTS-PASS ' FAIL=' WS-TESTS-FAIL
           IF WS-TESTS-FAIL > 0
               MOVE 8 TO WS-TEST-RC
               DISPLAY 'TSTTRAN RESULT: FAIL'
           ELSE
               DISPLAY 'TSTTRAN RESULT: PASS'
           END-IF
           MOVE WS-TEST-RC TO RETURN-CODE
           .
      *
      * Ver: CardDemo_v1.0
      *
