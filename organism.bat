@echo off
rem ORGANISM one-command launch (Windows, JDK 21+): compile + keygen + secured cluster + GUI.
rem organism.bat [nodes]
setlocal
cd /d "%~dp0"
where javac >nul 2>nul || (echo [organism] Nuzhen JDK 21+ s javac v PATH: https://adoptium.net & exit /b 1)
for /f "tokens=2" %%v in ('javac -version 2^>^&1') do set VER=%%v
for /f "delims=.-" %%m in ("%VER%") do set MAJ=%%m
set CFLAGS=
set RFLAGS=
if %MAJ% LSS 25 set CFLAGS=--enable-preview --release %MAJ%
if %MAJ% LSS 25 set RFLAGS=--enable-preview
echo [organism] compiling (JDK %MAJ%)...
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\java\*.java > sources.txt
javac %CFLAGS% -encoding UTF-8 -nowarn -d out @sources.txt || (echo [organism] compile failed & exit /b 1)
del sources.txt
xcopy /e /i /q /y src\main\resources out >nul
java %RFLAGS% -Dstdout.encoding=UTF-8 -cp out dev.organism.Launcher %*
endlocal
