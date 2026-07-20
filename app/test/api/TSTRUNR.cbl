      ****************************************************************
      * Program     : TSTRUNR                                        *
      * Application : CardDemo REST/JSON API - QA harness            *
      * Type        : Batch EXCI client (NOT an online CICS program) *
      * Feature     : MAJ-07 - one runner that invokes all six API   *
      *               test drivers and gates release on their        *
      *               machine-readable verdicts.                     *
      ****************************************************************
      * Why this exists:
      *   The six TST* drivers each run in the CICS region and
      *   publish a PASS/FAIL/SKIP verdict.  A COBOL RETURN-CODE is
      *   NOT carried back across an EXEC CICS LINK / EXCI DPL
      *   boundary - only a COMMAREA (or an abend) is - so each
      *   driver now ALSO writes its verdict into the shared COAPDRVY
      *   result COMMAREA when a caller passes one (EIBCALEN > 0).
      *   This runner is that caller: it EXCI-LINKs each driver,
      *   reads the verdict, and aggregates them into one batch
      *   RETURN-CODE so an automated release gate fails on any
      *   non-PASS outcome:
      *     RC = 0  all six drivers PASS
      *     RC = 4  at least one SKIP / incomplete (fixtures absent)
      *     RC = 8  at least one FAIL, LINK failure, or unknown
      *----------------------------------------------------------------*
      * Deployment: translate/compile/link with the EXCI option and
      *   the EXCI stub, then run from batch (app/jcl/APITSTR.jcl).
      *   The target region is resolved by the EXCI connection /
      *   DFHXCOPT in the run JCL, not by this source.  Read-only:
      *   the runner performs no VSAM or TSQ writes.  Rationale:
      *   docs/decision-log.md (D37).
      ****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. TSTRUNR.
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.

      * The six API test-driver PROGRAM names, in execution order.
       01  WS-DRIVER-TABLE.
           05  FILLER                PIC X(08) VALUE 'TSTAUTH'.
           05  FILLER                PIC X(08) VALUE 'TSTACCT'.
           05  FILLER                PIC X(08) VALUE 'TSTCUST'.
           05  FILLER                PIC X(08) VALUE 'TSTCARD'.
           05  FILLER                PIC X(08) VALUE 'TSTXREF'.
           05  FILLER                PIC X(08) VALUE 'TSTTRAN'.
       01  WS-DRIVER-LIST REDEFINES WS-DRIVER-TABLE.
           05  WS-DRIVER-NAME        PIC X(08) OCCURS 6 TIMES.

       01  WS-IDX                    PIC S9(04) COMP VALUE 0.
       01  WS-NUM-DRIVERS            PIC S9(04) COMP VALUE 6.

      * EXEC CICS LINK condition feedback.
       01  WS-RESP                   PIC S9(09) COMP VALUE 0.
       01  WS-RESP2                  PIC S9(09) COMP VALUE 0.

      * Aggregate verdict counters.
       01  WS-CNT-PASS               PIC 9(02) VALUE 0.
       01  WS-CNT-FAIL               PIC 9(02) VALUE 0.
       01  WS-CNT-SKIP               PIC 9(02) VALUE 0.
       01  WS-CNT-ERROR              PIC 9(02) VALUE 0.
       01  WS-OVERALL-RC             PIC S9(04) VALUE 0.

      * Shared driver-result COMMAREA (same layout the drivers write).
       COPY COAPDRVY REPLACING ==DFHCOMMAREA== BY ==WS-DRV-RESULT==.

       PROCEDURE DIVISION.

       0000-MAIN.
           DISPLAY 'TSTRUNR: CardDemo API driver runner - START'
           PERFORM VARYING WS-IDX FROM 1 BY 1
                   UNTIL WS-IDX > WS-NUM-DRIVERS
               PERFORM 1000-RUN-ONE-DRIVER
           END-PERFORM
           PERFORM 9000-AGGREGATE
           MOVE WS-OVERALL-RC TO RETURN-CODE
           GOBACK.

       1000-RUN-ONE-DRIVER.
      *    Pre-set the result to a sentinel so a driver that does not
      *    cooperate (returns no populated verdict) is treated as an
      *    ERROR, never as a silent PASS.
           INITIALIZE WS-DRV-RESULT
           MOVE 'U' TO DRV-VERDICT
           EXEC CICS LINK
                PROGRAM(WS-DRIVER-NAME(WS-IDX))
                COMMAREA(WS-DRV-RESULT)
                LENGTH(LENGTH OF WS-DRV-RESULT)
                SYNCONRETURN
                RESP(WS-RESP) RESP2(WS-RESP2)
           END-EXEC
           IF WS-RESP NOT = DFHRESP(NORMAL)
               ADD 1 TO WS-CNT-ERROR
               DISPLAY 'TSTRUNR: DRIVER=' WS-DRIVER-NAME(WS-IDX)
                       ' LINK-FAILED RESP=' WS-RESP
                       ' RESP2=' WS-RESP2
           ELSE
               EVALUATE TRUE
                   WHEN DRV-FAIL
                       ADD 1 TO WS-CNT-FAIL
                   WHEN DRV-SKIP
                       ADD 1 TO WS-CNT-SKIP
                   WHEN DRV-PASS
                       ADD 1 TO WS-CNT-PASS
                   WHEN OTHER
                       ADD 1 TO WS-CNT-ERROR
               END-EVALUATE
               DISPLAY 'TSTRUNR: DRIVER=' DRV-DRIVER-ID
                       ' VERDICT=' DRV-VERDICT
                       ' RUN='  DRV-TESTS-RUN
                       ' PASS=' DRV-TESTS-PASS
                       ' FAIL=' DRV-TESTS-FAIL
           END-IF.

       9000-AGGREGATE.
           DISPLAY 'TSTRUNR: SUMMARY'
                   ' PASS='  WS-CNT-PASS
                   ' FAIL='  WS-CNT-FAIL
                   ' SKIP='  WS-CNT-SKIP
                   ' ERROR=' WS-CNT-ERROR
           EVALUATE TRUE
               WHEN WS-CNT-FAIL > 0
                   MOVE 8 TO WS-OVERALL-RC
                   DISPLAY 'TSTRUNR: OVERALL RESULT: FAIL (RC=8)'
               WHEN WS-CNT-ERROR > 0
                   MOVE 8 TO WS-OVERALL-RC
                   DISPLAY 'TSTRUNR: OVERALL RESULT: ERROR (RC=8)'
               WHEN WS-CNT-SKIP > 0
                   MOVE 4 TO WS-OVERALL-RC
                   DISPLAY 'TSTRUNR: OVERALL RESULT: INCOMPLETE (RC=4)'
               WHEN OTHER
                   MOVE 0 TO WS-OVERALL-RC
                   DISPLAY 'TSTRUNR: OVERALL RESULT: PASS (RC=0)'
           END-EVALUATE.
      *
      * Ver: CardDemo_v1.0
      *
