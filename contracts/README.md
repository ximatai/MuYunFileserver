# 文件与媒体处理组件契约

FileServer 拥有文件接收、不可变资产、访问授权和生命周期；媒体 Worker 拥有处理队列、重试及衍生结果提交。
业务系统拥有用户和租户授权、处理决策及业务记录关联。组件通过 HTTP 协作，不跨库或直接读写对方存储。

当前能力声明为 `components.json`：FileServer 提供 fileReception v1；Worker 提供 workerJobs v1、依赖 fileReception v1。

- FileServer 提供 `/api/v1/internal/receptions` 接收及确认接口，Worker 仅消费已确认输入。
- Worker 提供认证后的 `/capabilities`，消费者检查 `schemaVersion=1`、`tenantScoped=true` 和请求租户；新增 provides/requires 字段分别描述任务 API 和接收依赖。schemaVersion 是能力响应的格式版本，不是 workerJobs 协议版本。
- 所有 Worker 请求及文件接收请求均携带可信业务确定的唯一租户；服务 Bearer 与用户凭据分离。
- 以接收任务 ID 幂等提交处理；业务查询 QUEUED/PROCESSING/READY/FAILED，失败恢复为显式命令。
- 输出先固定清单再确认，重试不重复发布资产；原始文件不因 Worker 停机或处理失败而删除。
- FileServer 基础存取功能不依赖 Worker；媒体能力按需启用，不保证 Worker 故障时媒体处理可用。

版本号用于标识制品，协议版本用于判断兼容。当前同一发布流水线给两个组件使用同一版本号，
但清单分别记录身份，组合清单引用已验证版本；以后独立发布不需要改变调用方向或共享数据库。
新增可选能力保持协议 v1 时应向后兼容；删除字段、改变状态或租户语义时需新协议和迁移说明，不能只比较版本号。

协作验收由 `FileReceptionResourceTest` 和 `workers/media-worker/acceptance.py` 覆盖真实文件接收、
音视频转换、租户隔离、幂等确认及 Worker 重启；Worker 单测覆盖上游故障和队列恢复。
普通文件测试独立于 Worker 运行，文件服务关闭接收 API 时也可单独使用。

独立打包：`python3 scripts/package_release.py --version VERSION --component media-worker` 无需 Java 构建；
FileServer 打包自动执行 `clean quarkusBuild`，不会安装或启动 Worker。
正式构建要求干净且与 `vVERSION` 标签相同的源码；FileServer 打包入口执行受控干净构建，拒绝外部预构建 viewer、缺失/过期构建回执以及构建后被修改的文件。包内 build-origin.json 将源码、版本与 Java 制品摘要绑定。默认 `--component all` 生成两个独立包和复用其文件的组合包。


组合匹配每个组件的 `requires` 与其他组件的 `provides`，只要求对应协议的版本集合有交集。
Worker 的 workerJobs API 升级不强制 FileServer 升级，除非其 fileReception 依赖也改变；
业务系统负责自身对 workerJobs 的兼容检查，不能把存储侧兼容误当成全部消费者兼容。
单组件可独立验包，其外部依赖在部署时由组合清单或运营方核对。组合清单校验身份和声明匹配，
协作行为还需集成测试，校验命令本身不证明任意版本组合已通过业务验收。

清单格式升级为 schemaVersion=2，与 HTTP API 版本分开。历史 schemaVersion=1 包保持原始字节，
使用其内附校验器验证，不重标或修改历史制品。
