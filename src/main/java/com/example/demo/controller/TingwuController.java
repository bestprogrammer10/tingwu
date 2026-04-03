package com.example.demo.controller;

import com.aliyun.tingwu20230930.models.CreateTaskResponseBody;
import com.aliyun.tingwu20230930.models.GetTaskInfoResponseBody;
import com.example.demo.service.TingwuService;
import com.example.demo.service.TingwuWebSocketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 听悟测试接口
 */
@RestController
@RequestMapping("/api/tingwu")
public class TingwuController {

    private static final Logger logger = LoggerFactory.getLogger(TingwuController.class);

    private final TingwuService tingwuService;
    private final TingwuWebSocketService webSocketService;

    @Autowired
    public TingwuController(TingwuService tingwuService,
                            TingwuWebSocketService webSocketService) {
        this.tingwuService = tingwuService;
        this.webSocketService = webSocketService;
    }

    /**
     * 创建实时转写任务
     */
    @PostMapping("/realtime/task")
    public ResponseEntity<?> createRealtimeTask(
            @RequestBody(required = false) Map<String, Object> params) {

        String sourceLanguage = params != null && params.get("sourceLanguage") != null
                ? (String) params.get("sourceLanguage") : "cn";
        String format = params != null && params.get("format") != null
                ? (String) params.get("format") : "pcm";
        int sampleRate = params != null && params.get("sampleRate") != null
                ? (Integer) params.get("sampleRate") : 16000;

        logger.info("创建实时转写任务，语言: {}, 格式: {}, 采样率: {}",
                sourceLanguage, format, sampleRate);

        CreateTaskResponseBody.CreateTaskResponseBodyData data = tingwuService.createRealtimeTask(
                sourceLanguage, format, sampleRate);

        if (data == null) {
            return ResponseEntity.badRequest().body("创建任务失败");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", data.taskId);
        result.put("meetingJoinUrl", data.meetingJoinUrl);
        result.put("taskKey", data.taskKey);
        result.put("taskStatus", data.taskStatus);

        return ResponseEntity.ok(result);
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<?> getTaskInfo(@PathVariable String taskId) {
        logger.info("查询任务状态，TaskId: {}", taskId);

        GetTaskInfoResponseBody.GetTaskInfoResponseBodyData data = tingwuService.getTaskInfo(taskId);

        if (data == null) {
            return ResponseEntity.badRequest().body("查询任务失败");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", data.taskId);
        result.put("taskStatus", data.taskStatus);
        result.put("result", data.result);

        return ResponseEntity.ok(result);
    }

    /**
     * 结束实时转写任务
     */
    @PostMapping("/task/{taskId}/stop")
    public ResponseEntity<?> stopRealtimeTask(@PathVariable String taskId) {
        logger.info("结束实时转写任务，TaskId: {}", taskId);

        // 1. 先断开 WebSocket 连接和麦克风（如果存在）
        webSocketService.disconnect(taskId);

        // 2. 结束听悟任务
        boolean success = tingwuService.stopRealtimeTask(taskId);

        if (!success) {
            return ResponseEntity.badRequest().body("结束任务失败");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "STOPPED");

        return ResponseEntity.ok(result);
    }
}
