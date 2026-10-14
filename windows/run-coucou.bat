@echo off
title Coucou Launcher
echo Memulai Coucou Antigravity Bridge...
cd /d "%~dp0bridge"
start /b "" node server.js
echo Memulai Coucou Native Notch...
start "" "%LOCALAPPDATA%\Coucou\coucou.exe"
exit
