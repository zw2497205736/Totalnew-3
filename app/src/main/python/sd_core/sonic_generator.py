"""
超声波信号发射器
生成并播放多频率超声波信号 (20-23.15kHz)
"""
import numpy as np
from typing import Tuple

class SonicGenerator:
	SAMPLE_RATE: int = 48000
	BUFFER_SIZE: int = 4096
	NUM_FREQUENCIES: int = 10
	START_FREQ: int = 20000
	FREQ_STEP: int = 350

	def __init__(self):
		self._phase_accumulators = np.zeros(self.NUM_FREQUENCIES, dtype=np.float64)

	def generate_multi_frequency_signal(self, num_samples: int=BUFFER_SIZE) -> np.ndarray:
		"""
		生成多频率混合的超声波信号（相位连续，消除咔嗒声）

		Args:
			num_samples: 样本数量

		Returns:
			ShortArray: 生成的音频数据
		"""
		buffer = np.zeros(num_samples, dtype=np.float64)
		amplitude = np.iinfo(np.int16).max / self.NUM_FREQUENCIES

		for f in range(self.NUM_FREQUENCIES):
			frequency = self.START_FREQ + f * self.FREQ_STEP
			for i in range(num_samples):
				buffer[i] += np.sin(self._phase_accumulators[f])
				self._phase_accumulators[f] += 2.0 * np.pi * frequency / self.SAMPLE_RATE
				if self._phase_accumulators[f] > 2.0 * np.pi:
					self._phase_accumulators[f] -= 2.0 * np.pi

		return (buffer * amplitude).astype(np.int16)

	def generate_multi_frequency_signal_vectorized(self, num_samples: int=BUFFER_SIZE) -> np.ndarray:
		"""
		生成多频率混合的超声波信号（向量化版本，更高效）

		Args:
			num_samples: 样本数量

		Returns:
			ShortArray: 生成的音频数据
		"""
		# NOTE: 耗时大约 <1ms, 基本无开销

		buffer = np.zeros(num_samples, dtype=np.float64)
		amplitude = np.iinfo(np.int16).max / self.NUM_FREQUENCIES

		for f in range(self.NUM_FREQUENCIES):
			frequency = self.START_FREQ + f * self.FREQ_STEP
			phase_increments = np.full(num_samples, 2.0 * np.pi * frequency / self.SAMPLE_RATE)
			phases = np.cumsum(phase_increments) + self._phase_accumulators[f]
			phases = phases % (2.0 * np.pi)
			self._phase_accumulators[f] = phases[-1]
			buffer += np.sin(phases)

		return (buffer * amplitude).astype(np.int16)

	def get_frequencies(self) -> Tuple[int, int, int]:
		"""
		获取频率配置信息

		Returns:
			Tuple[int, int, int]: (起始频率, 频率步进, 频率数量)
		"""
		return self.START_FREQ, self.FREQ_STEP, self.NUM_FREQUENCIES

	def reset_phase(self) -> None:
		"""重置相位累积器"""
		self._phase_accumulators.fill(0)
