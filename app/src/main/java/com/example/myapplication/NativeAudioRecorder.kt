package com.example.myapplication

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object NativeAudioRecorder {
    private const val TAG = "NativeAudioRecorder"
    
    init {
        try {
            System.loadLibrary("nativeaudio")
        } catch (t: Throwable) {
            Log.e(TAG, "Load native lib failed", t)
        }
    }

    @JvmStatic external fun nativeStart(sampleRate: Int, framesPerBuffer: Int): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun pollAudioFrame(): Boolean  // 新增：从队列取数据

    interface Callback {
        fun onAudioData(data: ShortArray)
    }

    private var callback: Callback? = null
    private val isRunning = AtomicBoolean(false)
    private var pollingThread: Thread? = null

    fun setCallback(cb: Callback) {
        callback = cb
    }

    @JvmStatic fun onNativeAudioFrame(data: ShortArray, frames: Int) {
        // frames is currently equal to data.size; provided for future checks
        callback?.onAudioData(data)
    }

    fun start(sampleRate: Int = 48000, framesPerBuffer: Int = 1024): Boolean {
        if (!nativeStart(sampleRate, framesPerBuffer)) {
            return false
        }
        
        // 启动轮询线程，从C++队列中取数据
        isRunning.set(true)
        pollingThread = Thread {
            Log.d(TAG, "轮询线程启动")
            while (isRunning.get()) {
                // 尽可能快地清空队列
                while (pollAudioFrame()) {
                    // pollAudioFrame内部会调用onNativeAudioFrame
                }
                // 队列空了，稍微等待（避免空转）
                Thread.sleep(5)
            }
            Log.d(TAG, "轮询线程结束")
        }
        pollingThread?.priority = Thread.MAX_PRIORITY  // 高优先级
        pollingThread?.start()
        
        return true
    }

    fun stop() {
        isRunning.set(false)
        pollingThread?.join(1000)
        pollingThread = null
        nativeStop()
    }
}
