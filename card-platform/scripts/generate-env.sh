#!/usr/bin/env bash
#
# Creates card-platform/.env and fills every credential that is still a placeholder.
#
# The variable names are read out of .env.example rather than listed here. A password is
# any assignment whose value begins REPLACE-WITH, and an identity hash is any assignment
# whose value carries REPLACE-THIS inside a {bcrypt} prefix. Adding a credential to
# .env.example therefore extends this script with no edit to it, which is the drift a
# second hand-kept list of names would invite.
#
# Re-running is safe and changes nothing that already holds a value: only assignments
# still carrying a placeholder are rewritten.
#
# The three identity passwords are stored as bcrypt hashes, and a hash cannot be sent to a
# service as a password, so the plaintext generated here is written to
# card-platform/.demo-credentials with owner-only permissions. That file is git-ignored.
# To choose a password yourself, set the matching override before running:
# CARDDEMO_ADMIN_PASSWORD, CARDDEMO_USER_PASSWORD or CARDDEMO_MONITORING_PASSWORD.
#
# Usage:
#   scripts/generate-env.sh
#
# Requires openssl and jshell. jshell reads spring-security-crypto out of the local Maven
# repository, so `mvn -B -ntp -DskipTests package` has to have run once on this machine.
# scripts/start-demo.sh runs that first and then calls this script.
#
# Rationale for the choices here: card-platform/docs/decision-log.md
# Setup guide: card-platform/docs/onboarding.md

set -euo pipefail

platform_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${platform_root}"

environment_file=".env"
example_file=".env.example"
credentials_file=".demo-credentials"
maven_repository="${MAVEN_REPOSITORY:-${HOME}/.m2/repository}"

note() { printf 'generate-env: %s\n' "$1"; }
fail() {
    printf 'generate-env: %s\n' "$1" >&2
    exit 1
}

command -v openssl >/dev/null 2>&1 \
    || fail "openssl is not on the path. It generates every password here."
command -v jshell >/dev/null 2>&1 \
    || fail "jshell is not on the path. Install Eclipse Temurin OpenJDK 25.0.4+7."
[ -f "${example_file}" ] \
    || fail "${example_file} is missing. Run this from a complete checkout."

if [ ! -f "${environment_file}" ]; then
    cp "${example_file}" "${environment_file}"
    note "created ${environment_file} from ${example_file}"
else
    note "reusing the existing ${environment_file}"
fi
# Owner-only, because the finished file holds nineteen working credentials.
chmod 600 "${environment_file}"

# Passwords, and the card-token key, which is generated the same way. tr strips three
# characters no password here may carry: the broker builds a login entry around its password,
# and a quote, a backslash or whitespace ends that entry early. What is left is alphanumeric,
# which is also safe as a sed replacement. The length is deliberate: com.carddemo.cobol.PanMasker
# refuses a card-token key under 32 characters, and 48 base-64 bytes leave well over that many
# after the three characters are stripped.
mapfile -t password_keys \
    < <(sed -n 's/^\([A-Z0-9_]\{1,\}\)=REPLACE-WITH.*/\1/p' "${environment_file}")
if [ "${#password_keys[@]}" -eq 0 ]; then
    note "every password already holds a value"
else
    for key in "${password_keys[@]}"; do
        value="$(openssl rand -base64 48 | tr -d '/+=')"
        sed -i "s|^${key}=.*|${key}=${value}|" "${environment_file}"
    done
    note "generated ${#password_keys[@]} passwords and keys"
fi

# Identity hashes. Each service refuses a value that carries no encoding prefix, which is
# how this platform declines to reproduce the plaintext comparison at
# app/cbl/COSGN00C.cbl:L223.
mapfile -t identity_keys \
    < <(sed -n "s/^\([A-Z0-9_]\{1,\}\)='{bcrypt}.*REPLACE-THIS.*/\1/p" "${environment_file}")

if [ "${#identity_keys[@]}" -eq 0 ]; then
    note "every identity hash already holds a value"
