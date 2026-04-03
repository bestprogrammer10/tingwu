package com.example.demo.controller;

import com.aliyun.tingwu20230930.models.CreateTaskResponseBody;
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
 * 听悟实时转写接口 - 支持WebSocket音频推送
 */
@RestController
@RequestMapping("/api/tingwu")
public class TingwuRealtimeController {

    private static final Logger logger = LoggerFactory.getLogger(TingwuRealtimeController.class);

    private final TingwuService tingwuService;
    private final TingwuWebSocketService webSocketService;

    @Autowired
    public TingwuRealtimeController(TingwuService tingwuService,
                                    TingwuWebSocketService webSocketService) {
        this.tingwuService = tingwuService;
        this.webSocketService = webSocketService;
    }

    /**
     * 创建实时任务并建立 WebSocket 连接
     *
     * POST /api/tingwu/realtime/start
     * {
     *   "sourceLanguage": "cn",
     *   "format": "pcm",
     *   "sampleRate": 16000
     * }
     */
    @PostMapping("/realtime/start")
    public ResponseEntity<?> startRealtimeTranscription(
            @RequestBody(required = false) Map<String, Object> params) {

        // 1. 创建实时任务
        String sourceLanguage = params != null && params.get("sourceLanguage") != null
                ? (String) params.get("sourceLanguage") : "cn";
        String format = params != null && params.get("format") != null
                ? (String) params.get("format") : "pcm";
        int sampleRate = params != null && params.get("sampleRate") != null
                ? (Integer) params.get("sampleRate") : 16000;

        logger.info("创建实时转写任务并建立WebSocket连接");

        CreateTaskResponseBody.CreateTaskResponseBodyData data =
                tingwuService.createRealtimeTask(sourceLanguage, format, sampleRate);

        if (data == null) {
            return ResponseEntity.badRequest().body("创建任务失败");
        }

        // 2. 建立 WebSocket 连接
        boolean connected = webSocketService.connect(
                data.taskId,
                data.meetingJoinUrl,
                null  // MeetingToken 如需要可从这里获取
        );

        if (!connected) {
            return ResponseEntity.badRequest().body("WebSocket连接失败");
        }

        // 3. 开始从麦克风采集音频
        boolean captureStarted = webSocketService.startMicrophoneCapture(data.taskId);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", data.taskId);
        result.put("taskKey", data.taskKey);
        result.put("taskStatus", data.taskStatus);
        result.put("webSocketConnected", connected);
        result.put("microphoneCaptureStarted", captureStarted);

        return ResponseEntity.ok(result);
    }

    /**
     * 停止实时转写
     * POST /api/tingwu/realtime/{taskId}/stop
     */
    @PostMapping("/realtime/{taskId}/stop")
    public ResponseEntity<?> stopRealtimeTranscription(@PathVariable String taskId) {
        logger.info("停止实时转写，任务ID: {}", taskId);

        // 1. 断开 WebSocket 连接（内部会停止麦克风采集）
        webSocketService.disconnect(taskId);

        // 2. 结束听悟任务
        boolean stopped = tingwuService.stopRealtimeTask(taskId);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("stopped", stopped);

        return ResponseEntity.ok(result);
    }

    /**
     * 检查 WebSocket 连接状态
     * GET /api/tingwu/realtime/{taskId}/status
     */
    @GetMapping("/realtime/{taskId}/status")
    public ResponseEntity<?> getConnectionStatus(@PathVariable String taskId) {
        boolean connected = webSocketService.isConnected(taskId);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("webSocketConnected", connected);

        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有连接的任务
     * GET /api/tingwu/realtime/connections
     */
    @GetMapping("/realtime/connections")
    public ResponseEntity<?> getAllConnections() {
        return ResponseEntity.ok(webSocketService.getConnectedTasks());
    }
}
