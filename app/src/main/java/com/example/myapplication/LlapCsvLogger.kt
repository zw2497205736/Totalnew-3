package com.example.myapplication

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LlapCsvLogger(private val context: Context) {

    companion object {
        private const val TAG = "LlapCsvLogger"
    }

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var writeCount = 0

    @Synchronized
    fun start(): String {
        if (writer != null && file != null) {
            return file!!.absolutePath
        }

        val dir = File(context.getExternalFilesDir(null), "llap_logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        file = File(dir, "llap_debug_$timestamp.csv")
        writer = BufferedWriter(FileWriter(file!!, false))

        writer?.write(
            "ts_ms,volume,energy_ratio,rel_dist_mm,delta_mm,phase_vel_mm_per_frame," +
                    "power_thr,avg_power,min_power,max_power,power_pass,freq_used_1st,freq_used_2nd\n"
        )
        writer?.flush()
        writeCount = 0

        Log.i(TAG, "LLAP 调试日志已启动: ${file!!.absolutePath}")
        return file!!.absolutePath
    }

    @Synchronized
    fun append(metrics: FusedProximityDetector.LlapFrameMetrics) {
        val currentWriter = writer ?: return

        val line = String.format(
            Locale.US,
            "%d,%d,%.6f,%.6f,%.6f,%.6f,%.1f,%.3f,%.3f,%.3f,%d,%d,%d\n",
            metrics.timestampMs,
            metrics.volume,
            metrics.energyRatio,
            metrics.relativeDistanceMm,
            metrics.distanceDeltaMm,
            metrics.phaseVelocityMmPerFrame,
            metrics.powerThreshold,
            metrics.avgPower,
            metrics.minPower,
            metrics.maxPower,
            metrics.powerPassCount,
            metrics.firstRegressionFreqCount,
            metrics.secondRegressionFreqCount
        )
        currentWriter.write(line)

        writeCount++
        if (writeCount % 25 == 0) {
            currentWriter.flush()
        }
    }

    @Synchronized
    fun stop() {
        try {
            writer?.flush()
            writer?.close()
            if (file != null) {
                Log.i(TAG, "LLAP 调试日志已关闭: ${file!!.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "关闭 LLAP 日志失败", e)
        } finally {
            writer = null
            file = null
            writeCount = 0
        }
    }
}
