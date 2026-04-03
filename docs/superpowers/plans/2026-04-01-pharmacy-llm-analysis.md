# 药店实时对话分析系统 - 控制台版Demo实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个控制台版Demo，实时分析药店售货员与顾客的对话，通过大模型识别顾客用药需求并在控制台输出分析结果。

**Architecture:** 基于现有Spring Boot + 阿里云听悟项目，新增分析模块。句子级触发（onSentenceEnd）→ 维护3轮上下文 → 调用第三方大模型API → 控制台输出JSON分析结果。

**Tech Stack:** Java 8, Spring Boot 2.7.18, Alibaba NLS SDK, HTTP Client (RestTemplate/WebClient)

**Design Doc:** [2026-04-01-pharmacy-llm-analysis-design.md](../specs/2026-04-01-pharmacy-llm-analysis-design.md)

---

## File Structure

### 新建文件
| 文件 | 职责 |
|-----|------|
| `service/analysis/AnalysisResult.java` | 大模型分析结果的数据结构 |
| `service/analysis/ContextManager.java` | 维护对话上下文（3轮） |
| `service/analysis/ContextManagerImpl.java` | ContextManager实现 |
| `service/analysis/LlmService.java` | 大模型服务接口 |
| `service/analysis/LlmServiceImpl.java` | 对接第三方API实现 |
| `service/analysis/ConsoleOutputService.java` | 控制台输出分析结果 |

### 修改文件
| 文件 | 修改内容 |
|-----|---------|
| `service/TingwuWebSocketService.java` | 在onSentenceEnd中集成分析逻辑 |
| `config/TingwuProperties.java` | 新增大模型配置属性 |
| `resources/application.yml` | 新增llm配置节 |

---

## Chunk 1: 数据模型和配置

### Task 1: 创建 AnalysisResult 数据类

**Files:**
- Create: `src/main/java/com/example/demo/service/analysis/AnalysisResult.java`

- [ ] **Step 1: 创建 AnalysisResult 类**

```java
package com.example.demo.service.analysis;

import java.util.List;

/**
 * 大模型分析结果
 */
public class AnalysisResult {
    private String customerNeed;
    private String recommendedCategory;
    private List<String> followUpQuestions;
    private String urgency;
    private double confidence;
    private boolean success;
    private String errorMessage;

    // 成功结果构造器
    public static AnalysisResult success(String customerNeed, String recommendedCategory, 
                                         List<String> followUpQuestions, String urgency, 
                                         double confidence) {
        AnalysisResult result = new AnalysisResult();
        result.customerNeed = customerNeed;
        result.recommendedCategory = recommendedCategory;
        result.followUpQuestions = followUpQuestions;
        result.urgency = urgency;
        result.confidence = confidence;
        result.success = true;
        return result;
    }

    // 失败结果构造器
    public static AnalysisResult failure(String errorMessage) {
        AnalysisResult result = new AnalysisResult();
        result.success = false;
        result.errorMessage = errorMessage;
        return result;
    }

    // Getters and Setters
    public String getCustomerNeed() { return customerNeed; }
    public void setCustomerNeed(String customerNeed) { this.customerNeed = customerNeed; }

    public String getRecommendedCategory() { return recommendedCategory; }
    public void setRecommendedCategory(String recommendedCategory) { this.recommendedCategory = recommendedCategory; }

    public List<String> getFollowUpQuestions() { return followUpQuestions; }
    public void setFollowUpQuestions(List<String> followUpQuestions) { this.followUpQuestions = followUpQuestions; }

    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @Override
    public String toString() {
        return "AnalysisResult{" +
                "customerNeed='" + customerNeed + '\'' +
                ", recommendedCategory='" + recommendedCategory + '\'' +
                ", followUpQuestions=" + followUpQuestions +
                ", urgency='" + urgency + '\'' +
                ", confidence=" + confidence +
                ", success=" + success +
                '}';
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

---

### Task 2: 新增大模型配置

**Files:**
- Modify: `src/main/java/com/example/demo/config/TingwuProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 TingwuProperties 中添加 LlmConfig 内部类**

在 `TingwuProperties.java` 中添加：

