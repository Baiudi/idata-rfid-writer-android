# 云端构建 APK（GitHub Actions）—— 零本地 Android 环境

适用场景：本机**没有**装 Android Studio / JDK17 / Android SDK，但你想拿到一个可安装的 `app-debug.apk`。
本工程已内置 `.github/workflows/build.yml`，只要推到 GitHub，云端（ubuntu 机器）就会自动编译并产出 APK 构件。

> 注意：云端构建**没有**厂商 iData UHF SDK，产出的 APK 是「**模拟模式**」版，用于验证 App 界面与「导入台账 → 批量写标 → 回读校验 → 落库 → 导出」全流程。
> 要真能往标签写数据，需按 README 路径 B/C 在本机放入 SDK 后重新构建，并在 App 里取消勾选「模拟模式」。

---

## 前提

- 一个 GitHub 账号（免费注册）
- 本机能联网，并且已登录 GitHub（浏览器登录 + Git 凭据管理器，或用 Personal Access Token）
- 本机装了 Git（[Git for Windows](https://git-scm.com/download/win)，安装时勾选默认即可）

---

## 步骤一：在 GitHub 新建空仓库

1. 打开 https://github.com → 右上角 **+ → New repository**。
2. 填 **Repository name**（例如 `idata-rfid-writer-android`）。
3. 可见性选 **Public** 或 **Private** 都可以（Private 仓库也含免费 Actions 额度，偶尔构建足够）。
4. **不要**勾选 “Add a README file”、**不要**勾 “.gitignore”、**不要**选 License —— 本工程已经自带这些。
5. 点 **Create repository**。
6. 创建后页面会显示仓库地址，复制 **HTTPS** 地址，形如：
   `https://github.com/<你的用户名>/idata-rfid-writer-android.git`

---

## 步骤二：把工程推送到 GitHub

工程已经在本地 `git init` 并提交好了，直接推即可。两种任选：

### 方式一（最简单）：一键脚本
双击本目录下的 **`push_to_github.bat`**，按提示把上面复制的仓库地址粘贴进去，回车，脚本会自动完成 `remote add` + `branch -M main` + `push`。

### 方式二：手动命令
在本机终端（工程目录下）执行：
```bash
git remote add origin https://github.com/<你的用户名>/idata-rfid-writer-android.git
git branch -M main
git push -u origin main
```
> 若提示认证失败：确认本机已登录 GitHub；或改用 Personal Access Token 作为密码（GitHub 已不支持账号密码 push）。

---

## 步骤三：触发云端构建

- **自动**：`push` 到 `main` 分支会**自动**触发 `Build APK` 工作流。
- **手动**：进仓库 → 顶部 **Actions** → 选 **Build APK** → **Run workflow** → 选 `main` → **Run**。

首次构建需要下载 JDK、Android SDK、Gradle 发行包与依赖，通常 **5–10 分钟**。可在 Actions 页面实时看日志。

---

## 步骤四：下载 APK

1. 构建完成后，进该次 run 的详情页。
2. 拉到底部 **Artifacts** 区域，下载 **`app-debug-apk`**（是一个 zip）。
3. 解压得到 **`app-debug.apk`**。

---

## 步骤五：安装到 T1 UHF

- **文件管理器安装**：把 `app-debug.apk` 拷到设备存储，用文件管理器打开安装（设备需允许“未知来源”应用）。
- **ADB 安装**（本机连着设备且开了 USB 调试）：
  ```bat
  adb install -r app-debug.apk
  ```

---

## 常见问题

- **Private 仓库 Actions 额度**：免费账号每月 2000 分钟，偶尔构建绰绰有余。
- **构建失败**：先看 Actions 日志。最常见是 SDK 包安装较慢或网络抖动，直接 **Re-run jobs** 重试。
- **push 被拒**：检查仓库地址是否正确、仓库是否真的是空仓库（不能带 README）、本机是否已登录 GitHub。
- **想产出“真实写标版”APK**：云端无法放厂商 SDK，请用 README 路径 B/C 在本机构建（放入 `app/libs/*.jar` 与 `jniLibs/*.so` 后取消模拟模式）。
