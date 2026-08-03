-- =============================================================================
-- V5__batch_metadata.sql - Spring Batch metadata schema (JobRepository tables
-- and sequences).
--
-- The canonical Spring Batch 6 PostgreSQL metadata DDL, taken verbatim from the
-- official schema-postgresql.sql shipped inside spring-batch-core (the version
-- managed by the Spring Boot 4.1.0 BOM), so the JobRepository contract is
-- byte-for-byte the framework's own: 6 tables (BATCH_JOB_INSTANCE,
-- BATCH_JOB_EXECUTION, BATCH_JOB_EXECUTION_PARAMS, BATCH_STEP_EXECUTION,
-- BATCH_STEP_EXECUTION_CONTEXT, BATCH_JOB_EXECUTION_CONTEXT) plus the 3 id
-- sequences. Only IF NOT EXISTS was added, which changes no resulting catalog
-- object but lets the set be replayed or adopted on a database that already
-- carries the tables.
--
-- These tables re-platform the JES job/step bookkeeping of the legacy JCL job
-- streams (app/jcl/), so they belong to the same ordered, single-owner migration
-- set as the business schema. They ship in the shared carddemo-common set rather
-- than in one service because THREE services run Spring Batch jobs against this
-- one shared database - batch-service (interest calculation and the
-- data-management jobs), transaction-service (transaction posting) and
-- reporting-service (statement generation) - which removes the hidden
-- requirement that batch-service start first for the others' JobRepository to
-- work.
--
-- Spring Boot 4.1 removed the whole spring.batch.jdbc.* property group, so the
-- former initialize-schema setting no longer exists to be set. Flyway owns this
-- metadata exactly as it owns the business tables; the only other writer of the
-- DDL is the sentinel-guarded provisioner in carddemo-common's
-- JdbcBatchConfiguration, which applies the framework's own script when a
-- service reaches a database where these tables are still absent. Both paths are
-- idempotent, so a Flyway-managed database, a freshly provisioned one and a test
-- container all converge on the same schema.
-- =============================================================================

CREATE TABLE IF NOT EXISTS BATCH_JOB_INSTANCE (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_PARAMS (
	JOB_EXECUTION_ID BIGINT NOT NULL,
	PARAMETER_NAME VARCHAR(100) NOT NULL,
	PARAMETER_TYPE VARCHAR(100) NOT NULL,
	PARAMETER_VALUE VARCHAR(2500),
	IDENTIFYING CHAR(1) NOT NULL,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	COMMIT_COUNT BIGINT,
	READ_COUNT BIGINT,
	FILTER_COUNT BIGINT,
	WRITE_COUNT BIGINT,
	READ_SKIP_COUNT BIGINT,
	WRITE_SKIP_COUNT BIGINT,
	PROCESS_SKIP_COUNT BIGINT,
	ROLLBACK_COUNT BIGINT,
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION_CONTEXT (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_CONTEXT (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE IF NOT EXISTS BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
