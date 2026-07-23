-- =============================================================================
-- V0__spring_batch_metadata.sql
--
-- Spring Batch metadata schema (PostgreSQL) for the CardDemo batch tier.
--
-- WHY THIS FILE EXISTS (review finding F2, CRITICAL):
--   Spring Batch's JdbcJobRepository requires the BATCH_* metadata tables and
--   sequences to exist before any Job/Step can run or restart. The previous
--   configuration relied on `spring.batch.jdbc.initialize-schema: embedded`,
--   which on a non-embedded database (PostgreSQL) behaves as `never` and so
--   NEVER created these tables. No dev/test profile and no other migration
--   supplied them, so every Job (including adminBatchJob) would fail on first
--   execution and could not restart.
--
-- AUTHORITATIVE STRATEGY:
--   Flyway is the single source of truth for ALL DDL in this application
--   (spring.jpa.hibernate.ddl-auto=validate; Hibernate never creates schema).
--   The Spring Batch metadata is therefore created here as a versioned Flyway
--   migration and `spring.batch.jdbc.initialize-schema` is set to `never`, so
--   there is exactly one authority for these tables and it is consistent across
--   every profile.
--
--   This migration runs first (V0) so the batch metadata exists before the
--   business schema (V1__schema.sql), reference data (V2), and indexes (V3).
--   The batch tables are self-contained and do not depend on the business
--   schema, and vice versa, so this ordering introduces no coupling.
--
-- PROVENANCE (version-matched, per F2 resolution):
--   Copied verbatim from the canonical DDL shipped inside the Spring Batch
--   distribution actually on this project's classpath:
--     spring-batch-core 5.2.6 :: org/springframework/batch/core/schema-postgresql.sql
--   Keeping this byte-for-identical to the library's own script guarantees the
--   JdbcJobRepository DAOs find exactly the columns/sequences they expect.
--
-- IDEMPOTENCY:
--   No `IF NOT EXISTS` clauses are used. Flyway records this migration in
--   flyway_schema_history and applies it exactly once per database, so guarding
--   for re-application is unnecessary and would mask accidental double-baselining.
-- =============================================================================

CREATE TABLE BATCH_JOB_INSTANCE  (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT ,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION  (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT  ,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS  (
	JOB_EXECUTION_ID BIGINT NOT NULL ,
	PARAMETER_NAME VARCHAR(100) NOT NULL ,
	PARAMETER_TYPE VARCHAR(100) NOT NULL ,
	PARAMETER_VALUE VARCHAR(2500) ,
	IDENTIFYING CHAR(1) NOT NULL ,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION  (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	COMMIT_COUNT BIGINT ,
	READ_COUNT BIGINT ,
	FILTER_COUNT BIGINT ,
	WRITE_COUNT BIGINT ,
	READ_SKIP_COUNT BIGINT ,
	WRITE_SKIP_COUNT BIGINT ,
	PROCESS_SKIP_COUNT BIGINT ,
	ROLLBACK_COUNT BIGINT ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT  (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT  (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
