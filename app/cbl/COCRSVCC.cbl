      *****************************************************************
      * Program:     COCRSVCC.CBL                                     *
      * Layer:       Business logic                                   *
      * Function:    REST API card inquiry service (read-only).       *
      *              Reads CARDDAT by card number and returns the     *
      *              card detail with the card number masked to the   *
      *              last four digits. Sensitive security data is     *
      *              never referenced, moved, logged, or serialized.  *
      * Endpoint:    GET /carddemo/api/v1/cards/{cardNum}             *
      * Linked by:   COAPIRTR (EXEC CICS LINK, COMMAREA COAPICOM)     *
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
       IDENTIFICATION DIVISION.
       PROGRAM-ID.
           COCRSVCC.
       DATE-WRITTEN.
           July 2025.
       DATE-COMPILED.
           Today.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *****************************************************************
      * Program constants and CICS response fields                    *
      *****************************************************************
       01  WS-VARIABLES.
           05  WS-PGMNAME            PIC X(08) VALUE 'COCRSVCC'.
           05  WS-CARDDAT-FILE       PIC X(08) VALUE 'CARDDAT '.
           05  WS-RESP-CD            PIC S9(09) COMP VALUE ZEROS.
           05  WS-REAS-CD            PIC S9(09) COMP VALUE ZEROS.
           05  WS-CARD-KEY           PIC X(16) VALUE SPACES.
      *****************************************************************
      * Work fields for the PAN masking routine                       *
      *****************************************************************
       01  WS-MASK-WORK.
           05  WS-MASKED-PAN         PIC X(16) VALUE SPACES.
           05  WS-PAN-WORK           PIC X(16) VALUE SPACES.
           05  WS-PAN-LAST4          PIC X(04) VALUE '0000'.
           05  WS-PAN-LEN            PIC S9(04) COMP VALUE ZERO.
           05  WS-PAN-IDX            PIC S9(04) COMP VALUE ZERO.
           05  WS-PAN-START          PIC S9(04) COMP VALUE ZERO.

      * Card master record layout (read-only source, RECLN 150)
       COPY CVACT02Y.

      * API card response layout (masked PAN; no security field)
       COPY COAPCRDY.

       LINKAGE SECTION.
      * Shared REST/JSON API COMMAREA contract (first 01 = COMMAREA)
       COPY COAPICOM.

       PROCEDURE DIVISION.
      *****************************************************************
      * 0000-MAIN : entry point invoked via EXEC CICS LINK.           *
      * Guards the COMMAREA length, performs the read, returns.       *
      *****************************************************************
       0000-MAIN.
           IF EIBCALEN > 0
               PERFORM 1000-READ-CARD
           END-IF

           EXEC CICS RETURN
           END-EXEC
           .

      *****************************************************************
      * 1000-READ-CARD : read-only keyed READ of CARDDAT and map      *
      * the HTTP status from the CICS response code.                  *
      *****************************************************************
       1000-READ-CARD.
           MOVE API-REQ-CARD-NUM     TO WS-CARD-KEY

           EXEC CICS READ
                DATASET   (WS-CARDDAT-FILE)
                INTO      (CARD-RECORD)
                LENGTH    (LENGTH OF CARD-RECORD)
                RIDFLD    (WS-CARD-KEY)
                KEYLENGTH (LENGTH OF WS-CARD-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   PERFORM 2000-MAP-RESPONSE
                   SET API-HTTP-OK           TO TRUE
                   MOVE ZERO                 TO API-RETURN-CODE
               WHEN DFHRESP(NOTFND)
                   SET API-HTTP-NOT-FOUND    TO TRUE
                   MOVE +4                   TO API-RETURN-CODE
                   MOVE 'NOTFOUND'           TO API-ERR-CODE
                   MOVE 'Card not found'     TO API-ERR-MESSAGE
               WHEN OTHER
                   SET API-HTTP-SERVER-ERROR TO TRUE
                   MOVE +8                   TO API-RETURN-CODE
                   MOVE 'INTERNAL'           TO API-ERR-CODE
                   MOVE 'Internal server error'
                       TO API-ERR-MESSAGE
           END-EVALUATE
           .

      *****************************************************************
      * 2000-MAP-RESPONSE : map the card record to the response       *
      * copybook. All shared field names are qualified. The card      *
      * number is masked; the security field is never referenced.     *
      *****************************************************************
       2000-MAP-RESPONSE.
           INITIALIZE API-CARD-RESPONSE

           PERFORM 2100-MASK-PAN

           MOVE CARD-ACCT-ID OF CARD-RECORD
             TO CARD-ACCT-ID OF API-CARD-RESPONSE
           MOVE CARD-EMBOSSED-NAME OF CARD-RECORD
             TO CARD-EMBOSSED-NAME OF API-CARD-RESPONSE
           MOVE CARD-EXPIRAION-DATE OF CARD-RECORD
             TO CARD-EXPIRAION-DATE OF API-CARD-RESPONSE
           MOVE CARD-ACTIVE-STATUS OF CARD-RECORD
             TO CARD-ACTIVE-STATUS OF API-CARD-RESPONSE

           MOVE API-CARD-RESPONSE    TO API-PAYLOAD
           .

      *****************************************************************
      * 2100-MASK-PAN : build masked card number = 12 asterisks       *
      * followed by the last four significant digits (X(16)).         *
      * Matches the OpenAPI MaskedPan pattern used by every           *
      * endpoint so masked values look identical across the API.      *
      *****************************************************************
       2100-MASK-PAN.
           MOVE CARD-NUM OF CARD-RECORD TO WS-PAN-WORK
           MOVE ZERO                    TO WS-PAN-LEN

           PERFORM VARYING WS-PAN-IDX FROM 16 BY -1
                   UNTIL WS-PAN-IDX < 1
                      OR WS-PAN-LEN > 0
               IF WS-PAN-WORK (WS-PAN-IDX:1) NOT = SPACE
                   MOVE WS-PAN-IDX      TO WS-PAN-LEN
               END-IF
           END-PERFORM

           MOVE '0000'                  TO WS-PAN-LAST4

           EVALUATE TRUE
               WHEN WS-PAN-LEN >= 4
                   COMPUTE WS-PAN-START = WS-PAN-LEN - 3
                   MOVE WS-PAN-WORK (WS-PAN-START:4)
                       TO WS-PAN-LAST4
               WHEN WS-PAN-LEN > 0
                   COMPUTE WS-PAN-START = 5 - WS-PAN-LEN
                   MOVE WS-PAN-WORK (1:WS-PAN-LEN)
                       TO WS-PAN-LAST4 (WS-PAN-START:WS-PAN-LEN)
               WHEN OTHER
                   CONTINUE
           END-EVALUATE

           MOVE ALL '*'                 TO WS-MASKED-PAN
           MOVE WS-PAN-LAST4            TO WS-MASKED-PAN (13:4)
           MOVE WS-MASKED-PAN
             TO CARD-NUM-MASKED OF API-CARD-RESPONSE
           .

      *
      * Ver: CardDemo REST/JSON API v1 - COCRSVCC card inquiry service
      *
