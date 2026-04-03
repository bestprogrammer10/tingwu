package com.example.demo.service.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
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

        //logger.debug("添加对话轮次: {}, 当前总轮数: {}", turn, contextQueue.size());
    }

    @Override
    public String buildPrompt(String currentInput) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("【角色】你是一位专业的药店销售助手\n");
        prompt.append("【任务】根据顾客和销售员的对话，分析顾客的用药需求并提供智能推荐\n");

        if (!contextQueue.isEmpty()) {
            prompt.append("【历史对话】\n");
            for (String turn : contextQueue) {
                prompt.append(turn).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("【当前输入】\n");
        prompt.append(currentInput).append("\n\n");

        prompt.append("【回复要求】\n");
        prompt.append("请根据对话内容，直接给出自然语言回复：\n");
        prompt.append("1. 分析顾客的核心需求（如退烧药、感冒药等）,不要md格式,正常文本格式就可以,直接给出用户需要的药物\n");

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

    @Override
    public java.util.List<String> getNewTurns(int lastCount) {
        java.util.List<String> allTurns = new java.util.ArrayList<>(contextQueue);
        if (lastCount >= allTurns.size()) {
            return java.util.Collections.emptyList();
        }
        return allTurns.subList(lastCount, allTurns.size());
    }

    @Override
    public java.util.List<String> getAllTurns() {
        return new java.util.ArrayList<>(contextQueue);
    }
}
