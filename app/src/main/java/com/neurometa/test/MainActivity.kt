package com.neurometa.test

import android.Manifest
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.neurometa.sdk.NeuroMetaSDK
import com.neurometa.sdk.data.DataCollector
import com.neurometa.sdk.data.FilteredEEGProcessor
import com.neurometa.sdk.data.PSDAnalyzer
import com.neurometa.sdk.data.PSDConfig
import com.neurometa.sdk.device.DeviceManager
import com.neurometa.sdk.edf.EdfRecorder
import com.neurometa.sdk.model.ConnectionState
import com.neurometa.sdk.model.Device
import com.neurometa.sdk.model.DeviceStatus
import com.neurometa.sdk.model.EEGDataPacket
import com.neurometa.sdk.auth.License
import com.neurometa.sdk.model.SDKError
import com.neurometa.sdk.smarteeg.SmartEEGOtaStatus
import com.neurometa.test.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val MIN_OTA_BATTERY_LEVEL = 30
        private const val RAW_LOG_TAG = "NeuroMeta-TestRaw"
        private const val EEG_LOG_TAG = "NeuroMeta-TestEEG"
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
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceAdapter
    
    private val sdk: NeuroMetaSDK by lazy { NeuroMetaSDK.getInstance() }
    private var connectedDevice: Device? = null
    private var isRecording = false
    private var sdkListenersRegistered = false
    private var latestBatteryLevel = -1
    private var selectedFirmwareCachePath: String? = null
    private val firmwarePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                cacheSelectedFirmware(uri)
            }
        }
    
    // 图表数据 (5秒 @50Hz = 250 点)
    private val chartEntries = mutableListOf<Entry>()
    private var dataIndex = 0f
    private val maxDataPoints = 250

    // PSD 分析器
    private val psdAnalyzer: PSDAnalyzer by lazy { PSDAnalyzer.getInstance() }

    // PSD 频段图表数据
    private data class BandChartState(
        val entries: MutableList<Entry> = mutableListOf(),
        var dataIndex: Float = 0f,
        var currentValue: Float = 0f,
        var targetValue: Float = 0f,
        var initialized: Boolean = false
    )
    private val bandStates = mapOf(
        "delta" to BandChartState(),
        "theta" to BandChartState(),
        "alpha" to BandChartState(),
        "beta" to BandChartState(),
        "gamma" to BandChartState()
    )
    private var currentPsdDisplayMode = DisplayMode.ABS_UV
    private val psdChartFrameIntervalMs = 50L
    private val psdChartWindowMs = 5_000L
    private val bandMaxPoints = (psdChartWindowMs / psdChartFrameIntervalMs).toInt()
    private val psdChartLerpFactor = 0.35f
    private val psdChartSnapThreshold = 0.05f
    // 时间更新 Handler
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateTime()
            timeHandler.postDelayed(this, 1000)
        }
    }
    private val psdChartRenderRunnable = object : Runnable {
        override fun run() {
            renderPsdCharts()
            timeHandler.postDelayed(this, psdChartFrameIntervalMs)
        }
    }
    private val rawBleDebugListener =
        object : DeviceManager.DataListener {
            override fun onRawDataReceived(data: ByteArray) {
                val message = DebugLogFormatter.formatRawPacket(data)
                Log.d(RAW_LOG_TAG, message)
                runOnUiThread { log(message) }
            }
        }
    private val parsedEegDebugListener =
        object : DataCollector.UnfilteredDataListener {
            override fun onUnfilteredData(packet: EEGDataPacket) {
                val message = DebugLogFormatter.formatParsedEeg(packet)
                Log.d(EEG_LOG_TAG, message)
                runOnUiThread { log(message) }
            }
        }
    private val otaListener = object : DeviceManager.SmartEEGOtaListener {
        override fun onSmartEegOtaStatus(status: SmartEEGOtaStatus) {
            runOnUiThread {
                binding.tvOtaStatus.text = formatOtaStatus(status)
                log("OTA ${status.state}: ${status.progress}% ${status.error ?: status.debugMessage.orEmpty()}")
            }
        }
    }
    private val connectionListener = object : DeviceManager.ConnectionListener {
        override fun onConnected() {
            runOnUiThread {
                updateConnectionStateCard(ConnectionState.CONNECTED)
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                updateConnectionStateCard(ConnectionState.DISCONNECTED)
            }
        }

        override fun onStateChanged(state: ConnectionState) {
            runOnUiThread {
                updateConnectionStateCard(state)
            }
        }

        override fun onError(error: SDKError) {
            runOnUiThread {
                updateConnectionStateCard(ConnectionState.DISCONNECTED)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initChart()
        initBandCharts()
        initPSDControls()
        checkPermissions()
        startTimeUpdate()
        startPsdChartRenderLoop()
    }

    private fun initViews() {
        // 设备列表
        deviceAdapter = DeviceAdapter { device ->
            connectDevice(device)
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        // 按钮事件
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnClearLog.setOnClickListener { clearLog() }
        binding.btnRecordEdf.setOnClickListener { toggleRecording() }
        binding.btnSelectFirmwareFile.setOnClickListener { selectFirmwareFile() }
        binding.btnStartOta.setOnClickListener { startSmartEegOtaFromInput() }
        binding.btnCancelOta.setOnClickListener {
            val cancelled = sdk.getDeviceManager().cancelSmartEegOta()
            log("OTA cancel requested: $cancelled")
        }
        binding.btnQueryFirmwareVersion.setOnClickListener { queryFirmwareVersion() }
        updateConnectionStateCard(ConnectionState.DISCONNECTED)
        
        // 设置 FilteredEEGProcessor 回调，处理滤波后的数据并更新图表
        FilteredEEGProcessor.getInstance().setCallback { packet ->
            runOnUiThread {
                updateChartData(packet.samples)
            }
        }

        binding.tvPsdStatus.text = "INIT"
        binding.tvPsdStatus.setTextColor(Color.parseColor("#9E9E9E"))
        setPsdVisualState(PSDAnalyzer.SignalQuality.NOT_WORN, false)
    }

    private fun startTimeUpdate() {
        timeHandler.post(timeUpdateRunnable)
    }

    private fun updateTime() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        binding.tvTime.text = time
        updateFirmwareVersionDisplay()
    }

    private fun initChart() {
        binding.chartEEG.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            setDrawGridBackground(false)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#00FF00")
                setDrawGridLines(false)
                setDrawLabels(false)
                setDrawAxisLine(false)
                axisMinimum = 0f
                axisMaximum = maxDataPoints.toFloat()
            }
            
            axisLeft.apply {
                textColor = Color.parseColor("#333333")
                setDrawGridLines(true)
                gridColor = Color.parseColor("#1A1A1A")
                axisMinimum = -100f
                axisMaximum = 100f
                setDrawAxisLine(false)
                setDrawLabels(false)
            }
            
            axisRight.isEnabled = false
            
            // 初始化空数据
            data = LineData()
        }
    }

    // ==================== PSD 频段图表 ====================

    private fun initBandCharts() {
        val charts = mapOf(
            "delta" to Pair(binding.chartDelta, Color.parseColor("#E53935")),
            "theta" to Pair(binding.chartTheta, Color.parseColor("#FFB300")),
            "alpha" to Pair(binding.chartAlpha, Color.parseColor("#43A047")),
            "beta" to Pair(binding.chartBeta, Color.parseColor("#1E88E5")),
            "gamma" to Pair(binding.chartGamma, Color.parseColor("#AB47BC"))
        )

        for ((band, pair) in charts) {
            val (chart, color) = pair
            setupBandChart(chart, band, color)
        }
    }

    private fun setupBandChart(chart: LineChart, band: String, color: Int) {
        val axisMin = 0f
        val axisMax = resolveBandYAxisMax(band, currentPsdDisplayMode)
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)
            setViewPortOffsets(0f, 0f, 0f, 0f)
            setAutoScaleMinMaxEnabled(false)

            xAxis.apply {
                setDrawGridLines(false)
                setDrawLabels(false)
                setDrawAxisLine(false)
                axisMinimum = 0f
                axisMaximum = bandMaxPoints.toFloat()
            }

            axisLeft.apply {
                setDrawGridLines(false)
                setDrawLabels(false)
                setDrawAxisLine(false)
                axisMinimum = axisMin
                axisMaximum = axisMax
            }

            axisRight.isEnabled = false
            data = LineData(
                LineDataSet(mutableListOf(), band).apply {
                    this.color = color
                    lineWidth = 1f
                    setDrawCircles(false)
                    setDrawValues(false)
                    mode = LineDataSet.Mode.LINEAR
                }
            )
        }
    }

    private fun resolveBandYAxisMax(band: String, displayMode: DisplayMode): Float {
        if (displayMode == DisplayMode.REL_PERCENT) return 100f
        return when (band) {
            "delta" -> 50f
            "theta" -> 30f
            "alpha" -> 25f
            "beta" -> 20f
            "gamma" -> 15f
            else -> 40f
        }
    }

    private fun startPsdChartRenderLoop() {
        timeHandler.post(psdChartRenderRunnable)
    }

    private fun renderPsdCharts() {
        val charts = mapOf(
            "delta" to binding.chartDelta,
            "theta" to binding.chartTheta,
            "alpha" to binding.chartAlpha,
            "beta" to binding.chartBeta,
            "gamma" to binding.chartGamma
        )

        for ((band, chart) in charts) {
            val state = bandStates[band] ?: continue
            if (!state.initialized) continue
            appendBandChartValue(chart, band, nextDisplayedBandValue(state))
        }
    }

    private fun nextDisplayedBandValue(state: BandChartState): Float {
        val delta = state.targetValue - state.currentValue
        if (abs(delta) <= psdChartSnapThreshold) {
            state.currentValue = state.targetValue
            return state.currentValue
        }
        state.currentValue += delta * psdChartLerpFactor
        return state.currentValue
    }

    private fun appendBandChartValue(chart: LineChart, band: String, value: Float) {
        val state = bandStates[band] ?: return
        val lineData = chart.data ?: return

        val dataSet = lineData.getDataSetByIndex(0) as? LineDataSet ?: return
        state.entries.add(Entry(state.dataIndex++, value))
        if (state.entries.size > bandMaxPoints) {
            state.entries.removeAt(0)
        }
        dataSet.values = state.entries.toList()
        chart.xAxis.axisMaximum = maxOf(bandMaxPoints.toFloat(), state.dataIndex)
        chart.xAxis.axisMinimum = maxOf(0f, state.dataIndex - bandMaxPoints)
        lineData.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.setVisibleXRangeMaximum(bandMaxPoints.toFloat())
        chart.moveViewToX(maxOf(0f, state.dataIndex - bandMaxPoints))
        chart.invalidate()
    }

    private fun updateBandChartTargets(uiState: PsdUiState) {
        updateBandTarget("delta", uiState.deltaPower)
        updateBandTarget("theta", uiState.thetaPower)
        updateBandTarget("alpha", uiState.alphaPower)
        updateBandTarget("beta", uiState.betaPower)
        updateBandTarget("gamma", uiState.gammaPower)
    }

    private fun updatePsdDisplayMode(displayMode: DisplayMode) {
        if (currentPsdDisplayMode == displayMode) return
        currentPsdDisplayMode = displayMode

        val charts = mapOf(
            "delta" to binding.chartDelta,
            "theta" to binding.chartTheta,
            "alpha" to binding.chartAlpha,
            "beta" to binding.chartBeta,
            "gamma" to binding.chartGamma
        )

        for ((band, chart) in charts) {
            chart.axisLeft.axisMaximum = resolveBandYAxisMax(band, displayMode)
            chart.invalidate()
        }
        resetBandCharts()
    }

    private fun updateBandTarget(band: String, value: Double) {
        val state = bandStates[band] ?: return
        state.targetValue = value.toFloat()
        if (!state.initialized) {
            state.currentValue = state.targetValue
            state.initialized = true
        }
    }

    private fun resetBandCharts() {
        val charts = listOf(
            binding.chartDelta,
            binding.chartTheta,
            binding.chartAlpha,
            binding.chartBeta,
            binding.chartGamma
        )
        bandStates.values.forEach { state ->
            state.entries.clear()
            state.dataIndex = 0f
            state.currentValue = 0f
            state.targetValue = 0f
            state.initialized = false
        }
        charts.forEach { chart ->
            val lineData = chart.data ?: return@forEach
            val dataSet = lineData.getDataSetByIndex(0) as? LineDataSet ?: return@forEach
            dataSet.clear()
            lineData.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.xAxis.axisMinimum = 0f
            chart.xAxis.axisMaximum = bandMaxPoints.toFloat()
            chart.moveViewToX(0f)
            chart.invalidate()
        }
    }

    // ==================== PSD 控制面板 ====================

    private fun initPSDControls() {
        // Gain SeekBar: 0~99 → 0.1~10.0 (progress 9 = 1.0x)
        binding.seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = progressToGain(progress)
                binding.tvGainValue.text = String.format("%.1fx", gain)
                if (fromUser) {
                    updatePSDConfig(gain = gain)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Window SeekBar: 0~70 → 0.5~4.0s (progress 30 = 2.0s)
        binding.seekWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val window = progressToWindow(progress)
                binding.tvWindowValue.text = String.format("%.1fs", window)
                if (fromUser) {
                    updatePSDConfig(windowSeconds = window)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val currentConfig = psdAnalyzer.getConfig()
        binding.seekGain.progress = gainToProgress(currentConfig.gain)
        binding.seekWindow.progress = windowToProgress(currentConfig.windowSeconds)
        binding.tvGainValue.text = String.format("%.1fx", currentConfig.gain)
        binding.tvWindowValue.text = String.format("%.1fs", currentConfig.windowSeconds)
        binding.tvPsdStatus.text = resolvePsdModeLabel(currentConfig)
    }

    /** SeekBar progress (0~99) → Gain (0.1~10.0) */
    private fun progressToGain(progress: Int): Float {
        return PSDConfig.GAIN_MIN + (PSDConfig.GAIN_MAX - PSDConfig.GAIN_MIN) * progress / 99f
    }

    /** Gain (0.1~10.0) → SeekBar progress (0~99) */
    private fun gainToProgress(gain: Float): Int {
        val normalized = (gain - PSDConfig.GAIN_MIN) / (PSDConfig.GAIN_MAX - PSDConfig.GAIN_MIN)
        return (normalized * 99f).toInt().coerceIn(0, 99)
    }

    /** SeekBar progress (0~70) → Window (0.5~4.0s) */
    private fun progressToWindow(progress: Int): Float {
        return PSDConfig.WINDOW_MIN + (PSDConfig.WINDOW_MAX - PSDConfig.WINDOW_MIN) * progress / 70f
    }

    /** Window (0.5~4.0s) → SeekBar progress (0~70) */
    private fun windowToProgress(windowSeconds: Float): Int {
        val normalized = (windowSeconds - PSDConfig.WINDOW_MIN) / (PSDConfig.WINDOW_MAX - PSDConfig.WINDOW_MIN)
        return (normalized * 70f).toInt().coerceIn(0, 70)
    }

    private fun updatePSDConfig(
        gain: Float = psdAnalyzer.getConfig().gain,
        windowSeconds: Float = psdAnalyzer.getConfig().windowSeconds
    ) {
        val current = psdAnalyzer.getConfig()
        psdAnalyzer.updateConfig(
            current.copy(gain = gain, windowSeconds = windowSeconds)
        )
    }


    private fun isAwakeFrontalMode(config: PSDConfig): Boolean {
        return config.highpassFrequency >= 0.99
    }

    private fun resolvePsdModeLabel(config: PSDConfig): String {
        return if (isAwakeFrontalMode(config)) {
            "AWAKE FRONTAL"
        } else {
            "SLEEP RAW"
        }
    }

    // ==================== PSD 监听 ====================

    private fun setupPSDListener() {
        psdAnalyzer.addListener { result ->
            runOnUiThread {
                val uiState = result.toPsdUiState()
                updatePsdDisplayMode(uiState.displayMode)

                binding.tvDeltaPower.text = formatBandValue(uiState.deltaPower, uiState.displayMode)
                binding.tvThetaPower.text = formatBandValue(uiState.thetaPower, uiState.displayMode)
                binding.tvAlphaPower.text = formatBandValue(uiState.alphaPower, uiState.displayMode)
                binding.tvBetaPower.text = formatBandValue(uiState.betaPower, uiState.displayMode)
                binding.tvGammaPower.text = formatBandValue(uiState.gammaPower, uiState.displayMode)

                val statusColor = when (uiState.signalQuality) {
                    PSDAnalyzer.SignalQuality.GOOD -> Color.parseColor("#43A047")
                    PSDAnalyzer.SignalQuality.FAIR -> Color.parseColor("#FFB300")
                    PSDAnalyzer.SignalQuality.POOR -> Color.parseColor("#E53935")
                    PSDAnalyzer.SignalQuality.NOT_WORN -> Color.parseColor("#9E9E9E")
                }
                binding.tvPsdStatus.text = uiState.statusText
                binding.tvPsdStatus.setTextColor(statusColor)
                setPsdVisualState(uiState.signalQuality, uiState.isDataValid)

                updateBandChartTargets(uiState)
            }
        }
    }

    private fun formatBandValue(value: Double, displayMode: DisplayMode): String {
        return when (displayMode) {
            DisplayMode.ABS_UV -> String.format(Locale.getDefault(), "%.1f μV", value)
            DisplayMode.REL_PERCENT -> String.format(Locale.getDefault(), "%.1f %%", value)
        }
    }

    private fun setPsdVisualState(signalQuality: PSDAnalyzer.SignalQuality, isDataValid: Boolean) {
        val alpha = when (signalQuality) {
            PSDAnalyzer.SignalQuality.GOOD -> 1.0f
            PSDAnalyzer.SignalQuality.FAIR -> if (isDataValid) 0.92f else 0.82f
            PSDAnalyzer.SignalQuality.POOR -> 0.76f
            PSDAnalyzer.SignalQuality.NOT_WORN -> 0.40f
        }
        binding.tvDeltaPower.alpha = alpha
        binding.tvThetaPower.alpha = alpha
        binding.tvAlphaPower.alpha = alpha
        binding.tvBetaPower.alpha = alpha
        binding.tvGammaPower.alpha = alpha
        binding.chartDelta.alpha = alpha
        binding.chartTheta.alpha = alpha
        binding.chartAlpha.alpha = alpha
        binding.chartBeta.alpha = alpha
        binding.chartGamma.alpha = alpha
    }

    // ==================== 权限和 SDK ====================

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        } else {
            initSDK()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initSDK()
            } else {
                log("Permission denied", isError = true)
            }
        }
    }

    private fun initSDK() {
        log("Initializing SDK...")
        
        try {
            // 从 assets 加载 License
            val license = License.fromAssets(this, "neurometa_license.json")
            
            sdk.init(this, license, object : NeuroMetaSDK.InitCallback {
                override fun onSuccess() {
                    runOnUiThread {
                        log("SDK initialized successfully", isSuccess = true)
                        registerSdkListeners()
                        setupDataListener()
                        setupPSDListener()
                    }
                }

                override fun onError(error: SDKError) {
                    runOnUiThread {
                        log("SDK init failed: ${error.message}", isError = true)
                    }
                }
            }, allowDevLicense = true)
        } catch (e: Exception) {
            log("Failed to load license: ${e.message}", isError = true)
        }
    }

    private fun registerSdkListeners() {
        if (sdkListenersRegistered) return
        sdk.getDeviceManager().addSmartEegOtaListener(otaListener)
        sdk.getDeviceManager().addConnectionListener(connectionListener)
        updateConnectionStateCard(sdk.getDeviceManager().getConnectionState())
        sdkListenersRegistered = true
    }

    private fun setupDataListener() {
        val dataCollector = sdk.getDataCollector()
        sdk.getDeviceManager().addDataListener(rawBleDebugListener)
        
        // 未滤波数据 → PSDAnalyzer + FilteredEEGProcessor
        dataCollector.addUnfilteredDataListener(object : DataCollector.UnfilteredDataListener {
            override fun onUnfilteredData(packet: EEGDataPacket) {
                // 喂给 PSD 分析器
                packet.channelData[0]?.let { samples ->
                    psdAnalyzer.addSamples(samples)
                }

                runOnUiThread {
                    handleEEGData(packet)
                }
            }
        })

        dataCollector.addUnfilteredDataListener(parsedEegDebugListener)

        // 设备状态监听器（电量、佩戴状态）
        dataCollector.addDeviceStatusListener(object : DataCollector.DeviceStatusListener {
            override fun onDeviceStatus(status: DeviceStatus) {
                runOnUiThread {
                    handleDeviceStatus(status)
                }
            }
        })
    }

    // ==================== 扫描和连接 ====================

    private fun startScan() {
        log("Scanning for devices...")
        binding.btnScan.isEnabled = false
        binding.rvDevices.visibility = android.view.View.VISIBLE
        deviceAdapter.clear()
        updateDeviceCount()

        sdk.getDeviceManager().startScan(
            timeout = 3000L,
            callback = object : DeviceManager.ScanCallback {
                override fun onDeviceFound(device: Device) {
                    runOnUiThread {
                        log("Found: ${device.name ?: device.id}")
                        deviceAdapter.addDevice(device)
                        updateDeviceCount()
                    }
                }

                override fun onComplete(devices: List<Device>) {
                    runOnUiThread {
                        binding.btnScan.isEnabled = true
                    }
                }

                override fun onError(error: SDKError) {
                    runOnUiThread {
                        log("Scan failed: ${error.message}", isError = true)
                        binding.btnScan.isEnabled = true
                    }
                }
            }
        )
    }

    private fun updateDeviceCount() {
        val count = deviceAdapter.getDeviceCount()
        binding.tvDeviceCount.text = "$count FOUND"
    }

    private fun connectDevice(device: Device) {
        log("Connecting to ${device.id}...")

        sdk.getDeviceManager().connect(
            deviceId = device.id,
            deviceName = device.name,
            callback = object : DeviceManager.ConnectionCallback {
                override fun onConnected() {
                    runOnUiThread {
                        log("Connected! Starting data stream", isSuccess = true)
                        connectedDevice = sdk.getDeviceManager().getCurrentDevice() ?: device
                        updateFirmwareVersionDisplay()
                        deviceAdapter.setConnectedDevice(device.id)
                        binding.btnScan.visibility = android.view.View.GONE
                        binding.btnDisconnect.visibility = android.view.View.VISIBLE
                        scheduleFirmwareVersionQuery()
                    }
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        log("Disconnected")
                        handleDisconnect()
                    }
                }

                override fun onError(error: SDKError) {
                    runOnUiThread {
                        log("Connection failed: ${error.message}", isError = true)
                    }
                }
            }
        )
    }

    private fun disconnect() {
        log("Disconnecting...")
        sdk.getDeviceManager().disconnect()
        handleDisconnect()
    }

    private fun handleDisconnect() {
        connectedDevice = null
        latestBatteryLevel = -1
        deviceAdapter.setConnectedDevice(null)
        binding.btnScan.visibility = android.view.View.VISIBLE
        binding.btnDisconnect.visibility = android.view.View.GONE
        binding.tvBatteryLevel.text = "--"
        binding.tvWearStatus.text = "--"
        binding.tvFirmwareVersion.text = "--"
        binding.tvFirmwareVersion.setTextColor(Color.parseColor("#9E9E9E"))
        binding.tvSamplingRate.text = "-- Hz LIVE"
        binding.tvPsdStatus.text = "DISCONNECTED"
        binding.tvPsdStatus.setTextColor(Color.parseColor("#9E9E9E"))
        setPsdVisualState(PSDAnalyzer.SignalQuality.NOT_WORN, false)

        // 重置 PSD
        psdAnalyzer.reset()
        resetBandCharts()
        
        if (isRecording) {
            stopRecording()
        }
    }

    private fun updateConnectionStateCard(state: ConnectionState) {
        binding.tvConnectionState.text = formatConnectionState(state)
        binding.tvConnectionState.setTextColor(connectionStateColor(state))
    }

    private fun formatConnectionState(state: ConnectionState): String {
        return when (state) {
            ConnectionState.DISCONNECTED -> "DISCONNECTED"
            ConnectionState.SCANNING -> "SCANNING"
            ConnectionState.CONNECTING -> "CONNECTING"
            ConnectionState.CONNECTED -> "CONNECTED"
            ConnectionState.DISCONNECTING -> "DISCONNECTING"
            ConnectionState.RECONNECTING -> "RECONNECTING"
        }
    }

    private fun connectionStateColor(state: ConnectionState): Int {
        return when (state) {
            ConnectionState.CONNECTED -> Color.parseColor("#00FF00")
            ConnectionState.DISCONNECTED -> Color.parseColor("#9E9E9E")
            ConnectionState.SCANNING,
            ConnectionState.CONNECTING,
            ConnectionState.DISCONNECTING,
            ConnectionState.RECONNECTING -> Color.parseColor("#E53935")
        }
    }

    private fun handleEEGData(packet: EEGDataPacket) {
        // 更新采样率
        binding.tvSamplingRate.text = "${packet.samplingRate} Hz LIVE"

        // 将原始数据传给滤波处理器
        packet.channelData[0]?.let { samples ->
            FilteredEEGProcessor.getInstance().addSamples(samples)
        }
    }

    private fun handleDeviceStatus(status: DeviceStatus) {
        psdAnalyzer.updateWearState(status.wear)

        // 更新电池电量
        if (status.batteryLevel >= 0) {
            latestBatteryLevel = status.batteryLevel
            binding.tvBatteryLevel.text = "${status.batteryLevel}%"
            val batteryColor = when {
                status.batteryLevel > 50 -> Color.parseColor("#00FF00")
                status.batteryLevel > 20 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#E53935")
            }
            binding.tvBatteryLevel.setTextColor(batteryColor)
        }

        // 更新佩戴状态
        if (status.wear) {
            binding.tvWearStatus.text = "WEARING"
            binding.tvWearStatus.setTextColor(Color.parseColor("#00FF00"))
        } else {
            binding.tvWearStatus.text = "NOT WORN"
            binding.tvWearStatus.setTextColor(Color.parseColor("#E53935"))
            binding.tvPsdStatus.text = "AWAKE FRONTAL · NOT WORN"
            binding.tvPsdStatus.setTextColor(Color.parseColor("#9E9E9E"))
            setPsdVisualState(PSDAnalyzer.SignalQuality.NOT_WORN, false)
        }
    }

    private fun scheduleFirmwareVersionQuery() {
        timeHandler.postDelayed({ queryFirmwareVersion() }, 800)
    }

    private fun queryFirmwareVersion() {
        if (!sdk.isInitialized()) {
            log("SDK is not initialized", isError = true)
            return
        }
        val requested = sdk.getDeviceManager().querySmartEegFirmwareVersion()
        log("Firmware version query requested: $requested")
        timeHandler.postDelayed({ updateFirmwareVersionDisplay() }, 500)
        timeHandler.postDelayed({ updateFirmwareVersionDisplay() }, 1500)
    }

    private fun updateFirmwareVersionDisplay() {
        if (!sdk.isInitialized()) {
            binding.tvFirmwareVersion.text = "--"
            binding.tvFirmwareVersion.setTextColor(Color.parseColor("#9E9E9E"))
            return
        }
        val device = sdk.getDeviceManager().getCurrentDevice()
        if (device != null) {
            connectedDevice = device
        }
        val version = device?.firmwareVersion?.takeIf { it.isNotBlank() }
        binding.tvFirmwareVersion.text = version ?: "--"
        binding.tvFirmwareVersion.setTextColor(
            if (version == null) Color.parseColor("#9E9E9E") else Color.parseColor("#00FF00")
        )
    }

    // ==================== EEG 主图表 ====================

    private fun updateChartData(samples: DoubleArray) {
        val lineData = binding.chartEEG.data ?: return
        
        var dataSet = lineData.getDataSetByIndex(0) as? LineDataSet
        if (dataSet == null) {
            dataSet = LineDataSet(chartEntries, "EEG").apply {
                color = Color.parseColor("#00FF00")
                lineWidth = 1f
                setDrawCircles(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
            }
            lineData.addDataSet(dataSet)
        }

        for (sample in samples) {
            chartEntries.add(Entry(dataIndex++, sample.toFloat()))
            if (chartEntries.size > maxDataPoints) {
                chartEntries.removeAt(0)
            }
        }

        dataSet.values = chartEntries.toList()
        lineData.notifyDataChanged()
        binding.chartEEG.notifyDataSetChanged()
        
        binding.chartEEG.setVisibleXRangeMaximum(maxDataPoints.toFloat())
        if (chartEntries.size >= maxDataPoints) {
            binding.chartEEG.xAxis.axisMinimum = dataIndex - maxDataPoints
            binding.chartEEG.xAxis.axisMaximum = dataIndex
            binding.chartEEG.moveViewToX(dataIndex - maxDataPoints)
        } else {
            binding.chartEEG.moveViewToX(0f)
        }
        binding.chartEEG.invalidate()
    }

    // ==================== EDF 录制 ====================

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (connectedDevice == null) {
            Toast.makeText(this, "Please connect a device first", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "eeg_$timestamp.edf"
        val outputDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "edf")
        outputDir.mkdirs()
        val filePath = File(outputDir, fileName).absolutePath

        log("Starting EDF recording: $fileName")

        val recorder = sdk.getEdfRecorder()
        val success = recorder.startRecording(
            outputPath = filePath,
            callback = object : EdfRecorder.RecordingCallback {
                override fun onRecordingStarted(filePath: String) {
                    runOnUiThread {
                        log("Recording started", isSuccess = true)
                        isRecording = true
                        binding.btnRecordEdf.text = "Stop Recording"
                        binding.btnRecordEdf.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.accent_red)
                        )
                        binding.tvRecordStatus.visibility = android.view.View.VISIBLE
                        binding.tvRecordStatus.text = "Recording: $fileName"
                    }
                }

                override fun onRecordingStopped(filePath: String, recordCount: Int) {
                    runOnUiThread {
                        log("Recording stopped: $recordCount records")
                        isRecording = false
                        binding.btnRecordEdf.text = "Record EDF"
                        binding.btnRecordEdf.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.text_secondary)
                        )
                        binding.tvRecordStatus.visibility = android.view.View.GONE
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        log("Recording error: $message", isError = true)
                        isRecording = false
                        binding.tvRecordStatus.visibility = android.view.View.GONE
                    }
                }
            }
        )

        if (!success) {
            log("Failed to start recording", isError = true)
        }
    }

    private fun stopRecording() {
        log("Stopping recording...")
        sdk.getEdfRecorder().stopRecording()
    }

    private fun selectFirmwareFile() {
        firmwarePicker.launch(arrayOf("application/octet-stream", "application/macbinary", "*/*"))
    }

    private fun cacheSelectedFirmware(uri: Uri) {
        try {
            val fileName = resolveFirmwareFileName(uri)
            val outputDir = File(cacheDir, "firmware")
            outputDir.mkdirs()
            val outputFile = File(outputDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to open selected file")
            selectedFirmwareCachePath = outputFile.absolutePath
            binding.etOtaFilePath.setText(outputFile.absolutePath)
            log("Selected firmware: $fileName (${outputFile.length()} bytes)", isSuccess = true)
        } catch (e: Exception) {
            selectedFirmwareCachePath = null
            log("Failed to cache firmware: ${e.message}", isError = true)
        }
    }

    private fun resolveFirmwareFileName(uri: Uri): String {
        val fallback = "firmware_${System.currentTimeMillis()}.bin"
        val lastPath = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return lastPath ?: fallback
    }

    private fun startSmartEegOtaFromInput() {
        val typedPath = binding.etOtaFilePath.text?.toString()?.trim().orEmpty()
        val path = selectedFirmwareCachePath?.takeIf { typedPath == it } ?: typedPath
        if (path.isBlank()) {
            log("Firmware path is required", isError = true)
            return
        }
        if (latestBatteryLevel < 0) {
            log("Battery level unavailable; wait for battery data before OTA", isError = true)
            return
        }
        if (latestBatteryLevel < MIN_OTA_BATTERY_LEVEL) {
            log("Battery must be at least $MIN_OTA_BATTERY_LEVEL% for OTA; current=$latestBatteryLevel%", isError = true)
            return
        }
        val ok = sdk.getDeviceManager().startSmartEegOta(
            filePath = path,
            awaitAck = binding.cbOtaAwaitAck.isChecked
        )
        log("OTA start requested: $ok")
    }

    private fun formatOtaStatus(status: SmartEEGOtaStatus): String {
        val base = "${status.state} ${status.progress}% (${status.sentChunks}/${status.totalChunks})"
        return if (status.error.isNullOrBlank()) base else "$base ${status.error}"
    }

    // ==================== 日志 ====================

    private fun clearLog() {
        binding.tvLog.text = ""
    }

    private fun log(message: String, isSuccess: Boolean = false, isError: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logText = "[$time] $message\n"
        
        val spannable = SpannableStringBuilder(logText)
        val color = when {
            isSuccess -> Color.parseColor("#00FF00")
            isError -> Color.parseColor("#E53935")
            else -> Color.parseColor("#888888")
        }
        spannable.setSpan(
            ForegroundColorSpan(color),
            0, logText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        binding.tvLog.append(spannable)
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timeHandler.removeCallbacks(timeUpdateRunnable)
        timeHandler.removeCallbacks(psdChartRenderRunnable)
        if (sdk.isInitialized()) {
            sdk.getDeviceManager().removeDataListener(rawBleDebugListener)
            sdk.getDeviceManager().removeConnectionListener(connectionListener)
            sdk.getDeviceManager().removeSmartEegOtaListener(otaListener)
            sdk.getDataCollector().removeUnfilteredDataListener(parsedEegDebugListener)
        }
        psdAnalyzer.cleanup()
        if (sdk.isInitialized() && isRecording) {
            sdk.getEdfRecorder().stopRecording()
        }
        if (sdk.isInitialized()) {
            sdk.getDeviceManager().disconnect()
        }
    }
}
