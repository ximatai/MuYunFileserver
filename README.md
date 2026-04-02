# MuYunFileServer

`MuYunFileServer` 是一个基于 `Quarkus` 的轻量文件资产服务，当前提供文件上传、元数据查询、下载、删除和健康检查能力。

它适合这类场景：

- 业务系统需要一个独立的文件服务
- 需要基础的多租户隔离
- 需要本地文件系统或 `MinIO` 对象存储
- 需要清晰、可控的 `SQLite + JDBC` 实现，而不是重型基础设施

## 你能直接用什么

当前已经支持：

- 单次多文件上传
- 整单成功 / 整单失败语义
- 单文件元数据查询
- 附件下载
- 文件软删
- 定时物理清理
- `liveness / readiness` 健康检查
- `local` 和 `minio` 两种存储模式

当前明确不包含：

- 分片上传
- 断点续传
- HTTP `Range`
- 文件预览
- 公开分享链接
- 列表搜索
- 业务对象引用治理
- 对象存储直传

## 使用 Release 包

如果你只是想部署或试用服务，优先使用 GitHub Releases，而不是源码构建。

### 1. 下载

从 GitHub Releases 下载以下任一文件：

- `MuYunFileServer-<version>.zip`
- `MuYunFileServer-<version>.tar.gz`

解压后目录中会包含：

- `quarkus-app/`
- `application.yml`
- `application.local.example.yml`
- `application.minio.example.yml`
- `compose.yaml`
- `README.md`
- `RUN.md`

### 2. 配置

默认配置文件是 [application.yml](./src/main/resources/application.yml)。
如果你使用 release 包，则编辑解压目录里的同名 `application.yml`。

如果你不想从零开始写配置，可以直接参考：

- `application.local.example.yml`
- `application.minio.example.yml`

### 3. 启动

先准备目录：

```sh
mkdir -p var/storage var/tmp var/data
```

然后在 release 目录内启动：

```sh
java -jar quarkus-app/quarkus-run.jar
```

### 4. 验证

健康检查：

```sh
curl http://127.0.0.1:8080/q/health/ready
```

期望看到的关键结果是：

```json
{"status":"UP"}
```

试传一个文件：

```sh
curl -X POST http://127.0.0.1:8080/api/v1/files \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' \
  -F 'files=@/path/to/contract.pdf'
```

成功响应至少会包含：

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "01..."
      }
    ]
  }
}
```

如果你只想快速验证项目，到这里就够了。

## 从源码运行

默认配置文件是 [application.yml](./src/main/resources/application.yml)。

推荐第一次先用默认 `local` 模式验证服务是否可用。

### 3 分钟跑起来

1. 准备目录：

```sh
mkdir -p var/storage var/tmp var/data
```

2. 启动服务：

```sh
./gradlew quarkusDev
```

3. 健康检查：

```sh
curl http://127.0.0.1:8080/q/health/ready
```

4. 试传一个文件：

```sh
curl -X POST http://127.0.0.1:8080/api/v1/files \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' \
  -F 'files=@/path/to/contract.pdf'
```

其他常用命令：

运行测试：

```sh
./gradlew test
```

构建：

```sh
./gradlew build
```

打包后运行：

```sh
java -jar build/quarkus-app/quarkus-run.jar
```

## 存储模式

### Local

默认模式为 `local`，适合本地开发和单机部署。首次运行最少只需要保证这 3 个路径可写：

```yaml
mfs:
  storage:
    type: local
    root-dir: ${user.dir}/var/storage
    temp-dir: ${user.dir}/var/tmp
```

### MinIO

切换到 `MinIO` 时，服务仍使用本地临时目录做上传预处理，正式文件写入对象存储。最小配置如下：

```yaml
mfs:
  storage:
    type: minio
    temp-dir: ${user.dir}/var/tmp
    minio:
      endpoint: http://127.0.0.1:9000
      access-key: minioadmin
      secret-key: minioadmin
      bucket: muyun-files
      auto-create-bucket: true
```

本地启动 MinIO：

```sh
docker run -d \
  --name muyun-minio \
  -p 9000:9000 \
  -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  -v $(pwd)/.data/minio:/data \
  minio/minio:RELEASE.2023-09-04T19-57-37Z \
  server /data --console-address ":9001"
