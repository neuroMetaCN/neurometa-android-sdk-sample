# NeuroMeta SDK 集成测试 Demo

本项目是 `NeuroMeta Android SDK` 的官方集成示例，演示如何在 Android 应用中接入 EEG 脑电数据采集功能。

## 📋 功能清单

| 功能 | 状态 | 说明 |
|-----|------|------|
| SDK 初始化 | ✅ | License 授权验证 |
| 蓝牙设备扫描 | ✅ | 扫描 EEG Sensor 设备 |
| 蓝牙设备连接 | ✅ | BLE 连接管理 |
| 实时数据采集 | ✅ | 250Hz 原始采样 → 50Hz 滤波输出 |
| 实时波形显示 | ✅ | MPAndroidChart 绘制 |
| EDF 文件录制 | ✅ | 标准 EDF 格式输出 |
| 设备状态监测 | ✅ | 电池电量 & 佩戴状态 |

---

## 🚀 快速开始

### 1. 添加 SDK 依赖

将 `neurometa-android-sdk-release.aar` 复制到 `app/libs/` 目录：

```kotlin
// app/build.gradle.kts
dependencies {
    // NeuroMeta SDK
    implementation(files("libs/neurometa-android-sdk-release.aar"))
    
    // SDK 必需依赖
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### 2. 配置 AndroidManifest.xml

```xml
<!-- 蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- 存储权限 (EDF 录制) -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" 
    android:maxSdkVersion="28" />
```

### 3. 准备 License 文件

在 `app/src/main/assets/` 创建 `neurometa_license.json`：

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

> ⚠️ **注意**: 请联系 NeuroMeta 获取正式授权 License

---

## 📖 SDK 对接流程

```
┌──────────────────────────────────────────────────────────────────┐
│                        SDK 对接流程图                             │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐       │
│   │ 1. 权限申请  │ ──▶ │ 2. SDK初始化 │ ──▶ │ 3. 注册监听器│       │
│   └─────────────┘     └─────────────┘     └─────────────┘       │
│                                                 │                │
│                                                 ▼                │
│   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐       │
│   │ 6. 数据处理  │ ◀── │ 5. 数据接收  │ ◀── │ 4. 扫描连接  │       │
│   └─────────────┘     └─────────────┘     └─────────────┘       │
│         │                                                        │
│         ▼                                                        │
│   ┌─────────────┐     ┌─────────────┐                           │
│   │ 7. 波形显示  │     │ 8. EDF录制  │  (可选)                    │
│   └─────────────┘     └─────────────┘                           │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 📝 详细步骤

### Step 1: 权限申请

SDK 在 Android 12+ 需要动态申请蓝牙权限：

```kotlin
private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
} else {
    arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}

private fun checkPermissions() {
    val missing = REQUIRED_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isNotEmpty()) {
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    } else {
        initSDK()  // 权限已获取，初始化 SDK
    }
}
```

---

### Step 2: SDK 初始化

```kotlin
private val sdk: NeuroMetaSDK by lazy { NeuroMetaSDK.getInstance() }

private fun initSDK() {
    // 从 assets 加载 License
    val license = License.fromAssets(this, "neurometa_license.json")
    
    sdk.init(
        context = this,
        license = license,
        callback = object : NeuroMetaSDK.InitCallback {
            override fun onSuccess() {
                Log.d(TAG, "SDK 初始化成功")
                setupDataListeners()  // 注册数据监听器
            }

            override fun onError(error: SDKError) {
                Log.e(TAG, "SDK 初始化失败: ${error.message}")
            }
        },
        allowDevLicense = true  // 开发环境允许测试 License
    )
}
```

---

### Step 3: 注册数据监听器

SDK 提供多种监听器，按需注册：

```kotlin
private fun setupDataListeners() {
    val dataCollector = sdk.getDataCollector()
    
    // 监听器 1: 实时滤波数据 (推荐用于 UI 显示)
    dataCollector.addRealtimeDataListener(object : DataCollector.RealtimeDataListener {
        override fun onRealtimeData(packet: EEGDataPacket) {
            // 已滤波 + 降采样，适合波形绑制
            val samples = packet.channelData[0] ?: return
            updateChart(samples)
        }
    })
    
    // 监听器 2: 未滤波原始数据 (用于调试/科研)
    dataCollector.addUnfilteredDataListener(object : DataCollector.UnfilteredDataListener {
        override fun onUnfilteredData(packet: EEGDataPacket) {
            // 原始 250Hz 数据
            val rawSamples = packet.channelData[0] ?: return
            Log.d(TAG, "原始数据: ${rawSamples.take(5)}")
        }
    })
    
    // 监听器 3: 设备状态 (电池 + 佩戴)
    dataCollector.addDeviceStatusListener(object : DataCollector.DeviceStatusListener {
        override fun onDeviceStatus(status: DeviceStatus) {
            updateBatteryUI(status.batteryLevel)
            updateWearUI(status.wear)
        }
    })
}
```

**监听器类型对比：**

| 监听器 | 采样率 | 滤波状态 | 典型用途 |
|-------|-------|---------|---------|
| `RealtimeDataListener` | 50Hz | ✅ 已滤波 | UI 波形显示 |
| `UnfilteredDataListener` | 250Hz | ❌ 未滤波 | 调试/科研分析 |
| `FilteredDataListener` | 250Hz | ✅ 已滤波 | 特征提取 |
| `DeviceStatusListener` | - | - | 电池/佩戴状态 |

