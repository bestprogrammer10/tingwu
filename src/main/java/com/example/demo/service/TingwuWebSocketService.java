package com.example.demo.service;

import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import com.example.demo.service.analysis.AnalysisResult;
import com.example.demo.service.analysis.ConsoleOutputService;
import com.example.demo.service.analysis.ContextManager;
import com.example.demo.service.analysis.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.sound.sampled.*;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 听悟 WebSocket 服务 - 使用阿里云 NLS SDK 管理实时音频流连接
 */
@Service
public class TingwuWebSocketService {

    private static final Logger logger = LoggerFactory.getLogger(TingwuWebSocketService.class);

    // 注入 TingwuService 用于停止任务
    private final TingwuService tingwuService;

    // 注入分析服务
    private final ContextManager contextManager;
    private final LlmService llmService;
    private final ConsoleOutputService consoleOutputService;

    @Autowired
    public TingwuWebSocketService(TingwuService tingwuService,
                                  ContextManager contextManager,
                                  LlmService llmService,
                                  ConsoleOutputService consoleOutputService) {
        this.tingwuService = tingwuService;
        this.contextManager = contextManager;
        this.llmService = llmService;
        this.consoleOutputService = consoleOutputService;
    }

    // 存储每个任务的语音识别器
    private final ConcurrentHashMap<String, SpeechTranscriber> transcriberMap = new ConcurrentHashMap<>();

    // 音频采集相关
    private TargetDataLine microphone;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private Thread captureThread;

    // NLS 客户端（单例模式）
    private NlsClient nlsClient;

    // 静音检测配置
    private static final long SILENCE_THRESHOLD_MS = 30000;  // 静音阈值：30秒
    private static final long CHECK_INTERVAL_MS = 5000;      // 检查间隔：5秒

    // 记录每个任务最后一次检测到语音的时间
    private final ConcurrentHashMap<String, Long> lastSpeechTimeMap = new ConcurrentHashMap<>();

    // 静音检测定时任务
    private final ScheduledExecutorService silenceChecker = Executors.newScheduledThreadPool(1);
    private final ConcurrentHashMap<String, ScheduledFuture<?>> silenceCheckTasks = new ConcurrentHashMap<>();

    // 流式分析状态缓存（合并三个Map为一个）
    private final ConcurrentHashMap<String, StreamState> streamStateMap = new ConcurrentHashMap<>();

    // ObjectMapper for JSON parsing
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 流式分析状态内部类
    private static class StreamState {
        final StringBuilder buffer = new StringBuilder();
        String speaker0Text;  // 说话人0的内容
        String speaker1Text;  // 说话人1的内容
        int lastSpeakerIndex = -1;  // 上一个说话的索引
        volatile boolean analysisComplete = false;
        volatile boolean isAnalyzing = false;  // AI是否正在分析中
        int analyzedTurnCount = 0;  // 已经分析过的对话轮数
    }

    // 音频格式：16kHz, 16bit, 单声道, PCM
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(
            16000,  // 采样率
            16,     // 采样位数
            1,      // 声道数（单声道）
            true,   // 有符号
            false   // 小端序
    );

