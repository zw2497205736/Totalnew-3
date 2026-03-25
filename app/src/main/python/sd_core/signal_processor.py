"""
信号处理器
FFT分析、幅度提取、滤波等
"""
import numpy as np
from typing import Tuple
import math

class SignalProcessor:
	SAMPLE_RATE: int = 48000
	NUM_FREQUENCIES: int = 10
	START_FREQ: int = 20000
	FREQ_STEP: int = 350

	_fc: float = 15000.0
	_omega: float = 2.0 * math.pi * _fc / SAMPLE_RATE
	_cos_omega: float = math.cos(_omega)
	_sin_omega: float = math.sin(_omega)
	_q: float = 0.7071
	_alpha: float = _sin_omega / (2.0 * _q)
	_b0: float = (1.0 + _cos_omega) / 2.0
	_b1: float = -(1.0 + _cos_omega)
	_b2: float = (1.0 + _cos_omega) / 2.0
	_a0: float = 1.0 + _alpha
	_a1: float = -2.0 * _cos_omega
	_a2: float = 1.0 - _alpha

	b0_norm: float = _b0 / _a0
	b1_norm: float = _b1 / _a0
	b2_norm: float = _b2 / _a0
	a1_norm: float = _a1 / _a0
	a2_norm: float = _a2 / _a0

	def __init__(self):
		self._hpf_x1: float = 0.0
		self._hpf_x2: float = 0.0
		self._hpf_y1: float = 0.0
		self._hpf_y2: float = 0.0
		self._debug_counter: int = 0

	def high_pass_filter(self, data: np.ndarray) -> np.ndarray:
		"""
		二阶巴特沃斯高通滤波器 - 去除人声和低频噪声
		截止频率: 15kHz (人声通常 < 4kHz)

		Args:
			data: 输入音频数据 (ShortArray)

		Returns:
			滤波后的音频数据
		"""
		if len(data) == 0:
			return data

		filtered = np.zeros(len(data), dtype=np.int16)

		for i in range(len(data)):
			x0 = float(data[i])
			y0 = (self.b0_norm * x0 + self.b1_norm * self._hpf_x1 +
				self.b2_norm * self._hpf_x2 - self.a1_norm * self._hpf_y1 -
				self.a2_norm * self._hpf_y2)
			self._hpf_x2 = self._hpf_x1
			self._hpf_x1 = x0
			self._hpf_y2 = self._hpf_y1
			self._hpf_y1 = y0
			y0_clamped = max(-32768.0, min(32767.0, y0))
			filtered[i] = int(y0_clamped)

		return filtered

	def reset_filter(self) -> None:
		"""重置滤波器状态"""
		self._hpf_x1 = 0.0
		self._hpf_x2 = 0.0
		self._hpf_y1 = 0.0
		self._hpf_y2 = 0.0

	def extract_magnitudes(self, audio_data: np.ndarray) -> np.ndarray:
		"""
		提取多个频率的幅度（使用 Goertzel 算法，比 FFT 更高效）

		Args:
			audio_data: 音频数据

		Returns:
			各频率的幅度数组
		"""
		return self._vectorized_goertzel(audio_data)

	def _vectorized_goertzel(self, samples: np.ndarray) -> np.ndarray:
		"""
		使用 FFT 替代 Goertzel 算法，提高多频率检测效率

		Args:
			samples: 音频样本

		Returns:
			各频率的幅度数组
		"""
		n = len(samples)
		frequencies = np.array([self.START_FREQ + i * self.FREQ_STEP for i in range(self.NUM_FREQUENCIES)], dtype=np.float32)

		# 计算每个频率对应的 k 值
		k = (0.5 + (n * frequencies) / self.SAMPLE_RATE).astype(int)

		# 使用 FFT 计算所有频率的幅度
		fft_result = np.fft.fft(samples.astype(np.float64))
		magnitude = np.abs(fft_result[k]) / n

		return magnitude.astype(np.float32)

	def band_pass_filter(self, data: np.ndarray) -> np.ndarray:
		"""
		带通滤波器（简单的移动平均）

		Args:
			data: 输入音频数据

		Returns:
			滤波后的音频数据
		"""
		if len(data) < 3:
			return data

		filtered = np.zeros(len(data), dtype=np.int16)
		filtered[0] = data[0]

		for i in range(1, len(data) - 1):
			filtered[i] = int((int(data[i - 1]) + int(data[i]) + int(data[i + 1])) / 3)

		filtered[-1] = data[-1]
		return filtered

	@staticmethod
	def calculate_rms(data: np.ndarray) -> float:
		"""
		计算 RMS (均方根) 能量

		Args:
			data: 音频数据

		Returns:
			RMS值
		"""
		if len(data) == 0:
			return 0.0

		sum_val = 0.0
		for sample in data:
			sum_val += float(sample) ** 2

		return float(math.sqrt(sum_val / len(data)))

	@staticmethod
	def calculate_average_magnitude(magnitudes: np.ndarray) -> float:
		"""
		计算平均幅度

		Args:
			magnitudes: 幅度数组

		Returns:
			平均幅度
		"""
		if len(magnitudes) == 0:
			return 0.0
		return float(np.mean(magnitudes))

	@staticmethod
	def short_to_float_array(data: np.ndarray) -> np.ndarray:
		"""
		Short数组转Float数组（归一化到[-1, 1]）

		Args:
			data: Short数组

		Returns:
			Float数组
		"""
		return data.astype(np.float32) / 32768.0

	@staticmethod
	def teager_envelope(signal: np.ndarray) -> np.ndarray:
		"""
		Teager-Kaiser包络提取
		使用公式: Ψ[x(n)] = x²(n) - x(n-1)·x(n+1)
		返回包络信号 = sqrt(|Ψ|)

		Args:
			signal: 输入信号

		Returns:
			包络信号
		"""
		envelope = np.zeros(len(signal), dtype=np.float32)
		for i in range(1, len(signal) - 1):
			psi = signal[i] * signal[i] - signal[i - 1] * signal[i + 1]
			envelope[i] = math.sqrt(abs(psi))
		return envelope

	@staticmethod
	def calculate_teager_feature(envelope: np.ndarray) -> float:
		"""
		计算Teager包络特征值（平均幅度）

		Args:
			envelope: 包络信号

		Returns:
			平均幅度
		"""
		if len(envelope) == 0:
			return 0.0
		if len(envelope) <= 2:
			return 0.0
		return float(np.mean(envelope[1:-1]))
