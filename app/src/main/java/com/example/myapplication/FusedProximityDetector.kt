package com.example.myapplication

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 融合接近检测器
 * 结合能量法(快速状态分类)和相位法(精确相对距离测量)
 */
class FusedProximityDetector(
    private val context: Context,
    private val energyDetector: ProximityDetector,
    private val phaseRangeFinder: PhaseRangeFinder,
    private val signalProcessor: SignalProcessor
) {
    data class LlapFrameMetrics(
        val timestampMs: Long,
        val volume: Int,
        val energyRatio: Float,
        val relativeDistanceMm: Float,
        val phaseVelocityMmPerFrame: Float,
        val distanceDeltaMm: Float,
        val powerThreshold: Float,
        val avgPower: Float,
        val minPower: Float,
        val maxPower: Float,
        val powerPassCount: Int,
        val firstRegressionFreqCount: Int,
        val secondRegressionFreqCount: Int
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    companion object {
        private const val TAG = "FusedProximityDetector"
        
        // 能量阈值（基础值，实际使用动态阈值）
        private const val VERY_NEAR_RATIO = 1.25f  // 默认阈值（高音量时使用）
        private const val LOW_ENERGY_RATIO = 0.88f  // <0.88 为能量过低
        
        // 相位距离阈值
        private const val FAR_DISTANCE_THRESHOLD = 50f   // >50mm 转为远离
        
        // 低能量持续时间阈值
        private const val LOW_ENERGY_DURATION = 1000L  // 1秒
        
        // 距离变化阈值（降低以提高响应速度）
        private const val DISTANCE_CHANGE_THRESHOLD = 0.001f  // mm
        
        // 速度调整系数 (等效C++的SPEED_ADJ)
        private const val SPEED_ADJ = 1.3f  // mm/mm
        
        // UI更新最小间隔 (等效C++的10ms)
        private const val UI_UPDATE_INTERVAL = 10L  // ms
        
        // 状态定时器
        private const val STATE_TIMEOUT = 10_000L  // ms

        // 驻波守卫：LLAP速度绝对值 < 此值时认为手静止（驻波）
        private const val STATIONARY_VELOCITY_THRESHOLD = 0.3f  // mm/帧

        // 驻波守卫：远离速度需大于此值才允许降级
        private const val LEAVING_VELOCITY_THRESHOLD = 0.1f  // mm/帧（正值=远离）
    }
    
    // 当前状态
    enum class ProximityState {
        FAR,       // 远离
        VERY_NEAR, // 非常近
        PENDING    // 待定
    }
    
    private var currentState = ProximityState.FAR
    private var relativeDistance = 0f  // 当前状态下的相对距离变化 (mm)
    private var stateStartTime = System.currentTimeMillis()
    private var lastUIUpdateTime = 0L  // 上次UI更新时间
    
    // 低能量计时
    private var lowEnergyStartTime = 0L  // 能量低于0.88的开始时间
    private var isLowEnergy = false  // 当前是否处于低能量状态

    // 驻波守卫：最新一帧的 LLAP 速度（由相位处理循环更新）
    private var latestPhaseVelocity = 0f
    
    // 独立缓冲区：能量法4096（稳定），相位法960（快速）
    private val energyBuffer = ShortArray(4096)
    private var energyBufferPos = 0
    private val phaseBuffer = ShortArray(960)
    private var phaseBufferPos = 0
    
    // 日志控制
    private var frameCount = 0
    
    // 状态变化监听器
    var onStateChanged: ((ProximityState, Float) -> Unit)? = null
    
    // 实时更新监听器 (每帧调用)
    var onDataUpdate: ((ProximityState, Float, Float, Long) -> Unit)? = null  // state, energyRatio, relDist, timeInState
    
    // 相对距离重置回调 (用于累加到累积距离)
    var onRelativeDistanceReset: ((Float) -> Unit)? = null

    // LLAP逐帧指标回调（用于文件落盘分析）
    var onLlapFrameMetrics: ((LlapFrameMetrics) -> Unit)? = null
    
    /**
     * 动态阈值：低音量时提高阈值（避免噪声误触发）
     */
    private fun getVeryNearThreshold(volume: Int): Float = when {
        volume == 1 -> 1.9f   // 音量1：提高到1.9
        volume == 2 -> 1.9f   // 音量2：提高到1.9
        volume == 3 -> 1.8f   // 音量3：提高到1.8
        volume == 4 -> 1.6f   // 音量4：提高到1.6
        else -> 1.3f          // 音量3+：使用标准阈值1.3
    }
    
    /**
     * 处理音频数据（统一入口）
     * @param filteredData 高通滤波后的数据（用于能量法）
     * @param rawData 原始音频数据（用于相位法，保留更多能量）
     * @return 当前能量比
     */
    fun processAudioData(filteredData: ShortArray, rawData: ShortArray = filteredData): Float {
        val processStartTime = System.currentTimeMillis()
        var energyRatio = energyDetector.getCurrentRatio()
        var hasNewDistanceChange = false  // 标记是否有新的距离变化
        
        // 滑动窗口处理：分别处理两个缓冲区，满就处理并清空
        // 1. 能量法处理
        var energyOffset = 0
        while (energyOffset < filteredData.size) {
            val energySpace = energyBuffer.size - energyBufferPos
            val energyCopySize = minOf(filteredData.size - energyOffset, energySpace)
            System.arraycopy(filteredData, energyOffset, energyBuffer, energyBufferPos, energyCopySize)
            energyBufferPos += energyCopySize
            energyOffset += energyCopySize
            
            if (energyBufferPos >= energyBuffer.size) {
                energyDetector.processAudioData(energyBuffer)
                energyRatio = energyDetector.getCurrentRatio()
                energyBufferPos = 0  // 清空，从头开始接收下一个4096
                // 移除高频日志避免GC压力
            }
        }
        
        // 2. 相位法处理（独立处理，不受能量法影响）
        var phaseOffset = 0
        while (phaseOffset < rawData.size) {
            val phaseSpace = phaseBuffer.size - phaseBufferPos
            val phaseCopySize = minOf(rawData.size - phaseOffset, phaseSpace)
            System.arraycopy(rawData, phaseOffset, phaseBuffer, phaseBufferPos, phaseCopySize)
            phaseBufferPos += phaseCopySize
            phaseOffset += phaseCopySize
            
            if (phaseBufferPos >= phaseBuffer.size) {
                // 满1920就处理
                val distanceChange = phaseRangeFinder.processFrame(phaseBuffer)
                // 记录当前帧速度（processFrame内已更新速度窗口，这里取最新均值）
                latestPhaseVelocity = phaseRangeFinder.getRecentVelocity()

                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                phaseRangeFinder.getLastDebugSnapshot()?.let { snapshot ->
                    onLlapFrameMetrics?.invoke(
                        LlapFrameMetrics(
                            timestampMs = System.currentTimeMillis(),
                            volume = currentVolume,
                            energyRatio = energyRatio,
                            relativeDistanceMm = relativeDistance,
                            phaseVelocityMmPerFrame = latestPhaseVelocity,
                            distanceDeltaMm = distanceChange,
                            powerThreshold = snapshot.powerThreshold,
                            avgPower = snapshot.avgPower,
                            minPower = snapshot.minPower,
                            maxPower = snapshot.maxPower,
                            powerPassCount = snapshot.powerPassCount,
                            firstRegressionFreqCount = snapshot.firstRegressionFreqCount,
                            secondRegressionFreqCount = snapshot.secondRegressionFreqCount
                        )
                    )
                }

                if (kotlin.math.abs(distanceChange) > DISTANCE_CHANGE_THRESHOLD) {
                    relativeDistance += distanceChange * SPEED_ADJ
                    hasNewDistanceChange = true
                    // 极少量日志，避免GC和I/O阻塞
                    if (frameCount % 100 == 0) {
                        Log.d(TAG, "  ✅ 相位: Δ=${String.format("%.2f", distanceChange)}mm, 累积=${String.format("%.1f", relativeDistance)}mm, 速度=${String.format("%.3f", latestPhaseVelocity)}mm/帧")
                    }
                    
                    // 只限制上限，允许负数（表示远离）
                    if (relativeDistance > 500f) relativeDistance = 500f
                }
                // 移除verbose日志避免性能影响
                
                phaseBufferPos = 0  // 清空缓冲区，从头开始接收下一个1920
            }
        }
        
        // 3. 检查定时重置
        val currentTime = System.currentTimeMillis()
        val timeInState = currentTime - stateStartTime
        
        frameCount++
        if (frameCount % 50 == 0) {
            Log.d(TAG, "融合状态: $currentState, 能量=${String.format("%.2f", energyRatio)}, 距离=${String.format("%.1f", relativeDistance)}mm, 时长=${timeInState/1000f}s")
        }
        
        // 能量比值达到动态阈值时重置相对距离（驻波期间禁止重置）
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val dynamicThreshold = getVeryNearThreshold(currentVolume)
        val isStandingWave = currentState == ProximityState.VERY_NEAR
                && energyRatio < dynamicThreshold
                && phaseRangeFinder.isHandStationary(STATIONARY_VELOCITY_THRESHOLD)
        if (energyRatio >= dynamicThreshold && kotlin.math.abs(energyRatio - dynamicThreshold) < 0.05f) {
            if (relativeDistance != 0f && !isStandingWave) {
                Log.d(TAG, "能量达到阈值重置: 能量=$energyRatio, 阈值=$dynamicThreshold, 音量=$currentVolume, 相对距离=$relativeDistance mm")
                onRelativeDistanceReset?.invoke(relativeDistance)
                relativeDistance = 0f
            }
        }
        
        // 检测低能量持续时间
        if (energyRatio < LOW_ENERGY_RATIO) {
            if (!isLowEnergy) {
                // 刚进入低能量状态
                isLowEnergy = true
                lowEnergyStartTime = currentTime
                Log.d(TAG, "进入低能量状态: 能量=$energyRatio < 0.88")
            }
        } else {
            // 能量恢复正常
            if (isLowEnergy) {
                Log.d(TAG, "退出低能量状态: 能量=$energyRatio")
            }
            isLowEnergy = false
            lowEnergyStartTime = 0L
        }
        
        // 4. 状态转换逻辑
        val lowEnergyDuration = if (isLowEnergy) currentTime - lowEnergyStartTime else 0L
        val newState = determineState(energyRatio, relativeDistance, lowEnergyDuration)
        
        if (newState != currentState) {
            Log.d(TAG, "状态变化: $currentState → $newState (能量=$energyRatio, 相对距离=$relativeDistance mm)")
            currentState = newState
            stateStartTime = currentTime
            onStateChanged?.invoke(currentState, energyRatio)
        }
        
        // 【优化】每次都更新UI，实现最低延迟
        onDataUpdate?.invoke(currentState, energyRatio, relativeDistance, timeInState)
        lastUIUpdateTime = currentTime
        
        return energyRatio
    }
    
    /**
     * 确定状态 (含LLAP速度守卫：驻波时禁止降级)
     */
    private fun determineState(energyRatio: Float, relativeDistance: Float, lowEnergyDuration: Long): ProximityState {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val dynamicThreshold = getVeryNearThreshold(currentVolume)

        // 驻波检测：当前处于靠近状态 且 能量下降 且 LLAP速度几乎为0
        val isStandingWave = currentState == ProximityState.VERY_NEAR
                && energyRatio < dynamicThreshold
                && phaseRangeFinder.isHandStationary(STATIONARY_VELOCITY_THRESHOLD)

        // 真实离开检测：LLAP速度明显正向（远离）
        val isLeavingByPhase = latestPhaseVelocity > LEAVING_VELOCITY_THRESHOLD

        return when {
            // 能量比值 >= 动态阈值 → VERY_NEAR (非常近)
            energyRatio >= dynamicThreshold -> {
                Log.d(TAG, "状态判定: VERY_NEAR (能量=$energyRatio >= $dynamicThreshold, 音量=$currentVolume)")
                ProximityState.VERY_NEAR
            }

            // 【驻波守卫】能量下降但手静止 → 保持 VERY_NEAR，抑制降级
            isStandingWave -> {
                Log.d(TAG, "驻波守卫: 抑制降级 (能量=$energyRatio < $dynamicThreshold, LLAP速度=${String.format("%.3f", latestPhaseVelocity)} ≈ 0)")
                ProximityState.VERY_NEAR
            }

            // 能量 < 动态阈值 且 相对距离 > 50mm 且 LLAP确认远离 → FAR
            energyRatio < dynamicThreshold && relativeDistance > FAR_DISTANCE_THRESHOLD && isLeavingByPhase -> {
                Log.d(TAG, "状态判定: FAR (能量=$energyRatio, 距离=$relativeDistance > 50mm, LLAP速度=${String.format("%.3f", latestPhaseVelocity)}, 音量=$currentVolume)")
                ProximityState.FAR
            }

            // 能量 < 动态阈值 且 相对距离 > 50mm 但 LLAP无法确认 → PENDING（不贸然降级）
            energyRatio < dynamicThreshold && relativeDistance > FAR_DISTANCE_THRESHOLD -> {
                Log.d(TAG, "状态判定: PENDING (能量=$energyRatio, 距离=$relativeDistance, LLAP速度=${String.format("%.3f", latestPhaseVelocity)} 未确认离开)")
                ProximityState.PENDING
            }

            // 能量 < 0.88 持续超过1秒 且 LLAP确认远离 → FAR
            lowEnergyDuration >= LOW_ENERGY_DURATION && isLeavingByPhase -> {
                Log.d(TAG, "状态判定: FAR (低能量持续${lowEnergyDuration}ms, LLAP速度=${String.format("%.3f", latestPhaseVelocity)} 确认离开)")
                ProximityState.FAR
            }

            // 能量 < 0.88 持续超过1秒 但 LLAP手静止 → 仍然驻波，保持 VERY_NEAR
            lowEnergyDuration >= LOW_ENERGY_DURATION && phaseRangeFinder.isHandStationary(STATIONARY_VELOCITY_THRESHOLD) && currentState == ProximityState.VERY_NEAR -> {
                Log.d(TAG, "驻波守卫(低能量超时): 抑制FAR判定，LLAP速度=${String.format("%.3f", latestPhaseVelocity)} ≈ 0")
                ProximityState.VERY_NEAR
            }

            // 能量 < 0.88 持续超过1秒（无驻波情况）→ FAR
            lowEnergyDuration >= LOW_ENERGY_DURATION -> {
                Log.d(TAG, "状态判定: FAR (低能量持续${lowEnergyDuration}ms, 能量=$energyRatio < 0.88)")
                ProximityState.FAR
            }

            // 其余情况 → PENDING (待定)
            else -> {
                ProximityState.PENDING
            }
        }
    }
    
    /**
     * 重置相对距离（只在确认真实离开时调用，驻波期间不调用）
     */
    private fun resetRelativeDistance(reason: String) {
        Log.d(TAG, "重置相对距离 (原因: $reason, 当前值=$relativeDistance mm)")
        relativeDistance = 0f
        latestPhaseVelocity = 0f
        phaseRangeFinder.reset()
    }
    
    /**
     * 获取当前状态
     */
    fun getCurrentState(): ProximityState = currentState
    
    /**
     * 获取相对距离
     */
    fun getRelativeDistance(): Float = relativeDistance
    
    /**
     * 获取状态停留时间
     */
    fun getTimeInState(): Long = System.currentTimeMillis() - stateStartTime
    
    /**
     * 手动重置
     */
    fun reset() {
        currentState = ProximityState.FAR
        resetRelativeDistance("手动重置")
        stateStartTime = System.currentTimeMillis()
    }
}
