"""
融合接近检测器
结合能量法(快速状态分类)和相位法(精确相对距离测量)
"""
import numpy as np
import time
from dataclasses import dataclass
from enum import Enum
from typing import Optional, Callable
from .proximity_detector import ProximityDetector
from .phase_range_finder import PhaseRangeFinder
from .signal_processor import SignalProcessor


@dataclass
class LlapFrameMetrics:
	timestamp_ms: int
	volume: int
	energy_ratio: float
	relative_distance_mm: float
	phase_velocity_mm_per_frame: float
	distance_delta_mm: float
	power_threshold: float
	avg_power: float
	min_power: float
	max_power: float
	power_pass_count: int
	first_regression_freq_count: int
	second_regression_freq_count: int

class ProximityState(Enum):
	FAR = 0
	VERY_NEAR = 1
	PENDING = 2

cnt = 0
total = 0
class FusedDetector:
	TAG: str = "FusedProximityDetector"

	VERY_NEAR_RATIO: float = 1.25
	LOW_ENERGY_RATIO: float = 0.88
	FAR_DISTANCE_THRESHOLD: float = 50.0
	LOW_ENERGY_DURATION: float = 1000.0
	DISTANCE_CHANGE_THRESHOLD: float = 0.01
	SPEED_ADJ: float = 1.3
	UI_UPDATE_INTERVAL: float = 10.0
	STATE_TIMEOUT: float = 10000.0
	STATIONARY_VELOCITY_THRESHOLD: float = 0.3
	LEAVING_VELOCITY_THRESHOLD: float = 0.1

	def __init__(self, energy_detector: ProximityDetector,
				phase_range_finder: PhaseRangeFinder):
		self._energy_detector = energy_detector
		self._phase_range_finder = phase_range_finder

		self._current_state: ProximityState = ProximityState.FAR
		self._relative_distance: float = 0.0
		self._state_start_time: float = time.time() * 1000
		self._last_ui_update_time: float = 0.0

		self._low_energy_start_time: float = 0.0
		self._is_low_energy: bool = False

		self._latest_phase_velocity: float = 0.0

		self._energy_buffer: np.ndarray = np.zeros(4096, dtype=np.int16)
		self._energy_buffer_pos: int = 0
		self._phase_buffer: np.ndarray = np.zeros(960, dtype=np.int16)
		self._phase_buffer_pos: int = 0

		self._frame_count: int = 0

		self.on_state_changed: Optional[Callable[[ProximityState, float], None]] = None
		self.on_data_update: Optional[Callable[[ProximityState, float, float, int], None]] = None
		self.on_relative_distance_reset: Optional[Callable[[float], None]] = None
		self.on_llap_frame_metrics: Optional[Callable[[LlapFrameMetrics], None]] = None

	def _get_very_near_threshold(self, volume: int) -> float:
		if volume == 1:
			return 1.9
		elif volume == 2:
			return 1.9
		elif volume == 3:
			return 1.8
		elif volume == 4:
			return 1.6
		else:
			return 1.3

	def process_audio_data(self, current_volume: int,
						filtered_data: np.ndarray,
						raw_data: Optional[np.ndarray] = None) -> float:
		"""
		处理音频数据（统一入口）

		Args:
			current_volume: 当前音量
			filtered_data: 高通滤波后的数据（用于能量法）
			raw_data: 原始音频数据（用于相位法，保留更多能量）

		Returns:
			当前能量比
		"""
		if raw_data is None:
			raw_data = filtered_data
		# NOTE: 平均耗时约为 5ms
		global cnt, total
		# 处理能量法
		energy_ratio = self._energy_detector.get_current_ratio()

		energy_offset = 0
		while energy_offset < len(filtered_data):
			energy_space = len(self._energy_buffer) - self._energy_buffer_pos
			energy_copy_size = min(len(filtered_data) - energy_offset, energy_space)
			self._energy_buffer[self._energy_buffer_pos:self._energy_buffer_pos + energy_copy_size] = \
				filtered_data[energy_offset:energy_offset + energy_copy_size]
			self._energy_buffer_pos += energy_copy_size
			energy_offset += energy_copy_size

			if self._energy_buffer_pos >= len(self._energy_buffer):
				self._energy_detector.process_audio_data(self._energy_buffer)	# NOTE: 平均耗时 <1ms, 基本无开销
				energy_ratio = self._energy_detector.get_current_ratio()
				self._energy_buffer_pos = 0

		# 处理相位法
		phase_offset = 0
		while phase_offset < len(raw_data):
			phase_space = len(self._phase_buffer) - self._phase_buffer_pos
			phase_copy_size = min(len(raw_data) - phase_offset, phase_space)
			self._phase_buffer[self._phase_buffer_pos:self._phase_buffer_pos + phase_copy_size] = \
				raw_data[phase_offset:phase_offset + phase_copy_size]
			self._phase_buffer_pos += phase_copy_size
			phase_offset += phase_copy_size

			if self._phase_buffer_pos >= len(self._phase_buffer):
				distance_change = self._phase_range_finder.process_frame(self._phase_buffer)	# NOTE: 平均耗时 1~2ms
				self._latest_phase_velocity = self._phase_range_finder.get_recent_velocity()

				# NOTE: 暂时无用, 并未记录 LLAP 指标
				snapshot = self._phase_range_finder.get_last_debug_snapshot()
				if snapshot:
					if self.on_llap_frame_metrics:
						self.on_llap_frame_metrics(
							LlapFrameMetrics(
								timestamp_ms=int(time.time() * 1000),
								volume=current_volume,
								energy_ratio=energy_ratio,
								relative_distance_mm=self._relative_distance,
								phase_velocity_mm_per_frame=self._latest_phase_velocity,
								distance_delta_mm=distance_change,
								power_threshold=snapshot.power_threshold,
								avg_power=snapshot.avg_power,
								min_power=snapshot.min_power,
								max_power=snapshot.max_power,
								power_pass_count=snapshot.power_pass_count,
								first_regression_freq_count=snapshot.first_regression_freq_count,
								second_regression_freq_count=snapshot.second_regression_freq_count
							)
						)

				if abs(distance_change) > self.DISTANCE_CHANGE_THRESHOLD:
					self._relative_distance += distance_change * self.SPEED_ADJ

					if self._relative_distance > 500.0:
						self._relative_distance = 500.0

				self._phase_buffer_pos = 0

		current_time = time.time() * 1000
		time_in_state = current_time - self._state_start_time

		self._frame_count += 1

		dynamic_threshold = self._get_very_near_threshold(current_volume)
		is_standing_wave = (self._current_state == ProximityState.VERY_NEAR and
						energy_ratio < dynamic_threshold and
						self._phase_range_finder.is_hand_stationary(self.STATIONARY_VELOCITY_THRESHOLD))

		if (energy_ratio >= dynamic_threshold and
			abs(energy_ratio - dynamic_threshold) < 0.05):
			if self._relative_distance != 0.0 and not is_standing_wave:
				if self.on_relative_distance_reset:
					self.on_relative_distance_reset(self._relative_distance)
				self._relative_distance = 0.0

		if energy_ratio < self.LOW_ENERGY_RATIO:
			if not self._is_low_energy:
				self._is_low_energy = True
				self._low_energy_start_time = current_time
		else:
			self._is_low_energy = False
			self._low_energy_start_time = 0.0

		low_energy_duration = current_time - self._low_energy_start_time if self._is_low_energy else 0.0
		new_state = self._determine_state(current_volume, energy_ratio,
										self._relative_distance, low_energy_duration)

		if new_state != self._current_state:
			self._current_state = new_state
			self._state_start_time = current_time
			if self.on_state_changed:
				self.on_state_changed(self._current_state, energy_ratio)

		if self.on_data_update:
			self.on_data_update(self._current_state, energy_ratio,
							self._relative_distance, int(time_in_state))
		self._last_ui_update_time = current_time

		return energy_ratio

	def _determine_state(self, current_volume: int, energy_ratio: float,
						relative_distance: float, low_energy_duration: float) -> ProximityState:
		"""确定状态 (含LLAP速度守卫：驻波时禁止降级)"""
		dynamic_threshold = self._get_very_near_threshold(current_volume)

		is_standing_wave = (self._current_state == ProximityState.VERY_NEAR and
						energy_ratio < dynamic_threshold and
						self._phase_range_finder.is_hand_stationary(self.STATIONARY_VELOCITY_THRESHOLD))

		is_leaving_by_phase = self._latest_phase_velocity > self.LEAVING_VELOCITY_THRESHOLD

		if energy_ratio >= dynamic_threshold:
			return ProximityState.VERY_NEAR

		if is_standing_wave:
			return ProximityState.VERY_NEAR

		if (energy_ratio < dynamic_threshold and
			relative_distance > self.FAR_DISTANCE_THRESHOLD and
			is_leaving_by_phase):
			return ProximityState.FAR

		if energy_ratio < dynamic_threshold and relative_distance > self.FAR_DISTANCE_THRESHOLD:
			return ProximityState.PENDING

		if low_energy_duration >= self.LOW_ENERGY_DURATION and is_leaving_by_phase:
			return ProximityState.FAR

		if (low_energy_duration >= self.LOW_ENERGY_DURATION and
			self._phase_range_finder.is_hand_stationary(self.STATIONARY_VELOCITY_THRESHOLD) and
			self._current_state == ProximityState.VERY_NEAR):
			return ProximityState.VERY_NEAR

		if low_energy_duration >= self.LOW_ENERGY_DURATION:
			return ProximityState.FAR

		return ProximityState.PENDING

	def _reset_relative_distance(self, reason: str) -> None:
		"""重置相对距离（只在确认真实离开时调用，驻波期间不调用）"""
		self._relative_distance = 0.0
		self._latest_phase_velocity = 0.0
		self._phase_range_finder.reset()

	def get_current_energy_ratio(self) -> float:
		return self._energy_detector.get_current_energy_ratio()

	def get_current_state(self) -> ProximityState:
		return self._current_state

	def get_relative_distance(self) -> float:
		return self._relative_distance

	def get_time_in_state(self) -> int:
		return int(time.time() * 1000 - self._state_start_time)

	def reset(self) -> None:
		"""手动重置"""
		self._current_state = ProximityState.FAR
		self._reset_relative_distance("手动重置")
		self._state_start_time = time.time() * 1000

		self._energy_detector.reset()
		self._phase_range_finder.reset()
