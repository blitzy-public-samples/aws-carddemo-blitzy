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
# card-platform/.demo-credentials. That file is git-ignored, and it is owner-only from the
# moment it exists: this script sets umask 077 before its first write, fills a temporary file
# mktemp created at mode 600, and renames that onto the name above.
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

# Every file this script creates carries a working credential, so none of them may exist
# readable to anyone else for any interval at all. This is set before the first write rather
# than repaired after it: a redirection creates its file under the umask in force at that
# moment, and a mode narrowed afterwards leaves whatever was written before the narrowing
# readable to every local account in between.
umask 077

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

# Refuses a destination this script would write a credential through rather than to.
#
# A symbolic link makes the write land wherever the link points, which is how a file this
# script creates owner-only ends up appended to something readable. A directory or a device
# in the same position is the same problem in a different shape. Either is reported and
# nothing is written.
refuse_indirect_destination() {
    [ ! -L "$1" ] || fail "$1 is a symbolic link. This script writes credentials to it, so it
  has to be a regular file. Remove or rename the link and run this again."
    [ ! -e "$1" ] || [ -f "$1" ] || fail "$1 exists and is not a regular file. This script
  writes credentials to it. Remove or rename it and run this again."
}

[ -f "${example_file}" ] \
    || fail "${example_file} is missing. Run this from a complete checkout."

# Both destinations are checked, and an existing credentials file is brought back to
# owner-only, before anything is generated. Both run whether or not this run writes a
# password: a file an earlier run left behind carries the same four logins, and the exposure
# belongs to the file rather than to the run that created it.
refuse_indirect_destination "${environment_file}"
refuse_indirect_destination "${credentials_file}"
if [ -f "${credentials_file}" ]; then
    chmod go-rwx "${credentials_file}"
    note "${credentials_file} already exists; its mode is now owner-only"
fi

# Each tool is required where it is used rather than here. A re-run with nothing left to
# generate — the common case, and the reconciliation below is the whole of it — calls neither,
# so demanding both up front would refuse a run that needs neither. Reconciliation happens
# before generation, so a tool that is genuinely missing still leaves the file complete in its
# names and reports which value it could not produce.
require_tool() {
    command -v "$1" >/dev/null 2>&1 || fail "$2"
}

