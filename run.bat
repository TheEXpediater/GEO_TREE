@echo off
rem GEO Tree: start the local development backend (MongoDB + FastAPI) with Docker Desktop.
rem Double-click this file, or run it from a terminal. It never deletes Docker volumes.
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_geo_tree.ps1" %*
set "GEO_TREE_EXIT=%ERRORLEVEL%"
echo.
rem Keep the window open when double-clicked so the addresses can be read.
pause
exit /b %GEO_TREE_EXIT%
