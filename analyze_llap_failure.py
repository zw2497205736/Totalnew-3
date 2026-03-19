#!/usr/bin/env python3
"""
LLAP 失效场景分析工具
用于分析快速移动和非直线运动时的相位解缠失效
"""

import re
import sys
from datetime import datetime
import matplotlib.pyplot as plt
import numpy as np

def parse_llap_log(log_file):
    """解析日志文件，提取相位、距离、能量数据"""
    
    data = {
        'timestamps': [],
        'phase_change': [],      # 相位变化量
        'relative_distance': [], # 累积相对距离
        'energy_ratio': [],      # 能量比
        'state': [],             # 状态
        'events': []             # 特殊事件（重置、失效等）
    }
    
    start_time = None
    last_energy = 1.0
    last_state = 'UNKNOWN'
    
    with open(log_file, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            # 提取时间戳
            time_match = re.search(r'(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})', line)
            if not time_match:
                continue
            
            timestamp_str = time_match.group(1)
            try:
                current_time = datetime.strptime(f"2025-{timestamp_str}", "%Y-%m-%d %H:%M:%S.%f")
                if start_time is None:
                    start_time = current_time
                elapsed = (current_time - start_time).total_seconds()
            except:
                continue
            
            # 提取相位变化量: "✅ 相位: Δ=XX.XXmm, 累积=YY.YYmm"
            phase_match = re.search(r'✅ 相位.*?Δ=([-\d.]+)mm.*?累积=([-\d.]+)mm', line)
            if phase_match:
                phase_delta = float(phase_match.group(1))
                rel_dist = float(phase_match.group(2))
                
                data['timestamps'].append(elapsed)
                data['phase_change'].append(phase_delta)
                data['relative_distance'].append(rel_dist)
                data['energy_ratio'].append(last_energy)
                data['state'].append(last_state)
                
                # 检测异常：相位变化突变为0
                if abs(phase_delta) < 0.001 and len(data['phase_change']) > 1:
                    if abs(data['phase_change'][-2]) > 1.0:  # 前一帧有正常变化
                        data['events'].append({
                            'time': elapsed,
                            'type': '相位归零',
                            'detail': f'Δ从{data["phase_change"][-2]:.2f}突变为0'
                        })
            
            # 提取能量比: "能量=X.XX"
            energy_match = re.search(r'能量=([\d.]+)', line)
            if energy_match:
                last_energy = float(energy_match.group(1))
            
            # 提取状态: "状态变化: XXX → YYY" 或 "状态判定: XXX"
            state_change_match = re.search(r'状态变化.*?→\s*(\w+)', line)
            if state_change_match:
                last_state = state_change_match.group(1)
            else:
                state_match = re.search(r'状态判定:\s*(\w+)', line)
                if state_match:
                    last_state = state_match.group(1)
            
            # 检测能量达到1.2重置事件
            if '能量达到1.2重置' in line:
                dist_match = re.search(r'相对距离=([-\d.]+)', line)
                if dist_match:
                    data['events'].append({
                        'time': elapsed,
                        'type': '能量重置',
                        'detail': f'距离={dist_match.group(1)}mm'
                    })
    
    return data

def detect_failure_patterns(data):
    """检测失效模式 - 重点关注相对距离的异常变化"""
    
    failures = {
        '距离归零失效': [],      # 相对距离突然变为0或接近0
        '距离突变': [],          # 相对距离大幅跳变
        '相位变化异常': [],      # 相位变化量异常
        '能量重置事件': []       # 能量达到1.2触发的重置
    }
    
    for i in range(1, len(data['relative_distance'])):
        curr_dist = data['relative_distance'][i]
        prev_dist = data['relative_distance'][i-1]
        delta = data['phase_change'][i]
        
        # 关键失效1：相对距离突然归零（LLAP核心失效）
        if abs(prev_dist) > 50.0 and abs(curr_dist) < 10.0:
            failures['距离归零失效'].append({
                'time': data['timestamps'][i],
                'prev_dist': prev_dist,
                'curr_dist': curr_dist,
                'drop': prev_dist - curr_dist
            })
        
        # 关键失效2：相对距离突变（跳变超过100mm）
        dist_jump = abs(curr_dist - prev_dist)
        if dist_jump > 100.0 and abs(delta) < 50.0:  # 距离突变但相位变化正常
            failures['距离突变'].append({
                'time': data['timestamps'][i],
                'prev_dist': prev_dist,
                'curr_dist': curr_dist,
                'jump': curr_dist - prev_dist
            })
        
        # 辅助检测：相位变化量异常（超出理论最大值4.3mm）
        if abs(delta) > 4.3:
            failures['相位变化异常'].append({
                'time': data['timestamps'][i],
                'delta': delta,
                'distance': curr_dist
            })
    
    # 统计能量重置事件（这会导致距离清零）
    for event in data['events']:
        if event['type'] == '能量重置':
            failures['能量重置事件'].append(event)
    
    return failures

def visualize_failure(data, failures, output_file='llap_failure_analysis.png'):
    """可视化失效场景"""
    
    # 设置中文字体
    plt.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    
    fig, axes = plt.subplots(4, 1, figsize=(16, 12), sharex=True)
    fig.suptitle('LLAP 相位测距失效场景分析', fontsize=16, fontweight='bold')
    
    times = np.array(data['timestamps'])
    
    # 图1: 相位变化量
    ax1 = axes[0]
    ax1.plot(times, data['phase_change'], 'b-', linewidth=1.5, marker='.', markersize=3, label='相位变化量 Δd')
    ax1.axhline(y=0, color='gray', linestyle='--', alpha=0.5)
    ax1.axhline(y=4.3, color='orange', linestyle=':', alpha=0.7, label='最大可跟踪=4.3mm (λ/4)')
    ax1.axhline(y=-4.3, color='orange', linestyle=':', alpha=0.7)
    
    # 标注相位变化异常点
    for fail in failures['相位变化异常']:
        ax1.axvline(x=fail['time'], color='purple', linestyle='-.', alpha=0.7)
        ax1.annotate(f'超限\nΔ={fail["delta"]:.2f}', 
                    xy=(fail['time'], fail['delta']), 
                    xytext=(fail['time']+3, fail['delta']+2),
                    arrowprops=dict(arrowstyle='->', color='purple'),
                    fontsize=9, color='purple', weight='bold')
    
    ax1.set_ylabel('相位变化 (mm)', fontsize=11)
    ax1.set_title('相位变化量时序 (紫线=超出理论最大值4.3mm)', fontsize=12)
    ax1.legend(loc='best')
    ax1.grid(True, alpha=0.3)
    
    # 图2: 累积相对距离（LLAP失效的核心指标）
    ax2 = axes[1]
    ax2.plot(times, data['relative_distance'], 'g-', linewidth=1.5, marker='o', markersize=4, label='累积相对距离')
    ax2.axhline(y=0, color='red', linestyle='--', alpha=0.7, linewidth=2, label='失效线（距离=0）')
    
    # 标注距离归零失效点（LLAP核心失效）
    for fail in failures['距离归零失效']:
        ax2.axvline(x=fail['time'], color='red', linestyle='-', alpha=0.8, linewidth=2)
        ax2.annotate(f'❌ LLAP失效\n{fail["prev_dist"]:.1f}→{fail["curr_dist"]:.1f}mm', 
                    xy=(fail['time'], fail['curr_dist']), 
                    xytext=(fail['time']+5, fail['prev_dist']/2),
                    arrowprops=dict(arrowstyle='->', color='red', lw=2),
                    fontsize=10, color='red', weight='bold',
                    bbox=dict(boxstyle='round', facecolor='yellow', alpha=0.7))
    
    # 标注距离突变点
    for fail in failures['距离突变']:
        ax2.axvline(x=fail['time'], color='orange', linestyle='--', alpha=0.7)
        ax2.annotate(f'突变\n{fail["jump"]:.1f}mm', 
                    xy=(fail['time'], fail['curr_dist']), 
                    xytext=(fail['time']+3, fail['curr_dist']+20),
                    arrowprops=dict(arrowstyle='->', color='orange'),
                    fontsize=9, color='orange', weight='bold')
    
    # 标注能量重置事件
    reset_count = 0
    for event in failures['能量重置事件']:
        ax2.axvline(x=event['time'], color='purple', linestyle=':', alpha=0.4)
        if reset_count % 5 == 0:
            ax2.text(event['time'], min(data['relative_distance'])*0.9, 
                    '能量重置', fontsize=7, color='purple', rotation=90, alpha=0.6)
        reset_count += 1
    
    ax2.set_ylabel('相对距离 (mm)', fontsize=11)
    ax2.set_title(f'⚠️ 累积相对距离（红线=LLAP失效，归零{len(failures["距离归零失效"])}次 | 紫线=能量重置{reset_count}次）', 
                 fontsize=12, weight='bold')
    ax2.legend(loc='best')
    ax2.grid(True, alpha=0.3)
    
    # 图3: 能量比
    ax3 = axes[2]
    ax3.plot(times, data['energy_ratio'], 'm-', linewidth=1.5, marker='.', markersize=3, label='能量比')
    ax3.axhline(y=1.2, color='red', linestyle='--', alpha=0.7, linewidth=2, label='VERY_NEAR阈值=1.2')
    ax3.axhline(y=0.88, color='blue', linestyle='--', alpha=0.7, linewidth=2, label='低能量阈值=0.88')
    ax3.axhline(y=1.0, color='gray', linestyle=':', alpha=0.5, label='基线=1.0')
    
    ax3.set_ylabel('能量比', fontsize=11)
    ax3.set_title('能量比时序', fontsize=12)
    ax3.legend(loc='best')
    ax3.grid(True, alpha=0.3)
    
    # 图4: 状态时序
    ax4 = axes[3]
    state_map = {'VERY_NEAR': 2, 'NEAR': 1, 'FAR': 0, 'PENDING': 0.5, 'UNKNOWN': -1}
    state_values = [state_map.get(s, -1) for s in data['state']]
    ax4.plot(times, state_values, 'k-', linewidth=2, marker='o', markersize=4, label='检测状态')
    ax4.set_yticks([0, 0.5, 1, 2])
    ax4.set_yticklabels(['远离(FAR)', '待定(PENDING)', '接近(NEAR)', '极近(VERY_NEAR)'])
    ax4.axhline(y=1, color='orange', linestyle='--', alpha=0.3)
    ax4.axhline(y=2, color='red', linestyle='--', alpha=0.3)
    
    ax4.set_xlabel('时间 (秒)', fontsize=11)
    ax4.set_ylabel('状态', fontsize=11)
    ax4.set_title('检测状态时序', fontsize=12)
    ax4.grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"\n✅ 可视化图表已保存: {output_file}")
    plt.show()

