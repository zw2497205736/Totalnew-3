#include "WavWriter.h"
#include <android/log.h>
#include <errno.h>
#include <string.h>

#define LOG_TAG "WavWriter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

WavWriter::WavWriter() : file(nullptr), sampleRate(0), numChannels(0), samplesWritten(0) {
}

WavWriter::~WavWriter() {
    if (file) {
        close();
    }
}

bool WavWriter::open(const char* filename, uint32_t sampleRate, uint16_t numChannels) {
    if (file) {
        LOGE("File already open");
        return false;
    }
    
    LOGI("Attempting to open file: %s", filename);
    
    file = fopen(filename, "wb");
    if (!file) {
        LOGE("Failed to open file: %s, errno: %d (%s)", filename, errno, strerror(errno));
        return false;
    }
    
    this->sampleRate = sampleRate;
    this->numChannels = numChannels;
    this->samplesWritten = 0;
    
    // 写入文件头（占位，稍后更新）
    writeHeader();
    
    LOGI("Started recording to: %s (%.1f kHz, %d channels)", filename, sampleRate/1000.0f, numChannels);
    return true;
}

void WavWriter::writeFloatSamples(const float* data, uint32_t numSamples) {
    if (!file) return;
    
    // 写入float数据（32-bit）
    size_t written = fwrite(data, sizeof(float), numSamples * numChannels, file);
    samplesWritten += written / numChannels;
    
    // 定期刷新缓冲
    if (samplesWritten % 1000 == 0) {
        fflush(file);
    }
}

void WavWriter::close() {
    if (!file) return;
    
    // 更新文件头
    updateHeader();
    
    fclose(file);
    file = nullptr;
    
    LOGI("Recording stopped. Total samples: %u (%.2f seconds)", 
         samplesWritten, (float)samplesWritten / sampleRate);
}

void WavWriter::writeHeader() {
    WAVHeader header;
    
    // RIFF chunk
    memcpy(header.riffID, "RIFF", 4);
    header.riffSize = 36;  // 暂时设为最小值，稍后更新
    memcpy(header.waveID, "WAVE", 4);
    
    // fmt chunk
    memcpy(header.fmtID, "fmt ", 4);
    header.fmtSize = 16;
    header.audioFormat = 3;  // IEEE_FLOAT
    header.numChannels = numChannels;
    header.sampleRate = sampleRate;
    header.bitsPerSample = 32;  // 32-bit float
    header.blockAlign = numChannels * sizeof(float);
    header.byteRate = sampleRate * header.blockAlign;
    
    // data chunk
    memcpy(header.dataID, "data", 4);
    header.dataSize = 0;  // 稍后更新
    
    fwrite(&header, sizeof(WAVHeader), 1, file);
}

void WavWriter::updateHeader() {
    if (!file) return;
    
    uint32_t dataSize = samplesWritten * numChannels * sizeof(float);
    uint32_t riffSize = 36 + dataSize;
    
    // 更新RIFF size
    fseek(file, 4, SEEK_SET);
    fwrite(&riffSize, sizeof(uint32_t), 1, file);
    
    // 更新data size
    fseek(file, 40, SEEK_SET);
    fwrite(&dataSize, sizeof(uint32_t), 1, file);
    
    fflush(file);
}
