@echo off
REM ============================================================
REM  iData RFID Writer —— 一键构建 APK（Windows）
REM  前置条件：
REM    1) 安装 JDK 17+，并设置环境变量 JAVA_HOME
REM       （Android Studio 自带 JDK，可指向它，例如
REM         set JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"）
REM    2) 已安装 Android SDK，或 local.properties 已填 sdk.dir
REM       （若设置了 ANDROID_HOME 会自动采用，无需 local.properties）
REM    3) 接入真实 RFID 才需要：
REM         app/libs/idata-uhf-sdk.jar 与 app/src/main/jniLibs/<abi>/*.so
REM       【未放 SDK 也能构建】—— 此时 App 以「模拟模式」运行（无真实射频），
REM       用于 UI 联调与流程验证，详见 README。
REM  用法：在命令行 / 双击运行本文件
REM ============================================================
setlocal
set GRADLE_OPTS=-Xmx2048m

if not defined JAVA_HOME (
  echo [ERROR] 未设置 JAVA_HOME。请先安装 JDK 17 并设置环境变量，例如：
  echo         set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
  goto :eof
)

REM 若未配置 local.properties，但设置了 ANDROID_HOME，则自动生成，避免构建报错
if not exist "local.properties" (
  if defined ANDROID_HOME (
    echo sdk.dir=%ANDROID_HOME%> local.properties
    echo [INFO] 已根据 ANDROID_HOME 生成 local.properties
  )
)

echo ============================================================
echo  构建 Debug APK（无需签名，可直接安装到 T1 UHF 调试）
echo  （未检测到 iData SDK 时将以「模拟模式」构建）
echo ============================================================
call gradlew.bat assembleDebug %*
if errorlevel 1 goto :fail

echo.
echo ============================================================
echo  Debug APK 已生成：
echo    app\build\outputs\apk\debug\app-debug.apk
echo ============================================================
echo.
echo  安装到已通过 USB 连接的 T1 UHF（需开启“USB 调试”）：
echo    adb install -r app\build\outputs\apk\debug\app-debug.apk
echo.
echo  若需要“已签名”的 Release APK：
echo    1) 在 app/build.gradle 的 android { } 内配置 signingConfigs
echo    2) 运行：call gradlew.bat assembleRelease
goto :ok

:fail
echo.
echo [ERROR] 构建失败。常见原因：
echo   - Android SDK 路径未配置：设置 ANDROID_HOME 或 local.properties 填 sdk.dir
echo   - JDK 版本不对：需 JDK 17（与 compileSdk/AGP 8.2 匹配）
echo   - 网络问题：首次构建需联网下载 Gradle 与 androidx 依赖
echo.
echo  注：未放入 iData SDK 不会导致构建失败，只会以「模拟模式」出包。
exit /b 1

:ok
endlocal
