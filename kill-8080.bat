@echo off
setlocal enabledelayedexpansion

set "PORT=8080"
set "FOUND="

echo Checking port %PORT%...

for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do (
    set "PID=%%P"
    if not "!PID!"=="" (
        set "FOUND=1"
        set "PNAME="
        for /f "tokens=1 delims=," %%N in ('tasklist /FI "PID eq !PID!" /FO CSV /NH 2^>NUL') do (
            set "PNAME=%%~N"
        )
        echo Killing process on port %PORT%: !PNAME! PID !PID!
        taskkill /PID !PID! /F >NUL
    )
)

if not defined FOUND (
    echo Port %PORT% is free.
) else (
    echo Port %PORT% has been released.
)

endlocal
