@echo off
rem Show PreFlight process and health status
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*preflight-server*' }; if ($p) { Write-Host ('RUNNING, PID: ' + ($p.ProcessId -join ',')) } else { Write-Host 'NOT RUNNING' }"
curl -s -m 2 http://localhost:8320/actuator/health
echo.
