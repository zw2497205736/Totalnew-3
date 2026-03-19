package com.example.myapplication

import android.util.Log
import kotlin.math.*

/**
 * 相位测距器 (LLAP 算法的 Kotlin 实现)
 * 基于多频率相位差测量相对距离变化
 */
class PhaseRangeFinder {

    data class DebugSnapshot(
        val frameIndex: Int,
        val powerThreshold: Float,
        val avgPower: Float,
        val minPower: Float,
        val maxPower: Float,
        val powerPassCount: Int,
        val firstRegressionFreqCount: Int,
        val secondRegressionFreqCount: Int,
        val distanceDeltaMm: Float
    )
    
    companion object {
        private const val TAG = "PhaseRangeFinder"
        private const val SAMPLE_RATE = 48000      // 采样率 48kHz
        private const val FRAME_SIZE = 960         // 帧大小 (优化：减半以提高帧率)
        private const val NUM_FREQUENCIES = 10     // 频率数量
        private const val START_FREQ = 20000f      // 起始频率 17.5kHz
        private const val FREQ_STEP = 350f         // 频率间隔 350Hz
        private const val TEMPERATURE = 20f        // 温度 (°C)
        private const val CIC_DEC = 16             // CIC 抽取倍数
        private const val CIC_SEC = 4              // CIC 滤波器级数
        private const val CIC_DELAY = 17           // CIC 滤波器延迟
        private const val CIC_STAGE0_GAIN = 1.0f   // CIC Stage 0 额外放大倍数（改为1.0不放大）
        private const val POWER_THR = 15000f       // 功率阈值（降低以支持更远距离）
        private const val PEAK_THR = 220f          // 峰值阈值
        private const val DC_TREND = 0.25f         // DC趋势系数
    }
    
    // 声速 (m/s)
    private val soundSpeed = 331.3f + 0.606f * TEMPERATURE
    
    // 频率和波长
    private val frequencies = FloatArray(NUM_FREQUENCIES) { i ->
        START_FREQ + i * FREQ_STEP
    }
    private val wavelengths = FloatArray(NUM_FREQUENCIES) { i ->
        soundSpeed / frequencies[i] * 1000  // mm
    }
    
    // 参考信号缓冲区
    private val cosBuffer = Array(NUM_FREQUENCIES) { i ->
        FloatArray(FRAME_SIZE) { n ->
            cos(2.0 * PI * n / SAMPLE_RATE * frequencies[i]).toFloat()
        }
    }
    private val sinBuffer = Array(NUM_FREQUENCIES) { i ->
        FloatArray(FRAME_SIZE) { n ->
            -sin(2.0 * PI * n / SAMPLE_RATE * frequencies[i]).toFloat()
        }
    }
    
    // 基带信号缓冲区 (960 / 16 = 60) - C++不使用偏移，直接60
    private val baseBandReal = Array(NUM_FREQUENCIES) { FloatArray(60) }
    private val baseBandImag = Array(NUM_FREQUENCIES) { FloatArray(60) }
    
    // CIC滤波器缓冲区 [freq][stage][channel(0=Real,1=Imag)]
    private val cicBuffer = Array(NUM_FREQUENCIES) { 
        Array(CIC_SEC) { 
            Array(2) { 
                FloatArray(60 + CIC_DELAY) 
            }
        }
    }
    
    // 临时缓冲区
    private val tempBuffer = FloatArray(FRAME_SIZE)
    
    // 音频数据缓冲区 (累积到1920样本后处理)
    private val audioBuffer = ShortArray(FRAME_SIZE)
    private var audioBufferPos = 0
    
    // DC 去除
    private val dcValueReal = FloatArray(NUM_FREQUENCIES)
    private val dcValueImag = FloatArray(NUM_FREQUENCIES)
    private val maxValueReal = FloatArray(NUM_FREQUENCIES)
    private val minValueReal = FloatArray(NUM_FREQUENCIES)
    private val maxValueImag = FloatArray(NUM_FREQUENCIES)
    private val minValueImag = FloatArray(NUM_FREQUENCIES)
    
    // 性能统计
    private var frameCount = 0
    private var totalProcessTime = 0L

