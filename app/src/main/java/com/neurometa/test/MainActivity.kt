package com.neurometa.test

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.neurometa.sdk.NeuroMetaSDK
import com.neurometa.sdk.data.DataCollector
import com.neurometa.sdk.data.FilteredEEGProcessor
import com.neurometa.sdk.device.DeviceManager
import com.neurometa.sdk.edf.EdfRecorder
import com.neurometa.sdk.model.Device
import com.neurometa.sdk.model.DeviceStatus
import com.neurometa.sdk.model.EEGDataPacket
import com.neurometa.sdk.auth.License
import com.neurometa.sdk.model.SDKError
import com.neurometa.test.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceAdapter
    
    private val sdk: NeuroMetaSDK by lazy { NeuroMetaSDK.getInstance() }
    private var connectedDevice: Device? = null
    private var isRecording = false
    
    // 图表数据 (5秒 @50Hz = 250 点)
    private val chartEntries = mutableListOf<Entry>()
    private var dataIndex = 0f
    private val maxDataPoints = 250

    // 时间更新 Handler
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateTime()
            timeHandler.postDelayed(this, 1000)
        }
    }



    companion object {
        private const val REQUEST_PERMISSIONS = 100
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initChart()
        checkPermissions()
        startTimeUpdate()
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
        
        // 设置 FilteredEEGProcessor 回调，处理滤波后的数据并更新图表
        FilteredEEGProcessor.getInstance().setCallback { packet ->
            runOnUiThread {
                updateChartData(packet.samples)
            }
        }
    }

    private fun startTimeUpdate() {
        timeHandler.post(timeUpdateRunnable)
    }

    private fun updateTime() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        binding.tvTime.text = time
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
                        setupDataListener()
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

    private fun setupDataListener() {
        val dataCollector = sdk.getDataCollector()
        
        // 实时数据监听器（滤波后，用于UI显示）
        dataCollector.addRealtimeDataListener(object : DataCollector.RealtimeDataListener {
            override fun onRealtimeData(packet: EEGDataPacket) {
                runOnUiThread {
                    handleEEGData(packet)
                }
            }
        })
        
        // 未滤波数据监听器（原始数据，用于调试）
        dataCollector.addUnfilteredDataListener(object : DataCollector.UnfilteredDataListener {
            override fun onUnfilteredData(packet: EEGDataPacket) {
                // 输出原始数据日志（每个包都输出）
                val samples = packet.channelData[0] ?: return
                val firstSamples = samples.take(5).map { String.format("%.1f", it) }
                runOnUiThread {
                    log("[RAW] seq=${packet.sequenceNumber}, ch=0, data=${firstSamples.joinToString(",")}")
                }
            }
        })

        // 设备状态监听器（电量、佩戴状态）
        dataCollector.addDeviceStatusListener(object : DataCollector.DeviceStatusListener {
            override fun onDeviceStatus(status: DeviceStatus) {
                runOnUiThread {
                    handleDeviceStatus(status)
                }
            }
        })
    }

    private fun startScan() {
        log("Scanning for devices...")
        binding.btnScan.isEnabled = false
        binding.rvDevices.visibility = android.view.View.VISIBLE
        deviceAdapter.clear()
        updateDeviceCount()

        sdk.getDeviceManager().startScan(
            timeout = 3000L,  // 扫描3秒
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
            callback = object : DeviceManager.ConnectionCallback {
                override fun onConnected() {
                    runOnUiThread {
                        log("Connected! Starting data stream", isSuccess = true)
                        connectedDevice = device
                        deviceAdapter.setConnectedDevice(device.id)
                        binding.btnScan.visibility = android.view.View.GONE
                        binding.btnDisconnect.visibility = android.view.View.VISIBLE
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
        deviceAdapter.setConnectedDevice(null)
        binding.btnScan.visibility = android.view.View.VISIBLE
        binding.btnDisconnect.visibility = android.view.View.GONE
        binding.tvBatteryLevel.text = "--"
        binding.tvWearStatus.text = "--"
        binding.tvSamplingRate.text = "-- Hz LIVE"
        
        if (isRecording) {
            stopRecording()
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
        // 更新电池电量
        if (status.batteryLevel >= 0) {
            binding.tvBatteryLevel.text = "${status.batteryLevel}%"
            // 根据电量设置颜色
            val batteryColor = when {
                status.batteryLevel > 50 -> Color.parseColor("#00FF00")  // 绿色
                status.batteryLevel > 20 -> Color.parseColor("#FF9800")  // 橙色
                else -> Color.parseColor("#E53935")  // 红色
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
        }
    }

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

        // 添加新数据点
        for (sample in samples) {
            chartEntries.add(Entry(dataIndex++, sample.toFloat()))
            
            // 保持最大点数
            if (chartEntries.size > maxDataPoints) {
                chartEntries.removeAt(0)
            }
        }

        // 更新数据集
        dataSet.values = chartEntries.toList()
        lineData.notifyDataChanged()
        binding.chartEEG.notifyDataSetChanged()
        
        // 设置可见范围并滚动
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

    private fun clearLog() {
        binding.tvLog.text = ""
    }

    private fun log(message: String, isSuccess: Boolean = false, isError: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logText = "[$time] $message\n"
        
        val spannable = SpannableStringBuilder(logText)
        val color = when {
            isSuccess -> Color.parseColor("#00FF00")  // 绿色
            isError -> Color.parseColor("#E53935")    // 红色
            else -> Color.parseColor("#888888")       // 灰色
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
        if (isRecording) {
            sdk.getEdfRecorder().stopRecording()
        }
        sdk.getDeviceManager().disconnect()
    }
}
