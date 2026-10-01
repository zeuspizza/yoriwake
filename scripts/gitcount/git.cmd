@echo off
rem The Windows half of the counting shim. ProcessBuilder("git") finds `git.cmd` here, not `git`,
rem so a POSIX-only shim would be silently bypassed and the count would come back zero.
if "%YORIWAKE_GIT_LOG%"=="" set YORIWAKE_GIT_LOG=%TEMP%\yoriwake-git.log
echo %* >> "%YORIWAKE_GIT_LOG%"
if "%YORIWAKE_REAL_GIT%"=="" set YORIWAKE_REAL_GIT=git.exe
"%YORIWAKE_REAL_GIT%" %*
