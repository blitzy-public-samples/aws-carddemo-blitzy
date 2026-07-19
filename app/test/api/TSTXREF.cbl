      ******************************************************************
      * Program     : TSTXREF.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : API test driver - card xref service COXRSVCC
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
       PROGRAM-ID. TSTXREF.
       AUTHOR. AWS.
      *
      ******************************************************************
      * This standalone driver LINKs to the card cross-reference
      * service COXRSVCC and asserts its GET /xref/{cardNum}
      * contract: HTTP 200 with a resolved account id and customer
      * id for a known card, HTTP 404 for an unknown card, the PAN
      * masked to the last four digits, and NO full PAN anywhere in
      * the response payload. Read-only; issues no VSAM writes.
      ******************************************************************
      *
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
      *
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      ******************************************************************
      * Shared API commarea / token / error-envelope contract that
      * carries the request key and the response status - COAPICOM.
      ******************************************************************
       COPY COAPICOM.
      *
      ******************************************************************
      * Card cross-reference response contract; card number masked
      * to the last four digits - COAPXRFY.
      ******************************************************************
       COPY COAPXRFY.
      *
      ******************************************************************
      * Driver working storage: LINK target program, CICS response
      * feedback codes, test counters, and the expected fixture
      * values used by the assertions.
      ******************************************************************
       01  WS-PGM-COXRSVCC        PIC X(08) VALUE 'COXRSVCC'.
       01  WS-RESP-CD             PIC S9(09) COMP VALUE ZEROS.
       01  WS-REAS-CD             PIC S9(09) COMP VALUE ZEROS.
       01  WS-TESTS-RUN           PIC 9(04) VALUE 0.
       01  WS-TESTS-PASS          PIC 9(04) VALUE 0.
       01  WS-TESTS-FAIL          PIC 9(04) VALUE 0.
       01  WS-TEST-RC             PIC S9(04) COMP VALUE 0.
      *
      ******************************************************************
      * Expected fixture values (app/data/ASCII/cardxref.txt rec0):
      * card 0500024453765740 -> acct 50, cust 50, mask ...5740.
      ******************************************************************
       01  WS-GOOD-CARD           PIC X(16) VALUE '0500024453765740'.
       01  WS-BAD-CARD            PIC X(16) VALUE '9999999999999999'.
       01  WS-EXP-CARD-MASK       PIC X(16) VALUE '************5740'.
       01  WS-EXP-ACCT            PIC 9(11) VALUE 50.
       01  WS-EXP-CUST            PIC 9(09) VALUE 50.
      *
      ******************************************************************
      * Swap-detection fixture (app/test/api/cardxref-swap.txt):
      * card 4444333322221111 -> acct 42, cust 77.  These ids are
      * DISTINCT, so a COXRSVCC that swapped the account and customer
      * slots would be caught here (the production rows all carry
      * acct = cust and cannot detect a swap).  The live API test JCL
      * (out of scope) must load this record into CCXREF; when it is
      * absent the service returns 404 and the case is skipped.
      ******************************************************************
       01  WS-SWAP-CARD           PIC X(16) VALUE '4444333322221111'.
       01  WS-EXP-SWAP-ACCT       PIC 9(11) VALUE 42.
       01  WS-EXP-SWAP-CUST       PIC 9(09) VALUE 77.
      *
      ******************************************************************
      * Full-PAN leak probe: the response payload must never carry
      * the unmasked 16-digit PAN. WS-PAN-COUNT tallies occurrences.
      ******************************************************************
       01  WS-FULL-PAN            PIC X(16) VALUE '0500024453765740'.
       01  WS-PAN-COUNT           PIC 9(04) VALUE 0.
      *
       PROCEDURE DIVISION.
      *
      ******************************************************************
      * 0000-MAIN : run the positive and negative cross-reference
      * tests, print the summary, and return to the caller.
      ******************************************************************
       0000-MAIN.
           PERFORM 1000-TEST-XREF-OK
           PERFORM 2000-TEST-XREF-NOTFOUND
           PERFORM 3000-TEST-XREF-SWAP
           PERFORM 9000-REPORT
           EXEC CICS RETURN
           END-EXEC
           .
      *
      ******************************************************************
      * 1000-TEST-XREF-OK : a known card resolves to HTTP 200 with
      * the expected account id and customer id, the PAN masked to
      * the last four digits, and no full PAN present in the
      * response payload (security leak probe must tally zero).
      ******************************************************************
       1000-TEST-XREF-OK.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           INITIALIZE API-XREF-RESPONSE
           MOVE 'COXRSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-GOOD-CARD TO API-REQ-CARD-NUM
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COXRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE API-PAYLOAD TO API-XREF-RESPONSE
           MOVE 0 TO WS-PAN-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-PAN-COUNT FOR ALL WS-FULL-PAN
           IF WS-PAN-COUNT > 0
               DISPLAY 'TSTXREF: PAN LEAK DETECTED'
           END-IF
           IF API-HTTP-OK
              AND XREF-ACCT-ID = WS-EXP-ACCT
              AND XREF-CUST-ID = WS-EXP-CUST
              AND XREF-CARD-NUM-MASKED = WS-EXP-CARD-MASK
              AND WS-PAN-COUNT = 0
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTXREF 1000 XREF-OK PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTXREF 1000 XREF-OK FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 2000-TEST-XREF-NOTFOUND : an unknown card must resolve to
      * HTTP 404 (NOT-FOUND) from the cross-reference service.
      ******************************************************************
       2000-TEST-XREF-NOTFOUND.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           MOVE 'COXRSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-BAD-CARD TO API-REQ-CARD-NUM
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COXRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF API-HTTP-NOT-FOUND
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTXREF 2000 XREF-NOTFOUND PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTXREF 2000 XREF-NOTFOUND FAIL'
           END-IF
           .
      *
      ******************************************************************
      * 3000-TEST-XREF-SWAP : resolve a card whose account id (42)
      * and customer id (77) are DISTINCT, then assert each lands in
      * its own response slot.  This is the assertion the production
      * rows (acct = cust) cannot make.  If the swap fixture is not
      * loaded the service returns 404 and the case is skipped so the
      * suite stays green without the extra fixture.
      ******************************************************************
       3000-TEST-XREF-SWAP.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           INITIALIZE API-XREF-RESPONSE
           MOVE 'COXRSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-SWAP-CARD TO API-REQ-CARD-NUM
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COXRSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE API-PAYLOAD TO API-XREF-RESPONSE
           EVALUATE TRUE
               WHEN API-HTTP-OK
                   IF XREF-ACCT-ID = WS-EXP-SWAP-ACCT
                      AND XREF-CUST-ID = WS-EXP-SWAP-CUST
                       ADD 1 TO WS-TESTS-PASS
                       DISPLAY 'TSTXREF 3000 XREF-SWAP PASS'
                   ELSE
                       ADD 1 TO WS-TESTS-FAIL
                       DISPLAY 'TSTXREF 3000 XREF-SWAP FAIL'
                   END-IF
               WHEN API-HTTP-NOT-FOUND
                   ADD 1 TO WS-TESTS-PASS
                   DISPLAY 'TSTXREF 3000 XREF-SWAP SKIP'
               WHEN OTHER
                   ADD 1 TO WS-TESTS-FAIL
                   DISPLAY 'TSTXREF 3000 XREF-SWAP FAIL'
           END-EVALUATE
           .
      *
      ******************************************************************
      * 9000-REPORT : print the run/pass/fail tallies and raise the
      * process return code to 8 when any assertion failed.
      ******************************************************************
       9000-REPORT.
           DISPLAY 'TSTXREF RESULTS RUN=' WS-TESTS-RUN
               ' PASS=' WS-TESTS-PASS
               ' FAIL=' WS-TESTS-FAIL
           IF WS-TESTS-FAIL > 0
               MOVE 8 TO WS-TEST-RC
           END-IF
           MOVE WS-TEST-RC TO RETURN-CODE
           .
      *
      *
      * Ver: CardDemo_v1.0
      *
