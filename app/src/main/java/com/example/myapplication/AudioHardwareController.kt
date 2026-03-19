package com.example.myapplication

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 音频硬件控制器
 * 通过 tinymix 命令精确控制麦克风和扬声器的底层硬件路由
 * 需要 root 权限
 */
class AudioHardwareController {
    
    companion object {
        private const val TAG = "AudioHardwareController"
        
        /**
         * 禁用音频前端处理（HPF/IIR/NS/AEC/AGC等）
         * 这些处理可能会衰减超声波信号
         */
        fun disableFrontEndProcessing(): Boolean {
            Log.i(TAG, "========================================")
            Log.i(TAG, "禁用音频前端处理（保护超声波信号）")
            
            val commands = arrayOf(
                "tinymix 'HPF Switch' 0",
                "tinymix 'TX_IIR Enable' 0",
                "tinymix 'NS Enable' 0",
                "tinymix 'AEC Enable' 0",
                "tinymix 'AGC Enable' 0",
                "tinymix 'Speech Enhancement' 0",
                "tinymix 'EC_REF_RX' 0",
                "tinymix 'FLUENCE_ENABLE' 0"
            )
            
            var successCount = 0
            for (cmd in commands) {
                if (executeCommand(cmd)) {
                    successCount++
                    Log.d(TAG, "✓ $cmd")
                } else {
                    Log.d(TAG, "⊘ $cmd (控件可能不存在)")
                }
            }
            
            Log.i(TAG, "✓ 前端处理禁用完成 ($successCount/${commands.size} 成功)")
            Log.i(TAG, "========================================")
            
            return true  // 即使部分失败也返回 true，因为某些控件可能不存在
        }
        
        /**
         * 配置底部麦克风（Android AudioRecord 可访问）
         * 使用 ADC_L+AIN0 配置
         */
        fun setupTopMicrophone(): Boolean {
            Log.i(TAG, "========================================")
            Log.i(TAG, "开始配置底部麦克风（Android AudioRecord 路由）")
            
            // 第一步：禁用前端处理
            disableFrontEndProcessing()
            
            // 第二步：配置硬件路由（底部麦克风 - ADC_L + AIN0）
            val commands = arrayOf(
                // 启用上行链路通道
                "tinymix 'UL9_CH1 ADDA_UL_CH1' '1'",
                "tinymix 'UL9_CH2 ADDA_UL_CH2' '1'",
                "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '1'",
                "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '1'",
                "tinymix 'CM1_UL_MUX' 'CM1_16CH_PATH'",
                
                // 路由配置（底部麦克风使用 CH1 - 左通道）
                "tinymix 'MISO0_MUX' 'UL1_CH1'",
                "tinymix 'MISO1_MUX' 'UL1_CH1'",
                
                // 配置 ADC 和 PGA 到底部麦克风（左通道 L + AIN0）
                "tinymix 'ADC_L_Mux' 'Left Preamplifier'",
                "tinymix 'PGA_L_Mux' 'AIN0'",
                
                // 配置数字麦克风数据
                "tinymix 'DMIC0_MUX' 'DMIC_DATA0'",
                "tinymix 'DMIC1_MUX' 'DMIC_DATA0'"
            )
            
            var success = true
            for (cmd in commands) {
                if (!executeCommand(cmd)) {
                    Log.e(TAG, "✗ 执行失败: $cmd")
                    success = false
                } else {
                    Log.d(TAG, "✓ $cmd")
                }
            }
            
            if (success) {
                Log.i(TAG, "✓ 底部麦克风配置成功")
                Log.i(TAG, "  ADC_L_Mux: Left Preamplifier")
                Log.i(TAG, "  PGA_L_Mux: AIN0 (底部麦克风)")
                Log.i(TAG, "  MISO_MUX: UL1_CH1 (左通道)")
            } else {
                Log.e(TAG, "✗ 底部麦克风配置失败（可能需要 root 权限）")
            }
            Log.i(TAG, "========================================")
            
            return success
        }
        
        /**
         * 配置底部扬声器（外放扬声器）
         * Android USAGE_MEDIA + speakerphoneOn=true 已经能路由到底部扬声器
         * 这里只做 tinymix 配置确保硬件路由正确
         */
        fun setupTopSpeaker(): Boolean {
            Log.i(TAG, "========================================")
            Log.i(TAG, "开始配置底部扬声器（需要 root 权限）")
            
            // 底部扬声器配置
            val commands = arrayOf(
                // 启用下行链路通道（DL0 是标准媒体通道）
                "tinymix 'ADDA_DL_CH1 DL0_CH1' '1'",
                "tinymix 'ADDA_DL_CH2 DL0_CH1' '1'",
                
                // 路由到底部扬声器（LOL = Line Out Left）
                "tinymix 'DAC In Mux' 'Normal Path'",
                "tinymix 'LOL Mux' 'Playback'",
                
                // 启用功放
                "tinymix 'Ext_Speaker_Amp Switch' '1'",
                
                // 底部扬声器场景
                "tinymix 'Tran_Pa_Scene' '14'"  // 场景14：底部扬声器
            )
            
            var success = true
            for (cmd in commands) {
                if (!executeCommand(cmd)) {
                    Log.e(TAG, "✗ 执行失败: $cmd")
                    success = false
                } else {
                    Log.d(TAG, "✓ $cmd")
                }
            }
            
            if (success) {
                Log.i(TAG, "✓ 底部扬声器配置成功")
                Log.i(TAG, "  ADDA_DL: DL0 通道")
                Log.i(TAG, "  LOL Mux: Playback")
                Log.i(TAG, "  Ext_Speaker_Amp: ON")
            } else {
                Log.e(TAG, "✗ 底部扬声器配置失败（可能需要 root 权限）")
            }
            Log.i(TAG, "========================================")
            
            return success
        }
        
        /**
         * 重置音频硬件配置（对应厂家脚本的清理部分）
         */
        fun resetAudioHardware(): Boolean {
            Log.i(TAG, "========================================")
            Log.i(TAG, "重置音频硬件配置")
            
            val commands = arrayOf(
                // 重置麦克风配置（厂家 cm7_tinycap.bat 清理部分）
                "tinymix 'MISO0_MUX' 'UL1_CH2'",
                "tinymix 'MISO1_MUX' 'UL1_CH1'",
                "tinymix 'ADC_R_Mux' 'Idle'",
                "tinymix 'PGA_R_Mux' 'None'",
                "tinymix 'ADC_L_Mux' 'Idle'",
                "tinymix 'PGA_L_Mux' 'None'",
                "tinymix 'ADC_3_Mux' 'Idle'",
                "tinymix 'PGA_3_Mux' '0'",
                
                // 重置上行链路
                "tinymix 'UL9_CH1 ADDA_UL_CH1' '0'",
                "tinymix 'UL9_CH2 ADDA_UL_CH2' '0'",
                "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '0'",
                "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '0'",
                "tinymix 'CM1_UL_MUX' 'CM1_2CH_PATH'",
                
                // 重置扬声器配置（厂家 CM7_tinyplay.bat 清理部分）
                "tinymix 'RCV Mux' 'Open'",
                "tinymix 'ADDA_DL_CH1 DL2_CH1' '0'",
                "tinymix 'ADDA_DL_CH2 DL2_CH2' '0'",
                "tinymix 'ADDA_DL_CH1 DL0_CH1' '0'",
                "tinymix 'ADDA_DL_CH2 DL0_CH1' '0'",
                "tinymix 'Ext_Speaker_Amp Switch' '0'",
                "tinymix 'Tran_PA_OPEN' '0'",
                "tinymix 'LOL Mux' '0'"
            )
            
            var success = true
            for (cmd in commands) {
                executeCommand(cmd)  // 忽略错误
            }
            
            Log.i(TAG, "✓ 音频硬件配置已重置")
            Log.i(TAG, "========================================")
            
            return success
        }
        
        /**
         * 检查是否有 root 权限
         */
        fun hasRootPermission(): Boolean {
            return try {
                // 尝试多种方式检查 root
                val suLocations = arrayOf(
                    "/system/bin/su",
                    "/system/xbin/su", 
                    "/sbin/su",
                    "/su/bin/su",
                    "/magisk/.core/bin/su",
                    "/system/sd/xbin/su",
                    "/data/local/xbin/su",
                    "/data/local/bin/su"
                )
                
                // 先检查 su 文件是否存在
                var suPath: String? = null
                for (path in suLocations) {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        suPath = path
                        Log.d(TAG, "找到 su 文件: $path")
                        break
                    }
                }
                
                if (suPath == null) {
                    Log.e(TAG, "✗ 未找到 su 可执行文件，设备可能未 root")
                    return false
                }
                
                // 尝试执行 su 命令
                val process = Runtime.getRuntime().exec(suPath)
                val outputStream = java.io.DataOutputStream(process.outputStream)
                
                // 执行 id 命令检查 root 权限
                outputStream.writeBytes("id\n")
                outputStream.writeBytes("exit\n")
                outputStream.flush()
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readLine()
                
                process.waitFor()
                
                val hasRoot = output?.contains("uid=0") == true
                if (hasRoot) {
                    Log.i(TAG, "✓ 已获取 root 权限 (su path: $suPath)")
                    Log.d(TAG, "  输出: $output")
                } else {
                    Log.e(TAG, "✗ 无法获取 root 权限")
                    Log.d(TAG, "  输出: $output")
                }
                hasRoot
            } catch (e: Exception) {
                Log.e(TAG, "✗ 检查 root 权限失败: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                false
            }
        }
        
        /**
         * 执行 shell 命令（需要 root）
         */
        private fun executeCommand(command: String): Boolean {
            return try {
                val process = Runtime.getRuntime().exec("su")
                val outputStream = java.io.DataOutputStream(process.outputStream)
                val inputReader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                // 写入命令
                outputStream.writeBytes("$command\n")
                outputStream.writeBytes("echo COMMAND_DONE\n")
                outputStream.writeBytes("exit\n")
                outputStream.flush()
                
                // 读取输出
                var line: String?
                val output = StringBuilder()
                while (inputReader.readLine().also { line = it } != null) {
                    if (line == "COMMAND_DONE") break
                    output.append(line).append("\n")
                }
                
                // 读取错误输出
                val errors = StringBuilder()
                while (errorReader.readLine().also { line = it } != null) {
                    errors.append(line).append("\n")
                }
                
                val exitCode = process.waitFor()
                
                if (exitCode != 0 || errors.isNotEmpty()) {
                    if (errors.isNotEmpty()) {
                        Log.w(TAG, "命令警告: $command\n  错误: $errors")
                    }
                }
                
                if (output.isNotEmpty()) {
                    Log.v(TAG, "命令输出: $output")
                }
                
                exitCode == 0
            } catch (e: Exception) {
                Log.e(TAG, "执行命令失败: $command\n  异常: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
        
        /**
         * 获取 tinymix 配置信息（用于调试）
         */
        fun getTinymixInfo(control: String): String? {
            return try {
                val process = Runtime.getRuntime().exec("su")
                val outputStream = java.io.DataOutputStream(process.outputStream)
                
                outputStream.writeBytes("tinymix '$control'\n")
                outputStream.writeBytes("exit\n")
                outputStream.flush()
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                
                process.waitFor()
                output.toString().trim()
            } catch (e: Exception) {
                Log.e(TAG, "获取 tinymix 信息失败: $control\n  异常: ${e.message}")
                null
            }
        }
    }
}
