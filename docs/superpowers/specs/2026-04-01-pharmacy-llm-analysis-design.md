# 药店售货员实时对话分析系统设计文档

**日期**: 2026-04-01  
**主题**: 实时转写文字大模型分析  
**状态**: 已批准

---

## 1. 需求概述

### 1.1 背景
在药店场景中，售货员需要快速理解顾客的用药需求。通过实时语音转写结合大模型分析，为售货员提供智能辅助。

### 1.2 目标用户
药店售货员（在收银/售货终端查看分析结果）

### 1.3 核心需求
- 实时分析售货员与顾客的对话
- 识别顾客的用药需求和症状
- 为售货员提供实时建议

---

## 2. 设计方案

### 2.1 架构概览

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│  麦克风      │────▶│  NLS转写     │────▶│  句子级触发  │────▶│  大模型分析  │
└─────────────┘     └──────────────┘     └─────────────┘     └──────────────┘
                                                  │                      │
                                                  ▼                      ▼
                                         ┌─────────────┐     ┌──────────────┐
                                         │  去重判断    │     │  WebSocket   │
                                         └─────────────┘     │  推送售货员  │
                                                              └──────────────┘
```

### 2.2 核心组件

#### 2.2.1 ContextManager（上下文管理器）
**职责**：维护对话历史上下文

**功能**：
- 维护最近3轮对话队列（顾客+售货员）
- 提供格式化Prompt给大模型
- 自动清理过期上下文

**接口**：
```java
public interface ContextManager {
    void addTurn(String speaker, String text);
    String buildPrompt(String currentInput);
    void clear();
}
```

#### 2.2.2 DeduplicationFilter（去重过滤器）
**职责**：过滤重复或高度相似的句子（区分说话人）

**功能**：
- 基于编辑距离计算文本相似度
- 相似度阈值：85%
- 缓存最近5句用于比较
- **关键**：相同内容由不同说话人说出来不算重复（语义不同）

**接口**：
```java
public interface DeduplicationFilter {
    boolean isDuplicate(String speaker, String text);
}
```

#### 2.2.3 LlmService（大模型服务）
**职责**：对接第三方大模型API

**功能**：
- 支持流式/非流式输出
- 异步调用，不阻塞主线程
- 配置化（Base URL、API Key、模型名称）
- 自动解析JSON响应，处理解析失败

**接口**：
```java
public interface LlmService {
    CompletableFuture<AnalysisResult> analyze(String prompt);
    void streamAnalyze(String prompt, Consumer<String> onChunk);
}

public class AnalysisResult {
    private String customerNeed;
    private String recommendedCategory;
    private List<String> followUpQuestions;
    private String urgency;
    private boolean success;
    private String errorMessage;
    // getters/setters
}
```

#### 2.2.4 WebSocketHandler（WebSocket处理器）
**职责**：售货员终端连接管理

**功能**：
- 管理售货员客户端连接
- 实时推送分析结果
- 支持多终端同时连接
- 处理连接中断和重连

**接口**：
```java
public interface WebSocketHandler {
    CompletableFuture<Boolean> sendToSalesperson(String taskId, AnalysisResult result);
    void registerConnection(String taskId, WebSocketSession session);
    void unregisterConnection(String taskId);
}
```

### 2.3 数据流程

1. **onSentenceEnd触发** → 获取完整句子和SpeakerId
2. **说话人识别** → SpeakerDetector映射到角色（顾客/售货员）
3. **去重检查** → 基于（说话人+文本）判断重复
4. **若新内容** → 加入ContextManager
5. **构建Prompt** → 包含历史上下文+当前句子
6. **异步调用大模型** → 通过CompletableFuture
7. **解析响应** → 解析JSON，失败时返回降级结果
8. **结果推送** → WebSocket推送给售货员界面

**边界情况处理**：
- JSON解析失败：返回简化文本分析结果
- 网络中断：自动重连，重试发送
- 页面刷新：重新建立WebSocket连接

### 2.4 Prompt设计

```
【角色】你是一位专业的药店销售助手
【任务】根据顾客和销售员的对话，分析顾客的用药需求
【重要提醒】你只是辅助工具，最终用药建议必须由专业药师提供

【历史对话】
{contextHistory}

【当前输入】
{currentSpeaker}: {currentText}

【输出要求】
请分析并以JSON格式返回，不要包含markdown代码块标记：
{
    "customerNeed": "顾客核心需求，如'儿童退烧药'",
    "recommendedCategory": "推荐药品类型，如'对乙酰氨基酚类'",
    "followUpQuestions": ["需要询问的补充信息，如'孩子多大了？'"],
    "urgency": "紧急程度(high/medium/low)",
    "confidence": "判断置信度(0-1)"
}