    // 速度窗口：保存最近5帧的 Δd (mm)，用于驻波检测
    private val VELOCITY_WINDOW_SIZE = 5
    private val recentDeltas = FloatArray(VELOCITY_WINDOW_SIZE)
    private var velocityWindowPos = 0
    private var velocityWindowFilled = false

    // 每帧调试快照（用于低音量诊断）
    private var lastDebugSnapshot: DebugSnapshot? = null
    
    /**
     * 处理单帧数据 (固定1920样本)
     * @param frame 1920个样本的音频数据
     * @return 距离变化量 (mm)
     */
    fun processFrame(frame: ShortArray): Float {
        if (frame.size != FRAME_SIZE) {
            Log.w(TAG, "帧大小错误: ${frame.size}, 期望: $FRAME_SIZE")
            return 0f
        }
        
        // 检查音频数据是否有效
        val maxAmp = frame.maxOrNull() ?: 0
        val minAmp = frame.minOrNull() ?: 0
        // 移除高频日志
        
        if (maxAmp == 0.toShort() && minAmp == 0.toShort()) {
            Log.w(TAG, "警告: 音频数据全为0！")
            return 0f
        }
        
        val startTime = System.nanoTime()
        
        // 1. I/Q 解调 + CIC 抽取
        extractBaseband(frame)
        
        // 2. 去除 DC 分量
        removeDC()
        
        // 3. 计算相位并转换为距离
        val distance = calculateDistance()
        
        // 性能统计
        val processTime = (System.nanoTime() - startTime) / 1_000_000  // ms
        totalProcessTime += processTime
        frameCount++
        
        if (frameCount % 200 == 0) {
            val avgTime = totalProcessTime / 200f
            Log.d(TAG, "相位法: 平均${String.format("%.1f", avgTime)}ms/帧, Δd=${String.format("%.2f", distance)}mm (第${frameCount}帧)")
            totalProcessTime = 0L
        }

        // 更新速度窗口
        recentDeltas[velocityWindowPos] = distance
        velocityWindowPos = (velocityWindowPos + 1) % VELOCITY_WINDOW_SIZE
        if (velocityWindowPos == 0) velocityWindowFilled = true
        
        return distance
    }

    /**
     * 获取近期平均速度 (mm/帧)
     * 正值表示远离，负值表示靠近；绝对值接近0表示手静止（可能是驻波）
     */
    fun getRecentVelocity(): Float {
        val count = if (velocityWindowFilled) VELOCITY_WINDOW_SIZE else velocityWindowPos
        if (count == 0) return 0f
        return recentDeltas.take(count).sum() / count
    }

    /**
     * 判断当前是否处于驻波状态（手静止但能量异常）
     * 条件：近期速度绝对值 < 静止速度阈值
     */
    fun isHandStationary(stationaryThreshold: Float = 0.3f): Boolean {
        return kotlin.math.abs(getRecentVelocity()) < stationaryThreshold
    }

    fun getLastDebugSnapshot(): DebugSnapshot? = lastDebugSnapshot
    
    /**
     * 计算距离变化量 (兼容旧接口，内部缓冲)
     * @param audioData 音频数据 (48kHz 采样，通常是4096样本)
     * @return 距离变化量 (mm)，如果缓冲区未满返回0
     */
    fun getDistanceChange(audioData: ShortArray): Float {
        if (audioData.isEmpty()) return 0f
        
        // 累积数据到缓冲区，直到有1920个样本
        var offset = 0
        while (offset < audioData.size) {
            val copySize = minOf(audioData.size - offset, FRAME_SIZE - audioBufferPos)
            System.arraycopy(audioData, offset, audioBuffer, audioBufferPos, copySize)
            audioBufferPos += copySize
            offset += copySize
            
            // 缓冲区满了，处理这一帧
            if (audioBufferPos >= FRAME_SIZE) {
                val startTime = System.nanoTime()
                
                // 1. I/Q 解调 + CIC 抽取
                extractBaseband(audioBuffer)
                
                // 2. 去除 DC 分量
                removeDC()
                
                // 3. 计算相位并转换为距离
                val distance = calculateDistance()
                
                // 重置缓冲区
                audioBufferPos = 0
                
                // 性能统计
                val processTime = (System.nanoTime() - startTime) / 1_000_000  // ms
                totalProcessTime += processTime
                frameCount++
                
                if (frameCount % 200 == 0) {
                    val avgTime = totalProcessTime / 200f
                    Log.d(TAG, "相位法: 平均耗时=${String.format("%.1f", avgTime)}ms, 距离变化=${String.format("%.2f", distance)}mm (第${frameCount}帧)")
                    totalProcessTime = 0L
                }
                
                return distance
            }
        }
        
        return 0f  // 缓冲区未满，返回0
    }
    
