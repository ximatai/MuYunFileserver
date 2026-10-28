# 自有音视频存储与 FileServer 公共能力

## 已确定的方向

2026-10-02：使用自部署 SeaweedFS 提供 S3 接口，FileServer 管文件资产。
生产运行于自有服务器，可提供 HTTPS；不使用托管 OSS。
首个消费者是 MR 的声网会议归档，但公共接口不能包含声网频道、专家或会议状态。
接收模型同时考虑纯音频和完整音视频、多对象索引和分片、大文件。
百炼暂时保留在业务模型适配层，未来替换内部模型不改变存储协议。

## 职责与网络边界

| 组件 | 职责 |
| --- | --- |
| SeaweedFS | S3 签名、对象和分段上传、持久化读写 |
| FileServer | 受控接收任务、对象校验、正式资产登记、下载授权与清理 |
| 文件 Worker | 通用音视频探测、合并、转换，保存输入输出关系；独立进程运行 |
| HelmetBridge | 实际频道录制启停与查询、声网回调核验和技术事实 |
| MR | 录制策略、会议归档完整性、业务权限、转写和指导工作流 |

录制数据：声网 → HTTPS 网关 → SeaweedFS 接收区。
文件数据：FileServer / Worker → 内部 S3 地址 → SeaweedFS。
播放数据：浏览器 → FileServer 受限下载接口 → 正式资产存储。
控制：MR → FileServer 创建接收任务；MR → Bridge 启停录制；
Bridge 持有独立录制存储身份，核对 MR 传入的服务端接收桶及位置；MR 发起归档确认和处理。
声网回调只进入 Bridge，不能让公网请求仅凭自报租户登记任意对象。
会议结束不等待上传、合并或转写；回调加速，查询与对账补偿。

S3 接口与 FileServer 管理接口是独立入口。现有内部身份头接口依赖可信调用方，
不能直接因这次录制接入而开放给互联网。
录制凭证只允许专用接收桶；正式资产位于另一个桶。每项任务使用独立前缀，
允许结束后必要的延迟上传时间；任务级动态凭据未在本阶段实现。
SeaweedFS 管理、filer、master、volume 等端口不得一起公开。

## 当前交付：存储兼容基线

- 固定 SeaweedFS 4.48 镜像；Compose 使用镜像摘要，避免 tag 被覆盖。
- 单机 `mini`；关闭 Admin UI、WebDAV、Iceberg、Lance。
- 本地仅发布回环 S3 端口，使用专用 Docker 数据卷。
- 沿用 `mfs.storage.type=minio` 与 `mfs.storage.minio.*`；这是既有客户端适配名称，
  不代表部署 MinIO 服务。暂不迁移历史元数据的 provider 名称。
- `SeaweedFileResourceTest` 使用隔离容器、数据库和临时目录。
  验证 HTTP 文件上传/下载、Range、租户拒绝、签名下载和软删、12 MiB 音频字节校验，
  以及直接 S3 分段上传、索引覆盖、列举、错误签名和存储重启恢复。
  四路并发各 24 MiB 对象上传与下载后核对 SHA256，并删除测试对象。
- 直接 S3 测试使用生成的字节与索引样本，不代表声网真实音视频验收。

### 本地启动

自行生成开发密钥，通过环境注入，勿将值提交或用于生产。

```sh
export MFS_S3_ACCESS_KEY="$(openssl rand -hex 12)"
export MFS_S3_SECRET_KEY="$(openssl rand -hex 32)"
export MFS_S3_BUCKET=muyun-files
export MFS_RECEPTION_BUCKET=muyun-recording
export AGORA_STORAGE_ACCESS_KEY="$(openssl rand -hex 12)"
export AGORA_STORAGE_SECRET_KEY="$(openssl rand -hex 32)"
python3 scripts/render-seaweed-identities.py
docker compose -f compose.seaweedfs.yaml up -d
```

原生运行 FileServer 时参考 `distribution/release/application.seaweedfs.example.yml`。
FileServer 容器内不能使用 `127.0.0.1` 访问另一个容器，需要接入同一内部网络，
配置 `MFS_S3_ENDPOINT=http://seaweedfs:8333`。
从 local 切换前须迁移并核对已有资产；修改配置不会自动迁移历史磁盘文件。

```sh
./gradlew test --tests '*SeaweedFileResourceTest'
docker compose -f compose.seaweedfs.yaml stop
```

