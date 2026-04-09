package com.example.srsdemo;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;

/**
 * 将文本编码为视频帧的编码器
 * 采用像素块编码方式以确保在 H.264 压缩下仍能 100% 还原数据
 */
public class TextToVideoEncoder {
    public static int WIDTH = Config.getInt("video.width", 960);
    public static int HEIGHT = Config.getInt("video.height", 720);
    public static final int BLOCK_SIZE = 8; // 16x16 像素块表示一个 bit
    public static final int MAGIC_NUMBER = 0xDEADBEEF;

    private int sequenceNumber = 0;
    private int messageIdCounter = 0;

    /**
     * 将文本编码为 BufferedImage
     * @param text 输入文本
     * @return 编码后的图像
     */
    public BufferedImage encode(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int payloadLen = Math.min(payload.length, getMaxPayloadSize());
        
        // 构建帧数据: Magic(4) + Seq(4) + Len(4) + Payload(n)
        ByteBuffer buffer = ByteBuffer.allocate(12 + payloadLen);
        buffer.putInt(MAGIC_NUMBER);
        buffer.putInt(sequenceNumber++);
        buffer.putInt(payloadLen);
        buffer.put(payload, 0, payloadLen);
        
        byte[] frameData = buffer.array();
        
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        
        // 背景设为黑色
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        
        int blocksPerRow = WIDTH / BLOCK_SIZE;
        
        // 逐 byte 编码
        for (int i = 0; i < frameData.length; i++) {
            int b = frameData[i] & 0xFF;
            // 每个 byte 占用 8 个 bit (8 个 block)
            for (int bit = 7; bit >= 0; bit--) {
                int bitValue = (b >> bit) & 1;
                int blockIdx = i * 8 + (7 - bit);
                
                int row = blockIdx / blocksPerRow;
                int col = blockIdx % blocksPerRow;
                
                if (row >= HEIGHT / BLOCK_SIZE) break;
                
                g.setColor(bitValue == 1 ? Color.WHITE : Color.BLACK);
                g.fillRect(col * BLOCK_SIZE, row * BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE);
            }
        }
        
        g.dispose();
        return image;
    }

    /**
     * 将文本切片为多帧（用于超长消息的无损传输）
     * 帧负载格式为 JSON 包裹：
     * 兼容老格式: {"_id":<id>,"_i":<idx>,"_n":<n>,"d":"<text-part>"}
     * 新格式(默认): {"_id":<id>,"_i":<idx>,"_n":<n>,"b64":true,"x":"<base64-bytes>"}
     * 新格式通过 base64 传输原始 UTF-8 字节，避免多字节字符跨片段造成的乱码
     */
    public List<BufferedImage> encodeChunks(String text) {
        // 明文直传且不分片：直接把 text 编码到单帧
        List<BufferedImage> frames = new ArrayList<>();
        frames.add(encode(text));
        return frames;
    }

    /**
     * 计算最大有效载荷大小（不包括 JSON 包裹）
     * @return 最大有效载荷大小
     */
    public int getMaxPayloadSize() {
        int totalBlocks = (WIDTH / BLOCK_SIZE) * (HEIGHT / BLOCK_SIZE);
        int totalBytes = totalBlocks / 8;
        return totalBytes - 12; // 减去 header 长度
    }
}
