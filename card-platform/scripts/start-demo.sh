#!/usr/bin/env bash
#
# One command from a clean clone to a running demo stack.
#
# A clean clone cannot start with `docker compose up` alone, and the reasons are structural
# rather than incidental. docker-compose.yml reads nineteen credentials that nothing in this
# repository supplies, because a credential published here would be a credential everyone
# holds. The file they come from, .env, does not exist until it is copied. And the hash of
# each identity password is produced by jshell reading spring-security-crypto out of the
# local Maven repository, which the packaging step below is what populates. The images need
# no help: each Dockerfile compiles its own module in a Java Development Kit 25 builder
# stage. This script performs those steps in order and then starts the stack:
#
#   1. mvn -B -ntp -DskipTests package      fills the local repository the hashing step reads
#   2. scripts/generate-env.sh              creates .env and fills all nineteen credentials
#   3. docker compose up -d --build --wait  builds the six images and starts eight containers
#   4. reads /actuator/health on each of the six management ports
#
# Usage:
#   scripts/start-demo.sh
#
# Re-running is safe. Step 2 leaves an existing .env alone except for placeholders, and
# Compose recreates only what changed. The one thing that is not safe is replacing .env
# while an earlier database volume survives: the volume holds the superuser password the
# previous file carried, and nothing can sign in with the new one. Discard the volumes in
# the same move as the credentials:
#
#   docker compose down --volumes && scripts/start-demo.sh
#
# Set CLONE_INDEX to run a second stack beside the first, with POSTGRES_PORT and KAFKA_PORT
# moved to free host ports:
#
#   CLONE_INDEX=2 POSTGRES_PORT=5442 KAFKA_PORT=9102 scripts/start-demo.sh
#
# Tested toolchain: Eclipse Temurin OpenJDK 25.0.4+7, Apache Maven 3.9.16, Docker Engine
# 29.7.0, Docker Compose 5.3.1, OpenSSL 3.5.3, curl 8.14.1, git 2.51.0. The build refuses an
# older Java or Maven itself, and the loop below refuses a missing tool by name.
#
# Rationale for the choices here: card-platform/docs/decision-log.md
# Setup guide, ports, demo requests and pitfalls: card-platform/docs/onboarding.md

set -euo pipefail

platform_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${platform_root}"

services=(
    authorization-service
    ledger-posting-service
    fraud-detection-service
    notification-service
    account-service
    card-service
)

step() { printf '\nstart-demo: %s\n' "$1"; }
note() { printf 'start-demo: %s\n' "$1"; }
fail() {
    printf 'start-demo: %s\n' "$1" >&2
    exit 1
}

# curl is in this list because the health check at the end of this script uses it. It was absent
# once, so a machine without curl reached the last step and failed there instead of here.
for tool in java mvn docker openssl jshell curl; do
    command -v "${tool}" >/dev/null 2>&1 || fail \
        "${tool} is not on the path. card-platform/docs/onboarding.md lists the tested versions."
done
docker compose version >/dev/null 2>&1 \
    || fail "docker compose is unavailable. Install the Compose plugin. card-platform/docs/onboarding.md
  names the version this stack was exercised on, 5.3.1, and --wait below needs 2.1.1 or newer."
docker info >/dev/null 2>&1 \
    || fail "the Docker daemon is not reachable. Start Docker and run this again."

step "packaging the reactor, which fills the local repository the credential hashes are read from"
mvn -B -ntp -DskipTests package

step "preparing .env and its nineteen credentials"
scripts/generate-env.sh

step "building the six images and starting eight containers"
# --wait holds until every container reports healthy and exits non-zero if one does not,
# so a failed start stops here rather than at the health check below. The timeout bounds
# the one case --wait alone does not: a container that stays in "starting" and is never
# declared unhealthy would otherwise hold this script open with no message. A cold stack
# converges in about two minutes, so five is a bound rather than a target.
#
# Compose reports a failure as "container X is unhealthy", which names no cause. The one
# cause this script can predict is worth naming: a database volume outlives .env, so a
# replaced environment file leaves the volume holding the previous superuser password and
# nothing can sign in.
if ! docker compose up --detach --build --wait \
        --wait-timeout "${COMPOSE_WAIT_SECONDS:-300}"; then
    fail "the stack did not converge.
  If .env was replaced or its credentials regenerated after an earlier start, the database
  volume still holds the previous superuser password. Discard the volumes and start again:
      docker compose down --volumes && scripts/start-demo.sh
  For any other cause, read the container that failed:
      docker compose ps
      docker compose logs postgres kafka"
fi

step "reading the health endpoint of each service"
# The published host port is asked of Compose rather than assumed, because .env or the
# shell may have moved any of them, and CLONE_INDEX exists so a second stack can. Shell
# first, then .env, then the shipped default, which is the precedence Compose itself uses.
management_port="${MANAGEMENT_PORT:-$(sed -n 's/^MANAGEMENT_PORT=//p' .env | tail -1)}"
management_port="${management_port:-9080}"
for service in "${services[@]}"; do
    published="$(docker compose port "${service}" "${management_port}")"
    [ -n "${published}" ] || fail "Compose publishes no host port for ${service}"
    status="$(curl --fail --silent --show-error "http://${published}/actuator/health")"
    printf '%s' "${status}" | grep -q '"status":"UP"' \
        || fail "${service} on ${published} answered ${status}"
    note "${service} UP on ${published}"
done

step "the stack is up"
note "business routes require HTTP Basic authentication; the PORTS column below is where"
note "each one answers on this machine:"
docker compose ps
note "the four demo logins are in ${platform_root}/.demo-credentials"
note "next: docs/onboarding.md, section Verify the running stack"
note "stop it with: docker compose down --volumes"
