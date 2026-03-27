# NeuroMeta Android SDK 集成测试 Demo

`neurometa-android-test` 是 `NeuroMeta Android SDK` 的联调与验收示例工程，重点覆盖设备扫描连接、实时 EEG 波形、前额单通道 PSD 展示、佩戴状态联动和 EDF 录制。

本文档以当前代码实现为准，主要说明：
- Demo 当前具备哪些能力
- 实时 EEG 与 PSD 的实际数据流
- 哪些文件负责 UI、监听器和映射逻辑
- 什么时候需要重新打 SDK AAR

---

## 功能概览

| 功能 | 状态 | 说明 |
|---|---|---|
| SDK 初始化 | ✅ | 从 `assets/neurometa_license.json` 加载 License |
| BLE 设备扫描/连接 | ✅ | 扫描 EEG Sensor 设备并建立连接 |
| 实时 EEG 主波形 | ✅ | 主图展示滤波后的实时波形 |
| 实时 PSD 五频段 | ✅ | `Delta / Theta / Alpha / Beta / Gamma` 五路趋势图与数值 |
| PSD 状态联动 | ✅ | 佩戴状态、质量状态、模式文案联动 |
| PSD 参数调节 | ✅ | 当前仅保留 `Gain` 与 `Window` 两项控制 |
| EDF 录制 | ✅ | 录制到 `Documents/edf/` |
| 电池/佩戴状态显示 | ✅ | 实时显示设备电量与佩戴状态 |

---

## 当前架构

当前 Demo **依赖本地 AAR**，不是直接依赖源码 module：

```kotlin
implementation(files("libs/neurometa-android-sdk-release.aar"))
```

这意味着：
- 如果只改 `neurometa-android-test` 代码，不需要重新打 SDK 包
- 如果改了 `app/neurometa-android-sdk` 源码，必须重新生成并复制 AAR

SDK 打包与复制命令：

```powershell
cd app/neurometa-android-sdk
.\gradlew.bat buildSdk
```

该命令会构建 `release` AAR，并复制到：

```text
app/neurometa-android-test/app/libs/neurometa-android-sdk-release.aar
```

---

## 快速开始

### 1. 准备 License

在 `app/src/main/assets/` 下放置：

```text
neurometa_license.json
```

示例结构：

```json
{
  "appKey": "your_app_key",
  "packageName": "your.package.name",
  "deviceTypes": ["EEG_SENSOR"],
  "features": ["DATA_COLLECT", "EDF_RECORD", "FILTER"],
  "issueDate": "2026-01-30",
  "expireDate": "2027-01-30",
  "signature": "YOUR_LICENSE_SIGNATURE"
}
```

### 2. 运行 Demo

```powershell
cd app/neurometa-android-test
.\gradlew.bat installDebug
```

### 3. 基本使用流程

1. 打开 App，授予蓝牙与定位权限
2. 等待 SDK 初始化成功
3. 点击 `SCAN DEVICES`
4. 选择设备并连接
5. 佩戴后查看主波形与五频段 PSD
6. 如有需要，点击 `Record EDF` 开始录制

---

## 实时数据流

当前数据流不是 README 旧版本里描述的“单一 RealtimeDataListener 驱动全部 UI”，而是下面这套更明确的链路：

```text
Device -> SDK DataCollector
       -> UnfilteredDataListener -> PSDAnalyzer -> PSDResult -> PsdUiState -> PSD UI
       -> UnfilteredDataListener -> FilteredEEGProcessor -> EEG 主波形
       -> DeviceStatusListener   -> 电量/佩戴状态/PSD 门控
```

### 1. PSD 数据流

- 输入来源：`UnfilteredDataListener`
- 取值通道：`packet.channelData[0]`
- 分析器：`PSDAnalyzer`
- UI 模型：`PsdUiState`

主界面现在**不直接消费** `PSDAnalyzer.PSDResult` 的全部诊断字段，而是先映射成更精简的 `PsdUiState`，只渲染：
- 五个最终频段功率
- `signalQuality`
- `isDataValid`
- 简化后的 `statusText`

这样 UI 不会被 `deltaConfidence`、`artifactReason`、`qualityScore` 等内部诊断字段绑死。

### 2. EEG 主波形数据流

- 输入来源：原始 `channelData[0]`
- 中间处理：`FilteredEEGProcessor`
- 输出：主波形图 `chartEEG`

