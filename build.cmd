@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>&1
if %errorlevel% equ 0 (
    py -3 tools\build.py build %*
) else (
    python tools\build.py build %*
)
set "RESULT=%errorlevel%"
if not "%RESULT%"=="0" echo Build failed. Read the error above.
exit /b %RESULT%
