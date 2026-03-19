#!/usr/bin/env zsh
set -euo pipefail

# scripts/tinycap-top-mic.sh
# 将原 Windows batch 脚本改为 macOS (zsh) 可执行脚本。
# 说明：
#  - 需要 adb 在 PATH 中，设备通过 USB 连接并已授权调试。
#  - 脚本尝试运行 `adb root`，若无效会继续，但某些 tinymix 写命令可能被拒绝。
#  - 你可以通过参数调整 device id (-d) 和录音时长 (-T)。

OUT_REMOTE=${OUT_REMOTE:-/sdcard/top-mic.wav}
OUT_LOCAL=${OUT_LOCAL:-./top-mic.wav}
DEVICE_ID=${DEVICE_ID:-13}
DURATION=${DURATION:-10}

function usage() {
  cat <<EOF
Usage: ${0##*/} [--device-id N] [--duration SEC] [--out-local PATH]

Options:
  --device-id N    tinycap device id (default: ${DEVICE_ID})
  --duration SEC   recording duration seconds (default: ${DURATION})
  --out-local PATH pull path for recorded wav (default: ${OUT_LOCAL})
  -h, --help       show this help
EOF
}

# parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    --device-id) DEVICE_ID=$2; shift 2;;
    --duration) DURATION=$2; shift 2;;
    --out-local) OUT_LOCAL=$2; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "Unknown arg: $1"; usage; exit 1;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "错误：adb 未找到，请将 Android Platform Tools 的路径加入 PATH。"
  exit 2
fi

echo "设备列表："
adb devices

echo "尝试以 root 运行 adbd（如果设备支持）..."
adb root || echo "adb root 未成功：继续，但写入 tinymix 可能被拒绝（需要 su 或 root 固件）"

echo "---- 关闭常见前端处理（若控件存在，将忽略不存在的控件错误） ----"
# 若控件不存在会报错，使用 || true 忽略单条失败以继续流程
adb shell "tinymix 'HPF Switch' 0" || true
adb shell "tinymix 'TX_IIR Enable' 0" || true
adb shell "tinymix 'NS Enable' 0" || true
adb shell "tinymix 'AEC Enable' 0" || true
adb shell "tinymix 'AGC Enable' 0" || true
adb shell "tinymix 'Speech Enhancement' 0" || true
adb shell "tinymix 'EC_REF_RX' 0" || true
adb shell "tinymix 'FLUENCE_ENABLE' 0" || true

echo "---- 设置顶部麦克风路由 ----"
adb shell "tinymix 'UL9_CH1 ADDA_UL_CH1' '1'"
adb shell "tinymix 'UL9_CH2 ADDA_UL_CH2' '1'"
adb shell "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '1'"
adb shell "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '1'"
adb shell "tinymix 'CM1_UL_MUX' 'CM1_16CH_PATH'"
adb shell "tinymix 'MISO0_MUX' 'UL1_CH2'"
adb shell "tinymix 'MISO1_MUX' 'UL1_CH2'"
adb shell "tinymix 'ADC_R_Mux' 'Right Preamplifier'"
adb shell "tinymix 'PGA_R_Mux' 'AIN2'"
adb shell "tinymix 'DMIC0_MUX' 'DMIC_DATA1_R'"
adb shell "tinymix 'DMIC1_MUX' 'DMIC_DATA1_R'"

echo
printf "已应用路由配置。请把手机放好/靠近顶部麦克风，按任意键继续开始录音..."
read -k 1
echo

echo "---- 开始录音 (tinycap) ----"
echo "远端文件: ${OUT_REMOTE}   device id: ${DEVICE_ID}   duration: ${DURATION}s"
adb shell tinycap ${OUT_REMOTE} -D 0 -d ${DEVICE_ID} -c 1 -r 48000 -b 16 -p 1024 -n 8 -T ${DURATION}

echo "---- 拉取录音到本地: ${OUT_LOCAL} ----"
adb pull ${OUT_REMOTE} ${OUT_LOCAL} || echo "adb pull 失败（文件可能不存在）"

echo "---- 回滚 tinymix 设置（恢复默认） ----"
adb shell "tinymix 'ADC_L_Mux' 'Idle'" || true
adb shell "tinymix 'ADC_R_Mux' 'Idle'" || true
adb shell "tinymix 'ADC_3_Mux' 'Idle'" || true
adb shell "tinymix 'PGA_L_Mux' 'None'" || true
adb shell "tinymix 'PGA_R_Mux' 'None'" || true
adb shell "tinymix 'PGA_3_Mux' 0" || true
adb shell "tinymix 'UL_SRC_MUX' 'AMIC'" || true
adb shell "tinymix 'UL2_SRC_MUX' 'AMIC'" || true
adb shell "tinymix 'UL9_CH1 ADDA_UL_CH1' '0'" || true
adb shell "tinymix 'UL9_CH2 ADDA_UL_CH2' '0'" || true
adb shell "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '0'" || true
adb shell "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '0'" || true
adb shell "tinymix 'CM1_UL_MUX' 'CM1_2CH_PATH'" || true

echo "完成：本地文件 ${OUT_LOCAL}（如存在）已保存。建议打开并播放或用音频分析工具查看。"

exit 0
