# MuYunFileServer Development Plan

本文档用于承接一期设计到工程实现的拆解结果，作为后续开发排期、进度回标和范围控制的执行清单。

相关文档：

- [Overview Design](../design/overview.md)
- [API Design](../design/api.md)

---

## 1. 当前共识

已确认的一期关键约束如下：

- 持久化采用 `SQLite + 手写 SQL + 版本化 migration SQL`
- 上传接口支持单次多文件
- 多文件上传采用整单成功 / 整单失败语义
- 删除后再次删除返回 `404 Not Found`
- 文件服务只校验租户匹配，不承担业务语义授权
- 上传备注 `remark` 为整次请求统一字段
- 下载文件名做安全转义和标准响应头编码
- 允许上传 `0` 字节空文件
- MIME 限制采用白名单策略
- 软删物理清理任务默认每小时执行一次，且频率可配置

---

## 2. 任务树

- [x] 1. 项目基础设施
  - [x] 1.1 清理 starter 示例接口与测试
  - [x] 1.2 补齐 Quarkus 依赖：`multipart`、`SQLite/JDBC`、`health`、`scheduler`、配置校验
  - [x] 1.3 建立基础包结构：`api / application / domain / infrastructure / job / common`
  - [x] 1.4 初始化 `application.yml` 与环境变量映射

- [x] 2. 配置与运行时上下文
  - [x] 2.1 定义文件服务配置模型：根目录、临时目录、大小限制、文件数限制、磁盘阈值、保留期、统一查看开关与 viewer 配置
  - [x] 2.2 定义请求身份上下文模型：`tenantId`、`userId`、`requestId`、`clientId`
  - [x] 2.3 实现身份请求头解析与统一校验
  - [x] 2.4 建立统一错误响应结构与异常映射

- [x] 3. 数据库与 migration
  - [x] 3.1 设计一期 SQLite 表结构与索引
  - [x] 3.2 建立版本化 SQL migration 目录
  - [x] 3.3 实现启动自动 migration
  - [x] 3.4 建立 repository 层与手写 SQL 访问规范

- [x] 4. 领域模型
  - [x] 4.1 定义文件元数据模型与状态枚举
  - [x] 4.2 定义上传结果、删除结果、查询结果 DTO
  - [x] 4.3 定义 `ULID`、`MIME`、扩展名、文件名等校验工具
  - [x] 4.4 定义时间与 `sha256` 等通用约束

- [x] 5. 存储抽象与本地文件系统实现
  - [x] 5.1 定义 `StorageProvider` 抽象
  - [x] 5.2 实现本地文件系统存储：临时写入、正式落盘、流式读取、删除
  - [x] 5.3 实现 `storageKey` 规则：`{tenantId}/yyyy/MM/{ulid}`
  - [x] 5.4 实现启动时残留临时文件清理

- [x] 6. 上传链路
  - [x] 6.1 定义 multipart 请求模型
  - [x] 6.2 实现整单上传预校验：身份、文件数、`file_ids` 对齐、`ULID`、大小、磁盘阈值
  - [x] 6.3 实现逐文件流式落临时文件并计算 `sha256`
  - [x] 6.4 实现整单原子语义：任一文件失败则回滚全部正式文件与元数据
  - [x] 6.5 实现上传接口响应与结构化日志

- [x] 7. 查询链路
  - [x] 7.1 实现 `fileId` 校验
  - [x] 7.2 实现按租户查询 `ACTIVE` 文件元数据
  - [x] 7.3 实现 `403 / 404 / 500` 语义分流
  - [x] 7.4 实现查询接口与审计日志

- [x] 8. 下载链路
  - [x] 8.1 实现按租户校验文件可访问性
  - [x] 8.2 实现文件流下载与 `Content-Type / Content-Length / Content-Disposition`
  - [x] 8.3 处理物理文件缺失为 `500`
  - [x] 8.4 实现下载成功 / 失败日志

- [x] 9. 删除链路
  - [x] 9.1 实现按租户的软删更新
  - [x] 9.2 删除后再次删除返回 `404`
  - [x] 9.3 实现删除响应模型
  - [x] 9.4 实现删除成功 / 失败日志

- [x] 10. 后台任务与健康检查
  - [x] 10.1 实现软删文件物理清理定时任务
  - [x] 10.2 默认每小时扫描一次且可配置
  - [x] 10.3 实现 `liveness / readiness`：`SQLite`、根目录、临时目录
  - [x] 10.4 将清理失败纳入重试与日志

- [x] 11. 测试
  - [x] 11.1 单元测试：`ULID`、`MIME`、`storageKey`、响应映射
  - [x] 11.2 集成测试：上传、查询、下载、删除主链路
  - [x] 11.3 异常测试：冲突、非法 header、非法 `fileId`、MIME 拒绝、回滚
  - [x] 11.4 定时任务与 readiness 基础验证

- [x] 12. 收尾
  - [x] 12.1 更新 `README.md` 与本地运行说明
  - [x] 12.2 补充接口示例与配置说明
  - [x] 12.3 自测并整理剩余风险清单

---

## 3. 进度记录规则

