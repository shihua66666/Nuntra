@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0.."

echo.
echo ============================================================
echo  Nuntra - 生成固定 debug 签名密钥库
echo ============================================================
echo.
echo  为什么需要：不固定签名，每次 CI 构建的 APK 签名都不同，
echo  Android 会拒绝覆盖安装，必须先卸载 -^> 数据（标签/关注人/主题）全丢。
echo.

if exist "app\keystore\debug.keystore" (
    echo [跳过] app\keystore\debug.keystore 已存在。
    echo         如果你想重新生成，请先删除它再运行本脚本。
    goto :printbase64
)

rem ---- 定位 keytool：PATH 装不好就找 Android Studio 自带的 JBR ----
set "KEYTOOL="
for %%K in (keytool.exe) do if not defined KEYTOOL set "KEYTOOL=%%~$PATH:K"
if defined KEYTOOL goto :found

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
if defined KEYTOOL goto :found

for %%D in (
    "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\keytool.exe"
    "%LOCALAPPDATA%\Programs\Android Studio1\jbr\bin\keytool.exe"
    "%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe"
    "%ProgramFiles%\Android\Android Studio1\jbr\bin\keytool.exe"
    "%ProgramFiles(x86)%\Android\Android Studio\jbr\bin\keytool.exe"
    "%ProgramFiles%\JetBrains\IntelliJ IDEA\jbr\bin\keytool.exe"
) do (
    if not defined KEYTOOL if exist %%D set "KEYTOOL=%%~D"
)

:found
if not defined KEYTOOL (
    echo [错误] 找不到 keytool。
    echo        请任选其一：
    echo          1^) 用 Android Studio 打开本工程，它自带 JDK；然后重跑本脚本
    echo          2^) 安装 JDK 17 并把 JAVA_HOME 指向它
    echo          3^) 在 Android Studio 里 Build -^> Generate Signed Bundle / APK 生成
    exit /b 1
)
echo [信息] 使用 keytool: %KEYTOOL%

mkdir "app\keystore" 2>nul

"%KEYTOOL%" -genkeypair -v ^
  -keystore "app\keystore\debug.keystore" ^
  -storetype PKCS12 ^
  -alias androiddebugkey ^
  -keyalg RSA -keysize 2048 -validity 10950 ^
  -storepass android -keypass android ^
  -dname "CN=Android Debug,O=Android,C=US"

if not exist "app\keystore\debug.keystore" (
    echo [错误] 生成失败，密钥库不存在。
    exit /b 1
)
echo.
echo [成功] 已生成 app\keystore\debug.keystore
echo        别名=androiddebugkey   口令=android  ^(debug 专用，公开约定值^)

:printbase64
echo.
echo ============================================================
echo  下一步（二选一）
echo ============================================================
echo.
echo  方案 A（推荐，零配置）：把生成的文件提交进仓库 ——
echo      git add -f app/keystore/debug.keystore
echo      git commit -m "build: 固定 debug 签名密钥库"
echo      git push
echo    推送后 CI 会自动使用它，签名从此固定。
echo.
echo  方案 B（更严格）：放进 GitHub Secrets ——
echo    下面会打印 base64，粘贴到：
echo      GitHub 仓库 -^> Settings -^> Secrets and variables -^> Actions -^> New secret
echo      名称：KEYSTORE_BASE64
echo    （别名与口令用默认值即可，无需再配其他 secret）
echo.
powershell -NoProfile -Command "$b=[Convert]::ToBase64String([IO.File]::ReadAllBytes('app\keystore\debug.keystore')); Write-Host '=== KEYSTORE_BASE64（复制下面整行）===' -ForegroundColor Green; Write-Host $b"
echo.
pause
