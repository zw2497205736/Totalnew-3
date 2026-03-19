#include <jni.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include <cstring>
#include <time.h>
#include <queue>
#include <mutex>

#define TAG "NativeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jclass g_recorderClass = nullptr;
static jmethodID g_onFrameMethod = nullptr; // static void onNativeAudioFrame(short[] data, int frames)
static jshortArray g_globalArray = nullptr; // 全局复用的数组，避免频繁GC

static SLObjectItf engineObject = nullptr;
static SLEngineItf engineEngine = nullptr;

static SLObjectItf recorderObject = nullptr;
static SLRecordItf recorderRecord = nullptr;
static SLAndroidSimpleBufferQueueItf recorderBufferQueue = nullptr;

static short* buffers[2] = {nullptr, nullptr};
static size_t bufferSamples = 0; // samples per buffer
static int currentBuffer = 0;
static int g_sampleRate = 48000;

// 异步队列：避免JNI调用阻塞音频回调
struct AudioFrame {
    short* data;
    size_t frames;
};
static std::queue<AudioFrame> audioQueue;
static std::mutex queueMutex;
static const size_t MAX_QUEUE_SIZE = 4;  // 最多缓存4帧，避免内存爆炸

static void recorderCallback(SLAndroidSimpleBufferQueueItf bq, void* context) {
    (void)context;
    
    // 立即保存当前缓冲区指针和切换索引
    short* data = buffers[currentBuffer];
    size_t frames = bufferSamples;
    currentBuffer = 1 - currentBuffer;
    
    // 【关键优化】立即入队下一个缓冲区，不要等待JNI处理
    (*bq)->Enqueue(bq, buffers[currentBuffer], (SLuint32)(bufferSamples * sizeof(short)));
    
    // 时间戳日志
    static long lastCallbackTime = 0;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    long currentTime = ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    if (lastCallbackTime > 0) {
        long interval = currentTime - lastCallbackTime;
        if (interval > 100) {
            LOGI("⚠ C++ callback interval: %ld ms", interval);
        }
    }
    lastCallbackTime = currentTime;

    // 【新优化】将数据放入队列，立即返回（不阻塞音频线程）
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        if (audioQueue.size() < MAX_QUEUE_SIZE) {
            // 复制数据到独立缓冲区
            short* frameCopy = new short[frames];
            memcpy(frameCopy, data, frames * sizeof(short));
            audioQueue.push({frameCopy, frames});
        } else {
            LOGE("⚠ Audio queue full! Dropping frame.");
        }
    }
    // 回调立即返回，不等待Kotlin处理
}

static bool createEngine() {
    SLresult result = slCreateEngine(&engineObject, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) return false;
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    return result == SL_RESULT_SUCCESS;
}

static bool setupRecorder(int sampleRate, int framesPerBuffer) {
    bufferSamples = (size_t)framesPerBuffer;
    buffers[0] = new short[bufferSamples];
    buffers[1] = new short[bufferSamples];
    std::memset(buffers[0], 0, bufferSamples * sizeof(short));
    std::memset(buffers[1], 0, bufferSamples * sizeof(short));

    // Audio source: microphone
    SLDataLocator_IODevice loc_dev = {SL_DATALOCATOR_IODEVICE, SL_IODEVICE_AUDIOINPUT, SL_DEFAULTDEVICEID_AUDIOINPUT, nullptr};
    SLDataSource audioSrc = {&loc_dev, nullptr};

    // Sink: Android simple buffer queue, PCM format
    SLDataLocator_AndroidSimpleBufferQueue loc_bq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        1, // mono
        (SLuint32)(sampleRate * 1000), // milliHz
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_CENTER,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSink audioSnk = {&loc_bq, &format_pcm};

    // 低延迟配置
    const SLInterfaceID id[2] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_ANDROIDCONFIGURATION};
    const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_FALSE};

    SLresult result = (*engineEngine)->CreateAudioRecorder(engineEngine, &recorderObject, &audioSrc, &audioSnk, 2, id, req);
    if (result != SL_RESULT_SUCCESS) return false;

    // 设置低延迟性能模式
    SLAndroidConfigurationItf configItf;
    result = (*recorderObject)->GetInterface(recorderObject, SL_IID_ANDROIDCONFIGURATION, &configItf);
    if (result == SL_RESULT_SUCCESS) {
        SLuint32 streamType = SL_ANDROID_RECORDING_PRESET_UNPROCESSED;  // 完全无处理，原始信号
        (*configItf)->SetConfiguration(configItf, SL_ANDROID_KEY_RECORDING_PRESET, &streamType, sizeof(SLuint32));
        
        SLuint32 performanceMode = SL_ANDROID_PERFORMANCE_LATENCY;  // 优先延迟而非功耗
        (*configItf)->SetConfiguration(configItf, SL_ANDROID_KEY_PERFORMANCE_MODE, &performanceMode, sizeof(SLuint32));
    }

    result = (*recorderObject)->Realize(recorderObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*recorderObject)->GetInterface(recorderObject, SL_IID_RECORD, &recorderRecord);
    if (result != SL_RESULT_SUCCESS) return false;
    result = (*recorderObject)->GetInterface(recorderObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &recorderBufferQueue);
    if (result != SL_RESULT_SUCCESS) return false;

    result = (*recorderBufferQueue)->RegisterCallback(recorderBufferQueue, recorderCallback, nullptr);
    if (result != SL_RESULT_SUCCESS) return false;

    // Enqueue initial buffers
    currentBuffer = 0;
    (*recorderBufferQueue)->Enqueue(recorderBufferQueue, buffers[0], (SLuint32)(bufferSamples * sizeof(short)));
    (*recorderBufferQueue)->Enqueue(recorderBufferQueue, buffers[1], (SLuint32)(bufferSamples * sizeof(short)));

    return true;
}

