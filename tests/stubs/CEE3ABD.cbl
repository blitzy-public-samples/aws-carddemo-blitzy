      ******************************************************************
      *****       CEE3ABD STUB - LE ABEND PASSTHROUGH (TEST ONLY) ******
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
      *
      *  Purpose
      *  -------
      *  Provide a hand-authored COBOL stub that catches CALL 'CEE3ABD'
      *  invocations at link time so the off-platform GnuCOBOL build of
      *  every CardDemo batch program (CBACT01C, CBACT02C, CBACT03C,
      *  CBACT04C, CBCUS01C, CBSTM03A, CBTRN01C, CBTRN02C, CBTRN03C)
      *  can run its 9999-ABEND-PROGRAM paragraph WITHOUT terminating
      *  the surrounding test process.
      *
      *  Background
      *  ----------
      *  In IBM Language Environment (LE), CALL 'CEE3ABD' triggers an
      *  abnormal end of the address space using the abend code stored
      *  in WS-ABCODE (a job-step-level field declared by every batch
      *  program in WORKING-STORAGE).  Production sources hard-code
      *  MOVE 999 TO ABCODE immediately before each CALL site:
      *
      *      9999-ABEND-PROGRAM.
      *          DISPLAY 'ABENDING PROGRAM'
      *          MOVE 0   TO TIMING
      *          MOVE 999 TO ABCODE
      *          CALL 'CEE3ABD'.
      *
      *  On z/OS, control never returns from CEE3ABD; the address space
      *  ends with system completion code U0999.  Off-platform that
      *  behaviour would tear down the JVM hosting cobol-check and fail
      *  every testsuite that exercises the abend path.  This stub
      *  therefore records the abend in shared EXTERNAL state and
      *  returns to the caller via plain GOBACK.
      *
      *  Behaviour
      *  ---------
      *      MOVE 'Y'  TO WS-ABEND-FLAG       (observable flag)
      *      MOVE 999  TO WS-ABEND-CODE       (canonical abend reason)
      *      DISPLAY   'CEE3ABD STUB INVOKED - ABEND PATHWAY REACHED'
      *      GOBACK.
      *
      *  Tests assert the abend pathway was reached with:
      *      EXPECT WS-ABEND-FLAG TO BE 'Y'
      *      EXPECT WS-ABEND-CODE TO BE 999
      *  after invoking the production paragraph that performs
      *  9999-ABEND-PROGRAM.
      *
      *  Linkage compatibility
      *  ---------------------
      *  Every CALL site in app/cbl/*.cbl invokes the service WITHOUT a
      *  USING clause:
      *      CALL 'CEE3ABD'.
      *  Therefore this stub's PROCEDURE DIVISION declares NO USING
      *  clause and there is NO LINKAGE SECTION.  This matches the
      *  production CALL site signature exactly and avoids link-time
      *  argument-count mismatches.  The IBM LE documentation describes
      *  CEE3ABD as taking USING ABEND-CODE CLEANUP, but the CardDemo
      *  call sites omit those parameters and rely on LE picking up
      *  ABCODE from a per-program convention; the stub honours the
      *  call-site signature as observed, not the IBM specification.
      *
      *  Shared state
      *  ------------
      *  The stub writes WS-ABEND-FLAG and WS-ABEND-CODE on the
      *  STUB-ABEND-STATE 01-level group declared as EXTERNAL in
      *  tests/stubs/STUB-ABEND-FLAG.cpy.  Because the group carries
      *  the EXTERNAL clause, the same memory cell is visible to:
      *      * this stub (via WORKING-STORAGE COPY STUB-ABEND-FLAG)
      *      * every cobol-check testsuite (also via COPY)
      *      * the merged production-program-under-test binary
      *  All three see writes from any participant.  cobol-check
      *  testsuites reset the group in BEFORE-EACH so test cases do
      *  not leak abend state between cases.
      *
      *  Constraints (must hold on every commit)
      *  --------------------------------------
      *  This file is INFRASTRUCTURE only; it contains NO business
      *  logic, NO arithmetic operators (COMPUTE / ADD / SUBTRACT /
      *  MULTIPLY / DIVIDE), NO conditional logic on business fields,
      *  NO PERFORM of collaborator paragraphs, NO file I/O, and NO
      *  sub-program CALL.  The stub is deterministic: the same
      *  observable side effects are produced on every invocation
      *  given that BEFORE-EACH initialises the EXTERNAL group to
      *  WS-ABEND-FLAG = 'N' and WS-ABEND-CODE = 0.  The stub MUST
      *  return via plain GOBACK -- never STOP RUN, never EXIT
      *  PROGRAM WITH ABEND, never GOBACK WITH ABEND, never any form
      *  of recursion -- so the production paragraph that performed
      *  9999-ABEND-PROGRAM continues normally and the testsuite
      *  observes the post-abend state through EXPECT assertions.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       AUTHOR.     CARDDEMO-TESTS.
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY STUB-ABEND-FLAG.
       PROCEDURE DIVISION.
      *
      *  Single-paragraph entry point.  The PROCEDURE DIVISION has no
      *  USING clause so the stub matches the parameter-less CALL
      *  signature observed in every CardDemo batch program's
      *  9999-ABEND-PROGRAM paragraph.
      *
       0000-CEE3ABD-MAIN.
           MOVE 'Y' TO WS-ABEND-FLAG
           MOVE 999 TO WS-ABEND-CODE
           DISPLAY 'CEE3ABD STUB INVOKED - ABEND PATHWAY REACHED'
           GOBACK.
      *
      * End of CEE3ABD stub.
      *
