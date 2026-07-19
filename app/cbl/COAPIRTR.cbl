      ******************************************************************
      * Program     : COAPIRTR.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : REST/JSON API front-door router (read-only)
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
      *   COAPIRTR is the single entry point (front-door router) for
      *   the additive, read-only CardDemo REST/JSON API. It is driven
      *   by base CICS Web Support: a TCPIPSERVICE, a URIMAP for
      *   /carddemo/api/v1/* and an alias TRANSACTION invoke this
      *   program (all defined in the new CARDDEMOAPI CSD group).
      *
      *   Request lifecycle (0000-MAIN):
      *     receive -> parse route -> authorize -> dispatch ->
      *     serialize -> send. Each HTTP request is a fresh, one-shot
      *     invocation; the program is pseudo-conversational and ends
      *     with EXEC CICS RETURN (no COMMAREA, no TRANSID).
      *
      * DATA ACCESS RULES
      *   This program performs NO VSAM I/O and NO file control at all.
      *   Every data read is delegated to a service program via
      *   EXEC CICS LINK. There is NO READ / WRITE / REWRITE / DELETE /
      *   STARTBR / READNEXT against any dataset in this program, and
      *   NO BMS map handling.
      *
      * SECURITY
      *   Non-signon routes require a bearer token in the HTTP
      *   Authorization header; the token is validated by LINK to
      *   COAPISEC. The router never logs or echoes a password and
      *   never emits a full PAN, card security code, SSN or
      *   government id - the service programs already mask or omit
      *   those, and the router only passes the masked values through.
      *   The 500 (INTERNAL_ERROR) path never leaks RESP2 or any
      *   internal diagnostic into the JSON message.
      *
      * JSON ENGINE
      *   The uniform JSON envelope is produced by CALL 'COJSONUC',
      *   the dependency-free in-repo serializer. Monetary amounts are
      *   emitted as signed 2-decimal STRINGS (quoted) to satisfy the
      *   OpenAPI MonetaryAmount contract; ids and category codes are
      *   emitted as strings to preserve leading zeros.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COAPIRTR.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

       01 WS-VARIABLES.
         05 WS-PGMNAME              PIC X(08) VALUE 'COAPIRTR'.
      *  CICS response / reason codes for EXEC CICS commands.
         05 WS-RESP-CD              PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD              PIC S9(09) COMP VALUE ZEROS.
      *  HTTP request line extracted from CICS Web Support.
         05 WS-METHOD               PIC X(08) VALUE SPACES.
         05 WS-METHOD-U             PIC X(08) VALUE SPACES.
         05 WS-METHOD-LEN           PIC S9(08) COMP VALUE 8.
         05 WS-PATH                 PIC X(256) VALUE SPACES.
         05 WS-PATH-LEN             PIC S9(08) COMP VALUE 256.
         05 WS-REQ-BODY             PIC X(4096) VALUE SPACES.
         05 WS-REQ-LEN              PIC S9(08) COMP VALUE 4096.
      *  HTTP request header lookup (Authorization: Bearer <token>).
         05 WS-HDR-NAME             PIC X(32) VALUE 'Authorization'.
         05 WS-HDR-NAME-LEN         PIC S9(08) COMP VALUE 13.
         05 WS-AUTH-HDR             PIC X(256) VALUE SPACES.
         05 WS-AUTH-HDR-LEN         PIC S9(08) COMP VALUE 256.
      *  HTTP response status intent and Web Support work fields.
         05 WS-HTTP-STATUS          PIC 9(03) VALUE 200.
         05 WS-STATUSCODE           PIC S9(04) COMP VALUE 200.
         05 WS-STATUS-TEXT          PIC X(32) VALUE SPACES.
         05 WS-STATUS-TEXT-LEN      PIC S9(08) COMP VALUE 0.
         05 WS-MEDIATYPE            PIC X(56)
              VALUE 'application/json'.
      *  Error message text (safe, generic; never RESP2).
         05 WS-ERR-MSG              PIC X(120) VALUE SPACES.
         05 WS-CODE-STR             PIC X(16) VALUE SPACES.
      *  Container GET/PUT length work fields.
         05 WS-CONT-LEN             PIC S9(08) COMP VALUE 0.
         05 WS-CONT-LEN2            PIC S9(08) COMP VALUE 0.
      *  JSON buffer overflow indicator (COJSONUC bounds guard).
         05 WS-JSON-OVERFLOW        PIC X(01) VALUE 'N'.
           88 JSON-OVERFLOW                    VALUE 'Y'.
           88 JSON-OK                          VALUE 'N'.

      *----------------------------------------------------------------*
      *  Processing state - short-circuits later phases when an early
      *  error (400/401 or receive failure) has already been decided.
      *----------------------------------------------------------------*
       01 WS-PROC-STATE             PIC X(01) VALUE 'C'.
         88 STATE-CONTINUE                     VALUE 'C'.
         88 STATE-ERROR                        VALUE 'E'.

      *----------------------------------------------------------------*
      *  Public OpenAPI error-code strings (exceed the 8-char internal
      *  API-ERR-CODE; the router always serializes these full values).
      *----------------------------------------------------------------*
       01 WS-CODE-CONSTANTS.
         05 WS-CODE-BAD-REQUEST     PIC X(16) VALUE 'BAD_REQUEST'.
         05 WS-CODE-UNAUTHORIZED    PIC X(16) VALUE 'UNAUTHORIZED'.
         05 WS-CODE-NOT-FOUND       PIC X(16) VALUE 'NOT_FOUND'.
         05 WS-CODE-INTERNAL        PIC X(16) VALUE 'INTERNAL_ERROR'.

      *----------------------------------------------------------------*
      *  Route resolution work area. The base path is stripped and the
      *  remainder tokenized on '/' into up to five segments (SEG0 is
      *  the empty token before the leading slash).
      *----------------------------------------------------------------*
       01 WS-ROUTE-WORK.
         05 WS-BASE-PATH            PIC X(16)
              VALUE '/carddemo/api/v1'.
         05 WS-ROUTE-PATH           PIC X(240) VALUE SPACES.
         05 WS-SEG0                 PIC X(20) VALUE SPACES.
         05 WS-SEG1                 PIC X(20) VALUE SPACES.
         05 WS-SEG2                 PIC X(20) VALUE SPACES.
         05 WS-SEG3                 PIC X(20) VALUE SPACES.
         05 WS-SEG4                 PIC X(20) VALUE SPACES.
         05 WS-ROUTE-CODE           PIC X(08) VALUE 'NONE'.
           88 ROUTE-NONE                       VALUE 'NONE'.
           88 ROUTE-SIGNON                     VALUE 'SIGNON'.
           88 ROUTE-ACCT                       VALUE 'ACCT'.
           88 ROUTE-CUST                       VALUE 'CUST'.
           88 ROUTE-CARD                       VALUE 'CARD'.
           88 ROUTE-XREF                       VALUE 'XREF'.
           88 ROUTE-TRANLIST                   VALUE 'TRANLIST'.
           88 ROUTE-TRANDTL                    VALUE 'TRANDTL'.
         05 WS-SVC-PGM              PIC X(08) VALUE SPACES.

      *----------------------------------------------------------------*
      *  Path-parameter validation and numeric key build work area.
      *  Keys are validated all-numeric and right-justified with
      *  leading zeros into the fixed-width COMMAREA key fields.
      *----------------------------------------------------------------*
       01 WS-PARSE-WORK.
         05 WS-SEG-LEN              PIC S9(04) COMP VALUE 0.
         05 WS-OFFSET               PIC S9(04) COMP VALUE 0.
         05 WS-I                    PIC S9(04) COMP VALUE 0.
         05 WS-CHK-MAXLEN           PIC S9(04) COMP VALUE 0.
         05 WS-CHK-EXACT            PIC X(01) VALUE 'N'.
         05 WS-DIGIT-FLAG           PIC X(01) VALUE 'Y'.
           88 ALL-DIGITS                       VALUE 'Y'.
           88 NOT-DIGITS                       VALUE 'N'.

      *----------------------------------------------------------------*
      *  Numeric-to-string key holders (REDEFINES preserve leading
      *  zeros when a numeric id is serialized as a JSON string).
      *----------------------------------------------------------------*
       01 WS-KEY-WORK.
         05 WS-N11                  PIC 9(11) VALUE ZEROS.
         05 WS-N11X REDEFINES WS-N11 PIC X(11).
         05 WS-N09                  PIC 9(09) VALUE ZEROS.
         05 WS-N09X REDEFINES WS-N09 PIC X(09).
         05 WS-N04                  PIC 9(04) VALUE ZEROS.
         05 WS-N04X REDEFINES WS-N04 PIC X(04).

      *----------------------------------------------------------------*
      *  Request-id work area. A unique value is built per request from
      *  the CICS EIB (date, time, task number) and echoed in every
      *  error envelope as requestId.
      *----------------------------------------------------------------*
       01 WS-REQID-WORK.
         05 WS-REQ-ID               PIC X(36) VALUE SPACES.
         05 WS-EIB-DATE-9           PIC 9(07) VALUE ZEROS.
         05 WS-EIB-DATE-X REDEFINES WS-EIB-DATE-9 PIC X(07).
         05 WS-EIB-TIME-9           PIC 9(07) VALUE ZEROS.
         05 WS-EIB-TIME-X REDEFINES WS-EIB-TIME-9 PIC X(07).
         05 WS-TASK-9               PIC 9(07) VALUE ZEROS.
         05 WS-TASK-X REDEFINES WS-TASK-9 PIC X(07).

      *----------------------------------------------------------------*
      *  JSON emission work area (parameters staged for COJSONUC and
      *  numeric/monetary formatting scratch).
      *----------------------------------------------------------------*
       01 WS-JSON-WORK.
         05 WS-EMIT-NAME            PIC X(40) VALUE SPACES.
         05 WS-EMIT-NLEN            PIC S9(04) COMP VALUE 0.
         05 WS-STR-SRC              PIC X(256) VALUE SPACES.
         05 WS-STR-LEN              PIC S9(04) COMP VALUE 0.
         05 WS-FIRST-FLAG           PIC X(01) VALUE 'Y'.
         05 WS-ENTRY-FIRST          PIC X(01) VALUE 'Y'.
         05 WS-NUM-STR              PIC X(32) VALUE SPACES.
         05 WS-NUM-STR-LEN          PIC S9(04) COMP VALUE 0.
         05 WS-BOOL-VAL             PIC X(05) VALUE SPACES.
         05 WS-LIST-IDX             PIC S9(05) COMP VALUE 0.
         05 WS-START                PIC S9(04) COMP VALUE 0.
         05 WS-LEAD-SP              PIC S9(04) COMP VALUE 0.

      *----------------------------------------------------------------*
      *  Monetary and integer edit work fields. Money is formatted to a
      *  signed, 2-decimal string (pattern -?\d+\.\d{2}); integers are
      *  formatted with leading zeros suppressed.
      *----------------------------------------------------------------*
       01 WS-NUM-WORK.
         05 WS-MONEY-NUM            PIC S9(13)V99 VALUE 0.
         05 WS-MONEY-EDIT           PIC -(11)9.99.
         05 WS-MONEY-EDIT-X REDEFINES WS-MONEY-EDIT PIC X(15).
         05 WS-INT-VAL              PIC 9(09) VALUE 0.
         05 WS-INT-EDIT             PIC Z(8)9.
         05 WS-INT-EDIT-X REDEFINES WS-INT-EDIT PIC X(09).

      *----------------------------------------------------------------*
      *  API contract copybooks. The router is the top-level caller, so
      *  API-COMMAREA and all response layouts live in WORKING-STORAGE;
      *  only the LINK targets receive API-COMMAREA as their COMMAREA.
      *----------------------------------------------------------------*
       COPY COAPICOM.

       COPY COAPSGNY.

       COPY COAPACTY.

       COPY COAPCUSY.

       COPY COAPCRDY.

       COPY COAPXRFY.

       COPY COAPTRNY.

      *----------------------------------------------------------------*
      *  COJSONUC call interface. These layouts match the COJSONUC
      *  LINKAGE SECTION exactly (JSON-PARM and JSON-BUFFER).
      *----------------------------------------------------------------*
       01 JSON-PARM.
         05 JP-FUNCTION             PIC X(04).
         05 JP-NAME                 PIC X(40).
         05 JP-NAME-LEN             PIC S9(04) COMP.
         05 JP-VALUE                PIC X(256).
         05 JP-VALUE-LEN            PIC S9(04) COMP.
         05 JP-NUM-VALUE            PIC S9(13)V99.
         05 JP-FIRST-FLAG           PIC X(01).
           88 JP-IS-FIRST                      VALUE 'Y'.
           88 JP-NOT-FIRST                     VALUE 'N'.
         05 JP-RETURN-CODE          PIC S9(04) COMP.
           88 JP-OK                            VALUE 0.
           88 JP-ERROR                         VALUE 8.
         05 JP-PARSE-KEY            PIC X(40).
         05 JP-PARSE-RESULT         PIC X(256).
         05 JP-PARSE-RESULT-LEN     PIC S9(04) COMP.

       01 JSON-BUFFER.
         05 JB-DATA                 PIC X(32000).
         05 JB-LEN                  PIC S9(08) COMP.

      *----------------------------------------------------------------*
      *                      PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.

      *----------------------------------------------------------------*
      *                          0000-MAIN
      *  Orchestrates the request lifecycle. Any early failure sets
      *  STATE-ERROR and later logic phases are skipped; the response
      *  (success or error) is always serialized and sent. The program
      *  is pseudo-conversational and returns with no COMMAREA.
      *----------------------------------------------------------------*
       0000-MAIN.

           PERFORM 0050-INIT-REQUEST
           PERFORM 1000-RECEIVE-REQUEST
           IF STATE-CONTINUE
               PERFORM 2000-PARSE-ROUTE
           END-IF
           IF STATE-CONTINUE
               PERFORM 3000-AUTHORIZE
           END-IF
           IF STATE-CONTINUE
               PERFORM 4000-DISPATCH
           END-IF
           PERFORM 5000-SERIALIZE-RESPONSE
           PERFORM 6000-SEND-RESPONSE

           EXEC CICS RETURN
           END-EXEC.

      *----------------------------------------------------------------*
      *                       0050-INIT-REQUEST
      *  Reset per-request state and build a unique requestId from the
      *  CICS EIB (date, time, task number) for the error envelope.
      *----------------------------------------------------------------*
       0050-INIT-REQUEST.
           SET STATE-CONTINUE TO TRUE
           SET JSON-OK        TO TRUE
           MOVE 200    TO WS-HTTP-STATUS
           MOVE SPACES TO WS-ERR-MSG
           MOVE 'NONE' TO WS-ROUTE-CODE
           INITIALIZE API-COMMAREA
           MOVE EIBDATE  TO WS-EIB-DATE-9
           MOVE EIBTIME  TO WS-EIB-TIME-9
           MOVE EIBTASKN TO WS-TASK-9
           MOVE SPACES   TO WS-REQ-ID
           STRING 'req-'        DELIMITED BY SIZE
                  WS-EIB-DATE-X DELIMITED BY SIZE
                  '-'           DELIMITED BY SIZE
                  WS-EIB-TIME-X DELIMITED BY SIZE
                  '-'           DELIMITED BY SIZE
                  WS-TASK-X     DELIMITED BY SIZE
                  INTO WS-REQ-ID
           END-STRING
           MOVE WS-REQ-ID TO API-ERR-REQUEST-ID.

      *----------------------------------------------------------------*
      *                     1000-RECEIVE-REQUEST
      *  Extract the HTTP method and path; for POST also receive the
      *  request body. A Web Support failure maps to 400.
      *----------------------------------------------------------------*
       1000-RECEIVE-REQUEST.
           MOVE 8   TO WS-METHOD-LEN
           MOVE 256 TO WS-PATH-LEN
           MOVE SPACES TO WS-METHOD WS-PATH
           EXEC CICS WEB EXTRACT
                HTTPMETHOD   (WS-METHOD)
                METHODLENGTH (WS-METHOD-LEN)
                PATH         (WS-PATH)
                PATHLENGTH   (WS-PATH-LEN)
                RESP         (WS-RESP-CD)
                RESP2        (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 400 TO WS-HTTP-STATUS
               MOVE 'Malformed HTTP request' TO WS-ERR-MSG
               SET STATE-ERROR TO TRUE
           ELSE
               MOVE FUNCTION UPPER-CASE(WS-METHOD) TO WS-METHOD-U
               IF WS-METHOD-U = 'POST'
                   PERFORM 1100-RECEIVE-BODY
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                       1100-RECEIVE-BODY
      *  Receive the JSON request body (POST only). An oversized or
      *  unreadable body maps to 400.
      *----------------------------------------------------------------*
       1100-RECEIVE-BODY.
           MOVE 4096   TO WS-REQ-LEN
           MOVE SPACES TO WS-REQ-BODY
           EXEC CICS WEB RECEIVE
                INTO      (WS-REQ-BODY)
                LENGTH    (WS-REQ-LEN)
                MAXLENGTH (4096)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 400 TO WS-HTTP-STATUS
               MOVE 'Malformed request body' TO WS-ERR-MSG
               SET STATE-ERROR TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                       2000-PARSE-ROUTE
      *  Strip the versioned base path and tokenize the remainder on
      *  '/', then resolve the route and validate the path parameter.
      *----------------------------------------------------------------*
       2000-PARSE-ROUTE.
           IF WS-PATH(1:16) = WS-BASE-PATH
               MOVE WS-PATH(17:240) TO WS-ROUTE-PATH
           ELSE
               MOVE WS-PATH(1:240) TO WS-ROUTE-PATH
           END-IF
           MOVE SPACES TO WS-SEG0 WS-SEG1 WS-SEG2 WS-SEG3 WS-SEG4
           UNSTRING WS-ROUTE-PATH DELIMITED BY '/'
               INTO WS-SEG0 WS-SEG1 WS-SEG2 WS-SEG3 WS-SEG4
           END-UNSTRING
           PERFORM 2050-RESOLVE-ROUTE.

      *----------------------------------------------------------------*
      *                     2050-RESOLVE-ROUTE
      *  Map (method, segments) to a route code. The more specific
      *  /accounts/{id}/transactions is tested BEFORE /accounts/{id}.
      *  Any unmatched combination is a 400.
      *----------------------------------------------------------------*
       2050-RESOLVE-ROUTE.
           EVALUATE TRUE
               WHEN WS-SEG1 = 'signon' AND WS-SEG2 = SPACES
                   PERFORM 2110-ROUTE-SIGNON
               WHEN WS-SEG1 = 'accounts'
                    AND WS-SEG3 = 'transactions'
                   PERFORM 2120-ROUTE-TRANLIST
               WHEN WS-SEG1 = 'accounts'
                    AND WS-SEG2 NOT = SPACES
                    AND WS-SEG3 = SPACES
                   PERFORM 2130-ROUTE-ACCT
               WHEN WS-SEG1 = 'customers'
                    AND WS-SEG2 NOT = SPACES
                   PERFORM 2140-ROUTE-CUST
               WHEN WS-SEG1 = 'cards'
                    AND WS-SEG2 NOT = SPACES
                   PERFORM 2150-ROUTE-CARD
               WHEN WS-SEG1 = 'xref'
                    AND WS-SEG2 NOT = SPACES
                   PERFORM 2160-ROUTE-XREF
               WHEN WS-SEG1 = 'transactions'
                    AND WS-SEG2 NOT = SPACES
                   PERFORM 2170-ROUTE-TRANDTL
               WHEN OTHER
                   PERFORM 2900-SET-BAD-ROUTE
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                     2110-ROUTE-SIGNON  (POST /signon)
      *----------------------------------------------------------------*
       2110-ROUTE-SIGNON.
           IF WS-METHOD-U = 'POST'
               SET ROUTE-SIGNON TO TRUE
           ELSE
               PERFORM 2900-SET-BAD-ROUTE
           END-IF.

      *----------------------------------------------------------------*
      *          2120-ROUTE-TRANLIST  (GET /accounts/{id}/transactions)
      *----------------------------------------------------------------*
       2120-ROUTE-TRANLIST.
           IF WS-METHOD-U NOT = 'GET'
               PERFORM 2900-SET-BAD-ROUTE
           ELSE
               MOVE WS-SEG2 TO WS-STR-SRC
               MOVE 11 TO WS-CHK-MAXLEN
               MOVE 'N' TO WS-CHK-EXACT
               PERFORM 2800-CHECK-DIGITS
               IF ALL-DIGITS
                   PERFORM 2810-BUILD-ACCT-KEY
                   SET ROUTE-TRANLIST TO TRUE
               ELSE
                   PERFORM 2900-SET-BAD-ROUTE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     2130-ROUTE-ACCT  (GET /accounts/{id})
      *----------------------------------------------------------------*
       2130-ROUTE-ACCT.
           IF WS-METHOD-U NOT = 'GET'
               PERFORM 2900-SET-BAD-ROUTE
           ELSE
               MOVE WS-SEG2 TO WS-STR-SRC
               MOVE 11 TO WS-CHK-MAXLEN
               MOVE 'N' TO WS-CHK-EXACT
               PERFORM 2800-CHECK-DIGITS
               IF ALL-DIGITS
                   PERFORM 2810-BUILD-ACCT-KEY
                   SET ROUTE-ACCT TO TRUE
               ELSE
                   PERFORM 2900-SET-BAD-ROUTE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     2140-ROUTE-CUST  (GET /customers/{id})
      *----------------------------------------------------------------*
       2140-ROUTE-CUST.
           IF WS-METHOD-U NOT = 'GET'
               PERFORM 2900-SET-BAD-ROUTE
           ELSE
               MOVE WS-SEG2 TO WS-STR-SRC
               MOVE 9 TO WS-CHK-MAXLEN
               MOVE 'N' TO WS-CHK-EXACT
               PERFORM 2800-CHECK-DIGITS
               IF ALL-DIGITS
                   MOVE ZEROS TO WS-N09
                   COMPUTE WS-OFFSET = 9 - WS-SEG-LEN + 1
                   MOVE WS-STR-SRC(1:WS-SEG-LEN)
                       TO WS-N09X(WS-OFFSET:WS-SEG-LEN)
                   MOVE WS-N09 TO API-REQ-CUST-ID
                   SET ROUTE-CUST TO TRUE
               ELSE
                   PERFORM 2900-SET-BAD-ROUTE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     2150-ROUTE-CARD  (GET /cards/{cardNum})
      *----------------------------------------------------------------*
       2150-ROUTE-CARD.
           IF WS-METHOD-U NOT = 'GET'
               PERFORM 2900-SET-BAD-ROUTE
           ELSE
               MOVE WS-SEG2 TO WS-STR-SRC
               MOVE 16 TO WS-CHK-MAXLEN
               MOVE 'Y' TO WS-CHK-EXACT
               PERFORM 2800-CHECK-DIGITS
               IF ALL-DIGITS
                   MOVE WS-STR-SRC(1:16) TO API-REQ-CARD-NUM
                   SET ROUTE-CARD TO TRUE
               ELSE
                   PERFORM 2900-SET-BAD-ROUTE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     2160-ROUTE-XREF  (GET /xref/{cardNum})
      *----------------------------------------------------------------*
       2160-ROUTE-XREF.
           IF WS-METHOD-U NOT = 'GET'
               PERFORM 2900-SET-BAD-ROUTE
           ELSE
               MOVE WS-SEG2 TO WS-STR-SRC
               MOVE 16 TO WS-CHK-MAXLEN
               MOVE 'Y' TO WS-CHK-EXACT
               PERFORM 2800-CHECK-DIGITS
               IF ALL-DIGITS
                   MOVE WS-STR-SRC(1:16) TO API-REQ-CARD-NUM
                   SET ROUTE-XREF TO TRUE
               ELSE
                   PERFORM 2900-SET-BAD-ROUTE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                  2170-ROUTE-TRANDTL  (GET /transactions/{id})
      *  The transaction id is left-padded with zeros to 16 numeric
      *  digits, the form COTRSVCC requires for a detail read.
      *----------------------------------------------------------------*
       2170-ROUTE-TRANDTL.
           IF WS-METHOD-U NOT = 'GET'
               PERFORM 2900-SET-BAD-ROUTE
           ELSE
               MOVE WS-SEG2 TO WS-STR-SRC
               MOVE 16 TO WS-CHK-MAXLEN
               MOVE 'N' TO WS-CHK-EXACT
               PERFORM 2800-CHECK-DIGITS
               IF ALL-DIGITS
                   MOVE ALL '0' TO API-REQ-TRAN-ID
                   COMPUTE WS-OFFSET = 16 - WS-SEG-LEN + 1
                   MOVE WS-STR-SRC(1:WS-SEG-LEN)
                       TO API-REQ-TRAN-ID(WS-OFFSET:WS-SEG-LEN)
                   SET ROUTE-TRANDTL TO TRUE
               ELSE
                   PERFORM 2900-SET-BAD-ROUTE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     2800-CHECK-DIGITS
      *  Validate WS-STR-SRC holds 1..WS-CHK-MAXLEN digits (exact
      *  length when WS-CHK-EXACT = 'Y'). Sets ALL-DIGITS/NOT-DIGITS
      *  and WS-SEG-LEN (the significant length).
      *----------------------------------------------------------------*
       2800-CHECK-DIGITS.
           PERFORM 7600-CALC-STR-LEN
           MOVE WS-STR-LEN TO WS-SEG-LEN
           SET ALL-DIGITS TO TRUE
           IF WS-SEG-LEN < 1 OR WS-SEG-LEN > WS-CHK-MAXLEN
               SET NOT-DIGITS TO TRUE
           END-IF
           IF ALL-DIGITS AND WS-CHK-EXACT = 'Y'
               IF WS-SEG-LEN NOT = WS-CHK-MAXLEN
                   SET NOT-DIGITS TO TRUE
               END-IF
           END-IF
           IF ALL-DIGITS
               PERFORM VARYING WS-I FROM 1 BY 1
                       UNTIL WS-I > WS-SEG-LEN
                   IF WS-STR-SRC(WS-I:1) < '0'
                      OR WS-STR-SRC(WS-I:1) > '9'
                       SET NOT-DIGITS TO TRUE
                   END-IF
               END-PERFORM
           END-IF.

      *----------------------------------------------------------------*
      *                     2810-BUILD-ACCT-KEY
      *  Right-justify the validated account digits (leading zeros)
      *  into the 11-digit COMMAREA and list-request key fields.
      *----------------------------------------------------------------*
       2810-BUILD-ACCT-KEY.
           MOVE ZEROS TO WS-N11
           COMPUTE WS-OFFSET = 11 - WS-SEG-LEN + 1
           MOVE WS-STR-SRC(1:WS-SEG-LEN)
               TO WS-N11X(WS-OFFSET:WS-SEG-LEN)
           MOVE WS-N11 TO API-REQ-ACCT-ID
           MOVE WS-N11 TO TRLR-ACCT-ID.

      *----------------------------------------------------------------*
      *                     2900-SET-BAD-ROUTE
      *----------------------------------------------------------------*
       2900-SET-BAD-ROUTE.
           MOVE 400 TO WS-HTTP-STATUS
           MOVE 'Unknown route or invalid path parameter'
               TO WS-ERR-MSG
           SET ROUTE-NONE  TO TRUE
           SET STATE-ERROR TO TRUE.


      *----------------------------------------------------------------*
      *                       3000-AUTHORIZE
      *  Sign-on is the only route that does not require a token. Every
      *  other route must present a valid, unexpired bearer token,
      *  validated by LINK to COAPISEC.
      *----------------------------------------------------------------*
       3000-AUTHORIZE.
           IF ROUTE-SIGNON
               CONTINUE
           ELSE
               PERFORM 3100-READ-AUTH-HEADER
               IF STATE-CONTINUE
                   PERFORM 3200-VALIDATE-TOKEN
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     3100-READ-AUTH-HEADER
      *  Read the Authorization request header. A missing header or a
      *  non 'Bearer' scheme is a 401.
      *----------------------------------------------------------------*
       3100-READ-AUTH-HEADER.
           MOVE 'Authorization' TO WS-HDR-NAME
           MOVE 13  TO WS-HDR-NAME-LEN
           MOVE 256 TO WS-AUTH-HDR-LEN
           MOVE SPACES TO WS-AUTH-HDR
           EXEC CICS WEB READ
                HTTPHEADER  (WS-HDR-NAME)
                NAMELENGTH  (WS-HDR-NAME-LEN)
                VALUE       (WS-AUTH-HDR)
                VALUELENGTH (WS-AUTH-HDR-LEN)
                RESP        (WS-RESP-CD)
                RESP2       (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               PERFORM 3900-SET-UNAUTHORIZED
           ELSE
               PERFORM 3150-EXTRACT-BEARER
           END-IF.

      *----------------------------------------------------------------*
      *                     3150-EXTRACT-BEARER
      *  Extract the token following the 7-character 'Bearer ' prefix.
      *----------------------------------------------------------------*
       3150-EXTRACT-BEARER.
           IF FUNCTION UPPER-CASE(WS-AUTH-HDR(1:7)) = 'BEARER '
               MOVE WS-AUTH-HDR(8:64) TO API-TOKEN-VALUE
               IF API-TOKEN-VALUE = SPACES
                   PERFORM 3900-SET-UNAUTHORIZED
               END-IF
           ELSE
               PERFORM 3900-SET-UNAUTHORIZED
           END-IF.

      *----------------------------------------------------------------*
      *                     3200-VALIDATE-TOKEN
      *  LINK COAPISEC in VALIDATE mode. A non-OK status is a 401; a
      *  LINK failure is a 500 (no internal detail leaked).
      *----------------------------------------------------------------*
       3200-VALIDATE-TOKEN.
           MOVE 'VALIDATE' TO API-SERVICE-CODE
           MOVE SPACES     TO API-ERR-MESSAGE
           EXEC CICS LINK
                PROGRAM  ('COAPISEC')
                COMMAREA (API-COMMAREA)
                LENGTH   (LENGTH OF API-COMMAREA)
                RESP     (WS-RESP-CD)
                RESP2    (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 500 TO WS-HTTP-STATUS
               MOVE SPACES TO WS-ERR-MSG
               SET STATE-ERROR TO TRUE
           ELSE
               IF NOT API-HTTP-OK
                   PERFORM 3900-SET-UNAUTHORIZED
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     3900-SET-UNAUTHORIZED
      *----------------------------------------------------------------*
       3900-SET-UNAUTHORIZED.
           MOVE 401 TO WS-HTTP-STATUS
           MOVE 'Missing or invalid bearer token' TO WS-ERR-MSG
           SET STATE-ERROR TO TRUE.

      *----------------------------------------------------------------*
      *                       4000-DISPATCH
      *  Route to the resolved service. Single-record services use the
      *  COMMAREA; the transaction list uses a channel/container.
      *----------------------------------------------------------------*
       4000-DISPATCH.
           EVALUATE TRUE
               WHEN ROUTE-SIGNON
                   PERFORM 4100-DO-SIGNON
               WHEN ROUTE-ACCT
                   MOVE 'GETACCT ' TO API-SERVICE-CODE
                   MOVE 'COACSVCC' TO WS-SVC-PGM
                   PERFORM 4200-DO-SINGLE
               WHEN ROUTE-CUST
                   MOVE 'GETCUST ' TO API-SERVICE-CODE
                   MOVE 'COCUSVCC' TO WS-SVC-PGM
                   PERFORM 4200-DO-SINGLE
               WHEN ROUTE-CARD
                   MOVE 'GETCARD ' TO API-SERVICE-CODE
                   MOVE 'COCRSVCC' TO WS-SVC-PGM
                   PERFORM 4200-DO-SINGLE
               WHEN ROUTE-XREF
                   MOVE 'GETXREF ' TO API-SERVICE-CODE
                   MOVE 'COXRSVCC' TO WS-SVC-PGM
                   PERFORM 4200-DO-SINGLE
               WHEN ROUTE-TRANDTL
                   MOVE 'GETTRAN ' TO API-SERVICE-CODE
                   MOVE 'COTRSVCC' TO WS-SVC-PGM
                   PERFORM 4200-DO-SINGLE
               WHEN ROUTE-TRANLIST
                   PERFORM 4300-DO-TRAN-LIST
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                       4100-DO-SIGNON
      *  Parse the sign-on JSON body (userId, password), LINK COAPISEC
      *  in SIGNON mode, and scrub the password afterwards. A missing
      *  field is a 400; an authentication failure is a 401.
      *----------------------------------------------------------------*
       4100-DO-SIGNON.
           MOVE SPACES   TO API-SIGNON-REQUEST
           MOVE 'userId' TO JP-PARSE-KEY
           PERFORM 4150-PARSE-BODY-FIELD
           IF JP-OK AND JP-PARSE-RESULT-LEN > 0
               MOVE JP-PARSE-RESULT(1:JP-PARSE-RESULT-LEN)
                   TO SGNI-USER-ID
           ELSE
               PERFORM 4900-SET-BAD-BODY
           END-IF
           IF STATE-CONTINUE
               MOVE 'password' TO JP-PARSE-KEY
               PERFORM 4150-PARSE-BODY-FIELD
               IF JP-OK AND JP-PARSE-RESULT-LEN > 0
                   MOVE JP-PARSE-RESULT(1:JP-PARSE-RESULT-LEN)
                       TO SGNI-PASSWORD
               ELSE
                   PERFORM 4900-SET-BAD-BODY
               END-IF
           END-IF
           IF STATE-CONTINUE
               PERFORM 4180-LINK-SIGNON
           END-IF
           MOVE SPACES TO SGNI-PASSWORD
           MOVE SPACES TO WS-REQ-BODY.

      *----------------------------------------------------------------*
      *                     4150-PARSE-BODY-FIELD
      *  Parse one string field (key in JP-PARSE-KEY) from the JSON
      *  request body using COJSONUC PARS.
      *----------------------------------------------------------------*
       4150-PARSE-BODY-FIELD.
           MOVE 'PARS' TO JP-FUNCTION
           IF WS-REQ-LEN > 256
               MOVE 256 TO JP-VALUE-LEN
           ELSE
               MOVE WS-REQ-LEN TO JP-VALUE-LEN
           END-IF
           IF JP-VALUE-LEN < 1
               MOVE 1 TO JP-VALUE-LEN
           END-IF
           MOVE SPACES TO JP-VALUE
           MOVE WS-REQ-BODY(1:JP-VALUE-LEN) TO JP-VALUE
           MOVE SPACES TO JP-PARSE-RESULT
           MOVE 0      TO JP-PARSE-RESULT-LEN
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER.

      *----------------------------------------------------------------*
      *                       4180-LINK-SIGNON
      *----------------------------------------------------------------*
       4180-LINK-SIGNON.
           MOVE API-SIGNON-REQUEST TO API-PAYLOAD
           MOVE 'SIGNON  '         TO API-SERVICE-CODE
           MOVE SPACES             TO API-ERR-MESSAGE
           EXEC CICS LINK
                PROGRAM  ('COAPISEC')
                COMMAREA (API-COMMAREA)
                LENGTH   (LENGTH OF API-COMMAREA)
                RESP     (WS-RESP-CD)
                RESP2    (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 500 TO WS-HTTP-STATUS
               MOVE SPACES TO WS-ERR-MSG
           ELSE
               MOVE API-HTTP-STATUS TO WS-HTTP-STATUS
               IF NOT API-HTTP-OK
                   MOVE API-ERR-MESSAGE TO WS-ERR-MSG
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                       4200-DO-SINGLE
      *  LINK a single-record service over the COMMAREA and translate
      *  its status. A LINK failure is a 500 (no internal detail).
      *----------------------------------------------------------------*
       4200-DO-SINGLE.
           MOVE SPACES TO API-ERR-MESSAGE
           EXEC CICS LINK
                PROGRAM  (WS-SVC-PGM)
                COMMAREA (API-COMMAREA)
                LENGTH   (LENGTH OF API-COMMAREA)
                RESP     (WS-RESP-CD)
                RESP2    (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 500 TO WS-HTTP-STATUS
               MOVE SPACES TO WS-ERR-MSG
           ELSE
               MOVE API-HTTP-STATUS TO WS-HTTP-STATUS
               IF NOT API-HTTP-OK
                   MOVE API-ERR-MESSAGE TO WS-ERR-MSG
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                       4300-DO-TRAN-LIST
      *  Put the request container, LINK COTRSVCC over the channel, and
      *  read back the status and response containers.
      *----------------------------------------------------------------*
       4300-DO-TRAN-LIST.
           MOVE SPACES TO API-ERR-MESSAGE
           EXEC CICS PUT CONTAINER('TRANLISTREQ')
                CHANNEL ('CDEMOAPILISTCH')
                FROM    (API-TRAN-LIST-REQUEST)
                FLENGTH (LENGTH OF API-TRAN-LIST-REQUEST)
                RESP    (WS-RESP-CD)
                RESP2   (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 500 TO WS-HTTP-STATUS
               MOVE SPACES TO WS-ERR-MSG
           ELSE
               PERFORM 4310-LINK-LIST
           END-IF.

      *----------------------------------------------------------------*
      *                       4310-LINK-LIST
      *----------------------------------------------------------------*
       4310-LINK-LIST.
           EXEC CICS LINK
                PROGRAM ('COTRSVCC')
                CHANNEL ('CDEMOAPILISTCH')
                RESP    (WS-RESP-CD)
                RESP2   (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 500 TO WS-HTTP-STATUS
               MOVE SPACES TO WS-ERR-MSG
           ELSE
               PERFORM 4320-GET-LIST-RESULT
           END-IF.

      *----------------------------------------------------------------*
      *                     4320-GET-LIST-RESULT
      *  The status container is fixed length; the response container
      *  buffer is sized at the maximum (500 entries) so nothing is
      *  truncated. The list service's HTTP status is authoritative.
      *----------------------------------------------------------------*
       4320-GET-LIST-RESULT.
           MOVE LENGTH OF API-TRAN-LIST-STATUS TO WS-CONT-LEN
           EXEC CICS GET CONTAINER('TRANLISTSTA')
                CHANNEL ('CDEMOAPILISTCH')
                INTO    (API-TRAN-LIST-STATUS)
                FLENGTH (WS-CONT-LEN)
                RESP    (WS-RESP-CD)
                RESP2   (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE 500 TO WS-HTTP-STATUS
               MOVE SPACES TO WS-ERR-MSG
           ELSE
               MOVE 500 TO TRAN-LIST-COUNT
               MOVE LENGTH OF API-TRAN-LIST TO WS-CONT-LEN2
               EXEC CICS GET CONTAINER('TRANLISTRSP')
                    CHANNEL ('CDEMOAPILISTCH')
                    INTO    (API-TRAN-LIST)
                    FLENGTH (WS-CONT-LEN2)
                    RESP    (WS-RESP-CD)
                    RESP2   (WS-REAS-CD)
               END-EXEC
               IF WS-RESP-CD NOT = DFHRESP(NORMAL)
                   MOVE 500 TO WS-HTTP-STATUS
                   MOVE SPACES TO WS-ERR-MSG
               ELSE
                   MOVE TRLS-HTTP-STATUS TO WS-HTTP-STATUS
                   IF WS-HTTP-STATUS NOT = 200
                       MOVE TRLS-ERR-MESSAGE TO WS-ERR-MSG
                   END-IF
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                       4900-SET-BAD-BODY
      *----------------------------------------------------------------*
       4900-SET-BAD-BODY.
           MOVE 400 TO WS-HTTP-STATUS
           MOVE 'Request body is missing required fields'
               TO WS-ERR-MSG
           SET STATE-ERROR TO TRUE.


      *----------------------------------------------------------------*
      *                    5000-SERIALIZE-RESPONSE
      *  Build the JSON body. On HTTP 200 a success envelope is built
      *  for the matched route; if serialization overflows the buffer
      *  the response degrades to a 500 error envelope. Any non-200
      *  status yields the uniform error envelope.
      *----------------------------------------------------------------*
       5000-SERIALIZE-RESPONSE.
           IF WS-HTTP-STATUS = 200
               SET JSON-OK TO TRUE
               PERFORM 5100-SERIALIZE-SUCCESS
               IF JSON-OVERFLOW
                   MOVE 500 TO WS-HTTP-STATUS
                   MOVE SPACES TO WS-ERR-MSG
                   PERFORM 5900-SERIALIZE-ERROR
               END-IF
           ELSE
               PERFORM 5900-SERIALIZE-ERROR
           END-IF.

      *----------------------------------------------------------------*
      *                    5100-SERIALIZE-SUCCESS
      *  Emit { "data": { ...resource... } } for the matched route.
      *----------------------------------------------------------------*
       5100-SERIALIZE-SUCCESS.
           PERFORM 7000-JSON-BEGIN-DATA
           EVALUATE TRUE
               WHEN ROUTE-SIGNON
                   PERFORM 5110-SER-SIGNON
               WHEN ROUTE-ACCT
                   PERFORM 5120-SER-ACCT
               WHEN ROUTE-CUST
                   PERFORM 5130-SER-CUST
               WHEN ROUTE-CARD
                   PERFORM 5140-SER-CARD
               WHEN ROUTE-XREF
                   PERFORM 5150-SER-XREF
               WHEN ROUTE-TRANDTL
                   PERFORM 5160-SER-TRAN-DETAIL
               WHEN ROUTE-TRANLIST
                   PERFORM 5170-SER-TRAN-LIST
           END-EVALUATE
           PERFORM 7900-JSON-END-DATA.

      *----------------------------------------------------------------*
      *                     5110-SER-SIGNON
      *----------------------------------------------------------------*
       5110-SER-SIGNON.
           MOVE API-PAYLOAD TO API-SIGNON-RESPONSE
           MOVE 'token' TO WS-EMIT-NAME
           MOVE 5 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE SGNO-TOKEN TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'N' TO WS-FIRST-FLAG
           MOVE 'userId' TO WS-EMIT-NAME
           MOVE 6 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE SGNO-USER-ID TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'userType' TO WS-EMIT-NAME
           MOVE 8 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE SGNO-USER-TYPE TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'expiresAt' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE SGNO-TOKEN-EXPIRY-TS TO WS-STR-SRC
           PERFORM 7100-EMIT-STR.

      *----------------------------------------------------------------*
      *                     5120-SER-ACCT
      *  Money fields are emitted as signed 2-decimal STRINGS to match
      *  the OpenAPI MonetaryAmount (string) contract.
      *----------------------------------------------------------------*
       5120-SER-ACCT.
           MOVE API-PAYLOAD TO API-ACCT-RESPONSE
           MOVE 'accountId' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE ACCT-ID TO WS-N11
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N11X TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'N' TO WS-FIRST-FLAG
           MOVE 'activeStatus' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE ACCT-ACTIVE-STATUS TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'currentBalance' TO WS-EMIT-NAME
           MOVE 14 TO WS-EMIT-NLEN
           MOVE ACCT-CURR-BAL TO WS-MONEY-NUM
           PERFORM 7150-EMIT-MONEY
           MOVE 'creditLimit' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE ACCT-CREDIT-LIMIT TO WS-MONEY-NUM
           PERFORM 7150-EMIT-MONEY
           MOVE 'cashCreditLimit' TO WS-EMIT-NAME
           MOVE 15 TO WS-EMIT-NLEN
           MOVE ACCT-CASH-CREDIT-LIMIT TO WS-MONEY-NUM
           PERFORM 7150-EMIT-MONEY
           MOVE 'openDate' TO WS-EMIT-NAME
           MOVE 8 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE ACCT-OPEN-DATE TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'expirationDate' TO WS-EMIT-NAME
           MOVE 14 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE ACCT-EXPIRAION-DATE TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'reissueDate' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE ACCT-REISSUE-DATE TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'currentCycleCredit' TO WS-EMIT-NAME
           MOVE 18 TO WS-EMIT-NLEN
           MOVE ACCT-CURR-CYC-CREDIT TO WS-MONEY-NUM
           PERFORM 7150-EMIT-MONEY
           MOVE 'currentCycleDebit' TO WS-EMIT-NAME
           MOVE 17 TO WS-EMIT-NLEN
           MOVE ACCT-CURR-CYC-DEBIT TO WS-MONEY-NUM
           PERFORM 7150-EMIT-MONEY
           MOVE 'groupId' TO WS-EMIT-NAME
           MOVE 7 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE ACCT-GROUP-ID TO WS-STR-SRC
           PERFORM 7100-EMIT-STR.

      *----------------------------------------------------------------*
      *                     5130-SER-CUST
      *  ssnMasked / govtIdMasked already minimized by COCUSVCC; the
      *  router only passes the masked values through.
      *----------------------------------------------------------------*
       5130-SER-CUST.
           MOVE API-PAYLOAD TO API-CUST-RESPONSE
           MOVE 'customerId' TO WS-EMIT-NAME
           MOVE 10 TO WS-EMIT-NLEN
           MOVE CUST-ID TO WS-N09
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N09X TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'N' TO WS-FIRST-FLAG
           MOVE 'firstName' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-FIRST-NAME TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'middleName' TO WS-EMIT-NAME
           MOVE 10 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-MIDDLE-NAME TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'lastName' TO WS-EMIT-NAME
           MOVE 8 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-LAST-NAME TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'addressLine1' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-ADDR-LINE-1 TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'addressLine2' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-ADDR-LINE-2 TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'addressLine3' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-ADDR-LINE-3 TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'stateCode' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-ADDR-STATE-CD TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'countryCode' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-ADDR-COUNTRY-CD TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'addressZip' TO WS-EMIT-NAME
           MOVE 10 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-ADDR-ZIP TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'phone1' TO WS-EMIT-NAME
           MOVE 6 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-PHONE-NUM-1 TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'phone2' TO WS-EMIT-NAME
           MOVE 6 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-PHONE-NUM-2 TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'ssnMasked' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-SSN-MASKED TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'govtIdMasked' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-GOVT-ID-MASKED TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'dateOfBirth' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-DOB-YYYY-MM-DD TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'primaryCardHolderIndicator' TO WS-EMIT-NAME
           MOVE 26 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CUST-PRI-CARD-HOLDER-IND TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'ficoCreditScore' TO WS-EMIT-NAME
           MOVE 15 TO WS-EMIT-NLEN
           MOVE CUST-FICO-CREDIT-SCORE TO WS-INT-VAL
           PERFORM 7250-FMT-INT
           PERFORM 7200-EMIT-NUM.

      *----------------------------------------------------------------*
      *                     5140-SER-CARD
      *  cardNumberMasked already masked by COCRSVCC; security code is
      *  never present. The router passes the masked value through.
      *----------------------------------------------------------------*
       5140-SER-CARD.
           MOVE API-PAYLOAD TO API-CARD-RESPONSE
           MOVE 'cardNumberMasked' TO WS-EMIT-NAME
           MOVE 16 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CARD-NUM-MASKED TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'N' TO WS-FIRST-FLAG
           MOVE 'accountId' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE CARD-ACCT-ID TO WS-N11
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N11X TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'embossedName' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CARD-EMBOSSED-NAME TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'expirationDate' TO WS-EMIT-NAME
           MOVE 14 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CARD-EXPIRAION-DATE TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'activeStatus' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE CARD-ACTIVE-STATUS TO WS-STR-SRC
           PERFORM 7100-EMIT-STR.

      *----------------------------------------------------------------*
      *                     5150-SER-XREF
      *----------------------------------------------------------------*
       5150-SER-XREF.
           MOVE API-PAYLOAD TO API-XREF-RESPONSE
           MOVE 'cardNumberMasked' TO WS-EMIT-NAME
           MOVE 16 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE XREF-CARD-NUM-MASKED TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'N' TO WS-FIRST-FLAG
           MOVE 'accountId' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE XREF-ACCT-ID TO WS-N11
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N11X TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'customerId' TO WS-EMIT-NAME
           MOVE 10 TO WS-EMIT-NLEN
           MOVE XREF-CUST-ID TO WS-N09
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N09X TO WS-STR-SRC
           PERFORM 7100-EMIT-STR.

      *----------------------------------------------------------------*
      *                     5160-SER-TRAN-DETAIL
      *----------------------------------------------------------------*
       5160-SER-TRAN-DETAIL.
           MOVE API-PAYLOAD TO API-TRAN-RESPONSE
           MOVE 'transactionId' TO WS-EMIT-NAME
           MOVE 13 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-ID TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'N' TO WS-FIRST-FLAG
           MOVE 'typeCode' TO WS-EMIT-NAME
           MOVE 8 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-TYPE-CD TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'categoryCode' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE TRAN-CAT-CD TO WS-N04
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N04X TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'source' TO WS-EMIT-NAME
           MOVE 6 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-SOURCE TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'description' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-DESC TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'amount' TO WS-EMIT-NAME
           MOVE 6 TO WS-EMIT-NLEN
           MOVE TRAN-AMT TO WS-MONEY-NUM
           PERFORM 7150-EMIT-MONEY
           MOVE 'merchantId' TO WS-EMIT-NAME
           MOVE 10 TO WS-EMIT-NLEN
           MOVE TRAN-MERCHANT-ID TO WS-N09
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N09X TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'merchantName' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-MERCHANT-NAME TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'merchantCity' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-MERCHANT-CITY TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'merchantZip' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-MERCHANT-ZIP TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'cardNumberMasked' TO WS-EMIT-NAME
           MOVE 16 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-CARD-NUM-MASKED TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'originTimestamp' TO WS-EMIT-NAME
           MOVE 15 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-ORIG-TS TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'processTimestamp' TO WS-EMIT-NAME
           MOVE 16 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRAN-PROC-TS TO WS-STR-SRC
           PERFORM 7100-EMIT-STR.

      *----------------------------------------------------------------*
      *                     5170-SER-TRAN-LIST
      *  Emit accountId, the transactions array (possibly empty), the
      *  count and the truncated flag. An empty account is a 200 with
      *  an empty array.
      *----------------------------------------------------------------*
       5170-SER-TRAN-LIST.
           MOVE 'accountId' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE TRAN-LIST-ACCT-ID TO WS-N11
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N11X TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'transactions' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE 'N' TO WS-FIRST-FLAG
           PERFORM 7030-BEGIN-ARR
           MOVE 'Y' TO WS-ENTRY-FIRST
           PERFORM VARYING WS-LIST-IDX FROM 1 BY 1
                   UNTIL WS-LIST-IDX > TRAN-LIST-COUNT
               MOVE SPACES TO WS-EMIT-NAME
               MOVE 0 TO WS-EMIT-NLEN
               MOVE WS-ENTRY-FIRST TO WS-FIRST-FLAG
               PERFORM 7010-BEGIN-OBJ
               PERFORM 5180-SER-TRAN-ENTRY
               PERFORM 7020-END-OBJ
               MOVE 'N' TO WS-ENTRY-FIRST
           END-PERFORM
           PERFORM 7040-END-ARR
           MOVE 'count' TO WS-EMIT-NAME
           MOVE 5 TO WS-EMIT-NLEN
           MOVE TRAN-LIST-COUNT TO WS-INT-VAL
           PERFORM 7250-FMT-INT
           MOVE 'N' TO WS-FIRST-FLAG
           PERFORM 7200-EMIT-NUM
           MOVE 'truncated' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           IF TRAN-LIST-WAS-TRUNCATED
               MOVE 'true' TO WS-BOOL-VAL
           ELSE
               MOVE 'false' TO WS-BOOL-VAL
           END-IF
           MOVE 'N' TO WS-FIRST-FLAG
           PERFORM 7300-EMIT-BOOL.

      *----------------------------------------------------------------*
      *                     5180-SER-TRAN-ENTRY
      *  Serialize one transaction list entry (indexed by WS-LIST-IDX).
      *----------------------------------------------------------------*
       5180-SER-TRAN-ENTRY.
           MOVE 'transactionId' TO WS-EMIT-NAME
           MOVE 13 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-ID(WS-LIST-IDX) TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'N' TO WS-FIRST-FLAG
           MOVE 'typeCode' TO WS-EMIT-NAME
           MOVE 8 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-TYPE-CD(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'categoryCode' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE TRNL-CAT-CD(WS-LIST-IDX) TO WS-N04
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N04X TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'source' TO WS-EMIT-NAME
           MOVE 6 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-SOURCE(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'description' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-DESC(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'amount' TO WS-EMIT-NAME
           MOVE 6 TO WS-EMIT-NLEN
           MOVE TRNL-AMT(WS-LIST-IDX) TO WS-MONEY-NUM
           PERFORM 7150-EMIT-MONEY
           MOVE 'merchantId' TO WS-EMIT-NAME
           MOVE 10 TO WS-EMIT-NLEN
           MOVE TRNL-MERCHANT-ID(WS-LIST-IDX) TO WS-N09
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-N09X TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'merchantName' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-MERCHANT-NAME(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'merchantCity' TO WS-EMIT-NAME
           MOVE 12 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-MERCHANT-CITY(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'merchantZip' TO WS-EMIT-NAME
           MOVE 11 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-MERCHANT-ZIP(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'cardNumberMasked' TO WS-EMIT-NAME
           MOVE 16 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-CARD-NUM-MASKED(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'originTimestamp' TO WS-EMIT-NAME
           MOVE 15 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-ORIG-TS(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR
           MOVE 'processTimestamp' TO WS-EMIT-NAME
           MOVE 16 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE TRNL-PROC-TS(WS-LIST-IDX) TO WS-STR-SRC
           PERFORM 7100-EMIT-STR.

      *----------------------------------------------------------------*
      *                     5900-SERIALIZE-ERROR
      *  Emit { "error": { "code","message","requestId" } } - exactly
      *  three fields. The code string is mapped from the HTTP status
      *  and the message is guaranteed free of any internal detail.
      *----------------------------------------------------------------*
       5900-SERIALIZE-ERROR.
           PERFORM 9200-SET-CODE-STR
           MOVE 'INIT' TO JP-FUNCTION
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           MOVE SPACES TO WS-EMIT-NAME
           MOVE 0 TO WS-EMIT-NLEN
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7010-BEGIN-OBJ
           MOVE 'error' TO WS-EMIT-NAME
           MOVE 5 TO WS-EMIT-NLEN
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7010-BEGIN-OBJ
           MOVE 'code' TO WS-EMIT-NAME
           MOVE 4 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-CODE-STR TO WS-STR-SRC
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'message' TO WS-EMIT-NAME
           MOVE 7 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-ERR-MSG TO WS-STR-SRC
           MOVE 'N' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           MOVE 'requestId' TO WS-EMIT-NAME
           MOVE 9 TO WS-EMIT-NLEN
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-REQ-ID TO WS-STR-SRC
           MOVE 'N' TO WS-FIRST-FLAG
           PERFORM 7100-EMIT-STR
           PERFORM 7020-END-OBJ
           PERFORM 7020-END-OBJ.

      *----------------------------------------------------------------*
      *                     9200-SET-CODE-STR
      *  Map the HTTP status to the public OpenAPI error-code string
      *  and provide a safe default message. The 500 branch always
      *  uses a fixed generic message (never a service or RESP2 value).
      *----------------------------------------------------------------*
       9200-SET-CODE-STR.
           EVALUATE WS-HTTP-STATUS
               WHEN 400
                   MOVE WS-CODE-BAD-REQUEST TO WS-CODE-STR
                   IF WS-ERR-MSG = SPACES
                       MOVE 'Bad request' TO WS-ERR-MSG
                   END-IF
               WHEN 401
                   MOVE WS-CODE-UNAUTHORIZED TO WS-CODE-STR
                   IF WS-ERR-MSG = SPACES
                       MOVE 'Unauthorized' TO WS-ERR-MSG
                   END-IF
               WHEN 404
                   MOVE WS-CODE-NOT-FOUND TO WS-CODE-STR
                   IF WS-ERR-MSG = SPACES
                       MOVE 'Resource not found' TO WS-ERR-MSG
                   END-IF
               WHEN OTHER
                   MOVE 500 TO WS-HTTP-STATUS
                   MOVE WS-CODE-INTERNAL TO WS-CODE-STR
                   MOVE 'Internal server error' TO WS-ERR-MSG
           END-EVALUATE.


      *----------------------------------------------------------------*
      *                    6000-SEND-RESPONSE
      *  Send the serialized JSON with Content-Type application/json
      *  and the mapped HTTP status code. A WEB SEND failure cannot be
      *  reported to the client (the channel is already committing), so
      *  it is swallowed after being captured in the RESP fields.
      *----------------------------------------------------------------*
       6000-SEND-RESPONSE.
           MOVE WS-HTTP-STATUS TO WS-STATUSCODE
           PERFORM 6100-SET-STATUS-TEXT
           EXEC CICS WEB SEND
                FROM       (JB-DATA)
                FROMLENGTH (JB-LEN)
                MEDIATYPE  (WS-MEDIATYPE)
                STATUSCODE (WS-STATUSCODE)
                STATUSTEXT (WS-STATUS-TEXT)
                STATUSLEN  (WS-STATUS-TEXT-LEN)
                RESP       (WS-RESP-CD)
                RESP2      (WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                    6100-SET-STATUS-TEXT
      *  Provide the HTTP reason phrase for the mapped status code.
      *----------------------------------------------------------------*
       6100-SET-STATUS-TEXT.
           EVALUATE WS-HTTP-STATUS
               WHEN 200
                   MOVE 'OK' TO WS-STATUS-TEXT
                   MOVE 2 TO WS-STATUS-TEXT-LEN
               WHEN 400
                   MOVE 'Bad Request' TO WS-STATUS-TEXT
                   MOVE 11 TO WS-STATUS-TEXT-LEN
               WHEN 401
                   MOVE 'Unauthorized' TO WS-STATUS-TEXT
                   MOVE 12 TO WS-STATUS-TEXT-LEN
               WHEN 404
                   MOVE 'Not Found' TO WS-STATUS-TEXT
                   MOVE 9 TO WS-STATUS-TEXT-LEN
               WHEN OTHER
                   MOVE 'Internal Server Error' TO WS-STATUS-TEXT
                   MOVE 21 TO WS-STATUS-TEXT-LEN
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                    7000-JSON-BEGIN-DATA
      *  Reset the JSON buffer and open { "data": { .
      *----------------------------------------------------------------*
       7000-JSON-BEGIN-DATA.
           MOVE 'INIT' TO JP-FUNCTION
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           MOVE SPACES TO WS-EMIT-NAME
           MOVE 0 TO WS-EMIT-NLEN
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7010-BEGIN-OBJ
           MOVE 'data' TO WS-EMIT-NAME
           MOVE 4 TO WS-EMIT-NLEN
           MOVE 'Y' TO WS-FIRST-FLAG
           PERFORM 7010-BEGIN-OBJ.

      *----------------------------------------------------------------*
      *                    7900-JSON-END-DATA
      *  Close the data object and the root object.
      *----------------------------------------------------------------*
       7900-JSON-END-DATA.
           PERFORM 7020-END-OBJ
           PERFORM 7020-END-OBJ.

      *----------------------------------------------------------------*
      *                    7010-BEGIN-OBJ  (emit '{')
      *----------------------------------------------------------------*
       7010-BEGIN-OBJ.
           MOVE 'BEGO' TO JP-FUNCTION
           MOVE WS-EMIT-NAME TO JP-NAME
           MOVE WS-EMIT-NLEN TO JP-NAME-LEN
           MOVE WS-FIRST-FLAG TO JP-FIRST-FLAG
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           IF JP-ERROR
               SET JSON-OVERFLOW TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                    7020-END-OBJ  (emit '}')
      *----------------------------------------------------------------*
       7020-END-OBJ.
           MOVE 'ENDO' TO JP-FUNCTION
           MOVE SPACES TO JP-NAME
           MOVE 0 TO JP-NAME-LEN
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           IF JP-ERROR
               SET JSON-OVERFLOW TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                    7030-BEGIN-ARR  (emit '[')
      *----------------------------------------------------------------*
       7030-BEGIN-ARR.
           MOVE 'BEGA' TO JP-FUNCTION
           MOVE WS-EMIT-NAME TO JP-NAME
           MOVE WS-EMIT-NLEN TO JP-NAME-LEN
           MOVE WS-FIRST-FLAG TO JP-FIRST-FLAG
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           IF JP-ERROR
               SET JSON-OVERFLOW TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                    7040-END-ARR  (emit ']')
      *----------------------------------------------------------------*
       7040-END-ARR.
           MOVE 'ENDA' TO JP-FUNCTION
           MOVE SPACES TO JP-NAME
           MOVE 0 TO JP-NAME-LEN
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           IF JP-ERROR
               SET JSON-OVERFLOW TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                    7100-EMIT-STR
      *  Emit a string member "name":"value". The value length is the
      *  trimmed length of WS-STR-SRC; COJSONUC handles JSON escaping.
      *----------------------------------------------------------------*
       7100-EMIT-STR.
           PERFORM 7600-CALC-STR-LEN
           MOVE 'STR ' TO JP-FUNCTION
           MOVE WS-EMIT-NAME TO JP-NAME
           MOVE WS-EMIT-NLEN TO JP-NAME-LEN
           MOVE WS-STR-SRC TO JP-VALUE
           MOVE WS-STR-LEN TO JP-VALUE-LEN
           MOVE WS-FIRST-FLAG TO JP-FIRST-FLAG
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           IF JP-ERROR
               SET JSON-OVERFLOW TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                    7150-EMIT-MONEY
      *  Format a signed implied-decimal value as a 2dp string (e.g.
      *  "1234.56" / "-50.00") and emit it as a quoted STRING to match
      *  the OpenAPI MonetaryAmount (string) contract.
      *----------------------------------------------------------------*
       7150-EMIT-MONEY.
           MOVE WS-MONEY-NUM TO WS-MONEY-EDIT
           MOVE 0 TO WS-LEAD-SP
           INSPECT WS-MONEY-EDIT-X
               TALLYING WS-LEAD-SP FOR LEADING SPACES
           COMPUTE WS-START = WS-LEAD-SP + 1
           COMPUTE WS-STR-LEN = 15 - WS-LEAD-SP
           MOVE SPACES TO WS-STR-SRC
           MOVE WS-MONEY-EDIT-X(WS-START:WS-STR-LEN)
               TO WS-STR-SRC
           PERFORM 7100-EMIT-STR.

      *----------------------------------------------------------------*
      *                    7200-EMIT-NUM
      *  Emit an unquoted numeric member from the preformatted string
      *  in WS-NUM-STR / WS-NUM-STR-LEN.
      *----------------------------------------------------------------*
       7200-EMIT-NUM.
           MOVE 'NUM ' TO JP-FUNCTION
           MOVE WS-EMIT-NAME TO JP-NAME
           MOVE WS-EMIT-NLEN TO JP-NAME-LEN
           MOVE WS-NUM-STR TO JP-VALUE
           MOVE WS-NUM-STR-LEN TO JP-VALUE-LEN
           MOVE WS-FIRST-FLAG TO JP-FIRST-FLAG
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           IF JP-ERROR
               SET JSON-OVERFLOW TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                    7250-FMT-INT
      *  Convert WS-INT-VAL to a left-justified decimal string in
      *  WS-NUM-STR / WS-NUM-STR-LEN (no leading zeros, no sign).
      *----------------------------------------------------------------*
       7250-FMT-INT.
           MOVE WS-INT-VAL TO WS-INT-EDIT
           MOVE 0 TO WS-LEAD-SP
           INSPECT WS-INT-EDIT-X
               TALLYING WS-LEAD-SP FOR LEADING SPACES
           COMPUTE WS-START = WS-LEAD-SP + 1
           COMPUTE WS-NUM-STR-LEN = 9 - WS-LEAD-SP
           MOVE SPACES TO WS-NUM-STR
           MOVE WS-INT-EDIT-X(WS-START:WS-NUM-STR-LEN)
               TO WS-NUM-STR.

      *----------------------------------------------------------------*
      *                    7300-EMIT-BOOL
      *  Emit an unquoted boolean member (true/false) from WS-BOOL-VAL.
      *----------------------------------------------------------------*
       7300-EMIT-BOOL.
           MOVE 'BOOL' TO JP-FUNCTION
           MOVE WS-EMIT-NAME TO JP-NAME
           MOVE WS-EMIT-NLEN TO JP-NAME-LEN
           MOVE WS-BOOL-VAL TO JP-VALUE
           MOVE 5 TO JP-VALUE-LEN
           MOVE WS-FIRST-FLAG TO JP-FIRST-FLAG
           CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER
           IF JP-ERROR
               SET JSON-OVERFLOW TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                    7600-CALC-STR-LEN
      *  Compute the trimmed (trailing-space-stripped) length of
      *  WS-STR-SRC into WS-STR-LEN. An all-blank value yields 0.
      *----------------------------------------------------------------*
       7600-CALC-STR-LEN.
           MOVE 0 TO WS-STR-LEN
           PERFORM VARYING WS-I FROM 256 BY -1
                   UNTIL WS-I < 1 OR WS-STR-LEN > 0
               IF WS-STR-SRC(WS-I:1) NOT = SPACE
                   MOVE WS-I TO WS-STR-LEN
               END-IF
           END-PERFORM.
      *
      * Ver: CardDemo REST/JSON API v1.0 - COAPIRTR front-door router
      *
