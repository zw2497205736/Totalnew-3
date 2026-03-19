# 超声波接近检测 Android 应用

## 项目简介

这是一个基于超声波的手机接近检测应用，通过手机的扬声器和麦克风实现距离感知，无需额外硬件传感器。

## 核心原理

### 工作流程
```
听筒(扬声器) → 超声波(17.5-21kHz) → 反射面(耳朵/手/脸) → 麦克风接收
```

### 检测方法
- **基线校准**：记录无遮挡时的信号幅度作为基线
- **相对变化检测**：计算当前幅度相对于基线的变化率
- **状态判定**：根据变化率判断距离状态

### 距离状态
- 🔴 **非常近** (0-5cm)：贴近耳朵，幅度变化 >50% 或 <70%
- 🟡 **接近** (5-10cm)：手靠近，幅度变化 >20% 或 <85%
- 🟢 **远离** (>10cm)：正常状态，幅度接近基线

## 技术特点

### 1. 多频率扫描
- 使用 10 个频率点 (17.5kHz - 21kHz)
- 提高检测鲁棒性
- 避免单频干扰

### 2. Goertzel 算法
- 高效的单频检测算法
- 比完整 FFT 更快
- 实时性能优秀

### 3. 自适应滤波
- 滑动平均滤波减少抖动
- 状态切换防抖逻辑
- 避免误触发

### 4. 基线校准
- 解决直达声干扰问题
- 适应不同手机硬件
- 自动环境适应

## 项目结构

```
app/src/main/java/com/example/myapplication/
├── MainActivity.kt              # 主界面，UI 控制
├── UltrasonicGenerator.kt      # 超声波信号发射器
├── UltrasonicRecorder.kt       # 音频录音器
├── SignalProcessor.kt          # 信号处理（Goertzel、滤波）
└── ProximityDetector.kt        # 接近检测核心逻辑

app/src/main/res/
└── layout/
    └── activity_main.xml        # 主界面布局
```

## 使用说明

### 1. 权限要求
- ✅ 录音权限 (RECORD_AUDIO)
- ✅ 音频设置权限 (MODIFY_AUDIO_SETTINGS)

### 2. 使用步骤

#### 步骤 1：开始检测
点击 **"开始检测"** 按钮
- 扬声器开始播放超声波（人耳不可闻）
- 麦克风开始录音

#### 步骤 2：校准
点击 **"校准"** 按钮
- 将手机放在空旷处
- 不要遮挡扬声器和麦克风
- 等待校准完成（约 2 秒）

#### 步骤 3：测试
- 尝试用手靠近手机
- 尝试遮挡麦克风
- 观察状态变化

#### 步骤 4：停止
点击 **"停止检测"** 按钮

## 注意事项

### ⚠️ 硬件限制
- 不同手机的扬声器/麦克风性能差异很大
- 部分手机可能无法达到理想效果
- 建议在相对安静的环境测试

### ⚠️ 有效距离
- 实际有效距离可能 < 10cm
- 远距离检测可能不稳定
- 主要用于"贴近"/"离开"的二元判断

### ⚠️ 功耗问题
- 持续运行会消耗电池
- 建议按需使用
- 暂停应用时自动停止

## 可能的应用场景

1. **通话场景**：贴近耳朵自动关闭屏幕，远离自动点亮
2. **手势控制**：手掌挥动控制音乐播放
3. **接近检测**：检测物体接近触发动作
4. **辅助测距**：简单的距离估计工具

## 技术优化方向

### 已实现
- ✅ 多频率检测
- ✅ Goertzel 算法优化
- ✅ 基线校准
- ✅ 滑动平均滤波
- ✅ 状态防抖

### 可改进
- 🔧 机器学习模型（提高精度）
- 🔧 温度补偿（声速修正）
- 🔧 多传感器融合（光线+陀螺仪）
- 🔧 自适应阈值调整

## 开发环境

- **Android Studio**: 2024+
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 36 (Android 16)
- **语言**: Kotlin
- **依赖**: AndroidX, Material Design

## 环境配置（Windows / macOS）

### 1) JDK 要求

- 建议统一使用 **JDK 17**（AGP 8.10.1 + Gradle 8.11.1）

### 2) SDK 本地配置

1. 复制模板：`local.properties.example` → `local.properties`
2. 只保留一行 `sdk.dir=...`，按你的系统填写

示例：

```properties
# Windows
sdk.dir=D\:\\SDK_Android

# macOS
# sdk.dir=/Users/<your-name>/Library/Android/sdk
```

> `local.properties` 为本地文件，不应提交到版本库。

### 3) 命令行快速切换 JDK

Windows (PowerShell):

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

macOS (zsh):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

## 编译运行

```bash
# 克隆项目
git clone <repository-url>

# 打开 Android Studio
# 选择 Open Project
# 等待 Gradle 同步完成

# 连接 Android 设备或启动模拟器
# 点击 Run 按钮
```

命令行构建：

```bash
# macOS / Linux
./gradlew clean assembleDebug

# Windows
./gradlew.bat clean assembleDebug
```

## 许可证

MIT License

## 作者

GitHub Copilot AI Assistant

## 版本历史

- **v1.0** (2025-11-04): 初始版本
  - 基础超声波发射和接收
  - 多频率检测
  - 基线校准
  - 三态判定（远/近/非常近）

---

**祝你使用愉快！如有问题，欢迎反馈。**
