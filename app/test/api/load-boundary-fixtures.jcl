//APIBFXL  JOB 'Load API boundary fixtures',CLASS=A,MSGCLASS=0,
// NOTIFY=&SYSUID
//******************************************************************
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
//* language governing permissions and limitations under the License
//******************************************************************
//* PURPOSE (QA finding MAJ-08): load a deterministic API boundary /
//* fault fixture set into DISPOSABLE CLONE VSAM datasets so a clean
//* region can reproduce every documented boundary and fault state
//* for GET /carddemo/api/v1/accounts/{acctId}/transactions without
//* tribal knowledge. This job NEVER touches the production CardDemo
//* VSAM files: every dataset below carries the APIBT clone qualifier.
//*
//* PREREQUISITES (run from a non-production, disposable region):
//*  1. Generate the flat fixtures on any workstation:
//*        python3 app/test/api/gen-boundary-fixtures.py \
//*                --out-dir app/test/api/fixtures
//*     then verify counts + SHA-256 against the committed manifest:
//*        python3 app/test/api/gen-boundary-fixtures.py --verify
//*  2. Upload the chosen scenario's flat files into the staging PS
//*     datasets named below, one file type per PS, using these fixed
//*     record lengths (space-padded on upload):
//*        CARDDATA PS  LRECL=150   (*.carddat.txt)
//*        CARDXREF PS  LRECL=50    (*.ccxref.txt is 36 significant
//*                                  bytes; pad to 50 on upload, the
//*                                  same convention base XREFFILE.jcl
//*                                  uses for cardxref.txt)
//*        TRANSACT PS  LRECL=350   (*.transact.txt; omit for the two
//*                                  card-only scenarios)
//*  3. Record which scenario is staged in the SCN symbol below (for
//*     the job log only); it does not select files automatically.
//*
//* SCENARIO -> files to stage (see app/test/api/fixtures/MANIFEST.txt
//* for record counts, byte sizes, SHA-256, and expected API outcome):
//*   CARDBND  card-boundary-50.{carddat,ccxref}.txt  (50 cards -> OK)
//*   CARDOVR  card-overflow-51.{carddat,ccxref}.txt  (51 cards -> 500)
//*   TRANBND  tran-boundary-50.{carddat,ccxref,transact}.txt
//*               (50 matches -> 200, truncated=false)
//*   TRANTRN  tran-truncate-51.{carddat,ccxref,transact}.txt
//*               (51 matches -> 200, truncated=true)
//*
//* CLEANUP: run app/test/api/clean-boundary-fixtures.jcl (or re-run
//* this job; STEP10 DELETEs the clones first, so it is idempotent).
//******************************************************************
// SET SCN=CARDBND
// SET HLQ=AWS.M2.CARDDEMO.APIBT
//*
//* *******************************************************************
//* STEP10 - DELETE clone clusters if they already exist (idempotent)
//* *******************************************************************
//STEP10   EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
   DELETE &HLQ..CARDDATA.VSAM.KSDS CLUSTER
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDDATA.VSAM.AIX ALTERNATEINDEX
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDXREF.VSAM.KSDS CLUSTER
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDXREF.VSAM.AIX ALTERNATEINDEX
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..TRANSACT.VSAM.KSDS CLUSTER
   IF MAXCC LE 08 THEN SET MAXCC = 0
