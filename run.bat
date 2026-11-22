@echo off
rem Сборка и запуск на Windows (JDK 21+). Аргументы передаются приложению: run.bat --selftest
setlocal enabledelayedexpansion
cd /d "%~dp0"
where javac >nul 2>nul || (echo Нужен JDK 21+ с javac в PATH: https://adoptium.net & exit /b 1)
set FLAGS=--enable-preview
for /f "tokens=2" %%v in ('javac -version 2^>^&1') do set VER=%%v
for /f "delims=." %%m in ("%VER%") do if %%m GEQ 25 set FLAGS=
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\java\*.java > sources.txt
javac %FLAGS% -Xlint:-preview -nowarn -encoding UTF-8 -d out @sources.txt || exit /b 1
del sources.txt
xcopy /e /i /q /y src\main\resources out >nul
java -Dstdout.encoding=UTF-8 %FLAGS% -cp out dev.organism.Main %*
