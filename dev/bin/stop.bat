@echo off
rem ============================================================
rem PreFlight stop (Windows)
rem Kills the process that owns the service port, and refuses when that
rem process is not java - so this script can never kill an unrelated program.
rem Anchors come from _env.bat. No powershell (blocked on this machine).
rem ============================================================
call "%~dp0_env.bat"

call "%BIN_DIR%_env.bat" :pf_pid_by_port
if defined PF_PID goto :who
echo NOT RUNNING - nothing listens on port %PF_PORT%, nothing to stop.
exit /b 0

:who
call "%BIN_DIR%_env.bat" :pf_image_of_pid
if /i "%PF_IMG%"=="java.exe" goto :kill
echo [REFUSED] port %PF_PORT% is held by "%PF_IMG%" (PID %PF_PID%), that is not PreFlight.
echo           Nothing was killed.
exit /b 1

:kill
echo Stopping PID %PF_PID% (%PF_IMG%)...
taskkill /PID %PF_PID% /F >nul 2>&1

set "PF_TRIES=0"
:verify
call "%BIN_DIR%_env.bat" :pf_pid_by_port
if not defined PF_PID goto :stopped
set /a PF_TRIES+=1
if %PF_TRIES% LSS 10 goto :pause
echo [FAILED] PID %PF_PID% still listens on port %PF_PORT% - taskkill did not take effect.
exit /b 1

:pause
ping -n 2 127.0.0.1 >nul
goto :verify

:stopped
echo [OK] stopped, port %PF_PORT% is free.
exit /b 0
