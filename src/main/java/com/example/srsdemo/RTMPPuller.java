package com.example.srsdemo;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * RTMP 拉流客户端
 */
public class RTMPPuller {
    private static final Logger logger = LoggerFactory.getLogger(RTMPPuller.class);

    private String rtmpUrl;
    private FFmpegFrameGrabber grabber;
    private Java2DFrameConverter converter;
    private boolean isRunning = false;
    private Thread pullerThread;

    public RTMPPuller(String rtmpUrl) {
        this.rtmpUrl = rtmpUrl;
        this.converter = new Java2DFrameConverter();
    }

    public void start(Consumer<BufferedImage> frameHandler) {
        start(frameHandler, null);
    }

    public void start(Consumer<BufferedImage> frameHandler, Runnable onConnected) {
        isRunning = true;
        pullerThread = new Thread(() -> {
            boolean firstConnect = true;
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                FFmpegFrameGrabber localGrabber = null;
                try {
                    localGrabber = new FFmpegFrameGrabber(rtmpUrl);
                    localGrabber.setOption("rtmp_transport", "tcp");
                    localGrabber.setOption("fflags", "nobuffer"); // 关键：关闭缓冲区，配合推流端的全关键帧
                    localGrabber.setOption("flags", "low_delay"); // 启用低延迟标志
                    localGrabber.setOption("probesize", "32768"); // 32KB, default is 5MB
                    localGrabber.setOption("analyzeduration", "100000"); // 100ms, default is 5s
                    localGrabber.setOption("rw_timeout", "5000000");
                    grabber = localGrabber;
                    logger.info("Connecting to RTMP: {}...", rtmpUrl);
                    localGrabber.start();
                    logger.info("RTMP Puller connected.");

                    if (firstConnect && onConnected != null) {
                        onConnected.run();
                        firstConnect = false;
                    }

                    while (isRunning && !Thread.currentThread().isInterrupted()) {
                        Frame frame = localGrabber.grabImage();
                        if (frame == null) {
                            continue;
                        }
                        BufferedImage image = converter.convert(frame);
                        if (image != null) {
                            frameHandler.accept(image);
                        }
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        logger.warn("Puller connection lost, retrying in 1s... ({})", e.getMessage());
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } finally {
                    if (localGrabber != null) {
                        try {
                            localGrabber.stop();
                            localGrabber.release();
                        } catch (Exception e) {
                            logger.warn("Error stopping grabber: {}", e.getMessage());
                        }
                    }
                }
            }
            logger.info("RTMP Puller thread exited.");
        }, "RTMP-Puller-Thread");
        pullerThread.setDaemon(true);
        pullerThread.start();
    }

    public void stop() {
        isRunning = false;
        Thread threadToStop = pullerThread;
        if (threadToStop != null) {
            threadToStop.interrupt();
        }
        logger.info("RTMP Puller stopped");
    }
}
