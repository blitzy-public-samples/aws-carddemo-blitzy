      ******************************************************************
      *  CEE3ABD - off-platform stub for the IBM Language Environment   *
      *  abnormal termination service called from every CardDemo batch *
      *  program's 9999-ABEND-PROGRAM paragraph.                        *
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
      *  Replace the LE service                                         *
      *      CALL "CEE3ABD" USING ABCODE CLEANUP-CODE.                  *
      *                                                                 *
      *  CEE3ABD's production behavior is to terminate the address      *
      *  space with the supplied abend code.  In a unit-test context    *
      *  that would tear down the entire test process, so this stub     *
      *  simply records the abend by setting an external flag and       *
      *  returns to the caller.  Tests assert against the flag with     *
      *      EXPECT WS-ABEND-FLAG TO BE 'Y'                             *
      *  to verify that the abend pathway was reached.                  *
      *                                                                 *
      *  This file is INFRASTRUCTURE only; it contains no business      *
      *  logic.  cobol-check additionally redirects every CALL          *
      *  "CEE3ABD" through `MOCK CALL "CEE3ABD"` so this stub is        *
      *  rarely executed.                                               *
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-LAST-ABEND.
          02 WS-ABEND-FLAG       PIC X(01)  VALUE 'N'.
             88 WS-ABEND-OCCURRED        VALUE 'Y'.
             88 WS-NO-ABEND              VALUE 'N'.
          02 WS-ABEND-COUNT      PIC 9(05)  VALUE ZERO.
       LINKAGE SECTION.
       01 LK-ABCODE              PIC S9(9) BINARY.
       01 LK-CLEANUP-CODE        PIC S9(9) BINARY.
       PROCEDURE DIVISION USING LK-ABCODE
                                LK-CLEANUP-CODE.
           SET WS-ABEND-OCCURRED TO TRUE
           ADD 1 TO WS-ABEND-COUNT
           GOBACK.
