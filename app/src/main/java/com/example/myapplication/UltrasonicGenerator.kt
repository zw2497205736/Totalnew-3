package com.example.myapplication

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * 超声波信号发射器
 * 生成并播放多频率超声波信号 (17.5-21kHz)
 */
class UltrasonicGenerator {
    
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playThread: Thread? = null
    
    // 【新增】相位累积器，保持相位连续性，消除"咔嗒"声
    private val phaseAccumulators = DoubleArray(NUM_FREQUENCIES)
    
    companion object {
        private const val TAG = "UltrasonicGenerator"
        private const val SAMPLE_RATE = 48000  // 采样率 48 kHz（匹配脚本配置）
        private const val BUFFER_SIZE = 4096   // 缓冲区大小
        private const val NUM_FREQUENCIES = 10  // 频率点数量
        private const val START_FREQ = 20000   // 起始频率 20kHz（完全超声波）
        private const val FREQ_STEP = 350      // 频率步进 350Hz (到23.15kHz)
    }
    
    // 生成多频率混合的超声波信号（相位连续，消除咔嗒声）
    private fun generateMultiFrequencySignal(numSamples: Int): ShortArray {
        val buffer = ShortArray(numSamples)
        // 【最大功率】提高幅度到 100%（最大输出功率）
        val amplitude = (Short.MAX_VALUE * 1.0 / NUM_FREQUENCIES).toInt()
        
        for (i in 0 until numSamples) {
            var sample = 0.0
            
            // 混合多个频率，使用相位累积器保持连续性
            for (f in 0 until NUM_FREQUENCIES) {
                val frequency = START_FREQ + f * FREQ_STEP
                // 使用累积相位而不是重新计算，确保缓冲区之间平滑过渡
                sample += sin(phaseAccumulators[f])
                
                // 更新相位累积器（2π * frequency / sampleRate）
                phaseAccumulators[f] += 2.0 * PI * frequency / SAMPLE_RATE
                
                // 防止相位累积器溢出（保持在 0-2π 范围内）
                if (phaseAccumulators[f] > 2.0 * PI) {
                    phaseAccumulators[f] -= 2.0 * PI
                }
            }
            
            buffer[i] = (sample * amplitude).toInt().toShort()
        }
        
        return buffer

    }
    
    /**
     * 开始播放超声波
     */
    fun startPlaying() {
        if (isPlaying) {
            Log.w(TAG, "超声波已在播放中，跳过")
            return
        }
        
        Log.i(TAG, ">>> 开始启动超声波发射器...")
        
        // 重置相位累积器
        for (i in phaseAccumulators.indices) {
            phaseAccumulators[i] = 0.0
        }
        
        try {
            // 创建 AudioTrack
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            Log.d(TAG, "AudioTrack 最小缓冲区大小: $bufferSize bytes")
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)  // 媒体模式，路由到底部扬声器
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)  // 音乐内容
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            // 设置 AudioTrack 音量到最大
            audioTrack?.setVolume(1.0f)  // 1.0 = 100%
            
            audioTrack?.play()
            isPlaying = true
            
            Log.i(TAG, "========================================")
            Log.i(TAG, "✓ 超声波发射器启动（底部扬声器模式）")
            Log.i(TAG, "  音频属性: USAGE_MEDIA")
            Log.i(TAG, "  输出设备: 底部扬声器")
            Log.i(TAG, "  频率范围: ${START_FREQ}Hz - ${START_FREQ + (NUM_FREQUENCIES-1) * FREQ_STEP}Hz")
            Log.i(TAG, "  采样率: ${SAMPLE_RATE}Hz")
            Log.i(TAG, "========================================")
            
            // 在后台线程持续播放
            playThread = Thread {
                // 每次循环都重新生成信号（相位会自动连续）
                while (isPlaying && !Thread.currentThread().isInterrupted) {
                    try {
//                        val signal = generateMultiFrequencySignal(BUFFER_SIZE)
                        // TODO: 调用 Python 进行测试
                        val signalTmp = PyBridge.instance?.getAudioBufferToSend()
                        val signal = signalTmp ?: ShortArray(1)
                        if (signal.size == 1) {
                            Log.i(TAG, "获取播放音频数据失败")
                            continue
                        }
                        audioTrack?.write(signal, 0, signal.size)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        break
                    }
                }
            }
            playThread?.start()
            
        } catch (e: Exception) {
            e.printStackTrace()
            isPlaying = false
        }
    }
    
    /**
     * 停止播放超声波
     */
    fun stopPlaying() {
        isPlaying = false
        
        playThread?.interrupt()
        playThread?.join(1000)
        playThread = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
    
    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean = isPlaying
}
