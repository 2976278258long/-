package com.example.srsdemo;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从视频帧中解码文本的解码器
 */
public class VideoToTextDecoder {
    private int lastSequenceNumber = -1;

    /**
     * 从 BufferedImage 中解码文本
     * @param image 输入图像
     * @return 解码后的文本，如果不是有效数据帧或已处理过则返回 null
     */
    public String decode(BufferedImage image) {
        if (image == null) return null;

        int blocksPerRow = TextToVideoEncoder.WIDTH / TextToVideoEncoder.BLOCK_SIZE;
        int blocksPerCol = TextToVideoEncoder.HEIGHT / TextToVideoEncoder.BLOCK_SIZE;
        int totalBytes = (blocksPerRow * blocksPerCol) / 8;// 每个像素 8 位，总字节数

        byte[] data = new byte[totalBytes];
        
        for (int i = 0; i < totalBytes; i++) {
            int b = 0;
            for (int bit = 0; bit < 8; bit++) {
                int blockIdx = i * 8 + bit;
                int row = blockIdx / blocksPerRow;// 计算当前像素所在的行
                int col = blockIdx % blocksPerRow;// 计算当前像素所在的列
                
                if (row >= blocksPerCol) break;

                // 采样块中心像素
                int x = col * TextToVideoEncoder.BLOCK_SIZE + TextToVideoEncoder.BLOCK_SIZE / 2;
                int y = row * TextToVideoEncoder.BLOCK_SIZE + TextToVideoEncoder.BLOCK_SIZE / 2;
                
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;//      提取红色通道值
                int g = (rgb >> 8) & 0xFF;//      提取绿色通道值
                int b_val = rgb & 0xFF;// 提取蓝色通道值
                
                // 亮度阈值判断
                int brightness = (r + g + b_val) / 3;// 计算像素的亮度值
                if (brightness > 128) {
                    b |= (1 << (7 - bit));// 如果亮度大于 128，将当前位设置为 1
                }
            }
            data[i] = (byte) b;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);// 将字节数组包装为 ByteBuffer
        if (buffer.remaining() < 12) return null;

        int magic = buffer.getInt();
        if (magic != TextToVideoEncoder.MAGIC_NUMBER) {
            return null;
        }

        int seq = buffer.getInt();
        int len = buffer.getInt();

        if (len < 0 || len > buffer.remaining()) {
            return null;
        }

        // 如果是已经处理过的序号，跳过（可选，取决于是否需要处理重复帧）
        if (seq <= lastSequenceNumber) {
            return null;
        }
        lastSequenceNumber = seq;

        byte[] payload = new byte[len];
        buffer.get(payload);
        
        return new String(payload, StandardCharsets.UTF_8);
    }
}
