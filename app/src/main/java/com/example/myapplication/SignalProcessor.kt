package com.example.myapplication

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 信号处理器
 * FFT分析、幅度提取、滤波等
 */
class SignalProcessor {
    
    companion object {
        private const val SAMPLE_RATE = 48000  // 采样率 48 kHz（匹配脚本配置）
        private const val NUM_FREQUENCIES = 10  // 频率点数量
        private const val START_FREQ = 20000   // 起始频率 17.5kHz
        private const val FREQ_STEP = 350      // 频率步进 350Hz
        
        // 预计算二阶巴特沃斯高通滤波器系数（截止频率 15kHz）
        private val fc = 15000.0
        private val omega = 2.0 * PI * fc / SAMPLE_RATE
        private val cosOmega = cos(omega)
        private val sinOmega = sin(omega)
        
        // 二阶巴特沃斯滤波器 (Q = 0.7071)
        private val q = 0.7071
        private val alpha = sinOmega / (2.0 * q)
        private val b0 = (1.0 + cosOmega) / 2.0
        private val b1 = -(1.0 + cosOmega)
        private val b2 = (1.0 + cosOmega) / 2.0
        private val a0 = 1.0 + alpha
        private val a1 = -2.0 * cosOmega
        private val a2 = 1.0 - alpha
        
        // 归一化系数
        private val b0_norm = b0 / a0
        private val b1_norm = b1 / a0
        private val b2_norm = b2 / a0
        private val a1_norm = a1 / a0
        private val a2_norm = a2 / a0
    }
    
    // 高通滤波器状态变量
    private var hpf_x1 = 0.0
    private var hpf_x2 = 0.0
    private var hpf_y1 = 0.0
    private var hpf_y2 = 0.0
    
    /**
     * 二阶巴特沃斯高通滤波器 - 去除人声和低频噪声
     * 截止频率: 15kHz (人声通常 < 4kHz)
     * 优化：使用预计算的系数避免每次重复计算
     */
    fun highPassFilter(data: ShortArray): ShortArray {
        if (data.isEmpty()) return data
        
        val filtered = ShortArray(data.size)
        
        for (i in data.indices) {
            val x0 = data[i].toDouble()
            val y0 = b0_norm * x0 + b1_norm * hpf_x1 + b2_norm * hpf_x2 -
                     a1_norm * hpf_y1 - a2_norm * hpf_y2
            hpf_x2 = hpf_x1
            hpf_x1 = x0
            hpf_y2 = hpf_y1
            hpf_y1 = y0
            filtered[i] = y0.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
        
        return filtered
    }
    
    /**
     * 重置滤波器状态（开始新的录音时调用）
     */
    fun resetFilter() {
        hpf_x1 = 0.0
        hpf_x2 = 0.0
        hpf_y1 = 0.0
        hpf_y2 = 0.0
    }
    
    /**
     * 提取多个频率的幅度（使用 Goertzel 算法，比 FFT 更高效）
     */
    fun extractMagnitudes(audioData: ShortArray): FloatArray {
        val magnitudes = FloatArray(NUM_FREQUENCIES)
        
        for (i in 0 until NUM_FREQUENCIES) {
            val frequency = START_FREQ + i * FREQ_STEP
            magnitudes[i] = goertzelMagnitude(audioData, frequency)
        }
        
        // 【调试】每100帧打印一次幅度
        debugCounter++
        if (debugCounter % 100 == 0) {
            val max = magnitudes.maxOrNull() ?: 0f
            val avg = magnitudes.average().toFloat()
            android.util.Log.d("SignalProcessor", "幅度: 最大=${String.format("%.2f", max)}, 平均=${String.format("%.2f", avg)}")
        }
        
        return magnitudes
    }
    
    private var debugCounter = 0
    
    /**
     * Goertzel 算法 - 单频率检测
     * 比完整 FFT 更高效，适合只检测少数几个频率的场景
     */
    private fun goertzelMagnitude(samples: ShortArray, targetFreq: Int): Float {
        val n = samples.size
        val k = (0.5 + (n * targetFreq) / SAMPLE_RATE.toDouble()).toInt()
        val omega = (2.0 * PI * k) / n
        val coeff = 2.0 * cos(omega)
        
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        
        // Goertzel 迭代
        for (sample in samples) {
            q0 = coeff * q1 - q2 + sample.toDouble()
            q2 = q1
            q1 = q0
        }
        
        // 计算幅度
        val real = q1 - q2 * cos(omega)
        val imag = q2 * sin(omega)
        val magnitude = sqrt(real * real + imag * imag)
        
        return (magnitude / n).toFloat()  // 归一化
    }
    
    /**
     * 带通滤波器（简单的移动平均）
     */
    fun bandPassFilter(data: ShortArray): ShortArray {
        if (data.size < 3) return data
        
        val filtered = ShortArray(data.size)
        filtered[0] = data[0]
        
        for (i in 1 until data.size - 1) {
            // 简单的 3 点移动平均
            filtered[i] = ((data[i - 1].toInt() + data[i].toInt() + data[i + 1].toInt()) / 3).toShort()
        }
        
        filtered[data.size - 1] = data[data.size - 1]
        return filtered
    }
    
    /**
     * 计算 RMS (均方根) 能量
     */
    fun calculateRMS(data: ShortArray): Float {
        if (data.isEmpty()) return 0f
        
        var sum = 0.0
        for (sample in data) {
            sum += sample.toDouble() * sample.toDouble()
        }
        
        return sqrt(sum / data.size).toFloat()
    }
    
    /**
     * 计算平均幅度
     */
    fun calculateAverageMagnitude(magnitudes: FloatArray): Float {
        if (magnitudes.isEmpty()) return 0f
        return magnitudes.average().toFloat()
    }

    /**
     * Short数组转Float数组（归一化到[-1, 1]）
     */
    fun shortToFloatArray(data: ShortArray): FloatArray {
        return FloatArray(data.size) { i -> data[i] / 32768f }
    }

    /**
     * Teager-Kaiser包络提取
     * 使用公式: Ψ[x(n)] = x²(n) - x(n-1)·x(n+1)
     * 返回包络信号 = sqrt(|Ψ|)
     */
    fun teagerEnvelope(signal: FloatArray): FloatArray {
        val envelope = FloatArray(signal.size)
        // 边界点保持为0
        for (i in 1 until signal.size - 1) {
            val psi = signal[i] * signal[i] - signal[i - 1] * signal[i + 1]
            envelope[i] = sqrt(kotlin.math.abs(psi))
        }
        return envelope
    }

    /**
     * 计算Teager包络特征值（平均幅度）
     */
    fun calculateTeagerFeature(envelope: FloatArray): Float {
        if (envelope.isEmpty()) return 0f
        var sum = 0f
        var count = 0
        // 跳过边界点
        for (i in 1 until envelope.size - 1) {
            sum += envelope[i]
            count++
        }
        return if (count > 0) sum / count else 0f
    }
}
