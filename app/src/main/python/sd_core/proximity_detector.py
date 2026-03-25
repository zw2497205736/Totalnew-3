"""
接近检测器
核心检测逻辑：基线校准、相对变化检测、状态判定
"""
import numpy as np
import time
from enum import Enum
from typing import Optional, Callable, Dict
from .signal_processor import SignalProcessor


class ProximityState(Enum):
    UNKNOWN = 0
    FAR = 1
    NEAR = 2
    VERY_NEAR = 3

class ProximityDetector:
    TAG: str = "ProximityDetector"

    VOLUME_BASELINE_MAP: Dict[int, float] = {
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
        15: 20.330273
    }

    CALIBRATION_SAMPLES: int = 20
    VERY_NEAR_RATIO_HIGH: float = 1.5
    NEAR_RATIO_HIGH: float = 1.25
    FAR_RATIO_THRESHOLD: float = 1.0
    SMOOTHING_ALPHA: float = 0.2
    ENVELOPE_DECAY_RATE: float = 0.99
    ENVELOPE_MIN_RATIO: float = 1.0
    STATE_CHANGE_THRESHOLD: int = 4
    BASELINE_UPDATE_INTERVAL: float = 1000.0
    VOLUME_CHANGE_FREEZE_MS: float = 800.0

    def __init__(self):
        self._signal_processor = SignalProcessor()

        self._baseline_magnitude: float = 0.0
        self._is_calibrated: bool = False
        self._calibration_data: list = []
        self._last_baseline_update_time: float = 0.0

        self._current_state: ProximityState = ProximityState.UNKNOWN
        self._last_state: ProximityState = ProximityState.UNKNOWN
        self._state_counter: int = 0
        self._debug_counter: int = 0

        self._smoothed_magnitude: float = 0.0
        self._envelope_magnitude: float = 0.0

        self._last_volume: int = -1
        self._freeze_until_time: float = 0.0

        self._on_state_change_callback: Optional[Callable[[ProximityState], None]] = None
        self.on_calibration_complete: Optional[Callable[[float], None]] = None

        self._current_volume: int = 10
        self._max_volume: int = 15

    def _get_very_near_threshold(self, volume: int) -> float:
        if volume <= 2:
            return 2.0
        elif volume <= 4:
            return 1.8
        elif volume <= 6:
            return 1.6
        else:
            return 1.5

    def _get_near_threshold(self, volume: int) -> float:
        if volume <= 2:
            return 1.5
        elif volume <= 4:
            return 1.4
        elif volume <= 6:
            return 1.3
        else:
            return 1.25

    def set_on_state_change_callback(self, callback: Callable[[ProximityState], None]) -> None:
        self._on_state_change_callback = callback

    def set_volume(self, current_volume: int, max_volume: int) -> None:
        """设置当前音量（用于动态阈值计算）"""
        self._current_volume = current_volume
        self._max_volume = max_volume

    def start_calibration(self) -> None:
        """开始校准"""
        self._is_calibrated = False
        self._calibration_data.clear()
        self._baseline_magnitude = 0.0
        self._current_state = ProximityState.UNKNOWN

    def process_audio_data(self, audio_data: np.ndarray) -> None:
        """
        处理音频数据（校准或检测）

        Args:
            audio_data: 音频数据
        """
        magnitudes = self._signal_processor.extract_magnitudes(audio_data)					# NOTE: 大约 0~2ms, 基本无开销
        avg_magnitude = self._signal_processor.calculate_average_magnitude(magnitudes)		# NOTE: 大约 0ms, 无开销

        current_time = time.time() * 1000
        if self._is_calibrated and current_time - self._last_baseline_update_time >= self.BASELINE_UPDATE_INTERVAL:
            self._update_baseline_from_volume()
            self._last_baseline_update_time = current_time

        if not self._is_calibrated:
            self._calibration_data.append(avg_magnitude)

            if len(self._calibration_data) >= self.CALIBRATION_SAMPLES:
                sorted_data = sorted(self._calibration_data)
                trimmed_data = sorted_data[5:-5]
                self._baseline_magnitude = float(np.mean(trimmed_data))

                self._is_calibrated = True
                self._smoothed_magnitude = self._baseline_magnitude
                self._current_state = ProximityState.FAR

                if self.on_calibration_complete:
                    self.on_calibration_complete(self._baseline_magnitude)
                if self._on_state_change_callback:
                    self._on_state_change_callback(self._current_state)
        else:
            self._detect_proximity(avg_magnitude)

    def _detect_proximity(self, magnitude: float) -> None:
        """检测接近状态（使用Peak-Hold包络和动态阈值）"""
        current_time = time.time() * 1000

        baseline_from_map = self.VOLUME_BASELINE_MAP.get(self._current_volume, self._baseline_magnitude)

        if self._last_volume == -1:
            self._last_volume = self._current_volume
        elif self._current_volume != self._last_volume:
            self._last_volume = self._current_volume
            self._baseline_magnitude = baseline_from_map
            self._smoothed_magnitude = baseline_from_map
            self._envelope_magnitude = baseline_from_map
            self._state_counter = 0
            self._last_state = self._current_state
            self._freeze_until_time = current_time + self.VOLUME_CHANGE_FREEZE_MS

        if current_time < self._freeze_until_time:
            self._smoothed_magnitude = baseline_from_map
            self._envelope_magnitude = baseline_from_map
            self._debug_counter += 1
            return

        self._smoothed_magnitude = (self.SMOOTHING_ALPHA * magnitude +
                                    (1 - self.SMOOTHING_ALPHA) * self._smoothed_magnitude)

        if magnitude > self._envelope_magnitude:
            self._envelope_magnitude = magnitude
        else:
            self._envelope_magnitude *= self.ENVELOPE_DECAY_RATE

        effective_envelope = max(self._envelope_magnitude, baseline_from_map)
        ratio = effective_envelope / baseline_from_map

        very_near_threshold = self._get_very_near_threshold(self._current_volume)
        near_threshold = self._get_near_threshold(self._current_volume)

        self._debug_counter += 1

        if ratio >= very_near_threshold:
            detected_state = ProximityState.VERY_NEAR
        elif ratio >= near_threshold:
            detected_state = ProximityState.NEAR
        else:
            detected_state = ProximityState.FAR

        if detected_state == self._last_state:
            self._state_counter += 1

            if (self._state_counter >= self.STATE_CHANGE_THRESHOLD and
                detected_state != self._current_state):
                self._current_state = detected_state
                if self._on_state_change_callback:
                    self._on_state_change_callback(self._current_state)
                self._state_counter = 0
        else:
            self._last_state = detected_state
            self._state_counter = 0

    def _update_baseline_from_volume(self) -> None:
        """根据当前音量更新基线"""
        new_baseline = self.VOLUME_BASELINE_MAP.get(self._current_volume)

        if new_baseline is not None and new_baseline != self._baseline_magnitude:
            self._baseline_magnitude = new_baseline

    def get_current_state(self) -> ProximityState:
        return self._current_state

    def get_current_ratio(self) -> float:
        if self._is_calibrated and self._baseline_magnitude > 0:
            return self._smoothed_magnitude / self._baseline_magnitude
        return 0.0

    def is_calibrated(self) -> bool:
        return self._is_calibrated

    def get_calibration_progress(self) -> float:
        if self._is_calibrated:
            return 1.0
        return len(self._calibration_data) / self.CALIBRATION_SAMPLES

    def reset(self) -> None:
        """重置检测器"""
        self._is_calibrated = False
        self._calibration_data.clear()
        self._baseline_magnitude = 0.0
        self._smoothed_magnitude = 0.0
        self._envelope_magnitude = 0.0
        self._last_volume = -1
        self._freeze_until_time = 0.0
        self._current_state = ProximityState.UNKNOWN
        self._last_state = ProximityState.UNKNOWN
        self._state_counter = 0
