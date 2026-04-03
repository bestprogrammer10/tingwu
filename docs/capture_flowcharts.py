#!/usr/bin/env python3
"""
流程图截图工具
将HTML中的两个流程图分别导出为1920x1080的PNG图片
"""

import asyncio
from playwright.async_api import async_playwright


async def capture_flowcharts():
    async with async_playwright() as p:
        # 启动浏览器
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page()

        # 设置视口大小
        await page.set_viewport_size({"width": 1920, "height": 1080})

        # 加载HTML文件
        await page.goto("file:///D:/tingwu/docs/flowcharts.html")

        # 等待页面加载完成
        await page.wait_for_load_state("networkidle")
        await asyncio.sleep(2)  # 额外等待确保渲染完成

        # 截图第一个流程图 - 语音实时识别服务
        flow1 = await page.query_selector("#flow1")
        if flow1:
            await flow1.screenshot(
                path="D:/tingwu/docs/flow-voice-recognition.png", type="png"
            )
            print("✓ 已导出: flow-voice-recognition.png")

        # 截图第二个流程图 - 语义分析服务
        flow2 = await page.query_selector("#flow2")
        if flow2:
            await flow2.screenshot(
                path="D:/tingwu/docs/flow-semantic-analysis.png", type="png"
            )
            print("✓ 已导出: flow-semantic-analysis.png")

        await browser.close()
        print("\n所有流程图已成功导出！")


if __name__ == "__main__":
    asyncio.run(capture_flowcharts())
