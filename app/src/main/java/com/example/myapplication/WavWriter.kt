package com.example.myapplication

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV 文件写入器
 * 支持将处理后的音频数据保存为 WAV 格式
 */
class WavWriter(
    private val context: Context,
    private val filename: String,
    private val sampleRate: Int = 44100,
    private val channels: Int = 1
) {
    
    companion object {
        private const val TAG = "WavWriter"
    }
    
    private var raf: RandomAccessFile? = null
    private var dataSize: Long = 0
    private var isRecording = false
    
    /**
     * 开始录制 WAV 文件
     */
    fun start() {
        try {
            val file = File(context.getExternalFilesDir(null), filename)
            raf = RandomAccessFile(file, "rw")
            raf?.setLength(0)
            
            // 写入占位 WAV header（稍后在 stop 时更新实际大小）
            writeWavHeaderPlaceholder()
            
            dataSize = 0
            isRecording = true
            
            Log.d(TAG, "✓ 开始录制 WAV 文件: ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "✗ 启动 WAV 文件失败", e)
            isRecording = false
        }
    }
    
    /**
     * 写入音频样本数据
     */
    @Synchronized
    fun writeSamples(samples: ShortArray) {
        if (!isRecording || raf == null) return
        
        try {
            // 写入 16-bit PCM 数据（little-endian）
            for (sample in samples) {
                val value = sample.toInt()
                raf?.writeByte(value and 0xFF)           // 低字节
                raf?.writeByte((value shr 8) and 0xFF)   // 高字节
            }
            
            dataSize += samples.size * 2L  // 每个样本 2 字节
            
        } catch (e: IOException) {
            Log.e(TAG, "✗ 写入样本数据失败", e)
        }
    }
    
    /**
     * 停止录制并关闭文件
     */
    fun stop(): String? {
        if (!isRecording) return null
        
        try {
            // 回到文件开头，写入正确的 WAV header
            raf?.seek(0)
            writeWavHeader(dataSize)
            raf?.close()
            
            val file = File(context.filesDir, filename)
            Log.d(TAG, "✓ WAV 文件已保存: ${file.absolutePath}")
            Log.d(TAG, "  文件大小: ${dataSize + 44} 字节")
            Log.d(TAG, "  音频时长: ${"%.2f".format(dataSize / (sampleRate * channels * 2.0))} 秒")
            
            isRecording = false
            return file.absolutePath
            
        } catch (e: IOException) {
            Log.e(TAG, "✗ 完成 WAV 文件失败", e)
            return null
        } finally {
            raf = null
        }
    }
    
    /**
     * 写入占位 WAV header（44 字节）
     */
    @Throws(IOException::class)
    private fun writeWavHeaderPlaceholder() {
        raf?.write(ByteArray(44))
    }
    
    /**
     * 写入完整的 WAV header
     */
    @Throws(IOException::class)
    private fun writeWavHeader(pcmDataSize: Long) {
        val byteRate = sampleRate * channels * 2  // 16-bit = 2 bytes
        val blockAlign = channels * 2
        val chunkSize = 36 + pcmDataSize  // 总文件大小 - 8
        
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        
        // RIFF chunk descriptor
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        
        // fmt sub-chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                        // Subchunk1Size (PCM)
        header.putShort(1.toShort())             // AudioFormat (PCM)
        header.putShort(channels.toShort())      // NumChannels
        header.putInt(sampleRate)                // SampleRate
        header.putInt(byteRate)                  // ByteRate
        header.putShort(blockAlign.toShort())    // BlockAlign
        header.putShort(16.toShort())            // BitsPerSample
        
        // data sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmDataSize.toInt())
        
        raf?.write(header.array())
    }
    
    /**
     * 是否正在录制
     */
    fun isRecording(): Boolean = isRecording
}
