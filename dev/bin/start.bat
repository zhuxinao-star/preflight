@echo off
rem ============================================================
rem PreFlight start script (Windows)
rem Layout: dev\bin = this script | dev\lib = deployment artifacts
rem         workspace root = data\ and logs\  (the app opens them by RELATIVE path,
rem         see application.yml: jdbc:sqlite:data/preflight.db, logging.file.name=logs/preflight.log)
rem         => this script cd's to the workspace root before launching java.
rem Anchors (jar / port / log / java order) come from _env.bat - do not duplicate them here.
rem No window is left behind: the real launch happens in a hidden cmd started by
rem cscript + run-hidden.vbs. powershell.exe is NOT used anywhere - on this machine
rem starting it from a batch file fails with "Access is denied" (rc=5).
rem The final line is only printed after the port is actually listening.
rem ============================================================
call "%~dp0_env.bat"

call "%BIN_DIR%_env.bat" :pf_pid_by_port
if not defined PF_PID goto :check_jar
echo [ALREADY RUNNING] port %PF_PORT% is served by PID %PF_PID%.
echo   URL: http://localhost:%PF_PORT%   stop: dev\bin\stop.bat
exit /b 0

:check_jar
if exist "%PF_JAR%" goto :dirs
echo [ERROR] jar not found: %PF_JAR%
echo         run  dev\bin\build.bat package  first
exit /b 1

:dirs
if not exist "%ROOT_DIR%\logs" mkdir "%ROOT_DIR%\logs"
if not exist "%ROOT_DIR%\data" mkdir "%ROOT_DIR%\data"
if not exist "%PF_DRIVERS%" mkdir "%PF_DRIVERS%"
cd /d "%ROOT_DIR%" || exit /b 1

"%PF_JAVA%" -version >nul 2>&1
if not errorlevel 1 goto :which_pass
echo [ERROR] cannot execute java: %PF_JAVA%
echo         put a JDK 21 at dev\jre\ or tools\jdk-*, or make sure java is on PATH
exit /b 1

:which_pass
if defined PF_HIDDEN goto :launch

rem ---- pass 1 (the window you see): hide ourselves, then wait for the port ----
set "PF_SELF=%~f0"
set "PF_HIDDEN=1"
set "PF_WINDOW=hidden"
cscript //nologo "%BIN_DIR%run-hidden.vbs" "%PF_SELF%"
if not errorlevel 1 goto :wait_up
echo [WARN] cscript could not launch it hidden (rc=%ERRORLEVEL%).
echo [WARN] falling back to a MINIMIZED window - it stays on the taskbar while the service runs.
start "preflight-server" /min "%ComSpec%" /c "%PF_SELF%"
set "PF_WINDOW=minimized"

:wait_up
set "PF_TRIES=0"
:poll
call "%BIN_DIR%_env.bat" :pf_pid_by_port
if defined PF_PID goto :up
set /a PF_TRIES+=1
if %PF_TRIES% LSS 20 goto :wait2
echo [FAILED] nothing listens on port %PF_PORT% after about 40 seconds - the service did NOT start.
echo          look at the tail of: %PF_LOG%
echo          then run status:      dev\bin\status.bat
exit /b 1

:wait2
ping -n 3 127.0.0.1 >nul
goto :poll

:up
echo [OK] PreFlight is up on port %PF_PORT%  (PID %PF_PID%, window %PF_WINDOW%)
echo   URL: http://localhost:%PF_PORT%   log: logs\stdout.log
echo   stop: dev\bin\stop.bat   status: dev\bin\status.bat
curl -s -m 3 %PF_HEALTH_URL%
echo.
exit /b 0

:launch
rem ---- pass 2 (hidden console): this is the line that actually starts java ----
rem Keep the log a single merged file (2>&1) - see docs/06 round 42.
start "preflight-server" /b "%PF_JAVA%" -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -XX:-OmitStackTraceInFastThrow -Dloader.path="%PF_DRIVERS%" -cp "%PF_JAR%" org.springframework.boot.loader.launch.PropertiesLauncher >> "%PF_LOG%" 2>&1
exit /b 0
