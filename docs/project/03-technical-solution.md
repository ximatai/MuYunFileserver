# MuYunFileServer Technical Solution

本文档用于固化一期实现阶段的主要技术选型、依赖建议和取舍依据。

相关文档：

- [Overview Design](../design/01-overview.md)
- [API Design](../design/02-api.md)
- [Development Plan](./04-development-plan.md)

---

## 1. 选型原则

一期技术选型遵循以下原则：

- 优先满足单实例稳定性和实现清晰度
- 优先服务于文件流式处理，而不是为未来高并发场景过度设计
- 优先选择 Quarkus 生态内成熟方案，减少无必要的框架拼装
- 对后续迁移 `PostgreSQL`、`MinIO/S3` 保持可演进性
- 不因为一期范围简单而放弃必要的抽象，但也不引入过重基础设施

---

## 2. 总体技术栈

一期建议技术栈如下：

- 运行框架：`Quarkus`
- Web 层：`Quarkus REST`
- JSON 序列化：`Jackson`
- 文件上传：`quarkus-rest-multipart`
- 元数据存储：`SQLite`
- 数据访问：`JDBC + 手写 SQL`
- 连接管理：`Agroal DataSource`
- 数据库迁移：`Flyway`
- 健康检查：`SmallRye Health`
- 定时任务：`Quarkus Scheduler`
- 参数与配置校验：`Hibernate Validator`
- MIME 探测：`Apache Tika`
- 文件哈希：JDK `MessageDigest(SHA-256)`
- 文件 ID：`ULID` 三方库 + 项目内工具封装

---

## 3. Web 与接口层

### 3.1 REST 框架

采用：

- `io.quarkus:quarkus-rest`
- `io.quarkus:quarkus-rest-jackson`
- `io.quarkus:quarkus-rest-multipart`

原因：

- 与当前 starter 基础一致，迁移成本最低
- 一期接口较少，使用 Quarkus 原生 REST 已足够
- 上传需要 multipart，下载需要流式响应，原生能力可直接覆盖
- 不额外引入其他 Web 框架或控制器抽象

### 3.2 JSON

采用 `Jackson`，不使用 JSON-B。

原因：

- DTO 控制力更强
- 对时间类型、空字段、错误响应格式的控制更直接
- 文档、示例和社区经验更丰富

---

## 4. 数据访问与数据库

### 4.1 数据库

一期使用 `SQLite`。

原因：

- 符合单实例部署边界
- 部署轻量，适合一期启动阶段
- 元数据规模和查询复杂度均较低

约束：

- 不把文件流处理放进长事务
- 设计表结构和 SQL 时兼顾未来迁移 `PostgreSQL`

### 4.2 数据访问方式

采用：

- `quarkus-agroal`
- `org.xerial:sqlite-jdbc`
- `DataSource + JDBC + 手写 SQL`

不采用：

- `Hibernate ORM`
- `Panache`
- `MyBatis`

原因：

- 一期 SQL 很少，JDBC 直接、透明、可控
- ORM 在 `SQLite + 简单模型` 场景下收益低，复杂度高
- MyBatis 也能胜任，但会额外引入新的框架约束和配置心智，不如直接 JDBC 清晰

### 4.3 为什么不使用响应式数据库访问

一期虽然采用 `Quarkus + Vert.x`，但元数据持久化仍建议使用 `JDBC`，不切换到响应式数据库 API。

原因如下：

- 一期数据库操作简单，主要是单条插入、单条查询、软删更新和定时清理查询
- 系统主要复杂度来自文件上传、落盘、哈希计算、回滚和下载流，不在数据库访问
- `SQLite` 本身不适合追求高并发写入，换成响应式 API 也不能改变其写锁特性
- 响应式数据库访问会提升实现复杂度，包括事务包装、错误传播和与文件系统操作的编排成本
- 一期目标是稳定和可读，而不是在元数据访问层追求极致异步化

落地原则：

- Web 层和文件链路保持流式处理
- 数据访问层允许使用阻塞 JDBC
- 阻塞数据库操作不得运行在 event loop 上，应放在 worker 线程语境中执行

结论：

- 一期采用“流式文件处理 + JDBC 持久化”的组合
- 后续若数据库迁移为 `PostgreSQL` 且并发读写需求显著提升，再评估响应式数据库访问

### 4.4 Migration

采用：

- `io.quarkus:quarkus-flyway`

原因：

- 文档已明确使用版本化 SQL migration
- 启动自动执行 migration 是成熟场景，优先使用现成方案
- Flyway 适合管理简单、稳定、可审阅的 SQL 文件

规则：

- migration 文件按版本编号管理
- 应用启动时自动执行
- migration 失败则启动失败

---

## 5. 文件处理与存储

### 5.1 存储抽象

定义 `StorageProvider` 抽象，默认实现为本地文件系统，并支持切换到 `MinIO`。

目的：

- 隔离底层文件读写细节
- 屏蔽本地路径语义，不向上层和对外协议泄露
- 为后续迁移 `MinIO/S3` 预留演进点

当前落地方式：

- `local` 模式负责本地正式文件存储
- `minio` 模式负责对象存储正式文件写入
- 两种模式统一复用本地临时目录做上传预处理、`sha256` 计算和 MIME 探测

