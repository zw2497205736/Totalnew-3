package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面
 * 控制超声波发射、接收、校准和显示检测结果
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    // UI 组件
    private lateinit var tvStatus: TextView
    private lateinit var tvState: TextView
    private lateinit var tvCalibrationProgress: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvCumulativeDistance: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCalibrate: Button
    
    // 核心组件
    private lateinit var ultrasonicGenerator: UltrasonicGenerator
    private lateinit var ultrasonicRecorder: UltrasonicRecorder
    private lateinit var proximityDetector: ProximityDetector  // 能量检测器
    private lateinit var phaseRangeFinder: PhaseRangeFinder  // 相位测距器
    private lateinit var fusedDetector: FusedProximityDetector  // 融合检测器
    private lateinit var audioManager: AudioManager  // 音频管理器，用于路由到听筒
    private lateinit var signalProcessor: SignalProcessor  // 信号处理器
    private var wavWriter: WavWriter? = null  // WAV 文件写入器
    private var llapCsvLogger: LlapCsvLogger? = null  // LLAP 调试CSV日志
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var isRunning = false
    private var isCalibrated = false  // 是否已完成校准
    private var cumulativeDistance = 0f  // 累积距离变化 (mm)，从0开始不重置
    private var frameCount = 0  // 用于性能监控的帧计数器
    
    // 音量更新定时器
    private val volumeUpdateRunnable = object : Runnable {
        override fun run() {
            updateVolumeDisplay()
            mainHandler.postDelayed(this, 16)  // 60fps 实时更新
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        Log.i(TAG, "========================================")
        Log.i(TAG, "应用启动 - 超声波距离检测系统")
        Log.i(TAG, "音频配置: 下发下收模式 (同侧直达)")
        Log.i(TAG, "  - 扬声器: 底部扬声器 (USAGE_MEDIA + speakerphoneOn)")
        Log.i(TAG, "  - 麦克风: 底部麦克风 (UNPROCESSED + ADC_L/AIN0)")
        Log.i(TAG, "  - 采样率: 48000 Hz")
        Log.i(TAG, "========================================")
        
        // 检查并配置底层硬件（需要 root）
        checkAndSetupHardware()
        
        initViews()
        initComponents()
        checkPermissions()
        
        // 启动音量监控
        mainHandler.post(volumeUpdateRunnable)
    }
    
    /**
     * 检查 root 权限并配置底层音频硬件
     * 完整流程：
     * 1. 检查 root 权限
     * 2. 重置音频硬件到默认状态
     * 3. 禁用音频前端处理（保护超声波信号）
     * 4. 配置底部扬声器
     * 5. 配置底部麦克风（ADC_L + AIN0）
     */
    private fun checkAndSetupHardware() {
        Thread {
            Log.i(TAG, "========================================")
            Log.i(TAG, "开始配置底层音频硬件（应用内自动执行）")
            Log.i(TAG, "========================================")
            
            // 尝试应用内配置
            if (AudioHardwareController.hasRootPermission()) {
                Log.i(TAG, "✓ 检测到 root 权限，开始自动配置...")
                
                // 第一步：重置硬件配置到默认状态
                AudioHardwareController.resetAudioHardware()
                
                // 第二步：配置顶部扬声器（听筒）
                val speakerOk = AudioHardwareController.setupTopSpeaker()
                
                // 第三步：配置顶部麦克风（包含禁用前端处理）
                val micOk = AudioHardwareController.setupTopMicrophone()
                
                if (speakerOk && micOk) {
                    Log.i(TAG, "========================================")
                    Log.i(TAG, "✓✓✓ 底层硬件配置完成！✓✓✓")
                    Log.i(TAG, "已启用下发下收模式：")
                    Log.i(TAG, "  ✓ 扬声器：底部扬声器")
                    Log.i(TAG, "  ✓ 麦克风：底部麦克风 (ADC_L + AIN0)")
                    Log.i(TAG, "  ✓ 前端处理：已禁用 (保护超声波)")
                    Log.i(TAG, "========================================")
                    
                    // 在主线程显示提示
                    mainHandler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "✓ 硬件配置成功！下发下收模式",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Log.w(TAG, "========================================")
                    Log.w(TAG, "⚠ 部分硬件配置失败")
                    Log.w(TAG, "扬声器配置: ${if (speakerOk) "成功" else "失败"}")
                    Log.w(TAG, "麦克风配置: ${if (micOk) "成功" else "失败"}")
                    Log.w(TAG, "========================================")
                    
                    mainHandler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "⚠ 硬件配置部分失败，可能影响检测效果",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Log.i(TAG, "========================================")
                Log.i(TAG, "ℹ️ 应用内无法获取 root 权限（正常现象）")
                Log.i(TAG, "这是 Android 安全机制的限制")
                Log.i(TAG, "")
                Log.i(TAG, "✓ 如果你已通过 adb 运行配置脚本，硬件配置已生效！")
                Log.i(TAG, "")
                Log.i(TAG, "验证方法：")
                Log.i(TAG, "  adb shell \"tinymix | grep PGA_L_Mux\"")
                Log.i(TAG, "  （期望输出：AIN0 = 底部麦克风）")
                Log.i(TAG, "")
                Log.i(TAG, "如果尚未配置，请运行：")
                Log.i(TAG, "  ./scripts/start_app_with_top_mic.sh")
                Log.i(TAG, "========================================")
                
                // 不显示 Toast，避免干扰用户
                // 如果硬件已通过脚本配置，应用可以正常使用
            }
        }.start()
    }
    
    /**
     * 初始化视图
     */
    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvState = findViewById(R.id.tvState)
        tvCalibrationProgress = findViewById(R.id.tvCalibrationProgress)
        tvVolume = findViewById(R.id.tvVolume)
        tvCumulativeDistance = findViewById(R.id.tvCumulativeDistance)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        
        // 初始显示当前音量
        updateVolumeDisplay()
        
        btnStart.setOnClickListener { startDetection() }
        btnStop.setOnClickListener { stopDetection() }
        btnCalibrate.setOnClickListener { startCalibration() }
        
        // 长按校准按钮重置累积距离
        btnCalibrate.setOnLongClickListener {
            cumulativeDistance = 0f
            tvCumulativeDistance.text = "累积距离: 0.0 mm"
            Toast.makeText(this, "✓ 累积距离已重置", Toast.LENGTH_SHORT).show()
            true
        }
        
        updateUI()
    }
    
    /**
     * 初始化核心组件
     */
    private fun initComponents() {
        ultrasonicGenerator = UltrasonicGenerator()
        ultrasonicRecorder = UltrasonicRecorder()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        signalProcessor = SignalProcessor()  // 信号处理器
        
        // 初始化检测器
        proximityDetector = ProximityDetector(this)  // 能量检测器,传入Context用于获取音量
        proximityDetector.onCalibrationComplete = { baseline ->
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volumePercent = (currentVolume * 100 / maxVolume)
            
            // 打印到标准输出(可在电脑控制台看到)
            println("========================================")
            println("[校准完成]")
            println("  基线幅度: ${String.format("%.2f", baseline)}")
            println("  当前音量: $currentVolume/$maxVolume ($volumePercent%)")
            println("  音量流类型: STREAM_MUSIC")
            println("========================================")
            System.out.flush()  // 强制刷新输出
            
            // 保存到文件
            try {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val calibrationData = "$timestamp,音量=$currentVolume/$maxVolume ($volumePercent%),基线=${String.format("%.4f", baseline)}\n"
                val file = java.io.File(filesDir, "calibration_history.csv")
                file.appendText(calibrationData)
                Log.i(TAG, "校准数据已保存到文件: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "保存校准数据失败: ${e.message}")
            }
            
            // 在UI线程中更新Toast显示校准信息
            runOnUiThread {
                val calibrationInfo = """
                    校准完成！
                    
                    基线幅度: ${String.format("%.2f", baseline)}
                    当前音量: $currentVolume/$maxVolume ($volumePercent%)
                    音量流类型: STREAM_MUSIC
                """.trimIndent()
                
                Toast.makeText(this, calibrationInfo, Toast.LENGTH_LONG).show()
            }
            
            // 同时也在日志中记录
            Log.i(TAG, "[校准完成] 基线=${String.format("%.2f", baseline)}, 音量=$currentVolume/$maxVolume ($volumePercent%)")
        }
        
        phaseRangeFinder = PhaseRangeFinder()    // 相位测距器
        fusedDetector = FusedProximityDetector(  // 融合检测器
            context = this,
            energyDetector = proximityDetector,
            phaseRangeFinder = phaseRangeFinder,
            signalProcessor = signalProcessor
        )
        
        // 配置：底部扬声器 + 底部麦克风模式（下发下收）
        ultrasonicRecorder.setMicType(UltrasonicRecorder.MicType.UNPROCESSED)
        Log.i(TAG, "========================================")
        Log.i(TAG, "融合检测系统初始化")
        Log.i(TAG, "  能量法: 快速状态分类 (FAR/NEAR/VERY_NEAR)")
        Log.i(TAG, "  相位法: 精确相对距离 (±1-2mm 精度)")
        Log.i(TAG, "  融合策略: 双重验证 + 智能重置")
        Log.i(TAG, "  音频配置: 底部扬声器 + 底部麦克风 (下发下收)")
        Log.i(TAG, "  采样率: 48000 Hz")
        Log.i(TAG, "  超声波: 20000-23150 Hz (10 频率)")
        Log.i(TAG, "========================================")
        
        // 设置音频数据回调 (Java AudioRecord) - 已禁用，使用 C++ 原生录音
        /*
        ultrasonicRecorder.setAudioDataCallback { audioData ->
            // 应用高通滤波器，去除人声和低频噪声（仅用于能量法）
            val filteredData = signalProcessor.highPassFilter(audioData)
            
            // 如果已经校准完成，保存过滤后的信号到 WAV 文件
            if (isCalibrated && wavWriter?.isRecording() == true) {
                wavWriter?.writeSamples(filteredData)
            }
            
            // 【关键】传给融合检测器处理
            // - 能量法用filteredData（需要去除低频噪声）
            // - 相位法用audioData原始数据（I/Q解调自带带通滤波）
            fusedDetector.processAudioData(filteredData, audioData)
            
            // 更新 UI（校准进度）
            if (!proximityDetector.isCalibrated()) {
                mainHandler.post {
                    val progress = (proximityDetector.getCalibrationProgress() * 100).toInt()
                    tvCalibrationProgress.text = "校准进度: $progress%"
                }
            } else if (!isCalibrated) {
                // 校准刚完成，启动 WAV 录制
                isCalibrated = true
                startWavRecording()
            }
        }
        */

        // 设置原生音频数据回调 (C++ OpenSL ES)
        NativeAudioRecorder.setCallback(object : NativeAudioRecorder.Callback {
            override fun onAudioData(audioData: ShortArray) {
                // 性能监控：测量高通滤波器处理时间
                val startFilter = System.nanoTime()
                val filteredData = signalProcessor.highPassFilter(audioData)
                val filterTime = (System.nanoTime() - startFilter) / 1_000_000.0
                
                // 性能监控：测量融合检测处理时间
                val startProcess = System.nanoTime()
                fusedDetector.processAudioData(filteredData, audioData)
                val processTime = (System.nanoTime() - startProcess) / 1_000_000.0
                
                // 每隔100帧（约8.5秒）记录一次性能数据
                frameCount++
                if (frameCount % 100 == 0) {
                    Log.d("Performance", "Filter: ${String.format("%.2f", filterTime)}ms, Process: ${String.format("%.2f", processTime)}ms, Total: ${String.format("%.2f", filterTime + processTime)}ms")
                }
                
                if (isCalibrated && wavWriter?.isRecording() == true) {
                    wavWriter?.writeSamples(filteredData)
                }
                if (!proximityDetector.isCalibrated()) {
                    mainHandler.post {
                        val progress = (proximityDetector.getCalibrationProgress() * 100).toInt()
                        tvCalibrationProgress.text = "校准进度: $progress%"
                    }
                } else if (!isCalibrated) {
                    isCalibrated = true
                    startWavRecording()
                }
            }
        })
        

        // 设置状态变化回调 (记录日志)
        fusedDetector.onStateChanged = { state, energyRatio ->
            Log.i(TAG, "[状态变化] $state, 能量=$energyRatio")
        }
        
        // 设置实时数据更新回调 (更新UI)
        fusedDetector.onDataUpdate = { state, energyRatio, relDist, timeInState ->
            runOnUiThread {
                updateFusedStateUI(state, energyRatio, relDist, timeInState)
            }
        }
        
        // 设置相对距离重置回调 (累加到累积距离)
        fusedDetector.onRelativeDistanceReset = { relDist ->
            cumulativeDistance += relDist
            runOnUiThread {
                tvCumulativeDistance.text = "累积距离: %.1f mm".format(cumulativeDistance)
            }
            Log.i(TAG, "[距离重置] 相对距离=$relDist mm, 累积距离=$cumulativeDistance mm")
        }

        // 设置 LLAP 逐帧指标回调（写入CSV，便于低音量问题分析）
        fusedDetector.onLlapFrameMetrics = { metrics ->
            llapCsvLogger?.append(metrics)
        }
    }
    
    /**
     * 检查权限
     */
    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                notGranted.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要录音权限才能使用", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * 开始校准
     */
    private fun startCalibration() {
        if (!isRunning) {
            Toast.makeText(this, "请先点击开始检测", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(
            this,
            "开始校准：请将手机放在空旷处，不要遮挡",
            Toast.LENGTH_LONG
        ).show()
        
        proximityDetector.startCalibration()
        tvCalibrationProgress.text = "校准进度: 0%"
    }
    
    /**
     * 开始检测
     */
    private fun startDetection() {
        if (isRunning) {
            Toast.makeText(this, "已在运行中", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "请先授予录音权限", Toast.LENGTH_SHORT).show()
            checkPermissions()
            return
        }
        
        Log.d(TAG, "开始检测...")
        
        // 重置信号处理器的滤波器状态
        signalProcessor.resetFilter()
        Log.d(TAG, "✓ 高通滤波器已重置（截止频率: 15kHz）")
        
        // 0. 重新配置底层硬件（确保使用顶部麦克风）
        // 注意：这需要 root 权限，如果失败请先运行配置脚本
        Thread {
            Log.i(TAG, "→ 检测前重新配置硬件...")
            val micOk = AudioHardwareController.setupTopMicrophone()
            val speakerOk = AudioHardwareController.setupTopSpeaker()
            
            mainHandler.post {
                if (micOk && speakerOk) {
                    Log.i(TAG, "  ✓ 硬件重新配置成功")
                    Toast.makeText(
                        this@MainActivity,
                        "✓ 硬件已配置：下发下收模式",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.w(TAG, "  ⚠ 硬件配置失败（应用内无 root 权限）")
                    Toast.makeText(
                        this@MainActivity,
                        "⚠ 请先运行: ./scripts/start_app_with_top_mic.sh\n然后重新开始检测",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
        
        // 1. 配置音频路由到底部扬声器+底部麦克风（下发下收模式）
        setupBottomSpeakerMode()
        
        // 🔊 播放测试音（可选）- 用于验证扬声器位置
        // 取消注释下面这行来播放 3 秒测试音（1kHz，可听见）
        // AudioTestHelper.playTestTone(useEarpiece = false)
        // Thread.sleep(3500)  // 等待测试音播放完
        
        // 2. 启动超声波发射（通过 USAGE_MEDIA 路由到底部扬声器）
        ultrasonicGenerator.startPlaying()
        
        // 3. 启动录音（切换为原生 C++ 采集）
        val recordSuccess = NativeAudioRecorder.start(48000, 1024)
        
        if (!recordSuccess) {
            Toast.makeText(this, "启动录音失败，请检查权限", Toast.LENGTH_SHORT).show()
            ultrasonicGenerator.stopPlaying()
            return
        }

        // 3.1 启动 LLAP 调试日志（CSV）
        try {
            val logger = LlapCsvLogger(this)
            val logPath = logger.start()
            llapCsvLogger = logger
            Log.i(TAG, "✓ LLAP调试日志已启用: $logPath")
            Toast.makeText(this, "LLAP日志已启用\n$logPath", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "✗ 启动 LLAP 调试日志失败", e)
        }
        
        // 4. 【关键】持续强制音频模式和硬件配置（对抗系统重置）
        // 策略：在录音启动后的前 2 秒内，每 200ms 重复配置一次
        Thread {
            Log.i(TAG, "→ 启动持续音频模式监控...")
            for (i in 1..10) {  // 重复配置 10 次，共 2 秒
                Thread.sleep(200)
                // 强制保持 MODE_NORMAL（下发下收模式）
                if (audioManager.mode != AudioManager.MODE_NORMAL) {
                    runOnUiThread {
                        audioManager.mode = AudioManager.MODE_NORMAL
                        audioManager.isSpeakerphoneOn = true
                        Log.w(TAG, "  ↻ 检测到模式被重置，重新设置为 MODE_NORMAL + 外放")
                    }
                }
                AudioHardwareController.setupTopMicrophone()
                Log.d(TAG, "  ↻ 第 $i 次配置检查完成 (mode=${audioManager.mode})")
            }
            Log.i(TAG, "✓ 音频模式监控完成（已强制保持外放模式）")
        }.start()
        
        isRunning = true
        updateUI()
        
        Toast.makeText(this, "已开始检测，请点击校准进行初始化", Toast.LENGTH_LONG).show()
    }
    
    /**
     * 停止检测
     */
    private fun stopDetection() {
        if (!isRunning) return
        
        Log.d(TAG, "停止检测...")
        
        // 停止 WAV 录制
        stopWavRecording()
        
        ultrasonicGenerator.stopPlaying()
        NativeAudioRecorder.stop()
        proximityDetector.reset()
        llapCsvLogger?.stop()
        llapCsvLogger = null
        
        isRunning = false
        isCalibrated = false
        updateUI()
        
        Toast.makeText(this, "已停止检测", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 启动 WAV 文件录制（保存过滤后的超声波信号）
     */
    private fun startWavRecording() {
        mainHandler.post {
            try {
                // 生成文件名（带时间戳）- 标注为过滤后的超声波信号
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val filename = "ultrasonic_filtered_$timestamp.wav"  // filtered = 高通滤波后，去除人声
                
                wavWriter = WavWriter(this, filename, 48000, 1)  // 48kHz 采样率（匹配脚本）
                wavWriter?.start()
                
                Log.i(TAG, "✓ WAV 录制已启动: $filename (高通滤波 >15kHz)")
                Toast.makeText(this, "已开始保存超声波信号（已过滤人声）", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "✗ 启动 WAV 录制失败", e)
                Toast.makeText(this, "启动音频保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 停止 WAV 文件录制
     */
    private fun stopWavRecording() {
        try {
            val filePath = wavWriter?.stop()
            wavWriter = null
            
            if (filePath != null) {
                mainHandler.post {
                    Log.d(TAG, "✓ WAV 文件已保存: $filePath")
                    Toast.makeText(this, "音频已保存至:\n$filePath", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ 停止 WAV 录制失败", e)
        }
    }
    
    /**
     * 设置底部扬声器模式（下发下收，同侧直达）
     * 底部扬声器功率大，配合底部麦克风使用
     */
    private fun setupBottomSpeakerMode() {
        try {
            // 使用普通模式 + 外放扬声器
            audioManager.mode = AudioManager.MODE_NORMAL
            
            // 启用扬声器外放（底部扬声器）
            audioManager.isSpeakerphoneOn = true
            
            // 设置音量流为媒体音量
            volumeControlStream = AudioManager.STREAM_MUSIC
            
            // 【重要】强制设置媒体音量到最大，确保扬声器发声
            val maxMediaVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            
            // 总是设置到最大音量
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                maxMediaVolume,  // 设置到最大音量
                0  // 不显示系统音量UI
            )
            Log.i(TAG, "✓ 媒体音量已强制设置到最大: $maxMediaVolume (原来: $currentMediaVolume)")
            
            Log.i(TAG, "========================================")
            Log.i(TAG, "✓ 已启用底部扬声器模式（高能量）")
            Log.i(TAG, "  扬声器: 底部扬声器（外放，功率大）")
            Log.i(TAG, "    → tinymix: LOL Mux + Tran_Pa_Scene:14")
            Log.i(TAG, "  麦克风: 底部麦克风（UNPROCESSED）")
            Log.i(TAG, "    → tinymix: ADC_L + PGA_L(AIN0) + DMIC_DATA0")
            Log.i(TAG, "  优势: 同侧直达,声波路径最短最直接")
            Log.i(TAG, "========================================")
            Toast.makeText(this, "底部扬声器 + 底部麦克风：同侧直达模式", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "✗ 设置底部扬声器模式失败", e)
        }
    }
    
    /**
     * 设置听筒模式（上发上收配置）
     * 将音频输出路由到顶部听筒扬声器
     * 使用 MODE_IN_COMMUNICATION 激活完整的通话音频路径（听筒+顶部麦克风）
     */
    private fun setupEarpieceMode() {
        try {
            // 【关键】使用 MODE_IN_COMMUNICATION 激活通话音频路径
            // 这会让系统自动将输入路由到通话麦克风（通常是顶部麦克风）
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            // 关闭扬声器外放，让音频路由到顶部听筒
            audioManager.isSpeakerphoneOn = false
            
            // 设置音量流为通话音量（配合 MODE_IN_COMMUNICATION）
            volumeControlStream = AudioManager.STREAM_VOICE_CALL
            
            // 【重要】强制设置通话音量到最大，确保听筒发声
            val maxVoiceVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val currentVoiceVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            
            // 总是设置到最大音量（不管当前是多少）
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                maxVoiceVolume,  // 设置到最大音量
                0  // 不显示系统音量UI
            )
            Log.i(TAG, "✓ 通话音量已强制设置到最大: $maxVoiceVolume (原来: $currentVoiceVolume)")
            
            Log.i(TAG, "========================================")
            Log.i(TAG, "✓ 已启用上发上收模式")
            Log.i(TAG, "  扬声器: 顶部听筒（MODE_IN_COMMUNICATION + speakerphoneOn=false）")
            Log.i(TAG, "    → Android: USAGE_VOICE_COMMUNICATION")
            Log.i(TAG, "    → tinymix: RCV Mux + FSM_Scene:15")
            Log.i(TAG, "  麦克风: 顶部麦克风（通话麦克风）")
            Log.i(TAG, "    → Android: MODE_IN_COMMUNICATION 自动路由")
            Log.i(TAG, "    → tinymix: ADC_R + PGA_R(AIN2)")
            Log.i(TAG, "  采样率: 48000 Hz")
            Log.i(TAG, "========================================")
            Toast.makeText(this, "上发上收模式：顶部听筒 + 顶部麦克风", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "✗ 切换听筒模式失败: ${e.message}")
        }
    }
    
    /**
     * 恢复正常模式
     */
    private fun restoreNormalMode() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            Log.d(TAG, "✓ 已恢复正常模式")
        } catch (e: Exception) {
            Log.e(TAG, "恢复正常模式失败: ${e.message}")
        }
    }
    
    /**
     * 更新 UI
     */
    private fun updateUI() {
        tvStatus.text = if (isRunning) "状态: 运行中" else "状态: 已停止"
        btnStart.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
        btnCalibrate.isEnabled = isRunning
        
        if (!isRunning) {
            tvState.text = "检测状态: --"
            tvCalibrationProgress.text = "校准进度: --"
        }
        
        // 更新音量显示
        updateVolumeDisplay()
    }
    
    /**
     * 更新音量显示
     */
    private fun updateVolumeDisplay() {
        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volumePercent = (currentVolume * 100 / maxVolume)
            
            tvVolume.text = "音量: $currentVolume/$maxVolume ($volumePercent%)"
            
            // 音量过低时警告
            if (volumePercent < 50) {
                tvVolume.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            } else {
                tvVolume.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取音量失败: ${e.message}")
            tvVolume.text = "音量: --"
        }
    }
    
    /**
     * 更新状态 UI (原始能量法)
     */
    private fun updateStateUI(state: ProximityDetector.State) {
        val stateText = when (state) {
            ProximityDetector.State.UNKNOWN -> "未知（请校准）"
            ProximityDetector.State.FAR -> "远离 (>10cm)"
            ProximityDetector.State.NEAR -> "接近 (5-10cm)"
            ProximityDetector.State.VERY_NEAR -> "非常近 (0-5cm) 🔴"
        }
        
        tvState.text = "检测状态: $stateText"
        
        // 校准完成后隐藏校准进度
        if (proximityDetector.isCalibrated()) {
            tvCalibrationProgress.text = "校准完成 ✓"
        }
    }
    
    /**
     * 更新融合检测器状态 UI
     */
    private fun updateFusedStateUI(
        state: FusedProximityDetector.ProximityState,
        energyRatio: Float,
        relDist: Float,
        timeInState: Long
    ) {
        // 状态文本
        val stateText = when (state) {
            FusedProximityDetector.ProximityState.FAR -> "远离"
            FusedProximityDetector.ProximityState.VERY_NEAR -> "非常近 🔴"
            FusedProximityDetector.ProximityState.PENDING -> "待定 ⏳"
        }
        
        val timeInStateSec = timeInState / 1000f  // 秒
        
        // 直接显示 relDist (已经是累积值,不需要再累加)
        tvCumulativeDistance.text = "累积距离: %.1f mm".format(relDist)
        
        tvState.text = """
            检测状态: $stateText
            能量比: %.2f
            相对距离: %.1f mm (raw=$relDist)
            状态时间: %.1f 秒
        """.trimIndent().format(energyRatio, relDist, timeInStateSec)
        
        // 校准完成后隐藏校准进度
        if (proximityDetector.isCalibrated()) {
            tvCalibrationProgress.text = "校准完成 ✓ (融合模式)"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 停止音量监控
        mainHandler.removeCallbacks(volumeUpdateRunnable)
        
        stopDetection()
        
        // 重置底层硬件配置
        Thread {
            if (AudioHardwareController.hasRootPermission()) {
                AudioHardwareController.resetAudioHardware()
                Log.i(TAG, "✓ 已重置音频硬件配置")
            }
        }.start()
    }
    
    override fun onPause() {
        super.onPause()
        // 暂停时停止检测（避免后台耗电）
        if (isRunning) {
            stopDetection()
        }
    }
}
