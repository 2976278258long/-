package com.example.srsdemo;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * RTMP 推流客户端
 */
public class RTMPPusher {
    private static final Logger logger = LoggerFactory.getLogger(RTMPPusher.class);
    
    private String rtmpUrl;
    private FFmpegFrameRecorder recorder;
    private Java2DFrameConverter converter;
    private int frameRate = Config.getInt("encoder.fps", 6);
    private boolean isRunning = false;

    public RTMPPusher(String rtmpUrl) {
        this.rtmpUrl = rtmpUrl;
        this.converter = new Java2DFrameConverter();
    }

    public void start() throws Exception {
        recorder = new FFmpegFrameRecorder(rtmpUrl, TextToVideoEncoder.WIDTH, TextToVideoEncoder.HEIGHT);
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("flv");
        recorder.setFrameRate(frameRate);
        recorder.setGopSize(1); // 每一帧都是关键帧，确保秒开
        recorder.setVideoOption("tune", "zerolatency"); // 针对低延迟场景优化
        recorder.setVideoOption("preset", "ultrafast"); // 编码速度最快
        // 网络层优化参数
        recorder.setOption("rtmp_transport", "tcp");
        recorder.setOption("tcp_nodelay", "1");
        recorder.setOption("fflags", "nobuffer");
        recorder.setOption("flush_packets", "1");
        
        recorder.setVideoOption("crf", String.valueOf(Config.getInt("encoder.crf", 28)));
        recorder.setVideoOption("threads", String.valueOf(Config.getInt("encoder.threads", 2)));
        
        recorder.start();
        isRunning = true;
        logger.info("RTMP Pusher started: {}", rtmpUrl);
    }

    public void pushFrame(BufferedImage image) throws Exception {
        if (!isRunning) return;
        Frame frame = converter.convert(image);
        recorder.record(frame);
    }

    public void stop() throws Exception {
        isRunning = false;
        if (recorder != null) {
            recorder.stop();
            recorder.release();
        }
        logger.info("RTMP Pusher stopped");
    }
}
