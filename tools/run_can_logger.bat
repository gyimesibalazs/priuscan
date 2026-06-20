@echo off
REM PriusCAN CAN logger launcher (Windows). Double-click, then type the COM port.
REM Lists the available COM ports first.
echo === Available COM ports ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0can_logger.ps1" -Port list
echo.
set /p PORT=Enter COM port (e.g. COM5):
echo.
echo Starting logger on %PORT%  (Ctrl-C to stop)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0can_logger.ps1" -Port %PORT%
echo.
pause
