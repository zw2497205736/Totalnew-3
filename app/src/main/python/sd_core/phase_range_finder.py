"""
相位测距器 (LLAP 算法的 Python 实现)
基于多频率相位差测量相对距离变化
"""
import numpy as np
import math
from dataclasses import dataclass
from typing import Optional, Tuple


@dataclass
class DebugSnapshot:
	frame_index: int
	power_threshold: float
	avg_power: float
	min_power: float
	max_power: float
	power_pass_count: int
	first_regression_freq_count: int
	second_regression_freq_count: int
	distance_delta_mm: float


class PhaseRangeFinder:
	TAG: str = "PhaseRangeFinder"
	SAMPLE_RATE: int = 48000
	FRAME_SIZE: int = 960
	NUM_FREQUENCIES: int = 10
	START_FREQ: float = 20000.0
	FREQ_STEP: float = 350.0
	TEMPERATURE: float = 20.0
	CIC_DEC: int = 16
	CIC_SEC: int = 4
	CIC_DELAY: int = 17
	CIC_STAGE0_GAIN: float = 1.0
	POWER_THR: float = 15000.0
	PEAK_THR: float = 220.0
	DC_TREND: float = 0.25
	VELOCITY_WINDOW_SIZE: int = 5

	def __init__(self):
		self._sound_speed: float = 331.3 + 0.606 * self.TEMPERATURE

		self._frequencies: np.ndarray = np.array(
			[self.START_FREQ + i * self.FREQ_STEP for i in range(self.NUM_FREQUENCIES)],
			dtype=np.float32
		)
		self._wavelengths: np.ndarray = np.array(
			[self._sound_speed / freq * 1000 for freq in self._frequencies],
			dtype=np.float32
		)

		self._cos_buffer: np.ndarray = np.zeros((self.NUM_FREQUENCIES, self.FRAME_SIZE), dtype=np.float32)
		self._sin_buffer: np.ndarray = np.zeros((self.NUM_FREQUENCIES, self.FRAME_SIZE), dtype=np.float32)
		for i in range(self.NUM_FREQUENCIES):
			for n in range(self.FRAME_SIZE):
				self._cos_buffer[i, n] = math.cos(2.0 * math.pi * n / self.SAMPLE_RATE * self._frequencies[i])
				self._sin_buffer[i, n] = -math.sin(2.0 * math.pi * n / self.SAMPLE_RATE * self._frequencies[i])

		dec_size = self.FRAME_SIZE // self.CIC_DEC
		self._base_band_real: np.ndarray = np.zeros((self.NUM_FREQUENCIES, dec_size), dtype=np.float32)
		self._base_band_imag: np.ndarray = np.zeros((self.NUM_FREQUENCIES, dec_size), dtype=np.float32)

		self._cic_buffer: np.ndarray = np.zeros(
			(self.NUM_FREQUENCIES, self.CIC_SEC, 2, dec_size + self.CIC_DELAY),
			dtype=np.float32
		)

		self._temp_buffer: np.ndarray = np.zeros(self.FRAME_SIZE, dtype=np.float32)

		self._audio_buffer: np.ndarray = np.zeros(self.FRAME_SIZE, dtype=np.int16)
		self._audio_buffer_pos: int = 0

		self._dc_value_real: np.ndarray = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)
		self._dc_value_imag: np.ndarray = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)
		self._max_value_real: np.ndarray = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)
		self._min_value_real: np.ndarray = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)
		self._max_value_imag: np.ndarray = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)
		self._min_value_imag: np.ndarray = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)

		self._frame_count: int = 0

		self._recent_deltas: np.ndarray = np.zeros(self.VELOCITY_WINDOW_SIZE, dtype=np.float32)
		self._velocity_window_pos: int = 0
		self._velocity_window_filled: bool = False

		self._last_debug_snapshot: Optional[DebugSnapshot] = None

	def process_frame(self, frame: np.ndarray) -> float:
		"""
		处理单帧数据 (固定960样本)

		Args:
			frame: 960个样本的音频数据

		Returns:
			距离变化量 (mm)
		"""
		if len(frame) != self.FRAME_SIZE:
			print(f"{self.TAG}: 帧大小错误: {len(frame)}, 期望: {self.FRAME_SIZE}")
			return 0.0

		max_amp = np.max(frame)
		min_amp = np.min(frame)

		if max_amp == 0 and min_amp == 0:
			print(f"{self.TAG}: 警告: 音频数据全为0！")
			return 0.0

		self._extract_baseband(frame)			# NOTE: 大约 0~2ms
		self._remove_dc()						# NOTE: 大约 0~2ms
		distance = self._calculate_distance()	# NOTE: 大约 0~2ms
		self._frame_count += 1

		self._recent_deltas[self._velocity_window_pos] = distance
		self._velocity_window_pos = (self._velocity_window_pos + 1) % self.VELOCITY_WINDOW_SIZE
		if self._velocity_window_pos == 0:
			self._velocity_window_filled = True

		return distance

	def get_recent_velocity(self) -> float:
		"""
		获取近期平均速度 (mm/帧)
		正值表示远离，负值表示靠近；绝对值接近0表示手静止（可能是驻波）
		"""
		count = self.VELOCITY_WINDOW_SIZE if self._velocity_window_filled else self._velocity_window_pos
		if count == 0:
			return 0.0
		return float(np.sum(self._recent_deltas[:count]) / count)

	def is_hand_stationary(self, stationary_threshold: float = 0.3) -> bool:
		"""
		判断当前是否处于驻波状态（手静止但能量异常）
		条件：近期速度绝对值 < 静止速度阈值
		"""
		return abs(self.get_recent_velocity()) < stationary_threshold

	def get_last_debug_snapshot(self) -> Optional[DebugSnapshot]:
		return self._last_debug_snapshot

	def _sliding_window_sum(self, input_arr: np.ndarray, output: np.ndarray,
						size: int, window_size: int) -> None:
		"""滑动窗口求和（使用前缀和优化）"""
		prefix_sum = np.cumsum(input_arr[:size + window_size - 1])
		output[:size] = prefix_sum[window_size - 1:size + window_size - 1] - np.concatenate([[0], prefix_sum[:size - 1]])

	def _sliding_window_sum_with_offset(self, input_arr: np.ndarray, output: np.ndarray,
								size: int, window_size: int) -> None:
		"""带偏移的滑动窗口求和（使用前缀和优化）"""
		prefix_sum = np.cumsum(input_arr[:size + window_size - 1])
		sums = prefix_sum[window_size - 1:size + window_size - 1] - np.concatenate([[0], prefix_sum[:size - 1]])
		output[self.CIC_DELAY:self.CIC_DELAY + size] = sums

	def _extract_baseband(self, audio_data: np.ndarray) -> None:
		"""基带信号提取 (完全等效于C++的GetBaseBand)"""
		dec_size = self.FRAME_SIZE // self.CIC_DEC

		normalized_data = audio_data.astype(np.float32) / 32767.0

		for f in range(self.NUM_FREQUENCIES):
			# I/Q 解调 - Real channel (I): 乘以cos
			self._temp_buffer[:len(normalized_data)] = normalized_data * self._cos_buffer[f, :len(normalized_data)]

			np.copyto(self._cic_buffer[f, 0, 0, :self.CIC_DELAY],
					self._cic_buffer[f, 0, 0, dec_size:dec_size + self.CIC_DELAY])

			# Stage 0: 抽取 - 完全向量化
			chunked = self._temp_buffer[:self.FRAME_SIZE].reshape(-1, self.CIC_DEC)
			sums = np.sum(chunked, axis=1) * self.CIC_STAGE0_GAIN
			self._cic_buffer[f, 0, 0, self.CIC_DELAY:self.CIC_DELAY + dec_size] = sums

			np.copyto(self._cic_buffer[f, 1, 0, :self.CIC_DELAY],
					self._cic_buffer[f, 1, 0, dec_size:dec_size + self.CIC_DELAY])
			self._sliding_window_sum_with_offset(self._cic_buffer[f, 0, 0],
										self._cic_buffer[f, 1, 0], dec_size, self.CIC_DELAY)

			np.copyto(self._cic_buffer[f, 2, 0, :self.CIC_DELAY],
					self._cic_buffer[f, 2, 0, dec_size:dec_size + self.CIC_DELAY])
			self._sliding_window_sum_with_offset(self._cic_buffer[f, 1, 0],
										self._cic_buffer[f, 2, 0], dec_size, self.CIC_DELAY)

			np.copyto(self._cic_buffer[f, 3, 0, :self.CIC_DELAY],
					self._cic_buffer[f, 3, 0, dec_size:dec_size + self.CIC_DELAY])
			self._sliding_window_sum_with_offset(self._cic_buffer[f, 2, 0],
										self._cic_buffer[f, 3, 0], dec_size, self.CIC_DELAY)

			self._sliding_window_sum(self._cic_buffer[f, 3, 0], self._base_band_real[f], dec_size, self.CIC_DELAY)

			# I/Q 解调 - Imag channel (Q): 乘以-sin
			self._temp_buffer[:len(normalized_data)] = normalized_data * self._sin_buffer[f, :len(normalized_data)]

			np.copyto(self._cic_buffer[f, 0, 1, :self.CIC_DELAY],
					self._cic_buffer[f, 0, 1, dec_size:dec_size + self.CIC_DELAY])

			# Stage 0: 抽取 - 完全向量化
			chunked = self._temp_buffer[:self.FRAME_SIZE].reshape(-1, self.CIC_DEC)
			sums = np.sum(chunked, axis=1) * self.CIC_STAGE0_GAIN
			self._cic_buffer[f, 0, 1, self.CIC_DELAY:self.CIC_DELAY + dec_size] = sums

			np.copyto(self._cic_buffer[f, 1, 1, :self.CIC_DELAY],
					self._cic_buffer[f, 1, 1, dec_size:dec_size + self.CIC_DELAY])
			self._sliding_window_sum_with_offset(self._cic_buffer[f, 0, 1],
										self._cic_buffer[f, 1, 1], dec_size, self.CIC_DELAY)

			np.copyto(self._cic_buffer[f, 2, 1, :self.CIC_DELAY],
					self._cic_buffer[f, 2, 1, dec_size:dec_size + self.CIC_DELAY])
			self._sliding_window_sum_with_offset(self._cic_buffer[f, 1, 1],
										self._cic_buffer[f, 2, 1], dec_size, self.CIC_DELAY)

			np.copyto(self._cic_buffer[f, 3, 1, :self.CIC_DELAY],
					self._cic_buffer[f, 3, 1, dec_size:dec_size + self.CIC_DELAY])
			self._sliding_window_sum_with_offset(self._cic_buffer[f, 2, 1],
										self._cic_buffer[f, 3, 1], dec_size, self.CIC_DELAY)

			self._sliding_window_sum(self._cic_buffer[f, 3, 1], self._base_band_imag[f], dec_size, self.CIC_DELAY)

	def _remove_dc(self) -> None:
		"""去除 DC 分量 (Levd 算法 - 完全等效C++)"""
		dec_size = self.FRAME_SIZE // self.CIC_DEC

		for f in range(self.NUM_FREQUENCIES):
			vsum = 0.0
			dsum = 0.0

			max_r = float(np.max(self._base_band_real[f]))
			min_r = float(np.min(self._base_band_real[f]))

			temp_val = -self._base_band_real[f, 0]
			temp = self._base_band_real[f] + temp_val
			temp_sum = np.sum(temp)
			temp_sum2 = np.sum(temp ** 2)
			dsum += abs(temp_sum / dec_size)
			vsum += abs(temp_sum2 / dec_size)

			max_i = float(np.max(self._base_band_imag[f]))
			min_i = float(np.min(self._base_band_imag[f]))

			temp_val_i = -self._base_band_imag[f, 0]
			temp = self._base_band_imag[f] + temp_val_i
			temp_sum = np.sum(temp)
			temp_sum2 = np.sum(temp ** 2)
			dsum += abs(temp_sum / dec_size)
			vsum += abs(temp_sum2 / dec_size)

			power = vsum + dsum * dsum

			if power > self.POWER_THR:
				if (max_r > self._max_value_real[f] or
					(max_r > self._min_value_real[f] + self.PEAK_THR and
					 (self._max_value_real[f] - self._min_value_real[f]) > self.PEAK_THR * 4)):
					self._max_value_real[f] = max_r
				if (min_r < self._min_value_real[f] or
					(min_r < self._max_value_real[f] - self.PEAK_THR and
					 (self._max_value_real[f] - self._min_value_real[f]) > self.PEAK_THR * 4)):
					self._min_value_real[f] = min_r

				if (max_i > self._max_value_imag[f] or
					(max_i > self._min_value_imag[f] + self.PEAK_THR and
					 (self._max_value_imag[f] - self._min_value_imag[f]) > self.PEAK_THR * 4)):
					self._max_value_imag[f] = max_i
				if (min_i < self._min_value_imag[f] or
					(min_i < self._max_value_imag[f] - self.PEAK_THR and
					 (self._max_value_imag[f] - self._min_value_imag[f]) > self.PEAK_THR * 4)):
					self._min_value_imag[f] = min_i

				if ((self._max_value_real[f] - self._min_value_real[f]) > self.PEAK_THR and
					(self._max_value_imag[f] - self._min_value_imag[f]) > self.PEAK_THR):
					self._dc_value_real[f] = ((1.0 - self.DC_TREND) * self._dc_value_real[f] +
												(self._min_value_real[f] + self._max_value_real[f]) / 2.0 * self.DC_TREND)
					self._dc_value_imag[f] = ((1.0 - self.DC_TREND) * self._dc_value_imag[f] +
												(self._min_value_imag[f] + self._max_value_imag[f]) / 2.0 * self.DC_TREND)

			# 向量化去除 DC
			self._base_band_real[f] -= self._dc_value_real[f]
			self._base_band_imag[f] -= self._dc_value_imag[f]

	def _calculate_distance(self) -> float:
		"""计算距离变化 (相位解缠 + 线性回归 + 方差过滤)"""
		dec_size = self.FRAME_SIZE // self.CIC_DEC
		phase_data = np.zeros((self.NUM_FREQUENCIES, dec_size), dtype=np.float32)
		ignore_freq = np.zeros(self.NUM_FREQUENCIES, dtype=bool)
		powers = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)
		power_pass_count = 0

		# 向量化计算功率
		powers = (np.sum(self._base_band_real ** 2 + self._base_band_imag ** 2, axis=1) / dec_size)

		# 预计算索引数组
		indices = np.arange(dec_size)

		for f in range(self.NUM_FREQUENCIES):
			if powers[f] > self.POWER_THR:
				power_pass_count += 1
				# 向量化计算相位
				phase_data[f] = np.arctan2(self._base_band_imag[f], self._base_band_real[f])

				# 相位解缠
				phase_diff = np.diff(phase_data[f])
				phase_diff[phase_diff > np.pi] -= 2 * np.pi
				phase_diff[phase_diff < -np.pi] += 2 * np.pi
				phase_data[f, 1:] = phase_data[f, 0] + np.cumsum(phase_diff)

				if abs(phase_data[f, -1] - phase_data[f, 0]) > np.pi / 4.0:
					self._dc_value_real[f] = ((1.0 - 0.5) * self._dc_value_real[f] +
												(self._min_value_real[f] + self._max_value_real[f]) / 2.0 * 0.5)
					self._dc_value_imag[f] = ((1.0 - 0.5) * self._dc_value_imag[f] +
												(self._min_value_imag[f] + self._max_value_imag[f]) / 2.0 * 0.5)

				# 去除起始相位
				start_phase = phase_data[f, 0]
				phase_data[f] -= start_phase

				# 转换为距离
				scale = 2.0 * np.pi / self._wavelengths[f]
				phase_data[f] /= scale
			else:
				ignore_freq[f] = True

		# 第一次线性回归
		valid_freqs = ~ignore_freq
		if not np.any(valid_freqs):
			self._last_debug_snapshot = DebugSnapshot(
				frame_index=self._frame_count + 1,
				power_threshold=self.POWER_THR,
				avg_power=float(np.mean(powers)),
				min_power=float(np.min(powers)),
				max_power=float(np.max(powers)),
				power_pass_count=power_pass_count,
				first_regression_freq_count=0,
				second_regression_freq_count=0,
				distance_delta_mm=0.0
			)
			return 0.0

		first_regression_freq_count = np.sum(valid_freqs)

		# 向量化计算sum_xy和sum_y
		valid_phase_data = phase_data[valid_freqs]
		sum_xy = np.sum(indices * valid_phase_data)
		sum_y = np.sum(valid_phase_data)

		delta_x = (self.NUM_FREQUENCIES *
				((dec_size - 1) * dec_size * (2 * dec_size - 1) / 6.0 -
					(dec_size - 1) * dec_size * (dec_size - 1) / 4.0))
		delta = (sum_xy - sum_y * (dec_size - 1) / 2.0) / delta_x * self.NUM_FREQUENCIES / first_regression_freq_count

		# 方差过滤
		var_val = np.zeros(self.NUM_FREQUENCIES, dtype=np.float32)
		for f in range(self.NUM_FREQUENCIES):
			if not ignore_freq[f]:
				diff = phase_data[f] - indices * delta
				var_val[f] = np.sum(diff ** 2)

		var_sum = np.sum(var_val[~ignore_freq]) / first_regression_freq_count

		# 过滤方差过大的频率
		for f in range(self.NUM_FREQUENCIES):
			if not ignore_freq[f] and var_val[f] > var_sum:
				ignore_freq[f] = True

		# 第二次线性回归
		valid_freqs = ~ignore_freq
		if not np.any(valid_freqs):
			self._last_debug_snapshot = DebugSnapshot(
				frame_index=self._frame_count + 1,
				power_threshold=self.POWER_THR,
				avg_power=float(np.mean(powers)),
				min_power=float(np.min(powers)),
				max_power=float(np.max(powers)),
				power_pass_count=power_pass_count,
				first_regression_freq_count=first_regression_freq_count,
				second_regression_freq_count=0,
				distance_delta_mm=0.0
			)
			return 0.0

		num_freq_used = np.sum(valid_freqs)
		valid_phase_data = phase_data[valid_freqs]
		sum_xy = np.sum(indices * valid_phase_data)
		sum_y = np.sum(valid_phase_data)

		delta = (sum_xy - sum_y * (dec_size - 1) / 2.0) / delta_x * self.NUM_FREQUENCIES / num_freq_used
		distance_delta = -delta * dec_size / 2.0

		self._last_debug_snapshot = DebugSnapshot(
			frame_index=self._frame_count + 1,
			power_threshold=self.POWER_THR,
			avg_power=float(np.mean(powers)),
			min_power=float(np.min(powers)),
			max_power=float(np.max(powers)),
			power_pass_count=power_pass_count,
			first_regression_freq_count=first_regression_freq_count,
			second_regression_freq_count=num_freq_used,
			distance_delta_mm=distance_delta
		)

		return distance_delta

	def reset(self) -> None:
		"""重置累积距离"""
		self._dc_value_real.fill(0.0)
		self._dc_value_imag.fill(0.0)
		self._max_value_real.fill(0.0)
		self._min_value_real.fill(0.0)
		self._max_value_imag.fill(0.0)
		self._min_value_imag.fill(0.0)
		self._recent_deltas.fill(0.0)
		self._velocity_window_pos = 0
		self._velocity_window_filled = False
		self._last_debug_snapshot = None
