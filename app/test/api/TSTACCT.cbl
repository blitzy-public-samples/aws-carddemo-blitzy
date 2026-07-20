      ******************************************************************
      * Program     : TSTACCT.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : API test driver - account inquiry service COACSVCC
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
      *
      * TSTACCT is a standalone CICS COBOL test driver for the additive,
      * read-only CardDemo REST/JSON API layer.  It verifies the account
      * inquiry service COACSVCC, which backs the endpoint
      * GET /carddemo/api/v1/accounts/{acctId}.
      *
      * On a real CICS region the driver issues EXEC CICS LINK to
      * COACSVCC with the shared API-COMMAREA (copybook COAPICOM) and
      * inspects the returned API-RESPONSE-STATUS and, for the success
      * path, the account payload mapped into API-ACCT-RESPONSE
      * (copybook COAPACTY).  The driver performs NO VSAM writes.
      *
      * Test cases:
      *   1000-TEST-ACCT-OK       account 00000000001 -> HTTP 200 and
      *                           verified id/status/balance/dates.
      *   2000-TEST-ACCT-NOTFOUND account 99999999999 -> HTTP 404.
      *
      * Expected values are the verified app/data/ASCII/acctdata.txt
      * rec0 fixture; the balance assertion (+194.00) proves the signed
      * implied-decimal S9(10)V99 field decoded overpunch '00000001940{'
      * with correct value, 2-dp scale and positive sign.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. TSTACCT.
       AUTHOR. AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
      ******************************************************************
      * Shared API COMMAREA contract.  Provides 01 API-COMMAREA with
      * the request keys (API-SERVICE-CODE, API-HTTP-METHOD and
      * API-REQ-ACCT-ID), the API-RESPONSE-STATUS block (API-HTTP-STATUS
      * plus 88 names API-HTTP-OK / API-HTTP-NOT-FOUND) and API-PAYLOAD.
      ******************************************************************
       COPY COAPICOM.
      ******************************************************************
      * API account response contract.  Provides 01 API-ACCT-RESPONSE
      * into which API-PAYLOAD is re-mapped for field-level assertions.
      ******************************************************************
       COPY COAPACTY.
      ******************************************************************
      * Driver work fields and expected fixture literals.
      ******************************************************************
       01  WS-PGM-COACSVCC           PIC X(08) VALUE 'COACSVCC'.
       01  WS-RESP-CD                PIC S9(09) COMP VALUE ZEROS.
       01  WS-REAS-CD                PIC S9(09) COMP VALUE ZEROS.
       01  WS-TESTS-RUN              PIC 9(03)  VALUE ZEROS.
       01  WS-TESTS-PASS             PIC 9(03)  VALUE ZEROS.
       01  WS-TESTS-FAIL             PIC 9(03)  VALUE ZEROS.
       01  WS-TESTS-SKIP             PIC 9(03)  VALUE ZEROS.
       01  WS-TEST-RC                PIC S9(04) VALUE ZEROS.
       01  WS-GOOD-ACCT              PIC 9(11)  VALUE 1.
       01  WS-BAD-ACCT               PIC 9(11)  VALUE 99999999999.
       01  WS-EXP-BAL                PIC S9(10)V99 VALUE +194.00.
       01  WS-EXP-CREDIT             PIC S9(10)V99 VALUE +2020.00.
       01  WS-EXP-CASH               PIC S9(10)V99 VALUE +1020.00.
       01  WS-EXP-CYC-CR             PIC S9(10)V99 VALUE +0.00.
       01  WS-EXP-CYC-DB             PIC S9(10)V99 VALUE +0.00.
      ******************************************************************
      * Negative-money fixture (app/test/api/acctdata-neg.txt) account
      * 00000000888: balance -250.75 and cycle-credit -75.50 exercise
      * the signed S9(10)V99 overpunch decode for NEGATIVE values, a
      * path the all-positive production rows never cover.  The live
      * API test JCL (out of scope) must load this record into ACCTDAT;
      * when absent the service returns 404 and the case is skipped.
      ******************************************************************
       01  WS-NEG-ACCT               PIC 9(11)  VALUE 888.
       01  WS-EXP-NEG-BAL            PIC S9(10)V99 VALUE -250.75.
       01  WS-EXP-NEG-CYC-CR         PIC S9(10)V99 VALUE -75.50.
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
      * 0000-MAIN : entry point.  Run each test case, print the summary
      * report, then return control to the caller.
      ******************************************************************
       0000-MAIN.
           PERFORM 1000-TEST-ACCT-OK
           PERFORM 2000-TEST-ACCT-NOTFOUND
           PERFORM 3000-TEST-ACCT-NEG
           PERFORM 9000-REPORT
           EXEC CICS RETURN
           END-EXEC
           .
      ******************************************************************
      * 1000-TEST-ACCT-OK : good account (00000000001) must return HTTP
      * 200 with the fixture id, active status, +194.00 balance and the
      * open / expiration / reissue dates.  All assertions must hold for
      * the case to PASS.
      ******************************************************************
       1000-TEST-ACCT-OK.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           INITIALIZE API-ACCT-RESPONSE
           MOVE 'COACSVCC'          TO API-SERVICE-CODE
           MOVE 'GET '              TO API-HTTP-METHOD
           MOVE WS-GOOD-ACCT        TO API-REQ-ACCT-ID
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COACSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE API-PAYLOAD         TO API-ACCT-RESPONSE
           IF API-HTTP-OK
              AND ACCT-ID = WS-GOOD-ACCT
              AND ACCT-ACTIVE-STATUS = 'Y'
              AND ACCT-CURR-BAL = WS-EXP-BAL
              AND ACCT-OPEN-DATE = '2014-11-20'
              AND ACCT-EXPIRAION-DATE = '2025-05-20'
              AND ACCT-REISSUE-DATE = '2025-05-20'
              AND ACCT-CREDIT-LIMIT = WS-EXP-CREDIT
              AND ACCT-CASH-CREDIT-LIMIT = WS-EXP-CASH
              AND ACCT-CURR-CYC-CREDIT = WS-EXP-CYC-CR
              AND ACCT-CURR-CYC-DEBIT = WS-EXP-CYC-DB
              AND ACCT-GROUP-ID = SPACES
              ADD 1 TO WS-TESTS-PASS
           ELSE
              ADD 1 TO WS-TESTS-FAIL
              DISPLAY 'TSTACCT: OK-CASE FAILED HTTP=' API-HTTP-STATUS
           END-IF
           .
      ******************************************************************
      * 2000-TEST-ACCT-NOTFOUND : unknown account (99999999999) must
      * return HTTP 404 (API-HTTP-NOT-FOUND).
      ******************************************************************
       2000-TEST-ACCT-NOTFOUND.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           INITIALIZE API-ACCT-RESPONSE
           MOVE 'COACSVCC'          TO API-SERVICE-CODE
           MOVE 'GET '              TO API-HTTP-METHOD
           MOVE WS-BAD-ACCT         TO API-REQ-ACCT-ID
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COACSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF API-HTTP-NOT-FOUND
              ADD 1 TO WS-TESTS-PASS
           ELSE
              ADD 1 TO WS-TESTS-FAIL
              DISPLAY 'TSTACCT: NF-CASE FAILED HTTP=' API-HTTP-STATUS
           END-IF
           .
      ******************************************************************
      * 3000-TEST-ACCT-NEG : account 00000000888 exercises the NEGATIVE
      * signed-money decode path - balance -250.75 and cycle-credit
      * -75.50.  When the additive fixture is not loaded the service
      * returns HTTP 404 and the case is recorded as a release-blocking
      * SKIP (it never counts as PASS), so an absent fixture cannot hide
      * that the negative-money path was never actually exercised.
      ******************************************************************
       3000-TEST-ACCT-NEG.
           ADD 1 TO WS-TESTS-RUN
           INITIALIZE API-COMMAREA
           INITIALIZE API-ACCT-RESPONSE
           MOVE 'COACSVCC'          TO API-SERVICE-CODE
           MOVE 'GET '              TO API-HTTP-METHOD
           MOVE WS-NEG-ACCT         TO API-REQ-ACCT-ID
           EXEC CICS LINK
                PROGRAM   (WS-PGM-COACSVCC)
                COMMAREA  (API-COMMAREA)
                LENGTH    (LENGTH OF API-COMMAREA)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           MOVE API-PAYLOAD         TO API-ACCT-RESPONSE
           EVALUATE TRUE
               WHEN API-HTTP-OK
                   IF ACCT-CURR-BAL = WS-EXP-NEG-BAL
                      AND ACCT-CURR-CYC-CREDIT = WS-EXP-NEG-CYC-CR
                       ADD 1 TO WS-TESTS-PASS
                       DISPLAY 'TSTACCT 3000 ACCT-NEG PASS'
                   ELSE
                       ADD 1 TO WS-TESTS-FAIL
                       DISPLAY 'TSTACCT 3000 ACCT-NEG FAIL'
                   END-IF
               WHEN API-HTTP-NOT-FOUND
                   ADD 1 TO WS-TESTS-SKIP
                   DISPLAY 'TSTACCT 3000 ACCT-NEG '
                           'INCOMPLETE-FIXTURE ABSENT'
               WHEN OTHER
                   ADD 1 TO WS-TESTS-FAIL
                   DISPLAY 'TSTACCT 3000 ACCT-NEG FAIL'
           END-EVALUATE
           .
      ******************************************************************
      * 9000-REPORT : print run/pass/fail/skip counts and the overall
      * verdict, and publish RETURN-CODE for a batch / EXCI / started-
      * transaction harness: 0 = all passed, 4 = a required fixture was
      * absent so a case was skipped (release-blocking - load the
      * fixture and rerun), 8 = an assertion failed.
      ******************************************************************
       9000-REPORT.
           DISPLAY 'TSTACCT RESULTS RUN=' WS-TESTS-RUN
                   ' PASS=' WS-TESTS-PASS
                   ' FAIL=' WS-TESTS-FAIL
                   ' SKIP=' WS-TESTS-SKIP
           IF WS-TESTS-FAIL > ZERO
              MOVE 8 TO WS-TEST-RC
              DISPLAY 'TSTACCT RESULT: FAIL'
           ELSE
              IF WS-TESTS-SKIP > ZERO
                 MOVE 4 TO WS-TEST-RC
                 DISPLAY 'TSTACCT RESULT: INCOMPLETE-FIXTURE ABSENT'
              ELSE
                 MOVE ZERO TO WS-TEST-RC
                 DISPLAY 'TSTACCT RESULT: PASS'
              END-IF
           END-IF
      *    Publish the verdict to the caller COMMAREA when invoked via
      *    LINK/EXCI (EIBCALEN > 0). MAJ-07 / decision-log D37.
           IF EIBCALEN > 0
               MOVE WS-TESTS-RUN   TO DRV-TESTS-RUN
               MOVE WS-TESTS-PASS  TO DRV-TESTS-PASS
               MOVE WS-TESTS-FAIL  TO DRV-TESTS-FAIL
               MOVE 'TSTACCT'      TO DRV-DRIVER-ID
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
