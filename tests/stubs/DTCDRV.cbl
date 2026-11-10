      ******************************************************************
      * Program     : DTCDRV.cbl
      * Application : CardDemo characterization suite (tests/stubs)
      * Type        : Test driver, the caller of CSUTLDTC on the
      *               gnucobol-batch adapter only
      * Function    : Reads a list of date and mask pairs, CALLs
      *               CSUTLDTC once per pair, and prints the 80-byte
      *               result and the RETURN-CODE of every call.
      ******************************************************************
      * Boundary
      *   This source belongs to the test tree. It is never compiled
      *   into, copied over or placed under app/. build.py compiles it
      *   with cobc -x into the build cache. CSUTLDTC (cobc -m) and the
      *   CEEDAYS stub (tests/stubs/ceedays.c, built as CEEDAYS.so) are
      *   both found through COB_LIBRARY_PATH.
      *
      * Limitation (GAP-STB-004)
      *   - Only the CALL interface of CSUTLDTC is emulated, not the
      *     CICS context of its real callers: CORPT00C and COTRN02C
      *     (mask YYYY-MM-DD), and COACTUPC through CSUTLDPY
      *     EDIT-DATE-LE (mask YYYYMMDD). Those run only on UniKix.
      *   - The callers' surrounding storage is not reproduced. Every
      *     argument here is a clean X(10), X(10) or X(80) item, while
      *     CSUTLDPY passes the 8-byte WS-EDIT-DATE-CCYYMMDD, so the
      *     10-byte date CSUTLDTC reads there ends in binary bytes.
      *   - CSUTLDTC ends with EXIT PROGRAM and is never cancelled, so
      *     its working storage persists from one call to the next.
      *
      * Input contract
      *   ddname DTCIN (DD_DTCIN): record sequential, fixed 80-byte
      *   records, no newlines. Bytes 1-10 are the date and bytes
      *   11-20 the mask, passed as they stand; bytes 21-80 are
      *   ignored. The adapter writes it from the scenario's
      *   param_files, and its size must be a multiple of 80: libcob
      *   returns a short final record with status 00 and leaves the
      *   rest of the record area as the previous READ filled it.
      *
      * Output contract
      *   - stdout: one line per DTCIN record, in file order:
      *       DTCDRV DATE=<d> MASK=<m> RC=<rc> RESULT=<p> HEX=<h>
      *     <d>, <m>  the date and the mask, X(10) each, as passed
      *     <rc>      RETURN-CODE after the call, 4 digits: the
      *               severity CSUTLDTC sets
      *     <p>       the 80-byte result, each byte outside x'20' to
      *               x'7E' shown as '.'
      *     <h>       the same 80 bytes as 160 upper-case hex digits
      *     Every field keeps its full width, trailing spaces
      *     included. Result bytes 46-47 hold the binary Vstring
      *     length x'000A', copied by CSUTLDTC's MOVE WS-DATE-TO-TEST
      *     TO WS-DATE; its x'0A' would split a raw DISPLAY of the
      *     result, hence the two renderings. That byte pair is pinned
      *     as observed, never corrected.
      *   - Exit status 0 after the last record. An empty DTCIN gives
      *     no line and exit status 0.
      *   - stderr, for harness failures only: one line beginning
      *     CARDDEMO-DTCDRV, then exit status 255. DTCIN open, read or
      *     close failed (status=<ss>), or CSUTLDTC could not be
      *     loaded. The adapter treats these as harness errors, never
      *     as program behaviour.
      *   No clock or environment variable is read here, so the output
      *   depends only on DTCIN and on what CSUTLDTC and CEEDAYS return.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    DTCDRV.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT DTC-FILE ASSIGN TO DTCIN
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-DTC-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  DTC-FILE
           RECORD CONTAINS 80 CHARACTERS.
       01  DTC-REC.
           05  DTC-DATE                PIC X(10).
           05  DTC-MASK                PIC X(10).
           05  FILLER                  PIC X(60).

       WORKING-STORAGE SECTION.
      *    The three CALL arguments, shaped like CORPT00C's
      *    CSUTLDTC-DATE, CSUTLDTC-DATE-FORMAT and CSUTLDTC-RESULT.
       01  WS-DATE                     PIC X(10).
       01  WS-MASK                     PIC X(10).
       01  WS-RESULT                   PIC X(80).
       01  WS-RC-DISP                  PIC 9(4).

       01  WS-PRINT                    PIC X(80).
       01  WS-HEX                      PIC X(160).
       01  WS-HEX-DIGITS               PIC X(16)
                                       VALUE '0123456789ABCDEF'.

       01  WS-DTC-STATUS               PIC X(2).
       01  WS-EOF-FLAG                 PIC X VALUE 'N'.
           88  DTC-EOF                 VALUE 'Y'.

       01  WS-IDX                      PIC S9(4) COMP.
       01  WS-HEX-POS                  PIC S9(4) COMP.
       01  WS-BYTE-VAL                 PIC S9(4) COMP.
       01  WS-HI-NIBBLE                PIC S9(4) COMP.
       01  WS-LO-NIBBLE                PIC S9(4) COMP.

       PROCEDURE DIVISION.
       0000-MAIN.
           OPEN INPUT DTC-FILE
           IF WS-DTC-STATUS NOT = '00'
               DISPLAY 'CARDDEMO-DTCDRV DTCIN open failed status='
                       WS-DTC-STATUS UPON SYSERR
               MOVE 255 TO RETURN-CODE
               STOP RUN
           END-IF

           PERFORM 1000-READ-DTCIN
           PERFORM UNTIL DTC-EOF
               PERFORM 2000-CALL-CSUTLDTC
               PERFORM 1000-READ-DTCIN
           END-PERFORM

           CLOSE DTC-FILE
           IF WS-DTC-STATUS NOT = '00'
               DISPLAY 'CARDDEMO-DTCDRV DTCIN close failed status='
                       WS-DTC-STATUS UPON SYSERR
               MOVE 255 TO RETURN-CODE
               STOP RUN
           END-IF

           MOVE 0 TO RETURN-CODE
           STOP RUN.

       1000-READ-DTCIN.
           READ DTC-FILE
               AT END
                   SET DTC-EOF TO TRUE
           END-READ
           IF WS-DTC-STATUS NOT = '00' AND WS-DTC-STATUS NOT = '10'
               DISPLAY 'CARDDEMO-DTCDRV DTCIN read failed status='
                       WS-DTC-STATUS UPON SYSERR
               PERFORM 9000-CLOSE-AND-FAIL
           END-IF.

       2000-CALL-CSUTLDTC.
      *    Prepared as CORPT00C prepares its call: date, mask, and a
      *    result cleared to spaces.
           MOVE DTC-DATE TO WS-DATE
           MOVE DTC-MASK TO WS-MASK
           MOVE SPACES   TO WS-RESULT

      *    ON EXCEPTION only catches a CSUTLDTC that cannot be loaded.
      *    Without it libcob ends the run with exit status 1, which
      *    would read as a return code; the CARDDEMO-DTCDRV line and
      *    exit status 255 mark it as a harness error. The program is
      *    deliberately never CANCELled.
           CALL 'CSUTLDTC' USING WS-DATE WS-MASK WS-RESULT
               ON EXCEPTION
                   DISPLAY 'CARDDEMO-DTCDRV cannot load CSUTLDTC'
                           UPON SYSERR
                   PERFORM 9000-CLOSE-AND-FAIL
           END-CALL
           MOVE RETURN-CODE TO WS-RC-DISP

           PERFORM 3000-RENDER-RESULT

           DISPLAY 'DTCDRV DATE=' WS-DATE
                   ' MASK=' WS-MASK
                   ' RC=' WS-RC-DISP
                   ' RESULT=' WS-PRINT
                   ' HEX=' WS-HEX.

       3000-RENDER-RESULT.
           PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 80
               COMPUTE WS-BYTE-VAL =
                       FUNCTION ORD(WS-RESULT(WS-IDX:1)) - 1
               IF WS-BYTE-VAL >= 32 AND WS-BYTE-VAL <= 126
                   MOVE WS-RESULT(WS-IDX:1) TO WS-PRINT(WS-IDX:1)
               ELSE
                   MOVE '.' TO WS-PRINT(WS-IDX:1)
               END-IF
               DIVIDE WS-BYTE-VAL BY 16
                   GIVING WS-HI-NIBBLE REMAINDER WS-LO-NIBBLE
               COMPUTE WS-HEX-POS = WS-IDX * 2 - 1
               MOVE WS-HEX-DIGITS(WS-HI-NIBBLE + 1:1)
                 TO WS-HEX(WS-HEX-POS:1)
               MOVE WS-HEX-DIGITS(WS-LO-NIBBLE + 1:1)
                 TO WS-HEX(WS-HEX-POS + 1:1)
           END-PERFORM.

       9000-CLOSE-AND-FAIL.
      *    DTCIN is still open here. Closing it before STOP RUN keeps
      *    stderr to the single diagnostic line, because libcob would
      *    otherwise add an implicit-CLOSE warning. Its status is not
      *    tested: the run is already failing.
           CLOSE DTC-FILE
           MOVE 255 TO RETURN-CODE
           STOP RUN.