```java
public static class LlmConfig {
    private String apiBaseUrl;
    private String apiKey;
    private String model = "default";
    private int timeout = 5000;
    private String responseFormat = "json";

    // Getters and Setters
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }
}
```

并在 TingwuProperties 类中添加字段：
```java
private LlmConfig llm = new LlmConfig();

public LlmConfig getLlm() { return llm; }
public void setLlm(LlmConfig llm) { this.llm = llm; }
```

- [ ] **Step 2: 在 application.yml 中添加 llm 配置**

```yaml
# 大模型配置
llm:
  api-base-url: ${LLM_API_URL:}
  api-key: ${LLM_API_KEY:}
  model: ${LLM_MODEL:default}
  timeout: 5000
  response-format: json
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

---

## Chunk 2: 核心服务实现

### Task 3: 实现 ContextManager

**Files:**
- Create: `src/main/java/com/example/demo/service/analysis/ContextManager.java`
- Create: `src/main/java/com/example/demo/service/analysis/ContextManagerImpl.java`

- [ ] **Step 1: 创建 ContextManager 接口**

```java
package com.example.demo.service.analysis;

/**
 * 对话上下文管理器
 */
public interface ContextManager {
    
    /**
     * 添加一轮对话
     * @param speaker 说话人（顾客/售货员）
     * @param text 对话内容
     */
    void addTurn(String speaker, String text);
    
    /**
     * 构建给大模型的Prompt
     * @param currentInput 当前输入
     * @return 格式化后的Prompt
     */
    String buildPrompt(String currentInput);
    
    /**
     * 清空上下文
     */
    void clear();
    
    /**
     * 获取当前上下文轮数
     */
    int getTurnCount();
}
```

- [ ] **Step 2: 创建 ContextManagerImpl 实现**

```java
package com.example.demo.service.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * 对话上下文管理器实现 - 维护最近3轮对话
 */
