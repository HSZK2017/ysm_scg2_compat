@echo off
REM Build the mod and run the offline mixin contract verification.
REM
REM A successful `build` proves it compiles. verify-mixin-targets.ps1 proves BOTH mixin
REM variants still match the YSM jar they target - which is the failure mode a compile
REM cannot see, because a soft injection that stops matching is silent by design
REM (defaultRequire 0).
REM
REM The obfuscated-release check needs that jar; set YSM_LEGACY_JAR to point at it.
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
if defined YSM_LEGACY_JAR (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\verify-mixin-targets.ps1" -LegacyYsmJar "%YSM_LEGACY_JAR%"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\verify-mixin-targets.ps1"
)
if errorlevel 1 (
    echo MIXIN CONTRACT VERIFICATION FAILED
    exit /b 1
)

echo.
echo OK. Artifact: build\libs\
endlocal