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

    /**
     * 获取从上一次获取后新增的对话轮次
     * @param lastCount 上次获取时的轮数
     * @return 新增的对话列表
     */
    java.util.List<String> getNewTurns(int lastCount);

    /**
     * 获取所有对话轮次
     */
    java.util.List<String> getAllTurns();
}