因此：
- 主波形展示的是滤波后的实时波形
- PSD 展示的是单独分析链路算出的频段结果

### 3. 设备状态联动

`DeviceStatusListener` 会驱动：
- 电池电量显示
- `WEARING / NOT WORN` 状态
- `PSDAnalyzer.updateWearState(status.wear)`

所以佩戴状态会直接影响 PSD 是否进入稳定输出。

---

## PSD 界面说明

### 当前显示内容

`SPECTRAL COMPONENTS` 区域包含：
- `DELTA`
- `THETA`
- `ALPHA`
- `BETA`
- `GAMMA`

每个频段都有：
- 一个最终功率数值
- 一条最近 5 秒的趋势图

状态栏 `tvPsdStatus` 显示精简模式文案，例如：

```text
AWAKE FRONTAL · READY
AWAKE FRONTAL · READY · FAIR
AWAKE FRONTAL · NOT WORN
SLEEP RAW · STABILIZING
```

### 当前控制项

`PRECISION CONTROLS` 当前只保留：
- `GAIN`
- `WINDOW`

说明：
- `GAIN`：影响频段数值显示缩放
- `WINDOW`：影响 PSD 分析窗口长度
- `Y zoom` 已从当前版本移除，频段图使用固定默认 Y 轴范围

---

## 关键文件

```text
app/neurometa-android-test/
├── app/
│   ├── libs/
│   │   └── neurometa-android-sdk-release.aar
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── neurometa_license.json
│   │   ├── java/com/neurometa/test/
│   │   │   ├── MainActivity.kt
│   │   │   ├── DeviceAdapter.kt
│   │   │   └── PsdUiState.kt
│   │   └── res/layout/
│   │       └── activity_main.xml
│   └── src/test/java/com/neurometa/test/
│       ├── PsdUiStateMapperTest.kt
│       └── ActivityMainLayoutRegressionTest.kt
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

### 文件职责

- `app/src/main/java/com/neurometa/test/MainActivity.kt`
  负责权限、SDK 初始化、设备扫描连接、监听器注册、UI 更新、EDF 录制
- `app/src/main/java/com/neurometa/test/PsdUiState.kt`
  负责把 SDK 的 `PSDResult` 映射为 UI 所需的极简模型
- `app/src/main/res/layout/activity_main.xml`
  当前主页面布局，已移除 `Y zoom`
- `app/src/test/java/com/neurometa/test/PsdUiStateMapperTest.kt`
  约束 PSD UI 映射逻辑
- `app/src/test/java/com/neurometa/test/ActivityMainLayoutRegressionTest.kt`
  防止 `Y zoom` 控件重新进入主布局

---

## 常用命令

### 仅验证 Demo App

```powershell
cd app/neurometa-android-test
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

### 仅跑 PSD UI 相关测试

```powershell
cd app/neurometa-android-test
.\gradlew.bat testDebugUnitTest --tests com.neurometa.test.PsdUiStateMapperTest
.\gradlew.bat testDebugUnitTest --tests com.neurometa.test.ActivityMainLayoutRegressionTest
```

### SDK 改动后重新打包

```powershell
cd app/neurometa-android-sdk
.\gradlew.bat buildSdk
```

---

## 常见问题

### 扫描不到设备

- 确认蓝牙已开启
- 确认定位权限已授予
- Android 12+ 需要 `BLUETOOTH_SCAN` 与 `BLUETOOTH_CONNECT`
- 确认设备已开机且未被其他手机占用

### 已连接但 PSD 不更新

- 确认设备已正确佩戴
- 检查 `tvWearStatus` 是否为 `WEARING`
- 检查 `tvPsdStatus` 是否处于 `STABILIZING` 或 `NOT WORN`
- 确认 `channelData[0]` 有原始 EEG 数据输入

### 主波形有数据但 PSD 数值异常

- PSD 使用的是独立分析链路，不等于主波形图本身
- 检查当前 `GAIN` 和 `WINDOW` 设置
- 如刚修改 SDK 源码，确认是否重新执行了 `buildSdk`

### EDF 文件无法打开

- 确保录制时长足够
- 检查应用文档目录写入是否正常
- 使用 EDFbrowser 等工具打开验证

---

## 环境信息

- Android Gradle Plugin：`8.5.2`
- Kotlin：`2.0.21`
- minSdk：`21`
- targetSdk：`35`

---

## License

Copyright © 2026 NeuroMeta. All rights reserved.
