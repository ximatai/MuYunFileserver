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
- 视频启用时：按来源帧率重建连续帧时间轴，以 H.264（CRF 18、veryfast）与 AAC 生成 MP4，再全文件解码校验。当前范围为固定帧率合流录制；输出与音频及索引时长偏差超过 250ms 时拒绝确认。
- 输出经独立接收任务固化；冻结待确认清单后重试，防止进程重启产生不同资产。
- 单线程处理；启动恢复 PROCESSING，网络/服务暂时失败延迟 30 秒，最多执行 5 次，文件不支持或解码失败记 FAILED。
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

### TS 分片边界兼容（2026-10-12）

真实声网音视频素材中，各 TS 分片单独严格解码均成功，但 HLS 连续 demux 将跨分片的 transport continuity counter 重置标为 corrupt input packet。处理器现按已确认索引中的顺序生成本地 ffconcat，逐文件打开分片后合并时间轴；保留 `-xerror` 和最终整文件解码，不使用 discardcorrupt 或忽略错误参数。只允许 file 协议，拒绝加密、主索引、EXT-X-MAP、字节范围与远端／越界引用。合成计数器重置场景验证两段全部 40 帧和 AAC 音轨均保留，原有真实载荷损坏回归仍为 FAILED。

本次真实失败任务由运营者在停止 Worker 后，留存原 FAILED 快照，重新执行同一接收任务的处理和确认，实际资产生成成功后恢复同一队列记录。未重新请求供应商录制。该受控操作不是自动重试 API；失败任务的通用受控恢复入口仍需后续工程化。

### PR Review 时间轴修复

仅按 TS 探测时长拼接会累计音视频边界差异。现逐分片使用已校验的 EXTINF 时长；视频帧与音频样本分别重建连续时间轴，避免 seek/inpoint 丢失边界关键帧。保留全部视频帧，拒绝无效索引时长，严格解码后再核对 MP4、OGG 和索引总时长。新增 30 秒连续 HLS 回归同时检查 450 帧、帧间隔及音视频时长，不再只依赖文件可解码。

MP4 重新编码增加 CPU 开销，不再声明视频原码流复制；长会吞吐和并发容量仍待验证。CI 明确安装 FFmpeg，并执行 Worker 回归；Java 失败日志保留完整断言信息，便于定位原生处理失败。


## 链路治理状态（2026-10-15）

当前同时支持单租户与显式配置范围内的共享 Worker；现已增加有界重试及资源阈值；独立双租户验收和运维交付仍待完成。
后续目标、可信网络前提、接口状态和验收门槛以
[FileServer / Worker 链路契约](../../docs/design/seaweed-storage.md#fileserver--worker-链路治理契约2026-10-15)
及[阶段执行记录](../../docs/project/development-plan.md#7-fileserver--worker-链路治理2026-10-15)为准。

### 租户上下文与兼容

默认仍使用 `FILESERVER_TENANT`，单租户请求可省略 `X-Tenant-Id`。
共享实例设置 `FILESERVER_ALLOWED_TENANTS=tenant-a,tenant-b`（最多 64 个，须包含默认租户），
请求必须提供 `X-Tenant-Id`；格式与 FileServer 接收服务一致，服务端允许列表也须覆盖对应租户。
Bearer 只供可信服务使用；拥有该凭证的服务可访问配置列表内全部租户，不提供用户级授权。
`GET /capabilities` 同样需要认证与租户，返回 `schemaVersion=1`、`tenantScoped=true`、`tenantId`。
任务响应附带冻结的 `tenantId`；入队前检查源接收归属及 READY，跨租户提交/查询返回 404。
HTTP 200 的任务仍须按 state 判断结果；非法正文 400，范围外租户 403，上游暂不可达 503。

旧 jobs.db 首次升级时以 **原 FILESERVER_TENANT** 回填租户，并在 worker_metadata 固定迁移来源。
升级前停止 Worker，备份 jobs.db 与完整工作目录；不能先改默认租户再升级。
后续更改默认租户不会改写已有任务；移除允许列表中的租户后，该租户旧队列不会被消费或转给其他租户。
同一 root 仍只允许运行一个进程；不要将旧程序直接指向升级后的队列。

### 任务预算和资源阈值

| 环境变量 | 默认值 | 语义 |
| --- | --- | --- |
| MEDIA_WORKER_MAX_PENDING | 128 | 全实例 QUEUED + PROCESSING 上限，包含暂时移出允许范围的待办 |
| MEDIA_WORKER_MAX_ATTEMPTS | 5 | 每轮最多执行次数，持久保存；进程崩溃也消耗已开始的次数 |
| MEDIA_WORKER_RETRY_SECONDS | 30 | 暂时网络/5xx/确认忙的重试间隔 |
| MEDIA_WORKER_MAX_INPUT_BYTES | 17179869184 | 单任务原素材下载总量 |
| MEDIA_WORKER_MAX_WORK_BYTES | 34359738368 | 工作目录逻辑字节阈值，含素材、日志、数据库与失败现场 |
| MEDIA_WORKER_MIN_FREE_BYTES | 268435456 | 所在文件系统最小剩余空间 |
| MEDIA_WORKER_COMMAND_TIMEOUT | 600 | 单次 FFmpeg 最大秒数；FFprobe 仍限制 30 秒 |
| MEDIA_WORKER_JOB_TIMEOUT | 3600 | 单次执行总时限；传输/探测最多到当前 60/30 秒调用结束才检查 |

队列满/空间不足时新任务返回 503；相同任务的幂等查询仍可用。HTTP 最多 16 个并发线程，
读取请求超时 10 秒。编码期间每约 200ms 检查时间/磁盘，下载按块检查，结束后再次检查。
工作目录按实际文件大小抽样，是应用保护阈值，不能替代文件系统硬配额；数据库和在途写入
可能短暂越过阈值。CPU/内存硬限额由进程管理器或容器设置，本轮不声明实现公平调度或多节点。

`POST /jobs/{receptionId}/retry`，正文 `{}`，同样需要 Bearer 和正确租户；
仅 FAILED 任务重新排队并重置次数，保留原配置、租户、素材和冻结清单，不创建新产物版本。
重复调用排队/执行/成功任务不重置预算。普通 `POST /jobs` 从不重做 FAILED。
达到上限的暂时故障记 `RetryExhausted`；格式/解码/时限/磁盘失败需排障后显式恢复。
失败目录不自动删除；READY 清理失败的残留在重启时再清理。达到空间阈值时先扩容或
停止服务、备份后处理明确不再恢复的失败现场，不要删除仍在恢复中的冻结清单。
