#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
超声波能量分析脚本
分析 WAV 文件中的信号能量随时间变化，并计算相对基准的倍数
"""

import argparse
from typing import Optional
import numpy as np
import matplotlib.pyplot as plt
import matplotlib as mpl
from scipy.io import wavfile
from scipy.signal import butter, filtfilt
import os


def configure_matplotlib_fonts():
    """Configure matplotlib fonts to render Chinese reliably across OSes."""
    # Prefer common CJK fonts; matplotlib will pick the first available.
    mpl.rcParams['font.sans-serif'] = [
        'PingFang SC',        # macOS
        'Heiti SC',           # macOS (older)
        'Songti SC',          # macOS
        'Microsoft YaHei',    # Windows
        'SimHei',             # Windows/Linux (if installed)
        'Noto Sans CJK SC',   # Linux
        'Arial Unicode MS',   # macOS/Office
        'DejaVu Sans',        # fallback
    ]
    mpl.rcParams['axes.unicode_minus'] = False


# App-side baseline map (from ProximityDetector.kt, 2026-01-06)
VOLUME_BASELINE_MAP = {
    1: 0.35386017,
    2: 0.8211683,
    3: 1.4344041,
    4: 3.602211,
    5: 6.457968,
    6: 8.704215,
    7: 12.430713,
    8: 13.350524,
    9: 16.39806,
    10: 17.125603,
    11: 18.31323,
    12: 19.09781,
    13: 19.356688,
    14: 19.347998,
    15: 20.330273,
}


def app_very_near_threshold(volume: int) -> float:
    # Mirrors ProximityDetector.getVeryNearThreshold()
    if volume <= 2:
        return 2.0
    if volume <= 4:
        return 1.8
    if volume <= 6:
        return 1.6
    return 1.5


def app_near_threshold(volume: int) -> float:
    # Mirrors ProximityDetector.getNearThreshold()
    if volume <= 2:
        return 1.5
    if volume <= 4:
        return 1.4
    if volume <= 6:
        return 1.3
    return 1.25

def butter_bandpass(lowcut, highcut, fs, order=5):
    """创建带通滤波器"""
    nyq = 0.5 * fs
    low = lowcut / nyq
    high = highcut / nyq
    b, a = butter(order, [low, high], btype='band')
    return b, a

def bandpass_filter(data, lowcut, highcut, fs, order=5):
    """应用带通滤波器"""
    b, a = butter_bandpass(lowcut, highcut, fs, order=order)
    y = filtfilt(b, a, data)
    return y

def calculate_energy(signal, window_size):
    """
    计算信号的能量（使用滑动窗口）
    
    参数:
        signal: 音频信号
        window_size: 窗口大小（采样点数）
    
    返回:
        能量数组
    """
    # 计算 RMS（均方根）能量
    energy = np.array([
        np.sqrt(np.mean(signal[i:i+window_size]**2))
        for i in range(0, len(signal) - window_size, window_size // 4)  # 75% 重叠
    ])
    return energy

def analyze_wav_energy(
    wav_file,
    baseline_window_start_s: float = 10.0,
    baseline_window_end_s: float = 13.0,
    use_map_baseline: bool = False,
    volume: Optional[int] = None,
):
    """
    分析 WAV 文件的能量变化
    
    参数:
        wav_file: WAV 文件路径
        calibration_duration: 校准时长（秒），默认前 2 秒作为基准
    """
    print("=" * 60)
    print(f"📂 正在分析文件: {wav_file}")
    print("=" * 60)

    configure_matplotlib_fonts()
    
    # 读取 WAV 文件
    sample_rate, audio_data = wavfile.read(wav_file)
    
    # 转换为浮点数 [-1, 1]
    if audio_data.dtype == np.int16:
        audio_data = audio_data.astype(np.float32) / 32768.0
    elif audio_data.dtype == np.int32:
        audio_data = audio_data.astype(np.float32) / 2147483648.0
    
    # 如果是立体声，取单声道
    if len(audio_data.shape) > 1:
        audio_data = audio_data[:, 0]
    
    duration = len(audio_data) / sample_rate
    
    print(f"✓ 采样率: {sample_rate} Hz")
    print(f"✓ 时长: {duration:.2f} 秒")
    print(f"✓ 采样点数: {len(audio_data)}")
    print()
    
    # 应用带通滤波器，提取超声波频段（尽量贴近App：20kHz起的超声）
    print("🔧 应用带通滤波器 (20-23.15 kHz)...")
    filtered_data = bandpass_filter(audio_data, 20000, 23150, sample_rate)
    
    # 计算能量（窗口大小约 100ms）
    window_size = int(sample_rate * 0.1)  # 100ms 窗口
    print(f"🔧 计算能量（窗口大小: {window_size} 采样点 = 100ms）...")
    energy = calculate_energy(filtered_data, window_size)
    
    # 时间轴（对应能量数组）
    time_axis = np.linspace(0, duration, len(energy))
    
    # 计算基准能量（默认使用 10-13 秒之间的数据，避免启动瞬态）
    baseline_window_start_s = max(0.0, float(baseline_window_start_s))
    baseline_window_end_s = max(baseline_window_start_s, float(baseline_window_end_s))
    calibration_start_samples = int(baseline_window_start_s * len(energy) / duration)
    calibration_end_samples = int(baseline_window_end_s * len(energy) / duration)
    baseline_energy_wav = np.mean(energy[calibration_start_samples:calibration_end_samples])
    
    print(f"✓ WAV 基准能量 ({baseline_window_start_s:.1f}-{baseline_window_end_s:.1f} 秒): {baseline_energy_wav:.6f}")
    print()

    # 可选：把 WAV 能量等效映射到 App 的基线映射表量纲上（仅用于可视化对齐）
    # 做法：假设基准窗口对应“远离/无遮挡”状态，则将该窗口的能量映射为 VOLUME_BASELINE_MAP[volume]。
    map_baseline = None
    scale_to_app = 1.0
    if use_map_baseline:
        if volume is None:
            raise ValueError("use_map_baseline=True 时必须提供 --volume")
        if volume not in VOLUME_BASELINE_MAP:
            raise ValueError(f"volume 必须是 1-15，当前={volume}")
        if baseline_energy_wav <= 0:
            raise ValueError("WAV 基准能量<=0，无法映射到 App 基线")

        map_baseline = float(VOLUME_BASELINE_MAP[volume])
        scale_to_app = map_baseline / float(baseline_energy_wav)
        energy_for_plot = energy * scale_to_app
        baseline_energy_for_plot = map_baseline
        energy_ratio = energy_for_plot / baseline_energy_for_plot
    else:
        energy_for_plot = energy
        baseline_energy_for_plot = baseline_energy_wav
        energy_ratio = energy / baseline_energy_wav
    
    # 统计信息
    print("=" * 60)
    print("📊 统计信息:")
    if use_map_baseline:
        print(f"  App 映射表基线(音量{volume}): {baseline_energy_for_plot:.6f}")
        print(f"  WAV→App 等效缩放: x{scale_to_app:.3f}")
    else:
        print(f"  WAV 基准能量: {baseline_energy_for_plot:.6f}")
    print(f"  最大能量: {np.max(energy_for_plot):.6f} (倍数: {np.max(energy_ratio):.2f}x)")
    print(f"  最小能量: {np.min(energy_for_plot):.6f} (倍数: {np.min(energy_ratio):.2f}x)")
    print(f"  平均能量: {np.mean(energy_for_plot):.6f} (倍数: {np.mean(energy_ratio):.2f}x)")
    print("=" * 60)
    print()
    
    # 创建图表（从 10 秒后开始显示）
    start_display_time = 10.0
    start_index = int(start_display_time * len(energy) / duration)
    
    time_axis_display = time_axis[start_index:]
    energy_display = energy_for_plot[start_index:]
    energy_ratio_display = energy_ratio[start_index:]
    
    fig, axes = plt.subplots(2, 1, figsize=(14, 10))
    
    # 子图1: 绝对能量随时间变化
    axes[0].plot(time_axis_display, energy_display, linewidth=1.5, color='#2E86AB', alpha=0.8)
    axes[0].axhline(y=baseline_energy_for_plot, color='red', linestyle='--', linewidth=2,
                    label=f"基准 = {baseline_energy_for_plot:.6f}" + (f" (音量{volume}映射)" if use_map_baseline else " (WAV窗口)"))
    axes[0].axvspan(baseline_window_start_s, baseline_window_end_s, alpha=0.2, color='yellow',
                    label=f"基准窗口 ({baseline_window_start_s:.0f}-{baseline_window_end_s:.0f}秒)")
    axes[0].set_xlabel('时间 (秒)', fontsize=12)
    axes[0].set_ylabel('能量 (RMS)', fontsize=12)
    axes[0].set_title('超声波信号能量随时间变化', fontsize=14, fontweight='bold')
    axes[0].grid(True, alpha=0.3)
    axes[0].legend(fontsize=10)
    
    # 子图2: 相对倍数随时间变化
    axes[1].plot(time_axis_display, energy_ratio_display, linewidth=1.5, color='#A23B72', alpha=0.8)
    axes[1].axhline(y=1.0, color='red', linestyle='--', linewidth=2, 
                    label='基准倍数 = 1.0x')
    if use_map_baseline and volume is not None:
        near_th = app_near_threshold(volume)
        very_near_th = app_very_near_threshold(volume)
        axes[1].axhline(y=very_near_th, color='orange', linestyle=':', linewidth=1.5,
                        label=f'VERY_NEAR 阈值 = {very_near_th:.2f}x (App)')
        axes[1].axhline(y=near_th, color='green', linestyle=':', linewidth=1.5,
                        label=f'NEAR 阈值 = {near_th:.2f}x (App)')
    else:
        axes[1].axhline(y=1.5, color='orange', linestyle=':', linewidth=1.5,
                        label='VERY_NEAR 阈值 = 1.5x')
        axes[1].axhline(y=1.1, color='green', linestyle=':', linewidth=1.5,
                        label='NEAR 阈值 = 1.1x')
    axes[1].axvspan(baseline_window_start_s, baseline_window_end_s, alpha=0.2, color='yellow',
                    label=f"基准窗口 ({baseline_window_start_s:.0f}-{baseline_window_end_s:.0f}秒)")
    axes[1].set_xlabel('时间 (秒)', fontsize=12)
    axes[1].set_ylabel('能量倍数 (相对基准)', fontsize=12)
    axes[1].set_title('相对基准的能量倍数变化', fontsize=14, fontweight='bold')
    axes[1].grid(True, alpha=0.3)
    axes[1].legend(fontsize=10)
    
    # 设置 y 轴下限为 0
    axes[0].set_ylim(bottom=0)
    axes[1].set_ylim(bottom=0)
    
    plt.tight_layout()
    
    # 保存图表
    output_file = wav_file.replace('.wav', '_energy_analysis.png')
    plt.savefig(output_file, dpi=150, bbox_inches='tight')
    print(f"✓ 能量分析图表已保存: {output_file}")
    print()
    
    # 显示图表
    plt.show()

def main():
    parser = argparse.ArgumentParser(description="超声波能量分析脚本（WAV离线分析）")
    parser.add_argument("wav_file", help="WAV 文件路径")
    parser.add_argument("--baseline-start", type=float, default=10.0, help="基准窗口开始时间(秒)，默认10")
    parser.add_argument("--baseline-end", type=float, default=13.0, help="基准窗口结束时间(秒)，默认13")
    parser.add_argument("--use-map-baseline", action="store_true", help="把WAV能量等效映射到App音量-基线映射表量纲")
    parser.add_argument("--volume", type=int, default=None, help="当前媒体音量档位(1-15)，配合 --use-map-baseline")

    args = parser.parse_args()

    if not os.path.exists(args.wav_file):
        raise SystemExit(f"❌ 错误: 文件不存在: {args.wav_file}")

    analyze_wav_energy(
        args.wav_file,
        baseline_window_start_s=args.baseline_start,
        baseline_window_end_s=args.baseline_end,
        use_map_baseline=args.use_map_baseline,
        volume=args.volume,
    )

if __name__ == "__main__":
    main()