    /**
     * 滑动窗口求和 (等效于vDSP_vswsum)
     * C++: DSP_vswsum(input, output+CIC_DELAY, size, windowSize)
     * 输出从CIC_DELAY位置开始写入
     */
    private fun slidingWindowSum(input: FloatArray, output: FloatArray, size: Int, windowSize: Int) {
        // C++: output写入从CIC_DELAY偏移开始，但读取input从0开始(包含历史)
        for (i in 0 until size) {
            var sum = 0f
            // 窗口: [i, i+windowSize)
            for (j in i until i + windowSize) {
                sum += input[j]
            }
            output[i] = sum  // C++最后一级写baseBand时不用偏移
        }
    }
    
    /**
     * 带偏移的滑动窗口求和 (用于CIC中间级)
     * C++: DSP_vswsum(input, output+CIC_DELAY, size, windowSize)
     */
    private fun slidingWindowSumWithOffset(input: FloatArray, output: FloatArray, size: Int, windowSize: Int) {
        for (i in 0 until size) {
            var sum = 0f
            for (j in i until i + windowSize) {
                sum += input[j]
            }
            output[CIC_DELAY + i] = sum  // 写入偏移位置
        }
    }
    
    /**
     * 基带信号提取 (完全等效于C++的GetBaseBand)
     * 使用4级CIC滤波器，每级使用滑动窗口求和，延迟为CIC_DELAY(17)
     */
    private fun extractBaseband(audioData: ShortArray) {
        val decSize = FRAME_SIZE / CIC_DEC  // 120
        
        // 移除高频日志避免性能影响
        
        // 【关键】归一化输入: int16 -> float [-1, 1] (等效C++的 /32767.0)
        val normalizedData = FloatArray(audioData.size) { i ->
            audioData[i] / 32767f
        }
        
        for (f in 0 until NUM_FREQUENCIES) {
            // === I/Q解调 - Real channel (I): 乘以cos ===
            for (n in normalizedData.indices) {
                tempBuffer[n] = normalizedData[n] * cosBuffer[f][n]
            }
            
            // Stage 0: 抽取 - 保留历史延迟 + 每16个样本求和 + 额外放大
            // memmove: 保留最后CIC_DELAY个样本作为下一帧历史
            System.arraycopy(cicBuffer[f][0][0], decSize, cicBuffer[f][0][0], 0, CIC_DELAY)
            var index = CIC_DELAY
            for (k in 0 until FRAME_SIZE step CIC_DEC) {
                var sum = 0f
                for (j in 0 until CIC_DEC) {
                    sum += tempBuffer[k + j]
                }
                cicBuffer[f][0][0][index] = sum * CIC_STAGE0_GAIN
                index++
            }
            
            // Stages 1-4: 级联滑动窗口求和 (每级都保留历史)
            // Stage 1
            System.arraycopy(cicBuffer[f][1][0], decSize, cicBuffer[f][1][0], 0, CIC_DELAY)
            slidingWindowSumWithOffset(cicBuffer[f][0][0], cicBuffer[f][1][0], decSize, CIC_DELAY)
            
            // Stage 2
            System.arraycopy(cicBuffer[f][2][0], decSize, cicBuffer[f][2][0], 0, CIC_DELAY)
            slidingWindowSumWithOffset(cicBuffer[f][1][0], cicBuffer[f][2][0], decSize, CIC_DELAY)
            
            // Stage 3
            System.arraycopy(cicBuffer[f][3][0], decSize, cicBuffer[f][3][0], 0, CIC_DELAY)
            slidingWindowSumWithOffset(cicBuffer[f][2][0], cicBuffer[f][3][0], decSize, CIC_DELAY)
            
            // Stage 4 -> baseBand (C++最后一级不带偏移)
            slidingWindowSum(cicBuffer[f][3][0], baseBandReal[f], decSize, CIC_DELAY)
            
            // === I/Q解调 - Imag channel (Q): 乘以-sin ===
            for (n in normalizedData.indices) {
                tempBuffer[n] = normalizedData[n] * sinBuffer[f][n]
            }
            
            // Stage 0: 抽取 (保留历史 + 额外放大)
            System.arraycopy(cicBuffer[f][0][1], decSize, cicBuffer[f][0][1], 0, CIC_DELAY)
            index = CIC_DELAY
            for (k in 0 until FRAME_SIZE step CIC_DEC) {
                var sum = 0f
                for (j in 0 until CIC_DEC) {
                    sum += tempBuffer[k + j]
                }
                cicBuffer[f][0][1][index] = sum * CIC_STAGE0_GAIN
                index++
            }
            
            // Stages 1-4: 级联滑动窗口求和 (保留历史)
            // Stage 1
            System.arraycopy(cicBuffer[f][1][1], decSize, cicBuffer[f][1][1], 0, CIC_DELAY)
            slidingWindowSumWithOffset(cicBuffer[f][0][1], cicBuffer[f][1][1], decSize, CIC_DELAY)
            
            // Stage 2
            System.arraycopy(cicBuffer[f][2][1], decSize, cicBuffer[f][2][1], 0, CIC_DELAY)
            slidingWindowSumWithOffset(cicBuffer[f][1][1], cicBuffer[f][2][1], decSize, CIC_DELAY)
            
            // Stage 3
            System.arraycopy(cicBuffer[f][3][1], decSize, cicBuffer[f][3][1], 0, CIC_DELAY)
            slidingWindowSumWithOffset(cicBuffer[f][2][1], cicBuffer[f][3][1], decSize, CIC_DELAY)
            
            // Stage 4 -> baseBand
            slidingWindowSum(cicBuffer[f][3][1], baseBandImag[f], decSize, CIC_DELAY)
            
            // 移除高频debug日志
        }
    }
    
