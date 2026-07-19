      ******************************************************************
      * Program     : COJSONUC.CBL
      * Application : CardDemo
      * Type        : CICS COBOL Program
      * Function    : Self-contained JSON serializer / parser
      *               for the CardDemo REST/JSON API layer
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
      *----------------------------------------------------------------*
      * PROGRAM OVERVIEW
      *----------------------------------------------------------------*
      * COJSONUC is the dependency-free FALLBACK JSON engine for the
      * additive CardDemo REST/JSON API. It is CALL'd from the router
      * and service programs whenever native CICS TRANSFORM
      * DATATOJSON / JSONTODATA services are not available at the
      * target CICS level. It performs NO EXEC CICS commands, NO file
      * I/O and NO VSAM access - it is pure in-memory string handling,
      * which makes it a genuine leaf utility with zero dependencies.
      *
      * The program only FORMATS values handed to it. Callers are
      * responsible for masking a PAN, dropping a card security code,
      * and minimizing customer PII BEFORE calling. This program adds
      * no logic that could reconstruct or expose such values and it
      * never displays operand data.
      *----------------------------------------------------------------*
      * CALL INTERFACE
      *----------------------------------------------------------------*
      * CALL 'COJSONUC' USING JSON-PARM JSON-BUFFER.
      * Callers declare a matching WORKING-STORAGE copy of the two
      * LINKAGE group items defined below.
      *
      *   JSON-BUFFER.JB-DATA  - accumulating JSON output buffer.
      *   JSON-BUFFER.JB-LEN   - current used length; the utility
      *                          appends to JB-DATA and updates JB-LEN.
      *
      * Every append is bounds-checked against 32000. On overflow the
      * utility sets JP-ERROR and returns without overrunning JB-DATA.
      *----------------------------------------------------------------*
      * FUNCTIONS (dispatched on JP-FUNCTION)
      *----------------------------------------------------------------*
      *   INIT  reset JB-DATA to spaces and JB-LEN to zero.
      *   BEGO  begin object - emit optional name then '{'.
      *   ENDO  end object   - emit '}'.
      *   BEGA  begin array  - emit optional name then '['.
      *   ENDA  end array    - emit ']'.
      *   STR   string member  - emit "name":"escaped-value".
      *   NUM   number member  - emit "name":digits (caller formats).
      *   MON2  money member   - emit "name":signed-decimal, 2dp.
      *   BOOL  boolean member - emit "name":true|false.
      *   NULL  null member    - emit "name":null.
      *   RAW   splice value verbatim (pre-built fragment/element).
      *   PARS  extract a string value for a key from a flat object.
      *----------------------------------------------------------------*
      * COMMA HANDLING
      *----------------------------------------------------------------*
      * The CALLER controls separators via JP-FIRST-FLAG. For every
      * value/member emission (STR NUM MON2 BOOL NULL BEGO BEGA RAW)
      * the utility appends a leading ',' when JP-NOT-FIRST and emits
      * nothing extra when JP-IS-FIRST. This lets the caller build
      * comma-separated members and array elements deterministically.
      *----------------------------------------------------------------*
      * MON2 - SIGNED IMPLIED-DECIMAL TO JSON NUMBER
      *----------------------------------------------------------------*
      * JP-NUM-VALUE is PIC S9(13)V99 (signed, 2 implied fraction
      * digits) and holds S9(10)V99 and S9(09)V99 monetary values
      * without truncation. MON2 renders a compact JSON number token
      * matching ^-?\d+\.\d{2}$ : an optional leading '-' for
      * negatives (never '+'), integer digits with no leading zeros
      * beyond a single '0', a '.', then exactly two fraction digits.
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. COJSONUC.
       AUTHOR.     AWS.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.

       DATA DIVISION.
      *----------------------------------------------------------------*
      *                     WORKING STORAGE SECTION
      *----------------------------------------------------------------*
       WORKING-STORAGE SECTION.

       01  WS-VARIABLES.
           05  WS-PGMNAME          PIC X(08) VALUE 'COJSONUC'.
           05  WS-I                PIC S9(08) COMP VALUE 0.
           05  WS-DEST-POS         PIC S9(08) COMP VALUE 0.
           05  WS-APP-LEN          PIC S9(08) COMP VALUE 0.
           05  WS-APP-STR          PIC X(256) VALUE SPACES.
           05  WS-ESC-CHAR         PIC X(01) VALUE SPACE.
      *    Money-to-JSON conversion work fields.
           05  WS-MON-ABS          PIC 9(13)V99 VALUE ZEROS.
           05  WS-MON-INT-START    PIC S9(08) COMP VALUE 0.
           05  WS-MON-INT-LEN      PIC S9(08) COMP VALUE 0.
           05  WS-MON-TOKEN        PIC X(20) VALUE SPACES.
           05  WS-MON-TOKEN-LEN    PIC S9(08) COMP VALUE 0.
           05  WS-NEG-FLAG         PIC X(01) VALUE 'N'.
      *    Minimal flat-object JSON parser work fields.
           05  WS-PARSE-LEN        PIC S9(08) COMP VALUE 0.
           05  WS-PARSE-POS        PIC S9(08) COMP VALUE 0.
           05  WS-KEY-LEN          PIC S9(08) COMP VALUE 0.
           05  WS-SEARCH-KEY       PIC X(42) VALUE SPACES.
           05  WS-SEARCH-LEN       PIC S9(08) COMP VALUE 0.
           05  WS-FOUND-POS        PIC S9(08) COMP VALUE 0.
           05  WS-LIMIT            PIC S9(08) COMP VALUE 0.
           05  WS-DONE-FLAG        PIC X(01) VALUE 'N'.
           05  WS-CHAR             PIC X(01) VALUE SPACE.
           05  WS-RCHAR            PIC X(01) VALUE SPACE.
      *    Control-character byte values are the EBCDIC code points
      *    for HT/CR/LF, matching the z/OS CICS runtime in which this
      *    subprogram executes. The quote and backslash escapes use
      *    code-page-relative literals and are therefore portable.
           05  WS-TAB              PIC X(01) VALUE X'05'.
           05  WS-CR               PIC X(01) VALUE X'0D'.
           05  WS-LF               PIC X(01) VALUE X'25'.
           05  WS-BS               PIC X(01) VALUE X'16'.
           05  WS-FF               PIC X(01) VALUE X'0C'.
      *    C0 control escaping (N3) loop controls.
           05  WS-C0-COUNT         PIC S9(04) COMP VALUE 27.
           05  WS-C0-IDX           PIC S9(04) COMP VALUE 0.
           05  WS-C0-MATCHED       PIC X(01) VALUE 'N'.
               88  WS-C0-IS-MATCH  VALUE 'Y'.
               88  WS-C0-NO-MATCH  VALUE 'N'.

      *----------------------------------------------------------------*
      *    JSON C0 control-character escape tables (N3). Every byte
      *    whose EBCDIC (IBM-037) code maps to a Unicode C0 control not
      *    covered by a short escape (\b \t \n \f \r) is emitted as
      *    \u00XX. WS-C0-BYTE(i) is the EBCDIC byte; WS-C0-HX(i) is the
      *    paired Unicode hex. C0 mappings are identical across
      *    IBM-037 / 500 / 1140, the code pages this region uses.
      *----------------------------------------------------------------*
       01  WS-C0-BYTE-VALUES.
           05  FILLER  PIC X(27) VALUE
           X'00010203372D2E2F0B0E0F101112133C3D322618193F271C1D1E1F'.
       01  WS-C0-BYTE-TAB REDEFINES WS-C0-BYTE-VALUES.
           05  WS-C0-BYTE          PIC X(01) OCCURS 27 TIMES.
       01  WS-C0-HEX-VALUES.
           05  FILLER  PIC X(54) VALUE
           '00010203040506070B0E0F101112131415161718191A1B1C1D1E1F'.
       01  WS-C0-HEX-TAB REDEFINES WS-C0-HEX-VALUES.
           05  WS-C0-HX            PIC X(02) OCCURS 27 TIMES.

      *----------------------------------------------------------------*
      *                        LINKAGE SECTION
      *----------------------------------------------------------------*
       LINKAGE SECTION.
       01  JSON-PARM.
           05  JP-FUNCTION         PIC X(04).
      *        'INIT' 'BEGO' 'ENDO' 'BEGA' 'ENDA' 'STR ' 'NUM '
      *        'MON2' 'BOOL' 'NULL' 'RAW ' 'PARS'
           05  JP-NAME             PIC X(40).
           05  JP-NAME-LEN         PIC S9(04) COMP.
           05  JP-VALUE            PIC X(256).
           05  JP-VALUE-LEN        PIC S9(04) COMP.
           05  JP-NUM-VALUE        PIC S9(13)V99.
           05  JP-FIRST-FLAG       PIC X(01).
               88  JP-IS-FIRST     VALUE 'Y'.
               88  JP-NOT-FIRST    VALUE 'N'.
           05  JP-RETURN-CODE      PIC S9(04) COMP.
               88  JP-OK           VALUE 0.
               88  JP-ERROR        VALUE 8.
           05  JP-PARSE-KEY        PIC X(40).
           05  JP-PARSE-RESULT     PIC X(256).
           05  JP-PARSE-RESULT-LEN PIC S9(04) COMP.
       01  JSON-BUFFER.
           05  JB-DATA             PIC X(96000).
           05  JB-LEN              PIC S9(08) COMP.

      *----------------------------------------------------------------*
      *                       PROCEDURE DIVISION
      *----------------------------------------------------------------*
       PROCEDURE DIVISION USING JSON-PARM JSON-BUFFER.

      *----------------------------------------------------------------*
      * 0000-MAIN - dispatch on the requested function opcode.
      *----------------------------------------------------------------*
       0000-MAIN.
           SET JP-OK TO TRUE
           EVALUATE JP-FUNCTION
               WHEN 'INIT'
                   PERFORM 1000-INIT
               WHEN 'BEGO'
                   PERFORM 1100-BEGO
               WHEN 'ENDO'
                   PERFORM 1200-ENDO
               WHEN 'BEGA'
                   PERFORM 1300-BEGA
               WHEN 'ENDA'
                   PERFORM 1400-ENDA
               WHEN 'STR '
                   PERFORM 1500-STR
               WHEN 'NUM '
                   PERFORM 1600-NUM
               WHEN 'MON2'
                   PERFORM 1700-MON2
               WHEN 'BOOL'
                   PERFORM 1800-BOOL
               WHEN 'NULL'
                   PERFORM 1900-NULL
               WHEN 'RAW '
                   PERFORM 2000-RAW
               WHEN 'PARS'
                   PERFORM 2100-PARS
               WHEN OTHER
                   SET JP-ERROR TO TRUE
           END-EVALUATE
           GOBACK.

      *----------------------------------------------------------------*
      * 1000-INIT - reset the output buffer.
      *----------------------------------------------------------------*
       1000-INIT.
           MOVE SPACES TO JB-DATA
           MOVE 0 TO JB-LEN
           SET JP-OK TO TRUE.

      *----------------------------------------------------------------*
      * 1100-BEGO - begin object: optional name then '{'.
      *----------------------------------------------------------------*
       1100-BEGO.
           PERFORM 9100-COMMA-IF-NEEDED
           PERFORM 9200-EMIT-NAME-COLON
           MOVE '{' TO WS-APP-STR(1:1)
           MOVE 1 TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1200-ENDO - end object: '}'.
      *----------------------------------------------------------------*
       1200-ENDO.
           MOVE '}' TO WS-APP-STR(1:1)
           MOVE 1 TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1300-BEGA - begin array: optional name then '['.
      *----------------------------------------------------------------*
       1300-BEGA.
           PERFORM 9100-COMMA-IF-NEEDED
           PERFORM 9200-EMIT-NAME-COLON
           MOVE '[' TO WS-APP-STR(1:1)
           MOVE 1 TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1400-ENDA - end array: ']'.
      *----------------------------------------------------------------*
       1400-ENDA.
           MOVE ']' TO WS-APP-STR(1:1)
           MOVE 1 TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1500-STR - string member: "name":"escaped-value".
      *----------------------------------------------------------------*
       1500-STR.
           PERFORM 9100-COMMA-IF-NEEDED
           PERFORM 9200-EMIT-NAME-COLON
           MOVE '"' TO WS-APP-STR(1:1)
           MOVE 1 TO WS-APP-LEN
           PERFORM 9000-APPEND-STR
           PERFORM 9300-EMIT-ESCAPED-VALUE
           MOVE '"' TO WS-APP-STR(1:1)
           MOVE 1 TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1600-NUM - number member: "name":digits (caller preformats).
      *----------------------------------------------------------------*
       1600-NUM.
           PERFORM 9100-COMMA-IF-NEEDED
           PERFORM 9200-EMIT-NAME-COLON
           MOVE JP-VALUE TO WS-APP-STR
           MOVE JP-VALUE-LEN TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1700-MON2 - render JP-NUM-VALUE as a compact signed JSON
      * number with exactly two fraction digits, matching the
      * OpenAPI MonetaryAmount pattern ^-?\d+\.\d{2}$.
      *----------------------------------------------------------------*
       1700-MON2.
           PERFORM 9100-COMMA-IF-NEEDED
           PERFORM 9200-EMIT-NAME-COLON
           MOVE 'N' TO WS-NEG-FLAG
           IF JP-NUM-VALUE < 0
               MOVE 'Y' TO WS-NEG-FLAG
           END-IF
      *    Absolute value; unsigned receiver drops the sign and keeps
      *    the same V99 scale so no digit is truncated.
           MOVE JP-NUM-VALUE TO WS-MON-ABS
      *    Locate the first significant integer digit (positions 1-13
      *    are the integer part; positions 14-15 are the fraction).
           MOVE 0 TO WS-MON-INT-START
           PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 13
               IF WS-MON-ABS(WS-I:1) NOT = '0'
                  AND WS-MON-INT-START = 0
                   MOVE WS-I TO WS-MON-INT-START
               END-IF
           END-PERFORM
           IF WS-MON-INT-START = 0
               MOVE 13 TO WS-MON-INT-START
           END-IF
           COMPUTE WS-MON-INT-LEN = 13 - WS-MON-INT-START + 1
      *    Build the token: [-] integer '.' fraction.
           MOVE SPACES TO WS-MON-TOKEN
           MOVE 0 TO WS-MON-TOKEN-LEN
           IF WS-NEG-FLAG = 'Y'
               ADD 1 TO WS-MON-TOKEN-LEN
               MOVE '-' TO WS-MON-TOKEN(WS-MON-TOKEN-LEN:1)
           END-IF
           COMPUTE WS-DEST-POS = WS-MON-TOKEN-LEN + 1
           MOVE WS-MON-ABS(WS-MON-INT-START:WS-MON-INT-LEN)
                TO WS-MON-TOKEN(WS-DEST-POS:WS-MON-INT-LEN)
           ADD WS-MON-INT-LEN TO WS-MON-TOKEN-LEN
           ADD 1 TO WS-MON-TOKEN-LEN
           MOVE '.' TO WS-MON-TOKEN(WS-MON-TOKEN-LEN:1)
           COMPUTE WS-DEST-POS = WS-MON-TOKEN-LEN + 1
           MOVE WS-MON-ABS(14:2) TO WS-MON-TOKEN(WS-DEST-POS:2)
           ADD 2 TO WS-MON-TOKEN-LEN
           MOVE WS-MON-TOKEN TO WS-APP-STR
           MOVE WS-MON-TOKEN-LEN TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1800-BOOL - boolean member: "name":true|false.
      *----------------------------------------------------------------*
       1800-BOOL.
           PERFORM 9100-COMMA-IF-NEEDED
           PERFORM 9200-EMIT-NAME-COLON
           IF JP-VALUE(1:4) = 'true'
               MOVE 'true' TO WS-APP-STR(1:4)
               MOVE 4 TO WS-APP-LEN
           ELSE
               MOVE 'false' TO WS-APP-STR(1:5)
               MOVE 5 TO WS-APP-LEN
           END-IF
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 1900-NULL - null member: "name":null.
      *----------------------------------------------------------------*
       1900-NULL.
           PERFORM 9100-COMMA-IF-NEEDED
           PERFORM 9200-EMIT-NAME-COLON
           MOVE 'null' TO WS-APP-STR(1:4)
           MOVE 4 TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 2000-RAW - splice a pre-built fragment/element verbatim.
      *----------------------------------------------------------------*
       2000-RAW.
           PERFORM 9100-COMMA-IF-NEEDED
           MOVE JP-VALUE TO WS-APP-STR
           MOVE JP-VALUE-LEN TO WS-APP-LEN
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 2100-PARS - extract the string value of JP-PARSE-KEY from the
      * flat JSON object in JP-VALUE(1:JP-VALUE-LEN). On success the
      * unescaped value is returned in JP-PARSE-RESULT /
      * JP-PARSE-RESULT-LEN with JP-OK; otherwise JP-ERROR is set.
      *----------------------------------------------------------------*
       2100-PARS.
           MOVE SPACES TO JP-PARSE-RESULT
           MOVE 0 TO JP-PARSE-RESULT-LEN
           MOVE JP-VALUE-LEN TO WS-PARSE-LEN
           MOVE 0 TO WS-KEY-LEN
           PERFORM VARYING WS-I FROM 40 BY -1 UNTIL WS-I < 1
               IF JP-PARSE-KEY(WS-I:1) NOT = SPACE
                  AND WS-KEY-LEN = 0
                   MOVE WS-I TO WS-KEY-LEN
               END-IF
           END-PERFORM
           IF WS-KEY-LEN = 0
               SET JP-ERROR TO TRUE
           ELSE
               PERFORM 2110-PARS-FIND-KEY
           END-IF.

      *----------------------------------------------------------------*
      * 2110-PARS-FIND-KEY - locate the "key" token in the object.
      *----------------------------------------------------------------*
       2110-PARS-FIND-KEY.
           MOVE SPACES TO WS-SEARCH-KEY
           MOVE '"' TO WS-SEARCH-KEY(1:1)
           MOVE JP-PARSE-KEY(1:WS-KEY-LEN)
                TO WS-SEARCH-KEY(2:WS-KEY-LEN)
           COMPUTE WS-DEST-POS = WS-KEY-LEN + 2
           MOVE '"' TO WS-SEARCH-KEY(WS-DEST-POS:1)
           COMPUTE WS-SEARCH-LEN = WS-KEY-LEN + 2
           MOVE 0 TO WS-FOUND-POS
           COMPUTE WS-LIMIT = WS-PARSE-LEN - WS-SEARCH-LEN + 1
           PERFORM VARYING WS-I FROM 1 BY 1
                   UNTIL WS-I > WS-LIMIT OR WS-FOUND-POS > 0
               IF JP-VALUE(WS-I:WS-SEARCH-LEN) =
                  WS-SEARCH-KEY(1:WS-SEARCH-LEN)
                   MOVE WS-I TO WS-FOUND-POS
               END-IF
           END-PERFORM
           IF WS-FOUND-POS = 0
               SET JP-ERROR TO TRUE
           ELSE
               COMPUTE WS-PARSE-POS = WS-FOUND-POS + WS-SEARCH-LEN
               PERFORM 2120-PARS-EXTRACT
           END-IF.

      *----------------------------------------------------------------*
      * 2120-PARS-EXTRACT - consume ':' and the opening quote.
      *----------------------------------------------------------------*
       2120-PARS-EXTRACT.
           PERFORM UNTIL WS-PARSE-POS > WS-PARSE-LEN
                   OR JP-VALUE(WS-PARSE-POS:1) NOT = SPACE
               ADD 1 TO WS-PARSE-POS
           END-PERFORM
           IF WS-PARSE-POS > WS-PARSE-LEN
              OR JP-VALUE(WS-PARSE-POS:1) NOT = ':'
               SET JP-ERROR TO TRUE
           ELSE
               ADD 1 TO WS-PARSE-POS
               PERFORM UNTIL WS-PARSE-POS > WS-PARSE-LEN
                       OR JP-VALUE(WS-PARSE-POS:1) NOT = SPACE
                   ADD 1 TO WS-PARSE-POS
               END-PERFORM
               IF WS-PARSE-POS > WS-PARSE-LEN
                  OR JP-VALUE(WS-PARSE-POS:1) NOT = '"'
                   SET JP-ERROR TO TRUE
               ELSE
                   ADD 1 TO WS-PARSE-POS
                   PERFORM 2130-PARS-COPY
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      * 2130-PARS-COPY - copy until the unescaped closing quote,
      * unescaping \" and \\ (any other \x yields x).
      *----------------------------------------------------------------*
       2130-PARS-COPY.
           MOVE 'N' TO WS-DONE-FLAG
           PERFORM UNTIL WS-PARSE-POS > WS-PARSE-LEN
                   OR WS-DONE-FLAG = 'Y'
               MOVE JP-VALUE(WS-PARSE-POS:1) TO WS-CHAR
               EVALUATE TRUE
                   WHEN WS-CHAR = '"'
                       MOVE 'Y' TO WS-DONE-FLAG
                   WHEN WS-CHAR = '\'
                       ADD 1 TO WS-PARSE-POS
                       IF WS-PARSE-POS NOT > WS-PARSE-LEN
                           MOVE JP-VALUE(WS-PARSE-POS:1)
                                TO WS-RCHAR
                           PERFORM 9400-APPEND-RESULT-CHAR
                       END-IF
                   WHEN OTHER
                       MOVE WS-CHAR TO WS-RCHAR
                       PERFORM 9400-APPEND-RESULT-CHAR
               END-EVALUATE
               ADD 1 TO WS-PARSE-POS
           END-PERFORM
           IF WS-DONE-FLAG = 'Y'
               SET JP-OK TO TRUE
           ELSE
               SET JP-ERROR TO TRUE
           END-IF.

      *----------------------------------------------------------------*
      * 9000-APPEND-STR - append WS-APP-STR(1:WS-APP-LEN) to JB-DATA
      * with a hard bounds check against the JB-DATA buffer length.
      *----------------------------------------------------------------*
       9000-APPEND-STR.
           IF WS-APP-LEN > 0
               COMPUTE WS-DEST-POS = JB-LEN + WS-APP-LEN
               IF WS-DEST-POS > LENGTH OF JB-DATA
                   SET JP-ERROR TO TRUE
               ELSE
                   COMPUTE WS-DEST-POS = JB-LEN + 1
                   MOVE WS-APP-STR(1:WS-APP-LEN)
                        TO JB-DATA(WS-DEST-POS:WS-APP-LEN)
                   ADD WS-APP-LEN TO JB-LEN
               END-IF
           END-IF.

      *----------------------------------------------------------------*
      * 9100-COMMA-IF-NEEDED - emit a separator comma when the caller
      * flags that this is not the first member/element.
      *----------------------------------------------------------------*
       9100-COMMA-IF-NEEDED.
           IF JP-NOT-FIRST
               MOVE ',' TO WS-APP-STR(1:1)
               MOVE 1 TO WS-APP-LEN
               PERFORM 9000-APPEND-STR
           END-IF.

      *----------------------------------------------------------------*
      * 9200-EMIT-NAME-COLON - emit "name": when a name is supplied.
      *----------------------------------------------------------------*
       9200-EMIT-NAME-COLON.
           IF JP-NAME-LEN > 0
               MOVE '"' TO WS-APP-STR(1:1)
               MOVE 1 TO WS-APP-LEN
               PERFORM 9000-APPEND-STR
               MOVE JP-NAME TO WS-APP-STR
               MOVE JP-NAME-LEN TO WS-APP-LEN
               PERFORM 9000-APPEND-STR
               MOVE '":' TO WS-APP-STR(1:2)
               MOVE 2 TO WS-APP-LEN
               PERFORM 9000-APPEND-STR
           END-IF.

      *----------------------------------------------------------------*
      * 9300-EMIT-ESCAPED-VALUE - emit JP-VALUE(1:JP-VALUE-LEN) with
      * JSON string escaping applied one character at a time.
      *----------------------------------------------------------------*
       9300-EMIT-ESCAPED-VALUE.
           IF JP-VALUE-LEN > 0
               PERFORM VARYING WS-I FROM 1 BY 1
                       UNTIL WS-I > JP-VALUE-LEN
                   MOVE JP-VALUE(WS-I:1) TO WS-ESC-CHAR
                   PERFORM 9310-ESCAPE-ONE-CHAR
               END-PERFORM
           END-IF.

      *----------------------------------------------------------------*
      * 9310-ESCAPE-ONE-CHAR - escape a single character then append.
      *----------------------------------------------------------------*
       9310-ESCAPE-ONE-CHAR.
           EVALUATE TRUE
               WHEN WS-ESC-CHAR = '"'
                   MOVE '\"' TO WS-APP-STR(1:2)
                   MOVE 2 TO WS-APP-LEN
               WHEN WS-ESC-CHAR = '\'
                   MOVE '\\' TO WS-APP-STR(1:2)
                   MOVE 2 TO WS-APP-LEN
               WHEN WS-ESC-CHAR = WS-BS
                   MOVE '\b' TO WS-APP-STR(1:2)
                   MOVE 2 TO WS-APP-LEN
               WHEN WS-ESC-CHAR = WS-TAB
                   MOVE '\t' TO WS-APP-STR(1:2)
                   MOVE 2 TO WS-APP-LEN
               WHEN WS-ESC-CHAR = WS-LF
                   MOVE '\n' TO WS-APP-STR(1:2)
                   MOVE 2 TO WS-APP-LEN
               WHEN WS-ESC-CHAR = WS-FF
                   MOVE '\f' TO WS-APP-STR(1:2)
                   MOVE 2 TO WS-APP-LEN
               WHEN WS-ESC-CHAR = WS-CR
                   MOVE '\r' TO WS-APP-STR(1:2)
                   MOVE 2 TO WS-APP-LEN
               WHEN OTHER
                   PERFORM 9320-ESCAPE-CONTROL
           END-EVALUATE
           PERFORM 9000-APPEND-STR.

      *----------------------------------------------------------------*
      * 9320-ESCAPE-CONTROL - a byte not covered by the five short
      * escapes: if it is an EBCDIC C0 control, emit \u00XX for the
      * matching Unicode code point; otherwise emit the byte verbatim.
      * Only sets WS-APP-STR / WS-APP-LEN; 9310 performs the append.
      *----------------------------------------------------------------*
       9320-ESCAPE-CONTROL.
           SET WS-C0-NO-MATCH TO TRUE
           PERFORM VARYING WS-C0-IDX FROM 1 BY 1
                   UNTIL WS-C0-IDX > WS-C0-COUNT
                       OR WS-C0-IS-MATCH
               IF WS-ESC-CHAR = WS-C0-BYTE(WS-C0-IDX)
                   MOVE '\u00' TO WS-APP-STR(1:4)
                   MOVE WS-C0-HX(WS-C0-IDX) TO WS-APP-STR(5:2)
                   MOVE 6 TO WS-APP-LEN
                   SET WS-C0-IS-MATCH TO TRUE
               END-IF
           END-PERFORM
           IF WS-C0-NO-MATCH
               MOVE WS-ESC-CHAR TO WS-APP-STR(1:1)
               MOVE 1 TO WS-APP-LEN
           END-IF.

      *----------------------------------------------------------------*
      * 9400-APPEND-RESULT-CHAR - append WS-RCHAR to the parser result
      * with a bounds check against the 256-byte result field.
      *----------------------------------------------------------------*
       9400-APPEND-RESULT-CHAR.
           IF JP-PARSE-RESULT-LEN < 256
               ADD 1 TO JP-PARSE-RESULT-LEN
               MOVE WS-RCHAR
                    TO JP-PARSE-RESULT(JP-PARSE-RESULT-LEN:1)
           ELSE
               SET JP-ERROR TO TRUE
           END-IF.
      *
      * Ver: CardDemo_v1.0 REST/JSON API layer - COJSONUC
      *

