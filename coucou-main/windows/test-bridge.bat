@echo off
echo Checking Coucou Antigravity Bridge status...
powershell -NoProfile -Command "try { $res = Invoke-RestMethod -Uri 'http://127.0.0.1:9335/health' -TimeoutSec 3; Write-Host 'Bridge Status: OK'; Write-Host ('Connected to Antigravity IDE: ' + $res.connected); Write-Host ('CDP Port: ' + $res.port); } catch { Write-Host 'Bridge is NOT running. Run start-bridge.bat first!' -ForegroundColor Red }"
pause
