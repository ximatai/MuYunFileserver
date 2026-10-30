# 通用媒体处理 Worker

独立 Python 3.10+ 进程，依赖 `ffmpeg` / `ffprobe`（含 libopus、H.264 / AAC 解码支持）。
不使用 Python 三方包，不连接媒体供应商，不访问业务数据库。
提供独立发布包，也可从组合包获取；通过媒体处理协议与 FileServer 协作，组件版本号不要求相同。
不维护业务专用分支；兼容规则见源码仓库的 `contracts/`。
由可信服务提交已确认 FileServer 接收任务，保存输入任务与衍生任务关系。

## 运行

设置环境变量 `MEDIA_WORKER_TOKEN`（至少 24 字符）、`FILESERVER_URL`、
`FILESERVER_TENANT`、`FILESERVER_RECEPTION_TOKEN`；不将凭据写入源码或终端日志。

```sh
python3 /path/to/MuYunMediaWorker/worker.py --root /data/media-worker --port 8096
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
- 无设备故障回归：`python3 -m unittest discover -s workers/media-worker -p 'test*.py' -v`，覆盖确认忙/重启、PUT 与确认竞争、损坏 TS。真实音视频及 SeaweedFS 闭环由 FileReceptionResourceTest 执行。

### 分片与时间轴

对已确认索引按顺序生成本地 ffconcat，逐文件打开 TS，兼容分片间 continuity counter 重置；
不忽略损坏数据，保留 `-xerror` 与完整解码。只允许 file 协议，拒绝远端或越界引用、加密、
主索引、EXT-X-MAP 和字节范围。按已校验 EXTINF 时长重建视频帧与音频样本时间轴；
输出总时长须与音频及索引一致，正常分片边界不能丢帧。

MP4 重新编码增加 CPU 开销；长会吞吐和并发容量仍需按实际资源验证。
CI 安装 FFmpeg 并执行损坏输入、分片边界及连续时间轴回归。

## 运行契约

支持共享 Worker；每次请求显式传租户，由持久任务保存归属。
资源阈值、失败恢复和停止语义如下；无须配置业务租户白名单。

### 租户上下文与兼容

业务系统统一确定租户，所有 HTTP 请求必须提供唯一的 `X-Tenant-Id`，格式与 FileServer 接收服务一致。
不再配置或读取 `FILESERVER_ALLOWED_TENANTS`；新增业务租户无需修改 Worker 或 FileServer 配置。
`FILESERVER_TENANT` 仅保留为旧单租户队列迁移的历史归属和进程内兼容默认值，不决定 HTTP 请求归属。
Bearer 只供可信业务服务使用；业务服务负责用户级租户授权，Worker 仍验证接收任务与持久处理任务的租户归属。
消费与重启恢复直接使用持久任务中的租户，不依赖可变配置名单，不将旧任务重新归给默认租户。
`GET /capabilities` 同样需要认证与租户，返回 `schemaVersion=1`、`tenantScoped=true`、`tenantId` 以及 `provides.workerJobs`、`requires.fileReception`。schemaVersion 表示响应格式，不绑定任务协议版本；旧调用方可继续读取原字段。
任务响应附带冻结的 `tenantId`；入队前检查源接收归属及 READY，跨租户提交/查询返回 404。
HTTP 200 的任务仍须按 state 判断结果；非法正文或租户格式 400，上游暂不可达 503。

旧 jobs.db 首次升级时以 **原 FILESERVER_TENANT** 回填租户，并在 worker_metadata 固定迁移来源。
升级前停止 Worker，备份 jobs.db 与完整工作目录；不能先改默认租户再升级。
后续更改默认租户不会改写已有任务；所有持久任务继续按原租户消费。
同一 root 仍只允许运行一个进程；不要将旧程序直接指向升级后的队列。

### 任务预算和资源阈值

| 环境变量 | 默认值 | 语义 |
| --- | --- | --- |
| MEDIA_WORKER_MAX_PENDING | 128 | 全实例 QUEUED + PROCESSING 上限，覆盖所有租户 |
| MEDIA_WORKER_MAX_ATTEMPTS | 5 | 每轮最多执行次数，持久保存；进程崩溃也消耗已开始的次数 |
| MEDIA_WORKER_RETRY_SECONDS | 30 | 暂时网络/5xx/确认忙的重试间隔 |
| MEDIA_WORKER_MAX_INPUT_BYTES | 17179869184 | 单任务原素材下载总量 |
| MEDIA_WORKER_MAX_WORK_BYTES | 34359738368 | 工作目录逻辑字节阈值，含素材、日志、数据库与失败现场 |
| MEDIA_WORKER_MIN_FREE_BYTES | 268435456 | 所在文件系统最小剩余空间 |
| MEDIA_WORKER_COMMAND_TIMEOUT | 600 | 单次 FFmpeg 最大秒数；FFprobe 仍限制 30 秒 |
| MEDIA_WORKER_JOB_TIMEOUT | 3600 | 单次执行时限检查；在传输调用前后、下载分块和编码轮询点检查，非 HTTP 总时长硬上限 |

队列满/空间不足时新任务返回 503；相同任务的幂等查询仍可用。HTTP 最多 16 个并发线程，
读取请求超时 10 秒。编码期间每约 200ms 检查时间/磁盘，下载按块检查，结束后再次检查。
HTTP socket 超时 60 秒，持续缓慢响应可能更久；总时限会在控制返回后拒绝发布成功，
不能当作整次网络传输的强制截止。网络硬截止需由受控代理另行约束。
工作目录按实际文件大小抽样，是应用保护阈值，不能替代文件系统硬配额；数据库和在途写入
可能短暂越过阈值。CPU/内存硬限额由进程管理器或容器设置，本轮不声明实现公平调度或多节点。

`POST /jobs/{receptionId}/retry`，正文 `{}`，同样需要 Bearer 和正确租户；
仅 FAILED 任务重新排队并重置次数，保留原配置、租户、素材和冻结清单，不创建新产物版本。
重复调用排队/执行/成功任务不重置预算。普通 `POST /jobs` 从不重做 FAILED。
达到上限的暂时故障记 `RetryExhausted`；格式/解码/时限/磁盘失败需排障后显式恢复。
失败目录不自动删除；READY 清理失败的残留在重启时再清理。达到空间阈值时先扩容或
停止服务、备份后处理明确不再恢复的失败现场，不要删除仍在恢复中的冻结清单。

### 健康、日志和停止

所有 Worker HTTP 入口均保留 Bearer 与租户检查，包括健康和指标，不返回凭证、原始异常或文件内容。
`GET /health/live` 仅证明 HTTP 进程存活；`GET /health/ready` 检查消费者存活、最近循环无错误、
SQLite、工作盘阈值及队列容量，UP=200、DOWN=503。它不探测 FileServer/S3 或承诺任务无积压；
分别监控 FileServer `/q/health/ready` 与存储，持续慢响应需网关总时长限制及队列年龄告警。
`GET /metrics` 是 JSON 运维快照，不是 Prometheus 文本：当前租户各状态数量、全实例 pendingTotal/maxPending、
按下次可执行时间计算的 oldestQueuedAgeSeconds。后者不是任务从创建起累计等待时长。
默认告警建议：消费者/数据库 DOWN 立即告警；空间接近阈值、队列占用超过 80%、可执行队列年龄持续增长时排查。

stdout 使用 JSON 行，jobId + tenantId 关联 submitted/started/deferred/failed/ready/retry_requested；
仅记录异常类型，不记录 Bearer、请求正文或上游 URL。进程管理器负责 stdout 轮转与留存。
FFmpeg 排障日志在任务目录 `process.log`，后续命令会覆盖该文件；失败现场保留，READY 清理。
SIGTERM/SIGINT 停止接收并通知消费者；编码轮询会杀掉子进程组，正常停止不消耗一次执行预算，
任务重新 QUEUED，仍使用原素材和冻结清单。退出最多等待消费者 65 秒，日志 consumerStopped=false
表示未完成协作停止，重启会恢复 PROCESSING；不能据此宣称在途 HTTP 已被强制取消。

### 独立验收命令

在源码根目录、Java 21 / Python 3.10+ / FFmpeg / Docker 可用的环境串行执行：

```sh
python3 -m unittest discover -s workers/media-worker -p 'test*.py' -v
./gradlew test --tests '*FileReceptionResourceTest' --max-workers=1
```

验收使用随机端口、临时数据库和 SeaweedFS 容器，不连接 MR、设备或媒体厂商。
两个租户分别执行音频、音视频，共四条真实链路；入队前跨租户拒绝、派生资产归属、
下载哈希、全文件解码、播放 Range 和无效播放凭证均断言；随后重启 Worker 并改变默认租户，
验证四项结果与资产 ID 不变。该短样本不代表长会吞吐、生产容量或多节点高可用。
备份、升级和恢复步骤见独立包同目录 `RUN.md`（源码位置为 `distribution/release/WORKER.md`）。


### 消费者写库故障恢复

处理完成或异常结果写库失败时，消费者保留错误状态；下一轮在单消费者执行锁和独占工作目录锁下，
先恢复遗留 PROCESSING，再处理队列，不仅因暂无 QUEUED 就把遗留任务当成健康。
恢复保留原租户、执行次数和冻结清单，已有远端确认仍复用原资产；同样受持久重试预算限制。
异常补偿只修改仍处于 PROCESSING 的任务，避免不确定提交后覆盖已经发布的 READY。
