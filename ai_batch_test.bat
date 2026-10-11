@echo off
rem GEO Tree: evaluate held-out leaf photos in ai_test_input\<class>\ against the LOCAL AI backend.
rem   ai_batch_test.bat                  write a report to ai_test_results\<timestamp>\
rem   ai_batch_test.bat --cleanup-input  ...then delete ai_test_input\ only if the run fully succeeded
rem It never uploads training data, never talks to a hosted server and never deletes Docker volumes.
setlocal
cd /d "%~dp0"
set "GEO_TREE_PY=backend\.venv\Scripts\python.exe"
if not exist "%GEO_TREE_PY%" set "GEO_TREE_PY=python"
"%GEO_TREE_PY%" backend\scripts\ai_batch_test.py %*
exit /b %ERRORLEVEL%