else
    newest_jar() {
        find "${maven_repository}/$1" -name "$2-*.jar" \
            ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | tail -1
    }

    crypto_jar="$(newest_jar \
        org/springframework/security/spring-security-crypto spring-security-crypto)"
    core_jar="$(newest_jar org/springframework/spring-core spring-core)"
    logging_jar="$(newest_jar commons-logging/commons-logging commons-logging)"
    for jar in "${crypto_jar}" "${core_jar}" "${logging_jar}"; do
        [ -n "${jar}" ] || fail \
            "spring-security-crypto is not in ${maven_repository}. Run
  mvn -B -ntp -DskipTests package
from ${platform_root} first, which resolves it, then run this script again."
    done
    crypto_classpath="${crypto_jar}:${core_jar}:${logging_jar}"

    # Carries forward any line already recorded, so an identity left alone keeps its
    # password and only the ones generated now are replaced.
    credential_lines=()
    if [ -f "${credentials_file}" ]; then
        mapfile -t credential_lines < <(grep -v '^#' "${credentials_file}" | grep -v '^$' || true)
    fi

    for key in "${identity_keys[@]}"; do
        # ADMIN_PASSWORD_HASH names ADMIN_USERNAME and CARDDEMO_ADMIN_PASSWORD. The three
        # names are derived from the one key, so no mapping table can fall out of date.
        identity="${key%_PASSWORD_HASH}"
        username="$(sed -n "s/^${identity}_USERNAME=//p" "${environment_file}" | head -1)"
        [ -n "${username}" ] || username="${identity}"
        override="CARDDEMO_${identity}_PASSWORD"
        plaintext="${!override:-}"
        [ -n "${plaintext}" ] || plaintext="$(openssl rand -base64 18 | tr -d '/+=')"

        export CARDDEMO_PLAINTEXT="${plaintext}"
        hash="$(printf 'System.out.println(org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(System.getenv("CARDDEMO_PLAINTEXT")));\n/exit\n' \
            | jshell --class-path "${crypto_classpath}" 2>/dev/null \
            | sed -n 's/^jshell> //p' \
            | grep '^{bcrypt}' \
            | head -1)"
        unset CARDDEMO_PLAINTEXT
        [ -n "${hash}" ] || fail "jshell produced no bcrypt value for ${key}"

        # Single quotes are required. A bcrypt value contains $, and Compose expands $ in
        # an unquoted or double-quoted dotenv value.
        sed -i "s|^${key}=.*|${key}='${hash}'|" "${environment_file}"

        remaining=()
        for line in "${credential_lines[@]:-}"; do
            case "${line}" in
                "${username}="*) ;;
                "") ;;
                *) remaining+=("${line}") ;;
            esac
        done
        credential_lines=("${remaining[@]:-}" "${username}=${plaintext}")
    done

    {
        printf '# card-platform demo logins, written by scripts/generate-env.sh.\n'
        printf '#\n'
        printf '# .env carries the bcrypt hash of each password below, and a hash cannot be sent\n'
        printf '# to a service as a password, so these are kept here instead. Every one is\n'
        printf '# disposable and belongs to one local container stack. This file is git-ignored.\n'
        printf '#\n'
        printf '# Authenticate with:  curl -u "<username>:<password>" http://localhost:8081/...\n'
        printf '#\n'
        for line in "${credential_lines[@]:-}"; do
            [ -n "${line}" ] && printf '%s\n' "${line}"
        done
    } > "${credentials_file}"
    chmod 600 "${credentials_file}"
    note "generated ${#identity_keys[@]} identity hashes; the passwords are in ${credentials_file}"
fi

remaining_placeholders="$(grep -c 'REPLACE-WITH\|REPLACE-THIS' "${environment_file}" || true)"
[ "${remaining_placeholders}" -eq 0 ] \
    || fail "${remaining_placeholders} placeholders remain in ${environment_file}"

note "${environment_file} carries no placeholder and is ready"
