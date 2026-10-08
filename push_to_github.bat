@echo off
cd /d %~dp0

echo.
echo ============================================================
echo  iData T1 UHF RFID Writer - Push to GitHub (cloud build)
echo ============================================================
echo.
echo  BEFORE you run this script:
echo    1. Create an EMPTY repository on github.com
echo       (do NOT add README / .gitignore / license)
echo    2. Copy the repo HTTPS URL, for example:
echo       https://github.com/yourname/idata-rfid-writer-android.git
echo.

rem ---- find git.exe: system PATH first, then WorkBuddy portable git ----
set "GITEXE=git"
where git >nul 2>&1
if errorlevel 1 (
    if exist "C:\Users\MC00211\.workbuddy\binaries\PortableGit\versions\1.2.0\mingw64\bin\git.exe" (
        set "GITEXE=C:\Users\MC00211\.workbuddy\binaries\PortableGit\versions\1.2.0\mingw64\bin\git.exe"
    ) else (
        echo [ERROR] Git not found on this PC.
        echo         Install Git for Windows from: https://git-scm.com/download/win
        pause
        exit /b 1
    )
)

set /p REPO_URL=Paste your GitHub repo HTTPS URL then press Enter: 

if "%REPO_URL%"=="" (
    echo [ERROR] Empty URL. Exit.
    pause
    exit /b 1
)

"%GITEXE%" remote remove origin >nul 2>&1
"%GITEXE%" remote add origin %REPO_URL%
"%GITEXE%" branch -M main

echo.
echo Pushing to main ... first time may ask you to log in to GitHub.
echo   - Username: your GitHub username
echo   - Password: use a Personal Access Token, NOT your account password
echo     (GitHub - Settings - Developer settings - Personal access tokens)
echo.
"%GITEXE%" push -u origin main

if errorlevel 1 (
    echo.
    echo [ERROR] Push failed. Check:
    echo   1. Repo URL is correct and the repo is EMPTY
    echo   2. You are logged in to GitHub (use PAT as password)
    echo   3. Network can reach github.com
    pause
    exit /b 1
)

echo.
echo [OK] Push succeeded!
echo.
echo NEXT STEP:
echo   Open your repo on github.com - Actions tab - "Build APK"
echo   - Run workflow, wait 5-10 minutes, then download the
echo   artifact "app-debug-apk" and unzip it to get app-debug.apk.
echo   Details: see DEPLOY.md
echo.
pause