`down` 不应附带 `-v`，否则删除持久化数据。
此 Compose 是兼容与开发基线。录制身份有接收桶 Read/Write/List，不具有正式桶权限；
这不是声网实际所需最小接口集合的最终证明。文件不会被生成脚本自动覆盖；
轮换凭据时必须按运行操作手册管理已有配置、受限身份和活动任务。
生产上线还须配置 HTTPS、录制与正式资产区域隔离、受限凭证、资源限额、监控、
元数据和内容备份，以及备份恢复演练。单机不等于高可用。

## 已实现：受控接收任务与资产登记

1. 创建：租户、可信调用方、幂等键、过期时间、总容量和对象数量上限。
   服务生成不可复用的接收位置，不接受调用方指定任意 bucket / key / URL。
2. 接收：上传文件保持接收状态；存储事件或对象存在不自动证明上传集合完整。
   多对象文件清单支持索引覆盖和异步延迟到达。内部 Worker 可通过 PUT 暂存派生文件。
3. 确认：可信服务提供经校验的清单，核对任务范围、实际大小和 SHA256；
   不将 S3 ETag 一律当作文件 SHA256。按幂等事务登记稳定 `fileId`。
4. 固化：最终资产与可继续覆盖的接收对象隔离；确认时检查版本变化，
   正式资产从临时流复制到独立随机 key，防止校验后再次覆盖改变正式文件内容。
5. 生命周期：任务取消或过期后清理未登记对象；确认和清理并发互斥，
   活跃上传、处理和已确认资产不会误删。进程重启可恢复任务。

接收能力保持独立于存储产品和媒体供应商，原有 multipart 上传接口继续可用。
禁止仅凭外部传来的对象路径登记正式文件，禁止任意公网 URL 下载导入。

接口位于 `/api/v1/internal/receptions`：POST 创建、GET 查询、GET `/{id}/objects` 列举、
PUT `/{id}/objects/{key}` 暂存、POST `/{id}/confirm` 确认、POST `/{id}/cancel` 取消。
除原有身份头外，必须校验独立 Bearer；租户授权由可信业务服务负责，不再维护额外的业务租户允许列表。接收任务和正式文件的归属校验仍保留；接收基础设施默认关闭。
任务保存桶、前缀、容量、对象数、有效期及冻结清单；确认租约带 token，
元数据与 READY 在同一 SQLite 事务发布；候选副本持久跟踪，供崩溃后回收。
已确认资产不随接收区到期删除。限制在列举/确认及内部 PUT 时检查，
不能当作直接 S3 上传的硬配额；上线需设置存储容量与告警。

启用时须配置 `mfs.reception.enabled=true`、`bucket`、`service-token`；已移除 `allowed-tenants`。
接收桶必须与正式桶不同；大视频需同时协调 `mfs.upload.max-file-size-bytes`、
`quarkus.http.limits.max-body-size`、临时磁盘和 Worker 容量，不能仅提高任务总容量。

## 已实现：独立媒体 Worker

见 [Worker 运行与格式边界](../../workers/media-worker/README.md)。
输入、衍生输出使用两个独立接收任务，完成后返回 FileServer 资产 ID。
队列、待确认清单可跨重启恢复；每个持久目录只有一个处理进程。
通过真实进程 HTTP API 验证纯音频、音视频的 HLS → Opus OGG / MP4 → 下载解码。
原始正式资产和派生正式资产都由 FileServer 保存；MR 可以独立决定留存。

## 后续阶段

- 验证声网真实混合 HLS 输出、自有 HTTPS S3 的上传接口行为、完整音轨和上传回调。
- Worker 多索引处理、任务优先级及多节点按实际需求扩展。
- 使用两台浏览器验证真实音频和视频文件，不依赖安全帽。
- 再做真实设备验收、权限、留存和备份恢复；引用治理与多节点按需求扩展。

## 验证边界

本阶段不启用真实声网录制、不开放公网存储、不修改运行中的 MR 栈或真实数据，
本地媒体闭环、Bridge REST 适配与 MR 归档接线已实现并模拟验证；
真实声网到自有 S3、真实转写及生产资源容量尚未验收。
短样本不能外推长时、多会议并发峰值。

### 2026-10-02 实测

