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
      * TOKEN MODEL (opaque registry token, base-platform CSPRNG)
      *   The 64-char opaque token is filled from cryptographically
      *   strong random bytes obtained from the z/OS ICSF service
      *   CSNBRNG (a site-approved base-platform CSPRNG, NOT a new
      *   external library), each random byte mapped onto the 36-char
      *   [0-9A-Z] alphabet. It is NOT a self-signed JWT (no signing
      *   library is permitted). It is stored in a MAIN TSQ registry
      *   keyed by a short queue-name hash of the token for O(1),
      *   revocable, collision-safe lookup. Minting FAILS CLOSED
      *   (HTTP 500) if ICSF or the store clock is unavailable, and a
      *   live registry entry is never destructively overwritten.
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
      *  M2 - Clock/format health. Any ASKTIME / FORMATTIME failure
      *  sets TIME-BAD so mint and validate FAIL CLOSED (never issue or
      *  honor a token with an unreliable timestamp).
         05 WS-TIME-STATUS          PIC X(01) VALUE 'G'.
           88 TIME-OK                         VALUE 'G'.
           88 TIME-BAD                        VALUE 'B'.
      *  M3 - Registry write outcome for the collision-safe mint loop.
         05 WS-WRITE-STATUS         PIC X(01) VALUE 'P'.
           88 WRITE-PENDING                   VALUE 'P'.
           88 WRITE-DONE                      VALUE 'D'.
           88 WRITE-COLLIDE                   VALUE 'C'.
      *  M3 - Classification of an existing registry occupant.
         05 WS-PROBE-STATUS         PIC X(01) VALUE 'A'.
           88 PROBE-ABSENT                    VALUE 'A'.
           88 PROBE-RECLAIMABLE               VALUE 'R'.
           88 PROBE-COLLIDE-LIVE              VALUE 'L'.
      *  C4/M3 - Bounded re-mint attempts on a live hash collision.
         05 WS-MINT-ATTEMPT         PIC 9(02) VALUE ZEROS.
         05 WS-MAX-ATTEMPTS         PIC 9(02) VALUE 8.

      *----------------------------------------------------------------*
      *  Token build / hash work area
      *----------------------------------------------------------------*
       01 WS-TOKEN-WORK.
         05 WS-TOKEN-VALUE          PIC X(64) VALUE SPACES.
         05 WS-ALPHABET             PIC X(36) VALUE
              '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'.
         05 WS-IDX                  PIC 9(04) VALUE ZEROS.
         05 WS-POS                  PIC 9(04) VALUE ZEROS.
         05 WS-ORD                  PIC 9(05) VALUE ZEROS.
         05 WS-HASH                 PIC 9(14) VALUE ZEROS.
         05 WS-HASH-X               PIC 9(14) VALUE ZEROS.
         05 WS-QNAME                PIC X(16) VALUE SPACES.
         05 WS-ONE-CHAR             PIC X(01) VALUE SPACES.
      *  Halfword length work fields for TSQ WRITEQ / READQ calls.
      *  WS-Q-LEN2 is used by the collision probe (M3) READQ.
         05 WS-Q-LEN                PIC S9(04) COMP VALUE ZEROS.
         05 WS-Q-LEN2               PIC S9(04) COMP VALUE ZEROS.

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

      *----------------------------------------------------------------*
      *  M3 - Probe copy of a registry occupant, read before writing so
      *  a live (unexpired, different) token is never destructively
      *  overwritten on a queue-name hash collision.
      *----------------------------------------------------------------*
       01 WS-PROBE-RECORD.
         05 PRB-VALUE               PIC X(64) VALUE SPACES.
         05 PRB-USER-ID             PIC X(08) VALUE SPACES.
         05 PRB-USER-TYPE           PIC X(01) VALUE SPACES.
         05 PRB-EXPIRY-TS           PIC X(26) VALUE SPACES.

      *----------------------------------------------------------------*
      *  C4 - ICSF CSNBRNG (Random Number Generate) call interface plus
      *  the 64-byte random buffer used to build the opaque token. The
      *  service returns 8 random bytes per call; eight calls fill the
      *  64 positions, each mapped onto the [0-9A-Z] alphabet. A
      *  non-zero ICSF return code fails minting closed (HTTP 500).
      *----------------------------------------------------------------*
       01 WS-RNG-WORK.
         05 WS-RNG-RC               PIC S9(09) COMP VALUE 0.
         05 WS-RNG-REASON           PIC S9(09) COMP VALUE 0.
         05 WS-RNG-EXIT-LEN         PIC S9(09) COMP VALUE 0.
         05 WS-RNG-EXIT-DATA        PIC X(04) VALUE SPACES.
         05 WS-RNG-FORM             PIC X(08) VALUE 'RANDOM  '.
         05 WS-RNG-CHUNK            PIC X(08) VALUE SPACES.
         05 WS-RANDOM-BYTES         PIC X(64) VALUE SPACES.
         05 WS-RNG-CALL             PIC 9(02) VALUE ZEROS.
         05 WS-RNG-BASE             PIC 9(04) VALUE ZEROS.

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

           IF EIBCALEN >= LENGTH OF API-COMMAREA
               SET ADDRESS OF API-COMMAREA TO ADDRESS OF DFHCOMMAREA
               PERFORM 0100-DISPATCH
           END-IF

      *    C3 - Scrub every working-storage copy of a credential, token
      *    or PII field before the task returns and its storage is freed
      *    or reused. This is the single exit funnel, so the scrub runs
      *    on every path (issue ok/fail, validate ok/fail, bad request).
      *    Commarea response fields (API-TOKEN-VALUE / API-PAYLOAD) are
      *    intentionally preserved - the router owns their lifecycle.
           PERFORM 0900-SCRUB-SENSITIVE

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
           SET API-ERR-BAD-REQUEST  TO TRUE
           MOVE 'Unsupported API service operation'
                                    TO API-ERR-MESSAGE.

      *----------------------------------------------------------------*
      *                    0900-SCRUB-SENSITIVE
      *  C3 - Overwrite every working-storage copy of a credential,
      *  token, random seed or PII field so nothing sensitive survives
      *  in this task's storage after RETURN (defends against CWE-226
      *  storage reuse and CWE-532 dump/trace capture). The shared
      *  COMMAREA response fields (API-TOKEN-VALUE / API-PAYLOAD) are
      *  deliberately NOT cleared here - the router owns and scrubs them
      *  once the HTTP response has been serialized.
      *----------------------------------------------------------------*
       0900-SCRUB-SENSITIVE.

      *  Inbound / working credentials and the raw USRSEC record.
           MOVE SPACES              TO WS-USER-ID
           MOVE SPACES              TO WS-USER-PWD
           MOVE SPACES              TO SEC-USER-DATA
           MOVE SPACES              TO API-SIGNON-REQUEST

      *  Working copies of the issued token (the COMMAREA copies in
      *  API-TOKEN-VALUE / API-PAYLOAD are left for the router).
           MOVE SPACES              TO WS-TOKEN-VALUE
           MOVE SPACES              TO API-SIGNON-RESPONSE
           MOVE SPACES              TO WS-TOKEN-RECORD
           MOVE SPACES              TO WS-PROBE-RECORD

      *  CSPRNG byte buffers.
           MOVE SPACES              TO WS-RANDOM-BYTES
           MOVE SPACES              TO WS-RNG-CHUNK.


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
                   PERFORM 1150-MINT-AND-REGISTER
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
      *                    1150-MINT-AND-REGISTER
      *  C4/M3 - Mint a CSPRNG token and register it, retrying with a
      *  fresh token if the queue-name hash collides with a LIVE entry.
      *  Bounded to WS-MAX-ATTEMPTS; exhaustion or any hard error fails
      *  closed (AUTH-ERROR -> HTTP 500). A live token is never
      *  destructively overwritten (see 1300 / 1310).
      *----------------------------------------------------------------*
       1150-MINT-AND-REGISTER.

           SET WRITE-PENDING TO TRUE
           PERFORM VARYING WS-MINT-ATTEMPT FROM 1 BY 1
                   UNTIL WS-MINT-ATTEMPT > WS-MAX-ATTEMPTS
                      OR WRITE-DONE
                      OR AUTH-ERROR
               PERFORM 1200-MINT-TOKEN
               IF AUTH-OK
                   PERFORM 1300-WRITE-REGISTRY
               END-IF
           END-PERFORM

      *  All attempts collided with live entries -> fail closed (500).
           IF AUTH-OK AND NOT WRITE-DONE
               SET AUTH-ERROR TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                      1200-MINT-TOKEN
      *  C4 - Build a 64-char opaque bearer token from ICSF CSPRNG bytes
      *  (CSNBRNG), each random byte mapped onto the [0-9A-Z] alphabet,
      *  plus the matching expiry timestamp. M2 - a clock or ICSF
      *  failure fails minting closed (AUTH-ERROR -> HTTP 500).
      *----------------------------------------------------------------*
       1200-MINT-TOKEN.

           SET TIME-OK TO TRUE
           PERFORM 9100-CURRENT-ABSTIME

      *  C4 - Fill a 64-byte buffer from the ICSF CSPRNG, 8 bytes per
      *  CSNBRNG call. A non-zero ICSF return code fails closed (500).
           MOVE SPACES              TO WS-RANDOM-BYTES
           PERFORM VARYING WS-RNG-CALL FROM 1 BY 1
                   UNTIL WS-RNG-CALL > 8 OR AUTH-ERROR
               CALL 'CSNBRNG' USING WS-RNG-RC
                                    WS-RNG-REASON
                                    WS-RNG-EXIT-LEN
                                    WS-RNG-EXIT-DATA
                                    WS-RNG-FORM
                                    WS-RNG-CHUNK
               IF WS-RNG-RC = 0
                   COMPUTE WS-RNG-BASE = (WS-RNG-CALL - 1) * 8 + 1
                   MOVE WS-RNG-CHUNK
                     TO WS-RANDOM-BYTES (WS-RNG-BASE:8)
               ELSE
                   SET AUTH-ERROR TO TRUE
               END-IF
           END-PERFORM

      *  Map each random byte onto the [0-9A-Z] alphabet, preserving the
      *  64-char token contract the router enforces.
           IF AUTH-OK
               PERFORM VARYING WS-POS FROM 1 BY 1 UNTIL WS-POS > 64
                   MOVE WS-RANDOM-BYTES (WS-POS:1) TO WS-ONE-CHAR
                   COMPUTE WS-ORD = FUNCTION ORD (WS-ONE-CHAR)
                   COMPUTE WS-IDX = FUNCTION MOD (WS-ORD - 1, 36) + 1
                   MOVE WS-ALPHABET (WS-IDX:1)
                                    TO WS-TOKEN-VALUE (WS-POS:1)
               END-PERFORM
           END-IF

      *  Expiry timestamp = current time + the fixed token lifetime.
           COMPUTE WS-EXP-ABSTIME = WS-ABSTIME + WS-TOKEN-LIFE-MS
           MOVE WS-EXP-ABSTIME      TO WS-FMT-ABSTIME
           PERFORM 9200-FORMAT-TS

      *  M2 - any clock/format failure during minting fails closed.
           IF TIME-BAD
               SET AUTH-ERROR TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                     1300-WRITE-REGISTRY
      *  M3 - Register the token in the MAIN TSQ (permitted TSQ I/O, NOT
      *  a VSAM write). Probe the target queue first: write only if it
      *  is absent or holds an expired/identical token. A live
      *  token (hash collision) yields WRITE-COLLIDE so the caller
      *  re-mints - a live entry is never destroyed. M1 - the 'AT' queue
      *  prefix is matched by the CARDDEMOAPI TSMODEL (EXPIRYINT) so
      *  never-re-presented tokens are reaped on a bounded lifecycle.
      *----------------------------------------------------------------*
       1300-WRITE-REGISTRY.

           MOVE WS-TOKEN-VALUE      TO TOK-VALUE
           MOVE WS-USER-ID          TO TOK-USER-ID
           MOVE SEC-USR-TYPE        TO TOK-USER-TYPE
           MOVE WS-TS-26            TO TOK-EXPIRY-TS

           PERFORM 9000-HASH-QNAME
           MOVE LENGTH OF WS-TOKEN-RECORD TO WS-Q-LEN

           PERFORM 1310-PROBE-REGISTRY
           IF AUTH-OK
               EVALUATE TRUE
                   WHEN PROBE-ABSENT
                       PERFORM 1320-WRITE-NEW
                   WHEN PROBE-RECLAIMABLE
                       PERFORM 1330-WRITE-REPLACE
                   WHEN PROBE-COLLIDE-LIVE
                       SET WRITE-COLLIDE TO TRUE
                   WHEN OTHER
                       SET AUTH-ERROR TO TRUE
               END-EVALUATE
           END-IF.

      *----------------------------------------------------------------*
      *                     1310-PROBE-REGISTRY
      *  M3 - Read any existing item under the token's queue name to
      *  classify the occupant before writing. QIDERR/ITEMERR -> absent
      *  (safe to create); NORMAL -> classify (1315); any other RESP
      *  fails closed.
      *----------------------------------------------------------------*
       1310-PROBE-REGISTRY.

           SET PROBE-ABSENT TO TRUE
           MOVE LENGTH OF WS-PROBE-RECORD TO WS-Q-LEN2

           EXEC CICS READQ TS
                QUEUE     (WS-QNAME)
                INTO      (WS-PROBE-RECORD)
                LENGTH    (WS-Q-LEN2)
                ITEM      (1)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC

           EVALUATE WS-RESP-CD
               WHEN DFHRESP(NORMAL)
                   PERFORM 1315-CLASSIFY-OCCUPANT
               WHEN DFHRESP(QIDERR)
                   SET PROBE-ABSENT TO TRUE
               WHEN DFHRESP(ITEMERR)
                   SET PROBE-ABSENT TO TRUE
               WHEN OTHER
                   SET AUTH-ERROR TO TRUE
           END-EVALUATE.

      *----------------------------------------------------------------*
      *                    1315-CLASSIFY-OCCUPANT
      *  An identical stored token is reclaimable (harmless re-issue).
      *  Otherwise compare the occupant expiry to the current time:
      *  expired -> reclaimable, live -> collision. A clock failure
      *  (TIME-BAD) fails closed so a live token is never overwritten.
      *----------------------------------------------------------------*
       1315-CLASSIFY-OCCUPANT.

           IF PRB-VALUE = WS-TOKEN-VALUE
               SET PROBE-RECLAIMABLE TO TRUE
           ELSE
               SET TIME-OK TO TRUE
               PERFORM 9100-CURRENT-ABSTIME
               MOVE WS-ABSTIME      TO WS-FMT-ABSTIME
               PERFORM 9200-FORMAT-TS
               MOVE WS-TS-26        TO WS-TS-NOW
               IF TIME-BAD
                   SET AUTH-ERROR TO TRUE
               ELSE
                   IF WS-TS-NOW > PRB-EXPIRY-TS
                       SET PROBE-RECLAIMABLE TO TRUE
                   ELSE
                       SET PROBE-COLLIDE-LIVE TO TRUE
                   END-IF
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      *                       1320-WRITE-NEW
      *  Create the registry item (queue absent).
      *----------------------------------------------------------------*
       1320-WRITE-NEW.

           EXEC CICS WRITEQ TS
                QUEUE     (WS-QNAME)
                FROM      (WS-TOKEN-RECORD)
                LENGTH    (WS-Q-LEN)
                MAIN
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD = DFHRESP(NORMAL)
               SET WRITE-DONE TO TRUE
           ELSE
               SET AUTH-ERROR TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      *                     1330-WRITE-REPLACE
      *  Replace an expired or identical occupant in place (REWRITE).
      *  Never reached for a live, different token (a collision).
      *----------------------------------------------------------------*
       1330-WRITE-REPLACE.

           EXEC CICS WRITEQ TS
                QUEUE     (WS-QNAME)
                FROM      (WS-TOKEN-RECORD)
                LENGTH    (WS-Q-LEN)
                ITEM      (1)
                REWRITE
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC
           IF WS-RESP-CD = DFHRESP(NORMAL)
               SET WRITE-DONE TO TRUE
           ELSE
               SET AUTH-ERROR TO TRUE
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
           SET API-ERR-UNAUTHORIZED TO TRUE
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
           SET API-ERR-SERVER-ERROR TO TRUE
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
      *  the fixed-format timestamps. M2 - a clock/format failure fails
      *  validation CLOSED (500), never OPEN on a blank timestamp.
      *----------------------------------------------------------------*
       2200-CHECK-TOKEN.

           IF TOK-VALUE NOT = API-TOKEN-VALUE
               PERFORM 1900-UNAUTHORIZED
           ELSE
               SET TIME-OK TO TRUE
               PERFORM 9100-CURRENT-ABSTIME
               MOVE WS-ABSTIME      TO WS-FMT-ABSTIME
               PERFORM 9200-FORMAT-TS
               MOVE WS-TS-26        TO WS-TS-NOW
               IF TIME-BAD
                   PERFORM 1950-SERVER-ERROR
               ELSE
                   IF WS-TS-NOW > TOK-EXPIRY-TS
                       PERFORM 2400-PURGE-EXPIRED
                       PERFORM 1900-UNAUTHORIZED
                   ELSE
                       PERFORM 2300-TOKEN-VALID
                   END-IF
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
      *                     2400-PURGE-EXPIRED
      *  Delete the backing TSQ for an expired token (best-effort) so
      *  the stale registry entry is not left behind.  WS-QNAME is set
      *  by the preceding 2100-READ-REGISTRY hash.
      *----------------------------------------------------------------*
       2400-PURGE-EXPIRED.

           EXEC CICS DELETEQ TS
                QUEUE     (WS-QNAME)
                RESP      (WS-RESP-CD)
                RESP2     (WS-REAS-CD)
           END-EXEC.

      *----------------------------------------------------------------*
      *                     9000-HASH-QNAME
      *  Numeric rolling hash of the 64-char token -> 14-digit value,
      *  producing queue name 'AT' + hash (16 chars, = 16 TSQ limit).
      *----------------------------------------------------------------*
       9000-HASH-QNAME.

           MOVE ZEROS               TO WS-HASH
           PERFORM VARYING WS-POS FROM 1 BY 1 UNTIL WS-POS > 64
               MOVE WS-TOKEN-VALUE (WS-POS:1) TO WS-ONE-CHAR
               COMPUTE WS-ORD = FUNCTION ORD (WS-ONE-CHAR)
               COMPUTE WS-HASH =
                   FUNCTION MOD (WS-HASH * 31 + WS-ORD, 100000000000000)
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
      *  M2 - fail CLOSED: a clock read error marks TIME-BAD so
      *  mint and validate refuse to issue or honor a token with
      *  an unreliable timestamp.
               SET TIME-BAD TO TRUE
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
      *  M2 - fail CLOSED on a format error: mark TIME-BAD and
      *  blank the timestamp; callers treat TIME-BAD as a hard
      *  500 rather than trusting a blank (low-sorting) expiry.
               SET TIME-BAD TO TRUE
               MOVE SPACES          TO WS-TS-26
           END-IF.
      *
      * Ver: CardDemo REST/JSON API v1.0 - COAPISEC auth/token service
      *