```

如果你想直接用环境变量启动服务：

```sh
MFS_STORAGE_TYPE=minio \
MFS_STORAGE_TEMP_DIR=$(pwd)/var/tmp \
MFS_STORAGE_MINIO_ENDPOINT=http://127.0.0.1:9000 \
MFS_STORAGE_MINIO_ACCESS_KEY=minioadmin \
MFS_STORAGE_MINIO_SECRET_KEY=minioadmin \
MFS_STORAGE_MINIO_BUCKET=muyun-files \
./gradlew quarkusDev
```

`minioadmin / minioadmin` 仅用于本地开发示例，不应直接用于生产环境。

Readiness 在两种模式下的行为：

- `local`：检查数据库、正式目录和临时目录
- `minio`：检查数据库、临时目录和对象桶可访问性

## 关键配置

如果你只是首次运行，通常只需要关注下面这些：

- `mfs.storage.type`
- `mfs.storage.root-dir`
- `mfs.storage.temp-dir`
- `mfs.database.path`
- `mfs.upload.max-file-size-bytes`
- `mfs.security.allowed-mime-types`

只有切换到 `minio` 时，才需要额外关注：

- `mfs.storage.minio.endpoint`
- `mfs.storage.minio.access-key`
- `mfs.storage.minio.secret-key`
- `mfs.storage.minio.bucket`
- `mfs.storage.minio.auto-create-bucket`

完整默认值见 [application.yml](./src/main/resources/application.yml)。

## 接口使用

当前已实现接口：

- `POST /api/v1/files`
- `GET /api/v1/files/{fileId}`
- `GET /api/v1/files/{fileId}/download`
- `GET /api/v1/public/files/{fileId}?access_token=...`
- `GET /api/v1/public/files/{fileId}/download?access_token=...`
- `DELETE /api/v1/files/{fileId}`
- `GET /q/health/live`
- `GET /q/health/ready`

调用接口时需要传请求头：

- `X-Tenant-Id`
- `X-User-Id`
- `X-Request-Id` 可选
- `X-Client-Id` 可选

### 前端接入说明

如果你是浏览器前端，不要直接把 `X-Tenant-Id`、`X-User-Id` 这类身份头暴露给用户侧代码。

- 推荐接入方式是：浏览器 -> 业务网关 / BFF -> `MuYunFileServer`
- 这些身份头应由网关、BFF 或受控后端在服务端注入
- README 里的 `curl` 示例面向联调和服务端接入，不代表浏览器应直接携带同名身份头访问文件服务

如果你的浏览器前端需要下载文件名，请同时确认网关或上游已正确转发并暴露这些响应头：

- `Content-Disposition`
- `Content-Length`
- `Content-Type`

若存在跨域访问，网关还应显式配置 `Access-Control-Expose-Headers`，至少包含：

```text
Content-Disposition, Content-Length, Content-Type
```

当前默认上传限制如下，前端可以直接据此做表单校验：

- 单次请求最多 `10` 个文件
- 单文件最大 `524288000` 字节，也就是 `500 MB`
- 默认允许的 MIME 类型：`application/pdf`、`image/png`、`image/jpeg`、`text/plain`

错误处理建议：

- 分支逻辑优先按 HTTP 状态码处理，不要依赖 `message` 文案做程序分支
- `message` 更适合直接展示或写入日志
- `request_id` 用于把前端报错和服务端日志关联起来
- 多文件上传采用整单成功 / 整单失败语义，不会返回部分成功结果

### 访问模式矩阵

同一套文件能力目前支持两种访问模式：

| 能力 | 可信身份头模式 | 短时 token 模式 |
|---|---|---|
| 上传 | `POST /api/v1/files` | 暂不支持 |
| 单文件元数据查询 | `GET /api/v1/files/{fileId}` | `GET /api/v1/public/files/{fileId}?access_token=...` |
| 下载 | `GET /api/v1/files/{fileId}/download` | `GET /api/v1/public/files/{fileId}/download?access_token=...` |
| 删除 | `DELETE /api/v1/files/{fileId}` | `POST /api/v1/public/files/{fileId}/delete?access_token=...` |

可信身份头模式：

- 适合已有统一网关、BFF 或受控后端注入 `X-Tenant-Id` / `X-User-Id` 的场景
- 文件流通常经过业务网关或由业务网关代转发

短时 token 模式：

- 适合业务后端先完成权限校验，再给前端一个短时访问地址的场景
- 当前覆盖“单文件元数据查询 + 下载 + 删除”
- 业务后端负责校验“这个用户能不能访问这个附件”
- `MuYunFileServer` 只负责校验 token 是否允许访问这个 `fileId`

推荐做法：

- 若业务网关长期转发下载流量太重，或前端只需要临时访问单文件能力，优先考虑短时 token 模式
- 若现有系统已经稳定依赖网关注入身份头，继续使用可信身份头模式即可

当前最小实现说明：

- 读取 token 默认关闭，需要显式开启 `mfs.download-token.enabled=true`
- 删除 token 默认关闭，需要显式开启 `mfs.delete-token.enabled=true`
- 第一版只支持 `HMAC-SHA256`
- token 至少应携带 `tenant_id`、`file_id`、`exp`
- 删除 token 必须单独签发，并携带 `purpose=delete`
- 删除 token 第一版不做严格一次性消费

一个典型业务流程是：

1. 前端向业务后端请求查询、下载或删除某个附件
2. 业务后端校验该用户是否有权访问该业务对象和附件
3. 业务后端签发短时 `access_token`
4. 业务后端返回可访问的短时 URL
5. 前端最终访问：
   - `GET /api/v1/public/files/{fileId}?access_token=...`
   - 或 `GET /api/v1/public/files/{fileId}/download?access_token=...`
   - 或 `POST /api/v1/public/files/{fileId}/delete?access_token=...`

### 一条完整体验路径

下面是一条从上传到删除的最短体验路径。

#### 1. 上传文件并拿到 `fileId`

```bash
FILE_ID=$(
curl -s -X POST http://127.0.0.1:8080/api/v1/files \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' \
  -F 'files=@/path/to/contract.pdf' \
  -F 'remark=crm upload' | jq -r '.data.items[0].id'
)
echo "$FILE_ID"
```

#### 2. 查询元数据

```bash
curl http://127.0.0.1:8080/api/v1/files/$FILE_ID \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

