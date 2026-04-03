package com.example.demo.service.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 控制台输出服务 - Demo阶段替代WebSocket推送（支持流式输出）
 */
@Service
public class ConsoleOutputService {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleOutputService.class);
    private static final int LINE_WIDTH = 60;
    private static final String SEPARATOR_LINE = repeat("=", LINE_WIDTH);
    private static final String SEPARATOR_DASH = repeat("-", LINE_WIDTH);

    private static String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    /**
     * 构建头部信息
     */
    private StringBuilder buildHeader(String taskId, String speaker, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(SEPARATOR_LINE).append("\n");
        sb.append("【实时对话分析】任务ID: ").append(taskId).append("\n");
        sb.append(SEPARATOR_DASH).append("\n");
        sb.append("【说话人】").append(speaker).append("\n");
        sb.append("【内容】").append(text).append("\n");
        sb.append(SEPARATOR_DASH).append("\n");
        return sb;
    }

    /**
     * 在控制台打印分析结果（非流式，完整结果）
     */
    public void printAnalysis(String taskId, String speaker, String text, AnalysisResult result) {
        StringBuilder sb = buildHeader(taskId, speaker, text);

        if (result.isSuccess()) {
            sb.append("【分析结果】\n");
            appendResultFields(sb, result);
        } else {
            sb.append("【分析失败】").append(result.getErrorMessage()).append("\n");
        }

        sb.append(SEPARATOR_LINE).append("\n");

        System.out.println(sb.toString());
        logger.info("任务[{}] 分析完成: {}", taskId, result);
    }

    /**
     * 追加结果字段到 StringBuilder
     */
    private void appendResultFields(StringBuilder sb, AnalysisResult result) {
        sb.append("  顾客需求: ").append(result.getCustomerNeed()).append("\n");
        sb.append("  推荐类别: ").append(result.getRecommendedCategory()).append("\n");
        sb.append("  紧急程度: ").append(result.getUrgency()).append("\n");
        sb.append("  置信度: ").append(String.format("%.2f", result.getConfidence())).append("\n");

        if (result.getFollowUpQuestions() != null && !result.getFollowUpQuestions().isEmpty()) {
            sb.append("  建议追问:\n");
            for (String q : result.getFollowUpQuestions()) {
                sb.append("     - ").append(q).append("\n");
            }
        }
    }

    /**
     * 开始输出（打印语音转写内容）
     */
    public void printStreamStart(String taskId, String speaker, String text) {
        System.out.println("\n【语音转写: 说话人" + speaker + "】: " + text);
    }

    /**
     * 收集chunk（非流式，静默收集）
     * @param chunk 流式内容片段
     */
    public void printStreamChunk(String chunk) {
        // 静默收集，不实时输出
    }

    /**
     * 结束输出
     */
    public void printStreamEnd() {
        // 静默处理
    }

    /**
     * 打印AI完整回复（自动换行，每80字符）
     * @param content AI回复的完整内容
     */
    public void printStreamResult(String content) {
        System.out.println("【AI回复】");
        // 过滤掉think标签及其内容
        String filtered = filterThinkTags(content);
        // 自动换行，每行最多80字符
        String formatted = wrapText(filtered, 80);
        System.out.println(formatted);
        System.out.println();
    }

    /**
     * 过滤think标签及其内容
     */
    private String filterThinkTags(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 移除 <think>...</think> 及其内容（支持多行）
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    /**
     * 文本自动换行
     */
    private String wrapText(String text, int maxLineLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        StringBuilder line = new StringBuilder();

        for (char c : text.toCharArray()) {
            line.append(c);
            // 中文占2字符宽度，英文占1字符
            int width = getDisplayWidth(line.toString());
            if (width >= maxLineLength) {
                result.append(line.toString().trim()).append("\n");
                line = new StringBuilder();
            }
        }
        if (line.length() > 0) {
            result.append(line.toString().trim());
        }
        return result.toString();
    }

    /**
     * 计算字符串显示宽度（中文2，英文1）
     */
    private int getDisplayWidth(String str) {
        int width = 0;
        for (char c : str.toCharArray()) {
            width += (c >= 0x4e00 && c <= 0x9fff) ? 2 : 1;
        }
        return width;
    }

    /**
     * 打印系统状态
     */
    public void printStatus(String message) {
        System.out.println("\n>>> " + message + "\n");
    }
}
