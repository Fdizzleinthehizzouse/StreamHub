@echo off
setlocal
title StreamHub installer
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-StreamHub.ps1"
if errorlevel 1 pause
