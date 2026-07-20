//APITSTR  JOB 'Run CardDemo API drivers',CLASS=A,MSGCLASS=H,
//             MSGLEVEL=(1,1),REGION=0M,NOTIFY=&SYSUID,TIME=1440
//*********************************************************************
//* Copyright Amazon.com, Inc. or its affiliates.
//* All Rights Reserved.
//*
//* Licensed under the Apache License, Version 2.0 (the "License").
//* You may not use this file except in compliance with the License.
//* You may obtain a copy of the License at
//*
//*    http://www.apache.org/licenses/LICENSE-2.0
//*
//* Unless required by applicable law or agreed to in writing,
//* software distributed under the License is distributed on an
//* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
//* either express or implied. See the License for the specific
//* language governing permissions and limitations under the License.
//*********************************************************************
//*  API DRIVER RUNNER (MAJ-07) - build then RUN the batch EXCI runner
//*  TSTRUNR, which invokes all six API test drivers (TSTAUTH, TSTACCT,
//*  TSTCUST, TSTCARD, TSTXREF, TSTTRAN) in the CICS region over EXCI
//*  and gates release on their machine-readable COMMAREA verdicts:
//*
//*    JOB max RC = 0  every driver PASSED         -> release OK
//*    JOB max RC = 4  at least one SKIP/incomplete -> investigate
//*    JOB max RC = 8  at least one FAIL or ERROR   -> release BLOCKED
//*
//*  Prerequisites (one-time, per docs/onboarding-api.md):
//*   - The six drivers are built and NEWCOPY'd (app/jcl/APITSTB.jcl)
//*     and their PROGRAM defs installed with group CDEMOAPI
//*     (app/jcl/APICSDIN.jcl).
//*   - An EXCI connection (generic or specific) to the target region
//*     is installed, and the batch DFHXCOPT options module is
//*     available.  The target region is chosen by the EXCI
//*     connection / DFHXCURM, NOT by the runner source.
//*
//*  Runtime note: this job requires a live IBM CICS region with EXCI
//*  enabled; it cannot execute in the Linux CI sandbox (documented
//*  environment limitation).  Rationale: docs/decision-log.md (D37).
//*********************************************************************
//*  Site DSNs - confirm against your installation (these mirror the
//*  placeholder style already used in app/jcl/APICSDIN.jcl).
//*********************************************************************
//         SET HLQ=AWS.M2
//         SET COBLIB=IGY.SIGYCOMP.V63
//         SET DFHLOAD=OEM.CICSTS.V05R06M0.CICS.SDFHLOAD
//         SET DFHCOB=OEM.CICSTS.V05R06M0.CICS.SDFHCOB
//         SET DFHEXCI=OEM.CICSTS.V05R06M0.CICS.SDFHEXCI
//         SET CPYBKS=&HLQ..CARDDEMO.CPY
//         SET SOURCE=&HLQ..CARDDEMO.CBL
//         SET LOADLIB=&HLQ..CARDDEMO.LOADLIB
//*********************************************************************
//*  STEP 1 - TRANSLATE the EXCI client with the standalone CICS
//*  translator using the EXCI option (an EXCI batch client must NOT
//*  use the online integrated translator).
//*********************************************************************
//TRN     EXEC PGM=DFHECP1$,REGION=0M,PARM='EXCI'
//STEPLIB  DD DSN=&DFHLOAD,DISP=SHR
//SYSPRINT DD SYSOUT=*
//SYSIN    DD DSN=&SOURCE(TSTRUNR),DISP=SHR
//SYSPUNCH DD DSN=&&TRANOUT,DISP=(NEW,PASS),
//            DCB=(LRECL=80,BLKSIZE=400,RECFM=FB),
//            UNIT=3390,SPACE=(400,(400,100))
//*********************************************************************
//*  STEP 2 - COMPILE the translated source (NODYNAM/RENT; no CICS
//*  compiler option here because STEP 1 already translated it).
//*********************************************************************
//COB     EXEC PGM=IGYCRCTL,REGION=0M,COND=(4,LT),
//            PARM='NODYNAM,RENT,LIB,NOSEQ'
//STEPLIB  DD DSN=&COBLIB,DISP=SHR
//SYSLIB   DD DSN=&CPYBKS,DISP=SHR
//         DD DSN=&DFHCOB,DISP=SHR
//         DD DSN=CEE.SCEESAMP,DISP=SHR
//SYSPRINT DD SYSOUT=*
//SYSIN    DD DSN=&&TRANOUT,DISP=(OLD,DELETE)
//SYSLIN   DD DSN=&&LOADSET,DISP=(MOD,PASS),
//            UNIT=3390,SPACE=(80,(250,100))
//SYSUT1   DD SPACE=(80,(10,10),,,ROUND),UNIT=3390
//SYSUT2   DD SPACE=(80,(10,10),,,ROUND),UNIT=3390
//SYSUT3   DD SPACE=(80,(10,10),,,ROUND),UNIT=3390
//SYSUT4   DD SPACE=(80,(10,10),,,ROUND),UNIT=3390
//SYSUT5   DD SPACE=(80,(10,10),,,ROUND),UNIT=3390
//SYSUT6   DD SPACE=(80,(10,10),,,ROUND),UNIT=3390
//SYSUT7   DD SPACE=(80,(10,10),,,ROUND),UNIT=3390
//*********************************************************************
//*  STEP 3 - LINK-EDIT with the EXCI stub (SDFHEXCI / DFHXCSTB) so
//*  the EXEC CICS LINK calls resolve to the external CICS interface.
//*********************************************************************
//LKED    EXEC PGM=HEWL,REGION=0M,COND=(4,LT),
//            PARM='LIST,XREF,LET,MAP,AMODE(31),RMODE(ANY)'
//SYSPRINT DD SYSOUT=*
//SYSLIB   DD DSN=&DFHEXCI,DISP=SHR
//         DD DSN=CEE.SCEELKED,DISP=SHR
//         DD DSN=&LOADLIB,DISP=SHR
//SYSLIN   DD DSN=&&LOADSET,DISP=(OLD,DELETE)
//         DD *
   INCLUDE SYSLIB(DFHXCSTB)
   NAME TSTRUNR(R)
