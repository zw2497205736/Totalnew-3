# 融合接近检测系统 - 技术文档

## 📐 系统架构

### 双算法融合
```
音频流 (48kHz, 20-23.15kHz)
    │
    ├─→ [能量法] ProximityDetector
    │   └─→ Goertzel算法 → 能量比 → 快速状态分类
    │
    └─→ [相位法] PhaseRangeFinder  
        └─→ I/Q解调 → CIC滤波 → 相位解缠 → 相对距离
    
    ↓
[融合检测器] FusedProximityDetector
    ├─ 双重验证状态转换
    ├─ 智能重置机制
    └─ 状态时间跟踪
```

## 🔬 算法对比

| 特性 | 能量法 | 相位法 (LLAP) |
|------|--------|--------------|
| **算法** | Goertzel频谱分析 | I/Q解调 + 相位解缠 |
| **输出** | 能量比 (0.5-3.0) | 相对距离 (mm) |
| **精度** | ~2-5 cm | ~1-2 mm |
| **延迟** | ~85ms (4096样本) | ~40ms (1920样本) |
| **有效范围** | 0-15 cm | 30-150 mm |
| **优势** | 快速、稳定、绝对位置 | 高精度、相对变化 |
| **劣势** | 中距离盲区(5-10cm) | 累积漂移、需重置 |

## ⚙️ 融合策略

### 状态机
```
FAR (远离 >10cm)
 │
 ├─→ 能量 ≥1.5 → VERY_NEAR
 └─→ 能量 ≥1.1 且 距离<-50mm → NEAR
 
NEAR (接近 5-10cm)
 │
 ├─→ 能量 ≥1.5 → VERY_NEAR
 ├─→ 能量 <1.1 且 距离>30mm → FAR
 └─→ 保持
 
VERY_NEAR (极近 <5cm)
 │
 └─→ 能量 <1.5 → NEAR
```

### 重置条件
1. **状态转换重置**: 任何状态变化时重置相对距离
   - 目的: 防止跨状态累积误差
   
2. **10秒定时重置**: 每个状态停留10秒触发
   - 目的: 防止长时间漂移

## 📊 参数配置

### 音频参数 (Kotlin & C++ 一致)
```kotlin
采样率: 48000 Hz
起始频率: 20000 Hz
频率间隔: 350 Hz
频率数量: 10
频率范围: 20000-23150 Hz
```

### 缓冲区大小差异
```
Kotlin (能量法): 4096 samples (~85ms)
C++ (相位法):   1920 samples (~40ms)
```
**解决方案**: Kotlin实现自适应处理,可处理任意长度

### 阈值参数
```kotlin
// 能量阈值
VERY_NEAR_RATIO = 1.5f
NEAR_RATIO = 1.1f

// 距离阈值
NEAR_TO_FAR_DISTANCE = 30f   // mm
FAR_TO_NEAR_DISTANCE = -50f  // mm

// 时间阈值
STATE_TIMEOUT = 10_000L  // ms
```

## 🧮 相位法核心算法

### 1. I/Q 解调
```kotlin
// 正交参考信号
I(t) = cos(2πft)
Q(t) = -sin(2πft)

// 混频
baseband_I = signal × I(t)
baseband_Q = signal × Q(t)
```

### 2. CIC 抽取滤波器
```
输入: 48000 Hz
抽取倍数: 16
输出: 3000 Hz
```
简化实现: 每16个样本平均

### 3. DC 去除 (Levd算法)
```kotlin
// 估计DC分量
DC_real = (max_real + min_real) / 2
DC_imag = (max_imag + min_imag) / 2

// 去除
baseband_I -= DC_real
baseband_Q -= DC_imag
```

### 4. 相位解缠 + 距离计算
```kotlin
// 相位计算
phase = atan2(Q, I)

// 相位解缠 (防止2π跳变)
while (phase[i] - phase[i-1] > π) {
    phase[i] -= 2π
}

// 转换为距离
distance = phase × wavelength / (2π)

// 线性回归求变化量
Δd = linear_regression(distance)
```

