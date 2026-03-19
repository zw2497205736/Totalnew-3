#ifndef LLAP_WAVWRITER_H
#define LLAP_WAVWRITER_H

#include <stdio.h>
#include <stdint.h>
#include <string.h>

/**
 * WAV文件写入器
 * 支持32-bit float格式，用于保存基带I/Q信号
 */
class WavWriter {
public:
    WavWriter();
    ~WavWriter();
    
    /**
     * 打开WAV文件准备写入
     * @param filename 文件路径
     * @param sampleRate 采样率（Hz）
     * @param numChannels 声道数
     * @return true=成功, false=失败
     */
    bool open(const char* filename, uint32_t sampleRate, uint16_t numChannels);
    
    /**
     * 写入float数据
     * @param data float数组指针
     * @param numSamples 采样点数（每个声道）
     */
    void writeFloatSamples(const float* data, uint32_t numSamples);
    
    /**
     * 关闭文件并更新文件头
     */
    void close();
    
    /**
     * 是否正在录制
     */
    bool isRecording() const { return file != nullptr; }
    
private:
    struct WAVHeader {
        // RIFF chunk
        char riffID[4];          // "RIFF"
        uint32_t riffSize;       // 文件大小 - 8
        char waveID[4];          // "WAVE"
        
        // fmt chunk
        char fmtID[4];           // "fmt "
        uint32_t fmtSize;        // 16 for PCM, 18 for IEEE_FLOAT
        uint16_t audioFormat;    // 1=PCM, 3=IEEE_FLOAT
        uint16_t numChannels;    // 声道数
        uint32_t sampleRate;     // 采样率
        uint32_t byteRate;       // = sampleRate * numChannels * bitsPerSample/8
        uint16_t blockAlign;     // = numChannels * bitsPerSample/8
        uint16_t bitsPerSample;  // 位深度
        
        // data chunk
        char dataID[4];          // "data"
        uint32_t dataSize;       // 数据大小
    };
    
    FILE* file;
    uint32_t sampleRate;
    uint16_t numChannels;
    uint32_t samplesWritten;
    
    void writeHeader();
    void updateHeader();
};

#endif // LLAP_WAVWRITER_H
