package com.example.myapplication

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 接近检测器
 * 核心检测逻辑：基线校准、相对变化检测、状态判定
 */
class ProximityDetector(private val context: Context) {
    
    // 检测状态
    enum class State {
        UNKNOWN,        // 未知（未校准）
        FAR,            // 远离 (>10cm)
        NEAR,           // 接近 (5-10cm)
        VERY_NEAR       // 非常近 (0-5cm，贴近耳朵)
    }
    
    companion object {
        private const val TAG = "ProximityDetector"
        
        // 音量-基线映射表 (统一使用上麦克风数据 - 2026-01-06最新测量)
        private val VOLUME_BASELINE_MAP = mapOf(
            1 to 0.35386017f,   // 重新测量 2026-01-06
            2 to 0.8211683f,    // 重新测量 2026-01-06
            3 to 1.4344041f,    // 重新测量 2026-01-06
            4 to 3.602211f,     // 重新测量 2026-01-06
            5 to 6.457968f,     // 重新测量 2026-01-06
            6 to 8.704215f,     // 重新测量 2026-01-06
            7 to 12.430713f,    // 重新测量 2026-01-06
            8 to 13.350524f,    // 重新测量 2026-01-06
            9 to 16.39806f,     // 重新测量 2026-01-06
            10 to 17.125603f,   // 重新测量 2026-01-06
            11 to 18.31323f,    // 重新测量 2026-01-06
            12 to 19.09781f,    // 重新测量 2026-01-06
            13 to 19.356688f,   // 重新测量 2026-01-06
            14 to 19.347998f,   // 重新测量 2026-01-06
            15 to 20.330273f    // 重新测量 2026-01-06
        )
        
        // 校准参数
        private const val CALIBRATION_SAMPLES = 20
        
        // 状态判定阈值（拉大 NEAR 范围，避免跳过）
        // 注意：这些是基础阈值，实际使用时会根据音量动态调整
        private const val VERY_NEAR_RATIO_HIGH = 1.5f   // 能量增强 50% → 非常近 (0-5cm)
        private const val NEAR_RATIO_HIGH = 1.25f       // 能量增强 25% → 接近 (5-10cm) 开始
        private const val FAR_RATIO_THRESHOLD = 1.0f    // 基线水平 → 远离 (>10cm)
        
        // 滑动平均参数（加强平滑，降低瞬时波动）
        private const val SMOOTHING_ALPHA = 0.2f  // 20% 新值，80% 旧值
        
        // Peak-Hold 包络参数
        private const val ENVELOPE_DECAY_RATE = 0.99f   // 包络衰减率：每帧衰减1%
        private const val ENVELOPE_MIN_RATIO = 1.0f     // 最低比率保底（不低于基线）
        
        // 状态切换的滞后阈值
        private const val STATE_CHANGE_THRESHOLD = 4  // 需要连续 4 帧才切换状态
        
        // 基线更新间隔
        private const val BASELINE_UPDATE_INTERVAL = 1000L  // 1秒更新一次

        // 音量变化冻结窗口（避免瞬态冲高误判）
        private const val VOLUME_CHANGE_FREEZE_MS = 800L
    }
    
    private val signalProcessor = SignalProcessor()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    // 基线数据
    private var baselineMagnitude = 0f
    private var isCalibrated = false
    private val calibrationData = mutableListOf<Float>()
    private var lastBaselineUpdateTime = 0L
    
    // 当前状态
    private var currentState = State.UNKNOWN
    private var lastState = State.UNKNOWN
    private var stateCounter = 0  // 状态计数器（用于防抖）
    private var debugCounter = 0  // 调试日志计数器
    
    // 滑动平均
    private var smoothedMagnitude = 0f
    
    // Peak-Hold 包络幅度
    private var envelopeMagnitude = 0f

    // 音量变化跟踪
    private var lastVolume = -1
    private var freezeUntilTime = 0L
    
    // 状态变化回调
    private var onStateChangeCallback: ((State) -> Unit)? = null
    
    // 校准完成回调 (传递基线幅度)
    var onCalibrationComplete: ((Float) -> Unit)? = null
    
    /**
     * 动态阈值：低音量时提高阈值（避免噪声误触发，且低音量通常更靠近）
     */
    private fun getVeryNearThreshold(volume: Int): Float = when {
        volume <= 2 -> 2.0f   // 音量1-2：提高到2.0（极低音量，高噪声风险）
        volume <= 4 -> 1.8f   // 音量3-4：提高到1.8
        volume <= 6 -> 1.6f   // 音量5-6：提高到1.6
        else -> 1.5f          // 音量7+：使用标准阈值1.5
    }
    
    private fun getNearThreshold(volume: Int): Float = when {
        volume <= 2 -> 1.5f   // 音量1-2：提高到1.5
        volume <= 4 -> 1.4f   // 音量3-4：提高到1.4
        volume <= 6 -> 1.3f   // 音量5-6：提高到1.3
        else -> 1.25f         // 音量7+：使用标准阈值1.25
    }
    
