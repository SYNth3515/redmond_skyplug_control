@echo off
cd /d "%~dp0"
set PY=python
where py >nul 2>nul
if not errorlevel 1 set PY=py -3
%PY% --version
if errorlevel 1 goto nopy
%PY% -m pip install --quiet bleak
%PY% rsp103s_app.py
echo.
pause
goto :eof
:nopy
echo Python not found. Install it from python.org and tick "Add to PATH".
pause