@Component
public class ContextManagerImpl implements ContextManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ContextManagerImpl.class);
    private static final int MAX_CONTEXT_ROUNDS = 3;
    
    private final Queue<String> contextQueue = new LinkedList<>();
    
    @Override
    public void addTurn(String speaker, String text) {
        String turn = speaker + ": " + text;
        contextQueue.offer(turn);
        
        // 保持最多3轮
        while (contextQueue.size() > MAX_CONTEXT_ROUNDS * 2) { // *2 because each round has 2 speakers
            contextQueue.poll();
        }
        
        logger.debug("添加对话轮次: {}, 当前总轮数: {}", turn, contextQueue.size());
    }
    
    @Override
    public String buildPrompt(String currentInput) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("【角色】你是一位专业的药店销售助手\n");
        prompt.append("【任务】根据顾客和销售员的对话，分析顾客的用药需求\n");
        prompt.append("【重要提醒】你只是辅助工具，最终用药建议必须由专业药师提供\n\n");
        
        if (!contextQueue.isEmpty()) {
            prompt.append("【历史对话】\n");
            for (String turn : contextQueue) {
                prompt.append(turn).append("\n");
            }
            prompt.append("\n");
        }
        
        prompt.append("【当前输入】\n");
        prompt.append(currentInput).append("\n\n");
        
        prompt.append("【输出要求】\n");
        prompt.append("请分析并以JSON格式返回，不要包含markdown代码块标记：\n");
        prompt.append("{\n");
        prompt.append("    \"customerNeed\": \"顾客核心需求，如'儿童退烧药'\",\n");
        prompt.append("    \"recommendedCategory\": \"推荐药品类型，如'对乙酰氨基酚类'\",\n");
        prompt.append("    \"followUpQuestions\": [\"需要询问的补充信息，如'孩子多大了？'\"],\n");
        prompt.append("    \"urgency\": \"紧急程度(high/medium/low)\",\n");
        prompt.append("    \"confidence\": 0.95\n");
        prompt.append("}\n\n");
        
        prompt.append("【特殊情况处理】\n");
        prompt.append("- 如果信息不足：返回customerNeed为\"信息不足\"，并给出需要询问的问题\n");
        prompt.append("- 如果涉及处方药：添加提醒\"此药品为处方药，需凭处方购买\"\n");
        prompt.append("- 如果症状严重：添加提醒\"建议尽快就医\"\n");
        
        return prompt.toString();
    }
    
    @Override
    public void clear() {
        contextQueue.clear();
        logger.info("上下文已清空");
    }
    
    @Override
    public int getTurnCount() {
        return contextQueue.size();
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

---

### Task 4: 实现 LlmService

**Files:**
- Create: `src/main/java/com/example/demo/service/analysis/LlmService.java`
- Create: `src/main/java/com/example/demo/service/analysis/LlmServiceImpl.java`

- [ ] **Step 1: 创建 LlmService 接口**

```java
package com.example.demo.service.analysis;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
     * 流式分析（预留接口，Demo阶段可不实现）
     * @param prompt 构建好的prompt
     * @param onChunk 流式回调
     */
    void streamAnalyze(String prompt, Consumer<String> onChunk);
}
```

- [ ] **Step 2: 创建 LlmServiceImpl 实现**

```java
package com.example.demo.service.analysis;

import com.example.demo.config.TingwuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型服务实现 - 对接第三方API
 */
@Service
public class LlmServiceImpl implements LlmService {
    
    private static final Logger logger = LoggerFactory.getLogger(LlmServiceImpl.class);
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*?\\}");
    
    private final TingwuProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    @Autowired
    public LlmServiceImpl(TingwuProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public CompletableFuture<AnalysisResult> analyze(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callLlmApi(prompt);
            } catch (Exception e) {
                logger.error("调用大模型API失败", e);
                return AnalysisResult.failure("API调用失败: " + e.getMessage());
            }
        });
    }
    
    private AnalysisResult callLlmApi(String prompt) {
        TingwuProperties.LlmConfig llmConfig = properties.getLlm();
        
        if (llmConfig.getApiBaseUrl() == null || llmConfig.getApiBaseUrl().isEmpty()) {
            logger.warn("LLM API URL未配置，返回降级结果");
            return createFallbackResult();
        }
        
        // 构建请求体（OpenAI兼容格式）
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", llmConfig.getModel());
        requestBody.put("messages", Collections.singletonList(
                Map.of("role", "user", "content", prompt)
        ));
        
        if ("json".equals(llmConfig.getResponseFormat())) {
            requestBody.put("response_format", Map.of("type", "json_object"));
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + llmConfig.getApiKey());
        
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    llmConfig.getApiBaseUrl() + "/v1/chat/completions",
                    HttpMethod.POST,
                    request,
                    String.class
            );
            
            return parseResponse(response.getBody());
        } catch (Exception e) {
            logger.error("API请求异常", e);
            return AnalysisResult.failure("请求异常: " + e.getMessage());
        }
    }
    
    private AnalysisResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText();
                return parseJsonContent(content);
            }
            
            return AnalysisResult.failure("API返回格式异常");
        } catch (Exception e) {
            logger.error("解析响应失败", e);
            return AnalysisResult.failure("解析失败: " + e.getMessage());
        }
    }
    
    private AnalysisResult parseJsonContent(String content) {
        try {
            // 先尝试直接解析
            JsonNode json = objectMapper.readTree(content);
            return extractResult(json);
        } catch (Exception e) {
            // 尝试从文本中提取JSON
            Matcher matcher = JSON_PATTERN.matcher(content);
            if (matcher.find()) {
                try {
                    JsonNode json = objectMapper.readTree(matcher.group());
                    return extractResult(json);
                } catch (Exception ex) {
                    logger.warn("JSON提取失败，使用文本分析");
                }
            }
            
            // 降级：从文本中提取关键信息
            return extractFromText(content);
        }
    }
    
    private AnalysisResult extractResult(JsonNode json) {
        String customerNeed = json.path("customerNeed").asText("未知");
        String recommendedCategory = json.path("recommendedCategory").asText("未知");
        
        List<String> followUpQuestions = new ArrayList<>();
        JsonNode questionsNode = json.path("followUpQuestions");
        if (questionsNode.isArray()) {
            questionsNode.forEach(q -> followUpQuestions.add(q.asText()));
        }
        
        String urgency = json.path("urgency").asText("low");
        double confidence = json.path("confidence").asDouble(0.5);
        
        return AnalysisResult.success(customerNeed, recommendedCategory, followUpQuestions, urgency, confidence);
    }
    
    private AnalysisResult extractFromText(String text) {
        // 简单的文本提取逻辑
        String customerNeed = "分析中...";
        if (text.contains("发烧") || text.contains("退热")) {
            customerNeed = "退烧药";
        } else if (text.contains("感冒")) {
            customerNeed = "感冒药";
        } else if (text.contains("胃")) {
            customerNeed = "胃药";
        }
        
        return AnalysisResult.success(
                customerNeed,
                "请参考具体药品",
                Collections.singletonList("请提供更多症状信息"),
                "low",
                0.3
        );
    }
    
    private AnalysisResult createFallbackResult() {
        return AnalysisResult.success(
                "API未配置",
                "无法分析",
                Collections.singletonList("请配置LLM API"),
                "low",
                0.0
        );
    }
    
    @Override
    public void streamAnalyze(String prompt, Consumer<String> onChunk) {
        // Demo阶段暂不实现流式分析
        logger.info("流式分析暂未实现");
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

---

### Task 5: 实现 ConsoleOutputService

**Files:**
- Create: `src/main/java/com/example/demo/service/analysis/ConsoleOutputService.java`

- [ ] **Step 1: 创建 ConsoleOutputService**

```java
package com.example.demo.service.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 控制台输出服务 - Demo阶段替代WebSocket推送
 */
@Service
public class ConsoleOutputService {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsoleOutputService.class);
    
    /**
     * 在控制台打印分析结果
     * @param taskId 任务ID
     * @param speaker 说话人
     * @param text 原始文本
     * @param result 分析结果
     */
    public void printAnalysis(String taskId, String speaker, String text, AnalysisResult result) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("【实时对话分析】任务ID: " + taskId);
        System.out.println("-".repeat(60));
        System.out.println("【说话人】" + speaker);
        System.out.println("【内容】" + text);
        System.out.println("-".repeat(60));
        
        if (result.isSuccess()) {
            System.out.println("【分析结果】");
            System.out.println("  📋 顾客需求: " + result.getCustomerNeed());
            System.out.println("  💊 推荐类别: " + result.getRecommendedCategory());
            System.out.println("  ⚡ 紧急程度: " + result.getUrgency());
            System.out.println("  🎯 置信度: " + String.format("%.2f", result.getConfidence()));
            
            if (result.getFollowUpQuestions() != null && !result.getFollowUpQuestions().isEmpty()) {
                System.out.println("  ❓ 建议追问:");
                result.getFollowUpQuestions().forEach(q -> 
                    System.out.println("     - " + q)
                );
            }
        } else {
            System.out.println("【分析失败】" + result.getErrorMessage());
        }
        
        System.out.println("=".repeat(60) + "\n");
        
        // 同时记录到日志
        logger.info("任务[{}] 分析完成: {}", taskId, result);
    }
    
    /**
     * 打印系统状态
     */
    public void printStatus(String message) {
        System.out.println("\n>>> " + message + "\n");
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

---

## Chunk 3: 集成到现有系统

### Task 6: 在 TingwuWebSocketService 中集成分析逻辑

**Files:**
- Modify: `src/main/java/com/example/demo/service/TingwuWebSocketService.java`

- [ ] **Step 1: 注入分析服务**

在类中添加以下依赖注入：

```java
// 注入分析服务（在现有字段后添加）
private final ContextManager contextManager;
private final LlmService llmService;
private final ConsoleOutputService consoleOutputService;

// 修改构造函数，添加参数
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
```

- [ ] **Step 2: 新增说话人识别逻辑**

在类中添加：

```java
// 说话人ID到角色的映射（Demo简化版：第一个说话人是顾客，第二个是售货员）
private final ConcurrentHashMap<String, String> speakerRoleMap = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, String> taskFirstSpeaker = new ConcurrentHashMap<>();

/**
 * 识别说话人角色（简化版）
 */
private String detectSpeakerRole(String taskId, String speakerId) {
    String firstSpeaker = taskFirstSpeaker.get(taskId);
    
    if (firstSpeaker == null) {
        // 第一个说话人标记为顾客
        taskFirstSpeaker.put(taskId, speakerId);
        speakerRoleMap.put(speakerId, "顾客");
        return "顾客";
    }
    
    if (firstSpeaker.equals(speakerId)) {
        return "顾客";
    } else {
        // 第二个说话人标记为售货员
        speakerRoleMap.put(speakerId, "售货员");
        return "售货员";
    }
}
```

- [ ] **Step 3: 修改 onSentenceEnd 回调**

找到 `createTranscriberListener` 方法中的 `onSentenceEnd`，替换为：

```java
@Override
public void onSentenceEnd(SpeechTranscriberResponse response) {
    String text = response.getTransSentenceText();
    String speakerId = String.valueOf(response.getTransSentenceIndex()); // 使用句子索引作为说话人标识
    String speaker = detectSpeakerRole(taskId, speakerId);
    
    logger.info("句子结束 [{}]: 说话人={}, 文本={}", taskId, speaker, text);
    
    // 更新最后说话时间（用于静音检测）
    updateLastSpeechTime(taskId);
    
    // 添加到上下文
    contextManager.addTurn(speaker, text);
    
    // 构建Prompt并调用大模型分析
    String prompt = contextManager.buildPrompt(speaker + ": " + text);
    
    llmService.analyze(prompt)
        .thenAccept(result -> {
            // 在控制台输出分析结果
            consoleOutputService.printAnalysis(taskId, speaker, text, result);
        })
        .exceptionally(ex -> {
            logger.error("分析失败", ex);
            AnalysisResult fallback = AnalysisResult.failure("分析异常: " + ex.getMessage());
            consoleOutputService.printAnalysis(taskId, speaker, text, fallback);
            return null;
        });
}
```

- [ ] **Step 4: 在 disconnect 中清理上下文**

在 `disconnect` 方法末尾添加：

```java
// 清理分析相关上下文
contextManager.clear();
speakerRoleMap.clear();
taskFirstSpeaker.remove(taskId);
logger.info("分析上下文已清理，任务ID: {}", taskId);
```

- [ ] **Step 5: 编译验证**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

---

## Chunk 4: 测试和验证

### Task 7: 运行Demo测试

**前置条件：**
- 配置大模型API（在环境变量或application.yml中）
- 或者暂时不配置，观察降级输出

- [ ] **Step 1: 启动应用**

```bash
mvn spring-boot:run
```

- [ ] **Step 2: 调用实时转写接口**

```bash
curl -X POST http://localhost:8080/api/tingwu/realtime/start \
  -H "Content-Type: application/json" \
  -d '{"sourceLanguage": "cn", "format": "pcm", "sampleRate": 16000}'
```

Expected: 返回 taskId 和连接状态

- [ ] **Step 3: 模拟对话测试**

1. 对着麦克风说话（模拟顾客）："我想买点感冒药"
2. 观察控制台输出分析结果
3. 继续对话（模拟售货员）："您有什么症状？"
4. 观察上下文是否正确累积

**期望输出示例：**
```
============================================================
【实时对话分析】任务ID: xxx
------------------------------------------------------------
【说话人】顾客
【内容】我想买点感冒药
------------------------------------------------------------
【分析结果】
  📋 顾客需求: 感冒药
  💊 推荐类别: 复方感冒制剂
  ⚡ 紧急程度: low
  🎯 置信度: 0.85
  ❓ 建议追问:
     - 您有什么症状？
     - 发烧吗？
============================================================
```

---

## 完成标准

- [ ] 所有组件编译通过
- [ ] 启动应用无错误
- [ ] 实时转写时能触发分析
- [ ] 控制台能输出分析结果
- [ ] 上下文能正确累积（3轮）

---

## 后续优化方向（Demo验证后）

1. **添加 WebSocket 推送** - 替换 ConsoleOutputService
2. **实现 SpeakerDetector** - 基于阿里云说话人分离结果
3. **添加去重过滤器** - DeduplicationFilter
4. **完善降级策略** - 本地规则库
5. **添加前端界面** - 售货员终端