#### 3. 下载文件

```bash
curl -OJ http://127.0.0.1:8080/api/v1/files/$FILE_ID/download \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

如果你采用短时 token 模式，业务后端应先签发一个短时地址，再由前端直接访问，例如：

```text
GET /api/v1/public/files/{fileId}?access_token=...
GET /api/v1/public/files/{fileId}/download?access_token=...
POST /api/v1/public/files/{fileId}/delete?access_token=...
```

这些入口都不再要求浏览器传 `X-Tenant-Id`、`X-User-Id`。

#### 4. 删除文件

```bash
curl -X DELETE http://127.0.0.1:8080/api/v1/files/$FILE_ID \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

如果你采用删除 token，业务后端应单独签发带 `purpose=delete` 的 token，再由前端显式发起：

```text
POST /api/v1/public/files/{fileId}/delete?access_token=...
```

如果你的环境没有 `jq`，也可以直接用固定 `{fileId}` 替换下面这些独立示例。

### 上传能力说明

上传链路当前支持：

- 单次多文件上传
- 可选显式 `file_ids`
- `sha256` 计算
- MIME 白名单校验
- 临时文件落盘与失败回滚

## 运行与排查

健康检查接口：

- `GET /q/health/live`
- `GET /q/health/ready`

最常见的启动或访问失败，可以先按这个顺序排查：

- `local` 模式先看 `var/storage`、`var/tmp`、`var/data` 是否存在且可写
- 检查 `mfs.database.path` 指向的目录是否可写
- `minio` 模式先确认 `docker compose up -d minio` 已成功启动
- 检查 `mfs.storage.minio.endpoint`、`access-key`、`secret-key`、`bucket` 是否匹配
- 若 `readiness` 为 `DOWN`，先看 `/q/health/ready` 返回的具体字段
- 若上传失败，优先查看服务日志中的 `operation=upload`

关键业务日志已统一为 `key=value` 风格，便于 grep 和日志平台采集。常见字段包括：

- `operation`
- `result`
- `file_id`
- `tenant_id`
- `user_id`
- `request_id`
- `storage_provider`
- `reason`

## 开发者说明

如果你只是评估或运行服务，到上一节为止已经足够。下面这部分面向仓库维护者和后续开发者。

### 当前实现

当前技术栈：

- `Quarkus REST`
- `SQLite + Flyway`
- `JDBC + 手写 SQL`
- `StorageProvider` 抽象
- 本地文件系统 / `MinIO`
- `Apache Tika`
- `Testcontainers`

当前实现特点：

- 上传链路已完成第一轮职责拆分
- 默认存储为 `local`，`minio` 通过配置切换
- 上传在两种模式下都先走本地临时目录
- `storageKey` 规则统一为 `tenant/{tenantId}/yyyy/MM/dd/{ulid}`

### 测试状态

当前测试已覆盖：

- 上传、查询、下载、删除主流程
- 租户不匹配 `403`
- 缺少身份头 `401`
- 非法 `fileId` `400`
- MIME 拒绝 `415`
- 显式 `file_id` 冲突 `409`
- 空文件上传
- 超大文件拦截
- `file_ids` 部分显式部分自动生成
- 下载物理缺失场景
- `MinIO` provider 的上传、读取、删除和自动建桶验证
- `minio` 模式下的端到端 Quarkus 集成测试

### 项目文档

- [文档导航](./docs/README.md)
- [Overview Design](./docs/design/01-overview.md)
- [API Design](./docs/design/02-api.md)
- [Technical Solution](./docs/project/03-technical-solution.md)
- [Development Plan](./docs/project/04-development-plan.md)
- [Risks And Next Steps](./docs/project/05-risks-and-next-steps.md)

### 当前状态

- 设计边界已确认
- 主链路已接通
- `MinIO` 存储支持已落地
- 配置文件已切换为 `application.yml`
- 仓库已配置 GitHub Actions，在 `main` 和 PR 上执行构建与测试

## 开源协议

本项目采用 [MIT License](./LICENSE)。
