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
