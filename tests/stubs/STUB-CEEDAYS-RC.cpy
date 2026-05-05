      ******************************************************************
      *****       STUB-CEEDAYS-RC - SHARED CEEDAYS STATE (TEST) *******
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
      *  Shared EXTERNAL state for the CEEDAYS stub.  The cobol-check
      *  testsuite sets WS-CEEDAYS-RC-OVERRIDE in BEFORE-EACH; the
      *  CEEDAYS stub reads it and copies the bytes into the LINKAGE
      *  FEEDBACK-CODE that it returns to CSUTLDTC.
      *  WS-CEEDAYS-LILLIAN-OVERRIDE provides the same mechanism for
      *  the OUTPUT-LILLIAN parameter.
      *
      *  Consumers
      *  ---------
      *    tests/stubs/CEEDAYS.cbl        (reads WS-CEEDAYS-RC-
      *                                    OVERRIDE and WS-CEEDAYS-
      *                                    LILLIAN-OVERRIDE; copies
      *                                    them into the LINKAGE
      *                                    FEEDBACK-CODE and OUTPUT-
      *                                    LILLIAN returned to the
      *                                    caller)
      *    tests/cobol-check/CSUTLDTC.cut (resets in BEFORE-EACH and
      *                                    MOVEs an 8-byte X'...'
      *                                    literal matching an 88-
      *                                    level VALUE in production
      *                                    CSUTLDTC.cbl to drive a
      *                                    specific EVALUATE branch)
      *
      *  Default values
      *  --------------
      *  WS-CEEDAYS-RC-OVERRIDE defaults to X'0000000000000000'
      *  which matches production 88-level FC-INVALID-DATE.  Despite
      *  the misleading name, the production EVALUATE TRUE block in
      *  CSUTLDTC paragraph A000-MAIN treats this token as success
      *  and sets WS-RESULT = 'Date is valid'.  Happy-path testcases
      *  therefore inherit the default and do not have to MOVE
      *  anything; negative-path testcases explicitly MOVE a
      *  different X'...' literal corresponding to FC-INSUFFICIENT-
      *  DATA, FC-BAD-DATE-VALUE, FC-INVALID-ERA, FC-UNSUPP-RANGE,
      *  FC-INVALID-MONTH, FC-BAD-PIC-STRING, FC-NON-NUMERIC-DATA,
      *  or FC-YEAR-IN-ERA-ZERO.
      *  WS-CEEDAYS-LILLIAN-OVERRIDE defaults to 0; the production
      *  EVALUATE in CSUTLDTC does not consult OUTPUT-LILLIAN, so
      *  this field is provided as a forward-compatibility hook for
      *  tests that may wish to verify the Lillian day value.
      *
      *  This file is INFRASTRUCTURE only; it contains no business
      *  logic.  Pure data declarations and VALUE clauses
      *  establishing safe defaults.
      ******************************************************************
      *
       01 STUB-CEEDAYS-STATE EXTERNAL.
           05 WS-CEEDAYS-RC-OVERRIDE         PIC X(8)
                                             VALUE X'0000000000000000'.
           05 WS-CEEDAYS-LILLIAN-OVERRIDE    PIC S9(9) USAGE IS BINARY
                                             VALUE 0.
      *
      * End of STUB-CEEDAYS-RC copybook.
      *
