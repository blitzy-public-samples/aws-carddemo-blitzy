//APICSDRB JOB 'Rollback CardDemo API CSD',CLASS=A,MSGCLASS=H,
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
//*  ROLLBACK / UNINSTALL of the CardDemo REST/JSON API resource group
//*  CDEMOAPI. STEP 1 (batch, this job) removes the group from the
//*  startup list and deletes its CSD definitions. STEP 2 (operator
//*  action, documented below) discards the installed resources from a
//*  running region. Reverses app/jcl/APICSDIN.jcl. Additive rollback:
//*  it touches ONLY the CDEMOAPI group and never the CARDDEMO group.
//*  Rationale/alternatives/risk: docs/decision-log.md (D19).
//*********************************************************************
//*  Environment parameters (match app/jcl/APICSDIN.jcl):
//*   GRPLIST  - startup group list CDEMOAPI was added to at install.
//*   SDFHLOAD - CICS SDFHLOAD load library (for DFHCSDUP).
//*   DFHCSD   - the region CSD dataset to update.
//*********************************************************************
//   SET GRPLIST=DFHLIST
//   SET SDFHLOAD=OEM.CICSTS.V05R06M0.CICS.SDFHLOAD
//   SET DFHCSD=OEM.CICSTS.DFHCSD
//*********************************************************************
//*  STEP 1 - DFHCSDUP: unlink CDEMOAPI from &GRPLIST, then DELETE the
//*  group. Idempotent: if the group or list link is already gone,
//*  DFHCSDUP returns "not found" (RC=4) which is expected and
//*  harmless on a repeat run.
//*********************************************************************
//DELGRP  EXEC PGM=DFHCSDUP,REGION=0M,
//         PARM='CSD(READWRITE),PAGESIZE(60),NOCOMPAT'
//STEPLIB  DD DSN=&SDFHLOAD,DISP=SHR
//DFHCSD   DD UNIT=SYSDA,DISP=SHR,DSN=&DFHCSD
//OUTDD    DD SYSOUT=*
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *,SYMBOLS=JCLONLY
* Remove the group from the region startup list first, then delete the
* group's resource definitions from the CSD.
 REMOVE GROUP(CDEMOAPI) LIST(&GRPLIST)
 DELETE GROUP(CDEMOAPI)
 LIST GROUP(CDEMOAPI)
/*
//*********************************************************************
//*  STEP 2 - ONLINE DISCARD (operator action - no reliable batch path)
//*  DFHCSDUP edits only the CSD; it does NOT remove resources already
//*  installed in a running region. Complete the rollback by one of:
//*   1. Restart pickup (recommended) - STEP 1 removed CDEMOAPI from
//*      &GRPLIST, so a normal region restart comes up WITHOUT the
//*      group; no further action is required.
//*   2. Immediate discard in a running region - from an authorized
//*      CICS terminal, close the listener then discard the resources:
//*        CEMT SET TCPIPSERVICE(CDAPISVC) CLOSED
//*        CEMT DISCARD TCPIPSERVICE(CDAPISVC) URIMAP(CDAPIURI)
//*        CEMT DISCARD TRANSACTION(CAPI)
//*        CEMT DISCARD PROGRAM(COAPIRTR) ...(remaining API programs)
//*        CEMT DISCARD TSMODEL(CDAPITSM)
//*      Driver resources (TAUT/TACC/TCUS/TCRD/TXRF/TTRN and their
//*      TST* programs) are discarded the same way if installed.
//*********************************************************************
//*  FILE RESTORATION: none required. Group CDEMOAPI defines NO FILE
//*  resources (the CARDDEMO files are shared, never redefined), so
//*  this rollback cannot have altered any CARDDEMO FILE. If a legacy
//*  pre-remediation install had redefined those files, restore the
//*  original write-capable definitions by reinstalling the base
//*  group:  CEDA INSTALL GROUP(CARDDEMO)  from an authorized terminal.
//*  See docs/onboarding-api.md (Rollback).
//*********************************************************************
