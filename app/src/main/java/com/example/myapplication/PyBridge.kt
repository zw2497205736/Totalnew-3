package com.example.myapplication

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PyBridge {

    class PyBridgeState(val energy_ratio: Float,
                        val state: String,
                        val relative_distance: Float,
                        val time_in_state: Int) {
    }

    companion object {
        var instance: PyBridge? = null
    }

    private val lock = Object()

    private val api: PyObject by lazy {
        Python.getInstance().getModule("sd_core.api")
    }

    fun init(context: Context) {
        // 启动 Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
            instance = this
        }
    }

    // ========== 接口 1: 获取待发送音频 ==========
    fun getAudioBufferToSend(): ShortArray {
        var byteArray: ByteArray? = null
        synchronized(lock) {
            byteArray = api.callAttr("get_audio_buffer_to_send").toJava(ByteArray::class.java)
        }

        return ByteBuffer.wrap(byteArray!!)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .let { buf -> ShortArray(buf.remaining()).apply { buf.get(this) } }
    }

    // ========== 接口 2: 处理音频 ==========
    fun processAudioBuffer(currentVolume: Int, audioBuffer: ShortArray): PyBridgeState {
        val byteArray = ByteBuffer.allocate(audioBuffer.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { audioBuffer.forEach { putShort(it) } }
            .array()

        var jsonStr: String = ""
        synchronized(lock) {
            jsonStr = api.callAttr(
                "process_audio_buffer",
                currentVolume,
                byteArray
            ).toString()
        }

        val jsonObj = JSONObject(jsonStr)
        return PyBridgeState(jsonObj.getDouble("energy_ratio").toFloat(),
                            jsonObj.getString("state"),
                            jsonObj.getDouble("relative_distance").toFloat(),
                            jsonObj.getInt("time_in_state"))
    }

    // ========== 接口 3: 获取检测状态 ==========
    fun getDetectState(): PyBridgeState {
        val jsonStr = api.callAttr("get_detect_state").toString()
        val jsonObj = JSONObject(jsonStr)
        return PyBridgeState(jsonObj.getDouble("energy_ratio").toFloat(),
                            jsonObj.getString("state"),
                            jsonObj.getDouble("relative_distance").toFloat(),
                            jsonObj.getInt("time_in_state"))
    }

    // ========== 接口 4: 重置 ==========
    fun reset() {
        api.callAttr("reset")
    }
}