    /**
     * 去除 DC 分量 (Levd 算法 - 完全等效C++)
     */
    private fun removeDC() {
        val decSize = FRAME_SIZE / CIC_DEC  // 120
        
        for (f in 0 until NUM_FREQUENCIES) {
            var vsum = 0f
            var dsum = 0f
            
            // Real part - 获取最大最小值
            var maxR = Float.NEGATIVE_INFINITY
            var minR = Float.POSITIVE_INFINITY
            for (i in 0 until decSize) {
                if (baseBandReal[f][i] > maxR) maxR = baseBandReal[f][i]
                if (baseBandReal[f][i] < minR) minR = baseBandReal[f][i]
            }
            
            // 计算方差 - 先去除第一个值
            val tempVal = -baseBandReal[f][0]
            var tempSum = 0f
            var tempSum2 = 0f
            for (i in 0 until decSize) {
                val temp = baseBandReal[f][i] + tempVal
                tempSum += temp
                tempSum2 += temp * temp
            }
            dsum += abs(tempSum / decSize)
            vsum += abs(tempSum2 / decSize)
            
            // Imag part - 获取最大最小值
            var maxI = Float.NEGATIVE_INFINITY
            var minI = Float.POSITIVE_INFINITY
            for (i in 0 until decSize) {
                if (baseBandImag[f][i] > maxI) maxI = baseBandImag[f][i]
                if (baseBandImag[f][i] < minI) minI = baseBandImag[f][i]
            }
            
            // 计算方差 - 先去除第一个值
            val tempValI = -baseBandImag[f][0]
            tempSum = 0f
            tempSum2 = 0f
            for (i in 0 until decSize) {
                val temp = baseBandImag[f][i] + tempValI
                tempSum += temp
                tempSum2 += temp * temp
            }
            dsum += abs(tempSum / decSize)
            vsum += abs(tempSum2 / decSize)
            
            // 功率计算 (Levd算法)
            val power = vsum + dsum * dsum
            
            // 移除高频日志避免GC和字符串分配
            
            // DC估计更新 (等效C++的条件判断)
            if (power > POWER_THR) {
                // Real channel DC更新
                if (maxR > maxValueReal[f] || 
                    (maxR > minValueReal[f] + PEAK_THR && (maxValueReal[f] - minValueReal[f]) > PEAK_THR * 4)) {
                    maxValueReal[f] = maxR
                }
                if (minR < minValueReal[f] || 
                    (minR < maxValueReal[f] - PEAK_THR && (maxValueReal[f] - minValueReal[f]) > PEAK_THR * 4)) {
                    minValueReal[f] = minR
                }
                
                // Imag channel DC更新
                if (maxI > maxValueImag[f] || 
                    (maxI > minValueImag[f] + PEAK_THR && (maxValueImag[f] - minValueImag[f]) > PEAK_THR * 4)) {
                    maxValueImag[f] = maxI
                }
                if (minI < minValueImag[f] || 
                    (minI < maxValueImag[f] - PEAK_THR && (maxValueImag[f] - minValueImag[f]) > PEAK_THR * 4)) {
                    minValueImag[f] = minI
                }
                
                // DC值更新 (带平滑)
                if ((maxValueReal[f] - minValueReal[f]) > PEAK_THR && 
                    (maxValueImag[f] - minValueImag[f]) > PEAK_THR) {
                    dcValueReal[f] = (1f - DC_TREND) * dcValueReal[f] + 
                                     (minValueReal[f] + maxValueReal[f]) / 2f * DC_TREND
                    dcValueImag[f] = (1f - DC_TREND) * dcValueImag[f] + 
                                     (minValueImag[f] + maxValueImag[f]) / 2f * DC_TREND
                }
            }
            
            // 去除 DC
            for (i in 0 until decSize) {
                baseBandReal[f][i] -= dcValueReal[f]
                baseBandImag[f][i] -= dcValueImag[f]
            }
        }
    }
    
