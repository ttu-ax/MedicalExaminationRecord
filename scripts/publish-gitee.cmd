@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-gitee.ps1" %*
set "exitCode=%ERRORLEVEL%"
echo.
if not "%exitCode%"=="0" (
    echo Gitee publishing failed with exit code %exitCode%.
    echo If the APK was built, rerun with -Resume to retry this version.
) else (
    echo Gitee Release publishing completed.
)
echo.
pause
exit /b %exitCode%