    /**
     * 设置状态变化回调
     */
    fun setOnStateChangeCallback(callback: (State) -> Unit) {
        this.onStateChangeCallback = callback
    }
    
    /**
     * 开始校准
     * 提示：将手机放在空旷处，不要遮挡麦克风和扬声器
     */
    fun startCalibration() {
        Log.d(TAG, "开始校准...")
        isCalibrated = false
        calibrationData.clear()
        baselineMagnitude = 0f
        currentState = State.UNKNOWN
    }
    
    /**
     * 处理音频数据（校准或检测）
     */
    fun processAudioData(audioData: ShortArray) {
        // 提取多频率幅度（使用Goertzel算法）
        val magnitudes = signalProcessor.extractMagnitudes(audioData)
        val avgMagnitude = signalProcessor.calculateAverageMagnitude(magnitudes)
        
        // 根据音量自动更新基线（使用对应麦克风的映射表）
        val currentTime = System.currentTimeMillis()
        if (isCalibrated && currentTime - lastBaselineUpdateTime >= BASELINE_UPDATE_INTERVAL) {
            updateBaselineFromVolume()
            lastBaselineUpdateTime = currentTime
        }
        
        if (!isCalibrated) {
            // 校准模式
            calibrationData.add(avgMagnitude)
            
            // 【调试】打印校准进度
            Log.d(TAG, "校准中: ${calibrationData.size}/$CALIBRATION_SAMPLES, 当前幅度=${String.format("%.2f", avgMagnitude)}")
            
            if (calibrationData.size >= CALIBRATION_SAMPLES) {
                // 计算基线（去除最大最小值后取平均，更鲁棒）
                val sortedData = calibrationData.sorted()
                val trimmedData = sortedData.subList(5, sortedData.size - 5)  // 去掉头尾各 5 个
                baselineMagnitude = trimmedData.average().toFloat()
                
                isCalibrated = true
                smoothedMagnitude = baselineMagnitude
                currentState = State.FAR
                
                Log.d(TAG, "校准完成，基线幅度: $baselineMagnitude, 最小=${sortedData.first()}, 最大=${sortedData.last()}")
                onCalibrationComplete?.invoke(baselineMagnitude)
                onStateChangeCallback?.invoke(currentState)
            }
        } else {
            // 检测模式
            detectProximity(avgMagnitude)
        }
    }
    
    /**
     * 检测接近状态（使用Peak-Hold包络和动态阈值）
     */
    private fun detectProximity(magnitude: Float) {
        val currentTime = System.currentTimeMillis()

        // 实时从映射表获取当前音量对应的基线
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val baselineFromMap = VOLUME_BASELINE_MAP[currentVolume] ?: baselineMagnitude

        // 追踪音量变化：立即切基线并重置包络，进入冻结窗口
        if (lastVolume == -1) {
            lastVolume = currentVolume
        } else if (currentVolume != lastVolume) {
            val oldVolume = lastVolume
            val oldBaseline = baselineMagnitude

            lastVolume = currentVolume
            baselineMagnitude = baselineFromMap
            smoothedMagnitude = baselineFromMap
            envelopeMagnitude = baselineFromMap
            stateCounter = 0
            lastState = currentState
            freezeUntilTime = currentTime + VOLUME_CHANGE_FREEZE_MS

            Log.i(
                TAG,
                "[音量变化冻结] 音量=$oldVolume→$currentVolume, 基线=${String.format("%.4f", oldBaseline)}→${String.format("%.4f", baselineFromMap)}, 冻结=${VOLUME_CHANGE_FREEZE_MS}ms"
            )
        }

        // 冻结期间：固定到新基线，跳过状态判定
        if (currentTime < freezeUntilTime) {
            smoothedMagnitude = baselineFromMap
            envelopeMagnitude = baselineFromMap
            debugCounter++
            if (debugCounter % 30 == 0) {
                val remainMs = freezeUntilTime - currentTime
                Log.d(
                    TAG,
                    "[冻结中] 音量=$currentVolume/$maxVolume, 基线=${String.format("%.1f", baselineFromMap)}, 剩余=${remainMs}ms"
                )
            }
            return
        }

        // 兼容性：同时更新滑动平均（保留用于对比）
        smoothedMagnitude = SMOOTHING_ALPHA * magnitude + (1 - SMOOTHING_ALPHA) * smoothedMagnitude

        // Peak-Hold包络逻辑：
        // - 如果新值更高，立即跟踪峰值
        // - 如果新值更低，缓慢衰减（保持包络）
        if (magnitude > envelopeMagnitude) {
            envelopeMagnitude = magnitude
        } else {
            envelopeMagnitude *= ENVELOPE_DECAY_RATE
        }
        
        // 计算比率时使用包络值，但保底为1.0（不修改envelopeMagnitude本身）
        val effectiveEnvelope = maxOf(envelopeMagnitude, baselineFromMap)
        val ratio = effectiveEnvelope / baselineFromMap
        
        // 根据音量获取动态阈值
        val veryNearThreshold = getVeryNearThreshold(currentVolume)
        val nearThreshold = getNearThreshold(currentVolume)
        
        // 【调试】每30帧打印一次实际数值（包含包络信息）
        debugCounter++
        if (debugCounter % 30 == 0) {
            Log.d(TAG, "[包络监控] 音量=$currentVolume/$maxVolume, 原始=${String.format("%.1f", magnitude)}, 包络=${String.format("%.1f", envelopeMagnitude)}, 有效=${String.format("%.1f", effectiveEnvelope)}, 基线=${String.format("%.1f", baselineFromMap)}, 比率=${String.format("%.3f", ratio)}, 阈值(NEAR=${String.format("%.2f", nearThreshold)}, VERY_NEAR=${String.format("%.2f", veryNearThreshold)})")
        }
        
        // 根据动态阈值判断状态
        val detectedState = when {
            ratio >= veryNearThreshold -> State.VERY_NEAR       // 达到VERY_NEAR动态阈值 → 0-5cm
            ratio >= nearThreshold -> State.NEAR                // 达到NEAR动态阈值 → 5-10cm
            else -> State.FAR                                   // 低于阈值 → 远离 >10cm
        }
        
        // 防抖：需要连续多帧检测到相同状态才切换
        if (detectedState == lastState) {
            stateCounter++
            
            // 连续检测到相同状态，才真正切换
            if (stateCounter >= STATE_CHANGE_THRESHOLD && detectedState != currentState) {
                currentState = detectedState
                val volumePercent = (currentVolume * 100 / maxVolume)
                Log.i(TAG, "✓ 状态切换: $currentState | 音量=$currentVolume/$maxVolume(${volumePercent}%) | 比率=${"%.2f".format(ratio)} | 包络=${"%.1f".format(envelopeMagnitude)} | 基线=${"%.1f".format(baselineFromMap)} | 阈值(NEAR=${"%.2f".format(nearThreshold)}, VERY_NEAR=${"%.2f".format(veryNearThreshold)})")
                onStateChangeCallback?.invoke(currentState)
                
                // 切换后重置计数器
                stateCounter = 0
            }
        } else {
            // 检测到不同状态，重置计数器
            lastState = detectedState
            stateCounter = 0
        }
        
        // 移除高频日志避免GC咋力
    }
    