Apple Silicon 主机，Docker Linux ARM64，Docker VM 内存约 7.75 GiB；
以下是仅 SeaweedFS 容器的 `docker stats --no-stream` 采样，不含 FileServer、网关和 Worker。
基础 profile 关闭 Admin UI、WebDAV、Iceberg、Lance。

| 场景 | 观测 |
| --- | --- |
| 空闲，两次采样 | 84.12 / 90.57 MiB，CPU 0.36% / 0.28% |
| 四路并发各 24 MiB，读写及随后阶段的四次采样 | 370.4 / 509.8 / 503.4 / 503.1 MiB；CPU 118.90% / 11.90% / 9.13% / 4.83% |
| 固定摘要和客户端超时后的最终复核 | 456.8 / 531.9 / 531.9 / 531.9 MiB；CPU 143.00% / 13.38% / 3.46% / 3.49%；6 项测试通过，0 失败 |

Docker CPU 100% 约为一个逻辑 CPU 的满负载，不是整机百分比。
531.9 MiB 是两轮采样最高值，不是严格峰值；上传后的缓存/内存保留不证明泄漏或回落规律。
这里测的是生成字节的存储读写，不是视频编解码或真实声网录制，不能按这些数字承诺生产资源。
目标版本和目标硬件上线前需验证持续负载、容量增长、备份及恢复。

## 上游依据

- SeaweedFS：https://github.com/seaweedfs/seaweedfs
- 固定版本：https://github.com/seaweedfs/seaweedfs/releases/tag/4.48
- 声网自建 S3 配置：https://doc.shengwang.cn/doc/cloud-recording/restful/overview/release-notes

## Review 修复：迟到上传回收

未登记的候选正式对象在异常和到期清理时保留数据库追踪行，作为清理墓碑。
S3 请求超时或旧上传晚于清理完成后，后续清理仍按原 key 删除；不能因一次删除成功就忘记其 key。
成功发布的当前候选在事务中移除追踪，已经登记的正式资产不会被清理。
墓碑暂不自动删除；仅失败候选会保留，需纳入数据库容量监控，待部署层具备明确的在途写入截止保证后再制定回收策略。
已有 SeaweedFS 集成回归覆盖“先清理、后到达对象、再清理”，并确认正式对象保持不受影响。

## 接收前缀兼容供应商约束（2026-10-02）

新接收目录采用 receiving/加去连字符 UUID，仅含字母数字和分隔斜线；任务 API 的 UUID 不变。旧任务仍按持久 prefix 使用，不自动移动对象。消费服务须使用响应 prefix，不能依据任务 ID 自行拼目录。该格式符合声网 fileNamePrefix 的字符与长度要求，并在实际 SeaweedFS 接收/Worker 派生闭环验证。


## FileServer / Worker 链路治理契约（2026-10-15）

