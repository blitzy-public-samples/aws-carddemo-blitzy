      ******************************************************************
      * Program     : TSTAUTH.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : API test driver - authentication service COAPISEC
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
      ******************************************************************
      * PURPOSE
      *   TSTAUTH is a net-new, standalone CICS COBOL driver that
      *   structurally verifies the authentication service COAPISEC of
      *   the CardDemo REST/JSON API layer. It issues EXEC CICS LINK to
      *   COAPISEC over the shared API-COMMAREA (copybook COAPICOM) for
      *   both sign-on (token issue) and token-validate flows and
      *   asserts the HTTP status intent, the issued/validated identity
      *   and - as a first-class security assertion - that the sign-on
      *   password is never echoed anywhere in the response.
      *
      * DATA ACCESS
      *   READ-ONLY. The driver performs NO WRITE / REWRITE / DELETE
      *   against any VSAM dataset; it only drives COAPISEC, whose own
      *   token-registry TSQ I/O is internal to that program.
      *
      * SECURITY
      *   The password literal lives only in WORKING-STORAGE and in the
      *   request marshalled to the service. It is NEVER placed on a
      *   DISPLAY. 1000-TEST-SIGNON-OK inspects the whole response for
      *   the password and fails the run if a single occurrence exists.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. TSTAUTH.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

      * Shared API contract: 01 API-COMMAREA (service COMMAREA).
       COPY COAPICOM.

      * Sign-on request/response layouts (01 API-SIGNON-REQUEST and
      * 01 API-SIGNON-RESPONSE). Password is in the request only.
       COPY COAPSGNY.

      * Name of the authentication service program being driven.
       01 WS-PGM-COAPISEC        PIC X(08) VALUE 'COAPISEC'.

      * CICS RESP / RESP2 feedback for each EXEC CICS LINK.
       01 WS-RESP-CD             PIC S9(09) COMP VALUE ZEROS.
       01 WS-REAS-CD             PIC S9(09) COMP VALUE ZEROS.

      * Test bookkeeping counters and driver return code.
       01 WS-TESTS-RUN           PIC 9(03) VALUE 0.
       01 WS-TESTS-PASS          PIC 9(03) VALUE 0.
       01 WS-TESTS-FAIL          PIC 9(03) VALUE 0.
       01 WS-TEST-RC             PIC S9(04) VALUE 0.

      * Single-check outcome flag consumed by 8000-CHECK.
       01 WS-CHECK-RESULT        PIC X(01) VALUE 'N'.
           88 CHECK-PASSED       VALUE 'Y'.

      * Diagnostic text displayed only on a failed check.
       01 WS-FAIL-MSG            PIC X(50) VALUE SPACES.

      * INSPECT TALLYING counter for the password-leak assertion.
       01 WS-PWD-COUNT           PIC 9(04) VALUE 0.

      * Bearer token captured from 1000 for reuse in 3000.
       01 WS-SAVED-TOKEN         PIC X(64) VALUE SPACES.

      * Seeded credential fixtures (USRSEC): USER0001 / PASSWORD.
       01 WS-GOOD-USER           PIC X(08) VALUE 'USER0001'.
       01 WS-GOOD-PWD            PIC X(08) VALUE 'PASSWORD'.
       01 WS-BAD-PWD             PIC X(08) VALUE 'BADPASS'.
       01 WS-BAD-USER            PIC X(08) VALUE 'NOSUCHUS'.

      *----------------------------------------------------------------*
      *                     PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.

      *----------------------------------------------------------------*
      *                     0000-MAIN
      *----------------------------------------------------------------*
      * Drive every authentication test in order, publish the summary
      * and hand control back. This program is intended to be started
      * as its own task, so a plain EXEC CICS RETURN (no TRANSID) ends
      * the driver cleanly on a live CICS region.
       0000-MAIN.

           PERFORM 1000-TEST-SIGNON-OK
           PERFORM 2000-TEST-SIGNON-BAD-PWD
           PERFORM 3000-TEST-VALIDATE-OK
           PERFORM 4000-TEST-VALIDATE-BAD
           PERFORM 9000-REPORT

           EXEC CICS RETURN
           END-EXEC.

      *----------------------------------------------------------------*
      *                     1000-TEST-SIGNON-OK
      *----------------------------------------------------------------*
      * Valid credentials must yield HTTP 200 with an issued bearer
      * token, the echoed regular-user type, and NO password anywhere
      * in the response. Two independent checks are recorded.
       1000-TEST-SIGNON-OK.

           INITIALIZE API-COMMAREA
           INITIALIZE API-SIGNON-REQUEST API-SIGNON-RESPONSE

           MOVE 'SIGNON  '           TO API-SERVICE-CODE
           MOVE 'POST'               TO API-HTTP-METHOD
           MOVE WS-GOOD-USER         TO SGNI-USER-ID
           MOVE WS-GOOD-PWD          TO SGNI-PASSWORD

      * Router role: place the request into the shared payload.
           MOVE API-SIGNON-REQUEST   TO API-PAYLOAD

           EXEC CICS LINK
                     PROGRAM  (WS-PGM-COAPISEC)
                     COMMAREA (API-COMMAREA)
                     LENGTH   (LENGTH OF API-COMMAREA)
                     RESP     (WS-RESP-CD)
                     RESP2    (WS-REAS-CD)
           END-EXEC

      * Un-marshal the response payload back into the typed layout.
           MOVE API-PAYLOAD          TO API-SIGNON-RESPONSE
           MOVE API-TOKEN-VALUE      TO WS-SAVED-TOKEN

      * Check 1: 200 + non-empty token + regular-user type.
           MOVE 'N'                  TO WS-CHECK-RESULT
           IF  API-HTTP-OK
           AND SGNO-TOKEN      NOT = SPACES
           AND SGNO-TOKEN      NOT = LOW-VALUES
           AND API-TOKEN-VALUE NOT = SPACES
           AND SGNO-USER-REGULAR
               MOVE 'Y'               TO WS-CHECK-RESULT
           END-IF
           MOVE 'SIGNON OK: EXPECTED 200 WITH ISSUED TOKEN'
                                     TO WS-FAIL-MSG
           PERFORM 8000-CHECK

      * Check 2 (SECURITY): the password must not appear anywhere in
      * the response - neither in API-PAYLOAD nor in the API-TOKEN.
           MOVE ZERO                 TO WS-PWD-COUNT
           INSPECT API-PAYLOAD
                   TALLYING WS-PWD-COUNT FOR ALL WS-GOOD-PWD
           INSPECT API-TOKEN
                   TALLYING WS-PWD-COUNT FOR ALL WS-GOOD-PWD
           MOVE 'N'                  TO WS-CHECK-RESULT
           IF WS-PWD-COUNT = ZERO
               MOVE 'Y'               TO WS-CHECK-RESULT
           END-IF
           MOVE 'AUTH: PASSWORD LEAKED IN RESPONSE'
                                     TO WS-FAIL-MSG
           PERFORM 8000-CHECK.

      *----------------------------------------------------------------*
      *                     2000-TEST-SIGNON-BAD-PWD
      *----------------------------------------------------------------*
      * A wrong password and an unknown user must both fail as a
      * generic HTTP 401 with no token issued (no user enumeration).
       2000-TEST-SIGNON-BAD-PWD.

      * Negative 1: correct user, wrong password.
           INITIALIZE API-COMMAREA
           INITIALIZE API-SIGNON-REQUEST API-SIGNON-RESPONSE
           MOVE 'SIGNON  '           TO API-SERVICE-CODE
           MOVE 'POST'               TO API-HTTP-METHOD
           MOVE WS-GOOD-USER         TO SGNI-USER-ID
           MOVE WS-BAD-PWD           TO SGNI-PASSWORD
           MOVE API-SIGNON-REQUEST   TO API-PAYLOAD
           EXEC CICS LINK
                     PROGRAM  (WS-PGM-COAPISEC)
                     COMMAREA (API-COMMAREA)
                     LENGTH   (LENGTH OF API-COMMAREA)
                     RESP     (WS-RESP-CD)
                     RESP2    (WS-REAS-CD)
           END-EXEC
           MOVE API-PAYLOAD          TO API-SIGNON-RESPONSE
           MOVE 'N'                  TO WS-CHECK-RESULT
           IF  API-HTTP-UNAUTHORIZED
           AND SGNO-TOKEN = SPACES
               MOVE 'Y'               TO WS-CHECK-RESULT
           END-IF
           MOVE 'BAD PWD: EXPECTED 401 AND NO TOKEN'
                                     TO WS-FAIL-MSG
           PERFORM 8000-CHECK.

      * Negative 2: unknown user id.
           INITIALIZE API-COMMAREA
           INITIALIZE API-SIGNON-REQUEST API-SIGNON-RESPONSE
           MOVE 'SIGNON  '           TO API-SERVICE-CODE
           MOVE 'POST'               TO API-HTTP-METHOD
           MOVE WS-BAD-USER          TO SGNI-USER-ID
           MOVE WS-GOOD-PWD          TO SGNI-PASSWORD
           MOVE API-SIGNON-REQUEST   TO API-PAYLOAD
           EXEC CICS LINK
                     PROGRAM  (WS-PGM-COAPISEC)
                     COMMAREA (API-COMMAREA)
                     LENGTH   (LENGTH OF API-COMMAREA)
                     RESP     (WS-RESP-CD)
                     RESP2    (WS-REAS-CD)
           END-EXEC
           MOVE 'N'                  TO WS-CHECK-RESULT
           IF API-HTTP-UNAUTHORIZED
               MOVE 'Y'               TO WS-CHECK-RESULT
           END-IF
           MOVE 'UNKNOWN USER: EXPECTED 401'
                                     TO WS-FAIL-MSG
           PERFORM 8000-CHECK.

      *----------------------------------------------------------------*
      *                     3000-TEST-VALIDATE-OK
      *----------------------------------------------------------------*
      * Presenting the token issued in 1000 must validate as HTTP 200
      * and return the identity bound to that token.
       3000-TEST-VALIDATE-OK.

           INITIALIZE API-COMMAREA
           MOVE 'VALIDATE'           TO API-SERVICE-CODE
           MOVE 'GET'                TO API-HTTP-METHOD
           MOVE WS-SAVED-TOKEN       TO API-TOKEN-VALUE
           EXEC CICS LINK
                     PROGRAM  (WS-PGM-COAPISEC)
                     COMMAREA (API-COMMAREA)
                     LENGTH   (LENGTH OF API-COMMAREA)
                     RESP     (WS-RESP-CD)
                     RESP2    (WS-REAS-CD)
           END-EXEC
           MOVE 'N'                  TO WS-CHECK-RESULT
           IF  API-HTTP-OK
           AND API-TOKEN-USER-ID   = WS-GOOD-USER
           AND API-TOKEN-USER-TYPE = 'U'
               MOVE 'Y'               TO WS-CHECK-RESULT
           END-IF
           MOVE 'VALIDATE OK: EXPECTED 200 AND IDENTITY'
                                     TO WS-FAIL-MSG
           PERFORM 8000-CHECK.

      *----------------------------------------------------------------*
      *                     4000-TEST-VALIDATE-BAD
      *----------------------------------------------------------------*
      * A forged bearer token must be rejected as HTTP 401.
       4000-TEST-VALIDATE-BAD.

           INITIALIZE API-COMMAREA
           MOVE 'VALIDATE'           TO API-SERVICE-CODE
           MOVE 'GET'                TO API-HTTP-METHOD
           MOVE ALL 'X'              TO API-TOKEN-VALUE
           EXEC CICS LINK
                     PROGRAM  (WS-PGM-COAPISEC)
                     COMMAREA (API-COMMAREA)
                     LENGTH   (LENGTH OF API-COMMAREA)
                     RESP     (WS-RESP-CD)
                     RESP2    (WS-REAS-CD)
           END-EXEC
           MOVE 'N'                  TO WS-CHECK-RESULT
           IF API-HTTP-UNAUTHORIZED
               MOVE 'Y'               TO WS-CHECK-RESULT
           END-IF
           MOVE 'FORGED TOKEN: EXPECTED 401'
                                     TO WS-FAIL-MSG
           PERFORM 8000-CHECK.

      *----------------------------------------------------------------*
      *                     8000-CHECK
      *----------------------------------------------------------------*
      * Bookkeeping helper: count the check, and on failure emit a
      * diagnostic line. The password value is never displayed.
       8000-CHECK.

           ADD 1                     TO WS-TESTS-RUN
           IF CHECK-PASSED
               ADD 1                 TO WS-TESTS-PASS
           ELSE
               ADD 1                 TO WS-TESTS-FAIL
               DISPLAY 'TSTAUTH FAIL: ' WS-FAIL-MSG
           END-IF.

      *----------------------------------------------------------------*
      *                     9000-REPORT
      *----------------------------------------------------------------*
      * Emit a machine-greppable summary and an overall verdict, and
      * set the driver return code to 8 when any check failed.
       9000-REPORT.

           DISPLAY 'TSTAUTH RESULTS'
                   ' RUN='  WS-TESTS-RUN
                   ' PASS=' WS-TESTS-PASS
                   ' FAIL=' WS-TESTS-FAIL
           IF WS-TESTS-FAIL > ZERO
               MOVE 8 TO WS-TEST-RC
               DISPLAY 'TSTAUTH RESULT: FAIL'
           ELSE
               DISPLAY 'TSTAUTH RESULT: PASS'
           END-IF.
      *
      * Ver: CardDemo_v1.0
      *
