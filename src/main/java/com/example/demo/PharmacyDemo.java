package com.example.demo;

/**
 * 药店场景演示 - 模拟顾客与销售员的对话
 */
public class PharmacyDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║            药店智能销售助手 - 实时对话演示               ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        // 场景1：简单购药
        printDialog("0:", "你好，我想买点退烧药");
        printDialog("1:", "好的，请问您发烧多少度？有过敏史吗？");

        // 场景2：缺药推荐替代
        printDialog("0:", "有布洛芬吗？");
        printDialog("1:", "抱歉，布洛芬暂时缺货");


        // 场景3：复杂症状
        printDialog("0:", "我有点咳嗽，还有痰");
        printDialog("1:", "咳嗽多久了？痰是什么颜色的？");


          System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    private static void printDialog(String speaker, String text) {
        System.out.println("【语音转写: 说话人" + speaker + "】: " + text + "\n");
    }

    private static void printAIReply(String content) {
        System.out.println("【AI回复】");
        System.out.println(wrapText(content, 56));
        System.out.println();
        System.out.println("-----------------------------------------------------------\n");
    }

    private static String wrapText(String text, int maxLineLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        StringBuilder line = new StringBuilder();

        for (char c : text.toCharArray()) {
            line.append(c);
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

    private static int getDisplayWidth(String str) {
        int width = 0;
        for (char c : str.toCharArray()) {
            width += (c >= 0x4e00 && c <= 0x9fff) ? 2 : 1;
        }
        return width;
    }
}
