@echo off
cd /d "%~dp0"
where python >nul 2>nul || (echo Python not found on PATH & pause & exit /b 1)
start "vLLM Dashboard" python server.py
