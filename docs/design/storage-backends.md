# S3 后端支持范围

业务 HTTP 协议与存储后端无关。FileServer 用 [逻辑位置](storage-platform.md) 找数据，部署按所需能力选择后端。

| 能力 | 发布验证要求 | 回归后端 |
| --- | --- | --- |
| 普通文件 | HTTP 上传、对象写入、预览、Range 下载、软删后清理 | MinIO、SeaweedFS |
| 录制接收 | 独立录制身份向接收桶发起 S3 分段上传，对正式对象的读、写、列举均被拒绝。FileServer 核对清单，冻结副本并幂等登记。候选对象和接收对象需能恢复或清理 | MinIO、SeaweedFS |
| 媒体处理 | 启动真实 Worker 和 FFmpeg，从 FileServer 下载原件，生成音频和视频、回传并登记。验证不同租户的结果隔离及 Worker 重启后可查询 | MinIO、SeaweedFS |
| 实际录制供应商 | 逐部署验收公网 HTTPS、录制方支持的 endpoint 和签名、延迟补传、回调后的确认和播放 | 发布回归使用合成素材。真实声网直传须在目标部署另外验收 |

两套真实 S3 容器执行同一个 `S3ReceptionContract`，并与已有的产品专用回归一起进入 CI 和 FileServer 发布构建。当前基线为从 `07c3a429bfed433e49018cb0f78a52145d4bedeb` 构建的 MinIO（`RELEASE.2025-09-07T16-13-09Z`）和 [固定镜像摘要/SeaweedFS 4.48](../../compose.seaweedfs.yaml)。更换镜像版本时按同一能力要求重新验收。

MinIO 独立录制身份使用 `minio-recorder-policy.example.json`，该文件位于源码的 `distribution/release/` 及 FileServer 发布包根目录。SeaweedFS 示例的受限身份由 `render-seaweed-identities.py` 生成。两个示例都需要独立配置 FileServer 身份和外部录制身份。
