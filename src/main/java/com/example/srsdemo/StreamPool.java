package com.example.srsdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推流连接池
 * 预先建立多个推流连接并保活，实现 0ms 启动
 */
public class StreamPool {
    private static final Logger logger = LoggerFactory.getLogger(StreamPool.class);
    private static final int POOL_SIZE = 4;
    
    private final BlockingQueue<StreamConnection> availableConnections = new LinkedBlockingQueue<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread keepAliveThread;

    // 单个连接的包装类
    public static class StreamConnection {
        public final String streamId;
        public final String rtmpUrl;
        public final RTMPPusher pusher;
        private volatile boolean inUse = false;
        private long lastUsedTime = System.currentTimeMillis();

        public StreamConnection(String streamId, String rtmpUrl, RTMPPusher pusher) {
            this.streamId = streamId;
            this.rtmpUrl = rtmpUrl;
            this.pusher = pusher;
        }
    }

    public void init() {
        if (isRunning.getAndSet(true)) {
            return;
        }

        logger.info("Initializing StreamPool with {} connections...", POOL_SIZE);

        for (int i = 1; i <= POOL_SIZE; i++) {
            try {
                String streamId = "pool_stream_" + i;
                String server = Config.getString("rtmp.server", "127.0.0.1");
                String url = "rtmp://" + server + "/live/" + streamId;
                RTMPPusher pusher = new RTMPPusher(url);
                pusher.start(); // 这里会阻塞几秒，但只会发生一次
                
                availableConnections.offer(new StreamConnection(streamId, url, pusher));
                logger.info("Stream connection ready: {}", streamId);
            } catch (Exception e) {
                logger.error("Failed to initialize stream connection", e);
            }
        }

        // 启动保活线程
        startKeepAlive();
    }

    private void startKeepAlive() {
        keepAliveThread = new Thread(() -> {
            BufferedImage blackFrame = new BufferedImage(TextToVideoEncoder.WIDTH, TextToVideoEncoder.HEIGHT, BufferedImage.TYPE_INT_RGB);
            while (isRunning.get()) {
                try {
                    for (StreamConnection conn : availableConnections) {
                        // 只对空闲的连接进行保活
                        // 正在使用的连接由业务方负责推流
                        if (!conn.inUse) {
                            try {
                                conn.pusher.pushFrame(blackFrame);
                            } catch (Exception e) {
                                logger.warn("Keep-alive failed for {}, reconnecting...", conn.streamId);
                                // 简单的重连逻辑：重启 pusher
                                try {
                                    conn.pusher.stop();
                                    conn.pusher.start();
                                } catch (Exception re) {
                                    logger.error("Reconnect failed", re);
                                }
                            }
                        }
                    }
                    // 每秒推 1 帧心跳
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "StreamPool-KeepAlive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    /**
     * 获取一个可用的连接
     * @return StreamConnection 或 null (如果超时)
     */
    public StreamConnection acquire() {
        try {
            StreamConnection conn = availableConnections.poll(5, TimeUnit.SECONDS);
            if (conn != null) {
                conn.inUse = true;
                conn.lastUsedTime = System.currentTimeMillis();
                return conn;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * 归还连接
     */
    public void release(StreamConnection conn) {
        if (conn != null) {
            conn.inUse = false;
            availableConnections.offer(conn);
            logger.info("Stream released: {}", conn.streamId);
        }
    }

    public void shutdown() {
        isRunning.set(false);
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
        }
        for (StreamConnection conn : availableConnections) {
            try {
                conn.pusher.stop();
            } catch (Exception ignored) {}
        }
    }
}
