package com.example.myapplication

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * 超声波信号接收器
 * 录音并提供原始音频数据
 */
class UltrasonicRecorder {
    
    // 麦克风类型枚举
    enum class MicType {
        DEFAULT,              // 默认麦克风（系统自动选择）
        BOTTOM,               // 底部麦克风（主麦克风，用于录音/录像）
        TOP,                  // 顶部麦克风（通话麦克风）
        CAMCORDER,            // 相机麦克风（通常使用多个麦克风）
        UNPROCESSED,          // 未处理音频（绕过 AEC/NS/AGC）
        VOICE_PERFORMANCE     // 语音性能模式（低延迟，最小处理）
    }
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordThread: Thread? = null
    private var currentMicType = MicType.BOTTOM  // 默认使用底部麦克风
    
    companion object {
        private const val TAG = "UltrasonicRecorder"
        private const val SAMPLE_RATE = 48000  // 采样率 48 kHz（匹配脚本配置）
        private const val BUFFER_SIZE = 4096   // 缓冲区大小
    }
    
    // 音频数据回调
    private var onAudioDataCallback: ((ShortArray) -> Unit)? = null
    
    /**
     * 设置音频数据回调
     */
    fun setAudioDataCallback(callback: (ShortArray) -> Unit) {
        this.onAudioDataCallback = callback
    }
    
    /**
     * 设置麦克风类型
     */
    fun setMicType(micType: MicType) {
        this.currentMicType = micType
        Log.d(TAG, "麦克风类型设置为: $micType")
    }
    
    /**
     * 获取当前麦克风类型
     */
    fun getMicType(): MicType = currentMicType
    
    /**
     * 根据麦克风类型获取对应的音频源
     */
    private fun getAudioSourceForMicType(micType: MicType): Int {
        return when (micType) {
            MicType.DEFAULT -> {
                Log.d(TAG, "使用默认麦克风（系统自动选择）")
                MediaRecorder.AudioSource.MIC
            }
            MicType.BOTTOM -> {
                Log.d(TAG, "使用底部麦克风（主麦克风，对应脚本 ADC_L/PGA_L/AIN0）")
                MediaRecorder.AudioSource.MIC
            }
            MicType.TOP -> {
                Log.d(TAG, "使用顶部麦克风（通话麦克风，对应脚本 ADC_R/PGA_R/AIN2/DMIC_DATA1_R）")
                Log.w(TAG, "⚠️ 注意：VOICE_COMMUNICATION 会启用 AEC/NS/AGC 预处理，可能衰减超声波")
                MediaRecorder.AudioSource.VOICE_COMMUNICATION  // 顶部麦克风（通话）
            }
            MicType.CAMCORDER -> {
                Log.d(TAG, "使用相机麦克风（多麦克风阵列）")
                MediaRecorder.AudioSource.CAMCORDER
            }
            MicType.UNPROCESSED -> {
                Log.d(TAG, "使用 UNPROCESSED 音频源（绕过 AEC/NS/AGC，保留原始超声波）")
                MediaRecorder.AudioSource.UNPROCESSED  // Android 7.0+ (API 24)
            }
            MicType.VOICE_PERFORMANCE -> {
                Log.d(TAG, "使用 VOICE_PERFORMANCE 音频源（低延迟，最小预处理）")
                MediaRecorder.AudioSource.VOICE_PERFORMANCE  // Android 10+ (API 29)
            }
        }
    }
    
    /**
     * 开始录音
     */
    fun startRecording(): Boolean {
        if (isRecording) return true
        
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size")
                return false
            }
            
            val audioSource = getAudioSourceForMicType(currentMicType)
            Log.i(TAG, "========================================")
            Log.i(TAG, "音频录音配置:")
            Log.i(TAG, "  麦克风类型: $currentMicType")
            Log.i(TAG, "  AudioSource: $audioSource")
            Log.i(TAG, "  采样率: $SAMPLE_RATE Hz")
            Log.i(TAG, "  声道: MONO (单声道)")
            Log.i(TAG, "  编码: PCM 16-bit")
            Log.i(TAG, "========================================")
            
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            // 在后台线程持续录音
            recordThread = Thread {
                val buffer = ShortArray(BUFFER_SIZE)
                
                while (isRecording && !Thread.currentThread().isInterrupted) {
                    try {
                        val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        
                        if (readSize > 0) {
                            // 回调音频数据
                            onAudioDataCallback?.invoke(buffer.copyOf(readSize))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading audio", e)
                        break
                    }
                }
            }
            recordThread?.start()
            
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for recording", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            return false
        }
    }
    
    /**
     * 停止录音
     */
    fun stopRecording() {
        isRecording = false
        
        recordThread?.interrupt()
        recordThread?.join(1000)
        recordThread = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        
        audioRecord = null
    }
    
    /**
     * 是否正在录音
     */
    fun isRecording(): Boolean = isRecording
}
