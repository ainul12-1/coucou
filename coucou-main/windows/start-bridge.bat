@echo off
echo Starting Coucou Antigravity Bridge...
cd /d "%~dp0bridge"
start /b "" node server.js
echo Bridge started on http://127.0.0.1:9335!