【特殊情况处理】
- 如果信息不足：返回customerNeed为"信息不足"，并给出需要询问的问题
- 如果涉及处方药：添加提醒"此药品为处方药，需凭处方购买"
- 如果症状严重：添加提醒"建议尽快就医"
```

**容错处理**：
- 大模型返回非JSON格式时，使用正则提取关键信息
- 关键字段缺失时，使用默认值（如confidence=0.5）
- 使用`response_format: { "type": "json_object" }`强制JSON输出（OpenAI兼容API）

### 2.5 技术实现要点

#### 2.5.1 异步处理
- 使用`CompletableFuture`避免阻塞音频采集线程
- 大模型调用在独立线程池执行

#### 2.5.2 去重算法
- 采用Levenshtein编辑距离
- 归一化后计算相似度：`(1 - distance/max_len) * 100`
- 相似度>85%视为重复

#### 2.5.3 超时控制
- 大模型调用设置5秒超时
- 超时后返回简化分析结果

#### 2.5.4 降级策略
- API失败时返回本地规则库匹配结果（基于关键词）
- 超时后返回简化分析：`{"customerNeed": "分析中...", "recommendedCategory": "请稍后再试"}`
- 记录失败日志供排查
- 降级结果通过WebSocket推送，UI以灰色显示表示非AI生成

**本地规则库示例**：
- 关键词"发烧"→ 退烧药类别
- 关键词"感冒"→ 感冒药类别
- 关键词"胃"→ 胃药类别

#### 2.2.5 SpeakerDetector（说话人检测器）
**职责**：识别说话人身份（顾客 vs 售货员）

**功能**：
- 基于听悟说话人分离结果（SpeakerId）
- 映射到实际角色（顾客/售货员）
- 处理新说话人加入的场景

**接口**：
```java
public interface SpeakerDetector {
    String detectSpeaker(String taskId, String speakerId);
    void registerSpeaker(String taskId, String speakerId, String role);
    void clear(String taskId);
}
```

**说明**：
- 首次检测到新说话人时，默认标记为"顾客"
- 售货员可通过界面手动标记自己的身份
- 后续根据SpeakerId自动识别角色

### 3.1 与现有系统集成

在`TingwuWebSocketService.onSentenceEnd()`中增加：

```java
@Override
public void onSentenceEnd(SpeechTranscriberResponse response) {
    String text = response.getTransSentenceText();
    String speakerId = response.getSpeakerId(); // NLS返回的说话人ID
    String speaker = speakerDetector.detectSpeaker(taskId, speakerId);
    
    // 新增逻辑
    if (!deduplicationFilter.isDuplicate(speaker, text)) {
        contextManager.addTurn(speaker, text);
        String prompt = contextManager.buildPrompt(speaker + ": " + text);
        
        llmService.analyze(prompt)
            .thenAccept(result -> webSocketHandler.sendToSalesperson(taskId, result))
            .exceptionally(ex -> {
                logger.error("分析失败", ex);
                // 降级：使用本地规则库
                AnalysisResult fallback = ruleBasedAnalyzer.analyze(text);
                webSocketHandler.sendToSalesperson(taskId, fallback);
                return null;
            });
    }
}
```

### 3.2 配置项

```yaml
llm:
  api-base-url: ${LLM_API_URL}
  api-key: ${LLM_API_KEY}
  model: ${LLM_MODEL:default}
  timeout: 5000
  response-format: json  # 支持OpenAI等模型的强制JSON模式
  
analysis:
  context-rounds: 3
  dedup-threshold: 85
  max-retries: 2
  auto-cleanup-minutes: 30  # 自动清理上下文时间
```

---

## 4. 非功能性需求

### 4.1 性能要求
- 端到端延迟：< 3秒（从句子结束到推送结果，含大模型调用）
  - 正常情况：1.5-2秒
  - 降级情况：< 0.5秒（本地规则库）
- 支持并发：单实例支持10个药店同时工作

**性能优化措施**：
- 使用HTTP连接池复用与大模型API的连接
- 异步处理，不阻塞音频采集线程
- 本地规则库作为热缓存

### 4.2 可靠性
- 大模型API失败率< 1%
- 自动重试机制

### 4.3 安全性
- API Key加密存储（使用Spring Cloud Config加密或环境变量）
- 对话内容本地处理，仅转写文本传给大模型
- 敏感数据（如患者姓名）脱敏处理
- **处方药合规提醒**：系统输出需明确标注"建议咨询专业药师/医生"
- 日志中不记录完整对话内容，仅记录分析结果
- 定期清理历史上下文数据（建议30分钟无活动自动清理）

---

## 5. 后续扩展

- 支持药品库存联动（推荐有库存的药品）
- 支持会员识别和历史用药记录
- 多语言支持（方言识别）

---

## 6. 批准记录

- **设计方案批准**: 2026-04-01
- **实现方式**: 方案A + 去重优化
- **大模型**: 用户自有第三方API
- **输出对象**: 售货员终端

---

## 7. Demo阶段简化（当前阶段）

### 7.1 Demo目标
验证核心功能：语音转写 → 大模型分析 → 准确识别顾客需求

### 7.2 简化组件
| 完整版组件 | Demo简化 |
|-----------|---------|
| SpeakerDetector | 手动指定说话人角色（第一个=顾客，第二个=售货员） |
| WebSocketHandler | ConsoleOutputService（控制台打印JSON） |
| 售货员终端界面 | 控制台日志 |
| 本地规则库 | 暂不实现，先验证大模型能力 |

### 7.3 Demo验证流程
1. 启动应用，开始实时转写
2. 模拟对话（你扮演售货员，另一人扮演顾客）
3. 控制台实时输出分析结果：
```
[顾客]: 我想买点感冒药
[分析结果] {
  "customerNeed": "感冒药",
  "recommendedCategory": "复方感冒制剂",
  "followUpQuestions": ["您有什么症状？", "发烧吗？"],
  "urgency": "low"
}
```
4. 验证大模型是否能准确理解上下文和意图

### 7.4 后续扩展
Demo验证通过后，再添加：
- WebSocket推送给前端
- 本地规则库降级
- 售货员终端界面

