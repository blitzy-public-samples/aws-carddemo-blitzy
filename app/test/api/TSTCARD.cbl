      ******************************************************************
      * Program     : TSTCARD.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : API test driver - card inquiry service COCRSVCC
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
       PROGRAM-ID. TSTCARD.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
      ******************************************************************
      * Shared REST/JSON API COMMAREA contract (request + response).  *
      * Supplies API-COMMAREA, API-REQ-CARD-NUM, API-SERVICE-CODE,    *
      * API-HTTP-METHOD, API-PAYLOAD and the HTTP-status 88 levels.   *
      ******************************************************************
       COPY COAPICOM.

      ******************************************************************
      * Card inquiry response layout (masked PAN; no security field). *
      * By design COAPCRDY has NO card security code field, so this   *
      * driver cannot even reference one - the structural guarantee.  *
      ******************************************************************
       COPY COAPCRDY.

      ******************************************************************
      * Driver control fields.                                        *
      ******************************************************************
       01  WS-PGM-COCRSVCC       PIC X(08) VALUE 'COCRSVCC'.
       01  WS-RESP-CD            PIC S9(09) COMP VALUE ZEROS.
       01  WS-REAS-CD            PIC S9(09) COMP VALUE ZEROS.

       01  WS-COUNTERS.
           05  WS-TESTS-RUN      PIC 9(04) VALUE ZERO.
           05  WS-TESTS-PASS     PIC 9(04) VALUE ZERO.
           05  WS-TESTS-FAIL     PIC 9(04) VALUE ZERO.

       01  WS-TEST-RC            PIC 9(04) VALUE ZERO.

       01  WS-TEST-PASS-FLG      PIC X(01) VALUE 'Y'.
           88  WS-TEST-PASSED    VALUE 'Y'.
           88  WS-TEST-FAILED    VALUE 'N'.

      ******************************************************************
      * Expected fixture literals (app/data/ASCII/carddata.txt rec0). *
      * Masked form = twelve asterisks followed by the last four PAN  *
      * digits, matching the masking applied inside COCRSVCC.         *
      ******************************************************************
       01  WS-GOOD-CARD          PIC X(16) VALUE '0500024453765740'.
       01  WS-BAD-CARD           PIC X(16) VALUE '9999999999999999'.
       01  WS-EXP-CARD-MASK      PIC X(16) VALUE '************5740'.
       01  WS-EXP-EMBOSSED       PIC X(50) VALUE 'Aniya Von'.
       01  WS-EXP-EXPIRY         PIC X(10) VALUE '2023-03-09'.

      ******************************************************************
      * Security leak probes. Neither the full PAN nor the card       *
      * security code may ever appear in the marshalled payload; the  *
      * INSPECT counters below assert both are absent at run time.    *
      ******************************************************************
       01  WS-FULL-PAN           PIC X(16) VALUE '0500024453765740'.
       01  WS-CVV                PIC X(03) VALUE '747'.
      *    Contextual CVV signature = acct id (00000000050) immediately
      *    followed by the CVV (747) as laid out in the raw card record;
      *    a near-zero false-positive probe that the CVV never leaks.
       01  WS-CVV-CTX            PIC X(14) VALUE '00000000050747'.
       01  WS-PAN-COUNT          PIC 9(04) VALUE 0.
       01  WS-CVV-COUNT          PIC 9(04) VALUE 0.
       01  WS-CVV-CTX-COUNT      PIC 9(04) VALUE 0.

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
      ******************************************************************
      * 0000-MAIN : drive each test, publish the report, then return  *
      * control to the caller. Invoked as a CICS transaction.         *
      ******************************************************************
       0000-MAIN.
           PERFORM 1000-TEST-CARD-OK
           PERFORM 2000-TEST-CARD-NOTFOUND
           PERFORM 9000-REPORT

           EXEC CICS RETURN
           END-EXEC
           .

      ******************************************************************
      * 1000-TEST-CARD-OK : happy path. Links COCRSVCC with the seeded *
      * card number and asserts HTTP 200, the masked PAN, the resolved *
      * account id and active status, then runs the two security leak *
      * assertions over the marshalled payload.                       *
      ******************************************************************
       1000-TEST-CARD-OK.
           ADD 1                     TO WS-TESTS-RUN
           MOVE 'Y'                  TO WS-TEST-PASS-FLG

           INITIALIZE API-COMMAREA
           INITIALIZE API-CARD-RESPONSE
           MOVE 'COCRSVCC'           TO API-SERVICE-CODE
           MOVE 'GET '               TO API-HTTP-METHOD
           MOVE WS-GOOD-CARD         TO API-REQ-CARD-NUM

           EXEC CICS LINK
                PROGRAM   (WS-PGM-COCRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           MOVE API-PAYLOAD          TO API-CARD-RESPONSE

           IF NOT API-HTTP-OK
               MOVE 'N'              TO WS-TEST-PASS-FLG
           END-IF
           IF CARD-NUM-MASKED NOT = WS-EXP-CARD-MASK
               MOVE 'N'              TO WS-TEST-PASS-FLG
           END-IF
           IF CARD-ACCT-ID NOT = 50
               MOVE 'N'              TO WS-TEST-PASS-FLG
           END-IF
           IF CARD-ACTIVE-STATUS NOT = 'Y'
               MOVE 'N'              TO WS-TEST-PASS-FLG
           END-IF
           IF CARD-EMBOSSED-NAME NOT = WS-EXP-EMBOSSED
               MOVE 'N'              TO WS-TEST-PASS-FLG
           END-IF
           IF CARD-EXPIRAION-DATE NOT = WS-EXP-EXPIRY
               MOVE 'N'              TO WS-TEST-PASS-FLG
           END-IF

      *    Security: the full PAN must never appear in the payload.
           MOVE ZERO                 TO WS-PAN-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-PAN-COUNT FOR ALL WS-FULL-PAN
           IF WS-PAN-COUNT > 0
               MOVE 'N'              TO WS-TEST-PASS-FLG
               DISPLAY 'TSTCARD: SENSITIVE DATA LEAK DETECTED'
           END-IF

      *    Security: the card security code must never appear either.
      *    Scan the bare CVV and the acct-id+CVV signature across both
      *    the raw payload and the mapped response for full coverage.
           MOVE ZERO                 TO WS-CVV-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-CVV-COUNT FOR ALL WS-CVV
           INSPECT API-CARD-RESPONSE
               TALLYING WS-CVV-COUNT FOR ALL WS-CVV
           MOVE ZERO                 TO WS-CVV-CTX-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-CVV-CTX-COUNT FOR ALL WS-CVV-CTX
           INSPECT API-CARD-RESPONSE
               TALLYING WS-CVV-CTX-COUNT FOR ALL WS-CVV-CTX
           IF WS-CVV-COUNT > 0 OR WS-CVV-CTX-COUNT > 0
               MOVE 'N'              TO WS-TEST-PASS-FLG
               DISPLAY 'TSTCARD: SENSITIVE DATA LEAK DETECTED'
           END-IF

           IF WS-TEST-PASSED
               ADD 1                 TO WS-TESTS-PASS
               DISPLAY 'TSTCARD 1000-TEST-CARD-OK PASS'
           ELSE
               ADD 1                 TO WS-TESTS-FAIL
               DISPLAY 'TSTCARD 1000-TEST-CARD-OK FAIL'
           END-IF
           .

      ******************************************************************
      * 2000-TEST-CARD-NOTFOUND : negative path. An unknown card must  *
      * resolve to HTTP 404 (NOT-FOUND) from COCRSVCC.                 *
      ******************************************************************
       2000-TEST-CARD-NOTFOUND.
           ADD 1                     TO WS-TESTS-RUN
           MOVE 'Y'                  TO WS-TEST-PASS-FLG

           INITIALIZE API-COMMAREA
           MOVE 'COCRSVCC'           TO API-SERVICE-CODE
           MOVE 'GET '               TO API-HTTP-METHOD
           MOVE WS-BAD-CARD          TO API-REQ-CARD-NUM

           EXEC CICS LINK
                PROGRAM   (WS-PGM-COCRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           IF NOT API-HTTP-NOT-FOUND
               MOVE 'N'              TO WS-TEST-PASS-FLG
           END-IF

           IF WS-TEST-PASSED
               ADD 1                 TO WS-TESTS-PASS
               DISPLAY 'TSTCARD 2000-TEST-CARD-NOTFOUND PASS'
           ELSE
               ADD 1                 TO WS-TESTS-FAIL
               DISPLAY 'TSTCARD 2000-TEST-CARD-NOTFOUND FAIL'
           END-IF
           .

      ******************************************************************
      * 9000-REPORT : emit the run/pass/fail tally and set the process *
      * return code to 8 when any assertion failed.                   *
      ******************************************************************
       9000-REPORT.
           DISPLAY 'TSTCARD RESULTS RUN=' WS-TESTS-RUN
               ' PASS=' WS-TESTS-PASS ' FAIL=' WS-TESTS-FAIL

           IF WS-TESTS-FAIL > 0
               MOVE 8                TO WS-TEST-RC
           END-IF
      *    Publish the verdict to the caller COMMAREA when invoked via
      *    LINK/EXCI (EIBCALEN > 0). MAJ-07 / decision-log D37.
           IF EIBCALEN > 0
               MOVE WS-TESTS-RUN   TO DRV-TESTS-RUN
               MOVE WS-TESTS-PASS  TO DRV-TESTS-PASS
               MOVE WS-TESTS-FAIL  TO DRV-TESTS-FAIL
               MOVE 'TSTCARD'      TO DRV-DRIVER-ID
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
           MOVE WS-TEST-RC           TO RETURN-CODE
           .

      *
      * Ver: CardDemo_v1.0
      *
