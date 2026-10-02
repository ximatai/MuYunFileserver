# 通用媒体处理 Worker

独立 Python 3.10+ 进程，依赖 `ffmpeg` / `ffprobe`（含 libopus、H.264 / AAC 解码支持）。
不用 Python 三方包，不连接声网，不访问 MR 数据库。
由可信服务提交已确认 FileServer 接收任务，保存输入任务与衍生任务关系。

## 运行

设置环境变量 `MEDIA_WORKER_TOKEN`（至少 24 字符）、`FILESERVER_URL`、
`FILESERVER_TENANT`、`FILESERVER_RECEPTION_TOKEN`；不将凭据写入源码或终端日志。

```sh
python3 workers/media-worker/worker.py --root var/media-worker --port 8096
```

仅监听 `127.0.0.1`；跨主机通过自有 HTTPS 反向代理访问并保留 Bearer 认证。
FileServer URL 固定，禁重定向；HTTP 只允许回环地址。
队列使用 SQLite；同一个持久目录只允许一个进程持有锁，不支持多节点并发消费。
输入源、输出文件和待确认清单使用持久目录，不能指向会被应用启动清空的临时区。

内部 API：`POST /jobs`，正文 `{"receptionId":"UUID","video":false}`；
`GET /jobs/{receptionId}` 查询 `QUEUED/PROCESSING/READY/FAILED`。
所有请求需要 `Authorization: Bearer <MEDIA_WORKER_TOKEN>`。
重复提交同一任务与配置幂等；不接受任意 URL、目录、频道或厂商字段。

## 当前处理范围

- 输入：一份结束的合流 M3U8 及其全部本地 TS 分片；原始对象须先成为 FileServer 正式资产。
- 验证输入下载的 SHA256；拒绝网络/越界 URI、加密索引、主索引及不完整索引。
- 音频：单声道 Opus OGG；全文件解码和元数据探测。
- 视频启用时：保留原码流生成 MP4，再全文件解码校验。
- 输出经独立接收任务固化；冻结待确认清单后重试，防止进程重启产生不同资产。
- 单线程处理；启动恢复 PROCESSING，网络/服务暂时失败延迟 30 秒重试，文件不支持或解码失败记 FAILED。
- READY 提交后删除本地素材；失败素材暂留供排查，运维须设置失败目录留存与磁盘告警。

多个音频、视频和合并索引都可以被 FileServer 保留，当前 Worker 不自动混合多个索引；
遇到该格式会保留原始资产并报告 FAILED，不冒充完整归档。
长会议、并发容量、多节点、失败目录自动回收和多索引混合需要后续专项验证。

## 无设备验收

`FileReceptionResourceTest` 启动隔离 SeaweedFS / FileServer，执行 `acceptance.py`：
生成真实音频及音视频 HLS，经接收、确认、转换、衍生确认、下载和解码；验证重复提交。
测试不会联系真实声网、转写或安全帽。

## 录制归档恢复与完整性

- 派生任务 OPEN 才上传；CONFIRMING 使用冻结清单重新确认。租约忙返回延迟重试，不记永久 FAILED；READY 仍核对同一清单，返回原资产 ID。
- 转码及整文件解码开启 FFmpeg `-xerror`，损坏输入失败并保留原始资产。音频以样本数重建连续输出时间戳，避免正常 HLS 分段时间戳重复导致封装错误；不忽略解码错误。
- 无设备故障回归：`python3 -m unittest discover -s workers/media-worker -p test_worker.py -v`，覆盖确认忙/重启、PUT 与确认竞争、损坏 TS。真实音视频及 SeaweedFS 闭环由 FileReceptionResourceTest 执行。
