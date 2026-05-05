      ******************************************************************
      *****       DFHEI1 STUB - CICS API LINK-TIME FALLBACK ***********
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
      *  Provide a hand-authored COBOL stub that catches CALL "DFHEI1"
      *  invocations at link time when a cobol-check testsuite forgot
      *  to declare a MOCK CICS directive for the corresponding
      *  EXEC CICS verb.  The stub does NOT replace working MOCK CICS
      *  directives -- those are intercepted by cobol-check's own
      *  runtime.  This stub only acts as a "fail loudly" backstop.
      *
      *  Background
      *  ----------
      *  In IBM CICS, every EXEC CICS <verb> ... END-EXEC. directive
      *  is translated by the CICS translator into a sequence of
      *  COBOL statements that ultimately makes a
      *      CALL 'DFHEI1' USING <command-block> ...
      *  invocation.  The DFHEI1 entry point dispatches to the
      *  appropriate CICS service.  On z/OS that's the real CICS
      *  dispatcher; off-platform (GnuCOBOL on Linux) it is undefined.
      *  When a cobol-check testsuite contains MOCK CICS <verb>, the
      *  precompiler intercepts the call and substitutes mock
      *  behaviour.  When a testsuite forgets to declare a mock,
      *  the call falls through to THIS stub instead.
      *
      *  Behaviour
      *  ---------
      *      MOVE 'Y'  TO WS-DFHEI1-UNMOCKED-CALLED  (observable flag)
      *      MOVE 27   TO WS-DFHEI1-EIBRESP-OVERRIDE (NOTAUTH RESP)
      *      DISPLAY   'DFHEI1 STUB INVOKED ...'
      *      GOBACK.
      *
      *  EIBRESP=27 (NOTAUTH) is rare in real applications, so any
      *  test failure mentioning RESP=27 is almost certainly the
      *  result of THIS stub being hit -- pointing the developer
      *  straight at the missing MOCK CICS directive.
      *
      *  Linkage compatibility
      *  ---------------------
      *  The CICS DFHEI1 entry point accepts a variable number of
      *  parameters depending on the verb (RECEIVE MAP passes 4,
      *  READ FILE passes 6, RETURN passes 3, etc.).  To accept any
      *  caller signature, this stub's PROCEDURE DIVISION declares
      *  NO USING clause.  COBOL's calling convention silently
      *  discards arguments passed to a callee that does not declare
      *  USING.  GnuCOBOL may emit an informational warning that is
      *  acceptable and harmless.
      *
      *  Constraints
      *  -----------
      *  This file is INFRASTRUCTURE only; it contains NO business
      *  logic, NO arithmetic, NO conditional logic, NO PERFORM of
      *  collaborator paragraphs, NO file I/O, NO sub-program CALL.
      *  The stub is deterministic: same inputs (none) produce the
      *  same observable side effects on every invocation.  It MUST
      *  return via GOBACK (never STOP RUN, never abend, never
      *  recurse) so the production CICS program continues executing
      *  along its non-mock error path and surfaces the missing-mock
      *  defect through the testsuite's own EXPECT assertions.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DFHEI1.
       AUTHOR.     CARDDEMO-TESTS.
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY STUB-ABEND-FLAG.
       PROCEDURE DIVISION.
      *
      *  Single-paragraph entry point.  The PROCEDURE DIVISION has
      *  no USING clause so the stub can be CALLed with any number
      *  of arguments (CICS verbs pass differing parameter counts).
      *
       0000-DFHEI1-MAIN.
           MOVE 'Y' TO WS-DFHEI1-UNMOCKED-CALLED
           MOVE 27  TO WS-DFHEI1-EIBRESP-OVERRIDE
           DISPLAY 'DFHEI1 STUB INVOKED - MISSING MOCK CICS DIRECTIVE'
           GOBACK.
      *
      * End of DFHEI1 stub.
      *