- 开发推进时，直接在本文件中勾选已完成项
- 若任务拆分发生变化，优先维护本大纲，再修改实现
- 若设计边界发生变化，先同步更新设计文档，再回写本大纲

---

## 4. 版本与发布约定

当前发布版本采用项目内约定：

- `v0.YY.N`
- `0`：表示项目仍处于未定稿阶段
- `YY`：年份短码，例如 `26` 表示 `2026`
- `N`：当年发布序号，例如 `1` 表示当年第一个版本

示例：

- `v0.26.1`：`2026` 年第一个正式对外发布版本

发布方式：

- `main` 分支日常开发版本保持 `-SNAPSHOT`
- 推送类似 `v0.26.1` 的 Git tag 时，GitHub Actions 自动构建并发布 release 包
- release 附件默认包含可运行应用、默认配置、示例配置、运行说明和校验摘要

## 5. 当前进度

- [x] 设计文档已建立并完成一期边界确认
- [x] 上传语义已更新为整单成功 / 整单失败
- [x] 第一批代码骨架已落地并通过构建
- [x] 上传、查询、下载、删除主链路已接通并有首批集成测试覆盖
- [x] 上传链路边界场景已补充第二批集成测试覆盖
- [x] `UploadService` 已完成第一轮拆分，上传请求解析和单文件预处理已从编排层解耦
- [x] 上传链路已具备更细粒度的单元测试入口
- [x] `Office -> PDF` 查看能力已落地并支持 `PDF / Office` 统一查看
- [x] token 模式与可信身份头模式均已支持统一查看与 viewer 描述接口
- [x] 内置 file viewer 已落地，统一入口为 `/view/...`
- [x] `PDF.js` 已改为官方 viewer vendor 集成，不再依赖 wrapper 与 webpack
- [x] viewer 前端已接入正式构建链并可随 release 一起发布
- [x] Rocky 环境已完成 viewer 与 `LibreOffice` 闭环验证
- [x] 图片 renderer 已落地，viewer 已支持主流图片在线查看
- [x] 文本 renderer 已落地，viewer 已支持纯文本在线查看
- [x] 音频与视频 renderer 已落地，viewer 已支持浏览器原生媒体播放

---

## 6. Next Optimization Backlog

以下事项不属于当前一期基线阻塞项，但建议作为下一阶段持续优化 backlog 维护。

### P1 测试与契约稳定性

- [x] 补充下载物理文件缺失返回 `500` 的集成测试
- [x] 补充空文件上传测试
- [x] 补充超大文件拦截测试
- [x] 补充 `file_ids` 部分提供、部分自动生成的测试
- [x] 补充文件名特殊字符在 `Content-Disposition` 中的编码测试
- [ ] 复查并收口 `remark` 长度、原始文件名长度和字符集约束
- [ ] 复查错误 `message` 是否需要进一步稳定化，便于调用方依赖

### P1 代码结构与可维护性

- [x] 拆分 `UploadService`，将请求校验、单文件预处理、提交回滚编排解耦
- [ ] 提取文件名、MIME、hash 等工具逻辑，减少 application service 体积
- [x] 评估 repository 映射和异常映射的可测试性，补充更细粒度测试入口

### P2 日志与运维

- [ ] 统一关键日志字段：`operation`、`file_id`、`tenant_id`、`user_id`、`request_id`、`result`、`reason`
- [ ] 增补部署与运维文档，覆盖环境变量、目录准备、SQLite 使用边界和备份建议
- [ ] 复查清理任务日志，区分可重试失败与需要人工介入的失败
- [x] 补齐 Docker 交付中 viewer 静态资源与 `LibreOffice` 的正式验收说明

### P2 安全与规则增强

- [ ] 复查内置文件类型支持矩阵粒度，评估是否需要增加扩展名组合校验
- [ ] 评估是否需要补充更明确的上传文件名规范和拒绝策略

### P3 后续能力预研

- [ ] 评估下载链路未来是否需要 `Range`、`ETag`、缓存协商
- [ ] 评估后续从 `SQLite` 迁移到 `PostgreSQL` 的条件与路径
- [ ] 评估从本地文件系统迁移到 `MinIO/S3` 的演进方式
- [ ] 评估并设计“短时签名下载 token”模式，降低业务网关下载转发负担
- [ ] 若采用下载 token，补充配置模型、验签组件、公开下载入口与集成测试
- [ ] 评估并设计“删除 token”模式，明确 `purpose=delete`、有效期、幂等语义与接口形态
- [x] 实现图片 renderer，作为 viewer 的第二种正式 renderer
- [x] 实现文本 renderer，作为 viewer 的第三种正式 renderer
- [x] 实现视频 renderer，作为 viewer 的第四种正式 renderer
- [x] 实现音频 renderer，作为 viewer 的第五种正式 renderer
- [x] 收口 PDF.js 官方 viewer 的薄适配边界，工具栏裁剪改由同步脚本与 `pdfjs-overrides` 维护
- [x] 脚本化 PDF.js 升级与验证链路，确保 vendor 保持官方 release、补丁在构建时自动应用
- [ ] 评估视频转码、封面图、时长元数据和更强多媒体协议扩展方式

