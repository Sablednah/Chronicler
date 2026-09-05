@echo off
rem ---------------------------------------------------------------------
rem TestClient.cmd - launch the TestBuddy dev client for multiplayer
rem testing. Double-click me, or from PowerShell: .\TestClient.cmd
rem
rem Runs from WINDOWS because `./gradlew runClientBuddy` from WSL goes
rem through WSLg and the window often never appears. Pair with the dev
rem server started from WSL (`./gradlew runServer`); the buddy auto-joins
rem 127.0.0.1:<dev_server_port from gradle.properties>.
rem ---------------------------------------------------------------------
setlocal
cd /d "%~dp0"

rem CurseForge's java-runtime-delta (21) is a full JDK; java-runtime-epsilon
rem (25) is a JRE with no javac and fails deep inside NeoForm. Gradle ALWAYS
rem runs on the 21 -- it is only the launcher; the toolchain provisions
rem whatever Java the branch needs. Do not add a version switch here.
set "JAVA_HOME=%USERPROFILE%\curseforge\minecraft\Install\runtime\java-runtime-delta\windows-x64\java-runtime-delta"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Could not find CurseForge's JDK 21 at %JAVA_HOME%
    echo Edit JAVA_HOME in this file to point at any JDK 21.
    pause
    exit /b 1
)

rem Mirror the dev server's mods folder into the client's: NeoForge refuses a
rem client whose required-mod list disagrees, saying only "bad network protocol".
if exist "run\mods" (
    if not exist "runBuddy\mods" mkdir "runBuddy\mods"
    del /q "runBuddy\mods\*.jar" >nul 2>&1
    copy /y "run\mods\*.jar" "runBuddy\mods\" >nul 2>&1
)

rem Mute every sound category and never pause on lost focus: the buddy is
rem driven from a script while its owner may be on a call, and an unattended
rem client sitting on PauseScreen renders no frame at all.
if not exist "runBuddy" mkdir "runBuddy"
powershell -NoProfile -Command "$f='runBuddy\options.txt'; $lines = @(); if (Test-Path $f) { $lines = Get-Content $f }; $lines = $lines | Where-Object { $_ -notmatch '^(soundCategory_|pauseOnLostFocus)' }; $lines += 'pauseOnLostFocus:false'; foreach ($c in 'master','music','record','weather','block','hostile','neutral','player','ambient','voice','ui') { $lines += ('soundCategory_' + $c + ':0.0') }; Set-Content $f $lines"

echo Starting TestBuddy dev client (first run compiles - be patient)...
call gradlew.bat runClientBuddy --project-cache-dir .gradle-win -PwinClient=TestBuddy
pause
