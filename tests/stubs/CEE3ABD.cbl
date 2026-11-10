      ******************************************************************
      * Program     : CEE3ABD.cbl
      * Application : CardDemo characterization suite (tests/stubs)
      * Type        : Test stub, called dynamically by the batch units
      * Function    : Stands in for the Language Environment service
      *               CEE3ABD on the gnucobol-batch adapter only.
      ******************************************************************
      * Boundary
      *   This source belongs to the test tree. It is never compiled
      *   into, copied over or placed under app/. build.py compiles it
      *   with cobc -m into the build cache, and the adapter puts that
      *   directory on COB_LIBRARY_PATH for the program under test.
      *
      * Callers
      *   Nine CB* programs call it from their abend paragraph, each
      *   after DISPLAY 'ABENDING PROGRAM'. Eight of them, CBACT01C to
      *   CBACT04C, CBCUS01C and CBTRN01C to CBTRN03C, also run
      *   MOVE 0 TO TIMING and MOVE 999 TO ABCODE before the call.
      *   CBSTM03A runs neither MOVE and declares no TIMING or ABCODE
      *   field. No caller codes USING on CALL 'CEE3ABD', so no
      *   parameter ever arrives (GAP-DEF-001, pinned as observed).
      *   The program therefore has no LINKAGE SECTION and no
      *   PROCEDURE USING.
      *
      * Limitation (GAP-STB-001)
      *   - No abend code. The eight callers that set ABCODE set it
      *     to 999 (user abend U0999 on z/OS) but never pass it, and
      *     CBSTM03A sets none, so none is reproduced.
      *   - No LE condition handling, no CEEDUMP and no TIMING
      *     semantics.
      *   - STOP RUN closes the run unit's open files normally, where
      *     a z/OS abend leaves each dataset to its abnormal DISP.
      *     libcob reports each such close on stderr after the marker,
      *     as a line beginning 'libcob: warning: implicit CLOSE'.
      *
      * Output contract
      *   - stderr: the stub itself writes exactly one line, the
      *     marker CARDDEMO-ABEND CEE3ABD. After it, libcob may add
      *     one line for each file still open when STOP RUN closes
      *     the run unit, each beginning 'libcob: warning: implicit
      *     CLOSE'. The adapter maps the marker to return_code null
      *     and appends the marker to the result messages. Lines
      *     beginning 'libcob:' go only to the raw evidence.
      *   - stdout: nothing.
      *   - Exit status 16. The adapter does not rely on it.
      *   - Control never returns to the caller.
      *   For example, CBTRN02C run with XREFFILE absent writes these
      *   three stderr lines, the first from the stub:
      *    CARDDEMO-ABEND CEE3ABD
      *    libcob: warning: implicit CLOSE of TRANSACT-FILE ('TRANFILE')
      *    libcob: warning: implicit CLOSE of DALYTRAN-FILE ('DALYTRAN')
      *   The stub uses no clock, file, environment variable or working
      *   storage, so its own output is the same on every run. The
      *   libcob lines depend on which files the caller left open.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CEE3ABD.

       PROCEDURE DIVISION.
       0000-ABEND-RUN-UNIT.
           DISPLAY 'CARDDEMO-ABEND CEE3ABD' UPON SYSERR
           END-DISPLAY
           MOVE 16 TO RETURN-CODE
           STOP RUN.
