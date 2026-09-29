@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-release.ps1" %*
set "exitCode=%ERRORLEVEL%"
echo.
if not "%exitCode%"=="0" (
    echo Release failed with exit code %exitCode%.
    echo If the APK was built, run deploy-prepared-release.ps1 to retry the upload.
) else (
    echo Release published to https://acupofttu.top/.
)
echo.
pause
exit /b %exitCode%
