package com.example.srsdemo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WebServerApp {
    private static final Gson gson = new Gson();
    private static final List<String> outputs = new ArrayList<>();
    private static final Map<String, OutputStream> sseClients = new ConcurrentHashMap<>();
    private static final StreamPool streamPool = new StreamPool(); // 全局连接池

    public static void main(String[] args) throws Exception {
        // 1. 先启动连接池进行预热
        streamPool.init();

        int port = 23456;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new IndexHandler());
        server.createContext("/streamChat", new StreamChatHandler());
        server.createContext("/outputs", new OutputsHandler());
        server.createContext("/events", new EventsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Server started on port " + port);
    }

    static class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("static/index.html")) {
                if (is == null) {
                    exchange.sendResponseHeaders(404, 0);
                    return;
                }
                // JDK 8 兼容的读取方式
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int nRead;
                byte[] data = new byte[1024];
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                buffer.flush();
                byte[] bytes = buffer.toByteArray();
                
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    static class StreamChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            InputStream is = exchange.getRequestBody();
            // JDK 8 兼容读取
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[1024];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            String body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            String prompt = "";
            String clientId = "";
            try {
                JsonObject obj = gson.fromJson(body, JsonObject.class);
                if (obj.has("prompt")) prompt = obj.get("prompt").getAsString();
                if (obj.has("clientId")) clientId = obj.get("clientId").getAsString();
            } catch (Exception ignored) {}
            
            if (prompt == null || prompt.trim().isEmpty()) {
                String resp = gson.toJson(Collections.singletonMap("message", "缺少有效的prompt"));
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                return;
            }
            if (clientId == null || clientId.isEmpty()) {
                String resp = gson.toJson(Collections.singletonMap("message", "缺少clientId"));
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                return;
            }
            
            String finalPrompt = prompt;
            String finalClientId = clientId;
            new Thread(() -> runStreaming(finalPrompt, finalClientId)).start();
            
            String resp = gson.toJson(Collections.singletonMap("message", "已触发推流"));
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    static class OutputsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonObject obj = new JsonObject();
            obj.add("outputs", gson.toJsonTree(outputs));
            writeJson(exchange, 200, obj);
        }
    }

    static class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String clientId = null;
            if (query != null && query.contains("clientId=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("clientId=")) {
                        clientId = param.substring(9);
                        break;
                    }
                }
            }
            
            if (clientId == null) clientId = "unknown";

            Headers h = exchange.getResponseHeaders();
            h.add("Content-Type", "text/event-stream; charset=utf-8");
            h.add("Cache-Control", "no-cache");
            h.add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);
            
            OutputStream os = exchange.getResponseBody();
            sseClients.put(clientId, os); // 注册客户端
            
            try {
                os.write("event: ready\ndata: ok\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                while (true) {
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    try {
                        os.write(":\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (IOException e) {
                        break;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                sseClients.remove(clientId);
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void runStreaming(String prompt, String clientId) {
        TextToVideoEncoder encoder = new TextToVideoEncoder();
        VideoToTextDecoder decoder = new VideoToTextDecoder();
        
        // 1. 从连接池借用连接（0ms 耗时）
        StreamPool.StreamConnection conn = streamPool.acquire();
        if (conn == null) {
            sendSseMessage("服务器繁忙，无可用推流通道", clientId);
            return;
        }
        
        // 2. 告诉前端（或日志）这次用的是哪个流
        String streamUrl = conn.rtmpUrl;
        System.out.println("Client " + clientId + " assigned stream: " + conn.streamId);
        
        RTMPPuller puller = new RTMPPuller(streamUrl);
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            sendSseMessage("缺少 DASHSCOPE_API_KEY", clientId);
            streamPool.release(conn); // 归还连接
            return;
        }
        
        DashScopeClient llmClient = new DashScopeClient(apiKey);
        
        // 4. 定义变量的位置 (提前定义以供 Lambda 使用)
        CountDownLatch latch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean hasFirstToken = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicLong lastDecodedAt = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

        try {
            // 记录拉流开始时间
            long pullStart = System.currentTimeMillis();
            System.out.println("Client " + clientId + " start pulling stream...");

            // 3. 启动拉流，等待连接成功后再触发 LLM
            puller.start(image -> {
                String text = decoder.decode(image);
                if (text != null && !text.isEmpty()) {
                    lastDecodedAt.set(System.currentTimeMillis());
                    // 明文直传：每帧即完整文本，直接透传到前端
                    System.out.println("【Client " + clientId + " 拉流解码】" + text);
                    sendSseMessage(text, clientId);
                }
            }, () -> {
                // RTMP 连接建立成功回调
                long pullConnected = System.currentTimeMillis();
                System.out.println("Client " + clientId + " puller connected, cost: " + (pullConnected - pullStart) + "ms. Starting LLM request...");
                new Thread(() -> {
                    try {
                        llmClient.streamChat(
                            prompt,
                            msg -> {
                                try {
                                    hasFirstToken.set(true);
                                    System.out.println("【Client " + clientId + " 推流发送】" + msg);
                                    java.awt.image.BufferedImage frame = encoder.encode(msg);
                                    int red = Config.getInt("redundancy.count", 3);
                                    for (int i = 0; i < red; i++) {
                                        conn.pusher.pushFrame(frame);
                                    }
                                } catch (Exception ignored) {}
                            },
                            () -> {
                                latch.countDown();
                            }
                        );
                    } catch (Exception e) {
                        latch.countDown(); // 异常也要释放 latch
                    }
                }).start();
            });
            
            // 4. 开始业务推流
            BufferedImage blackFrame = new BufferedImage(TextToVideoEncoder.WIDTH, TextToVideoEncoder.HEIGHT, BufferedImage.TYPE_INT_RGB);
            
            // 启动一个填补空窗期的线程，在 LLM 返回第一个 token 前持续推黑帧，防止流断开
            Thread gapFiller = new Thread(() -> {
                while (!hasFirstToken.get()) {
                    try {
                        conn.pusher.pushFrame(blackFrame);
                        TimeUnit.MILLISECONDS.sleep(166);
                    } catch (Exception e) {
                        break;
                    }
                }
            });
            gapFiller.start();

            // llmClient.streamChat 已经移动到 puller 的回调中执行
            
            // 等待 LLM 生成完成
            try {
                latch.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 基于空闲时间的排空停流：等待最近一次成功解码后的空闲窗口再停止
            long doneAt = System.currentTimeMillis();
            int idleGapMs = Config.getInt("drain.idle_gap_ms", 800);
            int maxWaitMs = Config.getInt("drain.max_wait_ms", 6000);
            while ((System.currentTimeMillis() - lastDecodedAt.get()) < idleGapMs
                    && (maxWaitMs <= 0 || (System.currentTimeMillis() - doneAt) < maxWaitMs)) {
                try {
                    conn.pusher.pushFrame(blackFrame); // 保持通路存活
                    TimeUnit.MILLISECONDS.sleep(166);
                } catch (Exception ignored) {
                    break;
                }
            }
            
        } catch (Exception ignored) {
        } finally {
            try {
                puller.stop();
                streamPool.release(conn);
            } catch (Exception ignored) {}
        }
    }

    private static String getApiKey() {
        String v = Config.getString("api.key", "");
        if (v.isEmpty()) v = System.getenv("DASHSCOPE_API_KEY") == null ? "" : System.getenv("DASHSCOPE_API_KEY");
        return v;
    }

    private static void sendSseMessage(String text, String clientId) {
        OutputStream os = sseClients.get(clientId);
        if (os == null) return;
        
        // 确保 SSE 数据是单行的
        String safeText = text.replace("\r", "").replace("\n", "");
        String payload = "data: " + safeText + "\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try {
            os.write(bytes);
            os.flush();
        } catch (IOException e) {
            sseClients.remove(clientId);
            try {
                os.close();
            } catch (IOException ignored) {}
        }
    }

    private static String readBody(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, int status, JsonObject obj) throws IOException {
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        Headers h = exchange.getResponseHeaders();
        h.add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static JsonObject message(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("message", msg);
        return o;
    }
}
