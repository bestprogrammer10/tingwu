package com.example.demo.service.analysis;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * 大模型服务接口
 */
public interface LlmService {

    /**
     * 异步分析对话内容
     * @param prompt 构建好的prompt
     * @return 分析结果Future
     */
    CompletableFuture<AnalysisResult> analyze(String prompt);

    /**
     * 流式分析（实现SSE流式输出）
     * @param prompt 构建好的prompt
     * @param onChunk 流式回调，参数1为chunk内容，参数2为是否完成
     */
    void streamAnalyze(String prompt, BiConsumer<String, Boolean> onChunk);
}