当前阶段建议：

- 优先推进视频封面图、时长元数据与大文件媒体体验边界
- 持续收口 Docker 文档与交付模板表达
- 暂不进入视频转码，除非业务需求明确要求


## 7. FileServer / Worker 链路治理（2026-10-15）

优先级高于本文旧版 Optimization Backlog；MR 产品化暂缓，先完成公共链路。
工作分支 `codex/mr-tenant-worker`，独立工作空间，不修改原 main 目录或运行服务。
由主代理亲自实现，每阶段自查、提交、邀请 subagent 只读 review，修正 findings 后进入下一阶段。
依据：[链路契约](../design/seaweed-storage.md#fileserver--worker-链路治理契约2026-10-15)。

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| 1 | 现状核对、职责/接口/授权/格式/容量契约、差距和验收清单 | `73bfbb2` 已自查并由 subagent review，无 actionable findings |
| 2 | 任务、源文件与衍生文件可信归属；单租户兼容、共享实例与恢复隔离 | `2093da8` 与 `89ec66e` 自查及 subagent review 通过，无 actionable findings |
| 3 | 幂等、有限重试、故障恢复、队列及磁盘资源上限 | `ef23bd5` review 发现 1 项，`7140e7b` 修复并复核通过 |
| 4 | 独立真实链路验收、健康/日志/指标、升级和备份恢复说明 | 已实现，待全量回归及提交 review |
| 5 | MR 按稳定契约接入、归档与播放验证，之后续原业务计划 | 未开始 |

阶段 1 自查：对照 FileReceptionsResource、FileReceptionService、RequestContextFilter、
FileServiceConfig 与原版 Worker/acceptance 测试核对；明确当前能力与目标，
未把可信头当作凭证、未声称已有每调用方租户授权，未将 16 GiB 上限当容量验收结论。
本阶段仅文档，无运行逻辑修改，执行 diff/链接检查；不以重跑媒体测试替代契约审查。


阶段 2 自查：统一租户格式与 FileServer 一致；检查字段类型、重复身份头与来源归属；
任务幂等复用不依赖已过期源接收；迁移保存原租户，默认租户变化不改写归属，
消费者只读取当前允许范围。CI/release 均运行全部 test*.py，避免新隔离回归被漏掉。
验证：Worker 原有 6 项真实 FFmpeg 故障回归加租户/HTTP/迁移测试全部通过；
跨服务真实存储闭环安排在阶段 4，不以当前 fake 证明 FileServer 端到端隔离。


阶段 2 补充真实回归：FileReceptionResourceTest 共 10 项全部通过（含独立 Worker 音频/视频闭环）。
首次固定 localhost:8081 命中既有本机服务而返回 404；测试资源改为随机 HTTP 端口后通过，
未停止或修改既有服务。acceptance 输出测试目标地址用于定位此类误连，不包含凭证。
`2093da8` subagent review 无 actionable findings，独立复跑 10 项隔离回归通过。


阶段 3 自查：执行次数与任务同库持久化，崩溃恢复不绕过预算；入队容量在立即事务内检查，
重复请求不重复计数；显式恢复仅 FAILED，保留原租户与清单。转码在超时/空间不足时杀进程组并回收，
下载/上游调用延续磁盘及总时限检查；普通 GET 不因空间阈值失去可观察性。
资源阈值是应用层保护而非 OS 硬配额，失败现场不自动丢弃；README 明确实际保障范围。

阶段 3 验证：25 项 Python 回归全部通过，含真实 FFmpeg 媒体完整性、并发入队上限、
持久重试耗尽、显式恢复权限、崩溃预算、磁盘保护、命令超时与 READY 残留恢复。


阶段 3 review 发现并修复：最后一次确认跨过总时限仍可能发布 READY。
HTTP 成功返回和本地 READY 发布前新增检查；真实本地 HTTP 延迟确认回归验证失败保留清单，
显式恢复复用同一远端已确认资产。README 收窄网络保证：socket timeout 不是 HTTP 总时长硬截止，
持续慢响应在控制返回后才检查总时限，不承诺 60 秒内必然返回。


阶段 4 自查：健康区分 HTTP 存活与本地 ready，不以线程存活冒充上游可用；指标按租户返回状态，
服务容量统计含全部待办；日志不含原始异常和身份凭证。停止信号保留原队列与冻结清单，
编码子进程可中断，正常停止不扣执行预算；慢 HTTP 的停止边界如实记录。
延迟确认 HTTP fixture 补齐 chunked 请求体读取，避免提前响应造成偶发 BrokenPipe，未放松生产校验。
初步验证：28 Python tests 与 10 项 FileReceptionResourceTest 通过；真实双租户四条音视频链路、
改变默认租户后的 Worker 重启结果和 fileId 一致。完整灾备、长会和并发容量不在本次短样本证据内。


阶段 4 最终验证：FileServer 全量 162 tests，0 failures/errors/skips。
最后自查增加日志输出失败不得改变任务结果的保护后，29 项 Python tests 及真实接收 10 tests 复跑通过。
真实链路四项（双租户 × 音频/音视频）及重启均通过；未部署、未推送、未改既有服务。
