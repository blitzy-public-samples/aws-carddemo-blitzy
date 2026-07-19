//APITSTB  JOB 'Build CardDemo API drivers',CLASS=A,MSGCLASS=H,
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
//*  QA DRIVER BUILD - compile & link-edit the six CardDemo API test
//*  drivers into the CICS LOADLIB, then NEWCOPY each so a running
//*  region loads them. These EXEC CICS LINK drivers exercise the API
//*  service programs in-region (the programmatic counterpart to the
//*  run-api-tests.sh HTTP harness). Their PROGRAM/TRANSACTION defs
//*  install via app/jcl/APICSDIN.jcl (group CDEMOAPI). QA/test only -
//*  not required by the production API path. Modeled on APIBUILD.jcl.
//*********************************************************************
//*  Set parms for this build:
//*********************************************************************
//   SET HLQ=AWS.M2
//*********************************************************************
//*  Add proclib reference (BUILDONL / BLDONL lives here)
//*********************************************************************
//CCLIBS  JCLLIB ORDER=&HLQ..CARDDEMO.PRC.UTIL
//*********************************************************************
//*  Compile & link each driver (one BUILDONL step per member). LET is
//*  dropped via PARM.LKED so an unresolved external fails RC>=8 rather
//*  than being masked; COND=(4,LT) stops the build after a hard
//*  failure. Drivers use EXEC CICS LINK (runtime-resolved), so a clean
//*  bind is RC=0. Rationale: docs/decision-log.md (D13, D18).
//BLDAUTH EXEC BUILDONL,MEM=TSTAUTH,HLQ=&HLQ,
//         PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//BLDACCT EXEC BUILDONL,MEM=TSTACCT,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//BLDCUST EXEC BUILDONL,MEM=TSTCUST,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//BLDCARD EXEC BUILDONL,MEM=TSTCARD,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//BLDXREF EXEC BUILDONL,MEM=TSTXREF,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//BLDTRAN EXEC BUILDONL,MEM=TSTTRAN,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//*********************************************************************
//*  Issue CICS NEWCOPY for each driver via SDSF batch /MODIFY (region
//*  CICSAWSA). COND=(4,LT) skips NEWCOPY on any hard build failure;
//*  a clean driver bind is RC=0 so NEWCOPY runs.
//*********************************************************************
//NEWCOPY EXEC PGM=SDSF,COND=(4,LT)
//ISFOUT DD SYSOUT=*
//CMDOUT DD SYSOUT=*
//ISFIN  DD *
 /MODIFY CICSAWSA,'CEMT SET PROG(TSTAUTH) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(TSTACCT) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(TSTCUST) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(TSTCARD) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(TSTXREF) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(TSTTRAN) NEWCOPY'
/*
