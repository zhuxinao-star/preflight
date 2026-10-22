@echo off
rem ============================================================
rem PreFlight scripts - shared anchors (the ONE place these paths exist)
rem Used by start.bat / stop.bat / status.bat / build.bat:
rem     call "%~dp0_env.bat"                     rem just sets the variables
rem     call "%BIN_DIR%_env.bat" :pf_pid_by_port rem also resolves PF_PID
rem     call "%BIN_DIR%_env.bat" :pf_image_of_pid rem resolves PF_IMG for PF_PID
rem No setlocal on purpose: the caller needs these variables.
rem Pure cmd / netstat / tasklist only. This machine refuses to start
rem powershell.exe from a batch file (rc=5, "Access is denied"), so nothing
rem here may depend on it - see docs/06 round 54.
rem ============================================================
set "BIN_DIR=%~dp0"
for %%I in ("%BIN_DIR%..")     do set "DEV_DIR=%%~fI"
for %%I in ("%BIN_DIR%..\..")  do set "ROOT_DIR=%%~fI"

set "PF_JAR=%DEV_DIR%\lib\preflight-server.jar"
set "PF_DRIVERS=%DEV_DIR%\lib\drivers"
set "PF_JRE_DIR=%DEV_DIR%\jre"
set "PF_LOG=%ROOT_DIR%\logs\stdout.log"

rem --- build toolchain (tools\ is not published with the source) ---
set "PF_SRC=%ROOT_DIR%\src\preflight-server"
set "PF_M2=%ROOT_DIR%\tools\m2-repo"
set "PF_SETTINGS=%ROOT_DIR%\config\maven-settings.xml"

rem --- JDK home: dev\jre\ > tools\jdk-* > (none: use java on PATH) ---
set "PF_JDK_HOME="
if exist "%PF_JRE_DIR%\bin\java.exe" set "PF_JDK_HOME=%PF_JRE_DIR%"
if not defined PF_JDK_HOME for /d %%D in ("%ROOT_DIR%\tools\jdk-*") do set "PF_JDK_HOME=%%D"
set "PF_JAVA=java"
if defined PF_JDK_HOME set "PF_JAVA=%PF_JDK_HOME%\bin\java.exe"

set "PF_MVN="
for /d %%D in ("%ROOT_DIR%\tools\apache-maven-*") do set "PF_MVN=%%D\bin\mvn.cmd"
if not defined PF_MVN set "PF_MVN=mvn"

rem --- served port: must match server.port in the ONE real config,
rem     src\preflight-server\src\main\resources\application.yml (packed into the jar) ---
set "PF_PORT=8320"
set "PF_HEALTH_URL=http://localhost:%PF_PORT%/actuator/health"

if not "%~1"=="" call %~1
exit /b 0

:pf_pid_by_port
rem Sets PF_PID to the process listening on %PF_PORT%; leaves it empty when none.
set "PF_PID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":%PF_PORT% " ^| findstr "LISTENING"') do set "PF_PID=%%P"
exit /b 0

:pf_image_of_pid
rem Sets PF_IMG to the image name of %PF_PID% (empty when unresolvable).
set "PF_IMG="
for /f "tokens=1 delims=," %%N in ('tasklist /FI "PID eq %PF_PID%" /FO CSV /NH') do set "PF_IMG=%%~N"
exit /b 0
