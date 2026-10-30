# 文件与媒体处理组件契约

FileServer 拥有文件接收、不可变资产、访问授权和生命周期；媒体 Worker 拥有处理队列、重试及衍生结果提交。
业务系统拥有用户和租户授权、处理决策及业务记录关联。组件通过 HTTP 协作，不跨库或直接读写对方存储。

当前契约为 `media-processing-v1.json`：

- FileServer 提供 `/api/v1/internal/receptions` 接收及确认接口，Worker 仅消费已确认输入。
- Worker 提供认证后的 `/capabilities`，消费者检查 `schemaVersion=1`、`tenantScoped=true` 和请求租户。
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
FileServer 包要求先构建 `build/quarkus-app`，不会安装或启动 Worker。
正式构建要求干净且与 `vVERSION` 标签相同的源码。默认 `--component all` 生成两个独立包和复用其文件的组合包。