# Every assignment one file declares, one name per line and in file order.
declared_keys() { sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$1"; }

# Names the second file declares and the first file does not carry, in the order the second
# declares them. One awk pass rather than a shell loop, because a loop over `read` in a script
# whose whole purpose is to ask nothing reads as a prompt to anyone scanning for one.
keys_missing_from() {
    awk -v carried="$1" '
        BEGIN {
            while ((line = 0) == 0 && (getline line < carried) > 0) {
                if (line ~ /^[A-Za-z_][A-Za-z0-9_]*=/) {
                    split(line, assignment, "=")
                    present[assignment[1]] = 1
                }
            }
        }
        /^[A-Za-z_][A-Za-z0-9_]*=/ {
            split($0, assignment, "=")
            name = assignment[1]
            if (!(name in present) && !(name in reported)) {
                reported[name] = 1
                print name
            }
        }
    ' "$2"
}

if [ ! -f "${environment_file}" ]; then
    # install -m 600 rather than cp, so the file is never readable by anyone else, not even
    # for the moment between the copy and the chmod below.
    install -m 600 "${example_file}" "${environment_file}"
    note "created ${environment_file} from ${example_file}"
else
    note "reusing the existing ${environment_file}"
fi
# Owner-only, because the finished file holds every working credential of one stack.
chmod 600 "${environment_file}"

# Reconciliation, and it runs before anything is generated so that whatever it adds is
# filled in the same run.
#
# .env.example is the declaration of what a run needs; .env is one machine's answer to it.
# A key added to the declaration after the answer was written is simply absent from the
# answer, and an absent key leaves no placeholder behind, so the placeholder check at the
# end of this script cannot see it. That is not hypothetical: this script reported success
# on a file that was missing ACQUIRER_PASSWORD_HASH, and `docker compose config` then
# refused to interpolate it, so the documented demo path stopped at the first command.
#
# Every declared assignment this file does not carry is therefore appended with the example's
# own value, which is a placeholder for a credential and a working default for everything
# else. No existing line is read, reordered or rewritten: a value already chosen on this
# machine is that machine's, and a password in it is not recoverable from the hash it made.
mapfile -t added_keys < <(keys_missing_from "${environment_file}" "${example_file}")
if [ "${#added_keys[@]}" -eq 0 ]; then
    note "${environment_file} already declares every assignment ${example_file} does"
else
    {
        printf '\n# Added by scripts/generate-env.sh, reconciling this file against %s.\n' \
            "${example_file}"
        printf '# Each line below is the example value of a key this file did not carry.\n'
        printf '# A credential among them arrives as a placeholder and is generated below.\n'
        for key in "${added_keys[@]}"; do
            grep -m1 "^${key}=" "${example_file}"
        done
    } >> "${environment_file}"
    note "added ${#added_keys[@]} assignments ${example_file} declares and ${environment_file} did not:
  ${added_keys[*]}"
fi

# The other direction, reported rather than acted on. A name here that the example does not
# declare is either a setting that was retired or one this machine added, and this script
# cannot tell which: it reads two files and knows nothing about what consumes a variable.
# Deleting would risk discarding a value someone chose, and staying silent would leave a
# retired setting looking effective, so it names them and leaves them.
mapfile -t superseded_keys < <(keys_missing_from "${example_file}" "${environment_file}")
if [ "${#superseded_keys[@]}" -gt 0 ]; then
    note "${environment_file} carries ${#superseded_keys[@]} assignments ${example_file} does not
  declare. Each was either retired or added here on purpose, and this script cannot tell which,
  so it leaves them in place. Check each one and remove the retired ones yourself:
  ${superseded_keys[*]}"
fi

# Values this repository publishes, which every service that reads them refuses.
#
# Reconciling names is not enough on its own. A value that was usable when it was written can
# stop being usable, and the card-token key is the case that reaches every deployment: the
# authorization and card services refuse to start on the demo key published here, because
# anyone holding it and one card token can recompute the token of every card number. A file
# written before that guard existed carries that key, no placeholder marks it, and the check at
# the end of this script sees a complete file. The stack then fails at start-up.
#
# So an assignment holding one of these values is put back to whatever the example declares for
# that same name, which for a credential is the placeholder the generation below fills in this
# same run. The name is read out of the file rather than written here, for the reason every other
# name in this script is: a list of credential names kept here falls behind the example.
#
# This is the one case where a value already in the file is rewritten, and it is safe to rewrite
# precisely because the value is published: it is nobody's secret, and no deployment can be using
# it, since none can start on it.
published_values=(
    "carddemo-demo-card-token-key-not-for-production"
    "carddemo-build-scope-card-token-key-tests-only"
)
for published in "${published_values[@]}"; do
    mapfile -t holders < <(awk -F= -v published="${published}" '
        /^[A-Za-z_][A-Za-z0-9_]*=/ {
            name = $1
            sub(/^[^=]*=/, "")
            if ($0 == published) {
                print name
            }
        }
    ' "${environment_file}")
    for key in "${holders[@]}"; do
        replacement="$(grep -m1 "^${key}=" "${example_file}" | sed "s|^${key}=||")"
        [ -n "${replacement}" ] || fail \
            "${key} holds a value this repository publishes and ${example_file} declares no
  replacement for it. Give ${key} a value of your own."
        sed -i "s|^${key}=.*|${key}=${replacement}|" "${environment_file}"
        note "${key} held a value this repository publishes, which every service that reads it
  refuses to start on. A new one is generated below. Anything already derived under the old value
  stops resolving: card-platform/docs/suggested-next-tasks.md carries the rotation procedure, and
  a SCOPE_CARD authority in this file has to be re-derived."
    done
done

# Settings this file overrides, reported rather than changed.
#
# A value that differs from the example's is a choice this machine made, and keeping it is the
# point of reconciling rather than overwriting. It is also where a refusal at start-up usually
# comes from: a default the platform tightened is not a missing key and not a placeholder, so
# nothing above notices it. Naming both values is what turns "the service will not start" into
# one line an operator can act on. Credentials are excluded, because every one of them differs
# from the example by design.
overridden_settings="$(awk -F= '
    FILENAME == ARGV[1] && /^[A-Za-z_][A-Za-z0-9_]*=/ {
        name = $1
        sub(/^[^=]*=/, "")
        example[name] = $0
        next
    }
    /^[A-Za-z_][A-Za-z0-9_]*=/ {
        name = $1
        sub(/^[^=]*=/, "")
        if (name in example && example[name] != $0 \
                && name !~ /PASSWORD|SECRET|HASH|SCOPES/) {
            printf "  %s is %s here and %s in the example\n", name, $0, example[name]
        }
    }
' "${example_file}" "${environment_file}")"
if [ -n "${overridden_settings}" ]; then
    note "${environment_file} overrides these settings, and this script leaves each one alone.
  Check them first when a service refuses to start: a default this platform tightened looks
  like a working value here.
${overridden_settings}"
fi

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
    require_tool openssl "openssl is not on the path. It generates every password here."
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
    require_tool openssl \
        "openssl is not on the path. It generates the password each hash below covers."
    require_tool jshell "jshell is not on the path. Install Eclipse Temurin OpenJDK 25.0.4+7."
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

    # Written to a temporary file beside the destination and renamed onto it. mktemp creates
    # at mode 600 and creates rather than truncates, so no other account can hold the file
    # this script is about to fill; the rename is atomic inside one directory, so a reader
    # sees either the previous file or the finished one and never a partial write; and the
    # trap removes the temporary file if any command below fails under set -e.
    credentials_temp="$(mktemp "${credentials_file}.XXXXXX")"
    trap 'rm -f -- "${credentials_temp}"' EXIT
    chmod 600 "${credentials_temp}"

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
    } > "${credentials_temp}"

    # The rename carries the temporary file's mode onto the destination, replacing whatever
    # an earlier run left there, so nothing has to be narrowed afterwards.
    mv -f -- "${credentials_temp}" "${credentials_file}"
    trap - EXIT
    note "generated ${#identity_keys[@]} identity hashes; the passwords are in ${credentials_file}"
fi

remaining_placeholders="$(grep -c 'REPLACE-WITH\|REPLACE-THIS' "${environment_file}" || true)"
[ "${remaining_placeholders}" -eq 0 ] \
    || fail "${remaining_placeholders} placeholders remain in ${environment_file}"

# Completeness, checked separately from the placeholder count because the two failures look
# nothing alike. A placeholder is a value nobody has chosen yet; an absent key is a value
# Compose will refuse to interpolate, and it leaves no placeholder to count. The
# reconciliation above should make this unreachable, so reaching it means a name got past it,
# and the message says which.
mapfile -t unreconciled_keys < <(keys_missing_from "${environment_file}" "${example_file}")
[ "${#unreconciled_keys[@]}" -eq 0 ] || fail \
    "${environment_file} still declares nothing for ${#unreconciled_keys[@]} assignments
  ${example_file} requires, so docker compose will refuse to interpolate them:
  ${unreconciled_keys[*]}
Copy each one out of ${example_file}, or move ${environment_file} aside and run this again."

declared_count="$(declared_keys "${example_file}" | wc -l | tr -d ' ')"
note "${environment_file} carries no placeholder and declares all ${declared_count} assignments"
