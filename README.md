# SRS LLM Streaming Demo

将大模型 SSE 流式输出实时编码为视频帧并推送到 SRS（RTMP），再从 RTMP 拉流解码还原为文本，通过 SSE 推送到浏览器展示。

## 主要能力
- 内网仅放行 RTMP 的场景下，实现大模型流式输出的实时展示与回传
- 支持多用户并发测试页面（SSE+两面板）
- 配置外置化：模型、API URL/Key、RTMP、分辨率、CRF、FPS、冗余、排空停流参数均可配置
- 解决尾部丢帧：基于空闲时间排空停流，减少“还在路上就关流”

## 快速开始

### 1) 构建
```bash
mvn -DskipTests package
```

### 2) 选择部署包
- 推荐部署：target/srs-llm-demo-1.0-SNAPSHOT.jar（或 srs-llm-demo-1.0-SNAPSHOT-shaded.jar）
- 不要部署：target/original-srs-llm-demo-1.0-SNAPSHOT.jar（瘦包，不含依赖）

### 3) 准备配置
- 复制示例文件并填写：
  - config.example.txt → config.txt
- 建议用环境变量注入密钥：
  - DASHSCOPE_API_KEY=你的 key

### 4) 启动
```bash
java -jar srs-llm-demo-1.0-SNAPSHOT.jar
```
- 默认端口：23456
- 访问页面：http://localhost:23456/

## 配置说明
- 默认模板（打包内）：src/main/resources/app-config.txt
- 外部覆盖（运行目录）：config.txt（优先级更高；已加入 .gitignore，避免提交密钥）
- 示例模板：config.example.txt


## 常见问题

### Q1: 内网运行报 NoClassDefFoundError: org/bytedeco/ffmpeg/avformat/AVFormatContext
- 原因：运行了 original 瘦包或不带依赖的 JAR
- 解决：使用 target/srs-llm-demo-1.0-SNAPSHOT.jar 或 -shaded.jar

### Q2: 8K 分辨率启动失败（OpenH264 invalid 7680x4320）
- 原因：OpenH264 编码器对 width×height 有上限
- 解决：使用 3840×2160 或更低分辨率

### Q3: 拉流“很久才解码一次”
- 常见原因：推拉分辨率不一致 / 块大小与压缩参数导致帧头误码
- 解决：确保拉流分辨率与 video.width/height 完全一致；必要时降低 CRF 或增大 BLOCK_SIZE


## 主要类
- Web 入口与编排：src/main/java/com/example/srsdemo/WebServerApp.java
- 文本编码为帧：src/main/java/com/example/srsdemo/TextToVideoEncoder.java
- 帧解码为文本：src/main/java/com/example/srsdemo/VideoToTextDecoder.java
- RTMP 推流：src/main/java/com/example/srsdemo/RTMPPusher.java
- RTMP 拉流：src/main/java/com/example/srsdemo/RTMPPuller.java