本节是后续治理的目标契约，未标“已实现”的条款不能视为当前能力；进度与证据见
[执行计划](../project/development-plan.md#7-fileserver--worker-链路治理2026-10-15)。
本轮先治理通用链路，再接入 MR；不修改运行中的服务或既有业务数据。

### 职责、身份与部署

业务后端决定文件所属租户、业务可见性、处理时机及留存；FileServer 只管理文件归属、
接收确认和资产访问；Worker 只管理媒体加工、队列和输入输出关系。
公共接口不得要求设备、专家、会议、声网或 MR 数据库字段。

租户是可信调用方提供的不透明隔离标识。接收服务当前允许
`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`；Worker 与其对齐，不解释组织关系。
原 Worker 已实现“一实例一租户”（启动配置 `FILESERVER_TENANT`），并非完全不支持租户。
支持单租户部署及共享 Worker；每个持久队列任务冻结租户，
原文件读取、派生接收、结果查询和恢复始终延续同一归属。

安全边界必须区分三条路径：

| 路径 | 当前机制与目标约束 |
| --- | --- |
| 调用方 → Worker | 独立 Bearer；所有请求显式携带租户，业务后端负责授权，禁止仅靠 receptionId 认领归属 |
| Worker → FileServer 内部接收 API | 已有独立接收 Bearer + `X-Tenant-Id` + `X-User-Id`，租户授权由可信业务后端负责；共享密钥不提供按调用方细分授权 |
| Worker → FileServer 普通文件读取 | 当前可信身份头模式，不能把身份头存在当作凭证认证；FileServer 入口由内网/网关限制为可信后端，浏览器使用业务授权后签发的短时文件凭证 |

共享 Worker 的 Bearer 同样代表受信任服务代表业务租户发起处理，不能发给普通用户。
多业务方需要不同权限时，应使用分实例或可信网关约束调用方范围；本轮不新增身份平台。
文件头接口不得绕开网关开放给互联网。Worker 不接受任意 URL/文件路径，不向重定向转发凭证。

### 通用调用顺序与结果

1. 可信后端在 FileServer 创建受控接收任务；使用返回位置上传对象，不自行拼接 bucket/prefix。
2. 上传结束后，提交包含相对 key、大小及可选 SHA256 的完整清单确认。
   只有 `READY` 且含资产映射的接收任务可交给 Worker；对象存在和上传回调不等于完整确认。
3. 调用 `POST /jobs` 提交 `receptionId` 与 `video`；目标在入队前核验原接收归属与就绪状态。
4. 使用 `GET /jobs/{receptionId}` 轮询；HTTP 200 仅表示提交/查询成功，不表示处理完成。
5. 仅任务 `READY` 表示结果已在 FileServer 确认。结果返回 `derivedReceptionId`、
   `assets`（相对产物名 → fileId）及音频探测事实；业务后端另行决定播放授权和业务归档状态。

FileServer 接口保留 `success/data` 包装，Worker 保留直接 JSON；不为了形式统一破坏现有客户端。
Worker 的 `receptionId` 是全局接收 UUID；同一 ID、租户、处理配置重复提交返回原任务，
配置变化拒绝。跨租户查询或提交返回 404，不泄露任务结果；
缺少/无效凭证返回 401，租户或正文格式错误返回 400，FileServer 暂时不可达在入队前返回 503。
Worker 的错误规则不改变 FileServer 既有跨租户 403 规则。

### 媒体、容量与状态

当前已实现的格式范围见 [Worker README](../../workers/media-worker/README.md)：
一份结束的、非加密、非主索引 M3U8 与完整本地 TS；固定帧率合流；输出单声道 Opus OGG，
`video=true` 时另产 H.264/AAC MP4。不是任意媒体转码服务，不实现多索引混音、转写或业务摘要。
校验覆盖输入大小/哈希、HLS 引用、完整解码及音视频时间轴；失败不得发布 READY。

阶段 1 核对时 Worker 输入总量默认 16 GiB，单 FFmpeg 调用最多 600 秒、探测 30 秒，
FileServer 接收默认最多 4096 对象/16 GiB；实际单文件与 HTTP 限额还受 FileServer 配置约束。
这些是技术上限，不代表已验证的长会或吞吐承诺。阶段 1 时未限制排队总量、失败目录保留期及总磁盘，
临时网络异常可无限重试；第三阶段必须补齐有界策略和可检查状态。

| 状态 | 语义与目标 |
| --- | --- |
| QUEUED | 已持久入队，等待执行或延迟重试，不代表成功 |
| PROCESSING | 单个消费者正在处理；进程重启恢复同一任务，不更改租户或配置 |
| READY | 产物确认完成并持久记录结果；重复提交返回相同资产 |
| FAILED | 非法/损坏输入、不可恢复错误或重试耗尽；不得因重复 POST 隐式重做 |

目标：网络/上游暂时异常有限重试；失败类别不含凭证/响应正文；队列和临时空间受限；
确认成功但队列写成功前崩溃，通过冻结清单和派生幂等键恢复相同资产。
当前单进程/单消费者、SQLite + 持久工作目录继续保留，不增加分布式队列。
升级前停止消费者并备份数据库与工作目录；旧任务首次迁移必须使用原租户配置，
不得随默认租户变化重新分配。缩减租户范围前排空或明确保留旧任务，不能静默搬迁资产。

### 独立验收门槛

第二阶段：双租户原文件/任务/衍生产物隔离，越权和错误输入不占用合法任务 ID，
旧单租户队列迁移及重启不改变归属；HTTP 授权/范围/幂等有回归。
第三阶段：重复请求、确认超时、中断恢复、损坏文件、重试耗尽、队列/磁盘上限有故障回归。
第四阶段：隔离 FileServer + SeaweedFS + 独立 Worker 真实进程，覆盖音频和音视频闭环、
跨租户拒绝、重启后的结果一致、文件下载哈希和播放 Range；另提供启动、监测、备份恢复和回退说明。
第五阶段：以上提交均 review 并修正后，才接回 MR 验证归档与播放，不以 MR 的 mock 代替通用验收。
