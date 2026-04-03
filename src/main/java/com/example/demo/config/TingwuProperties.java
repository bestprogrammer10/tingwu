package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云听悟配置类
 */
@ConfigurationProperties(prefix = "aliyun.tingwu")
public class TingwuProperties {

    /** AccessKey ID */
    private String accessKeyId;

    /** AccessKey Secret */
    private String accessKeySecret;

    /** 听悟项目 AppKey */
    private String appKey;

    /** 服务区域 */
    private String region = "cn-beijing";

    /** 端点 */
    private String endpoint = "tingwu.cn-beijing.aliyuncs.com";

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** 大模型配置 */
    private LlmConfig llm = new LlmConfig();

    public LlmConfig getLlm() { return llm; }
    public void setLlm(LlmConfig llm) { this.llm = llm; }

    /**
     * 大模型配置内部类
     */
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
}