---

### Step 4: 扫描 & 连接设备

```kotlin
// 开始扫描
private fun startScan() {
    sdk.getDeviceManager().startScan(
        timeout = 3000L,  // 扫描 3 秒
        callback = object : DeviceManager.ScanCallback {
            override fun onDeviceFound(device: Device) {
                Log.d(TAG, "发现设备: ${device.name ?: device.id}")
                deviceList.add(device)
                updateUI()
            }

            override fun onComplete(devices: List<Device>) {
                Log.d(TAG, "扫描完成，共 ${devices.size} 个设备")
            }

            override fun onError(error: SDKError) {
                Log.e(TAG, "扫描失败: ${error.message}")
            }
        }
    )
}

// 连接设备
private fun connectDevice(device: Device) {
    sdk.getDeviceManager().connect(
        deviceId = device.id,
        callback = object : DeviceManager.ConnectionCallback {
            override fun onConnected() {
                Log.d(TAG, "连接成功，开始接收数据")
                // 数据会自动推送到已注册的监听器
            }

            override fun onDisconnected() {
                Log.d(TAG, "设备已断开")
            }

            override fun onError(error: SDKError) {
                Log.e(TAG, "连接失败: ${error.message}")
            }
        }
    )
}
```

---

### Step 5: 实时数据处理 (滤波)

如需对原始数据进行二次滤波处理：

```kotlin
// 使用 FilteredEEGProcessor 进行实时滤波
FilteredEEGProcessor.getInstance().setCallback { packet ->
    runOnUiThread {
        // packet.samples: 滤波 + 降采样后的数据
        updateChartData(packet.samples)
    }
}

// 将原始数据送入滤波器
dataCollector.addRealtimeDataListener(object : DataCollector.RealtimeDataListener {
    override fun onRealtimeData(packet: EEGDataPacket) {
        packet.channelData[0]?.let { samples ->
            FilteredEEGProcessor.getInstance().addSamples(samples)
        }
    }
})
```

---

### Step 6: EDF 文件录制

```kotlin
private var isRecording = false

private fun startRecording() {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val fileName = "eeg_$timestamp.edf"
    val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "edf")
    outputDir.mkdirs()
    val filePath = File(outputDir, fileName).absolutePath

    sdk.getEdfRecorder().startRecording(
        outputPath = filePath,
        callback = object : EdfRecorder.RecordingCallback {
            override fun onRecordingStarted(filePath: String) {
                isRecording = true
                Log.d(TAG, "录制开始: $filePath")
            }

            override fun onRecordingStopped(filePath: String, recordCount: Int) {
                isRecording = false
                Log.d(TAG, "录制完成: $recordCount 条记录")
            }

            override fun onError(message: String) {
                Log.e(TAG, "录制错误: $message")
            }
        }
    )
}

private fun stopRecording() {
    sdk.getEdfRecorder().stopRecording()
}
```

---

### Step 7: 资源释放

在 Activity 销毁时释放资源：

```kotlin
override fun onDestroy() {
    super.onDestroy()
    
    // 停止录制
    if (isRecording) {
        sdk.getEdfRecorder().stopRecording()
    }
    
    // 断开设备
    sdk.getDeviceManager().disconnect()
    
    // 可选: 销毁 SDK (通常在 Application 级别管理)
    // sdk.destroy()
}
```

---

## 📂 项目结构

```
neurometa-android-test/
├── app/
│   ├── libs/
│   │   └── neurometa-android-sdk-release.aar  ← SDK 库文件
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── neurometa_license.json         ← License 配置
│   │   ├── java/com/neurometa/test/
│   │   │   ├── MainActivity.kt                ← 主界面 (完整示例)
│   │   │   └── DeviceAdapter.kt               ← 设备列表适配器
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml          ← UI 布局
│   │   │   └── values/
│   │   │       └── colors.xml                 ← 颜色定义
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md                                   ← 本文档
```

---

## ⚠️ 常见问题

### Q1: 扫描不到设备？
- 确认蓝牙已开启
- 确认位置权限已授予
- 确认设备已开机且在范围内
- Android 12+ 需要 `BLUETOOTH_SCAN` 权限

### Q2: 连接失败？
- 检查设备是否已被其他手机连接
- 尝试重启设备后重新扫描
- 查看 Logcat 中的错误详情

### Q3: 数据不显示？
- 确认设备已正确佩戴（检查 `DeviceStatus.wear`）
- 确认已注册 `RealtimeDataListener`
- 检查图表更新是否在主线程

### Q4: EDF 文件无法打开？
- 确保录制时间 > 1 秒
- 检查存储权限
- 使用 EDFbrowser 等专业工具打开

---

## 📞 技术支持

- **SDK 版本**: v1.1.0
- **最低 Android 版本**: API 21 (Android 5.0)
- **目标 Android 版本**: API 35 (Android 15)
- **联系方式**: sdk-support@neurometa.com.cn

---

## 📜 License

Copyright © 2026 NeuroMeta. All rights reserved.