def generate_report(data, failures, output_file='llap_failure_report.txt'):
    """生成文字报告 - 重点突出距离归零失效"""
    
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write("=" * 60 + "\n")
        f.write("⚠️  LLAP 相位测距失效场景分析报告\n")
        f.write("=" * 60 + "\n\n")
        
        f.write(f"📊 数据统计\n")
        f.write(f"  - 总采样点数: {len(data['timestamps'])}\n")
        f.write(f"  - 测试时长: {data['timestamps'][-1]:.2f} 秒\n")
        f.write(f"  - 平均采样率: {len(data['timestamps'])/data['timestamps'][-1]:.1f} Hz\n")
        f.write(f"  - 相对距离范围: {min(data['relative_distance']):.1f}mm ~ {max(data['relative_distance']):.1f}mm\n\n")
        
        f.write(f"🚨 失效场景统计（按严重程度排序）\n\n")
        
        # 核心失效1：距离归零（LLAP完全失效）
        f.write(f"❌ 1. LLAP完全失效（距离归零）: {len(failures['距离归零失效'])} 次\n")
        if failures['距离归零失效']:
            f.write("   【这是LLAP最严重的失效，相对距离突然归零，无法继续测距】\n")
            f.write("   详细记录:\n")
            for i, fail in enumerate(failures['距离归零失效'], 1):
                f.write(f"   [{i}] 时刻 {fail['time']:.2f}s: "
                       f"{fail['prev_dist']:.1f}mm → {fail['curr_dist']:.1f}mm "
                       f"(跌落{fail['drop']:.1f}mm)\n")
                f.write(f"       → 原因: 快速移动或旋转导致相位跟踪丢失\n")
        else:
            f.write("   ✅ 未检测到距离归零失效\n")
        f.write("\n")
        
        # 核心失效2：距离突变
        f.write(f"⚠️  2. 距离突变（跳变>100mm）: {len(failures['距离突变'])} 次\n")
        if failures['距离突变']:
            f.write("   【距离测量出现大幅跳跃，但未归零】\n")
            f.write("   详细记录:\n")
            for i, fail in enumerate(failures['距离突变'][:10], 1):
                f.write(f"   [{i}] 时刻 {fail['time']:.2f}s: "
                       f"{fail['prev_dist']:.1f}mm → {fail['curr_dist']:.1f}mm "
                       f"(跳变{fail['jump']:.1f}mm)\n")
        else:
            f.write("   ✅ 未检测到距离突变\n")
        f.write("\n")
        
        # 辅助指标：相位变化异常
        f.write(f"ℹ️  3. 相位变化超限（>4.3mm）: {len(failures['相位变化异常'])} 次\n")
        if failures['相位变化异常']:
            f.write("   【相位变化超出理论最大值λ/4=4.3mm，可能导致解缠失败】\n")
            for i, fail in enumerate(failures['相位变化异常'][:5], 1):
                f.write(f"   [{i}] 时刻 {fail['time']:.2f}s: Δ={fail['delta']:.2f}mm (距离={fail['distance']:.1f}mm)\n")
        else:
            f.write("   ✅ 相位变化均在理论范围内\n")
        f.write("\n")
        
        # 系统行为：能量重置
        f.write(f"🔄 4. 能量达到1.2触发重置: {len(failures['能量重置事件'])} 次\n")
        if failures['能量重置事件']:
            f.write("   【系统检测到能量≥1.2时会自动重置相对距离，这是正常保护机制】\n")
            f.write(f"   共计 {len(failures['能量重置事件'])} 次重置\n")
        f.write("\n")
        
        f.write("=" * 60 + "\n")
        f.write("💡 结论与建议\n")
        f.write("=" * 60 + "\n\n")
        
        f.write("【LLAP失效原因分析】\n\n")
        
        if len(failures['距离归零失效']) > 0:
            f.write("✅ 本次测试成功捕捉到LLAP失效场景！\n\n")
            f.write("1. 距离归零失效机制:\n")
            f.write("   - 快速移动导致相邻帧相位变化超过π（对应距离变化>4.3mm）\n")
            f.write("   - 相位解缠算法无法区分2π的整数倍，导致累积距离归零\n")
            f.write("   - 理论最大跟踪速度: v_max = (λ/4) × 采样率 ≈ 4.3mm × 12Hz = 51.6mm/s\n")
            f.write("   - 超过此速度后，LLAP完全失效，需要重新初始化\n\n")
        else:
            f.write("⚠️  本次测试未捕捉到明显的距离归零失效\n")
            f.write("   建议：手动测试时进行更快速的移动或旋转\n\n")
        
        f.write("2. 能量重置机制:\n")
        f.write("   - 当能量比≥1.2时，系统判断为VERY_NEAR状态\n")
        f.write("   - 此时会自动将相对距离重置为0（作为新的参考点）\n")
        f.write(f"   - 本次测试共触发{len(failures['能量重置事件'])}次重置\n\n")
        
        f.write("【给老师的结论】\n\n")
        f.write(f"📌 测试结果总结:\n")
        f.write(f"   - LLAP完全失效次数: {len(failures['距离归零失效'])} 次\n")
        f.write(f"   - 距离测量跳变次数: {len(failures['距离突变'])} 次\n")
        f.write(f"   - 相位超限次数: {len(failures['相位变化异常'])} 次\n\n")
        
        if len(failures['距离归零失效']) > 0:
            f.write("✅ 已成功验证LLAP在快速移动场景下的失效现象\n")
            f.write("   失效表现：相对距离从数十mm突然归零，无法恢复\n")
            f.write("   失效原因：相位解缠算法在高速运动下跟踪丢失\n")
        
        f.write("\n【改进建议】\n\n")
        f.write("1. 提高采样率: 从当前12Hz提升至50Hz以上，扩大可跟踪速度范围\n")
        f.write("2. 多频融合: 同时使用多个超声频率测距，互相验证\n")
        f.write("3. 状态预测: 引入卡尔曼滤波，根据历史数据预测下一帧位置\n")
        f.write("4. 失效检测: 检测到距离突变时触发报警，而非继续使用错误值\n")
    
    print(f"✅ 文字报告已保存: {output_file}")