/*
//SYSLMOD  DD DSN=&LOADLIB,DISP=SHR
//SYSUT1   DD UNIT=3390,DCB=BLKSIZE=1024,SPACE=(1024,(200,20))
//*********************************************************************
//*  STEP 4 - RUN the runner.  Its batch RETURN-CODE (0/4/8) becomes
//*  the step RC and therefore the JOB completion code that an
//*  automated release gate reads.  DFHXCOPT supplies the EXCI options
//*  (TIMEOUT, TRACE, etc.); a DUMMY DD accepts installation defaults.
//*********************************************************************
//RUN     EXEC PGM=TSTRUNR,REGION=0M,COND=(4,LT)
//STEPLIB  DD DSN=&LOADLIB,DISP=SHR
//         DD DSN=&DFHEXCI,DISP=SHR
//DFHXCOPT DD DUMMY
//SYSPRINT DD SYSOUT=*
//SYSOUT   DD SYSOUT=*
//CEEDUMP  DD SYSOUT=*
//SYSUDUMP DD SYSOUT=*
//*********************************************************************
//*  STEP 5 - GATE.  Make the release verdict unmistakable in the job
//*  log.  The authoritative machine-readable gate is RUN.RC above;
//*  these steps only annotate it.
//*********************************************************************
// IF (RUN.RC = 0) THEN
//GATEOK  EXEC PGM=IEFBR14
//* API DRIVER GATE: PASS - all six drivers reported PASS (RC=0).
// ENDIF
// IF (RUN.RC = 4) THEN
//GATESKP EXEC PGM=IEFBR14
//* API DRIVER GATE: INCOMPLETE - at least one driver SKIPPED (RC=4).
// ENDIF
// IF (RUN.RC GE 8) THEN
//GATEBAD EXEC PGM=IEFBR14
//* API DRIVER GATE: FAIL - at least one driver FAILED/ERRORED (RC=8).
// ENDIF
//
