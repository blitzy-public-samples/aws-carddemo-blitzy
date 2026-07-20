//APIBFXC  JOB 'Clean API boundary fixtures',CLASS=A,MSGCLASS=0,
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
//* PURPOSE (QA finding MAJ-08): dispose of the boundary/fault clone
//* VSAM datasets created by app/test/api/load-boundary-fixtures.jcl.
//* It only ever DELETEs the APIBT clone qualifier, so it cannot
//* affect the production CardDemo VSAM files. Every DELETE tolerates
//* a not-found condition (SET MAXCC = 0), so the job always ends RC=0
//* whether or not the clones still exist - safe to run repeatedly.
//******************************************************************
// SET HLQ=AWS.M2.CARDDEMO.APIBT
//*
//* *******************************************************************
//* Delete every clone cluster, alternate index, and path
//* *******************************************************************
//STEP10   EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
   DELETE &HLQ..CARDDATA.VSAM.AIX.PATH PATH
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDDATA.VSAM.AIX ALTERNATEINDEX
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDDATA.VSAM.KSDS CLUSTER
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDXREF.VSAM.AIX.PATH PATH
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDXREF.VSAM.AIX ALTERNATEINDEX
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..CARDXREF.VSAM.KSDS CLUSTER
   IF MAXCC LE 08 THEN SET MAXCC = 0
   DELETE &HLQ..TRANSACT.VSAM.KSDS CLUSTER
   IF MAXCC LE 08 THEN SET MAXCC = 0
/*
//
