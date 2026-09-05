#!/usr/bin/env bash
# Upload one built jar to the Modrinth project.
#
#   MODRINTH_TOKEN=xxx MODRINTH_PROJECT_ID=abcd1234 \
#     ./scripts/modrinth-upload.sh <jar> <minecraft-version> <changelog-file> [release-type]
#
# Normally run for you by .github/workflows/modrinth.yml when a GitHub release is published, so
# publishing to GitHub publishes to Modrinth as well as CurseForge. Runnable by hand for a re-upload.
#
# Unlike CurseForge, Modrinth takes game versions by NAME, so no numeric ID lookup is needed. What it
# does take that CurseForge will not is a LIST of them on one file, which is the whole reason this
# script bothers with ranges: the 26.1 jar declares [26.1,26.2), so it should appear for someone
# filtering 26.1 or 26.1.1, not only for the 26.1.2 it happened to be built against.
#
# Optional environment:
#   MODRINTH_VERSION_RANGE   Maven-style range, e.g. "[26.1,26.2)" — expanded against the versions
#                            Modrinth actually lists. The workflow reads this from the branch that
#                            built the jar.
#   MODRINTH_GAME_VERSIONS   Explicit comma-separated list; overrides the range entirely.
#   MODRINTH_DEBUG           Print the metadata (contains no credentials).
#
# API reference: https://docs.modrinth.com/api/operations/createversion/
set -euo pipefail

API="https://api.modrinth.com/v2"

JAR="${1:?usage: modrinth-upload.sh <jar> <minecraft-version> <changelog-file> [release-type]}"
MC_VERSION="${2:?missing minecraft version, e.g. 26.2}"
CHANGELOG_FILE="${3:?missing changelog file}"
RELEASE_TYPE="${4:-release}"

# No apostrophes or quote characters in these messages: bash parses quoting inside ${var:?word}
# even within double quotes, so a stray one silently swallows the rest of the script.
: "${MODRINTH_TOKEN:?set MODRINTH_TOKEN — create one at https://modrinth.com/settings/pats with the Create versions scope}"
: "${MODRINTH_PROJECT_ID:?set MODRINTH_PROJECT_ID — the project ID or slug, from its settings page}"

[ -f "$JAR" ] || { echo "!! No such jar: $JAR" >&2; exit 1; }
[ -f "$CHANGELOG_FILE" ] || { echo "!! No such changelog: $CHANGELOG_FILE" >&2; exit 1; }

# Modrinth asks for a User-Agent that identifies the caller, and rate-limits anonymous-looking ones
# harder. https://docs.modrinth.com/api/#user-agents
UA="Sablednah/Chronicler (github.com/Sablednah/Chronicler)"

# --- game versions ------------------------------------------------------------------------------

