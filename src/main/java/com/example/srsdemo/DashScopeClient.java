package com.example.srsdemo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 阿里通义千问 (DashScope) 客户端，支持 SSE 流式输出
 */
public class DashScopeClient {
    private static final Logger logger = LoggerFactory.getLogger(DashScopeClient.class);
    private static final Gson gson = new Gson();

    private final String apiKey;
    private final String apiUrl = Config.getString("api.url", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
    private final OkHttpClient client;

    public DashScopeClient(String apiKey) {
        this.apiKey = apiKey;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // 流式读取不设置超时
                .build();
    }

    public void streamChat(String prompt, Consumer<String> contentHandler, Runnable onComplete) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", Config.getString("api.model", "qwen-plus"));
        payload.addProperty("stream", true);
        payload.addProperty("enable_thinking", true);
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);
        payload.add("messages", messages);

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .build();

        EventSource.Factory factory = EventSources.createFactory(client);
        factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                if ("[DONE]".equals(data)) {
                    contentHandler.accept(data);
                    onComplete.run();
                    return;
                }
                contentHandler.accept(data);
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                String errorMsg = "SSE connection failed";
                if (response != null) {
                    errorMsg += ". Response Code: " + response.code() + ", Message: " + response.message();
                    try {
                        if (response.body() != null) {
                            errorMsg += ", Body: " + response.body().string();
                        }
                    } catch (Exception ignored) {}
                }
                logger.error(errorMsg, t);
                onComplete.run();
            }

            @Override
            public void onClosed(EventSource eventSource) {
                logger.info("SSE connection closed normally. Request: {} {}", eventSource.request().method(), eventSource.request().url());
                onComplete.run();
            }
        });
    }
}
