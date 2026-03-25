import numpy as np
import json
import time

from .sonic_generator import SonicGenerator
from .signal_processor import SignalProcessor
from .proximity_detector import ProximityDetector
from .phase_range_finder import PhaseRangeFinder
from .fused_detector import FusedDetector

sonic_generator = SonicGenerator()
signal_processor = SignalProcessor()
fused_detector = FusedDetector(energy_detector=ProximityDetector(), phase_range_finder=PhaseRangeFinder())

def get_audio_buffer_to_send_np() -> np.ndarray:
	'''
	获取需要发送的音频数据
	'''
	audio_buffer = sonic_generator.generate_multi_frequency_signal_vectorized()
	audio_buffer = audio_buffer.view('<i2')
	return audio_buffer

def process_audio_buffer_np(current_volume: int, audio_buffer: np.ndarray) -> str:
	'''
	处理接收到的反射音频数据
	Args:
		current_volume (int): 当前音量
		audio_buffer (np.ndarray): 原始音频数据
	Returns:
		json 字符串:
			- energy_ratio: float, 能量比
			- state: str, 当前检测状态
			- relative_distance: float, 相对距离
			- time_in_state: int, 当前状态持续时间 (毫秒）
	'''
	filtered_audio_data = signal_processor.high_pass_filter(audio_buffer)
	energy_ratio = fused_detector.process_audio_data(current_volume, filtered_audio_data, audio_buffer)
	res = {
		"energy_ratio": energy_ratio,
		"state": fused_detector.get_current_state().name,
		"relative_distance": float(fused_detector.get_relative_distance()),
		"time_in_state": fused_detector.get_time_in_state(),
	}
	return json.dumps(res)

def get_audio_buffer_to_send() -> bytes:
	'''
	获取需要发送的音频数据
	'''
	audio_buffer = sonic_generator.generate_multi_frequency_signal_vectorized()
	audio_buffer = audio_buffer.view('<i2')
	return audio_buffer.tobytes()

def process_audio_buffer(current_volume: int, audio_buffer: bytes) -> str:
	'''
	处理接收到的反射音频数据
	Args:
		current_volume (int): 当前音量
		audio_buffer (bytes): 原始音频数据
	Returns:
		json 字符串:
			- energy_ratio: float, 能量比
			- state: str, 当前检测状态
			- relative_distance: float, 相对距离
			- time_in_state: int, 当前状态持续时间 (毫秒）
	'''
	audio_buffer = np.frombuffer(audio_buffer, dtype='<i2')
	filtered_audio_data = signal_processor.high_pass_filter(audio_buffer)
	energy_ratio = fused_detector.process_audio_data(current_volume, filtered_audio_data, audio_buffer)
	res = {
		"energy_ratio": energy_ratio,
		"state": fused_detector.get_current_state().name,
		"relative_distance": float(fused_detector.get_relative_distance()),
		"time_in_state": fused_detector.get_time_in_state(),
	}
	return json.dumps(res)

def get_detect_state() -> str:
	'''
	获取当前检测状态
	Returns:
		json 字符串:
			- state: 当前检测状态
			- relative_distance: 相对距离
			- time_in_state: 当前状态持续时间
	'''
	res = {
		"energy_ratio": fused_detector.get_current_energy_ratio(),
		"state": fused_detector.get_current_state().name,
		"relative_distance": float(fused_detector.get_relative_distance()),
		"time_in_state": fused_detector.get_time_in_state(),
	}
	return json.dumps(res)

def reset():
	'''
	重置检测器
	'''
	sonic_generator.reset_phase()
	signal_processor.reset_filter()
	fused_detector.reset()
