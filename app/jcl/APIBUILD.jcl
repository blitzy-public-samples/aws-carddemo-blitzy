//APIBUILD JOB 'Compile CardDemo API',CLASS=A,MSGCLASS=H,
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
//*  Compile & link-edit the CardDemo REST/JSON API programs into
//*  the CICS LOADLIB, then NEWCOPY each so a running region loads
//*  the new modules. Modeled on samples/jcl/CICCMP.jcl and the
//*  samples/proc/BUILDONL.prc (BLDONL) proc. Additive/read-only.
//*********************************************************************
//*  Set parms for this build:
//*********************************************************************
//   SET HLQ=AWS.M2
//*********************************************************************
//*  Add proclib reference (BUILDONL / BLDONL lives here)
//*********************************************************************
//CCLIBS  JCLLIB ORDER=&HLQ..CARDDEMO.PRC.UTIL
//*********************************************************************
//*  Compile & link each API program (one BUILDONL step per member)
//*********************************************************************
//*  PARM.LKED overrides the BUILDONL binder PARM to drop LET so an
//*  unresolved external (e.g. COJSONUC under NODYNAM) fails RC>=8
//*  instead of being masked as RC=4. COND=(4,LT) on the dependent
//*  steps stops the build after any hard failure while admitting the
//*  benign RC=4 (duplicate DFHEILID). See docs/decision-log.md (D13).
//CMPJSN  EXEC BUILDONL,MEM=COJSONUC,HLQ=&HLQ,
//         PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//CMPSEC  EXEC BUILDONL,MEM=COAPISEC,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//CMPACS  EXEC BUILDONL,MEM=COACSVCC,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//CMPCUS  EXEC BUILDONL,MEM=COCUSVCC,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//CMPCRS  EXEC BUILDONL,MEM=COCRSVCC,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//CMPXRS  EXEC BUILDONL,MEM=COXRSVCC,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//CMPTRS  EXEC BUILDONL,MEM=COTRSVCC,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//CMPRTR  EXEC BUILDONL,MEM=COAPIRTR,HLQ=&HLQ,
//         COND=(4,LT),PARM.LKED='LIST,XREF,MAP,AMODE(31),RMODE(ANY)'
//*********************************************************************
//*  Issue CICS NEWCOPY for each new program via SDSF batch /MODIFY
//*  (region CICSAWSA, matching the sample). With LET removed above,
//*  an unresolved COJSONUC binds RC>=8; a clean COAPIRTR bind still
//*  returns a benign RC=4 (duplicate DFHEILID via autocall), so
//*  COND=(4,LT) admits a good build yet skips NEWCOPY on RC>=8.
//*  Rationale/alternatives/risk: docs/decision-log.md (D13).
//*********************************************************************
//NEWCOPY EXEC PGM=SDSF,COND=(4,LT)
//ISFOUT DD SYSOUT=*
//CMDOUT DD SYSOUT=*
//ISFIN  DD *
 /MODIFY CICSAWSA,'CEMT SET PROG(COJSONUC) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(COAPISEC) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(COACSVCC) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(COCUSVCC) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(COCRSVCC) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(COXRSVCC) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(COTRSVCC) NEWCOPY'
 /MODIFY CICSAWSA,'CEMT SET PROG(COAPIRTR) NEWCOPY'
/*
