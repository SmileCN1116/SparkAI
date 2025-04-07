package com.example.demo.Controller;

import com.example.demo.Exception.CustomException;
import com.example.demo.Service.SparkChatService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/SparkAPI")
public class SparkChatController {

    @Resource
    private SparkChatService sparkChatService;

    /**
     * 测试API连通性
     * @return 标准JSON响应
     */
    @GetMapping("/testConnection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean isConnected = sparkChatService.testConnection();
            response.put("status", !isConnected ? 200 : 500);
            response.put("message", !isConnected ? "连接正常" : "连接失败");
            response.put("data", !isConnected);
            System.out.println("测试连接结果: " + (!isConnected ? "成功" : "失败"));
        } catch (Exception e) {
            System.err.println("测试连接异常: " + e.getMessage());
            throw new CustomException("500", "连接测试异常");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 与星火大模型对话 (SSE流式响应)
     * @param question 用户问题
     * @return SSE流
     */
    @GetMapping(value = "/chatStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String question) {
        String streamId = UUID.randomUUID().toString();
        System.out.println("创建SSE连接: " + streamId + " 问题: " + question);

        SseEmitter emitter = new SseEmitter(60_000L);

        emitter.onTimeout(() -> {
            System.out.println("SSE连接超时: " + streamId);
            emitter.complete();
        });

        emitter.onCompletion(() ->
                System.out.println("SSE连接完成: " + streamId)
        );

        emitter.onError(ex -> {
            System.err.println("SSE错误[" + streamId + "]: " + ex.getMessage());
            ex.printStackTrace();
        });

        try {
            sparkChatService.chatWithSparkStream(question, streamId, emitter);
        } catch (Exception e) {
            System.err.println("处理SSE请求失败: " + e.getMessage());
            e.printStackTrace();
            emitter.completeWithError(new CustomException("500", "处理请求失败"));
        }

        return emitter;
    }

    /**
     * 获取对话历史
     * @return 历史对话列表
     */
    @GetMapping("/getHistory")
    public ResponseEntity<List<SparkChatService.RoleContent>> getHistory() {
        try {
            List<SparkChatService.RoleContent> history = sparkChatService.getChatHistory();
            System.out.println("获取历史记录，数量: " + history.size());
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            System.err.println("获取历史记录失败: " + e.getMessage());
            throw new CustomException("500", "获取历史记录失败");
        }
    }

    /**
     * 清空对话历史
     */
    @DeleteMapping("/delHistory")
    public ResponseEntity<Void> clearHistory() {
        try {
            sparkChatService.clearChatHistory();
            System.out.println("已清空对话历史");
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            System.err.println("清空历史记录失败: " + e.getMessage());
            throw new CustomException("500", "清空历史记录失败");
        }
    }
}