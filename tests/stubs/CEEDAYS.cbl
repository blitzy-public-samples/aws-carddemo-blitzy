      ******************************************************************
      *****       CEEDAYS STUB - LE LILLIAN PASSTHROUGH (TEST) *********
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
      *  Provide a hand-authored COBOL stub that catches CALL 'CEEDAYS'
      *  invocations at link time when the off-platform GnuCOBOL build
      *  of app/cbl/CSUTLDTC.cbl performs its A000-MAIN paragraph.  On
      *  z/OS the call would resolve to the IBM Language Environment
      *  callable service that converts a variable-length date string
      *  in a configurable format into the corresponding Lillian-day
      *  binary value (count of days since 1582-10-15) and reports a
      *  12-byte FEEDBACK-CODE.  Off-platform (GnuCOBOL on Linux) the
      *  entry point is undefined; this stub provides a deterministic
      *  passthrough so cobol-check testsuites can drive the production
      *  EVALUATE TRUE block in CSUTLDTC paragraph A000-MAIN through
      *  every 88-level branch (FC-INVALID-DATE through
      *  FC-YEAR-IN-ERA-ZERO and the WHEN OTHER default).
      *
      *  Behaviour
      *  ---------
      *      MOVE WS-CEEDAYS-RC-OVERRIDE      TO LK-FC-TOKEN-VALUE
      *      MOVE WS-CEEDAYS-LILLIAN-OVERRIDE TO LK-OUTPUT-LILLIAN
      *      MOVE 0                           TO LK-FC-I-S-INFO
      *      GOBACK.
      *
      *  No parsing of the input date string, no validation of the
      *  format mask, no computation of the Lillian day, no I/O, and
      *  no sub-program CALL.  The stub reads from the EXTERNAL cell
      *      WS-CEEDAYS-RC-OVERRIDE     (PIC X(8))
      *      WS-CEEDAYS-LILLIAN-OVERRIDE (PIC S9(9) BINARY)
      *  declared in tests/stubs/STUB-CEEDAYS-RC.cpy, copies the bytes
      *  verbatim into the LINKAGE-area FEEDBACK-CODE token plus the
      *  OUTPUT-LILLIAN binary, zeroes the I-S-INFO trailer, and
      *  returns to the caller.
      *
      *  Tests SET WS-CEEDAYS-RC-OVERRIDE in BEFORE-EACH to drive a
      *  specific 88-level branch in the production EVALUATE TRUE.
      *  The eight non-zero feedback tokens accepted by the production
      *  EVALUATE block are (from CSUTLDTC.cbl lines 62-70):
      *      FC-INSUFFICIENT-DATA  X'000309CB59C3C5C5'
      *      FC-BAD-DATE-VALUE     X'000309CC59C3C5C5'
      *      FC-INVALID-ERA        X'000309CD59C3C5C5'
      *      FC-UNSUPP-RANGE       X'000309D159C3C5C5'
      *      FC-INVALID-MONTH      X'000309D559C3C5C5'
      *      FC-BAD-PIC-STRING     X'000309D659C3C5C5'
      *      FC-NON-NUMERIC-DATA   X'000309D859C3C5C5'
      *      FC-YEAR-IN-ERA-ZERO   X'000309D959C3C5C5'
      *  plus the success token X'0000000000000000' (mis-named
      *  FC-INVALID-DATE in production but treated as the success path
      *  by the production EVALUATE -- it sets WS-RESULT to
      *  'Date is valid' and returns severity zero).
      *
      *  Linkage compatibility
      *  ---------------------
      *  Production CSUTLDTC.cbl lines 116-120 invoke the service as:
      *      CALL "CEEDAYS" USING WS-DATE-TO-TEST,
      *                           WS-DATE-FORMAT,
      *                           OUTPUT-LILLIAN,
      *                           FEEDBACK-CODE.
      *  Therefore this stub's PROCEDURE DIVISION USING declares the
      *  same FOUR parameters in the SAME order, mirroring the
      *  WORKING-STORAGE byte layouts of CSUTLDTC byte-for-byte:
      *      LK-DATE-TO-TEST   mirrors WS-DATE-TO-TEST    (VSTRING)
      *      LK-DATE-FORMAT    mirrors WS-DATE-FORMAT     (VSTRING)
      *      LK-OUTPUT-LILLIAN mirrors OUTPUT-LILLIAN     (S9(9) BIN)
      *      LK-FEEDBACK-CODE  mirrors FEEDBACK-CODE      (12 bytes)
      *  Any reordering or layout change breaks linkage byte-for-byte.
      *  The 12-byte FEEDBACK-CODE is split as 8-byte token (matched
      *  by the X'...' values of the production 88-levels) plus a
      *  4-byte I-S-INFO trailer.  The stub uses a flat PIC X(8) for
      *  the token so a single MOVE from WS-CEEDAYS-RC-OVERRIDE
      *  populates the entire token verbatim.
      *
      *  Shared state
      *  ------------
      *  The stub reads (never writes) WS-CEEDAYS-RC-OVERRIDE and
      *  WS-CEEDAYS-LILLIAN-OVERRIDE on the STUB-CEEDAYS-STATE 01-
      *  level group declared as EXTERNAL in
      *  tests/stubs/STUB-CEEDAYS-RC.cpy.  Because the group carries
      *  the EXTERNAL clause, the same memory cell is visible to:
      *      * this stub (via WORKING-STORAGE COPY STUB-CEEDAYS-RC)
      *      * every cobol-check testsuite (also via COPY)
      *      * the merged production-program-under-test binary
      *  All three see writes from any participant.  cobol-check
      *  testsuites reset the group in BEFORE-EACH to safe defaults
      *  so test cases do not leak feedback-code state between cases.
      *
      *  Default behaviour
      *  -----------------
      *  WS-CEEDAYS-RC-OVERRIDE defaults to X'0000000000000000', which
      *  matches production 88-level FC-INVALID-DATE.  Despite the
      *  misleading name, the production EVALUATE TRUE block treats
      *  this token as success and sets WS-RESULT = 'Date is valid'
      *  with RETURN-CODE = 0.  Happy-path testcases therefore inherit
      *  the default and do not have to MOVE anything; negative-path
      *  testcases MOVE a different X'...' literal corresponding to
      *  one of the eight alternative 88-levels.  WS-CEEDAYS-LILLIAN-
      *  OVERRIDE defaults to 0; the production EVALUATE in CSUTLDTC
      *  does not consult OUTPUT-LILLIAN, so this field is provided
      *  as a forward-compatibility hook for tests that may wish to
      *  verify the Lillian day value.
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
      *  the desired override values.  The stub MUST return via
      *  plain GOBACK -- never STOP RUN, never EXIT PROGRAM WITH
      *  ABEND, never any form of recursion -- so the production
      *  paragraph that performed CALL "CEEDAYS" continues normally
      *  and the testsuite observes the post-call state via EXPECT.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEEDAYS.
       AUTHOR.     CARDDEMO-TESTS.
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      *  Shared EXTERNAL state.  Brings WS-CEEDAYS-RC-OVERRIDE and
      *  WS-CEEDAYS-LILLIAN-OVERRIDE into scope under the
      *  STUB-CEEDAYS-STATE 01-level group.  Same memory cell as the
      *  cobol-check testsuite and the merged production binary.
      *
       COPY STUB-CEEDAYS-RC.

       LINKAGE SECTION.
      *
      *  LK-DATE-TO-TEST mirrors WS-DATE-TO-TEST declared at lines
      *  25-31 of app/cbl/CSUTLDTC.cbl: a VSTRING with a 2-byte
      *  S9(4) BINARY length followed by 0..256 character bytes.
      *  The caller supplies the variable-length date string here;
      *  the stub does NOT inspect any byte of this parameter.
      *
       01 LK-DATE-TO-TEST.
          02 LK-DTT-LEN              PIC S9(4) BINARY.
          02 LK-DTT-TXT.
             03 LK-DTT-CHAR          PIC X
                                     OCCURS 0 TO 256 TIMES
                                     DEPENDING ON LK-DTT-LEN
                                     OF LK-DATE-TO-TEST.
      *
      *  LK-DATE-FORMAT mirrors WS-DATE-FORMAT declared at lines
      *  33-39 of app/cbl/CSUTLDTC.cbl: identical VSTRING shape.
      *  Also not inspected by the stub.
      *
       01 LK-DATE-FORMAT.
          02 LK-DFM-LEN              PIC S9(4) BINARY.
          02 LK-DFM-TXT.
             03 LK-DFM-CHAR          PIC X
                                     OCCURS 0 TO 256 TIMES
                                     DEPENDING ON LK-DFM-LEN
                                     OF LK-DATE-FORMAT.
      *
      *  LK-OUTPUT-LILLIAN mirrors OUTPUT-LILLIAN declared at line
      *  41 of app/cbl/CSUTLDTC.cbl: a 4-byte signed binary.  The
      *  stub copies WS-CEEDAYS-LILLIAN-OVERRIDE here so tests can
      *  inspect the value, but production CSUTLDTC does not
      *  consult OUTPUT-LILLIAN in its EVALUATE TRUE block.
      *
       01 LK-OUTPUT-LILLIAN           PIC S9(9) USAGE IS BINARY.
      *
      *  LK-FEEDBACK-CODE mirrors FEEDBACK-CODE declared at lines
      *  60-80 of app/cbl/CSUTLDTC.cbl, with the 8-byte
      *  FEEDBACK-TOKEN-VALUE flattened to PIC X(8) so a single
      *  MOVE from WS-CEEDAYS-RC-OVERRIDE populates the entire
      *  token verbatim.  I-S-INFO trailer mirrors line 80 layout
      *  exactly (PIC S9(9) BINARY = 4 bytes).  Total size 12
      *  bytes, byte-compatible with the production layout.
      *
       01 LK-FEEDBACK-CODE.
          02 LK-FC-TOKEN-VALUE       PIC X(8).
          02 LK-FC-I-S-INFO          PIC S9(9) BINARY.

      *
      *  PROCEDURE DIVISION USING -- the FOUR parameters are listed
      *  in the EXACT order of the production CALL site
      *  (CSUTLDTC.cbl line 116):
      *      WS-DATE-TO-TEST, WS-DATE-FORMAT,
      *      OUTPUT-LILLIAN,  FEEDBACK-CODE.
      *  Reordering breaks linkage byte-for-byte.  LK-DATE-TO-TEST
      *  and LK-DATE-FORMAT are received but intentionally not
      *  inspected -- the stub is a deterministic passthrough.
      *
       PROCEDURE DIVISION USING LK-DATE-TO-TEST,
                                LK-DATE-FORMAT,
                                LK-OUTPUT-LILLIAN,
                                LK-FEEDBACK-CODE.
      *
      *  Single-paragraph entry point.  Body is exactly THREE MOVE
      *  statements followed by a plain GOBACK.  No arithmetic, no
      *  PERFORM, no conditional logic, no file I/O, no sub-program
      *  CALL -- all such operations would violate the "no business
      *  logic" rule that exempts this stub by virtue of being an
      *  infrastructure shim only (AAP Section 0.5.1).
      *
       0000-CEEDAYS-MAIN.
           MOVE WS-CEEDAYS-RC-OVERRIDE
                                  TO LK-FC-TOKEN-VALUE
           MOVE WS-CEEDAYS-LILLIAN-OVERRIDE
                                  TO LK-OUTPUT-LILLIAN
           MOVE 0                 TO LK-FC-I-S-INFO
           GOBACK.
      *
      * End of CEEDAYS stub.
      *
