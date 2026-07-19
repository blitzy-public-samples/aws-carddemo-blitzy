      ******************************************************************
      * Program     : TSTCUST.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : API test driver - COCUSVCC customer inquiry
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
       PROGRAM-ID. TSTCUST.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *----------------------------------------------------------------*
      * API shared COMMAREA / channel contract (COAPICOM)              *
      *----------------------------------------------------------------*
       COPY COAPICOM.
      *----------------------------------------------------------------*
      * API customer response contract - masked / PII-minimized        *
      *----------------------------------------------------------------*
       COPY COAPCUSY.
      *----------------------------------------------------------------*
      * Driver control fields                                          *
      *----------------------------------------------------------------*
       01  WS-PGM-COCUSVCC      PIC X(08) VALUE 'COCUSVCC'.
       01  WS-RESP-CD           PIC S9(09) COMP VALUE ZEROS.
       01  WS-REAS-CD           PIC S9(09) COMP VALUE ZEROS.
       01  WS-TESTS-RUN         PIC 9(04) VALUE ZEROS.
       01  WS-TESTS-PASS        PIC 9(04) VALUE ZEROS.
       01  WS-TESTS-FAIL        PIC 9(04) VALUE ZEROS.
       01  WS-TEST-RC           PIC S9(04) COMP VALUE ZEROS.
       01  WS-TEST-FLAG         PIC X(01) VALUE 'Y'.
           88  WS-TEST-OK       VALUE 'Y'.
           88  WS-TEST-BAD      VALUE 'N'.
      *----------------------------------------------------------------*
      * Expected values for positive assertions                        *
      *----------------------------------------------------------------*
       01  WS-GOOD-CUST         PIC 9(09) VALUE 1.
       01  WS-BAD-CUST          PIC 9(09) VALUE 999999999.
       01  WS-EXP-SSN-MASK      PIC X(11) VALUE 'XXX-XX-3888'.
       01  WS-EXP-GOVT-MASK     PIC X(04) VALUE '8437'.
       01  WS-EXP-FIRST         PIC X(25) VALUE 'Immanuel'.
       01  WS-EXP-LAST          PIC X(25) VALUE 'Kessler'.
       01  WS-EXP-DOB           PIC X(10) VALUE '1961-06-08'.
       01  WS-EXP-FICO          PIC 9(03) VALUE 274.
      *----------------------------------------------------------------*
      * PII leak-probe literals + tally counters (full PII must never  *
      * appear anywhere in the response; expected tally is ZERO)       *
      *----------------------------------------------------------------*
       01  WS-FULL-SSN          PIC X(09) VALUE '020973888'.
       01  WS-FULL-GOVT         PIC X(20) VALUE '00000000000049368437'.
       01  WS-FULL-EFT          PIC X(10) VALUE '0053581756'.
       01  WS-SSN-COUNT         PIC 9(04) VALUE 0.
       01  WS-GOVT-COUNT        PIC 9(04) VALUE 0.
       01  WS-EFT-COUNT         PIC 9(04) VALUE 0.

       PROCEDURE DIVISION.
      *----------------------------------------------------------------*
      * 0000-MAIN : run each test, emit the summary, then RETURN.      *
      *----------------------------------------------------------------*
       0000-MAIN.
           PERFORM 1000-TEST-CUST-OK
           PERFORM 2000-TEST-CUST-NOTFOUND
           PERFORM 9000-REPORT
           EXEC CICS RETURN
           END-EXEC
           .
      *----------------------------------------------------------------*
      * 1000-TEST-CUST-OK : customer 000000001 -> HTTP 200, masked SSN *
      * and government id, and NO full PII present in the response.    *
      *----------------------------------------------------------------*
       1000-TEST-CUST-OK.
           ADD 1 TO WS-TESTS-RUN
           SET WS-TEST-OK TO TRUE
           INITIALIZE API-COMMAREA
           INITIALIZE API-CUST-RESPONSE
           MOVE 'COCUSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-GOOD-CUST TO API-REQ-CUST-ID
           EXEC CICS LINK
               PROGRAM   (WS-PGM-COCUSVCC)
               COMMAREA  (API-COMMAREA)
               LENGTH    (LENGTH OF API-COMMAREA)
               RESP      (WS-RESP-CD)
               RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE API-PAYLOAD TO API-CUST-RESPONSE
      *    Assertion : HTTP status intent is 200 OK
           IF NOT API-HTTP-OK
               SET WS-TEST-BAD TO TRUE
           END-IF
      *    Assertion : customer id is echoed back
           IF CUST-ID NOT = WS-GOOD-CUST
               SET WS-TEST-BAD TO TRUE
           END-IF
      *    Assertion : SSN is masked exactly (last 4 only)
           IF CUST-SSN-MASKED NOT = WS-EXP-SSN-MASK
               SET WS-TEST-BAD TO TRUE
           END-IF
      *    Assertion : government id is masked to last 4
           IF CUST-GOVT-ID-MASKED NOT = WS-EXP-GOVT-MASK
               SET WS-TEST-BAD TO TRUE
           END-IF
      *    Assertion : demographic fields decode correctly
           IF CUST-FIRST-NAME NOT = WS-EXP-FIRST
               SET WS-TEST-BAD TO TRUE
           END-IF
           IF CUST-LAST-NAME NOT = WS-EXP-LAST
               SET WS-TEST-BAD TO TRUE
           END-IF
           IF CUST-DOB-YYYY-MM-DD NOT = WS-EXP-DOB
               SET WS-TEST-BAD TO TRUE
           END-IF
           IF CUST-FICO-CREDIT-SCORE NOT = WS-EXP-FICO
               SET WS-TEST-BAD TO TRUE
           END-IF
      *    Security : the FULL SSN must not appear anywhere
           MOVE ZERO TO WS-SSN-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-SSN-COUNT FOR ALL WS-FULL-SSN
           INSPECT API-CUST-RESPONSE
               TALLYING WS-SSN-COUNT FOR ALL WS-FULL-SSN
           IF WS-SSN-COUNT > ZERO
               SET WS-TEST-BAD TO TRUE
               DISPLAY 'TSTCUST: PII LEAK DETECTED'
           END-IF
      *    Security : the FULL government id must not appear anywhere
           MOVE ZERO TO WS-GOVT-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-GOVT-COUNT FOR ALL WS-FULL-GOVT
           INSPECT API-CUST-RESPONSE
               TALLYING WS-GOVT-COUNT FOR ALL WS-FULL-GOVT
           IF WS-GOVT-COUNT > ZERO
               SET WS-TEST-BAD TO TRUE
               DISPLAY 'TSTCUST: PII LEAK DETECTED'
           END-IF
      *    Security : the EFT account id must not appear anywhere
           MOVE ZERO TO WS-EFT-COUNT
           INSPECT API-PAYLOAD
               TALLYING WS-EFT-COUNT FOR ALL WS-FULL-EFT
           INSPECT API-CUST-RESPONSE
               TALLYING WS-EFT-COUNT FOR ALL WS-FULL-EFT
           IF WS-EFT-COUNT > ZERO
               SET WS-TEST-BAD TO TRUE
               DISPLAY 'TSTCUST: PII LEAK DETECTED'
           END-IF
      *    Tally the outcome
           IF WS-TEST-OK
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTCUST T1-OK-200 PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTCUST T1-OK-200 FAIL'
           END-IF
           .
      *----------------------------------------------------------------*
      * 2000-TEST-CUST-NOTFOUND : unknown customer -> HTTP 404.        *
      *----------------------------------------------------------------*
       2000-TEST-CUST-NOTFOUND.
           ADD 1 TO WS-TESTS-RUN
           SET WS-TEST-OK TO TRUE
           INITIALIZE API-COMMAREA
           MOVE 'COCUSVCC' TO API-SERVICE-CODE
           MOVE 'GET ' TO API-HTTP-METHOD
           MOVE WS-BAD-CUST TO API-REQ-CUST-ID
           EXEC CICS LINK
               PROGRAM   (WS-PGM-COCUSVCC)
               COMMAREA  (API-COMMAREA)
               LENGTH    (LENGTH OF API-COMMAREA)
               RESP      (WS-RESP-CD)
               RESP2     (WS-REAS-CD)
           END-EXEC
      *    Assertion : HTTP status intent is 404 NOT FOUND
           IF NOT API-HTTP-NOT-FOUND
               SET WS-TEST-BAD TO TRUE
           END-IF
           IF WS-TEST-OK
               ADD 1 TO WS-TESTS-PASS
               DISPLAY 'TSTCUST T2-NOTFOUND PASS'
           ELSE
               ADD 1 TO WS-TESTS-FAIL
               DISPLAY 'TSTCUST T2-NOTFOUND FAIL'
           END-IF
           .
      *----------------------------------------------------------------*
      * 9000-REPORT : print run/pass/fail totals; RC=8 on any failure. *
      * SSN and government id are NEVER included in any DISPLAY.       *
      *----------------------------------------------------------------*
       9000-REPORT.
           DISPLAY 'TSTCUST RESULTS RUN=' WS-TESTS-RUN
               ' PASS=' WS-TESTS-PASS ' FAIL=' WS-TESTS-FAIL
           IF WS-TESTS-FAIL > ZERO
               MOVE 8 TO WS-TEST-RC
           ELSE
               MOVE ZERO TO WS-TEST-RC
           END-IF
           MOVE WS-TEST-RC TO RETURN-CODE
           .
      *
      * Ver: CardDemo_v1.0
      *
