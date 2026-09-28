@echo off
REM Build the mod and run the offline mixin contract verification.
REM
REM A successful `build` proves it compiles. verify-mixin-targets.ps1 proves the two
REM injection targets still exist with the declared signatures in the shipped
REM Yes Steve Model jar - which is the failure mode a compile cannot see, because a
REM soft injection that stops matching is silent by design (defaultRequire 0).
setlocal

set "JAVA_HOME=E:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo === gradlew build ===
call gradlew.bat build --console=plain
if errorlevel 1 (
    echo BUILD FAILED
    exit /b 1
)

echo.
echo === mixin contract verification ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\verify-mixin-targets.ps1"
if errorlevel 1 (
    echo MIXIN CONTRACT VERIFICATION FAILED
    exit /b 1
)

echo.
echo OK. Artifact: build\libs\
endlocal
