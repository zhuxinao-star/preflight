@echo off
rem ============================================================
rem PreFlight status (Windows)
rem Asks one question and answers it truthfully: is anything listening on the
rem service port, and is the app healthy? Identified by the PORT, not by the
rem process name - a name match cannot tell which java.exe it is.
rem Anchors come from _env.bat. No powershell (blocked on this machine).
rem ============================================================
call "%~dp0_env.bat"

call "%BIN_DIR%_env.bat" :pf_pid_by_port
if defined PF_PID goto :show

echo NOT RUNNING - nothing listens on port %PF_PORT%.
echo   start: dev\bin\start.bat
exit /b 0

:show
call "%BIN_DIR%_env.bat" :pf_image_of_pid
echo RUNNING, port %PF_PORT%, PID %PF_PID% (%PF_IMG%)
if /i not "%PF_IMG%"=="java.exe" echo [WARN] the port is held by something other than java - probably not PreFlight.
echo - health %PF_HEALTH_URL%:
curl -s -m 3 %PF_HEALTH_URL% || echo no answer from that URL
echo.
exit /b 0
