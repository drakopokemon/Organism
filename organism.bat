@echo off
rem ORGANISM one-command launch (Windows). Auto-bootstrap JDK 21+ esli nuzhno. organism.bat [nodes]
setlocal enabledelayedexpansion
cd /d "%~dp0"
where javac >nul 2>nul || (echo [organism] Nuzhen JDK dlya bootstrap: https://adoptium.net & exit /b 1)
if not exist out_boot mkdir out_boot
javac -encoding UTF-8 -nowarn -d out_boot src\main\java\dev\organism\tools\JdkBootstrap.java || (echo [organism] bootstrap compile failed & exit /b 1)
set "JDKHOME="
for /f "delims=" %%h in ('java -cp out_boot dev.organism.tools.JdkBootstrap "%CD%\.organism\jdk"') do set "JDKHOME=%%h"
if not defined JDKHOME (echo [organism] JDK 21+ ne poluchen. Postav: winget install EclipseAdoptium.Temurin.21.JDK & exit /b 1)
set "JAVAC=%JDKHOME%\bin\javac.exe"
set "JAVA=%JDKHOME%\bin\java.exe"
for /f "tokens=2" %%v in ('"%JAVAC%" -version 2^>^&1') do set VER=%%v
for /f "delims=.-" %%m in ("%VER%") do set MAJ=%%m
set CFLAGS=
set RFLAGS=
if %MAJ% LSS 25 set CFLAGS=--enable-preview --release %MAJ%
if %MAJ% LSS 25 set RFLAGS=--enable-preview
echo [organism] compiling (JDK %MAJ% iz %JDKHOME%)...
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\java\*.java > sources.txt
"%JAVAC%" %CFLAGS% -encoding UTF-8 -nowarn -d out @sources.txt || (echo [organism] compile failed & exit /b 1)
del sources.txt
xcopy /e /i /q /y src\main\resources out >nul
"%JAVA%" %RFLAGS% -Dstdout.encoding=UTF-8 -cp out dev.organism.Launcher %*
endlocal
