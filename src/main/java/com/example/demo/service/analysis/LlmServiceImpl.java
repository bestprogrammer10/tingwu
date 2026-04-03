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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型服务实现 - 对接第三方API (支持流式输出)
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

    /**
     * 流式分析实现
     * @param prompt 提示词
     * @param onChunk 流式回调，接收 (chunk内容, 是否完成)
     */
    @Override
    public void streamAnalyze(String prompt, BiConsumer<String, Boolean> onChunk) {
        TingwuProperties.LlmConfig llmConfig = properties.getLlm();

        if (llmConfig.getApiBaseUrl() == null || llmConfig.getApiBaseUrl().isEmpty()) {
            logger.warn("LLM API URL未配置");
            onChunk.accept("API未配置，无法分析", true);
            return;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(llmConfig.getApiBaseUrl() + "/v1/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + llmConfig.getApiKey());
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setConnectTimeout(llmConfig.getTimeout());
            connection.setReadTimeout(60000); // 流式读取需要更长的超时

            // 构建请求体（开启流式）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", llmConfig.getModel());

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            requestBody.put("messages", Collections.singletonList(message));
            requestBody.put("stream", true); // 开启流式
            requestBody.put("max_tokens", 32768); // 必填参数

            // 注意：流式模式下部分API不支持response_format，如需JSON格式，建议在prompt中要求

            // 发送请求
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonBody.getBytes("UTF-8"));
            }

            // 读取流式响应
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // 读取错误响应体
                String errorBody = "";
                try (BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), "UTF-8"))) {
                    StringBuilder errorSb = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorSb.append(line);
                    }
                    errorBody = errorSb.toString();
                } catch (Exception e) {
                    logger.warn("读取错误响应失败", e);
                }
                logger.error("API返回错误码: {}, 错误响应: {}", responseCode, errorBody);
                onChunk.accept("API请求失败: " + responseCode + ", " + errorBody, true);
                return;
            }

            // 解析 SSE 流
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                StringBuilder fullContent = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    // SSE 格式: data: {...}
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);

                        // 流结束标记
                        if ("[DONE]".equals(data)) {
                            break;
                        }

                        try {
                            JsonNode chunk = objectMapper.readTree(data);
                            JsonNode choices = chunk.path("choices");

                            if (choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta = choices.get(0).path("delta");
                                // 只取实际回复内容，不取思考过程
                                String content = delta.path("content").asText();

                                if (content != null && !content.isEmpty()) {
                                    fullContent.append(content);
                                    // 实时回调，标记为未完成
                                    onChunk.accept(content, false);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("解析流式chunk失败: {}", data);
                        }
                    }
                }

                // 流结束，回调完整内容
                onChunk.accept(fullContent.toString(), true);
            }

        } catch (Exception e) {
            logger.error("流式API调用失败", e);
            onChunk.accept("流式调用异常: " + e.getMessage(), true);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

        // 构建messages数组
        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        requestBody.put("messages", Collections.singletonList(message));
        requestBody.put("max_tokens", 4096); // 必填参数

        if ("json".equals(llmConfig.getResponseFormat())) {
            requestBody.put("response_format", Collections.singletonMap("type", "json_object"));
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
            for (JsonNode q : questionsNode) {
                followUpQuestions.add(q.asText());
            }
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
}
