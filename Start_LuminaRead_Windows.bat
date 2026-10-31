@echo off
setlocal
chcp 65001 >nul 2>&1
cd /d "%~dp0"

rem ============================================================
rem  LuminaRead launcher for Windows 10 / 11
rem  Requires Java 17 or newer (Adoptium Temurin / Microsoft / Oracle)
rem ============================================================

set "JAVA_OPTS=-Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true"

where java >nul 2>nul
if errorlevel 1 (
    echo.
    echo  [ERROR] Java was not found on this system.
    echo.
    echo  LuminaRead requires Java 17 or newer (LTS).
    echo  Please download and install Java 17+ from: https://adoptium.net/
    echo  Recommended: Eclipse Temurin JDK 17+ or 21+.
    echo.
    pause
    exit /b 1
)

if not exist "LuminaRead.jar" (
    echo.
    echo  [ERROR] LuminaRead.jar was not found in: %cd%
    echo  Please keep LuminaRead.jar and this .bat file in the same directory.
    echo.
    pause
    exit /b 1
)

where javaw >nul 2>nul
if errorlevel 1 (
    set "JAVA_RUNNER=java"
) else (
    set "JAVA_RUNNER=javaw"
)

start "" %JAVA_RUNNER% %JAVA_OPTS% -jar "LuminaRead.jar" %*
exit /b 0
