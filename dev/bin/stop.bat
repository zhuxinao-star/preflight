@echo off
rem Stop PreFlight: match java.exe processes whose command line references the server jar
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*preflight-server*' } | ForEach-Object { Write-Host ('Stopping PID: ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }"
echo Done.