def main():
    if len(sys.argv) < 2:
        print("用法: python analyze_llap_failure.py <日志文件>")
        print("\n示例:")
        print("  python analyze_llap_failure.py llap_failure_test_20250129_143022.log")
        sys.exit(1)
    
    log_file = sys.argv[1]
    
    print(f"🔍 正在分析日志文件: {log_file}")
    
    # 解析日志
    data = parse_llap_log(log_file)
    
    if not data['timestamps']:
        print("❌ 未找到有效数据，请检查日志文件格式")
        sys.exit(1)
    
    print(f"✅ 解析完成: {len(data['timestamps'])} 个数据点")
    
    # 检测失效模式
    failures = detect_failure_patterns(data)
    
    print(f"\n📊 失效场景统计:")
    print(f"  - ❌ LLAP完全失效（距离归零）: {len(failures['距离归零失效'])} 次")
    print(f"  - ⚠️  距离突变: {len(failures['距离突变'])} 次")
    print(f"  - ℹ️  相位变化超限: {len(failures['相位变化异常'])} 次")
    print(f"  - 🔄 能量重置: {len(failures['能量重置事件'])} 次")
    
    # 生成报告
    generate_report(data, failures)
    
    # 可视化
    visualize_failure(data, failures)

if __name__ == '__main__':
    main()
