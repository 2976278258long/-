package com.example.srsdemo;

import org.junit.Assert;
import org.junit.Test;
import java.awt.image.BufferedImage;

public class CodecTest {
    @Test
    public void testEncodeDecode() {
        TextToVideoEncoder encoder = new TextToVideoEncoder();
        VideoToTextDecoder decoder = new VideoToTextDecoder();
        
        String originalText = "你好，这是一个SRS推流演示程序。LLM Streaming output test!";
        BufferedImage frame = encoder.encode(originalText);
        
        String decodedText = decoder.decode(frame);
        
        Assert.assertEquals(originalText, decodedText);
    }
    
    @Test
    public void testSequence() {
        TextToVideoEncoder encoder = new TextToVideoEncoder();
        VideoToTextDecoder decoder = new VideoToTextDecoder();
        
        String t1 = "Hello";
        String t2 = "World";
        
        BufferedImage f1 = encoder.encode(t1);
        BufferedImage f2 = encoder.encode(t2);
        
        Assert.assertEquals(t1, decoder.decode(f1));
        Assert.assertEquals(t2, decoder.decode(f2));
        // 重复解码同一帧应返回 null (基于 seq 逻辑)
        Assert.assertNull(decoder.decode(f1));
    }
}