# sort -V puts version strings in version order, so "is this one at least that one" is a one-liner.
ver_le() { [ "$1" = "$2" ] || [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" = "$1" ]; }
ver_lt() { [ "$1" != "$2" ] && ver_le "$1" "$2"; }

echo ">> Asking Modrinth which game versions it knows"
VERSIONS_JSON="$(curl -sS --max-time 120 -H "User-Agent: $UA" "$API/tag/game_version")"
if ! jq -e 'type == "array"' >/dev/null 2>&1 <<<"$VERSIONS_JSON"; then
    echo "!! Unexpected response from $API/tag/game_version" >&2
    head -c 400 <<<"$VERSIONS_JSON" >&2; echo >&2
    exit 1
fi
# Releases only: snapshots and pre-releases are listed too, and a mod jar has no business claiming
# a snapshot it was never built against.
mapfile -t KNOWN < <(jq -r 'map(select(.version_type == "release")) | .[].version' <<<"$VERSIONS_JSON")

known() { local v="$1" k; for k in "${KNOWN[@]}"; do [ "$k" = "$v" ] && return 0; done; return 1; }

# Expand a Maven-style range — [a,b) (a,b] [a,b] and the open-ended forms — into every release
# version Modrinth lists inside it. A bare "[a]" means that one version.
expand_range() {
    local range="${1// /}" lo hi lo_inc hi_inc body v out=()
    case "${range:0:1}" in '[') lo_inc=1 ;; '(') lo_inc=0 ;; *) return 1 ;; esac
    case "${range: -1}" in ']') hi_inc=1 ;; ')') hi_inc=0 ;; *) return 1 ;; esac
    body="${range:1:${#range}-2}"
    if [[ "$body" == *,* ]]; then lo="${body%%,*}"; hi="${body#*,}"; else lo="$body"; hi="$body"; hi_inc=1; fi
    for v in "${KNOWN[@]}"; do
        if [ -n "$lo" ]; then
            if [ "$lo_inc" = 1 ]; then ver_le "$lo" "$v" || continue; else ver_lt "$lo" "$v" || continue; fi
        fi
        if [ -n "$hi" ]; then
            if [ "$hi_inc" = 1 ]; then ver_le "$v" "$hi" || continue; else ver_lt "$v" "$hi" || continue; fi
        fi
        out+=("$v")
    done
    [ ${#out[@]} -gt 0 ] || return 1
    printf '%s\n' "${out[@]}" | sort -V
}

if [ -n "${MODRINTH_GAME_VERSIONS:-}" ]; then
    IFS=',' read -r -a GAME_VERSIONS <<<"${MODRINTH_GAME_VERSIONS// /}"
    echo "   Using the explicit list from MODRINTH_GAME_VERSIONS"
elif [ -n "${MODRINTH_VERSION_RANGE:-}" ]; then
    GAME_VERSIONS=()
    mapfile -t GAME_VERSIONS < <(expand_range "$MODRINTH_VERSION_RANGE" || true)
    if [ ${#GAME_VERSIONS[@]} -gt 0 ]; then
        echo "   Range $MODRINTH_VERSION_RANGE covers ${#GAME_VERSIONS[@]} released version(s)"
    else
        # A range Modrinth has nothing inside is not fatal on its own — the exact build version is
        # still the truth, and is checked below.
        echo "!! Warning: no released version falls inside '$MODRINTH_VERSION_RANGE'; using $MC_VERSION alone." >&2
        GAME_VERSIONS=("$MC_VERSION")
    fi
else
    GAME_VERSIONS=("$MC_VERSION")
fi

# Whatever the range said, the version actually built against must be in the list and must be one
# Modrinth knows. This is the check that fails for a few days after a Minecraft release.
known "$MC_VERSION" || {
    echo "!! Modrinth does not list Minecraft '$MC_VERSION' yet." >&2
    echo "!! Closest names it does know:" >&2
    printf '%s\n' "${KNOWN[@]}" | grep "^${MC_VERSION%%.*}" | sort -V | tail -12 | sed 's/^/     /' >&2
    exit 1
}
KEPT=()
for v in "${GAME_VERSIONS[@]}"; do
    if known "$v"; then
        KEPT+=("$v")
    else
        echo "!! Warning: Modrinth does not list Minecraft '$v' — dropping it from the list." >&2
    fi
done
GAME_VERSIONS=("${KEPT[@]}")
[[ " ${GAME_VERSIONS[*]} " == *" $MC_VERSION "* ]] || GAME_VERSIONS+=("$MC_VERSION")
echo "   Game versions: ${GAME_VERSIONS[*]}"

# --- names --------------------------------------------------------------------------------------

# chronicler-2.5.1+mc26.2.jar -> 2.5.1, falling back to the whole basename if a jar is ever named
# differently. The file itself keeps its original name either way.
MOD_VERSION="$(basename "$JAR" .jar | sed -n 's/^chronicler-\(.*\)+mc.*$/\1/p')"
if [ -n "$MOD_VERSION" ]; then
    DISPLAY_NAME="Chronicler $MOD_VERSION / MC $MC_VERSION"
    # Modrinth requires version_number to be unique within the project, so it cannot be the bare mod
    # version: three branches publish 2.5.1. The +mc suffix the jars already carry makes it unique.
    VERSION_NUMBER="$MOD_VERSION+mc$MC_VERSION"
else
    DISPLAY_NAME="$(basename "$JAR" .jar)"
    VERSION_NUMBER="$(basename "$JAR" .jar)"
fi

METADATA="$(jq -n \
    --rawfile changelog "$CHANGELOG_FILE" \
    --arg name "$DISPLAY_NAME" \
    --arg versionNumber "$VERSION_NUMBER" \
    --arg versionType "$RELEASE_TYPE" \
    --arg projectId "$MODRINTH_PROJECT_ID" \
    --argjson gameVersions "$(printf '%s\n' "${GAME_VERSIONS[@]}" | jq -R . | jq -sc .)" \
    '{name: $name, version_number: $versionNumber, changelog: $changelog,
      dependencies: [], game_versions: $gameVersions, version_type: $versionType,
      loaders: ["neoforge"], featured: false, status: "listed", project_id: $projectId,
      file_parts: ["file"], primary_file: "file"}')"

if [ -n "${MODRINTH_DEBUG:-}" ]; then
    # The metadata carries no credentials, so it is safe to print when diagnosing a rejection.
    echo ">> metadata:"
    jq . <<<"$METADATA" | sed 's/^/     /'
fi

# --- upload -------------------------------------------------------------------------------------

echo ">> Uploading $(basename "$JAR") to project $MODRINTH_PROJECT_ID as $VERSION_NUMBER ($RELEASE_TYPE)"
# --form-string, not -F: curl gives ';', a leading '@' and a leading '<' special meaning inside an
# -F value, so a changelog containing any of them silently mangles the JSON. The jar still needs -F,
# since @ there is the point.
RESPONSE="$(curl -sS --max-time 600 -w '\n%{http_code}' \
    -H "Authorization: $MODRINTH_TOKEN" \
    -H "User-Agent: $UA" \
    --form-string "data=$METADATA" \
    -F "file=@$JAR" \
    "$API/version")"

STATUS="$(tail -n1 <<<"$RESPONSE")"
BODY="$(sed '$d' <<<"$RESPONSE")"

if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then
    VERSION_ID="$(jq -r '.id // empty' <<<"$BODY" 2>/dev/null || true)"
    echo ">> Published${VERSION_ID:+ as version $VERSION_ID}"
    # Unlike CurseForge there is no moderation queue for a version on an already-approved project:
    # a 200 here means it is live and downloadable now.
    echo "   https://modrinth.com/mod/$MODRINTH_PROJECT_ID/version/$VERSION_NUMBER"
    exit 0
fi

echo "!! Modrinth rejected the upload (HTTP $STATUS)" >&2
echo "$BODY" >&2
if [ "$STATUS" = "400" ] && grep -q "version number" <<<"$BODY"; then
    echo "!! That usually means $VERSION_NUMBER is already published — Modrinth will not take the" >&2
    echo "!! same version_number twice. Delete the existing version, or bump mod_version." >&2
fi
exit 1
