@echo off
rem ============================================================
rem PreFlight start script (Windows)
rem Layout: dev\bin = this script | dev\lib = deployment artifacts
rem         workspace root = data\ and logs\  (the app opens them by RELATIVE path,
rem         see application.yml: jdbc:sqlite:data/preflight.db, logging.file.name=logs/preflight.log)
rem         => this script cd's to the workspace root before launching java.
rem Java priority: dev\jre\ > tools\jdk-* > java on PATH. JAVA_HOME set in-process only.
rem ============================================================
setlocal
set "BIN_DIR=%~dp0"
for %%I in ("%BIN_DIR%..")     do set "DEV_DIR=%%~fI"
for %%I in ("%BIN_DIR%..\..")  do set "ROOT_DIR=%%~fI"

set "JAR=%DEV_DIR%\lib\preflight-server.jar"

set "JAVA="
if exist "%DEV_DIR%\jre\bin\java.exe" set "JAVA=%DEV_DIR%\jre\bin\java.exe"
if not defined JAVA for /d %%D in ("%ROOT_DIR%\tools\jdk-*") do set "JAVA=%%D\bin\java.exe"
if not defined JAVA set "JAVA=java"

if not exist "%JAR%" (
    echo [ERROR] jar not found: %JAR%  -  run dev\bin\build.bat package first
    exit /b 1
)

if not exist "%ROOT_DIR%\logs" mkdir "%ROOT_DIR%\logs"
if not exist "%ROOT_DIR%\data" mkdir "%ROOT_DIR%\data"
if not exist "%DEV_DIR%\lib\drivers" mkdir "%DEV_DIR%\lib\drivers"
cd /d "%ROOT_DIR%"
if errorlevel 1 exit /b 1

rem ------------------------------------------------------------
rem Run with NO leftover window (round 42, item 3).
rem Pass 1 (visible, from double-click): only validates, then relaunches THIS file
rem hidden; pass 2 (hidden console) is the one that actually starts java.
rem Two invariants: (a) log stays a single merged file logs\stdout.log (the java
rem line below does 2>&1), (b) java's command line must keep containing
rem "preflight-server" - bin\stop.bat and bin\status.bat match processes by it.
rem ------------------------------------------------------------
set "PF_SELF=%~f0"
if defined PF_HIDDEN goto :launch
powershell -NoProfile -Command "$env:PF_HIDDEN='1'; Start-Process -WindowStyle Hidden -FilePath $env:ComSpec -ArgumentList ('/c \"' + $env:PF_SELF + '\"')" >nul 2>&1
echo PreFlight moved to background - no window is left open.
echo   URL: http://localhost:8320   log: logs\stdout.log
echo   stop: dev\bin\stop.bat   status: dev\bin\status.bat
exit /b 0

:launch
rem PropertiesLauncher loads the external JDBC drivers under dev\lib\drivers (e.g. Dameng DmJdbcDriver18)
start "preflight-server" /b "%JAVA%" -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -XX:-OmitStackTraceInFastThrow -Dloader.path="%DEV_DIR%\lib\drivers" -cp "%JAR%" org.springframework.boot.loader.launch.PropertiesLauncher >> "%ROOT_DIR%\logs\stdout.log" 2>&1

echo PreFlight starting...  URL: http://localhost:8320   Log: logs\stdout.log
endlocal
