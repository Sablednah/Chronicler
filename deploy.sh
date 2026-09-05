#!/usr/bin/env bash
# Build Chronicler and copy the jar into a CurseForge instance.
#
# The target instance is chosen from the jar's Minecraft version, so it follows
# whichever version branch is checked out:
#
#   chronicler-0.1.0+mc1.21.11.jar  -> the 1.21.11 instance (default below)
#   chronicler-0.1.0+mc26.1.2.jar   -> the "26.1.2" instance
#   chronicler-0.1.0+mc26.2.jar     -> the "26.2" instance
#
# Usage:  ./deploy.sh
#         CHR_INSTANCE="/path/to/instance" ./deploy.sh    # override the target
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
INSTANCES="/mnt/c/Users/darre/curseforge/minecraft/Instances"

# Any JDK that can launch gradle will do; the toolchain provisions the rest.
for candidate in "$ROOT/tools/jdk21" \
                 "/home/sable/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2" \
                 "$ROOT/../MobHealth-Forge/tools/jdk21"; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
done
if [ -z "${JAVA_HOME:-}" ]; then
    echo "!! No JDK found. There is no system Java on this machine." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

echo ">> Building (JAVA_HOME=$JAVA_HOME)..."
"$ROOT/gradlew" build --console=plain -q

JAR="$(ls -t "$ROOT"/build/libs/chronicler-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true)"
[ -n "$JAR" ] || { echo "!! No built jar in build/libs" >&2; exit 1; }
JARNAME="$(basename "$JAR")"

MC_TAG=""
[[ "$JARNAME" =~ \+mc([0-9.]+)\.jar$ ]] && MC_TAG="${BASH_REMATCH[1]}"
case "$MC_TAG" in
    ""|1.21.11) TARGET="$INSTANCES/MobHealth - Forge" ;;  # the 1.21.11 fantasy/test instance
    *)          TARGET="$INSTANCES/$MC_TAG" ;;
esac
INSTANCE="${CHR_INSTANCE:-$TARGET}"
MODS="$INSTANCE/mods"
NAME="$(basename "$INSTANCE")"

[ -d "$MODS" ] || { echo "!! Instance mods folder not found: $MODS" >&2; exit 1; }

# REFUSE if that instance is running. Windows does NOT lock the jar, so the copy
# silently succeeds and the live JVM dies the moment it lazily loads a class it
# had not touched (NoClassDefFoundError <- ZipException: invalid LOC header).
RUNNING="$(powershell.exe -NoProfile -Command \
  "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' } | ForEach-Object { \
   \$m=[regex]::Match(\$_.CommandLine,'Instances\\\\([^\\\\\"\s]+)'); if (\$m.Success) { \$m.Groups[1].Value } }" \
  2>/dev/null | tr -d '\r' | sort -u || true)"

if echo "$RUNNING" | grep -qxF "$NAME"; then
    echo "!! '$NAME' is RUNNING. Refusing to overwrite a jar underneath a live game." >&2
    echo "!! Close Minecraft and run this again." >&2
    exit 1
fi

echo ">> Removing previous Chronicler jars from '$NAME'..."
rm -f "$MODS"/chronicler-*.jar

cp "$JAR" "$MODS/"

cmp -s "$JAR" "$MODS/$JARNAME" || { echo "!! Deployed jar does not match the build." >&2; exit 1; }
unzip -t "$MODS/$JARNAME" >/dev/null 2>&1 || { echo "!! Deployed jar is not a valid zip." >&2; exit 1; }

echo ">> Deployed $JARNAME ($(stat -c%s "$JAR") bytes) to '$NAME'"
echo ">> Launch that instance in CurseForge to test."
