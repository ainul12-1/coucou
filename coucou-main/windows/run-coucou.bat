@echo off
title Coucou Launcher
echo Memulai Coucou Antigravity Bridge...
cd /d "%~dp0bridge"
start /b "" node server.js
echo Memulai Coucou Native Notch...
start "" "C:\Users\ainul\AppData\Local\Coucou\coucou.exe"
exit
