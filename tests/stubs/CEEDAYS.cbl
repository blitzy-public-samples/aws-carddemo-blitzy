      ******************************************************************
      *  CEEDAYS - off-platform stub for the IBM Language Environment   *
      *  Lillian-day-conversion service called from CSUTLDTC.cbl.       *
      *                                                                 *
      *  Copyright Amazon.com, Inc. or its affiliates.                  *
      *  All Rights Reserved.                                           *
      *                                                                 *
      *  Licensed under the Apache License, Version 2.0 (the "License").*
      *  You may not use this file except in compliance with the        *
      *  License. You may obtain a copy of the License at               *
      *      http://www.apache.org/licenses/LICENSE-2.0                 *
      *  Unless required by applicable law or agreed to in writing,     *
      *  software distributed under the License is distributed on an    *
      *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,   *
      *  either express or implied. See the License for the specific    *
      *  language governing permissions and limitations under the       *
      *  License.                                                       *
      *                                                                 *
      *  Purpose                                                        *
      *  -------                                                        *
      *  Provide a deterministic, side-effect-free implementation of    *
      *  the Language Environment service                               *
      *      CALL "CEEDAYS" USING DATE-VALUE DATE-FORMAT                *
      *                          OUTPUT-LILLIAN FEEDBACK-CODE.          *
      *                                                                 *
      *  This file is INFRASTRUCTURE only; it contains no business      *
      *  logic.  It exists so that an off-platform GnuCOBOL build of    *
      *  CSUTLDTC can link.  At test time cobol-check intercepts the    *
      *  CALL via `MOCK CALL "CEEDAYS"` and never executes this code.   *
      *  In the rare situation the test author forgets to mock the      *
      *  call, this stub returns FEEDBACK-CODE=0 so the program keeps   *
      *  running and the assertion failure is visible to the reviewer.  *
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEEDAYS.
       DATA DIVISION.
       LINKAGE SECTION.
       01 LK-DATE-VALUE.
          02 LK-DV-LEN          PIC S9(4) BINARY.
          02 LK-DV-TEXT.
             03 LK-DV-CHAR      PIC X
                                OCCURS 0 TO 256 TIMES
                                DEPENDING ON LK-DV-LEN
                                IN LK-DATE-VALUE.
       01 LK-DATE-FORMAT.
          02 LK-DF-LEN          PIC S9(4) BINARY.
          02 LK-DF-TEXT.
             03 LK-DF-CHAR      PIC X
                                OCCURS 0 TO 256 TIMES
                                DEPENDING ON LK-DF-LEN
                                IN LK-DATE-FORMAT.
       01 LK-OUTPUT-LILLIAN     PIC S9(9) BINARY.
       01 LK-FEEDBACK.
          02 LK-FB-SEVERITY     PIC S9(4) BINARY.
          02 LK-FB-MSG-NO       PIC S9(4) BINARY.
          02 LK-FB-FACILITY     PIC X(03).
          02 LK-FB-IS-ISI       PIC X(04).
       PROCEDURE DIVISION USING LK-DATE-VALUE
                                LK-DATE-FORMAT
                                LK-OUTPUT-LILLIAN
                                LK-FEEDBACK.
           MOVE ZERO          TO LK-OUTPUT-LILLIAN
           MOVE ZERO          TO LK-FB-SEVERITY
           MOVE ZERO          TO LK-FB-MSG-NO
           MOVE 'CEE'         TO LK-FB-FACILITY
           MOVE LOW-VALUES    TO LK-FB-IS-ISI
           GOBACK.
