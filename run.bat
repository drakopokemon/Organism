@echo off
rem Сборка и запуск дашборд-симулятора на Windows (JDK 21+). Аргументы приложению: run.bat --selftest
rem Для продакшн-кластера с GUI используй organism.bat
setlocal
cd /d "%~dp0"
where javac >nul 2>nul || (echo Nuzhen JDK 21+ s javac v PATH: https://adoptium.net & exit /b 1)
for /f "tokens=2" %%v in ('javac -version 2^>^&1') do set VER=%%v
for /f "delims=.-" %%m in ("%VER%") do set MAJ=%%m
if %MAJ% LSS 21 (echo [run] ORGANISM trebuet JDK 21+ (virtual threads). U tebya %MAJ%. Postav: winget install EclipseAdoptium.Temurin.21.JDK  ili zapusti organism.bat (auto-bootstrap) & exit /b 1)
set CFLAGS=
set RFLAGS=
if %MAJ% LSS 25 set CFLAGS=--enable-preview --release %MAJ%
if %MAJ% LSS 25 set RFLAGS=--enable-preview
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\java\*.java > sources.txt
javac %CFLAGS% -Xlint:-preview -nowarn -encoding UTF-8 -d out @sources.txt || (echo compile failed & exit /b 1)
del sources.txt
xcopy /e /i /q /y src\main\resources out >nul
java -Dstdout.encoding=UTF-8 %RFLAGS% -cp out dev.organism.Main %*
endlocal
