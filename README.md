# iData T1 UHF 资产标签批量写入工具（原生 Android）

为 **iData T1 UHF**（安卓手持终端，ISO18000-6C / EPC Gen2 V2）编写的**原生 Android** 应用，
直接调用 iData 官方 **jar + so SDK**，支持「导入资产台账 → 批量写标（带 TID 过滤 + 回读校验）→ 写后同步到钉钉资产台账（dws）」。

> 这是一个**完整、可直接用 Android Studio / Gradle 编译出 APK 的 Android 工程**（已含 Gradle Wrapper）。
> **关键改进：即使没有厂商 iData UHF SDK，也能编译出可安装的 APK**——此时 App 以「模拟模式」运行，
> 用于 UI 联调与「导入台账 → 批量写标 → 回读校验 → 落库 → 导出」全流程验证；
> 接入真实 RFID 时，放入 SDK jar/so 并在 `IdataUhfAdapter` 补全 `TODO-SDK` 即可（界面取消勾选「模拟模式」）。

---

## 一、工程里已包含 vs 需要你准备

| 项目 | 状态 | 说明 |
|------|------|------|
| 全部源码 / 布局 / 配置 | ✅ 已包含 | `app/src/...`、`build.gradle` 等 |
| Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`） | ✅ 已包含 | 直接可命令行构建，无需单独装 Gradle |
| 示例台账 `sample-ledger.csv` | ✅ 已包含 | 上机前先拿它试跑 |
| **「模拟模式」适配器 `SimulatedRfidAdapter`** | ✅ 已包含 | 无硬件/无 SDK 也能跑通全流程 |
| **iData UHF SDK（`idata-uhf-sdk.jar` + `*.so`）** | ⚠️ 接真实 RFID 才需要 | 厂商专有库，放 `app/libs/` 与 `app/src/main/jniLibs/<abi>/`；**缺失不影响构建** |
| **JDK 17** | ❌ 需你准备 | 构建 Android（AGP 8.2）需要 |
| **Android SDK / Android Studio** | ❌ 需你准备 | 编译 + 真机安装 |

---

## 二、构建 APK（三条路径，任选）

### 路径 A：GitHub Actions 云端出包（**零本地环境**，推荐给不想装 Android Studio 的情况）
1. 把本工程推到 GitHub 仓库（已内置 `.github/workflows/build.yml`）。
2. 在仓库 `Actions → Build APK → Run workflow` 手动触发（或 push 到 main/master 自动触发）。
3. 构建完成后在 `Artifacts` 下载 `app-debug-apk`，即得到可安装的 `app-debug.apk`。
4. 该 APK 是「模拟模式」构建（因云端无厂商 SDK），用于流程验证；接真实 RFID 时在本地按路径 B/C 构建。

> 完整图文步骤（新建空仓库 → 一键推送 → 触发 → 下载 → 安装）→ 见 **[DEPLOY.md](DEPLOY.md)**。
> 不想敲命令可直接双击 **`push_to_github.bat`** 完成推送。

### 路径 B：Android Studio（推荐本地构建，最省心）
1. 安装 **Android Studio**（Hedgehog / Iguana 以上，自带 JDK 17 与 Android SDK）。
2. 接真实 RFID 才需要把 iData UHF SDK 放好（见第三节）；**不放也能直接构建**。
3. `File → Open` 打开本工程目录。
   - 首次打开会自动生成/校正 `local.properties`（SDK 路径）。
4. 连上 T1 UHF（开启「USB 调试」）→ 选真机 `Run`；或 `Build → Build Bundle(s)/APK → Build APK`。
5. 生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

### 路径 C：命令行（已配好 Gradle Wrapper）
1. 安装 **JDK 17** 并设 `JAVA_HOME`（可指向 Android Studio 自带 JDK：`C:\Program Files\Android\Android Studio\jbr`）。
2. 接真实 RFID 才需要把 SDK 放好（见第三节）。
3. 双击 **`build_apk.bat`**（Windows，会自动探测 `ANDROID_HOME` 生成 `local.properties`），或命令行执行：
   ```bat
   set JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   gradlew.bat assembleDebug
   ```
4. 构建产物：`app\build\outputs\apk\debug\app-debug.apk`。
   - 需要「已签名 Release APK」：在 `app/build.gradle` 配置 `signingConfigs` 后运行 `gradlew.bat assembleRelease`。

> 构建时 Gradle 会自动从 `google()` / `mavenCentral()` 下载 AndroidX、Gradle 发行包等依赖（需联网）。
> `app/build.gradle` 已改为**条件依赖**：仅当 `app/libs/idata-uhf-sdk.jar` 存在时才引入它，
> 因此**未放 SDK 也能编译**，此时自动以「模拟模式」出包（构建日志会打印提示）。

---

## 三、放入 iData UHF SDK（仅接真实 RFID 时需要）

获取：厂商开发者站 `developer.idata.com.cn` 或联系 iData 技术支持 / `sales@idataglobal.com`，
索要「**T1 UHF RFID SDK（Android，jar + so）**」。放法：

```
app/libs/idata-uhf-sdk.jar                 <- UHF SDK 的 jar 包
app/src/main/jniLibs/arm64-v8a/*.so        <- 64 位（T1 UHF 多为 64 位，优先）
app/src/main/jniLibs/armeabi-v7a/*.so      <- 32 位（兼容）
```

（`app/libs/README.md`、`app/src/main/jniLibs/README.md` 有详细说明。）
放好后重新构建，并在 App 界面**取消勾选「模拟模式」**。

---

## 四、对齐 SDK 类名（关键，接真实 RFID 且写标前必做）

`app/src/main/java/.../rfid/IdataUhfAdapter.java` 是唯一直接调用 iData SDK 的文件。
其中所有方法体都是**占位实现**（返回“待接入 SDK”），并标注了 `TODO-SDK`。
请按你拿到的 SDK jar 中的**实际**类名 / 方法名 / 回调形态替换这些占位，其余业务代码无需改动。

已核对无误、不要改的部分：
- Bank 编码：`0=RESERVED / 1=EPC / 2=TID(只读,出厂唯一) / 3=USER`
- 过滤地址 `fStartBit` 为 **bit** 类型（EPC 默认从 32 起）；写入/读取地址为 **Word**
- 默认访问密码 `00000000`
- `writeTagWithFilter` 形参顺序：`(fBank, fStartBit, fLenBits, fData, writeBank, startWord, writeData, pwd, callback)`

> 若拿到的是旧版「`GetRFIDThread` + `MyApp.getIdataLib()`」形态（iData 95W 等老机型），
> 用同样的 `RfidSdk` 接口实现一套 legacy 适配即可，业务层零改动。

---

## 五、安装到 T1 UHF

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
- 设备需开启「USB 调试」（设置 → 关于本机 → 连点版本号进入开发者选项 → 打开 USB 调试）。
- 也可把 APK 拷到设备用文件管理器直接安装。

---

## 六、App 使用流程

1. 填参数（界面顶部）：
   - **写标功率**：默认 **15 dBm**（单张写标务必低功率，防误写邻近标签）
   - **模块类型**：默认 `0=UM`，连不上依次试 `1=SLR` / `2=GX`
   - **写标区域**：`1=EPC` 或 `3=USER`
   - **起始字地址**：EPC 默认 `2`
2. 点「**连接UHF**」→ 状态显示已连接。
3. 点「**导入台账CSV**」选 `sample-ledger.csv`（或你的台账；表头含 `asset_no,dept,model,remark`，顺序不限）。
4. 点「**开始批量写标**」：
   - 逐张把**一张空白标签**靠近天线（低功率下只放一张，避免误写）；
   - App 检测单张 → 读 TID（只读、出厂唯一）→ 带 TID 过滤写入资产编号 → **回读校验**；
   - 成功/失败实时列表显示，结果落 SQLite（中途退出不丢，重跑自动跳过已成功项）。
5. 点「**导出结果JSON**」生成 `rfid_write_results.json`（在 app 外部目录，可 `adb pull` 或文件管理器取出）。
6. 点「**同步到dws台账**」= 导出 JSON 并提示 PC 端命令（见下）。

> 资产编号默认按 **ASCII 直写**。若你的 SDK 要求 Hex，把 `MainActivity` 里 `item.assetNo` 改为
> `FormatUtil.asciiToHex(item.assetNo)` 即可。

---

## 七、写后对接 dws 同步到资产台账

dws 是**桌面 CLI**，无法在 Android 直接运行，因此采用「App 导出 JSON → PC 脚本写钉钉」的闭环：

### 7.1 先在钉钉建好资产台账（在线电子表格）
表头至少包含：`资产编号`（关联键）、`标签TID`、`EPC`、`写标状态`、`写标时间`、`写入内容`。
（脚本会在列缺失时自动追加，无需手动建全。）

### 7.2 方式 A：脚本直读在线表格（最简）
```bash
python sync_to_dws.py --results rfid_write_results.json --node <表格nodeId>
```
> 拿 nodeId：钉钉打开表格 → 链接里 `spreadsheetv2/...`，或 `dws sheet +list` 查到。

### 7.3 方式 B：先导出本地 CSV 再同步（最稳）
```bash
dws sheet export-csv --node <表格nodeId> --output ledger.csv
python sync_to_dws.py --results rfid_write_results.json --ledger-csv ledger.csv --node <表格nodeId>
```

### 7.4 说明
- 按 `资产编号` 匹配行，回填 TID/EPC/状态/时间/写入内容；整表覆盖写回（其它单元格不动）。
- 默认只同步 `status=success`；`--include-failed` 也写失败状态。
- 默认 `--auto-convert=false` 保留资产编号/十六进制为文本（防丢前导零、防转日期）。
- 写操作触发 dws 确认门禁时加 `--yes`；多组织账号用 `--profile <corpId:userId>`；列名可改 `--key-col` 等；试跑先 `--dry-run`。

---

## 八、EPC vs USER（数据落区）

- **EPC（区 1）**：盘点默认读取区，远距一扫即得，写这里最方便关联资产；长度受限（约 12 字节）。
- **USER（区 3）**：容量更大，适合存部门/型号/备注；盘点默认不一定读，需在读写器开启读 USER。
- 推荐：**EPC 写唯一资产编码，USER 写可读详情，TID 只读当物理身份证**。

---

## 九、工程结构

```
idata-rfid-writer-android/
├── .github/workflows/build.yml               # GitHub Actions 云端构建 APK
├── gradlew / gradlew.bat / gradle/wrapper/   # Gradle Wrapper（已含，可直接构建）
├── build_apk.bat                             # Windows 一键构建 APK（自动探测 ANDROID_HOME）
├── sample-ledger.csv                         # 示例台账（上机试跑用）
├── sync_to_dws.py                            # PC 端：写标结果回写钉钉在线电子表格
├── app/
│   ├── build.gradle                          # 条件依赖：有 jar 才引入 iData SDK（否则模拟模式）
│   ├── libs/README.md                        # jar 放置说明
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── jniLibs/README.md                 # so 放置说明
│   │   ├── java/com/example/rfidwriter/
│   │   │   ├── MainActivity.java             # 主界面 + 批量写标编排（含「模拟模式」开关）
│   │   │   ├── rfid/
│   │   │   │   ├── RfidSdk.java              # UHF 操作抽象接口（业务只依赖它）
│   │   │   │   ├── IdataUhfAdapter.java      # iData 原生 SDK 适配层（唯一碰 SDK 处，含 TODO-SDK）
│   │   │   │   ├── SimulatedRfidAdapter.java # 模拟适配器（无硬件/无 SDK 跑全流程）
│   │   │   │   └── TagInfo.java
│   │   │   ├── db/   WriteResult.java / ResultDbHelper.java   # SQLite 持久化
│   │   │   ├── sync/ SyncManager.java        # 导出 JSON / 可选 HTTP 上报
│   │   │   └── util/ FormatUtil.java         # ASCII<->Hex、CSV、JSON
│   │   └── res/...                           # 布局与字符串（含 chkSimulate 模拟开关）
├── build.gradle / settings.gradle / gradle.properties / local.properties
```

---

## 十、注意事项

- **模拟模式**：App 默认勾选「模拟模式」，无硬件/无 SDK 也能完整体验写标流程（标签/TID 为随机虚拟值，回读校验通过）。
  接真实 RFID 后取消勾选即可切换为 `IdataUhfAdapter`。
- 写单张标签务必**低功率 + 单张靠近天线**，否则可能误写邻近标签（官方 SDK 文档明确提醒）。
- 标签若改过访问密码，把 `MainActivity.PWD` / `IdataUhfAdapter` 里的 `00000000` 改成实际密码。
- 首机上机请小批量试写，验证 EPC 长度与 PC（协议控制）字段是否正确。
- 工程已附带 Gradle Wrapper，可命令行构建；源码接口严格依据 iData UHF SDK 文档，
  上机前请按第四节对齐 SDK 实际类名并小批量验证。
