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
      *  M8 - Copybook-driven list length calibration. The fixed list
      *  header length and per-entry length are derived from the layout
      *  itself (no hard-coded sizes) and used to verify the returned
      *  container FLENGTH matches the returned entry count exactly.
         05 WS-TRLIST-HDR-LEN       PIC S9(08) COMP VALUE 0.
         05 WS-TRENTRY-LEN          PIC S9(08) COMP VALUE 0.
         05 WS-EXP-LIST-LEN         PIC S9(08) COMP VALUE 0.
      *  JSON buffer overflow indicator (COJSONUC bounds guard).
         05 WS-JSON-OVERFLOW        PIC X(01) VALUE 'N'.
           88 JSON-OVERFLOW                    VALUE 'Y'.
           88 JSON-OK                          VALUE 'N'.

      *----------------------------------------------------------------*
      *  C5 - Explicit HTTP code-page conversion controls. Inbound
      *  request bodies are converted from the declared client charset
      *  to the region host code page (037 - US EBCDIC); outbound JSON
      *  is emitted as UTF-8 so distributed clients interoperate
      *  deterministically regardless of platform default charset.
      *----------------------------------------------------------------*
       01 WS-HTTP-CONV.
         05 WS-CHARSET-UTF8         PIC X(40) VALUE 'utf-8'.
         05 WS-HOST-CP              PIC X(08) VALUE '037'.

      *----------------------------------------------------------------*
      *  N2 - Response security headers. Every API response carries
      *  account, card, customer, token, or error data and must not be
      *  cached by intermediaries or the client, so an explicit
      *  'Cache-Control: no-store, private' (with a legacy 'Pragma:
      *  no-cache') is written before the body is sent.
      *----------------------------------------------------------------*
       01 WS-RESP-HEADERS.
         05 WS-CC-NAME             PIC X(16) VALUE 'Cache-Control'.
         05 WS-CC-NAME-LEN         PIC S9(08) COMP VALUE 13.
         05 WS-CC-VAL              PIC X(32) VALUE 'no-store, private'.
         05 WS-CC-VAL-LEN          PIC S9(08) COMP VALUE 17.
         05 WS-PRG-NAME            PIC X(16) VALUE 'Pragma'.
         05 WS-PRG-NAME-LEN        PIC S9(08) COMP VALUE 6.
         05 WS-PRG-VAL             PIC X(16) VALUE 'no-cache'.
         05 WS-PRG-VAL-LEN         PIC S9(08) COMP VALUE 8.
      *  N3 - X-Content-Type-Options: nosniff blocks MIME-type
      *  sniffing of the JSON responses (rationale in
      *  docs/decision-log.md).
         05 WS-XCTO-NAME     PIC X(24) VALUE 'X-Content-Type-Options'.
         05 WS-XCTO-NAME-LEN PIC S9(08) COMP VALUE 22.
         05 WS-XCTO-VAL      PIC X(16) VALUE 'nosniff'.
         05 WS-XCTO-VAL-LEN  PIC S9(08) COMP VALUE 7.


      *----------------------------------------------------------------*
      *  N1 - Send-failure telemetry. When WEB SEND fails the client
      *  cannot be told, so a console record is written carrying ONLY
      *  the requestId correlation value and the CICS RESP/RESP2 - never
      *  the response body, route, credentials, or token.
      *----------------------------------------------------------------*
       01 WS-TELEMETRY.
         05 WS-TEL-MSG             PIC X(100) VALUE SPACES.
         05 WS-TEL-LEN             PIC S9(08) COMP VALUE 0.
         05 WS-TEL-PTR             PIC S9(08) COMP VALUE 1.
         05 WS-TEL-RESP-X          PIC -(9).
         05 WS-TEL-REAS-X          PIC -(9).

      *----------------------------------------------------------------*
      *  M6 - Content-Type request-header validation work area. A POST
      *  body must be declared application/json; anything else is 400.
      *----------------------------------------------------------------*
       01 WS-CTYPE-WORK.
         05 WS-CTYPE-NAME           PIC X(32) VALUE 'Content-Type'.
         05 WS-CTYPE-NAME-LEN       PIC S9(08) COMP VALUE 12.
         05 WS-CTYPE-VAL            PIC X(128) VALUE SPACES.
         05 WS-CTYPE-VAL-LEN        PIC S9(08) COMP VALUE 128.
         05 WS-CTYPE-U              PIC X(128) VALUE SPACES.

      *----------------------------------------------------------------*
      *  M5 - Strict bearer-token parse work area. A valid credential
      *  is exactly 64 characters drawn from the COAPISEC token
      *  alphabet [0-9A-Z], with no embedded or trailing padding after
      *  the 7-character 'Bearer ' scheme prefix.
      *----------------------------------------------------------------*
       01 WS-BEARER-WORK.
         05 WS-BEARER-I             PIC S9(04) COMP VALUE 0.
         05 WS-BEARER-J             PIC S9(04) COMP VALUE 0.
         05 WS-BEARER-CH            PIC X(01) VALUE SPACE.
         05 WS-CHK-TALLY            PIC S9(04) COMP VALUE 0.
         05 WS-BEARER-FLAG          PIC X(01) VALUE 'Y'.
           88 BEARER-OK                        VALUE 'Y'.
           88 BEARER-BAD                       VALUE 'N'.
         05 WS-ALLOWED-CHARS        PIC X(36) VALUE
              '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.

      *----------------------------------------------------------------*
      *  M5 - HTTP header browse work area. Used to count how many
      *  Authorization request headers the client sent; a well-formed
      *  request carries exactly one (zero or many is a 401).
      *----------------------------------------------------------------*
       01 WS-HDR-BROWSE.
         05 WS-HDR-COUNT            PIC S9(04) COMP VALUE 0.
         05 WS-HDRB-NAME            PIC X(64)  VALUE SPACES.
         05 WS-HDRB-NAME-LEN        PIC S9(08) COMP VALUE 64.
         05 WS-HDRB-VAL             PIC X(256) VALUE SPACES.
         05 WS-HDRB-VAL-LEN         PIC S9(08) COMP VALUE 256.

      *----------------------------------------------------------------*
      *  M9 - Minimal auth contract save area. The route keys (incl. a
      *  PAN) are saved here and cleared from the commarea for the
      *  duration of the COAPISEC validation LINK, then restored for
      *  dispatch, so credentials/keys the auth service does not need
      *  are never presented to it. Length matches API-REQUEST (64).
      *----------------------------------------------------------------*
       01 WS-SAVE-REQUEST           PIC X(64) VALUE SPACES.

      *----------------------------------------------------------------*
      *  M6 - Strict sign-on body validator work area. The POST /signon
      *  body is parsed by a bounded single-pass state machine over the
      *  complete received body (up to 4096 bytes) rather than a naive
      *  256-byte substring search. It accepts EXACTLY one flat JSON
      *  object { "userId":"..","password":".." }, rejecting nesting,
      *  duplicate keys, unknown keys, trailing garbage, backslash
      *  escapes, and any value outside 1..8 characters. userId and
      *  password (SGNI-USER-ID / SGNI-PASSWORD) are PIC X(08).
      *  State names are held in WS-SB-STATE; whitespace bytes are the
      *  host code-page (037) forms of SPACE/TAB/LF/CR and backslash is
      *  the 037 code point X'E0'.
      *----------------------------------------------------------------*
       01 WS-SIGNON-PARSE.
         05 WS-SB-N                 PIC S9(08) COMP VALUE 0.
         05 WS-SB-I                 PIC S9(08) COMP VALUE 0.
         05 WS-SB-CH                PIC X(01)  VALUE SPACE.
         05 WS-SB-STATE             PIC X(08)  VALUE 'OPEN'.
           88 SB-OPEN                           VALUE 'OPEN'.
           88 SB-KEYCL                          VALUE 'KEYCL'.
           88 SB-INKEY                          VALUE 'INKEY'.
           88 SB-COLON                          VALUE 'COLON'.
           88 SB-VAL                            VALUE 'VAL'.
           88 SB-INVAL                          VALUE 'INVAL'.
           88 SB-COMMACL                        VALUE 'COMMACL'.
           88 SB-KEY                            VALUE 'KEY'.
           88 SB-TRAIL                          VALUE 'TRAIL'.
         05 WS-SB-CURKEY            PIC X(32)  VALUE SPACES.
         05 WS-SB-CURKEY-LEN        PIC S9(04) COMP VALUE 0.
         05 WS-SB-VAL               PIC X(08)  VALUE SPACES.
         05 WS-SB-VAL-LEN           PIC S9(04) COMP VALUE 0.
         05 WS-SB-USER              PIC X(08)  VALUE SPACES.
         05 WS-SB-USER-LEN          PIC S9(04) COMP VALUE 0.
         05 WS-SB-PASS              PIC X(08)  VALUE SPACES.
         05 WS-SB-PASS-LEN          PIC S9(04) COMP VALUE 0.
         05 WS-SB-HAVE-USER         PIC X(01)  VALUE 'N'.
           88 SB-HAVE-USER                      VALUE 'Y'.
         05 WS-SB-HAVE-PASS         PIC X(01)  VALUE 'N'.
           88 SB-HAVE-PASS                      VALUE 'Y'.
         05 WS-SB-FLAG              PIC X(01)  VALUE 'Y'.
           88 SB-BODY-OK                        VALUE 'Y'.
           88 SB-BODY-BAD                       VALUE 'N'.
         05 WS-SB-ISWS              PIC X(01)  VALUE 'N'.
           88 SB-IS-WS                          VALUE 'Y'.
           88 SB-NOT-WS                         VALUE 'N'.
         05 WS-SB-TAB               PIC X(01)  VALUE X'05'.
         05 WS-SB-LF                PIC X(01)  VALUE X'25'.
         05 WS-SB-CR                PIC X(01)  VALUE X'0D'.
         05 WS-SB-ESC               PIC X(01)  VALUE X'E0'.

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
      *  I2 - COJSONUC is invoked by a static literal CALL 'COJSONUC'
      *  and is compiled NODYNAM, so it is statically bound into this
      *  load module by the linkage editor. Consequence: the API build
      *  job must compile COJSONUC first and INCLUDE its object when
      *  binding COAPIRTR, and any change to COJSONUC requires relinking
      *  COAPIRTR (and every other static caller). The build/relink
      *  order is encoded in the API compile JCL and recorded in
      *  docs/decision-log.md; JSON-PARM/JSON-BUFFER above are the
      *  frozen contract that keeps the static bind valid.
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

      *----------------------------------------------------------------*
      *  C1 - Shared JSON assembly buffer. Sized X(96000) to match the
      *  COJSONUC LINKAGE JSON-BUFFER exactly; a mismatch would let the
      *  subprogram append past this program's storage. 96000 bytes
      *  safely holds a capped 50-entry transaction list at worst-case
      *  JSON expansion (see docs/decision-log.md).
      *----------------------------------------------------------------*
       01 JSON-BUFFER.
         05 JB-DATA                 PIC X(96000).
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

      *    C3 - Defense-in-depth: overwrite tokens, PAN, credentials
      *    and PII copied into working storage before the task frees
      *    its storage (complements CSD STORAGECLEAR(YES)).
           PERFORM 6900-SCRUB-SENSITIVE

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
      *    M6 - reject any POST whose body is not declared JSON before
      *    the body itself is received and parsed.
           PERFORM 1150-CHECK-CONTENT-TYPE
           IF STATE-CONTINUE
               MOVE 4096   TO WS-REQ-LEN
               MOVE SPACES TO WS-REQ-BODY
      *        C5 - SRVCONVERT converts the inbound entity body from the
      *        client charset (identified from Content-Type, default
      *        ISO-8859-1) into HOSTCODEPAGE 037 (US EBCDIC) so the JSON
      *        parser always sees host-encoded characters.
               EXEC CICS WEB RECEIVE
                    INTO         (WS-REQ-BODY)
                    LENGTH       (WS-REQ-LEN)
                    MAXLENGTH    (4096)
                    SRVCONVERT
                    HOSTCODEPAGE (WS-HOST-CP)
                    RESP         (WS-RESP-CD)
                    RESP2        (WS-REAS-CD)
               END-EXEC
               IF WS-RESP-CD NOT = DFHRESP(NORMAL)
                   MOVE 400 TO WS-HTTP-STATUS
                   MOVE 'Malformed request body' TO WS-ERR-MSG
                   SET STATE-ERROR TO TRUE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     1150-CHECK-CONTENT-TYPE
      *  M6 - A request body is accepted only when the Content-Type
      *  request header begins with 'application/json' (case-
      *  insensitive; an optional ';charset=...' parameter is allowed).
      *  A missing or mismatched media type is a deterministic 400.
      *----------------------------------------------------------------*
       1150-CHECK-CONTENT-TYPE.
           MOVE 'Content-Type' TO WS-CTYPE-NAME
           MOVE 12  TO WS-CTYPE-NAME-LEN
           MOVE 128 TO WS-CTYPE-VAL-LEN
           MOVE SPACES TO WS-CTYPE-VAL
           MOVE SPACES TO WS-CTYPE-U
           EXEC CICS WEB READ
                HTTPHEADER  (WS-CTYPE-NAME)
                NAMELENGTH  (WS-CTYPE-NAME-LEN)
                VALUE       (WS-CTYPE-VAL)
                VALUELENGTH (WS-CTYPE-VAL-LEN)
                RESP        (WS-RESP-CD)
                RESP2       (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD = DFHRESP(NORMAL)
               MOVE FUNCTION UPPER-CASE(WS-CTYPE-VAL) TO WS-CTYPE-U
           END-IF
      *    M6 - Require the media-type token to be EXACTLY
      *    'application/json'. Matching only the first 16 bytes would
      *    also accept a run-on type such as 'application/jsonx', so the
      *    byte after the token (position 17) must be a delimiter: a
      *    space (bare type) or ';' (a parameter such as
      *    '; charset=utf-8'). Anything else is a 400.
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
             OR WS-CTYPE-U(1:16) NOT = 'APPLICATION/JSON'
             OR (WS-CTYPE-U(17:1) NOT = SPACE
                 AND WS-CTYPE-U(17:1) NOT = ';')
               MOVE 400 TO WS-HTTP-STATUS
               MOVE 'Content-Type must be application/json'
                    TO WS-ERR-MSG
               SET STATE-ERROR TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                       2000-PARSE-ROUTE
      *  Strip the versioned base path and tokenize the remainder on
      *  '/', then resolve the route and validate the path parameter.
      *----------------------------------------------------------------*
       2000-PARSE-ROUTE.
      *    M7 - The versioned base path must be followed by a '/'
      *    separator. This rejects look-alike prefixes such as
      *    /carddemo/api/v1extra/signon (char 17 = 'e', not '/') which
      *    would otherwise share the 16-byte prefix and be mis-routed.
      *    When the guard fails the full path is tokenized instead and
      *    resolves to no known route (-> 400).
           IF WS-PATH(1:16) = WS-BASE-PATH
             AND WS-PATH(17:1) = '/'
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
      *    M7 - Every route enforces an EXACT segment count. WS-SEG0 is
      *    the (always empty) part before the leading '/', so it must be
      *    SPACES; each route additionally pins the trailing segment(s)
      *    to SPACES so that surplus path segments (e.g. an extra
      *    /segment after {id}) fall through to 2900 -> 400 rather than
      *    being silently ignored.
           EVALUATE TRUE
               WHEN WS-SEG0 = SPACES
                    AND WS-SEG1 = 'signon'
                    AND WS-SEG2 = SPACES
                    AND WS-SEG3 = SPACES
                    AND WS-SEG4 = SPACES
                   PERFORM 2110-ROUTE-SIGNON
               WHEN WS-SEG0 = SPACES
                    AND WS-SEG1 = 'accounts'
                    AND WS-SEG2 NOT = SPACES
                    AND WS-SEG3 = 'transactions'
                    AND WS-SEG4 = SPACES
                   PERFORM 2120-ROUTE-TRANLIST
               WHEN WS-SEG0 = SPACES
                    AND WS-SEG1 = 'accounts'
                    AND WS-SEG2 NOT = SPACES
                    AND WS-SEG3 = SPACES
                    AND WS-SEG4 = SPACES
                   PERFORM 2130-ROUTE-ACCT
               WHEN WS-SEG0 = SPACES
                    AND WS-SEG1 = 'customers'
                    AND WS-SEG2 NOT = SPACES
                    AND WS-SEG3 = SPACES
                    AND WS-SEG4 = SPACES
                   PERFORM 2140-ROUTE-CUST
               WHEN WS-SEG0 = SPACES
                    AND WS-SEG1 = 'cards'
                    AND WS-SEG2 NOT = SPACES
                    AND WS-SEG3 = SPACES
                    AND WS-SEG4 = SPACES
                   PERFORM 2150-ROUTE-CARD
               WHEN WS-SEG0 = SPACES
                    AND WS-SEG1 = 'xref'
                    AND WS-SEG2 NOT = SPACES
                    AND WS-SEG3 = SPACES
                    AND WS-SEG4 = SPACES
                   PERFORM 2160-ROUTE-XREF
               WHEN WS-SEG0 = SPACES
                    AND WS-SEG1 = 'transactions'
                    AND WS-SEG2 NOT = SPACES
                    AND WS-SEG3 = SPACES
                    AND WS-SEG4 = SPACES
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
      *        M9 - The token has served its purpose once validated;
      *        clear it (and the raw header) so it is never forwarded to
      *        the inquiry services, which perform no per-user checks.
               IF STATE-CONTINUE
                   PERFORM 3300-CLEAR-TOKEN
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
      *    M4 - Distinguish an expected 'no/oversized credential' (401)
      *    from an unexpected CICS failure (500) instead of mapping
      *    every non-NORMAL response to 401. A missing header (NOTFND)
      *    or a value longer than the 256-byte buffer (LENGERR) is a
      *    malformed/absent credential -> 401; anything else -> 500.
           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   PERFORM 3150-EXTRACT-BEARER
               WHEN DFHRESP(NOTFND)
                   PERFORM 3900-SET-UNAUTHORIZED
               WHEN DFHRESP(LENGERR)
                   PERFORM 3900-SET-UNAUTHORIZED
               WHEN OTHER
                   PERFORM 3950-SET-INTERNAL
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                     3150-EXTRACT-BEARER
      *  Extract the token following the 7-character 'Bearer ' prefix.
      *----------------------------------------------------------------*
       3150-EXTRACT-BEARER.
      *    M5 - Accept ONLY 'Bearer ' followed by EXACTLY 64 characters
      *    drawn from the COAPISEC token alphabet [0-9A-Z], with no
      *    trailing data after the token and exactly one Authorization
      *    header present on the request. Any deviation is a 401.
           SET BEARER-OK TO TRUE
           PERFORM 3160-COUNT-AUTH-HEADERS
           IF BEARER-OK
             AND FUNCTION UPPER-CASE(WS-AUTH-HDR(1:7)) NOT = 'BEARER '
               SET BEARER-BAD TO TRUE
           END-IF
           IF BEARER-OK
               PERFORM 3170-CHECK-TOKEN-CHARS
           END-IF
           IF BEARER-OK
             AND WS-AUTH-HDR(72:185) NOT = SPACES
               SET BEARER-BAD TO TRUE
           END-IF
           IF BEARER-OK
               MOVE WS-AUTH-HDR(8:64) TO API-TOKEN-VALUE
           ELSE
               PERFORM 3900-SET-UNAUTHORIZED
           END-IF.

      *----------------------------------------------------------------*
      *                     3160-COUNT-AUTH-HEADERS
      *  M5 - Browse the inbound request headers and count how many are
      *  named 'Authorization'. A well-formed request carries exactly
      *  one; zero or more than one fails the bearer check (401). This
      *  defends against header-injection where a duplicate credential
      *  could be interpreted differently by an intermediary.
      *----------------------------------------------------------------*
       3160-COUNT-AUTH-HEADERS.
           MOVE 0 TO WS-HDR-COUNT
           EXEC CICS WEB STARTBROWSE HTTPHEADER
                RESP  (WS-RESP-CD)
                RESP2 (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD = DFHRESP(NORMAL)
               PERFORM 3165-BROWSE-ONE-HEADER
                   UNTIL WS-RESP-CD NOT = DFHRESP(NORMAL)
               EXEC CICS WEB ENDBROWSE HTTPHEADER
                    RESP  (WS-RESP-CD)
                    RESP2 (WS-REAS-CD)
               END-EXEC
           END-IF
           IF WS-HDR-COUNT NOT = 1
               SET BEARER-BAD TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                     3165-BROWSE-ONE-HEADER
      *  Read one header line; count it when its name is EXACTLY the
      *  13-character token 'Authorization' (case-insensitive), so that
      *  look-alike names such as 'Authorization-Info' are not counted.
      *----------------------------------------------------------------*
       3165-BROWSE-ONE-HEADER.
           MOVE SPACES TO WS-HDRB-NAME
           MOVE 64  TO WS-HDRB-NAME-LEN
           MOVE SPACES TO WS-HDRB-VAL
           MOVE 256 TO WS-HDRB-VAL-LEN
           EXEC CICS WEB READNEXT HTTPHEADER
                HTTPHEADER  (WS-HDRB-NAME)
                NAMELENGTH  (WS-HDRB-NAME-LEN)
                VALUE       (WS-HDRB-VAL)
                VALUELENGTH (WS-HDRB-VAL-LEN)
                RESP        (WS-RESP-CD)
                RESP2       (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD = DFHRESP(NORMAL)
             AND WS-HDRB-NAME-LEN = 13
             AND FUNCTION UPPER-CASE(WS-HDRB-NAME(1:13)) =
                 'AUTHORIZATION'
               ADD 1 TO WS-HDR-COUNT
           END-IF.

      *----------------------------------------------------------------*
      *                     3170-CHECK-TOKEN-CHARS
      *  Verify each of the 64 token bytes (positions 8..71) is a member
      *  of the COAPISEC alphabet [0-9A-Z]; a space or any other byte
      *  fails. INSPECT tallies occurrences of the byte in the allowed
      *  set - a tally of zero means the byte is not permitted.
      *----------------------------------------------------------------*
       3170-CHECK-TOKEN-CHARS.
           PERFORM VARYING WS-BEARER-I FROM 1 BY 1
                   UNTIL WS-BEARER-I > 64 OR BEARER-BAD
               COMPUTE WS-BEARER-J = 7 + WS-BEARER-I
               MOVE WS-AUTH-HDR(WS-BEARER-J:1) TO WS-BEARER-CH
               MOVE 0 TO WS-CHK-TALLY
               INSPECT WS-ALLOWED-CHARS
                   TALLYING WS-CHK-TALLY FOR ALL WS-BEARER-CH
               IF WS-CHK-TALLY = 0
                   SET BEARER-BAD TO TRUE
               END-IF
           END-PERFORM.

      *----------------------------------------------------------------*
      *                     3200-VALIDATE-TOKEN
      *  LINK COAPISEC in VALIDATE mode. A non-OK status is a 401; a
      *  LINK failure is a 500 (no internal detail leaked).
      *----------------------------------------------------------------*
       3200-VALIDATE-TOKEN.
      *    M9 - Present COAPISEC a minimal contract: only the service
      *    code and the bearer token. Save the route keys (incl. PAN),
      *    clear them and the payload for the LINK, then restore them
      *    for dispatch so the auth service never receives keys it does
      *    not need.
           MOVE API-REQUEST TO WS-SAVE-REQUEST
           MOVE 'VALIDATE' TO API-SERVICE-CODE
           MOVE SPACES     TO API-ERR-MESSAGE
           MOVE ZEROS      TO API-REQ-ACCT-ID
           MOVE ZEROS      TO API-REQ-CUST-ID
           MOVE SPACES     TO API-REQ-CARD-NUM
           MOVE SPACES     TO API-REQ-TRAN-ID
           MOVE SPACES     TO API-PAYLOAD
           EXEC CICS LINK
                PROGRAM  ('COAPISEC')
                COMMAREA (API-COMMAREA)
                LENGTH   (LENGTH OF API-COMMAREA)
                RESP     (WS-RESP-CD)
                RESP2    (WS-REAS-CD)
           END-EXEC
           MOVE WS-SAVE-REQUEST TO API-REQUEST
      *    M4 - A LINK failure, or an internal (500) status returned by
      *    COAPISEC itself, is propagated as 500; only a genuine
      *    credential failure (a non-OK status that is not 500) is
      *    reported to the caller as 401.
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               PERFORM 3950-SET-INTERNAL
           ELSE
               IF API-HTTP-SERVER-ERROR
                   PERFORM 3950-SET-INTERNAL
               ELSE
                   IF NOT API-HTTP-OK
                       PERFORM 3900-SET-UNAUTHORIZED
                   END-IF
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
      *                     3950-SET-INTERNAL
      *  M4 - Unexpected failure during authorization (LINK failure or
      *  an internal error surfaced by COAPISEC). Reports a generic 500
      *  with no internal detail; RESP2 is never surfaced to the client.
      *----------------------------------------------------------------*
       3950-SET-INTERNAL.
           MOVE 500 TO WS-HTTP-STATUS
           MOVE SPACES TO WS-ERR-MSG
           SET STATE-ERROR TO TRUE.

      *----------------------------------------------------------------*
      *                     3300-CLEAR-TOKEN
      *  M9 - Clear the validated bearer token, its identity fields, and
      *  the raw Authorization/header-browse buffers as soon as the
      *  token has been validated, so none of it flows to the inquiry
      *  services. Sign-on never reaches here (it mints a token that
      *  must survive to serialization).
      *----------------------------------------------------------------*
       3300-CLEAR-TOKEN.
           MOVE SPACES TO API-TOKEN-VALUE
           MOVE SPACES TO API-TOKEN-USER-ID
           MOVE SPACES TO API-TOKEN-USER-TYPE
           MOVE SPACES TO API-TOKEN-EXPIRY-TS
           MOVE SPACES TO WS-AUTH-HDR
           MOVE SPACES TO WS-HDRB-NAME
           MOVE SPACES TO WS-HDRB-VAL
           MOVE SPACE  TO WS-BEARER-CH.

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
      *    M6 - Parse and fully validate the sign-on body with a bounded
      *    state machine (4160). It sets SGNI-USER-ID / SGNI-PASSWORD on
      *    success or drives 4900-SET-BAD-BODY (400) on any violation.
           MOVE SPACES TO API-SIGNON-REQUEST
           PERFORM 4160-PARSE-SIGNON-BODY
           IF STATE-CONTINUE
               PERFORM 4180-LINK-SIGNON
           END-IF
           MOVE SPACES TO SGNI-PASSWORD
           MOVE SPACES TO WS-REQ-BODY.

      *----------------------------------------------------------------*
      *                     4160-PARSE-SIGNON-BODY
      *  M6 - Bounded single-pass validator for the sign-on JSON body.
      *  Scans the COMPLETE received body (1..WS-SB-N, capped at 4096)
      *  through the WS-SB-STATE machine, then finalizes. On success the
      *  extracted credentials are moved to SGNI-USER-ID/SGNI-PASSWORD;
      *  on ANY structural or length violation 4900-SET-BAD-BODY sets a
      *  deterministic 400.
      *----------------------------------------------------------------*
       4160-PARSE-SIGNON-BODY.
           SET SB-BODY-OK TO TRUE
           MOVE 'OPEN' TO WS-SB-STATE
           MOVE SPACES TO WS-SB-CURKEY
           MOVE 0      TO WS-SB-CURKEY-LEN
           MOVE SPACES TO WS-SB-VAL
           MOVE 0      TO WS-SB-VAL-LEN
           MOVE SPACES TO WS-SB-USER WS-SB-PASS
           MOVE 0      TO WS-SB-USER-LEN WS-SB-PASS-LEN
           MOVE 'N'    TO WS-SB-HAVE-USER WS-SB-HAVE-PASS
           MOVE WS-REQ-LEN TO WS-SB-N
           IF WS-SB-N > 4096
               MOVE 4096 TO WS-SB-N
           END-IF
           PERFORM VARYING WS-SB-I FROM 1 BY 1
                   UNTIL WS-SB-I > WS-SB-N OR SB-BODY-BAD
               MOVE WS-REQ-BODY(WS-SB-I:1) TO WS-SB-CH
               PERFORM 4162-CLASSIFY-WS
               PERFORM 4164-SCAN-STEP
           END-PERFORM
           IF SB-BODY-OK
               PERFORM 4166-FINALIZE-BODY
           END-IF
           IF SB-BODY-OK
               MOVE WS-SB-USER TO SGNI-USER-ID
               MOVE WS-SB-PASS TO SGNI-PASSWORD
           ELSE
               PERFORM 4900-SET-BAD-BODY
           END-IF.

      *----------------------------------------------------------------*
      *                     4162-CLASSIFY-WS
      *  Flag whether the current byte is JSON insignificant whitespace
      *  (space, tab, line feed, carriage return) in host code page 037.
      *----------------------------------------------------------------*
       4162-CLASSIFY-WS.
           IF WS-SB-CH = SPACE OR WS-SB-CH = WS-SB-TAB
              OR WS-SB-CH = WS-SB-LF OR WS-SB-CH = WS-SB-CR
               SET SB-IS-WS TO TRUE
           ELSE
               SET SB-NOT-WS TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                     4164-SCAN-STEP
      *  Dispatch the current byte to the handler for the current state.
      *----------------------------------------------------------------*
       4164-SCAN-STEP.
           EVALUATE TRUE
               WHEN SB-OPEN
                   PERFORM 4170-ST-OPEN
               WHEN SB-KEYCL
                   PERFORM 4171-ST-KEYCL
               WHEN SB-INKEY
                   PERFORM 4172-ST-INKEY
               WHEN SB-COLON
                   PERFORM 4173-ST-COLON
               WHEN SB-VAL
                   PERFORM 4174-ST-VAL
               WHEN SB-INVAL
                   PERFORM 4175-ST-INVAL
               WHEN SB-COMMACL
                   PERFORM 4176-ST-COMMACL
               WHEN SB-KEY
                   PERFORM 4177-ST-KEY
               WHEN SB-TRAIL
                   PERFORM 4178-ST-TRAIL
               WHEN OTHER
                   SET SB-BODY-BAD TO TRUE
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                     4166-FINALIZE-BODY
      *  After the scan the machine must rest in TRAIL (closed brace);
      *  both required fields must be present, and each credential must
      *  be 1..8 characters.
      *----------------------------------------------------------------*
       4166-FINALIZE-BODY.
           IF NOT SB-TRAIL
               SET SB-BODY-BAD TO TRUE
           END-IF
           IF SB-BODY-OK
             AND (NOT SB-HAVE-USER OR NOT SB-HAVE-PASS)
               SET SB-BODY-BAD TO TRUE
           END-IF
           IF SB-BODY-OK
             AND (WS-SB-USER-LEN < 1 OR WS-SB-USER-LEN > 8)
               SET SB-BODY-BAD TO TRUE
           END-IF
           IF SB-BODY-OK
             AND (WS-SB-PASS-LEN < 1 OR WS-SB-PASS-LEN > 8)
               SET SB-BODY-BAD TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *  State handlers. Each consumes exactly one byte (WS-SB-CH) and
      *  advances WS-SB-STATE or fails the body (SB-BODY-BAD).
      *----------------------------------------------------------------*
       4170-ST-OPEN.
           IF SB-IS-WS
               CONTINUE
           ELSE
               IF WS-SB-CH = '{'
                   MOVE 'KEYCL' TO WS-SB-STATE
               ELSE
                   SET SB-BODY-BAD TO TRUE
               END-IF
           END-IF.

       4171-ST-KEYCL.
           IF SB-IS-WS
               CONTINUE
           ELSE
               IF WS-SB-CH = '"'
                   MOVE 'INKEY' TO WS-SB-STATE
                   MOVE SPACES  TO WS-SB-CURKEY
                   MOVE 0       TO WS-SB-CURKEY-LEN
               ELSE
                   IF WS-SB-CH = '}'
                       MOVE 'TRAIL' TO WS-SB-STATE
                   ELSE
                       SET SB-BODY-BAD TO TRUE
                   END-IF
               END-IF
           END-IF.

       4172-ST-INKEY.
           IF WS-SB-CH = '"'
               MOVE 'COLON' TO WS-SB-STATE
           ELSE
               IF WS-SB-CH = WS-SB-ESC
                   SET SB-BODY-BAD TO TRUE
               ELSE
                   IF WS-SB-CURKEY-LEN >= 32
                       SET SB-BODY-BAD TO TRUE
                   ELSE
                       ADD 1 TO WS-SB-CURKEY-LEN
                       MOVE WS-SB-CH
                            TO WS-SB-CURKEY(WS-SB-CURKEY-LEN:1)
                   END-IF
               END-IF
           END-IF.

       4173-ST-COLON.
           IF SB-IS-WS
               CONTINUE
           ELSE
               IF WS-SB-CH = ':'
                   MOVE 'VAL' TO WS-SB-STATE
               ELSE
                   SET SB-BODY-BAD TO TRUE
               END-IF
           END-IF.

       4174-ST-VAL.
           IF SB-IS-WS
               CONTINUE
           ELSE
               IF WS-SB-CH = '"'
                   MOVE 'INVAL' TO WS-SB-STATE
                   MOVE SPACES  TO WS-SB-VAL
                   MOVE 0       TO WS-SB-VAL-LEN
               ELSE
                   SET SB-BODY-BAD TO TRUE
               END-IF
           END-IF.

       4175-ST-INVAL.
           IF WS-SB-CH = '"'
               PERFORM 4179-COMMIT-PAIR
           ELSE
               IF WS-SB-CH = WS-SB-ESC
                   SET SB-BODY-BAD TO TRUE
               ELSE
                   IF WS-SB-VAL-LEN >= 8
                       SET SB-BODY-BAD TO TRUE
                   ELSE
                       ADD 1 TO WS-SB-VAL-LEN
                       MOVE WS-SB-CH TO WS-SB-VAL(WS-SB-VAL-LEN:1)
                   END-IF
               END-IF
           END-IF.

       4176-ST-COMMACL.
           IF SB-IS-WS
               CONTINUE
           ELSE
               IF WS-SB-CH = ','
                   MOVE 'KEY' TO WS-SB-STATE
               ELSE
                   IF WS-SB-CH = '}'
                       MOVE 'TRAIL' TO WS-SB-STATE
                   ELSE
                       SET SB-BODY-BAD TO TRUE
                   END-IF
               END-IF
           END-IF.

       4177-ST-KEY.
           IF SB-IS-WS
               CONTINUE
           ELSE
               IF WS-SB-CH = '"'
                   MOVE 'INKEY' TO WS-SB-STATE
                   MOVE SPACES  TO WS-SB-CURKEY
                   MOVE 0       TO WS-SB-CURKEY-LEN
               ELSE
                   SET SB-BODY-BAD TO TRUE
               END-IF
           END-IF.

       4178-ST-TRAIL.
           IF SB-IS-WS
               CONTINUE
           ELSE
               SET SB-BODY-BAD TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                     4179-COMMIT-PAIR
      *  A complete "key":"value" pair has been read. Match the key to
      *  exactly one required field, rejecting duplicates and any
      *  unknown key, then expect a comma or the closing brace.
      *----------------------------------------------------------------*
       4179-COMMIT-PAIR.
           EVALUATE TRUE
               WHEN WS-SB-CURKEY-LEN = 6
                    AND WS-SB-CURKEY(1:6) = 'userId'
                   IF SB-HAVE-USER
                       SET SB-BODY-BAD TO TRUE
                   ELSE
                       SET SB-HAVE-USER TO TRUE
                       MOVE WS-SB-VAL     TO WS-SB-USER
                       MOVE WS-SB-VAL-LEN TO WS-SB-USER-LEN
                   END-IF
               WHEN WS-SB-CURKEY-LEN = 8
                    AND WS-SB-CURKEY(1:8) = 'password'
                   IF SB-HAVE-PASS
                       SET SB-BODY-BAD TO TRUE
                   ELSE
                       SET SB-HAVE-PASS TO TRUE
                       MOVE WS-SB-VAL     TO WS-SB-PASS
                       MOVE WS-SB-VAL-LEN TO WS-SB-PASS-LEN
                   END-IF
               WHEN OTHER
                   SET SB-BODY-BAD TO TRUE
           END-EVALUATE
           IF SB-BODY-OK
               MOVE 'COMMACL' TO WS-SB-STATE
           END-IF.

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
      *  C1 - The response container buffer is sized for the capped
      *  maximum of 50 entries (matching TRAN-LIST-ENTRY OCCURS 0 TO 50
      *  and JB-DATA X(96000)); a larger preset would over-size the ODO
      *  group and mis-drive JSON assembly.
      *  M8 - Receiving areas are initialized first, and the status
      *  container length, the response container length, and the
      *  returned count/truncated fields are all range/layout checked
      *  before the list is trusted. The list service's HTTP status is
      *  authoritative only once these checks pass.
      *----------------------------------------------------------------*
       4320-GET-LIST-RESULT.
           INITIALIZE API-TRAN-LIST-STATUS
           MOVE 0   TO TRAN-LIST-COUNT
           MOVE 'N' TO TRAN-LIST-TRUNCATED
           MOVE 0   TO TRAN-LIST-ACCT-ID
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
               IF WS-CONT-LEN NOT = LENGTH OF API-TRAN-LIST-STATUS
                   MOVE 500 TO WS-HTTP-STATUS
                   MOVE SPACES TO WS-ERR-MSG
               ELSE
                   PERFORM 4330-GET-LIST-BODY
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     4330-GET-LIST-BODY
      *  Calibrate the fixed header length and per-entry length from the
      *  copybook itself, size the receive buffer for the 50-entry cap,
      *  then read the response container.
      *----------------------------------------------------------------*
       4330-GET-LIST-BODY.
           MOVE 0 TO TRAN-LIST-COUNT
           MOVE LENGTH OF API-TRAN-LIST TO WS-TRLIST-HDR-LEN
           MOVE 1 TO TRAN-LIST-COUNT
           COMPUTE WS-TRENTRY-LEN =
               LENGTH OF API-TRAN-LIST - WS-TRLIST-HDR-LEN
           MOVE 50 TO TRAN-LIST-COUNT
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
               PERFORM 4340-VALIDATE-LIST
           END-IF.

      *----------------------------------------------------------------*
      *                     4340-VALIDATE-LIST
      *  M8 - Trust the ODO list only after the returned count is within
      *  [0,50], the truncated flag is exactly 'N' or 'Y', and the
      *  returned FLENGTH equals the fixed header plus the exact number
      *  of fixed-length entries. Any inconsistency is a generic 500.
      *----------------------------------------------------------------*
       4340-VALIDATE-LIST.
           IF TRAN-LIST-COUNT > 50
               MOVE 500 TO WS-HTTP-STATUS
               MOVE SPACES TO WS-ERR-MSG
           ELSE
               IF NOT (TRAN-LIST-COMPLETE OR TRAN-LIST-WAS-TRUNCATED)
                   MOVE 500 TO WS-HTTP-STATUS
                   MOVE SPACES TO WS-ERR-MSG
               ELSE
                   COMPUTE WS-EXP-LIST-LEN =
                       WS-TRLIST-HDR-LEN
                       + (TRAN-LIST-COUNT * WS-TRENTRY-LEN)
                   IF WS-CONT-LEN2 NOT = WS-EXP-LIST-LEN
                       MOVE 500 TO WS-HTTP-STATUS
                       MOVE SPACES TO WS-ERR-MSG
                   ELSE
                       PERFORM 4350-APPLY-LIST-STATUS
                   END-IF
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     4350-APPLY-LIST-STATUS
      *  The validated list service HTTP status becomes the response
      *  status; a non-200 carries the service error message.
      *----------------------------------------------------------------*
       4350-APPLY-LIST-STATUS.
           MOVE TRLS-HTTP-STATUS TO WS-HTTP-STATUS
           IF WS-HTTP-STATUS NOT = 200
               MOVE TRLS-ERR-MESSAGE TO WS-ERR-MSG
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
      *  Send the serialized JSON. C5 - SRVCONVERT converts the body
      *  from host code page 037 to CHARACTERSET utf-8 so distributed
      *  clients receive interoperable UTF-8 JSON and Content-Type is
      *  emitted as 'application/json; charset=utf-8'. N2 - the no-store
      *  cache headers are written first. N1 - a WEB SEND failure cannot
      *  be reported to the client, so it is captured and recorded as
      *  requestId-safe console telemetry.
      *----------------------------------------------------------------*
       6000-SEND-RESPONSE.
           MOVE WS-HTTP-STATUS TO WS-STATUSCODE
           PERFORM 6100-SET-STATUS-TEXT
           PERFORM 6050-WRITE-SEC-HEADERS
           EXEC CICS WEB SEND
                FROM         (JB-DATA)
                FROMLENGTH   (JB-LEN)
                MEDIATYPE    (WS-MEDIATYPE)
                SRVCONVERT
                CHARACTERSET (WS-CHARSET-UTF8)
                HOSTCODEPAGE (WS-HOST-CP)
                STATUSCODE   (WS-STATUSCODE)
                STATUSTEXT   (WS-STATUS-TEXT)
                STATUSLEN    (WS-STATUS-TEXT-LEN)
                RESP         (WS-RESP-CD)
                RESP2        (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               PERFORM 6060-LOG-SEND-FAILURE
           END-IF.

      *----------------------------------------------------------------*
      *                    6050-WRITE-SEC-HEADERS
      *  N2 - Write the no-store cache-policy headers and the
      *  X-Content-Type-Options: nosniff header before the body is
      *  sent. A header-write failure is non-fatal to the response and
      *  is intentionally not escalated.
      *----------------------------------------------------------------*
       6050-WRITE-SEC-HEADERS.
           EXEC CICS WEB WRITE
                HTTPHEADER  (WS-CC-NAME)
                NAMELENGTH  (WS-CC-NAME-LEN)
                VALUE       (WS-CC-VAL)
                VALUELENGTH (WS-CC-VAL-LEN)
                RESP        (WS-RESP-CD)
                RESP2       (WS-REAS-CD)
           END-EXEC
           EXEC CICS WEB WRITE
                HTTPHEADER  (WS-PRG-NAME)
                NAMELENGTH  (WS-PRG-NAME-LEN)
                VALUE       (WS-PRG-VAL)
                VALUELENGTH (WS-PRG-VAL-LEN)
                RESP        (WS-RESP-CD)
                RESP2       (WS-REAS-CD)
           END-EXEC
           EXEC CICS WEB WRITE
                HTTPHEADER  (WS-XCTO-NAME)
                NAMELENGTH  (WS-XCTO-NAME-LEN)
                VALUE       (WS-XCTO-VAL)
                VALUELENGTH (WS-XCTO-VAL-LEN)
                RESP        (WS-RESP-CD)
                RESP2       (WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                    6060-LOG-SEND-FAILURE
      *  N1 - Record a WEB SEND failure to the system console with only
      *  the requestId correlation value and the CICS RESP/RESP2. No
      *  response body, route, credential, or token data is included.
      *----------------------------------------------------------------*
       6060-LOG-SEND-FAILURE.
           MOVE SPACES     TO WS-TEL-MSG
           MOVE WS-RESP-CD TO WS-TEL-RESP-X
           MOVE WS-REAS-CD TO WS-TEL-REAS-X
           MOVE 1 TO WS-TEL-PTR
           STRING 'COAPIRTR WEB SEND failed req=' DELIMITED BY SIZE
                  WS-REQ-ID                        DELIMITED BY SIZE
                  ' resp='                         DELIMITED BY SIZE
                  WS-TEL-RESP-X                    DELIMITED BY SIZE
                  ' resp2='                        DELIMITED BY SIZE
                  WS-TEL-REAS-X                    DELIMITED BY SIZE
               INTO WS-TEL-MSG
               WITH POINTER WS-TEL-PTR
           END-STRING
           SUBTRACT 1 FROM WS-TEL-PTR GIVING WS-TEL-LEN
           EXEC CICS WRITE OPERATOR
                TEXT       (WS-TEL-MSG)
                TEXTLENGTH (WS-TEL-LEN)
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
      *                    6900-SCRUB-SENSITIVE
      *  C3 - Defense-in-depth: overwrite every buffer that may hold a
      *  credential, token, PAN, PII, or serialized sensitive payload
      *  before the task returns and its storage is freed or reused.
      *  This complements the CSD STORAGECLEAR(YES) attribute. The
      *  requestId is not sensitive and is intentionally retained for
      *  correlation.
      *----------------------------------------------------------------*
       6900-SCRUB-SENSITIVE.
      *    Inbound request material (the body may carry the password;
      *    the header carries the bearer token).
           MOVE SPACES TO WS-REQ-BODY
           MOVE SPACES TO WS-AUTH-HDR
           MOVE SPACES TO WS-CTYPE-VAL
           MOVE SPACES TO WS-CTYPE-U
           MOVE SPACES TO WS-HDRB-NAME
           MOVE SPACES TO WS-HDRB-VAL
      *    Parsed sign-on credentials.
           MOVE SPACES TO WS-SB-CURKEY
           MOVE SPACES TO WS-SB-VAL
           MOVE SPACES TO WS-SB-USER
           MOVE SPACES TO WS-SB-PASS
           MOVE SPACES TO API-SIGNON-REQUEST
      *    Commarea token and generic payload buffer.
           MOVE SPACES TO API-TOKEN-VALUE
           MOVE SPACES TO API-TOKEN-USER-ID
           MOVE SPACES TO API-TOKEN-USER-TYPE
           MOVE SPACES TO API-TOKEN-EXPIRY-TS
           MOVE SPACES TO API-PAYLOAD
      *    Per-service response areas (PAN, SSN, govt id, token, PII).
           MOVE SPACES TO API-ACCT-RESPONSE
           MOVE SPACES TO API-CUST-RESPONSE
           MOVE SPACES TO API-CARD-RESPONSE
           MOVE SPACES TO API-XREF-RESPONSE
           MOVE SPACES TO API-SIGNON-RESPONSE
           MOVE SPACES TO API-TRAN-RESPONSE
           MOVE 50     TO TRAN-LIST-COUNT
           MOVE SPACES TO API-TRAN-LIST
      *    Assembled JSON body (contains every emitted value).
           MOVE SPACES TO JB-DATA.

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
