@echo off
rem Starts the novel reader with the Java that sits in this folder (no install needed).
cd /d "%~dp0"
call :check || exit /b 1
start "" "%JAVAW%" --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED -Dfile.encoding=UTF-8 -cp "%~dp0app\*" com.kolnovel.reader.MainKt
exit /b 0

:check
if not exist "%~dp0app\" (
  echo The "app" folder is missing. Right-click the zip, choose "Extract All...", then run this file from the extracted folder.
  pause
  exit /b 1
)
rem Java ships in the "runtime" folder. Older copies borrowed it from Olympus Reader, so look there too.
for %%R in ("%~dp0runtime" "%~dp0..\OlympusReader\runtime" "%USERPROFILE%\Desktop\OlympusReader\runtime" "%OneDrive%\Desktop\OlympusReader\runtime") do (
  if exist "%%~R\bin\javaw.exe" (
    set "JAVAW=%%~R\bin\javaw.exe"
    set "JAVA=%%~R\bin\java.exe"
    exit /b 0
  )
)
where javaw >nul 2>nul && (set "JAVAW=javaw" & set "JAVA=java" & exit /b 0)
echo Java was not found: the "runtime" folder is missing. Download the zip again and extract all of it.
pause
exit /b 1
