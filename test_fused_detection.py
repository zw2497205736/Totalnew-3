#!/usr/bin/env python3
"""
融合检测器验证脚本
对比能量法和融合法的检测效果
"""

import numpy as np
import matplotlib.pyplot as plt
from scipy.io import wavfile
import sys

def analyze_fused_detection(wav_file):
    """
    分析 WAV 文件,模拟融合检测逻辑
    """
    # 读取 WAV 文件
    sample_rate, data = wavfile.read(wav_file)
    print(f"文件: {wav_file}")
    print(f"采样率: {sample_rate} Hz")
    print(f"时长: {len(data) / sample_rate:.2f} 秒")
    print(f"通道: {data.shape if len(data.shape) > 1 else '单声道'}")
    
    # 如果是立体声,只取左声道
    if len(data.shape) > 1:
        data = data[:, 0]
    
    # 参数
    frequencies = [20000 + i * 350 for i in range(10)]
    frame_size = 4096
    hop_size = 2048
    
    # Goertzel 提取能量
    def goertzel(samples, freq, fs):
        """Goertzel 算法"""
        N = len(samples)
        k = int(0.5 + N * freq / fs)
        omega = 2 * np.pi * k / N
        coeff = 2 * np.cos(omega)
        
        s_prev = 0
        s_prev2 = 0
        
        for sample in samples:
            s = sample + coeff * s_prev - s_prev2
            s_prev2 = s_prev
            s_prev = s
        
        power = s_prev2**2 + s_prev**2 - coeff * s_prev * s_prev2
        return np.sqrt(power / N)
    
    # 处理每一帧
    num_frames = (len(data) - frame_size) // hop_size + 1
    energy_ratios = []
    timestamps = []
    
    # 校准期 (10-13 秒)
    calib_start = int(10 * sample_rate)
    calib_end = int(13 * sample_rate)
    calib_frames = []
    
    for i in range(num_frames):
        start = i * hop_size
        end = start + frame_size
        frame = data[start:end].astype(np.float32) / 32768.0
        
        # 提取所有频率的能量
        energies = [goertzel(frame, freq, sample_rate) for freq in frequencies]
        
        # 收集校准帧
        if calib_start <= start < calib_end:
            calib_frames.append(energies)
        
        timestamps.append(start / sample_rate)
        energy_ratios.append(energies)
    
    # 计算基线
    if len(calib_frames) > 0:
        baseline = np.mean(calib_frames, axis=0)
        print(f"\n基线能量 (10-13秒平均): {np.mean(baseline):.2f}")
    else:
        baseline = np.ones(10) * 100  # 默认基线
        print("\n警告: 未找到校准区间,使用默认基线")
    
    # 计算能量比
    ratios = []
    for energies in energy_ratios:
        ratio = np.mean(energies) / np.mean(baseline)
        ratios.append(ratio)
    
    # 状态分类 (能量法)
    energy_states = []
    for ratio in ratios:
        if ratio >= 1.5:
            energy_states.append('VERY_NEAR')
        elif ratio >= 1.1:
            energy_states.append('NEAR')
        else:
            energy_states.append('FAR')
    
    # 模拟相位法 (简化版: 用能量变化率估计)
    relative_distances = []
    rel_dist = 0
    for i in range(1, len(ratios)):
        # 能量增加 → 物体靠近 (负距离变化)
        # 能量减小 → 物体远离 (正距离变化)
        energy_change = ratios[i] - ratios[i-1]
        dist_change = -energy_change * 50  # 简化映射: 能量变化 → 距离变化
        rel_dist += dist_change
        relative_distances.append(rel_dist)
    
    relative_distances.insert(0, 0)  # 第一帧相对距离为 0
    
    # 融合状态 (带重置逻辑)
    fused_states = []
    current_state = 'FAR'
    rel_dist_reset = 0
    state_start_idx = 0
    
    for i, (ratio, rel_dist) in enumerate(zip(ratios, relative_distances)):
        # 10 秒定时重置
        time_in_state = (i - state_start_idx) * hop_size / sample_rate
        if time_in_state >= 10.0:
            rel_dist_reset = rel_dist
            print(f"  [{timestamps[i]:.1f}s] 10秒重置: {current_state}, 相对距离={rel_dist:.1f}mm")
        
        # 相对距离(从上次重置开始)
        rel_dist_from_reset = rel_dist - rel_dist_reset
        
        # 状态转换
        if current_state == 'VERY_NEAR':
            if ratio < 1.5:
                current_state = 'NEAR'
                state_start_idx = i
                rel_dist_reset = rel_dist
                print(f"  [{timestamps[i]:.1f}s] VERY_NEAR → NEAR (能量={ratio:.2f})")
        
        elif current_state == 'NEAR':
            if ratio >= 1.5:
                current_state = 'VERY_NEAR'
                state_start_idx = i
                rel_dist_reset = rel_dist
                print(f"  [{timestamps[i]:.1f}s] NEAR → VERY_NEAR (能量={ratio:.2f})")
            elif ratio < 1.1 and rel_dist_from_reset > 30:
                current_state = 'FAR'
                state_start_idx = i
                rel_dist_reset = rel_dist
                print(f"  [{timestamps[i]:.1f}s] NEAR → FAR (能量={ratio:.2f}, 距离={rel_dist_from_reset:.1f}mm)")
        
        elif current_state == 'FAR':
            if ratio >= 1.5:
                current_state = 'VERY_NEAR'
                state_start_idx = i
                rel_dist_reset = rel_dist
                print(f"  [{timestamps[i]:.1f}s] FAR → VERY_NEAR (能量={ratio:.2f})")
            elif ratio >= 1.1 and rel_dist_from_reset < -50:
                current_state = 'NEAR'
                state_start_idx = i
                rel_dist_reset = rel_dist
                print(f"  [{timestamps[i]:.1f}s] FAR → NEAR (能量={ratio:.2f}, 距离={rel_dist_from_reset:.1f}mm)")
        
        fused_states.append(current_state)
    
    # 绘图对比
    fig, axes = plt.subplots(4, 1, figsize=(14, 10))
    
    # 1. 能量比
    axes[0].plot(timestamps, ratios, 'b-', linewidth=1.5, label='能量比')
    axes[0].axhline(y=1.5, color='r', linestyle='--', label='VERY_NEAR 阈值')
    axes[0].axhline(y=1.1, color='orange', linestyle='--', label='NEAR 阈值')
    axes[0].set_ylabel('能量比')
    axes[0].set_title('能量法检测')
    axes[0].legend()
    axes[0].grid(True, alpha=0.3)
    
    # 2. 能量法状态
    state_map = {'FAR': 0, 'NEAR': 1, 'VERY_NEAR': 2}
    energy_state_values = [state_map[s] for s in energy_states]
    axes[1].plot(timestamps, energy_state_values, 'g-', linewidth=2, label='能量法状态')
    axes[1].set_ylabel('状态')
    axes[1].set_yticks([0, 1, 2])
    axes[1].set_yticklabels(['FAR', 'NEAR', 'VERY_NEAR'])
    axes[1].set_title('能量法状态分类')
    axes[1].legend()
    axes[1].grid(True, alpha=0.3)
    
    # 3. 相对距离
    axes[2].plot(timestamps, relative_distances, 'm-', linewidth=1.5, label='相对距离')
    axes[2].axhline(y=30, color='orange', linestyle='--', label='NEAR→FAR 阈值 (+30mm)')
    axes[2].axhline(y=-50, color='purple', linestyle='--', label='FAR→NEAR 阈值 (-50mm)')
    axes[2].set_ylabel('相对距离 (mm)')
    axes[2].set_title('相位法相对距离 (简化模拟)')
    axes[2].legend()
    axes[2].grid(True, alpha=0.3)
    
    # 4. 融合法状态
    fused_state_values = [state_map[s] for s in fused_states]
    axes[3].plot(timestamps, fused_state_values, 'r-', linewidth=2, label='融合法状态')
    axes[3].set_ylabel('状态')
    axes[3].set_yticks([0, 1, 2])
    axes[3].set_yticklabels(['FAR', 'NEAR', 'VERY_NEAR'])
    axes[3].set_xlabel('时间 (秒)')
    axes[3].set_title('融合法状态 (能量 + 相位)')
    axes[3].legend()
    axes[3].grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig(wav_file.replace('.wav', '_fused_analysis.png'), dpi=150)
    print(f"\n✓ 分析结果已保存: {wav_file.replace('.wav', '_fused_analysis.png')}")
    plt.show()
    
    # 统计对比
    print("\n=== 能量法 vs 融合法对比 ===")
    for state in ['FAR', 'NEAR', 'VERY_NEAR']:
        energy_count = energy_states.count(state)
        fused_count = fused_states.count(state)
        print(f"{state:10s}: 能量法={energy_count:4d} 帧, 融合法={fused_count:4d} 帧")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法: python test_fused_detection.py <wav文件>")
        print("示例: python test_fused_detection.py proximity_test.wav")
        sys.exit(1)
    
    wav_file = sys.argv[1]
    analyze_fused_detection(wav_file)
