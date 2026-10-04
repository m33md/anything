@echo off
rem Same app, drawn with OpenGL instead of DirectX. Try this one if the normal one looks blurry.
cd /d "%~dp0"
call :findjava || exit /b 1
start "" "%JAVAW%" --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED -Dfile.encoding=UTF-8 -Dskiko.renderApi=OPENGL -cp "%~dp0app\*" com.kolnovel.reader.MainKt
exit /b 0

:findjava
if exist "%~dp0runtime\bin\javaw.exe" goto havejava
rem First start: borrow the Java that Olympus Reader already has next to this folder.
if exist "%~dp0..\OlympusReader\runtime\bin\javaw.exe" (
  echo First start: copying Java from the OlympusReader folder, one moment...
  robocopy "%~dp0..\OlympusReader\runtime" "%~dp0runtime" /E /NFL /NDL /NJH /NJS /NP >nul
)
if exist "%~dp0runtime\bin\javaw.exe" goto havejava
where javaw >nul 2>nul && (set "JAVAW=javaw" & set "JAVA=java" & exit /b 0)
echo Java was not found. Put the "runtime" folder from Olympus Reader inside this folder.
pause
exit /b 1
:havejava
set "JAVAW=%~dp0runtime\bin\javaw.exe"
set "JAVA=%~dp0runtime\bin\java.exe"
exit /b 0
