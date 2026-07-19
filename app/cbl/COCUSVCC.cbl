      ******************************************************************
      * Program     : COCUSVCC.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : REST/JSON API customer inquiry service backing
      *               GET /carddemo/api/v1/customers/{custId}.
      *               Performs a READ-ONLY keyed READ of CUSTDAT by
      *               customer id and maps the customer record into the
      *               API customer response contract with SSN and the
      *               government-issued id MINIMIZED (masked). LINKed by
      *               COAPIRTR using API-COMMAREA (COAPICOM). Additive,
      *               read-only, NO BMS / maps / screen I/O.
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
       PROGRAM-ID. COCUSVCC.
       DATE-WRITTEN. July 2024.
       DATE-COMPILED. Today.
      ******************************************************************
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *----------------------------------------------------------------*
      * Program work fields                                            *
      *----------------------------------------------------------------*
       01  WS-VARIABLES.
           05  WS-PGMNAME              PIC X(08) VALUE 'COCUSVCC'.
           05  WS-CUSTDAT-FILE         PIC X(08) VALUE 'CUSTDAT '.
           05  WS-RESP-CD              PIC S9(09) COMP VALUE ZEROS.
           05  WS-REAS-CD              PIC S9(09) COMP VALUE ZEROS.
           05  WS-CUST-KEY             PIC 9(09) VALUE ZEROS.
           05  WS-IDX                  PIC S9(04) COMP VALUE ZEROS.
      *    SSN masking work fields ('XXX-XX-####' or spaces)
           05  WS-SSN-X                PIC 9(09) VALUE ZEROS.
           05  WS-SSN-X-R  REDEFINES WS-SSN-X
                                       PIC X(09).
           05  WS-SSN-LAST4            PIC X(04) VALUE SPACES.
           05  WS-SSN-MASK             PIC X(11) VALUE SPACES.
      *    Government-issued id masking work fields (last 4 chars)
           05  WS-GOVT-WORK            PIC X(20) VALUE SPACES.
           05  WS-GOVT-LEN             PIC S9(04) COMP VALUE ZEROS.
           05  WS-GOVT-LAST4           PIC X(04) JUSTIFIED RIGHT.
      *----------------------------------------------------------------*
      * Customer master record layout (read-only source, RECLN 500)    *
      *----------------------------------------------------------------*
       COPY CVCUS01Y.
      *----------------------------------------------------------------*
      * API customer response contract (masked / PII-minimized target) *
      *----------------------------------------------------------------*
       COPY COAPCUSY.
      *----------------------------------------------------------------*
       LINKAGE SECTION.
      *----------------------------------------------------------------*
      * Raw commarea byte map; API-COMMAREA (COAPICOM) is overlaid on  *
      * it via SET ADDRESS so writes reach the caller (COAPIRTR).      *
      *----------------------------------------------------------------*
       01  DFHCOMMAREA.
           05  FILLER                  PIC X(01)
               OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.
       COPY COAPICOM.
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.
      *----------------------------------------------------------------*
      * 0000-MAIN : verify commarea, dispatch the read, then return.   *
      *----------------------------------------------------------------*
       0000-MAIN.
           IF EIBCALEN > 0
               SET ADDRESS OF API-COMMAREA TO ADDRESS OF DFHCOMMAREA
               PERFORM 1000-READ-CUST
           END-IF
           EXEC CICS RETURN
           END-EXEC
           .
      *----------------------------------------------------------------*
      * 1000-READ-CUST : read-only keyed READ of CUSTDAT; set the      *
      * HTTP status intent. 500 path carries NO RESP2 to the client.   *
      *----------------------------------------------------------------*
       1000-READ-CUST.
           MOVE API-REQ-CUST-ID TO WS-CUST-KEY
           EXEC CICS READ
                DATASET   (WS-CUSTDAT-FILE)
                INTO      (CUSTOMER-RECORD)
                LENGTH    (LENGTH OF CUSTOMER-RECORD)
                RIDFLD    (WS-CUST-KEY)
                KEYLENGTH (LENGTH OF WS-CUST-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   PERFORM 2000-MAP-RESPONSE
                   SET API-HTTP-OK TO TRUE
                   MOVE ZERO TO API-RETURN-CODE
               WHEN DFHRESP(NOTFND)
                   SET API-HTTP-NOT-FOUND TO TRUE
                   MOVE +4 TO API-RETURN-CODE
                   MOVE 'NOTFOUND' TO API-ERR-CODE
                   MOVE 'Customer not found' TO API-ERR-MESSAGE
               WHEN OTHER
                   SET API-HTTP-SERVER-ERROR TO TRUE
                   MOVE +8 TO API-RETURN-CODE
                   MOVE 'SRVERROR' TO API-ERR-CODE
                   MOVE 'Internal server error' TO API-ERR-MESSAGE
           END-EVALUATE
           .
      *----------------------------------------------------------------*
      * 2000-MAP-RESPONSE : qualified field moves plus PII masking.    *
      * Every shared-name reference is QUALIFIED with OF CUSTOMER-     *
      * RECORD / OF API-CUST-RESPONSE to avoid ambiguity.              *
      *----------------------------------------------------------------*
       2000-MAP-RESPONSE.
           INITIALIZE API-CUST-RESPONSE
           MOVE CUST-ID OF CUSTOMER-RECORD
             TO CUST-ID OF API-CUST-RESPONSE
           MOVE CUST-FIRST-NAME OF CUSTOMER-RECORD
             TO CUST-FIRST-NAME OF API-CUST-RESPONSE
           MOVE CUST-MIDDLE-NAME OF CUSTOMER-RECORD
             TO CUST-MIDDLE-NAME OF API-CUST-RESPONSE
           MOVE CUST-LAST-NAME OF CUSTOMER-RECORD
             TO CUST-LAST-NAME OF API-CUST-RESPONSE
           MOVE CUST-ADDR-LINE-1 OF CUSTOMER-RECORD
             TO CUST-ADDR-LINE-1 OF API-CUST-RESPONSE
           MOVE CUST-ADDR-LINE-2 OF CUSTOMER-RECORD
             TO CUST-ADDR-LINE-2 OF API-CUST-RESPONSE
           MOVE CUST-ADDR-LINE-3 OF CUSTOMER-RECORD
             TO CUST-ADDR-LINE-3 OF API-CUST-RESPONSE
           MOVE CUST-ADDR-STATE-CD OF CUSTOMER-RECORD
             TO CUST-ADDR-STATE-CD OF API-CUST-RESPONSE
           MOVE CUST-ADDR-COUNTRY-CD OF CUSTOMER-RECORD
             TO CUST-ADDR-COUNTRY-CD OF API-CUST-RESPONSE
           MOVE CUST-ADDR-ZIP OF CUSTOMER-RECORD
             TO CUST-ADDR-ZIP OF API-CUST-RESPONSE
           MOVE CUST-PHONE-NUM-1 OF CUSTOMER-RECORD
             TO CUST-PHONE-NUM-1 OF API-CUST-RESPONSE
           MOVE CUST-PHONE-NUM-2 OF CUSTOMER-RECORD
             TO CUST-PHONE-NUM-2 OF API-CUST-RESPONSE
           MOVE CUST-DOB-YYYY-MM-DD OF CUSTOMER-RECORD
             TO CUST-DOB-YYYY-MM-DD OF API-CUST-RESPONSE
           MOVE CUST-PRI-CARD-HOLDER-IND OF CUSTOMER-RECORD
             TO CUST-PRI-CARD-HOLDER-IND OF API-CUST-RESPONSE
           MOVE CUST-FICO-CREDIT-SCORE OF CUSTOMER-RECORD
             TO CUST-FICO-CREDIT-SCORE OF API-CUST-RESPONSE
           PERFORM 2100-MASK-SSN
           PERFORM 2200-MASK-GOVTID
           MOVE API-CUST-RESPONSE TO API-PAYLOAD
           .
      *----------------------------------------------------------------*
      * 2100-MASK-SSN : emit 'XXX-XX-####' (last 4 only) or spaces.    *
      * The full SSN is only ever a MOVE SOURCE into a work field.     *
      *----------------------------------------------------------------*
       2100-MASK-SSN.
           MOVE CUST-SSN OF CUSTOMER-RECORD TO WS-SSN-X
           IF WS-SSN-X-R = SPACES
              OR WS-SSN-X-R = '000000000'
               MOVE SPACES TO CUST-SSN-MASKED OF API-CUST-RESPONSE
           ELSE
               MOVE WS-SSN-X-R(6:4) TO WS-SSN-LAST4
               MOVE SPACES TO WS-SSN-MASK
               MOVE 'XXX-XX-' TO WS-SSN-MASK(1:7)
               MOVE WS-SSN-LAST4 TO WS-SSN-MASK(8:4)
               MOVE WS-SSN-MASK
                 TO CUST-SSN-MASKED OF API-CUST-RESPONSE
           END-IF
           .
      *----------------------------------------------------------------*
      * 2200-MASK-GOVTID : emit the last 4 significant characters of   *
      * the government-issued id (X(04)); fewer than 4 are right-      *
      * justified with leading spaces; all-blank yields spaces.        *
      * The full govt id is only ever a MOVE SOURCE into a work field. *
      *----------------------------------------------------------------*
       2200-MASK-GOVTID.
           MOVE SPACES TO WS-GOVT-LAST4
           MOVE CUST-GOVT-ISSUED-ID OF CUSTOMER-RECORD
             TO WS-GOVT-WORK
           MOVE ZERO TO WS-GOVT-LEN
           PERFORM VARYING WS-IDX FROM LENGTH OF WS-GOVT-WORK
                   BY -1 UNTIL WS-IDX < 1 OR WS-GOVT-LEN > ZERO
               IF WS-GOVT-WORK(WS-IDX:1) NOT = SPACE
                   MOVE WS-IDX TO WS-GOVT-LEN
               END-IF
           END-PERFORM
           EVALUATE TRUE
               WHEN WS-GOVT-LEN = ZERO
                   CONTINUE
               WHEN WS-GOVT-LEN >= 4
                   MOVE WS-GOVT-WORK(WS-GOVT-LEN - 3:4)
                     TO WS-GOVT-LAST4
               WHEN OTHER
                   MOVE WS-GOVT-WORK(1:WS-GOVT-LEN)
                     TO WS-GOVT-LAST4
           END-EVALUATE
           MOVE WS-GOVT-LAST4
             TO CUST-GOVT-ID-MASKED OF API-CUST-RESPONSE
           .
      ******************************************************************
      * Ver: CardDemo REST/JSON API - COCUSVCC v1.0
      ******************************************************************
