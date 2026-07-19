      ******************************************************************
      * Program     : COAPISEC.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : REST API authentication and bearer-token service
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
      *   COAPISEC is a net-new, additive service program for the
      *   read-only REST/JSON API layer of CardDemo. It is invoked by
      *   the router COAPIRTR via EXEC CICS LINK passing the shared
      *   API-COMMAREA (copybook COAPICOM). It performs two functions
      *   selected by API-SERVICE-CODE:
      *     'SIGNON  ' - validate USRSEC credentials and issue a
      *                  short-lived opaque bearer token.
      *     'VALIDATE' - verify a presented bearer token (exists and
      *                  not expired) and return the bound identity.
      *
      * DATA ACCESS RULES (IMPORTANT - read carefully)
      *   VSAM access here is STRICTLY READ-ONLY. The ONLY VSAM verb
      *   used is EXEC CICS READ DATASET('USRSEC'). There is NO
      *   WRITE / REWRITE / DELETE / STARTBR against USRSEC or any
      *   other business dataset.
      *   The bearer-token registry is NOT VSAM: it lives in a CICS
      *   Temporary Storage Queue (TSQ, MAIN). WRITEQ TS / READQ TS /
      *   DELETEQ TS against the token queue are permitted and are the
      *   ONLY write operations in this program. The read-only mandate
      *   applies to the six VSAM datasets, never to TSQs.
      *
      * SECURITY
      *   The presented password is used only for an equality compare
      *   against SEC-USR-PWD. It is never moved into the token, the
      *   sign-on response, API-PAYLOAD, a log, or a DISPLAY. On any
      *   authentication failure a generic 401 is returned (the same
      *   for unknown user and wrong password) to avoid user
      *   enumeration. RESP/RESP2 are never surfaced to the caller.
      *
      * TOKEN MODEL (demonstration-grade, no external crypto)
      *   The token is a 64-char opaque, non-sequential string derived
      *   from the absolute store clock, the CICS task number, the user
      *   id and a per-position mixing function. It is NOT
      *   cryptographically signed (no crypto library is permitted).
      *   It is stored in a MAIN TSQ registry keyed by a short queue
      *   name derived from a numeric hash of the token, making lookup
      *   O(1) and the token opaque and revocable.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COAPISEC.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

       01 WS-VARIABLES.
         05 WS-PGMNAME              PIC X(08) VALUE 'COAPISEC'.
      *  USRSEC is the security credential VSAM file (READ ONLY).
         05 WS-USRSEC-FILE          PIC X(08) VALUE 'USRSEC  '.
         05 WS-RESP-CD              PIC S9(09) COMP VALUE ZEROS.
         05 WS-REAS-CD              PIC S9(09) COMP VALUE ZEROS.
         05 WS-USER-ID              PIC X(08) VALUE SPACES.
         05 WS-USER-PWD             PIC X(08) VALUE SPACES.
      *  Result of the credential check drives the HTTP status.
         05 WS-AUTH-STATUS          PIC X(01) VALUE 'E'.
           88 AUTH-OK                         VALUE 'O'.
           88 AUTH-BAD                        VALUE 'B'.
           88 AUTH-ERROR                      VALUE 'E'.

      *----------------------------------------------------------------*
      *  Token build / hash work area
      *----------------------------------------------------------------*
       01 WS-TOKEN-WORK.
         05 WS-TOKEN-VALUE          PIC X(64) VALUE SPACES.
         05 WS-ALPHABET             PIC X(36) VALUE
              '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.
         05 WS-STATE                PIC 9(09) VALUE ZEROS.
         05 WS-IDX                  PIC 9(04) VALUE ZEROS.
         05 WS-POS                  PIC 9(04) VALUE ZEROS.
         05 WS-ORD                  PIC 9(05) VALUE ZEROS.
         05 WS-HASH                 PIC 9(08) VALUE ZEROS.
         05 WS-HASH-X               PIC 9(08) VALUE ZEROS.
         05 WS-QNAME                PIC X(16) VALUE SPACES.
         05 WS-TASK-NUM             PIC 9(07) VALUE ZEROS.
         05 WS-USERID-NUM           PIC 9(09) VALUE ZEROS.
         05 WS-ABSTIME-9            PIC 9(15) VALUE ZEROS.
         05 WS-ONE-CHAR             PIC X(01) VALUE SPACES.
      *  Halfword length work field for TSQ WRITEQ / READQ calls.
         05 WS-Q-LEN                PIC S9(04) COMP VALUE ZEROS.

      *----------------------------------------------------------------*
      *  Time / expiry work area. ABSTIME is milliseconds since 1900.
      *----------------------------------------------------------------*
       01 WS-TIME-WORK.
         05 WS-ABSTIME              PIC S9(15) COMP-3 VALUE ZEROS.
         05 WS-EXP-ABSTIME          PIC S9(15) COMP-3 VALUE ZEROS.
         05 WS-FMT-ABSTIME          PIC S9(15) COMP-3 VALUE ZEROS.
      *  Fixed token lifetime = 900000 ms (900 seconds / 15 minutes).
         05 WS-TOKEN-LIFE-MS        PIC S9(15) COMP-3 VALUE 900000.
         05 WS-FT-DATE              PIC X(10) VALUE SPACES.
         05 WS-FT-TIME              PIC X(08) VALUE SPACES.
         05 WS-TS-26                PIC X(26) VALUE SPACES.
         05 WS-TS-NOW               PIC X(26) VALUE SPACES.

      *----------------------------------------------------------------*
      *  Token registry record written to / read from the MAIN TSQ.
      *  This is TSQ storage, NOT a VSAM dataset.
      *----------------------------------------------------------------*
       01 WS-TOKEN-RECORD.
         05 TOK-VALUE               PIC X(64) VALUE SPACES.
         05 TOK-USER-ID             PIC X(08) VALUE SPACES.
         05 TOK-USER-TYPE           PIC X(01) VALUE SPACES.
         05 TOK-EXPIRY-TS           PIC X(26) VALUE SPACES.

      *  Security-user record layout (READ target for USRSEC).
       COPY CSUSR01Y.

      *  Sign-on request / response work areas (COPY in WORKING-STORAGE
      *  per the API contract). The password lives only in the request.
       COPY COAPSGNY.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
       LINKAGE SECTION.
      *  DFHCOMMAREA is the CICS-addressed anchor for the COMMAREA that
      *  the router passes on EXEC CICS LINK. COPY COAPICOM brings in
      *  01 API-COMMAREA; 0000-MAIN maps API-COMMAREA onto DFHCOMMAREA
      *  with SET ADDRESS so the typed contract overlays the commarea.
       01 DFHCOMMAREA               PIC X(01).

       COPY COAPICOM.

      *----------------------------------------------------------------*
      *                      PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION.

      *----------------------------------------------------------------*
      *                          0000-MAIN
      *  Establish addressability to the API COMMAREA passed on LINK
      *  and dispatch on the requested sub-function. This program is
      *  LINKed, so EXEC CICS RETURN (no TRANSID) hands the updated
      *  COMMAREA back to the router COAPIRTR.
      *----------------------------------------------------------------*
       0000-MAIN.

           IF EIBCALEN > 0
               SET ADDRESS OF API-COMMAREA TO ADDRESS OF DFHCOMMAREA
               PERFORM 0100-DISPATCH
           END-IF

           EXEC CICS RETURN
           END-EXEC.

      *----------------------------------------------------------------*
      *                        0100-DISPATCH
      *  Route on API-SERVICE-CODE. An unknown code fails safe as 400.
      *----------------------------------------------------------------*
       0100-DISPATCH.

           EVALUATE API-SERVICE-CODE
               WHEN 'SIGNON  '
                   PERFORM 1000-SIGNON-ISSUE
               WHEN 'VALIDATE'
                   PERFORM 2000-VALIDATE-TOKEN
               WHEN OTHER
                   PERFORM 0200-BAD-REQUEST
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                       0200-BAD-REQUEST
      *----------------------------------------------------------------*
       0200-BAD-REQUEST.

           SET API-HTTP-BAD-REQUEST TO TRUE
           MOVE -1                  TO API-RETURN-CODE
           MOVE 'APIREQ01'          TO API-ERR-CODE
           MOVE 'Unsupported API service operation'
                                    TO API-ERR-MESSAGE.

      *----------------------------------------------------------------*
      *                      1000-SIGNON-ISSUE
      *  Extract the sign-on request from API-PAYLOAD, validate the
      *  credentials against USRSEC (read-only) and, on success, mint
      *  and register a bearer token, then populate the API-TOKEN group
      *  and the sign-on response (returned in API-PAYLOAD).
      *----------------------------------------------------------------*
       1000-SIGNON-ISSUE.

      *  The router places API-SIGNON-REQUEST into API-PAYLOAD.
           MOVE API-PAYLOAD         TO API-SIGNON-REQUEST
      *  Scrub the inbound password out of the shared COMMAREA so it
      *  can never be echoed or serialized on any response path.
           MOVE SPACES              TO API-PAYLOAD

      *  Upper-case id and password before the compare, mirroring
      *  COSGN00C, because USRSEC keys and passwords are stored upper.
           MOVE FUNCTION UPPER-CASE (SGNI-USER-ID)  TO WS-USER-ID
           MOVE FUNCTION UPPER-CASE (SGNI-PASSWORD) TO WS-USER-PWD

           PERFORM 1100-READ-USRSEC

           EVALUATE TRUE
               WHEN AUTH-OK
                   PERFORM 1200-MINT-TOKEN
                   PERFORM 1300-WRITE-REGISTRY
                   IF AUTH-OK
                       PERFORM 1400-BUILD-SIGNON-RSP
                   ELSE
                       PERFORM 1950-SERVER-ERROR
                   END-IF
               WHEN AUTH-BAD
                   PERFORM 1900-UNAUTHORIZED
               WHEN OTHER
                   PERFORM 1950-SERVER-ERROR
           END-EVALUATE

      *  Scrub password work fields regardless of the outcome.
           MOVE SPACES              TO WS-USER-PWD
           MOVE SPACES              TO SGNI-PASSWORD.

      *----------------------------------------------------------------*
      *                      1100-READ-USRSEC
      *  READ-ONLY keyed access to the USRSEC VSAM file. This EXEC CICS
      *  READ is the ONLY VSAM verb in the entire program.
      *----------------------------------------------------------------*
       1100-READ-USRSEC.

           EXEC CICS READ
                DATASET   (WS-USRSEC-FILE)
                INTO      (SEC-USER-DATA)
                LENGTH    (LENGTH OF SEC-USER-DATA)
                RIDFLD    (WS-USER-ID)
                KEYLENGTH (LENGTH OF WS-USER-ID)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   IF SEC-USR-PWD = WS-USER-PWD
                       SET AUTH-OK TO TRUE
                   ELSE
                       SET AUTH-BAD TO TRUE
                   END-IF
               WHEN DFHRESP(NOTFND)
      *  Generic failure - do not reveal that the user is unknown.
                   SET AUTH-BAD TO TRUE
               WHEN OTHER
                   SET AUTH-ERROR TO TRUE
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                      1200-MINT-TOKEN
      *  Build a 64-char opaque, non-sequential bearer token and the
      *  matching expiry timestamp. Demonstration-grade: derived from
      *  the store clock, task number and user id (no crypto library).
      *----------------------------------------------------------------*
       1200-MINT-TOKEN.

           PERFORM 9100-CURRENT-ABSTIME

      *  Derive a numeric contribution from the user id characters.
           MOVE ZEROS               TO WS-USERID-NUM
           PERFORM VARYING WS-POS FROM 1 BY 1 UNTIL WS-POS > 8
               MOVE WS-USER-ID (WS-POS:1) TO WS-ONE-CHAR
               COMPUTE WS-ORD = FUNCTION ORD (WS-ONE-CHAR)
               COMPUTE WS-USERID-NUM =
                   FUNCTION MOD (WS-USERID-NUM * 31 + WS-ORD,
                                 1000000000)
           END-PERFORM

      *  Seed a small linear-congruential mixer from time, task and id.
           MOVE WS-ABSTIME          TO WS-ABSTIME-9
           MOVE EIBTASKN            TO WS-TASK-NUM
           COMPUTE WS-STATE =
               FUNCTION MOD (WS-ABSTIME-9 + WS-TASK-NUM
                             + WS-USERID-NUM, 1000003)

      *  Fill 64 opaque, non-sequential alphanumeric positions.
           PERFORM VARYING WS-POS FROM 1 BY 1 UNTIL WS-POS > 64
               COMPUTE WS-STATE =
                   FUNCTION MOD (WS-STATE * 8121 + 28411 + WS-POS,
                                 1000003)
               COMPUTE WS-IDX = FUNCTION MOD (WS-STATE, 36) + 1
               MOVE WS-ALPHABET (WS-IDX:1)
                                    TO WS-TOKEN-VALUE (WS-POS:1)
           END-PERFORM

      *  Expiry timestamp = current time + the fixed token lifetime.
           COMPUTE WS-EXP-ABSTIME = WS-ABSTIME + WS-TOKEN-LIFE-MS
           MOVE WS-EXP-ABSTIME      TO WS-FMT-ABSTIME
           PERFORM 9200-FORMAT-TS.

      *----------------------------------------------------------------*
      *                     1300-WRITE-REGISTRY
      *  Store the token record in the MAIN Temporary Storage Queue.
      *  This is TSQ I/O (permitted), NOT a VSAM write. The queue name
      *  is derived from the token hash for O(1) lookup.
      *----------------------------------------------------------------*
       1300-WRITE-REGISTRY.

           MOVE WS-TOKEN-VALUE      TO TOK-VALUE
           MOVE WS-USER-ID          TO TOK-USER-ID
           MOVE SEC-USR-TYPE        TO TOK-USER-TYPE
           MOVE WS-TS-26            TO TOK-EXPIRY-TS

           PERFORM 9000-HASH-QNAME
           MOVE LENGTH OF WS-TOKEN-RECORD TO WS-Q-LEN

      *  Remove any stale item under this queue name before writing.
           EXEC CICS DELETEQ TS
                QUEUE     (WS-QNAME)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
      *  QIDERR (queue absent) is expected on first use - ignore it.
           IF WS-RESP-CD = DFHRESP(NORMAL)
              OR WS-RESP-CD = DFHRESP(QIDERR)
               CONTINUE
           ELSE
               SET AUTH-ERROR TO TRUE
           END-IF

           IF AUTH-OK
               EXEC CICS WRITEQ TS
                    QUEUE     (WS-QNAME)
                    FROM      (WS-TOKEN-RECORD)
                    LENGTH    (WS-Q-LEN)
                    MAIN
                    RESP      (WS-RESP-CD)
                    RESP2     (WS-REAS-CD)
               END-EXEC
               IF WS-RESP-CD NOT = DFHRESP(NORMAL)
                   SET AUTH-ERROR TO TRUE
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                    1400-BUILD-SIGNON-RSP
      *  Populate both the API-TOKEN group and the sign-on response.
      *  The response purposely carries no password field.
      *----------------------------------------------------------------*
       1400-BUILD-SIGNON-RSP.

           MOVE WS-TOKEN-VALUE      TO API-TOKEN-VALUE
           MOVE WS-USER-ID          TO API-TOKEN-USER-ID
           MOVE SEC-USR-TYPE        TO API-TOKEN-USER-TYPE
           MOVE WS-TS-26            TO API-TOKEN-EXPIRY-TS

           MOVE SPACES              TO API-SIGNON-RESPONSE
           MOVE WS-TOKEN-VALUE      TO SGNO-TOKEN
           MOVE WS-USER-ID          TO SGNO-USER-ID
           MOVE SEC-USR-TYPE        TO SGNO-USER-TYPE
           MOVE WS-TS-26            TO SGNO-TOKEN-EXPIRY-TS
           MOVE API-SIGNON-RESPONSE TO API-PAYLOAD

           SET API-HTTP-OK TO TRUE
           MOVE 0                   TO API-RETURN-CODE
           MOVE SPACES              TO API-ERR-CODE
           MOVE SPACES              TO API-ERR-MESSAGE.

      *----------------------------------------------------------------*
      *                     1900-UNAUTHORIZED
      *  Generic 401 for both unknown user and wrong password so the
      *  caller cannot enumerate valid user ids.
      *----------------------------------------------------------------*
       1900-UNAUTHORIZED.

           SET API-HTTP-UNAUTHORIZED TO TRUE
           MOVE -1                  TO API-RETURN-CODE
           MOVE 'APIAUTH1'          TO API-ERR-CODE
           MOVE 'Authentication failed'
                                    TO API-ERR-MESSAGE
      *  Ensure no token or payload leaks on the failure path.
           MOVE SPACES              TO API-TOKEN-VALUE
           MOVE SPACES              TO API-PAYLOAD.

      *----------------------------------------------------------------*
      *                     1950-SERVER-ERROR
      *  Unexpected condition. RESP/RESP2 are deliberately NOT surfaced
      *  to the caller; the router emits a generic 500 envelope.
      *----------------------------------------------------------------*
       1950-SERVER-ERROR.

           SET API-HTTP-SERVER-ERROR TO TRUE
           MOVE -2                  TO API-RETURN-CODE
           MOVE 'APISRV1 '          TO API-ERR-CODE
           MOVE 'Internal server error'
                                    TO API-ERR-MESSAGE
           MOVE SPACES              TO API-TOKEN-VALUE
           MOVE SPACES              TO API-PAYLOAD.

      *----------------------------------------------------------------*
      *                     2000-VALIDATE-TOKEN
      *  Validate the bearer token presented in API-TOKEN-VALUE.
      *----------------------------------------------------------------*
       2000-VALIDATE-TOKEN.

           MOVE API-TOKEN-VALUE     TO WS-TOKEN-VALUE
           PERFORM 2100-READ-REGISTRY.

      *----------------------------------------------------------------*
      *                     2100-READ-REGISTRY
      *  Read the single token item from the MAIN TSQ (permitted TSQ
      *  I/O, NOT VSAM) and dispatch on the result.
      *----------------------------------------------------------------*
       2100-READ-REGISTRY.

           PERFORM 9000-HASH-QNAME
           MOVE LENGTH OF WS-TOKEN-RECORD TO WS-Q-LEN

           EXEC CICS READQ TS
                QUEUE     (WS-QNAME)
                INTO      (WS-TOKEN-RECORD)
                LENGTH    (WS-Q-LEN)
                ITEM      (1)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   PERFORM 2200-CHECK-TOKEN
               WHEN DFHRESP(QIDERR)
                   PERFORM 1900-UNAUTHORIZED
               WHEN DFHRESP(ITEMERR)
                   PERFORM 1900-UNAUTHORIZED
               WHEN OTHER
                   PERFORM 1950-SERVER-ERROR
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                     2200-CHECK-TOKEN
      *  Guard against a hash collision (stored token must equal the
      *  presented token) then test expiry with a lexical compare of
      *  the fixed-format timestamps.
      *----------------------------------------------------------------*
       2200-CHECK-TOKEN.

           IF TOK-VALUE NOT = API-TOKEN-VALUE
               PERFORM 1900-UNAUTHORIZED
           ELSE
               PERFORM 9100-CURRENT-ABSTIME
               MOVE WS-ABSTIME      TO WS-FMT-ABSTIME
               PERFORM 9200-FORMAT-TS
               MOVE WS-TS-26        TO WS-TS-NOW
               IF WS-TS-NOW > TOK-EXPIRY-TS
                   PERFORM 1900-UNAUTHORIZED
               ELSE
                   PERFORM 2300-TOKEN-VALID
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                     2300-TOKEN-VALID
      *  Return the identity bound to the token for the router/services.
      *----------------------------------------------------------------*
       2300-TOKEN-VALID.

           MOVE TOK-USER-ID         TO API-TOKEN-USER-ID
           MOVE TOK-USER-TYPE       TO API-TOKEN-USER-TYPE
           MOVE TOK-EXPIRY-TS       TO API-TOKEN-EXPIRY-TS
           SET API-HTTP-OK TO TRUE
           MOVE 0                   TO API-RETURN-CODE
           MOVE SPACES              TO API-ERR-CODE
           MOVE SPACES              TO API-ERR-MESSAGE.

      *----------------------------------------------------------------*
      *                     9000-HASH-QNAME
      *  Numeric rolling hash of the 64-char token -> 8-digit value,
      *  producing queue name 'AT' + hash (10 chars, <= 16 TSQ limit).
      *----------------------------------------------------------------*
       9000-HASH-QNAME.

           MOVE ZEROS               TO WS-HASH
           PERFORM VARYING WS-POS FROM 1 BY 1 UNTIL WS-POS > 64
               MOVE WS-TOKEN-VALUE (WS-POS:1) TO WS-ONE-CHAR
               COMPUTE WS-ORD = FUNCTION ORD (WS-ONE-CHAR)
               COMPUTE WS-HASH =
                   FUNCTION MOD (WS-HASH * 31 + WS-ORD, 100000000)
           END-PERFORM

           MOVE WS-HASH             TO WS-HASH-X
           MOVE SPACES              TO WS-QNAME
           STRING 'AT' DELIMITED BY SIZE
                  WS-HASH-X DELIMITED BY SIZE
                  INTO WS-QNAME
           END-STRING.

      *----------------------------------------------------------------*
      *                   9100-CURRENT-ABSTIME
      *  Obtain the current absolute store clock (milliseconds).
      *----------------------------------------------------------------*
       9100-CURRENT-ABSTIME.

           EXEC CICS ASKTIME
                ABSTIME   (WS-ABSTIME)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD NOT = DFHRESP(NORMAL)
               MOVE ZEROS           TO WS-ABSTIME
           END-IF.

      *----------------------------------------------------------------*
      *                     9200-FORMAT-TS
      *  Format WS-FMT-ABSTIME into a 26-char timestamp of the form
      *  'YYYY-MM-DD HH:MM:SS.000000'. Fixed width so a lexical compare
      *  is equivalent to a chronological compare.
      *----------------------------------------------------------------*
       9200-FORMAT-TS.

           EXEC CICS FORMATTIME
                ABSTIME   (WS-FMT-ABSTIME)
                YYYYMMDD  (WS-FT-DATE)
                DATESEP   ('-')
                TIME      (WS-FT-TIME)
                TIMESEP   (':')
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           IF WS-RESP-CD = DFHRESP(NORMAL)
               MOVE SPACES          TO WS-TS-26
               STRING WS-FT-DATE  DELIMITED BY SIZE
                      ' '         DELIMITED BY SIZE
                      WS-FT-TIME  DELIMITED BY SIZE
                      '.000000'   DELIMITED BY SIZE
                      INTO WS-TS-26
               END-STRING
           ELSE
               MOVE SPACES          TO WS-TS-26
           END-IF.
      *
      * Ver: CardDemo REST/JSON API v1.0 - COAPISEC auth/token service
      *
