@echo off
rem Stop the local setup from cmd.exe, PowerShell or Explorer: runs stoplocalsetup.sh with Git for Windows' bash.
rem Options are passed through:  stoplocalsetup.bat           remove containers, keep data volumes
rem                              stoplocalsetup.bat --pause   only stop containers
rem Plain "bash" is not used on purpose: on Windows it usually resolves to WSL (System32\bash.exe).
setlocal
set "GITBASH="
for /f "delims=" %%G in ('where git 2^>nul') do if not defined GITBASH if exist "%%~dpG..\bin\bash.exe" set "GITBASH=%%~dpG..\bin\bash.exe"
if not defined GITBASH if exist "%ProgramFiles%\Git\bin\bash.exe" set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined GITBASH (
  echo Git for Windows bash.exe not found. Install Git for Windows, or run stoplocalsetup.sh from Git Bash or WSL. 1>&2
  exit /b 1
)
rem Forward slashes so the script's "$(dirname "$0")" resolves to the repo root from any working directory.
set "SCRIPT=%~dp0stoplocalsetup.sh"
"%GITBASH%" "%SCRIPT:\=/%" %*
exit /b %ERRORLEVEL%
