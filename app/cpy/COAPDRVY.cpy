      *****************************************************************
      *  COAPDRVY - CardDemo REST/JSON API test-driver RESULT COMMAREA
      *  ------------------------------------------------------------- *
      *  Shared machine-readable verdict contract between the six TST*
      *  QA drivers and the batch EXCI runner (app/test/api/TSTRUNR.cbl
      *  driven by app/jcl/APITSTR.jcl).  It lets an automated release
      *  gate observe each driver's PASS/FAIL/SKIP outcome, because a
      *  COBOL RETURN-CODE is NOT propagated across an EXEC CICS LINK /
      *  EXCI DPL boundary - only a COMMAREA (or an abend) is.
      *
      *  Drivers COPY this straight into their LINKAGE SECTION as the
      *  01 DFHCOMMAREA that CICS auto-addresses on a LINK, and - ONLY
      *  when a caller actually passes it (EIBCALEN > 0) - publish their
      *  verdict here.  With no COMMAREA (started-task mode, EIBCALEN=0)
      *  the drivers behave exactly as before (pure backward compat).
      *
      *  The runner copies the IDENTICAL layout under a WORKING-STORAGE
      *  name so caller and callee never disagree on the wire format:
      *   COPY COAPDRVY REPLACING ==DFHCOMMAREA== BY ==WS-DRV-RESULT==.
      *
      *  Fixed 64-byte layout.  Rationale: docs/decision-log.md (D37).
      *  Feature: MAJ-07 (RC-gated multi-driver runner).
      *****************************************************************
       01  DFHCOMMAREA.
      *  Overall verdict for this driver run (single byte, 88-tested).
           05  DRV-VERDICT              PIC X(01).
               88  DRV-PASS             VALUE 'P'.
               88  DRV-FAIL             VALUE 'F'.
               88  DRV-SKIP             VALUE 'S'.
      *  Assertion counters copied from the driver's own WS totals.
           05  DRV-TESTS-RUN            PIC 9(04).
           05  DRV-TESTS-PASS           PIC 9(04).
           05  DRV-TESTS-FAIL           PIC 9(04).
      *  Identity of the driver that produced the verdict.
           05  DRV-DRIVER-ID            PIC X(08).
      *  Reserved padding to keep the contract fixed at 64 bytes.
           05  FILLER                   PIC X(43).
