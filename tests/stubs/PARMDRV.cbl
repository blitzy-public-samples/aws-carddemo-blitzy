      ******************************************************************
      * Program     : PARMDRV.cbl
      * Application : CardDemo characterization suite (tests/stubs)
      * Type        : Test driver, run as an executable by the
      *               gnucobol-batch adapter
      * Function    : Supplies the z/OS EXEC PGM=...,PARM='...' linkage
      *               to a batch program built as a module, then calls
      *               it, so that CBACT04C (PROCEDURE DIVISION USING
      *               EXTERNAL-PARMS) can run under GnuCOBOL.
      ******************************************************************
      * Boundary
      *   This source belongs to the test tree. It is never compiled
      *   into, copied over or placed under app/. build.py compiles it
      *   with cobc -x and the same dialect flags as the programs
      *   (-std=ibm -fsign=EBCDIC -ftab-width=4 -fbinary-truncate), so
      *   the PARM-LENGTH halfword is big-endian on both sides of the
      *   CALL, as it is on z/OS.
      *
      * Invocation contract
      *   <build dir>/PARMDRV <program> <parm text>
      *   - cwd is the scenario working directory; COB_LIBRARY_PATH
      *     holds <program>.so and the stubs it calls (CEE3ABD.so);
      *     the DD_<ddname> variables map the program's files.
      *   - INTCALC STEP15 PARM='2022071800' is run as
      *     PARMDRV CBACT04C 2022071800, which passes PARM-LENGTH 10.
      *   - The parameter block passed BY REFERENCE is a halfword
      *     binary length followed by 100 bytes of text, padded with
      *     spaces.
      *   - Exit status: the called program's RETURN-CODE. A program
      *     that abends through the CEE3ABD stub ends the run itself
      *     (stderr marker CARDDEMO-ABEND CEE3ABD, exit 16).
      *   - stdout carries only the called program's DISPLAY lines.
      *     The driver writes to stderr alone, and only on its own
      *     failures, each line beginning CARDDEMO-PARMDRV and followed
      *     by exit status 255:
      *       CARDDEMO-PARMDRV no program name
      *       CARDDEMO-PARMDRV program name too long: <name>
      *       CARDDEMO-PARMDRV cannot load <name>
      *
      * Command-line parsing
      *   libcob joins argv[1..] with single spaces. Leading spaces are
      *   skipped, the program name runs to the next space, and the
      *   PARM text is everything after that one separating space,
      *   with leading and interior spaces kept. PARM-LENGTH is the
      *   position of the last non-space character of the text, 0 for
      *   a blank text, capped at 100.
      *
      * Limitation (GAP-STB-004)
      *   - Only the PARM linkage is emulated: no JCL symbol
      *     substitution, no PARM quoting or apostrophe-doubling rules,
      *     and no PARMDD.
      *   - Trailing spaces of the text are not counted in PARM-LENGTH,
      *     where z/OS counts those inside the quotes.
      *   - Text beyond 100 characters is truncated to 100, where z/OS
      *     rejects such a PARM with a JCL error.
      *   - Program names longer than 30 characters are refused;
      *     z/OS load module names have at most 8.
      *
      * Determinism
      *   No file, clock, environment variable or CANCEL is used, and
      *   the driver displays nothing on stdout, so its own behaviour
      *   is the same on every run.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    PARMDRV.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-CMDLINE              PIC X(256) VALUE SPACES.
       01  WS-PROGRAM              PIC X(30)  VALUE SPACES.
       01  WS-TEXT                 PIC X(256) VALUE SPACES.
       01  WS-TEXT-REVERSED        PIC X(256) VALUE SPACES.

       01  WS-SCAN.
           05  WS-LEADING-SPACES   PIC S9(4) COMP VALUE 0.
           05  WS-NAME-START       PIC S9(4) COMP VALUE 0.
           05  WS-NAME-LEN         PIC S9(4) COMP VALUE 0.
           05  WS-TEXT-START       PIC S9(4) COMP VALUE 0.
           05  WS-TRAILING-SPACES  PIC S9(4) COMP VALUE 0.
           05  WS-TEXT-LEN         PIC S9(4) COMP VALUE 0.

       01  PARM-AREA.
           05  PARM-LENGTH         PIC S9(4) COMP VALUE 0.
           05  PARM-TEXT           PIC X(100) VALUE SPACES.

       PROCEDURE DIVISION.
       0000-MAIN.
           ACCEPT WS-CMDLINE FROM COMMAND-LINE
           PERFORM 1000-SPLIT-COMMAND-LINE
           PERFORM 2000-CHECK-PROGRAM-NAME
           PERFORM 3000-BUILD-PARM-AREA

      *    RETURN-CODE is left as the CALL sets it, so that the exit
      *    status of STOP RUN is the called program's return code.
           CALL WS-PROGRAM USING PARM-AREA
               ON EXCEPTION
                   DISPLAY 'CARDDEMO-PARMDRV cannot load '
                           FUNCTION TRIM(WS-PROGRAM)
                           UPON SYSERR
                   MOVE 255 TO RETURN-CODE
           END-CALL

           STOP RUN.

      *---------------------------------------------------------------*
      * Locate the program name and the PARM text in the command line.
      * INSPECT does the scanning, so no reference modification ever
      * starts outside WS-CMDLINE.
      *---------------------------------------------------------------*
       1000-SPLIT-COMMAND-LINE.
           MOVE 0 TO WS-LEADING-SPACES
           INSPECT WS-CMDLINE
               TALLYING WS-LEADING-SPACES FOR LEADING SPACES

           MOVE 0 TO WS-NAME-LEN
           MOVE SPACES TO WS-TEXT
           IF WS-LEADING-SPACES < LENGTH OF WS-CMDLINE
               COMPUTE WS-NAME-START = WS-LEADING-SPACES + 1
               INSPECT WS-CMDLINE(WS-NAME-START:)
                   TALLYING WS-NAME-LEN
                   FOR CHARACTERS BEFORE INITIAL SPACE
               COMPUTE WS-TEXT-START =
                       WS-NAME-START + WS-NAME-LEN + 1
               IF WS-TEXT-START <= LENGTH OF WS-CMDLINE
                   MOVE WS-CMDLINE(WS-TEXT-START:) TO WS-TEXT
               END-IF
           END-IF
           EXIT.

      *---------------------------------------------------------------*
      * Refuse a missing name, and a name WS-PROGRAM cannot hold whole:
      * a truncated name could load a different program.
      *---------------------------------------------------------------*
       2000-CHECK-PROGRAM-NAME.
           IF WS-NAME-LEN = 0
               DISPLAY 'CARDDEMO-PARMDRV no program name' UPON SYSERR
               MOVE 255 TO RETURN-CODE
               STOP RUN
           END-IF

           IF WS-NAME-LEN > LENGTH OF WS-PROGRAM
               DISPLAY 'CARDDEMO-PARMDRV program name too long: '
                       WS-CMDLINE(WS-NAME-START:WS-NAME-LEN)
                       UPON SYSERR
               MOVE 255 TO RETURN-CODE
               STOP RUN
           END-IF

           MOVE WS-CMDLINE(WS-NAME-START:WS-NAME-LEN) TO WS-PROGRAM
           EXIT.

      *---------------------------------------------------------------*
      * PARM-LENGTH is the position of the last non-space character of
      * the text, capped at the 100 bytes of PARM-TEXT; PARM-TEXT takes
      * the first 100 characters, padded with spaces.
      *---------------------------------------------------------------*
       3000-BUILD-PARM-AREA.
           MOVE FUNCTION REVERSE(WS-TEXT) TO WS-TEXT-REVERSED
           MOVE 0 TO WS-TRAILING-SPACES
           INSPECT WS-TEXT-REVERSED
               TALLYING WS-TRAILING-SPACES FOR LEADING SPACES
           COMPUTE WS-TEXT-LEN =
                   LENGTH OF WS-TEXT - WS-TRAILING-SPACES

           IF WS-TEXT-LEN > LENGTH OF PARM-TEXT
               MOVE LENGTH OF PARM-TEXT TO WS-TEXT-LEN
           END-IF

           MOVE WS-TEXT-LEN TO PARM-LENGTH
           MOVE WS-TEXT     TO PARM-TEXT
           EXIT.
