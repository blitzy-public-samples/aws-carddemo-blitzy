      *****************************************************************
      * Program:     COXRSVCC.CBL                                     *
      * Layer:       Business logic                                   *
      * Function:    REST API card cross-reference inquiry service.   *
      *              Resolve a card number to its account id and      *
      *              customer id via a read-only keyed READ of the    *
      *              CCXREF cross-reference VSAM file (base KSDS).    *
      *              LINKed by COAPIRTR with API-COMMAREA (COAPICOM). *
      *              Card number is masked to the last four digits;   *
      *              CVV is never handled by this service.            *
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
       PROGRAM-ID.
           COXRSVCC.
       DATE-WRITTEN.
           July 2025.
       DATE-COMPILED.
           Today.
      *
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
      *
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      *****************************************************************
      * General working-storage variables                             *
      ******************************************************************
       01  WS-VARIABLES.
           05  WS-PGMNAME          PIC X(08) VALUE 'COXRSVCC'.
           05  WS-CCXREF-FILE      PIC X(08) VALUE 'CCXREF  '.
           05  WS-RESP-CD          PIC S9(09) COMP VALUE ZEROS.
           05  WS-REAS-CD          PIC S9(09) COMP VALUE ZEROS.
           05  WS-XREF-KEY         PIC X(16) VALUE SPACES.
           05  WS-MASKED-PAN       PIC X(16) VALUE SPACES.
           05  WS-PAN-LAST4        PIC X(04) VALUE SPACES.
           05  WS-PAN-WORK         PIC X(16) VALUE SPACES.
           05  WS-PAN-LEN          PIC S9(04) COMP VALUE ZERO.
           05  WS-PAN-IDX          PIC S9(04) COMP VALUE ZERO.
           05  WS-PAN-START        PIC S9(04) COMP VALUE ZERO.
      *
      *****************************************************************
      * Card cross-reference record layout (read target,              *
      * RECLN 50) - CVACT03Y                                          *
      ******************************************************************
       COPY CVACT03Y.
      *
      *****************************************************************
      * API card cross-reference response contract; card              *
      * number masked to last 4 - COAPXRFY                            *
      ******************************************************************
       COPY COAPXRFY.
      *
      *****************************************************************
      * Shared API commarea / token / error-envelope contract.        *
      * The LINKAGE DFHCOMMAREA is mapped to this API-COMMAREA        *
      * working copy on entry and copied back on exit, mirroring      *
      * the CardDemo COMMAREA convention used by COACTVWC and         *
      * the online presentation tier - COAPICOM.                      *
      ******************************************************************
       COPY COAPICOM.
      *
       LINKAGE SECTION.
      *****************************************************************
      * Raw pass-through communication area supplied by the           *
      * router COAPIRTR on the EXEC CICS LINK.  It is addressed       *
      * as the API-COMMAREA contract (see COAPICOM above).            *
      ******************************************************************
       01  DFHCOMMAREA.
           05  FILLER              PIC X(01)
               OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN.
      *
       PROCEDURE DIVISION.
      *
      *****************************************************************
      * 0000-MAIN : entry point.  Validate the passed commarea,       *
      * copy it into the API-COMMAREA working copy, perform the       *
      * read, copy the populated contract back, and return to         *
      * the router one logical level higher.                          *
      ******************************************************************
       0000-MAIN.
           IF EIBCALEN >= LENGTH OF API-COMMAREA
               MOVE DFHCOMMAREA(1:LENGTH OF API-COMMAREA)
                   TO API-COMMAREA
               PERFORM 1000-READ-XREF
               MOVE API-COMMAREA
                   TO DFHCOMMAREA(1:LENGTH OF API-COMMAREA)
           END-IF
           EXEC CICS RETURN
           END-EXEC
           .
      *
      *****************************************************************
      * 1000-READ-XREF : read-only keyed READ of the base CCXREF      *
      * KSDS by 16-byte card number.  NORMAL -> map + HTTP 200;       *
      * NOTFND -> HTTP 404; any other RESP -> HTTP 500 with a         *
      * generic message (RESP2 is never surfaced to the caller).      *
      ******************************************************************
       1000-READ-XREF.
           MOVE API-REQ-CARD-NUM       TO WS-XREF-KEY
           EXEC CICS READ
                DATASET   (WS-CCXREF-FILE)
                INTO      (CARD-XREF-RECORD)
                LENGTH    (LENGTH OF CARD-XREF-RECORD)
                RIDFLD    (WS-XREF-KEY)
                KEYLENGTH (LENGTH OF WS-XREF-KEY)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   PERFORM 2000-MAP-RESPONSE
                   SET API-HTTP-OK              TO TRUE
                   MOVE +0                      TO API-RETURN-CODE
               WHEN DFHRESP(NOTFND)
                   MOVE SPACES                  TO API-PAYLOAD
                   SET API-HTTP-NOT-FOUND       TO TRUE
                   MOVE +4                      TO API-RETURN-CODE
                   MOVE 'NOTFOUND'              TO API-ERR-CODE
                   MOVE 'Cross-reference not found'
                       TO API-ERR-MESSAGE
               WHEN OTHER
                   MOVE SPACES                  TO API-PAYLOAD
                   SET API-HTTP-SERVER-ERROR    TO TRUE
                   MOVE +8                      TO API-RETURN-CODE
                   SET API-ERR-SERVER-ERROR     TO TRUE
                   MOVE 'Internal server error'
                       TO API-ERR-MESSAGE
           END-EVALUATE
           .
      *
      *****************************************************************
      * 2000-MAP-RESPONSE : populate the xref response contract       *
      * with the masked PAN plus the resolved account and             *
      * customer ids.  Shared field names are QUALIFIED to avoid      *
      * ambiguity between CARD-XREF-RECORD (CVACT03Y) and             *
      * API-XREF-RESPONSE (COAPXRFY).                                 *
      ******************************************************************
       2000-MAP-RESPONSE.
           PERFORM 2100-MASK-PAN
           MOVE XREF-ACCT-ID OF CARD-XREF-RECORD
               TO XREF-ACCT-ID OF API-XREF-RESPONSE
           MOVE XREF-CUST-ID OF CARD-XREF-RECORD
               TO XREF-CUST-ID OF API-XREF-RESPONSE
           MOVE SPACES                 TO API-PAYLOAD
           MOVE API-XREF-RESPONSE      TO API-PAYLOAD
           .
      *
      *****************************************************************
      * 2100-MASK-PAN : build 12 asterisks followed by the last       *
      * four digits of the card number (e.g. ************1234).       *
      * The full PAN is used only as a mask source and is never       *
      * placed into the response payload or any log.                  *
      ******************************************************************
       2100-MASK-PAN.
           MOVE XREF-CARD-NUM OF CARD-XREF-RECORD
                                       TO WS-PAN-WORK
           MOVE ZERO                   TO WS-PAN-LEN

           PERFORM VARYING WS-PAN-IDX FROM 16 BY -1
                   UNTIL WS-PAN-IDX < 1
                      OR WS-PAN-LEN > 0
               IF WS-PAN-WORK (WS-PAN-IDX:1) NOT = SPACE
                   MOVE WS-PAN-IDX     TO WS-PAN-LEN
               END-IF
           END-PERFORM

           MOVE '0000'                 TO WS-PAN-LAST4

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

           MOVE ALL '*'                TO WS-MASKED-PAN
           MOVE WS-PAN-LAST4           TO WS-MASKED-PAN (13:4)
           MOVE WS-MASKED-PAN
               TO XREF-CARD-NUM-MASKED OF API-XREF-RESPONSE
           .
      *
      * Ver: CardDemo REST/JSON API v1 - COXRSVCC card cross-reference
      *