/*
//*
//* *******************************************************************
//* STEP20 - DEFINE clone clusters (sizes mirror the base load JCL:
//*          CARDDATA 150/KEYS(16 0); CARDXREF 50/KEYS(16 0);
//*          TRANSACT 350/KEYS(16 0))
//* *******************************************************************
//STEP20   EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
   DEFINE CLUSTER (NAME(&HLQ..CARDDATA.VSAM.KSDS) -
          CYLINDERS(1 5) -
          KEYS(16 0) -
          RECORDSIZE(150 150) -
          SHAREOPTIONS(2 3) -
          INDEXED) -
          DATA (NAME(&HLQ..CARDDATA.VSAM.KSDS.DATA)) -
          INDEX (NAME(&HLQ..CARDDATA.VSAM.KSDS.INDEX))
   DEFINE CLUSTER (NAME(&HLQ..CARDXREF.VSAM.KSDS) -
          CYLINDERS(1 5) -
          KEYS(16 0) -
          RECORDSIZE(50 50) -
          SHAREOPTIONS(2 3) -
          INDEXED) -
          DATA (NAME(&HLQ..CARDXREF.VSAM.KSDS.DATA)) -
          INDEX (NAME(&HLQ..CARDXREF.VSAM.KSDS.INDEX))
   DEFINE CLUSTER (NAME(&HLQ..TRANSACT.VSAM.KSDS) -
          CYLINDERS(1 5) -
          KEYS(16 0) -
          RECORDSIZE(350 350) -
          SHAREOPTIONS(2 3) -
          INDEXED) -
          DATA (NAME(&HLQ..TRANSACT.VSAM.KSDS.DATA)) -
          INDEX (NAME(&HLQ..TRANSACT.VSAM.KSDS.INDEX))
/*
//*
//* *******************************************************************
//* STEP30 - REPRO the staged flat files into the clone clusters.
//*          Stage only the file types the chosen scenario needs; a
//*          card-only scenario leaves TRANSACT empty (an empty list).
//* *******************************************************************
//STEP30   EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//CARDDATA DD   DISP=SHR,DSN=&HLQ..CARDDATA.PS
//CARDVSAM DD   DISP=SHR,DSN=&HLQ..CARDDATA.VSAM.KSDS
//XREFDATA DD   DISP=SHR,DSN=&HLQ..CARDXREF.PS
//XREFVSAM DD   DISP=SHR,DSN=&HLQ..CARDXREF.VSAM.KSDS
//SYSIN    DD   *
   REPRO INFILE(CARDDATA) OUTFILE(CARDVSAM)
   REPRO INFILE(XREFDATA) OUTFILE(XREFVSAM)
/*
//*
//* *******************************************************************
//* STEP35 - REPRO transactions (TRANBND / TRANTRN only). For the
//*          card-only scenarios comment this step out or stage an
//*          empty TRANSACT PS.
//* *******************************************************************
//STEP35   EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//TRANDATA DD   DISP=SHR,DSN=&HLQ..TRANSACT.PS
//TRANVSAM DD   DISP=SHR,DSN=&HLQ..TRANSACT.VSAM.KSDS
//SYSIN    DD   *
   REPRO INFILE(TRANDATA) OUTFILE(TRANVSAM)
/*
//*
//* *******************************************************************
//* STEP40 - Build the account->card AIX on the CARDDATA clone
//*          (KEYS(11 16)) so COTRSVCC can resolve an account to its
//*          cards, mirroring base CARDFILE.jcl.
//* *******************************************************************
//STEP40   EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
   DEFINE ALTERNATEINDEX (NAME(&HLQ..CARDDATA.VSAM.AIX) -
          RELATE(&HLQ..CARDDATA.VSAM.KSDS) -
          KEYS(11 16) -
          NONUNIQUEKEY -
          UPGRADE -
          RECORDSIZE(150 150) -
          CYLINDERS(5 1)) -
          DATA (NAME(&HLQ..CARDDATA.VSAM.AIX.DATA)) -
          INDEX (NAME(&HLQ..CARDDATA.VSAM.AIX.INDEX))
   DEFINE PATH (NAME(&HLQ..CARDDATA.VSAM.AIX.PATH) -
          PATHENTRY(&HLQ..CARDDATA.VSAM.AIX))
   BLDINDEX INDATASET(&HLQ..CARDDATA.VSAM.KSDS) -
          OUTDATASET(&HLQ..CARDDATA.VSAM.AIX)
/*
//*
//* *******************************************************************
//* STEP50 - Build the account->xref AIX on the CARDXREF clone
//*          (KEYS(11 25)), mirroring base XREFFILE.jcl.
//* *******************************************************************
//STEP50   EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
   DEFINE ALTERNATEINDEX (NAME(&HLQ..CARDXREF.VSAM.AIX) -
          RELATE(&HLQ..CARDXREF.VSAM.KSDS) -
          KEYS(11 25) -
          NONUNIQUEKEY -
          UPGRADE -
          RECORDSIZE(50 50) -
          CYLINDERS(5 1)) -
          DATA (NAME(&HLQ..CARDXREF.VSAM.AIX.DATA)) -
          INDEX (NAME(&HLQ..CARDXREF.VSAM.AIX.INDEX))
   DEFINE PATH (NAME(&HLQ..CARDXREF.VSAM.AIX.PATH) -
          PATHENTRY(&HLQ..CARDXREF.VSAM.AIX))
   BLDINDEX INDATASET(&HLQ..CARDXREF.VSAM.KSDS) -
          OUTDATASET(&HLQ..CARDXREF.VSAM.AIX)
/*
//
