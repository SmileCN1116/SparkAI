package com.example.demo.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import com.example.demo.Config.SparkConfig;
import jakarta.annotation.Resource;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SparkChatService {

    @Resource
    private SparkConfig sparkConfig;
    @Resource
    private OkHttpClient okHttpClient;

    private final List<RoleContent> historyList = new CopyOnWriteArrayList<>();
    private final Map<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();

    public void chatWithSparkStream(String question, String streamId, SseEmitter emitter) {
        sseEmitters.put(streamId, emitter);

        // 添加用户消息到历史
        RoleContent userMessage = new RoleContent();
        userMessage.role = "user";
        userMessage.content = question;
        historyList.add(userMessage);

        // 创建AI消息占位
        RoleContent assistantMessage = new RoleContent();
        assistantMessage.role = "assistant";
        assistantMessage.content = "";
        historyList.add(assistantMessage);

        emitter.onCompletion(() -> {
            sseEmitters.remove(streamId);
            System.out.println("SSE连接完成: " + streamId);
        });

        emitter.onTimeout(() -> {
            sseEmitters.remove(streamId);
            System.out.println("SSE连接超时: " + streamId);
        });

        emitter.onError((e) -> {
            sseEmitters.remove(streamId);
            System.out.println("SSE连接错误: " + e.getMessage());
        });

        try {
            String authUrl = getAuthUrl(sparkConfig.getHostUrl(), sparkConfig.getApiKey(), sparkConfig.getApiSecret());
            String url = authUrl.replace("http://", "ws://").replace("https://", "wss://");

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            okHttpClient.newWebSocket(request, new SparkWebSocketListener(streamId, question));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    public List<RoleContent> getChatHistory() {
        return new ArrayList<>(historyList);
    }

    public void clearChatHistory() {
        historyList.clear();
    }

    public boolean testConnection() {
        try {
            String host = new URL(sparkConfig.getHostUrl()).getHost();
            Request request = new Request.Builder()
                    .url("https://" + host)
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            System.err.println("连接测试失败: " + e.getMessage());
            return false;
        }
    }

    private boolean canAddHistory() {
        int historyLength = historyList.stream()
                .mapToInt(rc -> rc.content.length())
                .sum();
        if (historyLength > 12000) {
            historyList.subList(0, Math.min(5, historyList.size())).clear();
            return false;
        }
        return true;
    }

    private String getAuthUrl(String hostUrl, String apiKey, String apiSecret) throws Exception {
        URL url = new URL(hostUrl);
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());

        String preStr = "host: " + url.getHost() + "\n" +
                "date: " + date + "\n" +
                "GET " + url.getPath() + " HTTP/1.1";

        Mac mac = Mac.getInstance("hmacsha256");
        SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "hmacsha256");
        mac.init(spec);

        byte[] hexDigits = mac.doFinal(preStr.getBytes(StandardCharsets.UTF_8));
        String sha = Base64.getEncoder().encodeToString(hexDigits);

        String authorization = String.format("api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"",
                apiKey, "hmac-sha256", "host date request-line", sha);

        HttpUrl httpUrl = Objects.requireNonNull(HttpUrl.parse("https://" + url.getHost() + url.getPath()))
                .newBuilder()
                .addQueryParameter("authorization", Base64.getEncoder().encodeToString(authorization.getBytes(StandardCharsets.UTF_8)))
                .addQueryParameter("date", date)
                .addQueryParameter("host", url.getHost())
                .build();

        return httpUrl.toString();
    }

    private class SparkWebSocketListener extends WebSocketListener {
        private final String streamId;
        private final String question;
        private WebSocket webSocket;

        public SparkWebSocketListener(String streamId, String question) {
            this.streamId = streamId;
            this.question = question;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            this.webSocket = webSocket;
            try {
                JSONObject requestJson = new JSONObject();

                // Header
                JSONObject header = new JSONObject();
                header.put("app_id", sparkConfig.getAppid());
                header.put("uid", UUID.randomUUID().toString().substring(0, 10));

                // Parameter
                JSONObject parameter = new JSONObject();
                JSONObject chat = new JSONObject();
                chat.put("domain", sparkConfig.getDomain());
                chat.put("temperature", 0.5);
                chat.put("max_tokens", 8192);
                chat.put("auditing", "default");
                parameter.put("chat", chat);

                // Payload
                JSONObject payload = new JSONObject();
                JSONObject message = new JSONObject();
                JSONArray text = new JSONArray();

                // History
                if (!historyList.isEmpty()) {
                    for (RoleContent tempRoleContent : historyList) {
                        if ("user".equals(tempRoleContent.role)) {
                            text.add(JSON.toJSON(tempRoleContent));
                        }
                    }
                }

                // New question
                RoleContent roleContent = new RoleContent();
                roleContent.role = "user";
                roleContent.content = question;
                text.add(JSON.toJSON(roleContent));

                message.put("text", text);
                payload.put("message", message);

                requestJson.put("header", header);
                requestJson.put("parameter", parameter);
                requestJson.put("payload", payload);

                String requestStr = requestJson.toJSONString();
                webSocket.send(requestStr);
            } catch (Exception e) {
                sendErrorToClient("WebSocket初始化失败: " + e.getMessage());
                webSocket.close(1000, "Initialization failed");
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonParse response = JSON.parseObject(text, JsonParse.class);

                if (response == null || response.getHeader() == null) {
                    sendErrorToClient("无效的API响应格式");
                    return;
                }

                if (response.getHeader().getCode() != 0) {
                    String errorMsg = String.format("API错误: code=%d, message=%s",
                            response.getHeader().getCode(),
                            response.getHeader().getMessage());
                    sendErrorToClient(errorMsg);
                    return;
                }

                if (response.getPayload() != null &&
                        response.getPayload().getChoices() != null &&
                        response.getPayload().getChoices().getText() != null) {

                    for (Text content : response.getPayload().getChoices().getText()) {
                        if (content.getContent() != null) {
                            sendChunkToClient(content.getContent());
                        }
                    }
                }

                if (response.getHeader().getStatus() == 2) {
                    completeClientStream();
                    webSocket.close(1000, "");
                }
            } catch (Exception e) {
                sendErrorToClient("处理API响应时出错: " + e.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            String errorMsg = "WebSocket连接失败: " + t.getMessage();
            if (response != null) {
                errorMsg += ", 状态码: " + response.code();
            }
            sendErrorToClient(errorMsg);
        }

        private void sendChunkToClient(String chunk) {
            SseEmitter emitter = sseEmitters.get(streamId);
            if (emitter != null) {
                try {
                    // 更新最后一条AI消息内容
                    if (!historyList.isEmpty()) {
                        RoleContent lastMessage = historyList.get(historyList.size() - 1);
                        if ("assistant".equals(lastMessage.role)) {
                            lastMessage.content += chunk;
                        }
                    }

                    Map<String, Object> data = new HashMap<>();
                    data.put("content", chunk);
                    data.put("finished", false);

                    emitter.send(SseEmitter.event()
                            .id(streamId)
                            .name("message")
                            .data(data));
                } catch (Exception e) {
                    emitter.completeWithError(e);
                    sseEmitters.remove(streamId);
                }
            }
        }

        private void completeClientStream() {
            SseEmitter emitter = sseEmitters.get(streamId);
            if (emitter != null) {
                try {
                    Map<String, Object> data = new HashMap<>();
                    data.put("content", "");
                    data.put("finished", true);

                    emitter.send(SseEmitter.event()
                            .id(streamId)
                            .name("complete")
                            .data(data));

                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                } finally {
                    sseEmitters.remove(streamId);
                }
            }
        }

        private void sendErrorToClient(String errorMsg) {
            SseEmitter emitter = sseEmitters.get(streamId);
            if (emitter != null) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(streamId)
                            .name("error")
                            .data(errorMsg));

                    emitter.completeWithError(new RuntimeException(errorMsg));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    sseEmitters.remove(streamId);
                }
            }
        }
    }

    public static class RoleContent {
        public String role;
        public String content;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    private static class JsonParse {
        private Header header;
        private Payload payload;

        public Header getHeader() { return header; }
        public void setHeader(Header header) { this.header = header; }
        public Payload getPayload() { return payload; }
        public void setPayload(Payload payload) { this.payload = payload; }
    }

    private static class Header {
        private int code;
        private String message;
        private String sid;
        private int status;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSid() { return sid; }
        public void setSid(String sid) { this.sid = sid; }
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
    }

    private static class Payload {
        private Choices choices;
        private Usage usage;

        public Choices getChoices() { return choices; }
        public void setChoices(Choices choices) { this.choices = choices; }
        public Usage getUsage() { return usage; }
        public void setUsage(Usage usage) { this.usage = usage; }
    }

    private static class Choices {
        private int status;
        private int seq;
        private List<Text> text;

        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }
        public List<Text> getText() { return text; }
        public void setText(List<Text> text) { this.text = text; }
    }

    private static class Text {
        private String role;
        private String content;
        private int index;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
    }

    private static class Usage {
        @JSONField(name = "text")
        private TextUsage textUsage;

        public TextUsage getTextUsage() { return textUsage; }
        public void setTextUsage(TextUsage textUsage) { this.textUsage = textUsage; }
    }

    private static class TextUsage {
        private int question_tokens;
        private int prompt_tokens;
        private int completion_tokens;
        private int total_tokens;

        public int getQuestion_tokens() { return question_tokens; }
        public void setQuestion_tokens(int question_tokens) { this.question_tokens = question_tokens; }
        public int getPrompt_tokens() { return prompt_tokens; }
        public void setPrompt_tokens(int prompt_tokens) { this.prompt_tokens = prompt_tokens; }
        public int getCompletion_tokens() { return completion_tokens; }
        public void setCompletion_tokens(int completion_tokens) { this.completion_tokens = completion_tokens; }
        public int getTotal_tokens() { return total_tokens; }
        public void setTotal_tokens(int total_tokens) { this.total_tokens = total_tokens; }
    }
}