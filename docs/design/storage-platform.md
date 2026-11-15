# 文件存储平台设计

本文定义 MuYunFileServer 的存储边界与演进契约，适用于普通文件、预览产物、录制接收和媒体处理。现行组件 HTTP 协议见 [组件契约](../../contracts/README.md)。SeaweedFS 部署和声网接入实例见 [音视频存储设计](seaweed-storage.md)。

## 职责与依赖方向

| 边界 | 职责 |
| --- | --- |
| 业务系统 | 决定租户、业务权限、录制和处理时机，保存 `fileId` 与业务记录的关联 |
| FileServer | 管理文件身份、归属、访问、接收确认和清理，通过应用层接口操作存储 |
| Media Worker | 保存和执行媒体处理任务，通过 FileServer HTTP 获取原件和提交产物 |
| 存储基础设施 | 按位置读写字节。部署配置绑定位置、目录或桶、S3 endpoint 和凭据 |

应用层需要三种能力：正式对象写入、读取（含 Range）和删除；接收区暂存、按任务前缀列举、读取和清理；本地工作区临时文件分配和释放。避免让正式对象或接收区接口携带 SDK 类型、存储凭据或部署配置。普通文件和预览路径可落本地或 S3；允许第三方直传的接收区要求 S3。MinIO 和 SeaweedFS 是 S3 部署选项，Java SDK 是实现细节。组件公开 HTTP 协议与内部存储契约独立演进。

## 对象身份与位置

- `fileId` 标识一个资产，外部调用方以它引用文件。FileServer 持有对象引用 `位置 ID + 对象 key`，并在读取、预览、删除、失败补偿和后台清理时使用它。
- 位置 ID 标识一个稳定的存储命名空间。位置 ID 与本地根目录或 S3 地址和桶的绑定保存在元数据库。凭据可以轮换；已有数据的位置更改地址、根目录或桶须走显式迁移，包括访问同一数据集时变更外部地址的情况。只有创建对象时才选择默认写入位置。历史记录引用的未知位置和仍被记录引用的位置重绑会阻止启动。
- 接收任务创建时冻结接收位置及服务端生成的前缀。录制身份的权限限定在独立接收桶，接收桶和正式桶可以部署在同一 S3 服务。现有 v1 HTTP 响应提供桶和前缀，录制端的 endpoint 和身份由业务部署绑定。切换外部直传路由前须停止新任务并处理旧任务和外部在途写入。未来若需两条外部直传路由同时服务，协议须提供可由业务解析的稳定路由标识。
- 确认以同一租户的完整清单为输入。从接收区读回并校验内容后写入独立的正式对象。先持久化含确切位置的候选记录，再写对象。正式文件登记和接收状态 `READY` 同事务发布。遇到结果不确定的存储操作，保留候选记录供后续对账和清理。后续接收区清理不能影响已登记资产。
- 升级后需为历史文件、预览产物、接收任务及未完成候选记录解析出其实际位置。历史记录的 `local`／`minio` 和桶字段只能辅助定位，尤其无法区分两个具有同名桶的 S3 服务。迁移使用已核对的旧部署映射。上线前检查未解析位置和接收任务，禁止凭新默认值猜测目标，也不能仅通过改 endpoint 宣告切换完成。

## 配置与版本升级

最简单的升级方式是保留原数据库和原存储配置启动一次。`local` 和原有 `minio` 配置仍可用，绑定为 `legacy-assets`，配置过独立接收桶时也绑定 `legacy-reception`。单位置部署可用 `mfs.storage.type=s3` 和 `mfs.storage.s3.*` 表达相同的 S3 能力。历史记录缺少地址时，启用升级前须核对旧配置所指的数据集。如果原数据库的旧记录来自多个后端，需要先逐批厘清来源并完成受控迁移。

具名位置使用 `mfs.storage.locations.<id>`。设 `mfs.storage.default-location` 来选择新文件位置，`mfs.reception.location` 选择新接收位置。已有旧记录的数据库如果首次直接使用具名配置，须按真实来源设置 `mfs.storage.legacy-asset-locations` 和 `mfs.storage.legacy-reception-locations` 列表，可列多个历史桶。旧候选记录只有 key；若存在多个旧资产位置，须用 `mfs.storage.legacy-candidate-location` 声明经核实的旧候选位置。相同的旧类型和桶对应多个位置时，需先逐条厘清记录来源。完成回填后，由各条记录的位置 ID 寻址。若已用旧配置启动过新版，改用具名配置时保留已有的 `legacy-assets` 和 `legacy-reception` ID、角色和存储命名空间。

```yaml
mfs:
  storage:
    temp-dir: /data/fileserver/workspace
    default-location: primary
    locations:
      primary:
        type: s3
        s3:
          endpoint: ${MFS_S3_ENDPOINT}
          bucket: muyun-files
          access-key: ${MFS_S3_ACCESS_KEY}
          secret-key: ${MFS_S3_SECRET_KEY}
          auto-create-bucket: false
      recordings:
        type: s3
        role: reception
        s3:
          endpoint: ${MFS_S3_ENDPOINT}
          bucket: muyun-recording
          access-key: ${MFS_RECEPTION_S3_ACCESS_KEY}
          secret-key: ${MFS_RECEPTION_S3_SECRET_KEY}
          auto-create-bucket: false
  reception:
    enabled: true
    location: recordings
    service-token: ${MFS_RECEPTION_TOKEN}
```

接收位置的 S3 凭据用于 FileServer 访问接收桶，外部录制端使用另配的写入身份。各接收桶的名称必须与所有正式桶不同，因为不同 endpoint 可能经不同网关访问同一个存储。具名本地资产位置使用 `type: local` 和 `root-dir`，与工作区保持独立目录。

## 分阶段验收

1. **边界**：评审上述依赖方向、位置与生命周期约束。实现阶段把 SDK 隔离与分层要求纳入架构检查。
2. **位置**：接口、配置与数据模型实施统一的位置语义，保留旧配置和旧数据库的升级路径。验收至少覆盖旧文件读取和删除、预览、接收任务与候选清理在改变默认位置后的行为。
3. **能力**：按普通文件和接收分别定义支持项。两个真实 S3 后端使用共同的行为验收，覆盖读取、Range、清理、接收隔离、分段上传、确认以及 Worker 回传。实际录制供应商直传由部署集成验收，支持范围按实测能力和后端版本标注。
4. **迁移**：演练切换、恢复和回退。切换前暂停接收、写入和清理，处理外部在途上传，或实现等效的写入隔离。以一致快照对账源和目标的对象清单、大小及摘要。回退前核对并保全切换后写入的资产和接收状态，确认原 `fileId` 和已发布的媒体结果仍有效。