## 🏗️ 代码结构

### 新增文件
```
app/src/main/java/com/example/myapplication/
├── PhaseRangeFinder.kt          # 相位测距器 (LLAP算法Kotlin实现)
└── FusedProximityDetector.kt    # 融合检测器 (状态机+重置逻辑)
```

### 修改文件
```
app/src/main/java/com/example/myapplication/
└── MainActivity.kt               # 集成融合检测器
    ├── initComponents()          # 初始化融合检测器
    └── updateFusedStateUI()      # 显示融合状态
```

## 🧪 测试方法

### 1. 编译运行
```bash
# 构建APK
./gradlew assembleDebug

# 安装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 查看日志
adb logcat | grep -E "FusedProximityDetector|PhaseRangeFinder"
```

### 2. 导出WAV分析
```bash
# 导出录音文件
adb pull /sdcard/proximity_test.wav

# 分析融合效果
python test_fused_detection.py proximity_test.wav
```

### 3. 验证重置逻辑
观察日志中的重置事件:
```
[FusedProximityDetector] 重置相对距离 (原因: 状态转换, 当前值=45.2 mm)
[FusedProximityDetector] 重置相对距离 (原因: 10秒定时, 当前值=12.8 mm)
```

## 📈 预期效果

### 能量法 (原始)
- ✅ 0-4cm: 准确检测 VERY_NEAR
- ⚠️ 5-10cm: 盲区(误判为 FAR)
- ✅ >10cm: 准确检测 FAR

### 融合法 (新)
- ✅ 0-4cm: 能量法主导,快速响应
- ✅ 5-10cm: 相位法验证,减少误判
  - NEAR→FAR需要: 能量<1.1 **且** 距离>30mm
  - FAR→NEAR需要: 能量≥1.1 **且** 距离<-50mm
- ✅ >10cm: 能量法主导

### 关键改进
1. **减少误判**: 双重验证防止单一信号波动
2. **防止漂移**: 状态转换和10秒定时重置
3. **精确跟踪**: 显示毫米级相对距离变化

## 🎯 使用场景

### 场景1: 手慢慢靠近
```
FAR (能量=0.8, 距离=0mm)
  ↓ 手靠近1cm (能量=1.0, 距离=-20mm)
  ↓ 手再靠近3cm (能量=1.2, 距离=-70mm) ✓ 距离<-50mm
NEAR (重置, 距离=0mm)
  ↓ 手继续靠近 (能量=1.6)
VERY_NEAR
```

### 场景2: 手在6cm处停留
```
NEAR (能量=1.15, 距离=0mm)
  ↓ 10秒后...
NEAR (重置, 距离=0mm) [防止漂移]
```

### 场景3: 手从7cm快速远离
```
NEAR (能量=1.12, 距离=0mm)
  ↓ 手远离5cm (能量=0.9, 距离=+60mm) ✓ 能量<1.1且距离>30mm
FAR (重置, 距离=0mm)
```

## 🔧 调试技巧

### 查看实时状态
观察 `tvState` 显示:
```
检测状态: 接近 (5-10cm)
能量比: 1.23
相对距离: 15.4 mm
状态时间: 3.2 秒
```

### 关键日志
```
[FusedProximityDetector] 状态变化: FAR → NEAR (能量=1.18, 距离=-52.3 mm)
[FusedProximityDetector] NEAR→FAR 验证: 能量=1.05, 距离=35.8 mm
[PhaseRangeFinder] 距离变化: -2.3 mm
```

## 🚀 下一步优化

1. **动态阈值**: 根据环境自适应调整能量阈值
2. **多帧融合**: 使用卡尔曼滤波器融合历史数据
3. **手势识别**: 利用相对距离变化识别挥手/点击
4. **功耗优化**: 根据状态调整采样率

## 📚 参考资料

- LLAP论文: "Low-Latency Acoustic Positioning"
- Goertzel算法: DTMF音频解码经典算法
- 相位解缠: Branch-cut算法
- CIC滤波器: Cascaded Integrator-Comb抽取滤波