### 5.2 文件系统实现

本地存储实现负责：

- 临时文件写入
- 正式文件落盘
- 文件读取输出
- 文件删除
- 启动时和定时任务中的临时目录清理

`storageKey` 规则：

```text
tenant/{tenantId}/yyyy/MM/dd/{ulid}
```

### 5.3 MinIO 实现

`MinIO` 存储实现负责：

- 启动时校验客户端配置
- 按配置自动创建 bucket
- 将临时文件上传为对象
- 读取、删除和存在性检查
- 复用统一的 `storageKey` 规则，避免上层区分本地路径与对象 key

取舍：

- 一期不做预签名直传，仍由服务端接收上传并写入对象存储
- 一期不引入 multipart upload、高级元数据或对象标签
- 先以 `MinIO` 验证 `S3` 兼容抽象，后续再评估更广义的对象存储差异

### 5.4 MIME 探测

采用：

- `Apache Tika`

策略：

- 以服务端识别的 MIME type 为主
- 以原始文件扩展名为辅助判断
- 使用白名单策略控制允许类型

原因：

- 仅依赖上传头或扩展名不可靠
- `Files.probeContentType` 跨平台稳定性不足
- Tika 足以满足一期基础 MIME 探测需求

约束：

- 一期只做基础 MIME 探测，不引入内容分析、预览解析等重能力

### 5.5 哈希计算

采用 JDK 自带 `MessageDigest` 计算 `SHA-256`。

原因：

- 无额外依赖
- 能满足流式处理
- 足够覆盖一期元数据要求

---

## 6. 配置、校验与运行时支持

### 6.1 配置

采用：

- `@ConfigMapping`

原因：

- 配置项较多时更清晰
- 比零散的 `@ConfigProperty` 更适合文件服务配置模型
- 类型安全更强

建议配置前缀：

```text
mfs.*
```

其中存储相关配置分为：

- `mfs.storage.type=local|minio`
- `mfs.storage.root-dir`：`local` 模式正式文件目录
- `mfs.storage.temp-dir`：两种模式共用的上传临时目录
- `mfs.storage.minio.*`：`minio` 模式连接参数和 bucket 配置

### 6.2 参数与配置校验

采用：

- `io.quarkus:quarkus-hibernate-validator`

用途：

- 配置合法性校验
- 请求参数和路径参数校验
- DTO 级基础约束

说明：

- 这里只把它作为通用校验工具使用，与 ORM 无关

### 6.3 健康检查

采用：

- `io.quarkus:quarkus-smallrye-health`

原因：

- 统一暴露数据库与存储可用性
- `local` 模式可检查目录写权限
- `minio` 模式可检查 bucket 可访问性和临时目录可写性

- 一期已明确需要 `liveness` 和 `readiness`
- Quarkus 有现成约定路径
- 只需增加自定义 readiness 检查即可

就绪检查建议覆盖：

- `SQLite` 可访问
- `local` 模式下正式文件目录可写
- `minio` 模式下对象桶可访问
- 临时目录可写

### 6.4 定时任务

采用：

- `io.quarkus:quarkus-scheduler`

原因：

- 一期只有轻量内部清理任务
- 单实例部署下无需引入更重任务框架

用途：

- 软删文件物理清理
- 残留临时文件清理

---

## 7. 日志与审计

一期不强绑定 `logback`，采用 Quarkus 默认日志体系输出结构化日志。

原因：

- Quarkus 默认日志实现已能满足一期要求
- 关键目标是输出结构化字段，而不是绑定某个具体日志框架
- 避免为了日志底座切换增加不必要复杂度

要求：

- 上传、查询、下载、删除的成功和失败都要记录
- 日志至少包含 `file_id`、`tenant_id`、`user_id`、`request_id`、操作类型、结果、失败原因

---

## 8. 测试方案

采用：

- `quarkus-junit5`
- `rest-assured`

原则：

- 以接口级集成测试为主
- 辅以少量工具类和规则类单元测试
- 文件系统测试使用临时目录隔离

重点覆盖：

- 上传、查询、下载、删除主链路
- 非法 header、非法 `ULID`、MIME 拒绝、回滚语义
- readiness 和清理任务的基础行为

---

## 9. 推荐依赖清单

建议引入以下依赖：

- `io.quarkus:quarkus-arc`
- `io.quarkus:quarkus-rest`
- `io.quarkus:quarkus-rest-jackson`
- `io.quarkus:quarkus-rest-multipart`
- `io.quarkus:quarkus-agroal`
- `io.quarkus:quarkus-flyway`
- `io.quarkus:quarkus-smallrye-health`
- `io.quarkus:quarkus-scheduler`
- `io.quarkus:quarkus-hibernate-validator`
- `org.xerial:sqlite-jdbc`
- `org.apache.tika:tika-core`
- `de.huxhorn.sulky:de.huxhorn.sulky.ulid`

---

## 10. 后续实现要求

- 除非设计边界变化，否则实现阶段不再重复讨论上述基础选型
- 若未来需要偏离本方案，应先修改本文档并说明原因
- 开发过程中的新依赖引入应优先检查是否与本文档约束冲突
