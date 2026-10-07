@echo off
setlocal
cd /d "%~dp0.."

echo.
echo ============================================================
echo  Nuntra - generate fixed debug keystore
echo ============================================================
echo.
echo  Why this exists:
echo    Without a FIXED signature, every CI build is signed with a
echo    brand new key, so Android refuses to install over the old
echo    app. You would have to uninstall first, which wipes all
echo    saved data (tags, watched contacts, theme...).
echo.
echo  IMPORTANT - no local JDK?
echo    Do NOT use this script. Run the GitHub workflow instead:
echo      Actions  -  Generate Debug Keystore  -  Run workflow
echo    then download the artifact and unzip debug.keystore into
echo      app\keystore\debug.keystore
echo.

set "KS=app\keystore\debug.keystore"

if exist "%KS%" goto :already

rem ---- locate keytool: PATH first, then JAVA_HOME, then Android Studio JBR ----
set "KEYTOOL="
for %%K in (keytool.exe) do if not defined KEYTOOL set "KEYTOOL=%%~$PATH:K"
if not defined KEYTOOL if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
if not defined KEYTOOL if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\keytool.exe" set "KEYTOOL=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\keytool.exe"
if not defined KEYTOOL if exist "%LOCALAPPDATA%\Programs\Android Studio1\jbr\bin\keytool.exe" set "KEYTOOL=%LOCALAPPDATA%\Programs\Android Studio1\jbr\bin\keytool.exe"
if not defined KEYTOOL if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe" set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe"
if not defined KEYTOOL if exist "%ProgramFiles%\Android\Android Studio1\jbr\bin\keytool.exe" set "KEYTOOL=%ProgramFiles%\Android\Android Studio1\jbr\bin\keytool.exe"

if defined KEYTOOL goto :havekeytool
echo [ERROR] keytool not found on this machine.
echo.
echo   Option A - use the cloud workflow, no JDK needed:
echo     GitHub  -  Actions  -  Generate Debug Keystore  -  Run workflow
echo.
echo   Option B - install a JDK 17 and set JAVA_HOME, then rerun.
echo.
echo   Option C - open the project in Android Studio and use
echo     Build  -  Generate Signed Bundle / APK
echo.
exit /b 1

:havekeytool
echo [info] using keytool: %KEYTOOL%

if not exist "app\keystore" mkdir "app\keystore"

"%KEYTOOL%" -genkeypair -v -keystore "%KS%" -storetype PKCS12 -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=USA"

if not exist "%KS%" goto :failed
echo.
echo [OK] created %KS%
goto :next

:already
echo [skip] %KS% already exists.
echo        Delete it first if you really want to regenerate.

:next
echo.
echo ============================================================
echo  Next step
echo ============================================================
echo.
echo  Commit it so CI always signs with the same key:
echo.
echo      git add -f app/keystore/debug.keystore
echo      git commit -m "build: add fixed debug keystore"
echo      git push
echo.
echo  This is a DEBUG-only keystore. Its password is the public
echo  Android convention android / androiddebugkey, so it is NOT
echo  a secret. Never reuse it for a release build or for Play.
echo.
pause
exit /b 0

:failed
echo [ERROR] generation failed - keystore was not created.
echo         See the keytool output above for the reason.
echo.
pause
exit /b 1