static void startRecording() {
    if (recorderRecord) {
        (*recorderRecord)->SetRecordState(recorderRecord, SL_RECORDSTATE_RECORDING);
    }
}

static void stopRecording() {
    if (recorderRecord) {
        (*recorderRecord)->SetRecordState(recorderRecord, SL_RECORDSTATE_STOPPED);
    }
    if (recorderObject) {
        (*recorderObject)->Destroy(recorderObject);
        recorderObject = nullptr;
        recorderRecord = nullptr;
        recorderBufferQueue = nullptr;
    }
    delete[] buffers[0];
    delete[] buffers[1];
    buffers[0] = buffers[1] = nullptr;
}

jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_NativeAudioRecorder_nativeStart(JNIEnv* env, jclass clazz, jint sampleRate, jint framesPerBuffer) {
    // Cache class & method for callback
    if (!g_recorderClass) {
        g_recorderClass = (jclass)env->NewGlobalRef(clazz);
    }
    if (!g_onFrameMethod) {
        g_onFrameMethod = env->GetStaticMethodID(g_recorderClass, "onNativeAudioFrame", "([SI)V");
        if (!g_onFrameMethod) {
            LOGE("Failed to get onNativeAudioFrame method ID");
            return JNI_FALSE;
        }
    }
    // 创建全局复用的数组
    if (!g_globalArray) {
        jshortArray localArray = env->NewShortArray(framesPerBuffer);
        g_globalArray = (jshortArray)env->NewGlobalRef(localArray);
        env->DeleteLocalRef(localArray);
    }
    g_sampleRate = sampleRate;
    if (!engineObject && !createEngine()) {
        LOGE("Failed to create OpenSL ES engine");
        return JNI_FALSE;
    }
    if (!setupRecorder(sampleRate, framesPerBuffer)) {
        LOGE("Failed to setup recorder");
        return JNI_FALSE;
    }
    startRecording();
    LOGI("Native recorder started: %d Hz, %d frames", sampleRate, framesPerBuffer);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeAudioRecorder_nativeStop(JNIEnv* env, jclass) {
    stopRecording();
    if (engineObject) {
        (*engineObject)->Destroy(engineObject);
        engineObject = nullptr;
        engineEngine = nullptr;
    }
    // 清理全局数组
    if (g_globalArray) {
        env->DeleteGlobalRef(g_globalArray);
        g_globalArray = nullptr;
    }
    // 清理队列中的数据
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        while (!audioQueue.empty()) {
            delete[] audioQueue.front().data;
            audioQueue.pop();
        }
    }
    LOGI("Native recorder stopped and resources freed");
}

// 新函数：从队列中取出一帧数据（Kotlin主动调用，异步处理）
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_NativeAudioRecorder_pollAudioFrame(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(queueMutex);
    if (audioQueue.empty()) {
        return JNI_FALSE;  // 没有数据
    }
    
    AudioFrame frame = audioQueue.front();
    audioQueue.pop();
    
    // 将数据写入全局数组
    if (g_globalArray && g_recorderClass && g_onFrameMethod) {
        env->SetShortArrayRegion(g_globalArray, 0, (jsize)frame.frames, frame.data);
        env->CallStaticVoidMethod(g_recorderClass, g_onFrameMethod, g_globalArray, (jint)frame.frames);
    }
    
    // 释放复制的数据
    delete[] frame.data;
    
    return JNI_TRUE;  // 成功处理一帧
}
