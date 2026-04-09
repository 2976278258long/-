package com.example.srsdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SRS LLM 流式输出演示主程序
 */
public class SrsLlmStreamDemo {
    private static final Logger logger = LoggerFactory.getLogger(SrsLlmStreamDemo.class);
    
    // SRS 服务器地址和流名称
    private static final String SRS_SERVER = Config.getString("rtmp.server", "127.0.0.1");
    private static final String STREAM_URL = "rtmp://" + SRS_SERVER + "/live/llm_stream";

    public static void main(String[] args) {
        TextToVideoEncoder encoder = new TextToVideoEncoder();
        VideoToTextDecoder decoder = new VideoToTextDecoder();
        
        RTMPPusher pusher = new RTMPPusher(STREAM_URL);
        RTMPPuller puller = new RTMPPuller(STREAM_URL);

        try {

            // 1. 启动推流客户端
            logger.info("Starting Pusher..."+System.currentTimeMillis());
            pusher.start();
            
            // 等待推流建立稳定（SRS 接收并初始化流）
            logger.info("Waiting for stream to stabilize...");

            // 2. 启动拉流客户端，注册解码回调
            logger.info("Starting Puller...");
            puller.start(image -> {
                String text = decoder.decode(image);
                if (text != null && !text.isEmpty()) {
                    System.out.println("[拉流解码还原] >>> " + text);
                }
            });

            // 预热：推送 2 秒黑帧，确保拉流端已就绪
            logger.info("Warming up stream...");
            // BufferedImage blackFrame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
            // for (int i = 0; i < 1; i++) {
            //     pusher.pushFrame(blackFrame);
            // }
            System.out.println(System.currentTimeMillis());
            // 3. 接入真实大模型流式输出 (阿里通义千问)
            String apiKey = Config.getString("api.key", "");
            if (apiKey.isEmpty()) {
                String env = System.getenv("DASHSCOPE_API_KEY");
                if (env != null) apiKey = env;
            }
            DashScopeClient llmClient = new DashScopeClient(apiKey);
            CountDownLatch latch = new CountDownLatch(1);
            logger.info("Connecting to DashScope (Qwen-plus)...");
            llmClient.streamChat("请写一段关于人工智能未来的短评，50字以内。", 
                msg -> {
                    try {
                        logger.info("[大模型实时输出]: {}", msg);
                        // 编码并推送帧
                        BufferedImage frame = encoder.encode(msg);
                        // 每个文字/片段推送 10 帧以保证采样稳定性
                        int red = Config.getInt("redundancy.count", 1);
                        for (int i = 0; i < red; i++) {
                            pusher.pushFrame(frame);
                        }
                    } catch (Exception e) {
                        logger.error("Error pushing LLM frame: {}", e.getMessage());
                    }
                }, 
                () -> {
                    logger.info("LLM stream completed.");
                    latch.countDown();
                }
            );


            // 保持运行一段时间以确保所有帧都被拉取和处理
            logger.info("Waiting for final processing...");
            TimeUnit.SECONDS.sleep(3);
        } catch (Exception e) {
            logger.error("Demo error: {}", e.getMessage(), e);
        } finally {
            try {
                // 主动关闭 LLM 客户端，清理 OkHttp 线程池与连接
                // 注意：确保 llmClient 在作用域内已创建
                puller.stop();
                pusher.stop();
            } catch (Exception e) {
                // ignore
            }
            logger.info("Demo finished.");
        }
    }
}
