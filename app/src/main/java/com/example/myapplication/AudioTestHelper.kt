package com.example.myapplication

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * 音频测试工具 - 播放可听见的测试音（用于验证扬声器位置）
 */
object AudioTestHelper {
    
    private const val TAG = "AudioTestHelper"
    private const val SAMPLE_RATE = 48000
    private const val TEST_FREQUENCY = 1000  // 1kHz 测试音（可听见）
    
    /**
     * 播放测试音（3秒），用于确认扬声器位置
     * @param useEarpiece true=听筒, false=扬声器
     */
    fun playTestTone(useEarpiece: Boolean) {
        Thread {
            try {
                val duration = 3  // 3秒
                val numSamples = SAMPLE_RATE * duration
                val buffer = ShortArray(numSamples)
                
                // 生成 1kHz 正弦波
                for (i in 0 until numSamples) {
                    val phase = 2.0 * PI * TEST_FREQUENCY * i / SAMPLE_RATE
                    buffer[i] = (sin(phase) * Short.MAX_VALUE * 0.5).toInt().toShort()
                }
                
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                if (useEarpiece) 
                                    AudioAttributes.USAGE_VOICE_COMMUNICATION 
                                else 
                                    AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                if (useEarpiece)
                                    AudioAttributes.CONTENT_TYPE_SPEECH
                                else
                                    AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(numSamples * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                
                audioTrack.write(buffer, 0, buffer.size)
                
                Log.i(TAG, "========================================")
                Log.i(TAG, if (useEarpiece) 
                    "▶️  播放测试音 - 应该从 **顶部听筒** 发出" 
                else 
                    "▶️  播放测试音 - 应该从 **底部扬声器** 发出")
                Log.i(TAG, "  频率: ${TEST_FREQUENCY}Hz (可听见)")
                Log.i(TAG, "  时长: ${duration}秒")
                Log.i(TAG, "  请仔细听声音来自哪里！")
                Log.i(TAG, "========================================")
                
                audioTrack.play()
                Thread.sleep((duration * 1000).toLong())
                audioTrack.stop()
                audioTrack.release()
                
                Log.i(TAG, "✓ 测试音播放完毕")
                
            } catch (e: Exception) {
                Log.e(TAG, "播放测试音失败: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }
}
