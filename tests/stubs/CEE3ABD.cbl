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
      *   Every CB* abend path runs MOVE 0 TO TIMING, MOVE 999 TO
      *   ABCODE, then CALL 'CEE3ABD' with no USING, so no parameter
      *   ever arrives (GAP-DEF-001, pinned as observed). The program
      *   therefore has no LINKAGE SECTION and no PROCEDURE USING.
      *
      * Limitation (GAP-STB-001)
      *   - No abend code. The callers set ABCODE to 999 (user abend
      *     U0999 on z/OS) but never pass it, so none is reproduced.
      *   - No LE condition handling, no CEEDUMP and no TIMING
      *     semantics.
      *   - STOP RUN closes the run unit's open files normally, where
      *     a z/OS abend leaves each dataset to its abnormal DISP.
      *     libcob reports each such close on stderr after the marker,
      *     as a line beginning 'libcob: warning: implicit CLOSE'.
      *
      * Output contract
      *   - stderr: exactly one line, CARDDEMO-ABEND CEE3ABD. The
      *     adapter maps it to return_code null and appends it to the
      *     result messages.
      *   - stdout: nothing.
      *   - Exit status 16. The adapter does not rely on it.
      *   - Control never returns to the caller.
      *   No clock, file, environment variable or working storage is
      *   used, so the output is the same on every run.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CEE3ABD.

       PROCEDURE DIVISION.
       0000-ABEND-RUN-UNIT.
           DISPLAY 'CARDDEMO-ABEND CEE3ABD' UPON SYSERR
           MOVE 16 TO RETURN-CODE
           STOP RUN.