    /**
     * 计算距离变化 (相位解缠 + 线性回归 + 方差过滤 - 完全等效C++)
     */
    private fun calculateDistance(): Float {
        val decSize = FRAME_SIZE / CIC_DEC  // 120
        val phaseData = Array(NUM_FREQUENCIES) { FloatArray(decSize) }
        val ignoreFreq = BooleanArray(NUM_FREQUENCIES)
        val powers = FloatArray(NUM_FREQUENCIES)
        var powerPassCount = 0
        
        // 1. 计算相位并解缠
        for (f in 0 until NUM_FREQUENCIES) {
            // 计算功率
            var power = 0f
            for (i in 0 until decSize) {
                power += baseBandReal[f][i] * baseBandReal[f][i] + 
                         baseBandImag[f][i] * baseBandImag[f][i]
            }
            power /= decSize
            powers[f] = power
            
            if (power > POWER_THR) {
                powerPassCount++
                // 计算相位
                for (i in 0 until decSize) {
                    phaseData[f][i] = atan2(baseBandImag[f][i], baseBandReal[f][i])
                }
                
                // 相位解缠
                for (i in 1 until decSize) {
                    while (phaseData[f][i] - phaseData[f][i-1] > PI.toFloat()) {
                        phaseData[f][i] -= (2f * PI).toFloat()
                    }
                    while (phaseData[f][i] - phaseData[f][i-1] < -PI.toFloat()) {
                        phaseData[f][i] += (2f * PI).toFloat()
                    }
                }
                
                // 检查相位变化是否过大 (等效C++的DC调整条件)
                if (abs(phaseData[f][decSize-1] - phaseData[f][0]) > PI.toFloat() / 4f) {
                    dcValueReal[f] = (1f - 0.5f) * dcValueReal[f] + 
                                     (minValueReal[f] + maxValueReal[f]) / 2f * 0.5f
                    dcValueImag[f] = (1f - 0.5f) * dcValueImag[f] + 
                                     (minValueImag[f] + maxValueImag[f]) / 2f * 0.5f
                }
                
                // 去除起始相位
                val startPhase = phaseData[f][0]
                for (i in 0 until decSize) {
                    phaseData[f][i] -= startPhase
                }
                
                // 转换为距离 (mm)
                val scale = (2f * PI / wavelengths[f]).toFloat()
                for (i in 0 until decSize) {
                    phaseData[f][i] /= scale
                }
            } else {
                ignoreFreq[f] = true
            }
        }
        
        // 2. 第一次线性回归
        var sumXY = 0f
        var sumY = 0f
        var numFreqUsed = 0
        
        for (f in 0 until NUM_FREQUENCIES) {
            if (!ignoreFreq[f]) {
                for (i in 0 until decSize) {
                    sumXY += i * phaseData[f][i]
                    sumY += phaseData[f][i]
                }
                numFreqUsed++
            }
        }
        
        if (numFreqUsed == 0) {
            lastDebugSnapshot = DebugSnapshot(
                frameIndex = frameCount + 1,
                powerThreshold = POWER_THR,
                avgPower = powers.average().toFloat(),
                minPower = powers.minOrNull() ?: 0f,
                maxPower = powers.maxOrNull() ?: 0f,
                powerPassCount = powerPassCount,
                firstRegressionFreqCount = 0,
                secondRegressionFreqCount = 0,
                distanceDeltaMm = 0f
            )
            return 0f
        }

        val firstRegressionFreqCount = numFreqUsed
        
        val deltaX = NUM_FREQUENCIES * ((decSize - 1) * decSize * (2 * decSize - 1) / 6f -
                                        (decSize - 1) * decSize * (decSize - 1) / 4f)
        var delta = (sumXY - sumY * (decSize - 1) / 2f) / deltaX * NUM_FREQUENCIES / numFreqUsed
        
        // 3. 方差过滤 (等效C++)
        val varVal = FloatArray(NUM_FREQUENCIES)
        var varSum = 0f
        
        for (f in 0 until NUM_FREQUENCIES) {
            if (ignoreFreq[f]) continue
            
            var variance = 0f
            for (i in 0 until decSize) {
                val diff = phaseData[f][i] - i * delta
                variance += diff * diff
            }
            varVal[f] = variance
            varSum += variance
        }
        
        varSum /= numFreqUsed
        
        // 过滤方差过大的频率
        for (f in 0 until NUM_FREQUENCIES) {
            if (!ignoreFreq[f] && varVal[f] > varSum) {
                ignoreFreq[f] = true
            }
        }
        
        // 4. 第二次线性回归 (使用过滤后的频率)
        sumXY = 0f
        sumY = 0f
        numFreqUsed = 0
        
        for (f in 0 until NUM_FREQUENCIES) {
            if (!ignoreFreq[f]) {
                for (i in 0 until decSize) {
                    sumXY += i * phaseData[f][i]
                    sumY += phaseData[f][i]
                }
                numFreqUsed++
            }
        }
        
        if (numFreqUsed == 0) {
            lastDebugSnapshot = DebugSnapshot(
                frameIndex = frameCount + 1,
                powerThreshold = POWER_THR,
                avgPower = powers.average().toFloat(),
                minPower = powers.minOrNull() ?: 0f,
                maxPower = powers.maxOrNull() ?: 0f,
                powerPassCount = powerPassCount,
                firstRegressionFreqCount = firstRegressionFreqCount,
                secondRegressionFreqCount = 0,
                distanceDeltaMm = 0f
            )
            return 0f
        }
        
        delta = (sumXY - sumY * (decSize - 1) / 2f) / deltaX * NUM_FREQUENCIES / numFreqUsed
        val distanceDelta = -delta * decSize / 2f  // 距离变化 (mm)

        lastDebugSnapshot = DebugSnapshot(
            frameIndex = frameCount + 1,
            powerThreshold = POWER_THR,
            avgPower = powers.average().toFloat(),
            minPower = powers.minOrNull() ?: 0f,
            maxPower = powers.maxOrNull() ?: 0f,
            powerPassCount = powerPassCount,
            firstRegressionFreqCount = firstRegressionFreqCount,
            secondRegressionFreqCount = numFreqUsed,
            distanceDeltaMm = distanceDelta
        )

        return distanceDelta
    }
    
    /**
     * 重置累积距离
     */
    fun reset() {
        // 重置 DC 值
        dcValueReal.fill(0f)
        dcValueImag.fill(0f)
        maxValueReal.fill(0f)
        minValueReal.fill(0f)
        maxValueImag.fill(0f)
        minValueImag.fill(0f)
        // 重置速度窗口
        recentDeltas.fill(0f)
        velocityWindowPos = 0
        velocityWindowFilled = false
        lastDebugSnapshot = null
    }
}
