package com.example.demo.service;

import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.tingwu20230930.Client;
import com.aliyun.tingwu20230930.models.CreateTaskRequest;
import com.aliyun.tingwu20230930.models.CreateTaskResponse;
import com.aliyun.tingwu20230930.models.CreateTaskResponseBody;
import com.aliyun.tingwu20230930.models.GetTaskInfoResponse;
import com.aliyun.tingwu20230930.models.GetTaskInfoResponseBody;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.demo.config.TingwuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 听悟服务 - 封装实时转写任务接口 (Tea SDK 版)
 */
@Service
public class TingwuService {

    private static final Logger logger = LoggerFactory.getLogger(TingwuService.class);

    private final TingwuProperties tingwuProperties;
    private final Client client;
    private final ObjectMapper objectMapper;

    @Autowired
    public TingwuService(TingwuProperties tingwuProperties) throws Exception {
        this.tingwuProperties = tingwuProperties;
        this.client = createClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 创建阿里云听悟客户端 (Tea SDK)
     */
    private Client createClient() throws Exception {
        Config config = new Config()
                .setAccessKeyId(tingwuProperties.getAccessKeyId())
                .setAccessKeySecret(tingwuProperties.getAccessKeySecret());
        config.endpoint = tingwuProperties.getEndpoint();
        return new Client(config);
    }

    /**
     * 创建实时转写任务
     */
    public CreateTaskResponseBody.CreateTaskResponseBodyData createRealtimeTask(
            String sourceLanguage, String format, int sampleRate) {

        // 构建 Input
        CreateTaskRequest.CreateTaskRequestInput input = new CreateTaskRequest.CreateTaskRequestInput()
                .setSourceLanguage(sourceLanguage)
                .setFormat(format)
                .setSampleRate(sampleRate);

        // 构建说话人分离配置（2人对话）
        CreateTaskRequest.CreateTaskRequestParametersTranscriptionDiarization diarization =
                new CreateTaskRequest.CreateTaskRequestParametersTranscriptionDiarization()
                        .setSpeakerCount(2);

        // 构建身份识别配置（销售员 vs 顾客）
        CreateTaskRequest.CreateTaskRequestParametersIdentityRecognition identityRecognition =
                new CreateTaskRequest.CreateTaskRequestParametersIdentityRecognition()
                        .setSceneIntroduction("药店销售场景，销售员和顾客的对话")
                        .setIdentityContents(Arrays.asList(
                                new CreateTaskRequest.CreateTaskRequestParametersIdentityRecognitionIdentityContents()
                                        .setName("销售员")
                                        .setDescription("药店销售人员，主动介绍药品、回答顾客咨询、提供用药建议"),
                                new CreateTaskRequest.CreateTaskRequestParametersIdentityRecognitionIdentityContents()
                                        .setName("顾客")
                                        .setDescription("前来购买药品的顾客，描述症状、询问药品信息、提出用药需求")
                        ));

        // 构建转写参数
        CreateTaskRequest.CreateTaskRequestParametersTranscription transcription =
                new CreateTaskRequest.CreateTaskRequestParametersTranscription()
                        .setDiarizationEnabled(true)
                        .setDiarization(diarization)
                        .setOutputLevel(2);

        // 构建 Parameters
        CreateTaskRequest.CreateTaskRequestParameters parameters =
                new CreateTaskRequest.CreateTaskRequestParameters()
                        .setTranscription(transcription)
                        .setIdentityRecognitionEnabled(true)
                        .setIdentityRecognition(identityRecognition);

        // 创建请求
        CreateTaskRequest request = new CreateTaskRequest()
                .setAppKey(tingwuProperties.getAppKey())
                .setType("realtime")
                .setInput(input)
                .setParameters(parameters);

        logger.info("创建实时转写任务，语言: {}, 格式: {}, 采样率: {}",
                sourceLanguage, format, sampleRate);

        try {
            Map<String, String> headers = new HashMap<>();
            RuntimeOptions runtime = new RuntimeOptions();
            CreateTaskResponse response = client.createTaskWithOptions(request, headers, runtime);

            if (response != null && response.body != null && response.body.data != null) {
                logger.info("创建任务成功，TaskId: {}, MeetingJoinUrl: {}",
                        response.body.data.taskId,
                        response.body.data.meetingJoinUrl);
                return response.body.data;
            }
            return null;
        } catch (TeaException error) {
            logger.error("创建任务失败: {}", error.getMessage());
            if (error.getData() != null && error.getData().get("Recommend") != null) {
                logger.error("诊断地址: {}", error.getData().get("Recommend"));
            }
            return null;
        } catch (Exception e) {
            logger.error("创建任务异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 查询任务状态和结果
     */
    public GetTaskInfoResponseBody.GetTaskInfoResponseBodyData getTaskInfo(String taskId) {
        logger.info("查询任务状态，TaskId: {}", taskId);

        try {
            Map<String, String> headers = new HashMap<>();
            RuntimeOptions runtime = new RuntimeOptions();
            GetTaskInfoResponse response = client.getTaskInfoWithOptions(taskId, headers, runtime);

            if (response != null && response.body != null && response.body.data != null) {
                logger.info("查询任务成功，Status: {}", response.body.data.taskStatus);
                return response.body.data;
            }
            return null;
        } catch (TeaException error) {
            logger.error("查询任务失败: {}", error.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("查询任务异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 结束实时转写任务
     */
    public boolean stopRealtimeTask(String taskId) {
        // 构建 Input（只需要 TaskId）
        CreateTaskRequest.CreateTaskRequestInput input =
                new CreateTaskRequest.CreateTaskRequestInput()
                        .setTaskId(taskId);

        // 创建请求
        CreateTaskRequest request = new CreateTaskRequest()
                .setAppKey(tingwuProperties.getAppKey())
                .setType("realtime")
                .setOperation("stop")
                .setInput(input);

        logger.info("结束实时转写任务，TaskId: {}", taskId);

        try {
            Map<String, String> headers = new HashMap<>();
            RuntimeOptions runtime = new RuntimeOptions();
            CreateTaskResponse response = client.createTaskWithOptions(request, headers, runtime);

            return response != null && response.body != null;
        } catch (TeaException error) {
            logger.error("结束任务失败: {}", error.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("结束任务异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建离线转写任务（带身份识别）
     * @param fileUrl 音频文件URL
     * @param taskKey 任务标识（可选）
     * @return 任务数据
     */
    public CreateTaskResponseBody.CreateTaskResponseBodyData createOfflineTask(String fileUrl, String taskKey) {
        // 构建 Input
        CreateTaskRequest.CreateTaskRequestInput input = new CreateTaskRequest.CreateTaskRequestInput()
                .setSourceLanguage("cn")
                .setFileUrl(fileUrl)
                .setTaskKey(taskKey != null ? taskKey : ("task_" + System.currentTimeMillis()));

        // 构建说话人分离配置（2人对话）
        CreateTaskRequest.CreateTaskRequestParametersTranscriptionDiarization diarization =
                new CreateTaskRequest.CreateTaskRequestParametersTranscriptionDiarization()
                        .setSpeakerCount(2);

        // 构建身份识别配置（销售员 vs 顾客）
        CreateTaskRequest.CreateTaskRequestParametersIdentityRecognition identityRecognition =
                new CreateTaskRequest.CreateTaskRequestParametersIdentityRecognition()
                        .setSceneIntroduction("药店销售场景，销售员和顾客的对话")
                        .setIdentityContents(Arrays.asList(
                                new CreateTaskRequest.CreateTaskRequestParametersIdentityRecognitionIdentityContents()
                                        .setName("销售员")
                                        .setDescription("药店销售人员，主动介绍药品、回答顾客咨询、提供用药建议"),
                                new CreateTaskRequest.CreateTaskRequestParametersIdentityRecognitionIdentityContents()
                                        .setName("顾客")
                                        .setDescription("前来购买药品的顾客，描述症状、询问药品信息、提出用药需求")
                        ));

        // 构建转写参数
        CreateTaskRequest.CreateTaskRequestParametersTranscription transcription =
                new CreateTaskRequest.CreateTaskRequestParametersTranscription()
                        .setDiarizationEnabled(true)
                        .setDiarization(diarization);

        // 构建 Parameters
        CreateTaskRequest.CreateTaskRequestParameters parameters =
                new CreateTaskRequest.CreateTaskRequestParameters()
                        .setTranscription(transcription)
                        .setIdentityRecognitionEnabled(true)
                        .setIdentityRecognition(identityRecognition);

        // 创建请求
        CreateTaskRequest request = new CreateTaskRequest()
                .setAppKey(tingwuProperties.getAppKey())
                .setType("offline")
                .setInput(input)
                .setParameters(parameters);

        logger.info("创建离线转写任务，文件URL: {}", fileUrl);

        try {
            Map<String, String> headers = new HashMap<>();
            RuntimeOptions runtime = new RuntimeOptions();
            CreateTaskResponse response = client.createTaskWithOptions(request, headers, runtime);

            if (response != null && response.body != null && response.body.data != null) {
                logger.info("创建离线任务成功，TaskId: {}", response.body.data.taskId);
                return response.body.data;
            }
            return null;
        } catch (TeaException error) {
            logger.error("创建离线任务失败: {}", error.getMessage());
            if (error.getData() != null && error.getData().get("Recommend") != null) {
                logger.error("诊断地址: {}", error.getData().get("Recommend"));
            }
            return null;
        } catch (Exception e) {
            logger.error("创建离线任务异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取离线任务结果（包含身份识别结果URL）
     * @param taskId 任务ID
     * @return 任务结果数据
     */
    public OfflineTaskResult getOfflineTaskResult(String taskId) {
        GetTaskInfoResponseBody.GetTaskInfoResponseBodyData taskInfo = getTaskInfo(taskId);

        if (taskInfo == null) {
            return null;
        }

        OfflineTaskResult result = new OfflineTaskResult();
        result.setTaskId(taskId);
        result.setTaskStatus(taskInfo.taskStatus);

        // 如果任务完成，提取结果URL
        if ("COMPLETED".equals(taskInfo.taskStatus) && taskInfo.result != null) {
            result.setTranscriptionUrl(taskInfo.result.getTranscription());
            result.setIdentityRecognitionUrl(taskInfo.result.getIdentityRecognition());
        }

        return result;
    }

    /**
     * 下载并解析身份识别结果
     * @param identityRecognitionUrl 身份识别结果URL
     * @return 说话人身份映射 Map<SpeakerId, Identity>
     */
    public Map<String, String> downloadIdentityRecognitionResult(String identityRecognitionUrl) {
        if (identityRecognitionUrl == null || identityRecognitionUrl.isEmpty()) {
            logger.warn("身份识别结果URL为空");
            return null;
        }

        logger.info("下载身份识别结果: {}", identityRecognitionUrl);

        HttpURLConnection connection = null;
        try {
            URL url = new URL(identityRecognitionUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                logger.error("下载失败，状态码: {}", responseCode);
                return null;
            }

            // 读取响应内容
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            }

            // 解析JSON
            JsonNode root = objectMapper.readTree(content.toString());
            JsonNode identityResults = root.path("IdentityRecognition").path("IdentityResults");

            Map<String, String> speakerIdentityMap = new HashMap<>();
            if (identityResults.isArray()) {
                for (JsonNode item : identityResults) {
                    String speakerId = item.path("SpeakerId").asText();
                    String identity = item.path("Identity").asText();
                    speakerIdentityMap.put(speakerId, identity);
                    logger.info("身份识别结果: SpeakerId={}, Identity={}", speakerId, identity);
                }
            }

            return speakerIdentityMap;

        } catch (Exception e) {
            logger.error("下载或解析身份识别结果失败: {}", e.getMessage(), e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 离线任务结果数据结构
     */
    public static class OfflineTaskResult {
        private String taskId;
        private String taskStatus;
        private String transcriptionUrl;
        private String identityRecognitionUrl;

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }

        public String getTaskStatus() { return taskStatus; }
        public void setTaskStatus(String taskStatus) { this.taskStatus = taskStatus; }

        public String getTranscriptionUrl() { return transcriptionUrl; }
        public void setTranscriptionUrl(String transcriptionUrl) { this.transcriptionUrl = transcriptionUrl; }

        public String getIdentityRecognitionUrl() { return identityRecognitionUrl; }
        public void setIdentityRecognitionUrl(String identityRecognitionUrl) { this.identityRecognitionUrl = identityRecognitionUrl; }

        @Override
        public String toString() {
            return "OfflineTaskResult{" +
                    "taskId='" + taskId + '\'' +
                    ", taskStatus='" + taskStatus + '\'' +
                    ", transcriptionUrl='" + transcriptionUrl + '\'' +
                    ", identityRecognitionUrl='" + identityRecognitionUrl + '\'' +
                    '}';
        }
    }
}
