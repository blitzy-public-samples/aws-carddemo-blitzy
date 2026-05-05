      ******************************************************************
      *****       DFHAID STUB - CICS AID + EIB OFF-PLATFORM SHIM ******
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
      *  Off-platform substitute for IBM CICS-supplied DFHAID copybook
      *  AND the implicit EIB area normally injected before LINKAGE
      *  by the CICS translator on z/OS.  This shim makes CardDemo's
      *  CICS programs compile under GnuCOBOL when no CICS translator
      *  is available.
      *
      *  Off-platform reality
      *  --------------------
      *  In real CICS, the translator injects DFHEIBLK definitions
      *  (containing EIBAID, EIBCALEN, EIBRESP, etc.) before LINKAGE
      *  SECTION automatically.  Off-platform GnuCOBOL has no such
      *  translator, so EIB fields would otherwise be undefined when
      *  programs reference them in WORKING-STORAGE-time tests.  We
      *  embed EIB fields here in the DFHAID copybook because
      *  COPY DFHAID is the earliest required injection point in
      *  every CICS program in app/cbl/CO*C.cbl.  Putting EIB fields
      *  in WORKING-STORAGE rather than LINKAGE is semantically
      *  unconventional but valid for off-platform test compilation
      *  -- production code uses CICS translator-managed memory
      *  layout that is irrelevant to test harness execution.
      *
      *  Real-world AID character codes
      *  ------------------------------
      *  The CICS AID byte values come from the IBM CICS Application
      *  Programming Reference and are documented as their EBCDIC
      *  character forms in the original DFHAID copybook (e.g.,
      *  DFHENTER = X"7D" which is QUOTE in EBCDIC).  Off-platform
      *  GnuCOBOL on Linux uses ASCII; we use the same logical
      *  characters because production code only compares EIBAID
      *  to these symbolic constants -- the actual byte values are
      *  irrelevant as long as DFHENTER, DFHPF3, etc. are distinct
      *  one-byte values.
      *
      *  Constraints
      *  -----------
      *  This file is INFRASTRUCTURE only.  No business logic, no
      *  arithmetic, no procedural code.  Pure data declarations,
      *  VALUE clauses, and 88-level conditional names where useful
      *  to mirror DFHAID's CICS-supplied 01-level group structure.
      *
      *  Consumers
      *  ---------
      *    app/cbl/COMEN01C.cbl      COPY DFHAID
      *    app/cbl/COADM01C.cbl      COPY DFHAID
      *    app/cbl/COACTVWC.cbl      COPY DFHAID
      *    app/cbl/COACTUPC.cbl      COPY DFHAID
      *    app/cbl/COBIL00C.cbl      COPY DFHAID
      *    app/cbl/COCRDLIC.cbl      COPY DFHAID
      *    app/cbl/COCRDSLC.cbl      COPY DFHAID
      *    app/cbl/COCRDUPC.cbl      COPY DFHAID
      *    app/cbl/CORPT00C.cbl      COPY DFHAID
      *    app/cbl/COSGN00C.cbl      COPY DFHAID
      *    app/cbl/COTRN00C.cbl      COPY DFHAID
      *    app/cbl/COTRN01C.cbl      COPY DFHAID
      *    app/cbl/COTRN02C.cbl      COPY DFHAID
      *    app/cbl/COUSR00C.cbl      COPY DFHAID
      *    app/cbl/COUSR01C.cbl      COPY DFHAID
      *    app/cbl/COUSR02C.cbl      COPY DFHAID
      *    app/cbl/COUSR03C.cbl      COPY DFHAID
      ******************************************************************
      *
      *  Standard CICS Attention Identifier (AID) constants.  Programs
      *  test EIBAID against these symbolic names in EVALUATE EIBAID /
      *  IF EIBAID = ... statements.  Each is a one-byte literal whose
      *  exact byte value does not matter for off-platform testing as
      *  long as the symbols are distinct and EIBAID can be MOVEd to
      *  match each one in turn from the test driver.
      *
       01  DFHAID.
           02  DFHNULL                     PIC X  VALUE ' '.
           02  DFHENTER                    PIC X  VALUE QUOTE.
           02  DFHCLEAR                    PIC X  VALUE '_'.
           02  DFHCLRP                     PIC X  VALUE 'A'.
           02  DFHPEN                      PIC X  VALUE '='.
           02  DFHOPID                     PIC X  VALUE 'W'.
           02  DFHMSRE                     PIC X  VALUE 'X'.
           02  DFHSTRF                     PIC X  VALUE 'h'.
           02  DFHTRIG                     PIC X  VALUE '"'.
           02  DFHPA1                      PIC X  VALUE '%'.
           02  DFHPA2                      PIC X  VALUE '>'.
           02  DFHPA3                      PIC X  VALUE ','.
           02  DFHPF1                      PIC X  VALUE '1'.
           02  DFHPF2                      PIC X  VALUE '2'.
           02  DFHPF3                      PIC X  VALUE '3'.
           02  DFHPF4                      PIC X  VALUE '4'.
           02  DFHPF5                      PIC X  VALUE '5'.
           02  DFHPF6                      PIC X  VALUE '6'.
           02  DFHPF7                      PIC X  VALUE '7'.
           02  DFHPF8                      PIC X  VALUE '8'.
           02  DFHPF9                      PIC X  VALUE '9'.
           02  DFHPF10                     PIC X  VALUE ':'.
           02  DFHPF11                     PIC X  VALUE '#'.
           02  DFHPF12                     PIC X  VALUE '@'.
           02  DFHPF13                     PIC X  VALUE 'B'.
           02  DFHPF14                     PIC X  VALUE 'C'.
           02  DFHPF15                     PIC X  VALUE 'D'.
           02  DFHPF16                     PIC X  VALUE 'E'.
           02  DFHPF17                     PIC X  VALUE 'F'.
           02  DFHPF18                     PIC X  VALUE 'G'.
           02  DFHPF19                     PIC X  VALUE 'H'.
           02  DFHPF20                     PIC X  VALUE 'I'.
           02  DFHPF21                     PIC X  VALUE 'J'.
           02  DFHPF22                     PIC X  VALUE 'K'.
           02  DFHPF23                     PIC X  VALUE 'L'.
           02  DFHPF24                     PIC X  VALUE 'M'.
      *
      *  Off-platform EIB area injected here because GnuCOBOL has no
      *  CICS translator to inject DFHEIBLK before LINKAGE SECTION.
      *  Production code references EIBAID, EIBCALEN, etc. directly
      *  (no LINKAGE-explicit declaration required, in real CICS).
      *  We declare these as plain WORKING-STORAGE fields so the
      *  test harness can MOVE values into them via BEFORE-EACH and
      *  the production paragraphs can read them in EVALUATE/IF
      *  expressions during testcase execution.
      *
      *  Plain WORKING-STORAGE (not EXTERNAL).  Each program-under-
      *  test compiled in isolation gets its own private EIB copy.
      *  EXTERNAL would require all modules sharing this copybook to
      *  declare identical VALUE clauses, which conflicts with how
      *  cobol-check injects per-module test driver fields.
      *
       01  DFHEIBLK-OFFPLATFORM-SHIM.
           02  EIBTIME                     PIC S9(7) COMP-3.
           02  EIBDATE                     PIC S9(7) COMP-3.
           02  EIBTRNID                    PIC X(4).
           02  EIBTASKN                    PIC S9(7) COMP-3.
           02  EIBTRMID                    PIC X(4).
           02  EIBCPOSN                    PIC S9(4) COMP.
           02  EIBCALEN                    PIC S9(4) COMP.
           02  EIBAID                      PIC X(1).
           02  EIBFN                       PIC X(2).
           02  EIBRCODE                    PIC X(6).
           02  EIBDS                       PIC X(8).
           02  EIBREQID                    PIC X(8).
           02  EIBRSRCE                    PIC X(8).
           02  EIBSYNC                     PIC X(1).
           02  EIBFREE                     PIC X(1).
           02  EIBRECV                     PIC X(1).
           02  EIBSEND                     PIC X(1).
           02  EIBATT                      PIC X(1).
           02  EIBEOC                      PIC X(1).
           02  EIBFMH                      PIC X(1).
           02  EIBCOMPL                    PIC X(1).
           02  EIBSIG                      PIC X(1).
           02  EIBCONF                     PIC X(1).
           02  EIBERR                      PIC X(1).
           02  EIBERRCD                    PIC X(4).
           02  EIBSYNRB                    PIC X(1).
           02  EIBNODAT                    PIC X(1).
           02  EIBRESP                     PIC S9(8) COMP.
           02  EIBRESP2                    PIC S9(8) COMP.
      *
      * End of DFHAID off-platform shim copybook.
      *
