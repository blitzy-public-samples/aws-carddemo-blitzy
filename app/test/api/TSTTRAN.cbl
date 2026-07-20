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
      * The request, raw-request alias and status container layouts are
      * the SHARED COAPTRNY groups - API-TRAN-LIST-REQUEST (TRLR-ACCT-ID
      * 9(11)), API-TRAN-LIST-REQ-RAW (TRLR-ACCT-ID-RAW X(11), for the
      * malformed-input case) and API-TRAN-LIST-STATUS (TRLS-*) - so the
      * driver can never drift from the producer COTRSVCC. No local
      * mirrors of the container layouts are declared.
      *
      * Expected sanitized error-envelope messages COTRSVCC publishes
      * (fixed literals; asserting equality proves the envelope carries
      * no RESP2 / internal leakage).
      ******************************************************************
       01  WS-EXP-BADREQ-MSG   PIC X(120) VALUE
           'Account id must be 11 numeric digits'.
       01  WS-EXP-NOTFND-MSG   PIC X(120) VALUE
           'Account not found'.
       01  WS-EXP-INTNL-MSG    PIC X(120) VALUE
           'Internal server error'.
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
       01  WS-TESTS-SKIP          PIC 9(03) VALUE 0.
       01  WS-TEST-RC             PIC S9(04) VALUE 0.
       01  WS-BAD-MASK-CNT        PIC 9(04) VALUE 0.
      *  Command-response accumulator: set to 'N' by any container /
      *  link command whose RESP is not NORMAL or whose FLENGTH does
      *  not match the exact container contract (M21 RESP/FLENGTH).
       01  WS-CMD-OK              PIC X(01) VALUE 'Y'.
           88  CMD-OK             VALUE 'Y'.
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
      * Boundary / failure fixtures (additive; loaded by the live API
      * test JCL, out of scope). WS-TRUNC-ACCT owns MORE than the cap
      * of matching transactions so the list is truncated at 50 with
      * TRAN-LIST-WAS-TRUNCATED = 'Y' and HTTP 200 (never 500).
      * WS-FAULT-ACCT is engineered to drive an unexpected file error
      * so COTRSVCC returns the sanitized HTTP 500 envelope. When
      * either fixture is absent the service answers 404 and the case
      * is a release-blocking SKIP (never PASS).
      ******************************************************************
       01  WS-TRUNC-ACCT          PIC 9(11) VALUE 97.
       01  WS-FAULT-ACCT          PIC 9(11) VALUE 96.
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
      *----------------------------------------------------------------*
      *                     LINKAGE SECTION
      *----------------------------------------------------------------*
      * Optional result COMMAREA for the RC-gated runner (MAJ-07). When
      * a caller LINKs with it (EIBCALEN > 0) 9000-REPORT publishes the
      * verdict here; with no COMMAREA (started-task mode) the driver is
      * unchanged. Layout: app/cpy/COAPDRVY.cpy. See decision-log D37.
      *----------------------------------------------------------------*
       LINKAGE SECTION.
       COPY COAPDRVY.

       PROCEDURE DIVISION.
      *
      ******************************************************************
      * 0000-MAIN : run every detail and list check (including the
      * boundary and failure-path list cases), report, and return
      * control to the caller.
      ******************************************************************
       0000-MAIN.
           MOVE 0 TO WS-TESTS-RUN
           MOVE 0 TO WS-TESTS-PASS
           MOVE 0 TO WS-TESTS-FAIL
           MOVE 0 TO WS-TESTS-SKIP
           MOVE 0 TO WS-TEST-RC
           PERFORM 1000-TEST-DETAIL-OK
           PERFORM 2000-TEST-DETAIL-NOTFOUND
           PERFORM 2500-TEST-DETAIL-BADREQ
           PERFORM 3000-TEST-LIST-NONEMPTY
           PERFORM 4000-TEST-LIST-EMPTY
           PERFORM 5000-TEST-LIST-NOTFOUND
           PERFORM 6000-TEST-LIST-BADREQ
           PERFORM 7000-TEST-LIST-NO-CONTAINER
           PERFORM 7100-TEST-LIST-BAD-LENGTH
           PERFORM 7200-TEST-LIST-TRUNCATED
           PERFORM 7300-TEST-LIST-INTERNAL
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
      * with six transactions. Reset the channel, PUT the request
      * container, LINK COTRSVCC, then GET the status and response
      * containers. Preset the ODO object to 50 so the receive area
      * is sized to the cap before the GET; COTRSVCC overwrites it
      * with the true count. Assert every container command returns
      * NORMAL with the exact contract FLENGTH, HTTP 200, a positive
      * count, every entry masked, and no full PAN in the payload.
      ******************************************************************
       3000-TEST-LIST-NONEMPTY.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           MOVE ZERO TO TRAN-LIST-COUNT
           INITIALIZE API-TRAN-LIST-REQUEST API-TRAN-LIST-STATUS
                      API-TRAN-LIST
           MOVE WS-LIST-ACCT TO TRLR-ACCT-ID
           MOVE 0 TO WS-PAN-COUNT
           MOVE 0 TO WS-BAD-MASK-CNT
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (API-TRAN-LIST-REQUEST)
                FLENGTH   (LENGTH OF API-TRAN-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE 50 TO TRAN-LIST-COUNT
           MOVE LENGTH OF API-TRAN-LIST TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-RSP)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST
               MOVE 'N' TO WS-CMD-OK
           END-IF
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
           IF TRLS-HTTP-STATUS = 200
              AND TRAN-LIST-COUNT > 0
              AND WS-BAD-MASK-CNT = 0
              AND WS-PAN-COUNT = 0
              AND CMD-OK
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
      * Same channel/container calls as 3000 (ODO preset to 50
      * before the response GET), with the same RESP/FLENGTH
      * command-response assertions. See WS-EMPTY-ACCT for the
      * supplied synthetic ACCTDAT fixture.
      ******************************************************************
       4000-TEST-LIST-EMPTY.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           MOVE ZERO TO TRAN-LIST-COUNT
           INITIALIZE API-TRAN-LIST-REQUEST API-TRAN-LIST-STATUS
                      API-TRAN-LIST
           MOVE WS-EMPTY-ACCT TO TRLR-ACCT-ID
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (API-TRAN-LIST-REQUEST)
                FLENGTH   (LENGTH OF API-TRAN-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE 50 TO TRAN-LIST-COUNT
           MOVE LENGTH OF API-TRAN-LIST TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-RSP)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST
               MOVE 'N' TO WS-CMD-OK
           END-IF
           IF TRLS-HTTP-STATUS = 404
               DISPLAY 'TSTTRAN: EMPTY LIST RETURNED 404 (EXPECTED 200)'
           END-IF
           IF TRLS-HTTP-STATUS = 200
              AND TRAN-LIST-COUNT = 0
              AND CMD-OK
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
      * from ACCTDAT, so list mode must return HTTP 404/NOTFOUND
      * with the sanitized 'Account not found' envelope message and
      * every container command NORMAL. This distinguishes a missing
      * account from test 4000's valid-but-cardless HTTP 200 response.
      ******************************************************************
       5000-TEST-LIST-NOTFOUND.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           INITIALIZE API-TRAN-LIST-REQUEST API-TRAN-LIST-STATUS
           MOVE WS-NOTFOUND-ACCT TO TRLR-ACCT-ID
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (API-TRAN-LIST-REQUEST)
                FLENGTH   (LENGTH OF API-TRAN-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           IF TRLS-HTTP-STATUS = 404
              AND TRLS-RETURN-CODE = +4
              AND TRLS-ERR-CODE = 'NOTFOUND'
              AND TRLS-ERR-MESSAGE = WS-EXP-NOTFND-MSG
              AND CMD-OK
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
      * reject the request with HTTP 400/BADREQ and the sanitized
      * 'Account id must be 11 numeric digits' message before any
      * ACCTDAT access, with every container command NORMAL.
      ******************************************************************
       6000-TEST-LIST-BADREQ.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           INITIALIZE API-TRAN-LIST-REQUEST API-TRAN-LIST-STATUS
           MOVE WS-BADREQ-ACCT TO TRLR-ACCT-ID-RAW
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (API-TRAN-LIST-REQ-RAW)
                FLENGTH   (LENGTH OF API-TRAN-LIST-REQ-RAW)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           IF TRLS-HTTP-STATUS = 400
              AND TRLS-RETURN-CODE = +4
              AND TRLS-ERR-CODE = 'BADREQ'
              AND TRLS-ERR-MESSAGE = WS-EXP-BADREQ-MSG
              AND CMD-OK
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 6000 LIST-BADREQ PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 6000 LIST-BADREQ FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 8100-RESET-CHANNEL : delete any residual request/response/
      * status containers so each list test runs against a FRESH
      * channel (M21 - no cross-test container carryover). RESP is
      * captured but tolerated: a not-found container on the first
      * pass is expected and benign. Container commands are task-
      * local, so no VSAM dataset is written (read-only preserved).
      ******************************************************************
       8100-RESET-CHANNEL.
           EXEC CICS DELETE
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EXEC CICS DELETE
                CONTAINER (WS-CTR-RSP)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EXEC CICS DELETE
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           .
      *
      ******************************************************************
      * 7000-TEST-LIST-NO-CONTAINER : drive the missing-request-
      * container path. After resetting the channel the driver does
      * NOT put TRANLISTREQ, then LINKs COTRSVCC. The service's GET
      * of the absent container fails, so it must answer HTTP 400 /
      * BADREQ with the sanitized message - never a dump or 500.
      ******************************************************************
       7000-TEST-LIST-NO-CONTAINER.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           INITIALIZE API-TRAN-LIST-STATUS
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           IF TRLS-HTTP-STATUS = 400
              AND TRLS-ERR-CODE = 'BADREQ'
              AND TRLS-ERR-MESSAGE = WS-EXP-BADREQ-MSG
              AND CMD-OK
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 7000 LIST-NO-CONTAINER PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 7000 LIST-NO-CONTAINER FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 7100-TEST-LIST-BAD-LENGTH : PUT a request container that is
      * shorter than the 11-byte contract (5 bytes) even though the
      * account id itself is numeric. COTRSVCC must detect the FLENGTH
      * mismatch and answer HTTP 400 / BADREQ with the sanitized
      * message before any ACCTDAT access - proving the length guard.
      ******************************************************************
       7100-TEST-LIST-BAD-LENGTH.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           INITIALIZE API-TRAN-LIST-REQUEST API-TRAN-LIST-STATUS
           MOVE WS-LIST-ACCT TO TRLR-ACCT-ID
           MOVE 5 TO WS-FLEN
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (API-TRAN-LIST-REQUEST)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           IF TRLS-HTTP-STATUS = 400
              AND TRLS-ERR-CODE = 'BADREQ'
              AND TRLS-ERR-MESSAGE = WS-EXP-BADREQ-MSG
              AND CMD-OK
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 7100 LIST-BAD-LENGTH PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTTRAN 7100 LIST-BAD-LENGTH FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 7200-TEST-LIST-TRUNCATED : account 00000000097 owns MORE than
      * the cap of matching transactions. The service must cap the
      * list at 50, set TRAN-LIST-WAS-TRUNCATED = 'Y', and STILL
      * answer HTTP 200 (a disclosed partial list, never a 500). When
      * the boundary fixture is absent the account is unknown and the
      * service answers 404: a release-blocking SKIP, never a PASS.
      ******************************************************************
       7200-TEST-LIST-TRUNCATED.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           MOVE ZERO TO TRAN-LIST-COUNT
           INITIALIZE API-TRAN-LIST-REQUEST API-TRAN-LIST-STATUS
                      API-TRAN-LIST
           MOVE WS-TRUNC-ACCT TO TRLR-ACCT-ID
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (API-TRAN-LIST-REQUEST)
                FLENGTH   (LENGTH OF API-TRAN-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE 50 TO TRAN-LIST-COUNT
           MOVE LENGTH OF API-TRAN-LIST TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-RSP)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST
               MOVE 'N' TO WS-CMD-OK
           END-IF
           IF TRLS-HTTP-STATUS = 200
              AND TRAN-LIST-COUNT = 50
              AND TRAN-LIST-WAS-TRUNCATED
              AND CMD-OK
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 7200 LIST-TRUNCATED PASS'
           ELSE
               IF TRLS-HTTP-STATUS = 404
                   ADD 1 TO WS-TESTS-SKIP
                   DISPLAY 'TSTTRAN 7200 TRUNC SKIP-ABSENT'
               ELSE
                   ADD 1 TO WS-TESTS-FAIL
                   DISPLAY 'TSTTRAN 7200 LIST-TRUNCATED FAIL'
               END-IF
           END-IF
           .
      *
      ******************************************************************
      * 7300-TEST-LIST-INTERNAL : account 00000000096 is engineered to
      * drive an unexpected file error inside COTRSVCC so the service
      * must answer the sanitized HTTP 500 envelope ('Internal server
      * error', code INTERNAL) with NO RESP2 / internal leakage. When
      * the fault fixture is absent the account is unknown and the
      * service answers 404: a release-blocking SKIP, never a PASS.
      ******************************************************************
       7300-TEST-LIST-INTERNAL.
           ADD 1 TO WS-TESTS-RUN
           PERFORM 8100-RESET-CHANNEL
           MOVE 'Y' TO WS-CMD-OK
           INITIALIZE API-TRAN-LIST-REQUEST API-TRAN-LIST-STATUS
           MOVE WS-FAULT-ACCT TO TRLR-ACCT-ID
           EXEC CICS PUT
                CONTAINER (WS-CTR-REQ)
                CHANNEL   (WS-CHANNEL)
                FROM      (API-TRAN-LIST-REQUEST)
                FLENGTH   (LENGTH OF API-TRAN-LIST-REQUEST)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COTRSVCC)
                CHANNEL   (WS-CHANNEL)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 'N' TO WS-CMD-OK
           END-IF
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-FLEN
           EXEC CICS GET
                CONTAINER (WS-CTR-STA)
                CHANNEL   (WS-CHANNEL)
                INTO      (API-TRAN-LIST-STATUS)
                FLENGTH   (WS-FLEN)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
              OR WS-FLEN NOT = LENGTH OF API-TRAN-LIST-STATUS
               MOVE 'N' TO WS-CMD-OK
           END-IF
           IF TRLS-HTTP-STATUS = 500
              AND TRLS-ERR-CODE = 'INTERNAL'
              AND TRLS-ERR-MESSAGE = WS-EXP-INTNL-MSG
              AND CMD-OK
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTTRAN 7300 LIST-INTERNAL PASS'
           ELSE
               IF TRLS-HTTP-STATUS = 404
                   ADD 1 TO WS-TESTS-SKIP
                   DISPLAY 'TSTTRAN 7300 INTNL SKIP-ABSENT'
               ELSE
                   ADD 1 TO WS-TESTS-FAIL
                   DISPLAY 'TSTTRAN 7300 LIST-INTERNAL FAIL'
               END-IF
           END-IF
           .
      *
      ******************************************************************
      * 9000-REPORT : print the run/pass/fail/skip tallies and the
      * overall verdict, then raise the machine-observable process
      * return code (M19): 8 when any assertion failed, else 4 when a
      * release-blocking fixture was absent (SKIP), else 0. A non-zero
      * RC lets the harness / CSD-invoked run fail the build.
      ******************************************************************
       9000-REPORT.
           DISPLAY 'TSTTRAN RESULTS RUN=' WS-TESTS-RUN
               ' PASS=' WS-TESTS-PASS ' FAIL=' WS-TESTS-FAIL
               ' SKIP=' WS-TESTS-SKIP
           IF WS-TESTS-FAIL > 0
               MOVE 8 TO WS-TEST-RC
               DISPLAY 'TSTTRAN RESULT: FAIL'
           ELSE
               IF WS-TESTS-SKIP > 0
                   MOVE 4 TO WS-TEST-RC
                   DISPLAY 'TSTTRAN RESULT: PASS (WITH SKIPS)'
               ELSE
                   MOVE 0 TO WS-TEST-RC
                   DISPLAY 'TSTTRAN RESULT: PASS'
               END-IF
           END-IF
      *    Publish the verdict to the caller COMMAREA when invoked via
      *    LINK/EXCI (EIBCALEN > 0). MAJ-07 / decision-log D37.
           IF EIBCALEN > 0
               MOVE WS-TESTS-RUN   TO DRV-TESTS-RUN
               MOVE WS-TESTS-PASS  TO DRV-TESTS-PASS
               MOVE WS-TESTS-FAIL  TO DRV-TESTS-FAIL
               MOVE 'TSTTRAN'      TO DRV-DRIVER-ID
               EVALUATE WS-TEST-RC
                   WHEN 8
                       SET DRV-FAIL TO TRUE
                   WHEN 4
                       SET DRV-SKIP TO TRUE
                   WHEN 0
                       SET DRV-PASS TO TRUE
                   WHEN OTHER
                       SET DRV-SKIP TO TRUE
               END-EVALUATE
           END-IF
           MOVE WS-TEST-RC TO RETURN-CODE
           .
      *
      * Ver: CardDemo_v1.0
      *
