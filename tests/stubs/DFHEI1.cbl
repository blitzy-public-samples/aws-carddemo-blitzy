      ******************************************************************
      *  DFHEI1 - off-platform stub for the CICS Application Programming*
      *  Interface entry point.  When CardDemo's online programs        *
      *  (CO*C.cbl) are translated by IBM's DFHECP1$ precompiler, every *
      *  EXEC CICS verb is replaced with a CALL to DFHEI1 supplying a   *
      *  fixed-format parameter block.  GnuCOBOL has no precompiler, so *
      *  this stub provides a link-time stand-in.                       *
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
      *  Provide a no-op entry point named DFHEI1 so that any link-time *
      *  reference to CICS resolves without error.  At test time        *
      *  cobol-check intercepts every EXEC CICS verb with               *
      *  `MOCK CICS <command> END-MOCK`, so this stub is rarely         *
      *  invoked.  In the situation where a test forgets to declare a   *
      *  mock for a particular EXEC CICS verb, this stub returns a      *
      *  non-zero EIBRESP (NOTAUTH=70) so the failure is loud.          *
      *                                                                 *
      *  This file is INFRASTRUCTURE only; it contains no business      *
      *  logic.                                                         *
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DFHEI1.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-CICS-CALL-COUNT     PIC 9(09) VALUE ZERO.
       LINKAGE SECTION.
       01 LK-DUMMY               PIC X(01).
       PROCEDURE DIVISION.
           ADD 1 TO WS-CICS-CALL-COUNT
           GOBACK.
