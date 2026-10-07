@echo off
chcp 65001 >nul
cd /d %~dp0

echo.
echo ============================================================
echo  iData T1 UHF 写标工具 —— 一键推送到 GitHub（触发云端构建）
echo ============================================================
echo.
echo  前置条件：
echo   1. 已在 github.com 新建一个【空】仓库（不要带 README/.gitignore）
echo   2. 本机已安装 Git 并登录 GitHub
echo   3. 已复制该仓库的 HTTPS 地址
echo.
set /p REPO_URL=请粘贴 GitHub 仓库 HTTPS 地址（形如 https://github.com/你/仓库名.git）：

if "%REPO_URL%"=="" (
    echo 未输入地址，已退出。
    pause
    exit /b 1
)

git remote remove origin >nul 2>&1
git remote add origin %REPO_URL%
git branch -M main

echo.
echo 正在推送到 main 分支（首次会请求 GitHub 登录/令牌）...
echo.
git push -u origin main

if errorlevel 1 (
    echo.
    echo 推送失败，请检查：
    echo   1）仓库地址是否正确
    echo   2）本机是否已登录 GitHub（或用 Personal Access Token 作为密码）
    echo   3）仓库是否为【空】仓库（不能带初始 README）
    pause
    exit /b 1
)

echo.
echo 推送成功！
echo 请到 GitHub 仓库 → Actions → Build APK 等待构建完成，
echo 然后在 Artifacts 下载 app-debug-apk，解压即得到 app-debug.apk。
echo 详细步骤见 DEPLOY.md。
echo.
pause
