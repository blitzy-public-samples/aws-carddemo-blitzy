      *****************************************************************
      * Program     : COACSVCC.CBL
      * Application  : CardDemo
      * Type         : CICS COBOL Program
      * Function     : REST API account inquiry service (read-only)
      *****************************************************************
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
      *****************************************************************
      *
      * COACSVCC is a business-logic service program for the additive,
      * read-only CardDemo REST/JSON API layer.  It is invoked by the
      * router COAPIRTR via EXEC CICS LINK with the shared API-COMMAREA
      * (copybook COAPICOM).  It performs a read-only keyed READ of the
      * ACCTDAT VSAM file by account id and maps the record into the
      * account response contract (COAPACTY / API-ACCT-RESPONSE) for
      * JSON serialization by the router.
      *
      * Endpoint  : GET /carddemo/api/v1/accounts/{acctId}
      * Data flow : COAPIRTR --LINK(API-COMMAREA)--> COACSVCC
      *                       --READ(ACCTDAT)------> VSAM (read-only)
      *
      * The read pattern mirrors COACTVWC 9300-GETACCTDATA-BYACCT.
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COACSVCC.
       AUTHOR. AWS.
       DATE-WRITTEN. JULY 2022.
       DATE-COMPILED. TODAY.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *****************************************************************
      * Program work fields
      *****************************************************************
       01  WS-VARIABLES.
           05  WS-PGMNAME       PIC X(08) VALUE 'COACSVCC'.
           05  WS-ACCTDAT-FILE  PIC X(08) VALUE 'ACCTDAT '.
           05  WS-RESP-CD       PIC S9(09) COMP VALUE ZEROS.
           05  WS-REAS-CD       PIC S9(09) COMP VALUE ZEROS.
           05  WS-ACCT-KEY      PIC 9(11)       VALUE ZEROS.
      *****************************************************************
      * Account master record layout (VSAM ACCTDAT, RECLN 300).
      * Provides 01 ACCOUNT-RECORD.
      *****************************************************************
       COPY CVACT01Y.
      *****************************************************************
      * API account response contract.  Provides 01 API-ACCT-RESPONSE
      * whose 05 field names intentionally mirror ACCOUNT-RECORD, so
      * every shared reference below is qualified with OF ... .
      *****************************************************************
       COPY COAPACTY.

       LINKAGE SECTION.
      *****************************************************************
      * C7 - Raw COMMAREA byte map.  API-COMMAREA (COAPICOM) is
      * overlaid on DFHCOMMAREA via SET ADDRESS in 0000-MAIN so the
      * typed contract addresses the storage the router LINKed and any
      * writes reach the caller (COAPIRTR).  Mirrors the working
      * services (COCUSVCC).  Without this overlay API-COMMAREA is an
      * unaddressed LINKAGE item and the account route is not runnable.
      *****************************************************************
       01  DFHCOMMAREA.
           05  FILLER                  PIC X(01)
               OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.
       COPY COAPICOM.

       PROCEDURE DIVISION.
      *****************************************************************
      * 0000-MAIN : entry point.  The router LINKs here with a
      * populated API-COMMAREA.  When a COMMAREA is present, read the
      * requested account and map the response, then return to caller.
      *****************************************************************
       0000-MAIN.

           IF EIBCALEN >= LENGTH OF API-COMMAREA
              SET ADDRESS OF API-COMMAREA TO ADDRESS OF DFHCOMMAREA
              PERFORM 1000-READ-ACCT
                 THRU 1000-READ-ACCT-EXIT
           END-IF

           EXEC CICS RETURN
           END-EXEC
           .
       0000-MAIN-EXIT.
           EXIT
           .
      *****************************************************************
      * 1000-READ-ACCT : read ACCTDAT by account id (read-only).
      * Sets HTTP 200 on success, 404 when the account is not found,
      * and 500 for any other CICS response.  RESP2 is never exposed
      * to the client on the error paths.
      *****************************************************************
       1000-READ-ACCT.

           MOVE API-REQ-ACCT-ID     TO WS-ACCT-KEY

           EXEC CICS READ
                DATASET   (WS-ACCTDAT-FILE)
                INTO      (ACCOUNT-RECORD)
                LENGTH    (LENGTH OF ACCOUNT-RECORD)
                RIDFLD    (WS-ACCT-KEY)
                KEYLENGTH (LENGTH OF WS-ACCT-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   PERFORM 2000-MAP-RESPONSE
                      THRU 2000-MAP-RESPONSE-EXIT
                   SET API-HTTP-OK          TO TRUE
                   MOVE ZERO                TO API-RETURN-CODE
               WHEN DFHRESP(NOTFND)
                   MOVE SPACES              TO API-PAYLOAD
                   SET API-HTTP-NOT-FOUND   TO TRUE
                   MOVE +4                  TO API-RETURN-CODE
                   MOVE 'NOTFOUND'          TO API-ERR-CODE
                   MOVE 'Account not found' TO API-ERR-MESSAGE
               WHEN OTHER
                   MOVE SPACES              TO API-PAYLOAD
                   SET API-HTTP-SERVER-ERROR TO TRUE
                   MOVE +8                  TO API-RETURN-CODE
                   SET API-ERR-SERVER-ERROR TO TRUE
                   MOVE 'Internal server error'
                                            TO API-ERR-MESSAGE
           END-EVALUATE
           .
       1000-READ-ACCT-EXIT.
           EXIT
           .
      *****************************************************************
      * 2000-MAP-RESPONSE : map ACCOUNT-RECORD into API-ACCT-RESPONSE.
      * Every reference is qualified (OF ...) because the two layouts
      * share field names.  Money fields are moved as-is (S9(10)V99);
      * JSON scaling is done centrally by the router / COJSONUC.
      * ACCT-ADDR-ZIP and FILLER are intentionally not mapped.
      *****************************************************************
       2000-MAP-RESPONSE.

           MOVE ACCT-ID OF ACCOUNT-RECORD
             TO ACCT-ID OF API-ACCT-RESPONSE
           MOVE ACCT-ACTIVE-STATUS OF ACCOUNT-RECORD
             TO ACCT-ACTIVE-STATUS OF API-ACCT-RESPONSE
           MOVE ACCT-CURR-BAL OF ACCOUNT-RECORD
             TO ACCT-CURR-BAL OF API-ACCT-RESPONSE
           MOVE ACCT-CREDIT-LIMIT OF ACCOUNT-RECORD
             TO ACCT-CREDIT-LIMIT OF API-ACCT-RESPONSE
           MOVE ACCT-CASH-CREDIT-LIMIT OF ACCOUNT-RECORD
             TO ACCT-CASH-CREDIT-LIMIT OF API-ACCT-RESPONSE
           MOVE ACCT-CURR-CYC-CREDIT OF ACCOUNT-RECORD
             TO ACCT-CURR-CYC-CREDIT OF API-ACCT-RESPONSE
           MOVE ACCT-CURR-CYC-DEBIT OF ACCOUNT-RECORD
             TO ACCT-CURR-CYC-DEBIT OF API-ACCT-RESPONSE
           MOVE ACCT-OPEN-DATE OF ACCOUNT-RECORD
             TO ACCT-OPEN-DATE OF API-ACCT-RESPONSE
           MOVE ACCT-EXPIRAION-DATE OF ACCOUNT-RECORD
             TO ACCT-EXPIRAION-DATE OF API-ACCT-RESPONSE
           MOVE ACCT-REISSUE-DATE OF ACCOUNT-RECORD
             TO ACCT-REISSUE-DATE OF API-ACCT-RESPONSE
           MOVE ACCT-GROUP-ID OF ACCOUNT-RECORD
             TO ACCT-GROUP-ID OF API-ACCT-RESPONSE

           MOVE API-ACCT-RESPONSE   TO API-PAYLOAD
           .
       2000-MAP-RESPONSE-EXIT.
           EXIT
           .
      *
      * Ver: CardDemo v1.0 - COACSVCC REST/JSON account inquiry service
      *
