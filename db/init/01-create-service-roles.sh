#!/bin/bash
# =============================================================================
# db/init/01-create-service-roles.sh
#
# Provisions the per-service PostgreSQL login roles that the deployment
# descriptors reference. The Kubernetes secrets (k8s/secret.yaml) hand each
# DB-owning service its OWN SPRING_DATASOURCE_USERNAME / _PASSWORD - carddemo_auth,
# carddemo_user, carddemo_account, carddemo_card, carddemo_transaction,
# carddemo_billpay, carddemo_reporting, carddemo_batch - and the bootstrap secret
# states that "a DB-init step uses it to create the per-service roles below".
# This IS that step. Without it those eight roles never existed, so the manifests
# referenced credentials nothing could authenticate.
#
# The api-gateway is deliberately absent: it maps no entities and holds no
# datasource.
#
# HOW IT RUNS
#   The official postgres image executes every /docker-entrypoint-initdb.d/*.sh
#   exactly once, during first-boot initialisation of an empty data directory,
#   as the superuser named by POSTGRES_USER against POSTGRES_DB. docker-compose.yml
#   mounts this directory read-only; k8s/deployment-postgres.yaml projects the same
#   script from a ConfigMap onto the same path.
#
# PRIVILEGE MODEL
#   Every service runs the SAME shared Flyway migration set from carddemo-common,
#   so any one of them may be the process that first materialises the schema and
#   therefore ends up OWNING those tables. A per-role grant matrix would need
#   8x8 entries and would still miss objects created after it ran. Instead all
#   eight roles are members of one NOLOGIN group role, carddemo_app:
#
#     * carddemo_app holds USAGE + CREATE on schema public (CREATE is required
#       because Flyway creates its own flyway_schema_history table, and because
#       whichever service wins the race applies the DDL migrations).
#     * carddemo_app holds SELECT/INSERT/UPDATE/DELETE on every existing table and
#       USAGE/SELECT/UPDATE on every existing sequence.
#     * ALTER DEFAULT PRIVILEGES is declared FOR EACH of the nine possible creating
#       roles, so any object a service creates later is automatically usable by all
#       of its siblings. This is what makes the model hold under the shared
#       migration set rather than only at provisioning time.
#
#   The result is per-service AUTHENTICATION and per-service auditability with a
#   shared application privilege set - not per-table isolation. The precise scope,
#   and why per-table isolation would require separating migration from runtime,
#   is recorded in docs/decision-log.md.
#
# PASSWORDS
#   Each role's password comes from CARDDEMO_<SVC>_DB_PASSWORD in the environment.
#   Nothing is defaulted: a missing or empty value aborts initialisation rather
#   than silently creating a role with a guessable or absent password.
#
# IDEMPOTENCE
#   Every statement tolerates re-execution (CREATE ... IF NOT EXISTS semantics via
#   DO blocks, and ALTER ROLE for the password), so re-running the script - or
#   applying it by hand against an existing cluster - is safe.
# =============================================================================
set -euo pipefail

# Role name -> name of the environment variable carrying its password.
SERVICE_ROLES=(
  "carddemo_auth:CARDDEMO_AUTH_DB_PASSWORD"
  "carddemo_user:CARDDEMO_USER_DB_PASSWORD"
  "carddemo_account:CARDDEMO_ACCOUNT_DB_PASSWORD"
  "carddemo_card:CARDDEMO_CARD_DB_PASSWORD"
  "carddemo_transaction:CARDDEMO_TRANSACTION_DB_PASSWORD"
  "carddemo_billpay:CARDDEMO_BILLPAY_DB_PASSWORD"
  "carddemo_reporting:CARDDEMO_REPORTING_DB_PASSWORD"
  "carddemo_batch:CARDDEMO_BATCH_DB_PASSWORD"
)

GROUP_ROLE="carddemo_app"

echo "carddemo: provisioning per-service database roles in ${POSTGRES_DB} as ${POSTGRES_USER}"

# Fail before touching the cluster if any password is missing, so the container
# never comes up half-provisioned.
for entry in "${SERVICE_ROLES[@]}"; do
  var="${entry#*:}"
  if [ -z "${!var:-}" ]; then
    echo "carddemo: FATAL - ${var} is not set; cannot create role ${entry%%:*}" >&2
    exit 1
  fi
done

run_sql() {
  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" "$@"
}

# --- The shared application group role ---------------------------------------
run_sql <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${GROUP_ROLE}') THEN
        CREATE ROLE ${GROUP_ROLE} NOLOGIN;
    END IF;
END
\$\$;

GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO ${GROUP_ROLE};
GRANT USAGE, CREATE ON SCHEMA public TO ${GROUP_ROLE};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${GROUP_ROLE};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ${GROUP_ROLE};
ALTER DEFAULT PRIVILEGES FOR ROLE ${POSTGRES_USER} IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${GROUP_ROLE};
ALTER DEFAULT PRIVILEGES FOR ROLE ${POSTGRES_USER} IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${GROUP_ROLE};
SQL

# --- One login role per DB-owning service ------------------------------------
for entry in "${SERVICE_ROLES[@]}"; do
  role="${entry%%:*}"
  var="${entry#*:}"
  password="${!var}"

  # The role name and password travel as psql variables and are rendered into SQL
  # only through format()'s %I / %L, which quote an identifier and a literal
  # correctly whatever characters they contain. `\gexec` then runs each rendered
  # statement, so the values are never spliced into SQL text by the shell.
  #
  # The statements deliberately sit OUTSIDE any dollar-quoted block: psql does not
  # substitute :'var' inside $$ ... $$, so a DO block would receive the literal
  # text :'role' and fail with `syntax error at or near ":"`.
  run_sql --set=role="${role}" --set=password="${password}" <<'SQL'
SELECT format(
           CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role')
                THEN 'ALTER ROLE %I WITH LOGIN PASSWORD %L'
                ELSE 'CREATE ROLE %I WITH LOGIN PASSWORD %L'
           END,
           :'role', :'password')
\gexec

SELECT format('GRANT carddemo_app TO %I', :'role')
\gexec

-- Anything this role creates later (the Flyway history table, or the migrated
-- business tables when it wins the migration race) is granted to the shared group
-- so every sibling service can read and write it.
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public'
              ' GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO carddemo_app', :'role')
\gexec

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public'
              ' GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO carddemo_app', :'role')
\gexec
SQL

  echo "carddemo: role ${role} ready (member of ${GROUP_ROLE})"
done

echo "carddemo: per-service database roles provisioned"