    /**
     * 从 URL 中提取 token
     */
    private String extractTokenFromUrl(String url) {
        try {
            URI uri = new URI(url);
            String query = uri.getQuery();
            if (query != null) {
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    if (pair.startsWith("mc=")) {
                        return pair.substring(3);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("解析 URL 失败: {}", url, e);
        }
        return null;
    }

    /**
     * 获取或创建 NLS 客户端
     */
    private synchronized NlsClient getNlsClient(String token) {
        if (nlsClient == null) {
            // 使用 token 创建 NLS 客户端
            nlsClient = new NlsClient(token);
            logger.info("NLS 客户端已创建");
        }
        return nlsClient;
    }

    /**
     * 建立语音识别连接
     *
     * @param taskId         任务ID
     * @param meetingJoinUrl WebSocket连接地址（NLS URL）
     * @param meetingToken   连接令牌（NLS中不需要，URL已包含token）
     * @return 是否连接成功
     */
    public boolean connect(String taskId, String meetingJoinUrl, String meetingToken) {
        // 如果已存在连接，先关闭
        if (transcriberMap.containsKey(taskId)) {
            logger.warn("任务 {} 已存在连接，先关闭旧连接", taskId);
            disconnect(taskId);
        }

        logger.info("正在连接语音识别服务，任务ID: {}, URL: {}", taskId, meetingJoinUrl);

        try {
            // 从 URL 中提取 token
            String token = extractTokenFromUrl(meetingJoinUrl);
            if (token == null || token.isEmpty()) {
                logger.error("无法从 URL 中提取 token: {}", meetingJoinUrl);
                return false;
            }
            logger.info("提取到 token: {}", token.substring(0, Math.min(10, token.length())) + "...");

            // 创建识别监听器
            SpeechTranscriberListener listener = createTranscriberListener(taskId);

            // 创建语音识别器，使用 meetingJoinUrl 作为服务地址
            SpeechTranscriber transcriber = new SpeechTranscriber(
                    getNlsClient(token),
                    "default",  // appKey，使用默认值
                    listener,
                    meetingJoinUrl  // 直接使用 meetingJoinUrl 作为服务URL
            );

            // 配置识别参数
            transcriber.setFormat(InputFormatEnum.PCM);  // PCM格式
            transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);  // 16k采样率
            transcriber.setEnableIntermediateResult(true);  // 开启中间结果
            transcriber.setEnablePunctuation(true);  // 开启标点

            // 启动识别
            transcriber.start();

            // 保存识别器
            transcriberMap.put(taskId, transcriber);

            // 初始化最后说话时间并启动静音检测（已禁用，改为持续对话模式）
            updateLastSpeechTime(taskId);
            // startSilenceCheck(taskId); // 注释掉：改为持续对话，不自动断开

            logger.info("语音识别连接已建立，任务ID: {}", taskId);
            return true;

        } catch (Exception e) {
            logger.error("语音识别连接失败，任务ID: {}", taskId, e);
            return false;
        }
    }

    /**
     * 创建语音识别监听器
     */
    private SpeechTranscriberListener createTranscriberListener(String taskId) {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                // 静默处理识别开始
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                // 静默处理句子开始
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                int sentenceIndex = response.getTransSentenceIndex();
                // 说话人简化为 0 和 1 交替
                String speaker = String.valueOf(sentenceIndex % 2);

                // 更新最后说话时间（用于静音检测）
                updateLastSpeechTime(taskId);

                // 添加到上下文（始终收集）
                contextManager.addTurn(speaker, text);

                // 获取或创建流式状态
                StreamState state = streamStateMap.computeIfAbsent(taskId, k -> new StreamState());

                // 根据说话人缓存内容
                if ("0".equals(speaker)) {
                    state.speaker0Text = text;
                } else {
                    state.speaker1Text = text;
                }

                // 打印语音转写
                consoleOutputService.printStreamStart(taskId, speaker, text);

                // 只有当两个说话人都有内容且AI不在分析中时，才触发AI分析
                if (state.speaker0Text != null && state.speaker1Text != null && !state.isAnalyzing) {
                    // 标记AI正在分析，防止重复触发
                    state.isAnalyzing = true;
                    // 记录当前分析时的轮数
                    state.analyzedTurnCount = contextManager.getTurnCount();

                    // 构建完整的对话Prompt（ContextManager已经包含历史对话）
                    String dialog = "说话人0: " + state.speaker0Text + "\n说话人1: " + state.speaker1Text;
                    String prompt = contextManager.buildPrompt("【本轮对话】\n" + dialog);

                    // 清空本轮缓存，准备收集下一轮
                    state.speaker0Text = null;
                    state.speaker1Text = null;

                    // 启动流式分析
                    llmService.streamAnalyze(prompt, (chunk, isDone) -> {
                        StreamState currentState = streamStateMap.get(taskId);
                        if (currentState == null) return;

                        if (isDone) {
                            // 流结束，标记分析完成
                            currentState.analysisComplete = true;
                            consoleOutputService.printStreamEnd();

                            // 直接打印AI完整回复
                            consoleOutputService.printStreamResult(currentState.buffer.toString());
                            currentState.buffer.setLength(0); // 清空缓冲区

                            // 分析完成后，检查是否有新收集的对话
                            int currentTurnCount = contextManager.getTurnCount();
                            if (currentTurnCount > currentState.analyzedTurnCount) {
                                // 有新对话，再次触发分析
                                logger.info("AI回复期间有新对话，再次触发分析，新轮数: {}",
                                        currentTurnCount - currentState.analyzedTurnCount);
                                triggerAnalysis(taskId);
                            } else {
                                // 没有新对话，解除分析状态
                                currentState.isAnalyzing = false;
                            }
                        } else {
                            // 收集chunk
                            currentState.buffer.append(chunk);
                        }
                    });
                }
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                // 静默处理识别结果变更
                updateLastSpeechTime(taskId);
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                // 静默处理识别完成
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                logger.error("识别失败 [{}]: 状态码={}, 错误={}",
                        taskId, response.getStatus(), response.getTransSentenceText());
                // 清理连接
                transcriberMap.remove(taskId);
                stopMicrophoneCapture();
            }

            @Override
            public void onClose(int code, String reason) {
                logger.info("连接已关闭 [{}], Code: {}, Reason: {}", taskId, code, reason);
                transcriberMap.remove(taskId);
                stopMicrophoneCapture();
            }
        };
    }

    /**
     * 从麦克风采集音频并推送到 NLS
     *
     * @param taskId 任务ID
     * @return 是否开始采集
     */
    public boolean startMicrophoneCapture(String taskId) {
        SpeechTranscriber transcriber = transcriberMap.get(taskId);
        if (transcriber == null) {
            logger.error("任务 {} 未建立语音识别连接", taskId);
            return false;
        }

        if (isRecording.get()) {
            logger.warn("已经在采集音频中");
            return false;
        }

        try {
            // 获取麦克风
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                logger.error("系统不支持该音频格式");
                return false;
            }

            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(AUDIO_FORMAT);
            microphone.start();

            isRecording.set(true);

            // 启动采集线程
            captureThread = new Thread(() -> {
                // NLS 推荐每次发送 3200 字节（约 100ms 的 16k PCM 数据）
                byte[] buffer = new byte[3200];
                logger.info("开始从麦克风采集音频，任务ID: {}", taskId);

                while (isRecording.get()) {
                    try {
                        // 检查连接是否还存活（必须从 map 中实时获取）
                        SpeechTranscriber currentTranscriber = transcriberMap.get(taskId);
                        if (currentTranscriber == null) {
                            logger.warn("语音识别连接已断开，停止采集，任务ID: {}", taskId);
                            break;
                        }

                        int count = microphone.read(buffer, 0, buffer.length);
                        if (count > 0) {
                            // 再次检查连接状态，确保发送前连接还在
                            if (transcriberMap.get(taskId) == null) {
                                logger.warn("发送前连接已断开，停止采集，任务ID: {}", taskId);
                                break;
                            }
                            // 发送音频数据到 NLS
                            currentTranscriber.send(buffer, count);
                        }
                    } catch (Exception e) {
                        logger.error("发送音频数据失败，任务ID: {}", taskId, e);
                        break;
                    }
                }

                logger.info("麦克风采集结束，任务ID: {}", taskId);
            });

            captureThread.start();
            return true;

        } catch (LineUnavailableException e) {
            logger.error("麦克风不可用", e);
            return false;
        }
    }

    /**
     * 停止麦克风采集
     */
    public void stopMicrophoneCapture() {
        if (!isRecording.get()) {
            return;
        }

        logger.info("停止麦克风采集");

        // 先关闭麦克风，这样可以中断阻塞的 read() 操作
        if (microphone != null) {
            microphone.stop();
            microphone.close();
            microphone = null;
        }

        // 然后设置标志位
        isRecording.set(false);

        // 等待采集线程结束
        if (captureThread != null) {
            try {
                captureThread.join(2000); // 等待最多2秒
                logger.info("采集线程已停止");
            } catch (InterruptedException e) {
                logger.warn("等待采集线程结束时被中断");
            }
            captureThread = null;
        }
    }

    /**
     * 断开语音识别连接
     *
     * @param taskId 任务ID
     */
    public void disconnect(String taskId) {
        logger.info("开始断开连接，任务ID: {}", taskId);

        // 1. 取消静音检测任务
        stopSilenceCheck(taskId);

        // 2. 清理最后说话时间记录
        lastSpeechTimeMap.remove(taskId);

        // 3. 清理分析相关上下文
        contextManager.clear();
        logger.info("分析上下文已清理，任务ID: {}", taskId);

        // 4. 清理流式状态
        streamStateMap.remove(taskId);

        // 5. 先从 map 中移除，这样采集线程会立即停止发送
        SpeechTranscriber transcriber = transcriberMap.remove(taskId);

        // 5. 停止麦克风采集
        stopMicrophoneCapture();

        // 6. 关闭 NLS 连接
        if (transcriber != null) {
            try {
                transcriber.stop();
                logger.info("语音识别连接已断开，任务ID: {}", taskId);
            } catch (Exception e) {
                logger.error("断开语音识别连接失败", e);
            }
        }
    }

    /**
     * 检查连接是否存活
     *
     * @param taskId 任务ID
     * @return 是否连接中
     */
    public boolean isConnected(String taskId) {
        return transcriberMap.containsKey(taskId);
    }

    /**
     * 获取所有连接的任务ID
     */
    public ConcurrentHashMap.KeySetView<String, SpeechTranscriber> getConnectedTasks() {
        return transcriberMap.keySet();
    }

    /**
     * 关闭服务时清理资源
     */
    @PreDestroy
    public void shutdown() {
        logger.info("关闭 NLS 服务...");

        // 取消所有静音检测任务
        for (String taskId : silenceCheckTasks.keySet()) {
            stopSilenceCheck(taskId);
        }
        silenceChecker.shutdown();

        stopMicrophoneCapture();

        // 断开所有连接
        for (String taskId : transcriberMap.keySet()) {
            disconnect(taskId);
        }

        // 关闭 NLS 客户端
        if (nlsClient != null) {
            nlsClient.shutdown();
            nlsClient = null;
        }
    }

    // ==================== 流式分析相关方法 ====================

    /**
     * 触发新一轮AI分析（用于AI回复期间有新对话的情况）
     */
    private void triggerAnalysis(String taskId) {
        StreamState state = streamStateMap.get(taskId);
        if (state == null) return;

        // 获取新增的对话
        java.util.List<String> newTurns = contextManager.getNewTurns(state.analyzedTurnCount);
        if (newTurns.isEmpty()) {
            state.isAnalyzing = false;
            return;
        }

        // 更新分析轮数
        state.analyzedTurnCount = contextManager.getTurnCount();

        // 构建新增对话的文本
        StringBuilder dialogBuilder = new StringBuilder();
        for (String turn : newTurns) {
            dialogBuilder.append(turn).append("\n");
        }

        // 构建Prompt（ContextManager会自动包含历史对话）
        String prompt = contextManager.buildPrompt("【新增对话】\n" + dialogBuilder.toString());

        // 重置缓冲区
        state.buffer.setLength(0);
        state.analysisComplete = false;

        // 启动流式分析
        llmService.streamAnalyze(prompt, (chunk, isDone) -> {
            StreamState currentState = streamStateMap.get(taskId);
            if (currentState == null) return;

            if (isDone) {
                currentState.analysisComplete = true;
                consoleOutputService.printStreamEnd();
                consoleOutputService.printStreamResult(currentState.buffer.toString());
                currentState.buffer.setLength(0);

                // 再次检查是否有新对话（递归触发）
                int currentTurnCount = contextManager.getTurnCount();
                if (currentTurnCount > currentState.analyzedTurnCount) {
                    logger.info("AI回复期间有新对话，再次触发分析，新轮数: {}",
                            currentTurnCount - currentState.analyzedTurnCount);
                    triggerAnalysis(taskId);
                } else {
                    currentState.isAnalyzing = false;
                }
            } else {
                currentState.buffer.append(chunk);
            }
        });
    }

    /**
     * 解析流式返回的完整内容为 AnalysisResult
     */
    private AnalysisResult parseStreamResult(String content) {
        try {
            // 尝试使用 ObjectMapper 解析 JSON
            JsonNode json = objectMapper.readTree(content);

            String customerNeed = json.path("customerNeed").asText("未知");
            String recommendedCategory = json.path("recommendedCategory").asText("未知");
            String urgency = json.path("urgency").asText("low");
            double confidence = json.path("confidence").asDouble(0.5);

            // 解析 followUpQuestions 数组
            java.util.List<String> followUpQuestions = new java.util.ArrayList<>();
            JsonNode questionsNode = json.path("followUpQuestions");
            if (questionsNode.isArray()) {
                for (JsonNode q : questionsNode) {
                    followUpQuestions.add(q.asText());
                }
            }

            return AnalysisResult.success(customerNeed, recommendedCategory, followUpQuestions, urgency, confidence);

        } catch (Exception e) {
            // JSON解析失败，尝试关键词匹配
            logger.warn("JSON解析失败，使用关键词匹配: {}", e.getMessage());

            String customerNeed = "分析中...";
            if (content.contains("发烧") || content.contains("退热")) {
                customerNeed = "退烧药";
            } else if (content.contains("感冒")) {
                customerNeed = "感冒药";
            } else if (content.contains("胃")) {
                customerNeed = "胃药";
            }

            return AnalysisResult.success(customerNeed, "请参考具体药品",
                    java.util.Collections.singletonList("请提供更多症状信息"), "low", 0.3);
        }
    }

    // ==================== 静音检测相关方法 ====================

    /**
     * 更新最后说话时间
     */
    private void updateLastSpeechTime(String taskId) {
        lastSpeechTimeMap.put(taskId, System.currentTimeMillis());
    }

    /**
     * 启动静音检测定时任务
     */
    private void startSilenceCheck(String taskId) {
        ScheduledFuture<?> future = silenceChecker.scheduleAtFixedRate(
                () -> checkSilence(taskId),
                CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        silenceCheckTasks.put(taskId, future);
    }

    /**
     * 停止静音检测定时任务
     */
    private void stopSilenceCheck(String taskId) {
        ScheduledFuture<?> future = silenceCheckTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            logger.info("停止静音检测，任务ID: {}", taskId);
        }
    }

    /**
     * 检查是否静音超时（已禁用，改为持续对话模式）
     */
    private void checkSilence(String taskId) {
        // 静音检测已禁用，改为持续对话模式
        // 对话会持续进行，每完成一组（0+1）就发给AI分析
        /*
        if (!transcriberMap.containsKey(taskId)) {
            stopSilenceCheck(taskId);
            return;
        }

        Long lastTime = lastSpeechTimeMap.get(taskId);
        if (lastTime == null) {
            return;
        }

        long silentDuration = System.currentTimeMillis() - lastTime;
        if (silentDuration >= SILENCE_THRESHOLD_MS) {
            StreamState state = streamStateMap.get(taskId);
            if (state != null && !state.analysisComplete) {
                lastSpeechTimeMap.put(taskId, System.currentTimeMillis() - SILENCE_THRESHOLD_MS + 10000);
                return;
            }
            disconnect(taskId);
            try {
                tingwuService.stopRealtimeTask(taskId);
            } catch (Exception e) {
                logger.error("停止听悟任务失败，任务ID: {}", taskId, e);
            }
        }
        */
    }
}
