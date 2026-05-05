      ******************************************************************
      ****  DFHBMSCA STUB - BMS ATTRIBUTE + DFHRESP OFF-PLATFORM SHIM **
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
      *  Off-platform substitute for IBM CICS-supplied DFHBMSCA copybook
      *  PLUS a substitute for the CICS DFHRESP() preprocessor macro.
      *  This shim makes CardDemo's CICS programs compile under
      *  GnuCOBOL when no CICS translator is available.
      *
      *  Two distinct constructs are defined here:
      *
      *  1. BMS (Basic Mapping Support) attribute constants used by
      *     production code to set screen field display attributes.
      *     Production code does MOVE DFHGREEN TO ERRMSGC OF MAP1AO,
      *     MOVE DFHRED TO PWDC OF SIGNONO, etc.  Real CICS supplies
      *     these as 88-level conditional names embedded in DFHBMSCA;
      *     here we declare equivalent one-byte literals.  The exact
      *     byte values do not matter for off-platform testing because
      *     they are never sent to a real terminal -- they only appear
      *     in test EXPECT clauses if the testsuite chooses to assert
      *     on them.
      *
      *  2. DFHRESP table.  In real CICS, DFHRESP(NORMAL),
      *     DFHRESP(NOTFND), etc. are PREPROCESSOR MACRO INVOCATIONS
      *     that the CICS translator expands to integer literals
      *     (NORMAL=0, NOTFND=13, DUPREC=14, DUPKEY=15, ENDFILE=20).
      *     Off-platform GnuCOBOL has no preprocessor; production
      *     source contains expressions like
      *         WHEN DFHRESP(NORMAL)
      *         IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
      *     which are syntactically OCCURS-table subscripted accesses
      *     in standard COBOL.  We define DFHRESP as an OCCURS table
      *     of integers and the standard CICS RESP names (NORMAL,
      *     NOTFND, DUPREC, DUPKEY, ENDFILE) as the 1-based subscript
      *     constants pointing at the entry whose value equals the
      *     real-CICS RESP code.
      *
      *  Production verification
      *  -----------------------
      *  $ grep -hoE "DFHRESP\\([A-Z]+\\)" app/cbl/CO*C.cbl
      *      DFHRESP(DUPKEY)     ->  CICS RESP code 15
      *      DFHRESP(DUPREC)     ->  CICS RESP code 14
      *      DFHRESP(ENDFILE)    ->  CICS RESP code 20
      *      DFHRESP(NORMAL)     ->  CICS RESP code  0
      *      DFHRESP(NOTFND)     ->  CICS RESP code 13
      *
      *  Symbol-collision audit
      *  ----------------------
      *  Production references to NORMAL, NOTFND, DUPREC, DUPKEY,
      *  ENDFILE outside DFHRESP() context have been audited and
      *  found to be ZERO (verified by grep across all in-scope CBL
      *  files), so declaring them as numeric subscripts here causes
      *  no name conflict with production identifiers.
      *
      *  Constraints
      *  -----------
      *  This file is INFRASTRUCTURE only.  No business logic, no
      *  arithmetic, no procedural code.  Pure data declarations
      *  with VALUE clauses establishing safe defaults.
      *
      *  Consumers
      *  ---------
      *    app/cbl/COMEN01C.cbl      COPY DFHBMSCA  (BMS only)
      *    app/cbl/COADM01C.cbl      COPY DFHBMSCA  (BMS only)
      *    app/cbl/COACTVWC.cbl      COPY DFHBMSCA  (BMS + DFHRESP)
      *    app/cbl/COACTUPC.cbl      COPY DFHBMSCA  (BMS + DFHRESP)
      *    app/cbl/COBIL00C.cbl      COPY DFHBMSCA
      *    app/cbl/COCRDLIC.cbl      COPY DFHBMSCA
      *    app/cbl/COCRDSLC.cbl      COPY DFHBMSCA
      *    app/cbl/COCRDUPC.cbl      COPY DFHBMSCA
      *    app/cbl/CORPT00C.cbl      COPY DFHBMSCA
      *    app/cbl/COSGN00C.cbl      COPY DFHBMSCA
      *    app/cbl/COTRN00C.cbl      COPY DFHBMSCA
      *    app/cbl/COTRN01C.cbl      COPY DFHBMSCA
      *    app/cbl/COTRN02C.cbl      COPY DFHBMSCA
      *    app/cbl/COUSR00C.cbl      COPY DFHBMSCA
      *    app/cbl/COUSR01C.cbl      COPY DFHBMSCA
      *    app/cbl/COUSR02C.cbl      COPY DFHBMSCA
      *    app/cbl/COUSR03C.cbl      COPY DFHBMSCA
      ******************************************************************
      *
      *  BMS attribute constants.  Production code sets these on
      *  screen field colour-attribute (color of message, prompt) or
      *  modify-data tag (MDT) fields to control how a 3270 terminal
      *  renders the field.  Off-platform these are pure
      *  byte-comparison targets in test assertions.
      *
       01  DFHBMSCA.
           02  DFHBMPEM                    PIC X  VALUE '"'.
           02  DFHBMPNL                    PIC X  VALUE '@'.
           02  DFHBMPFF                    PIC X  VALUE '<'.
           02  DFHBMPCR                    PIC X  VALUE '_'.
           02  DFHBMASK                    PIC X  VALUE '0'.
           02  DFHBMUNP                    PIC X  VALUE ' '.
           02  DFHBMUNN                    PIC X  VALUE '&'.
           02  DFHBMPRO                    PIC X  VALUE '-'.
           02  DFHBMBRY                    PIC X  VALUE '('.
           02  DFHBMDAR                    PIC X  VALUE '<'.
           02  DFHBMFSE                    PIC X  VALUE 'A'.
           02  DFHBMPRF                    PIC X  VALUE '/'.
           02  DFHBMASF                    PIC X  VALUE '1'.
           02  DFHBMASB                    PIC X  VALUE '8'.
           02  DFHBMEOF                    PIC X  VALUE '"'.
           02  DFHBMDET                    PIC X  VALUE 'P'.
      *
      *  Default colour and attribute (used when production code
      *  resets a field with MOVE DFHDFCOL TO ... or MOVE DFHATTR).
      *
           02  DFHDFCOL                    PIC X  VALUE LOW-VALUES.
           02  DFHATTR                     PIC X  VALUE LOW-VALUES.
      *
      *  BMS extended-attribute COLOUR constants (program writes
      *  these to xxxC fields like ERRMSGC, PWDC, etc.).
      *
           02  DFHDFT                      PIC X  VALUE LOW-VALUES.
           02  DFHBLUE                     PIC X  VALUE '1'.
           02  DFHRED                      PIC X  VALUE '2'.
           02  DFHPINK                     PIC X  VALUE '3'.
           02  DFHGREEN                    PIC X  VALUE '4'.
           02  DFHTURQ                     PIC X  VALUE '5'.
           02  DFHYELLO                    PIC X  VALUE '6'.
           02  DFHNEUTR                    PIC X  VALUE '7'.
      *
      *  BMS highlight constants.
      *
           02  DFHBLINK                    PIC X  VALUE '1'.
           02  DFHREVRS                    PIC X  VALUE '2'.
           02  DFHUNDLN                    PIC X  VALUE '4'.
      *
      *  BMS programmed-symbol identifiers.
      *
           02  DFHPS                       PIC X  VALUE LOW-VALUES.
      *
      *  DFHRESP off-platform substitute.
      *  --------------------------------
      *  Real CICS DFHRESP() is a translator macro.  Off-platform we
      *  declare DFHRESP as a 30-element OCCURS table whose values
      *  match the real CICS RESP codes (0 .. 29).  Standard CICS
      *  response keyword names (NORMAL, NOTFND, DUPREC, DUPKEY,
      *  ENDFILE) are declared as numeric 1-based subscripts pointing
      *  at the entry whose value equals the real RESP code.
      *
      *  Result: production expressions like
      *      WHEN DFHRESP(NORMAL)
      *      IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
      *  compile and evaluate exactly as on z/OS:
      *      DFHRESP(NORMAL)  -> DFHRESP(1)  -> 0
      *      DFHRESP(NOTFND)  -> DFHRESP(14) -> 13
      *      DFHRESP(DUPREC)  -> DFHRESP(15) -> 14
      *      DFHRESP(DUPKEY)  -> DFHRESP(16) -> 15
      *      DFHRESP(ENDFILE) -> DFHRESP(21) -> 20
      *
       01  DFHRESP-VALUES.
           02  FILLER  PIC S9(8) COMP VALUE 0.
           02  FILLER  PIC S9(8) COMP VALUE 1.
           02  FILLER  PIC S9(8) COMP VALUE 2.
           02  FILLER  PIC S9(8) COMP VALUE 3.
           02  FILLER  PIC S9(8) COMP VALUE 4.
           02  FILLER  PIC S9(8) COMP VALUE 5.
           02  FILLER  PIC S9(8) COMP VALUE 6.
           02  FILLER  PIC S9(8) COMP VALUE 7.
           02  FILLER  PIC S9(8) COMP VALUE 8.
           02  FILLER  PIC S9(8) COMP VALUE 9.
           02  FILLER  PIC S9(8) COMP VALUE 10.
           02  FILLER  PIC S9(8) COMP VALUE 11.
           02  FILLER  PIC S9(8) COMP VALUE 12.
           02  FILLER  PIC S9(8) COMP VALUE 13.
           02  FILLER  PIC S9(8) COMP VALUE 14.
           02  FILLER  PIC S9(8) COMP VALUE 15.
           02  FILLER  PIC S9(8) COMP VALUE 16.
           02  FILLER  PIC S9(8) COMP VALUE 17.
           02  FILLER  PIC S9(8) COMP VALUE 18.
           02  FILLER  PIC S9(8) COMP VALUE 19.
           02  FILLER  PIC S9(8) COMP VALUE 20.
           02  FILLER  PIC S9(8) COMP VALUE 21.
           02  FILLER  PIC S9(8) COMP VALUE 22.
           02  FILLER  PIC S9(8) COMP VALUE 23.
           02  FILLER  PIC S9(8) COMP VALUE 24.
           02  FILLER  PIC S9(8) COMP VALUE 25.
           02  FILLER  PIC S9(8) COMP VALUE 26.
           02  FILLER  PIC S9(8) COMP VALUE 27.
           02  FILLER  PIC S9(8) COMP VALUE 28.
           02  FILLER  PIC S9(8) COMP VALUE 29.
       01  DFHRESP-TABLE REDEFINES DFHRESP-VALUES.
           02  DFHRESP                     OCCURS 30 TIMES
                                           PIC S9(8) COMP.
      *
      *  CICS RESP keyword names declared as 1-based table subscripts.
      *  Each constant's VALUE is (real-RESP-code + 1) so that
      *  DFHRESP(KEYWORD) yields the original real CICS RESP code
      *  via the OCCURS table indirection above.
      *
       01  DFHRESP-KEYWORDS.
           02  NORMAL                      PIC S9(8) COMP   VALUE 1.
           02  ERRORW                      PIC S9(8) COMP   VALUE 2.
           02  RDATT                       PIC S9(8) COMP   VALUE 3.
           02  WRBRK                       PIC S9(8) COMP   VALUE 4.
           02  EOF                         PIC S9(8) COMP   VALUE 5.
           02  EODS                        PIC S9(8) COMP   VALUE 6.
           02  EOC                         PIC S9(8) COMP   VALUE 7.
           02  INBFMH                      PIC S9(8) COMP   VALUE 8.
           02  ENDINPT                     PIC S9(8) COMP   VALUE 9.
           02  NONVAL                      PIC S9(8) COMP   VALUE 10.
           02  NOSTART                     PIC S9(8) COMP   VALUE 11.
           02  TERMIDERR                   PIC S9(8) COMP   VALUE 12.
           02  FILENOTFOUND                PIC S9(8) COMP   VALUE 13.
           02  NOTFND                      PIC S9(8) COMP   VALUE 14.
           02  DUPREC                      PIC S9(8) COMP   VALUE 15.
           02  DUPKEY                      PIC S9(8) COMP   VALUE 16.
           02  INVREQ                      PIC S9(8) COMP   VALUE 17.
           02  IOERR                       PIC S9(8) COMP   VALUE 18.
           02  NOSPACE                     PIC S9(8) COMP   VALUE 19.
           02  NOTOPEN                     PIC S9(8) COMP   VALUE 20.
           02  ENDFILE                     PIC S9(8) COMP   VALUE 21.
           02  ILLOGIC                     PIC S9(8) COMP   VALUE 22.
           02  LENGERR                     PIC S9(8) COMP   VALUE 23.
           02  QZERO                       PIC S9(8) COMP   VALUE 24.
           02  SIGNAL                      PIC S9(8) COMP   VALUE 25.
           02  QBUSY                       PIC S9(8) COMP   VALUE 26.
           02  ITEMERR                     PIC S9(8) COMP   VALUE 27.
           02  NOTAUTH                     PIC S9(8) COMP   VALUE 28.
           02  PGMIDERR                    PIC S9(8) COMP   VALUE 29.
           02  TRANSIDERR                  PIC S9(8) COMP   VALUE 30.
      *
      * End of DFHBMSCA off-platform shim copybook.
      *