    /**
     * 根据当前音量更新基线（统一使用上麦克风表）
     */
    private fun updateBaselineFromVolume() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        // 直接使用统一的映射表
        val newBaseline = VOLUME_BASELINE_MAP[currentVolume]
        
        if (newBaseline != null && newBaseline != baselineMagnitude) {
            val oldBaseline = baselineMagnitude
            baselineMagnitude = newBaseline
            val volumePercent = (currentVolume * 100 / maxVolume)
            
            // 获取动态阈值信息
            val veryNearThreshold = getVeryNearThreshold(currentVolume)
            val nearThreshold = getNearThreshold(currentVolume)
            
            // 打印到控制台和日志（超明显的分隔符）
            println("")
            println("████████████████████████████████████████████████████████████████")
            println("██                   【音量变化检测】                        ██")
            println("████████████████████████████████████████████████████████████████")
            println("  音量: $currentVolume/$maxVolume ($volumePercent%)")
            println("  基线: ${String.format("%.4f", oldBaseline)} → ${String.format("%.4f", newBaseline)}")
            println("  动态阈值: NEAR=${String.format("%.2f", nearThreshold)}, VERY_NEAR=${String.format("%.2f", veryNearThreshold)}")
            println("████████████████████████████████████████████████████████████████")
            println("")
            System.out.flush()
            
            Log.i(TAG, "████ 【音量切换】音量=$currentVolume/$maxVolume($volumePercent%) | 基线=${String.format("%.4f", oldBaseline)}→${String.format("%.4f", newBaseline)} | 阈值(NEAR=${String.format("%.2f", nearThreshold)}, VERY_NEAR=${String.format("%.2f", veryNearThreshold)}) ████")
        }
    }
    
    /**
     * 获取当前状态
     */
    fun getCurrentState(): State = currentState
    
    /**
     * 获取当前能量比
     */
    fun getCurrentRatio(): Float {
        return if (isCalibrated && baselineMagnitude > 0) {
            smoothedMagnitude / baselineMagnitude
        } else {
            0f
        }
    }
    
    /**
     * 是否已校准
     */
    fun isCalibrated(): Boolean = isCalibrated
    
    /**
     * 获取校准进度（0.0 - 1.0）
     */
    fun getCalibrationProgress(): Float {
        return if (isCalibrated) 1.0f
        else calibrationData.size.toFloat() / CALIBRATION_SAMPLES
    }
    
    /**
     * 重置检测器
     */
    fun reset() {
        isCalibrated = false
        calibrationData.clear()
        baselineMagnitude = 0f
        smoothedMagnitude = 0f
        envelopeMagnitude = 0f
        lastVolume = -1
        freezeUntilTime = 0L
        currentState = State.UNKNOWN
        lastState = State.UNKNOWN
        stateCounter = 0
    }
}
