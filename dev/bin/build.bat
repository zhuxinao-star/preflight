@echo off
rem ============================================================
rem PreFlight build script (Windows dev machine)
rem Prefers the JDK/Maven bundled under tools\ (process-level env only, no system changes);
rem tools\ is NOT published with the source, so on a fresh clone it falls back to java/mvn on PATH.
rem Sources live in src\preflight-server\ (import that pom into IntelliJ IDEA directly).
rem The produced jar is copied into dev\lib\preflight-server.jar, which is what start.bat runs.
rem Usage: dev\bin\build.bat                 (= compile)
rem        dev\bin\build.bat package         (= fat jar + copy to dev\lib)
rem Note: this repo currently has NO src\test - "package" compiles main sources only,
rem       so -DskipTests changes nothing here (kept for when tests are added back).
rem ============================================================
setlocal
set "BIN_DIR=%~dp0"
for %%I in ("%BIN_DIR%..")     do set "DEV_DIR=%%~fI"
for %%I in ("%BIN_DIR%..\..")  do set "ROOT_DIR=%%~fI"

set "SRC_DIR=%ROOT_DIR%\src\preflight-server"
set "M2_REPO=%ROOT_DIR%\tools\m2-repo"
set "SETTINGS=%ROOT_DIR%\config\maven-settings.xml"

set "JAVA_HOME="
for /d %%D in ("%ROOT_DIR%\tools\jdk-*") do set "JAVA_HOME=%%D"
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] Bundled JDK is broken - %JAVA_HOME%\bin\java.exe not found.
    exit /b 1
)

set "MVN_CMD="
for /d %%D in ("%ROOT_DIR%\tools\apache-maven-*") do set "MVN_CMD=%%D\bin\mvn.cmd"
if not defined MVN_CMD set "MVN_CMD=mvn"

echo Using JDK    : %JAVA_BIN%
echo Using Maven  : %MVN_CMD%
echo Using pom    : %SRC_DIR%\pom.xml

rem The bundled offline dependency repo is not published; fall back to the machine default ~/.m2.
if exist "%M2_REPO%\." (
    echo Local repo   : %M2_REPO%
    "%JAVA_BIN%" -version
    call "%MVN_CMD%" -B -s "%SETTINGS%" -Dmaven.repo.local="%M2_REPO%" -f "%SRC_DIR%\pom.xml" %*
) else (
    echo Local repo   : default ~/.m2 - %M2_REPO% not present, first build needs network.
    "%JAVA_BIN%" -version
    call "%MVN_CMD%" -B -s "%SETTINGS%" -f "%SRC_DIR%\pom.xml" %*
)
if errorlevel 1 exit /b 1

rem Deployment artifacts stay in dev\: copy the freshly built jar to its single runtime location.
set "BUILT=%SRC_DIR%\target\preflight-server-0.1.0-SNAPSHOT.jar"
if exist "%BUILT%" (
    copy /Y "%BUILT%" "%DEV_DIR%\lib\preflight-server.jar" >nul
    if errorlevel 1 (
        echo [WARN] built ok but copy to dev\lib failed - the running service holds the jar. Run dev\bin\stop.bat, then re-run this.
    ) else (
        echo [OK] deployed -^> %DEV_DIR%\lib\preflight-server.jar
    )
)
endlocal
