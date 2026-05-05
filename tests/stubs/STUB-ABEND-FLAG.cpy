      ******************************************************************
      *****       STUB-ABEND-FLAG - SHARED ABEND/CICS STATE (TEST) ****
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
      *  Shared EXTERNAL state for the CEE3ABD and DFHEI1 stubs.
      *  The cobol-check testsuite resets these fields in BEFORE-EACH;
      *  the CEE3ABD stub sets WS-ABEND-FLAG/WS-ABEND-CODE when the
      *  production code performs 9999-ABEND-PROGRAM; the DFHEI1 stub
      *  sets WS-DFHEI1-UNMOCKED-CALLED/WS-DFHEI1-EIBRESP-OVERRIDE
      *  when an EXEC CICS verb without a MOCK CICS directive falls
      *  through.
      *
      *  Consumers
      *  ---------
      *    tests/stubs/CEE3ABD.cbl      (writes WS-ABEND-FLAG = 'Y' and
      *                                  WS-ABEND-CODE = 999, mirroring
      *                                  production MOVE 999 TO ABCODE)
      *    tests/stubs/DFHEI1.cbl      (writes WS-DFHEI1-UNMOCKED-CALLED
      *                                 = 'Y' and WS-DFHEI1-EIBRESP-
      *                                 OVERRIDE = 27 (NOTAUTH))
      *    tests/cobol-check/*.cut      (resets in BEFORE-EACH and
      *                                  asserts via EXPECT/VERIFY)
      *
      *  This file is INFRASTRUCTURE only; it contains no business
      *  logic.  Pure data declarations, 88-level condition names, and
      *  VALUE clauses establishing safe defaults.
      ******************************************************************
      *
       01 STUB-ABEND-STATE EXTERNAL.
           05 WS-ABEND-FLAG                  PIC X        VALUE 'N'.
              88 WS-ABEND-NOT-RAISED         VALUE 'N'.
              88 WS-ABEND-RAISED             VALUE 'Y'.
           05 WS-ABEND-CODE                  PIC S9(9) USAGE IS BINARY
                                             VALUE 0.
           05 WS-DFHEI1-UNMOCKED-CALLED      PIC X        VALUE 'N'.
              88 WS-DFHEI1-ALL-MOCKED        VALUE 'N'.
              88 WS-DFHEI1-MOCK-MISSING      VALUE 'Y'.
           05 WS-DFHEI1-EIBRESP-OVERRIDE     PIC S9(9) USAGE IS BINARY
                                             VALUE 0.
      *
      * End of STUB-ABEND-FLAG copybook.
      *
