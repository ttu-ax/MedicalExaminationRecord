@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-gitee-release.ps1" %*
if errorlevel 1 (
    echo Gitee Release publishing failed. Fix the error and rerun this script.
) else (
    echo Gitee Release publishing completed.
)
pause
