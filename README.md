# MuYunFileServer

`MuYunFileServer` 是一个基于 `Quarkus` 构建的轻量文件资产服务。

一期聚焦以下正式能力：

- 文件上传
- 单文件元数据查询
- 文件下载
- 文件删除
- 健康检查

当前实现采用：

- `Quarkus REST`
- `SQLite + Flyway`
- `StorageProvider` 抽象 + 本地文件系统 / `MinIO`
- `JDBC + 手写 SQL`
- 多租户隔离
- 软删 + 后台物理清理

仓库已配置 GitHub Actions CI，在 `main` 提交和 Pull Request 上自动执行测试与构建。

## 特性概览

- 单次多文件上传
- 整单成功 / 整单失败语义
- 流式落盘与 `SHA-256` 计算
- MIME 白名单校验
- 单文件元数据查询
- 附件下载
- 软删与定时物理清理
- `liveness / readiness` 健康检查
- 首批单元测试和集成测试
- `MinIO` 对象存储适配与 `Testcontainers` 验证

## 设计边界

一期明确不包含：

- 分片上传
- 断点续传
- HTTP `Range`
- 文件预览
- 公开分享链接
- 列表搜索
- 业务对象引用治理
- 对象存储直传

## 项目文档

- [文档导航](./docs/README.md)
- [Overview Design](./docs/design/01-overview.md)
- [API Design](./docs/design/02-api.md)
- [Technical Solution](./docs/project/03-technical-solution.md)
- [Development Plan](./docs/project/04-development-plan.md)
- [Risks And Next Steps](./docs/project/05-risks-and-next-steps.md)

## 快速开始

默认配置见 [application.properties](./src/main/resources/application.properties)，一期关键配置包括：

- `mfs.storage.type`：存储类型，默认 `local`，可切换为 `minio`
- `mfs.storage.root-dir`：正式文件根目录
- `mfs.storage.temp-dir`：上传临时目录
- `mfs.storage.minio.endpoint`：MinIO / S3 兼容服务地址
- `mfs.storage.minio.access-key`：MinIO 访问密钥
- `mfs.storage.minio.secret-key`：MinIO 密钥
- `mfs.storage.minio.bucket`：对象桶名称
- `mfs.storage.minio.auto-create-bucket`：启动时自动建桶，默认 `true`
- `mfs.database.path`：SQLite 文件路径
- `mfs.upload.max-file-size-bytes`：单文件大小上限，默认 `500MB`
- `mfs.upload.max-file-count`：单次上传最大文件数，默认 `10`
- `mfs.upload.min-free-space-bytes`：最小剩余磁盘阈值
- `mfs.cleanup.deleted-retention`：软删保留期，默认 `7D`
- `mfs.cleanup.deleted-sweep-interval`：物理清理扫描周期，默认 `1H`
- `mfs.security.allowed-mime-types`：MIME 白名单

启动前需要保证：

- `SQLite` 文件所在目录可写
- `local` 模式下正式文件目录和临时目录可写
- `minio` 模式下临时目录可写，且目标对象桶可访问

开发模式运行：

```sh
./gradlew quarkusDev
```

构建：

```sh
./gradlew build
```

运行测试：

```sh
./gradlew test
```

打包后的运行入口：

```sh
java -jar build/quarkus-app/quarkus-run.jar
```

## 存储模式

### Local

默认模式为 `local`，适合本地开发和单机部署：

```properties
mfs.storage.type=local
mfs.storage.root-dir=${user.dir}/var/storage
mfs.storage.temp-dir=${user.dir}/var/tmp
```

### MinIO

切换到 `MinIO` 时，服务仍使用本地临时目录做上传预处理，正式文件写入对象存储：

```properties
mfs.storage.type=minio
mfs.storage.temp-dir=${user.dir}/var/tmp
mfs.storage.minio.endpoint=http://127.0.0.1:9000
mfs.storage.minio.access-key=minioadmin
mfs.storage.minio.secret-key=minioadmin
mfs.storage.minio.bucket=muyun-files
mfs.storage.minio.auto-create-bucket=true
```

本地可直接用 Docker 启动一个 MinIO：

```sh
docker run -d \
  --name muyun-minio \
  -p 9000:9000 \
  -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio:RELEASE.2023-09-04T19-57-37Z \
  server /data --console-address ":9001"
```

启动后可通过以下配置运行服务：

```sh
MFS_STORAGE_TYPE=minio \
MFS_STORAGE_TEMP_DIR=$(pwd)/var/tmp \
MFS_STORAGE_MINIO_ENDPOINT=http://127.0.0.1:9000 \
MFS_STORAGE_MINIO_ACCESS_KEY=minioadmin \
MFS_STORAGE_MINIO_SECRET_KEY=minioadmin \
MFS_STORAGE_MINIO_BUCKET=muyun-files \
./gradlew quarkusDev
```

Readiness 在 `local` 模式下检查目录可写性，在 `minio` 模式下检查临时目录和对象桶可访问性。

## 已实现接口

- `POST /api/v1/files`
- `GET /api/v1/files/{fileId}`
- `GET /api/v1/files/{fileId}/download`
- `DELETE /api/v1/files/{fileId}`
- `GET /q/health/live`
- `GET /q/health/ready`

上传接口当前已支持：

- 单次多文件上传
- 整单成功 / 整单失败
- 可选显式 `file_ids`
- `sha256` 计算
- MIME 白名单校验
- 临时文件落盘与失败回滚

当前测试已覆盖：

- 上传、查询、下载、删除主流程
- 租户不匹配 `403`
- 缺少身份头 `401`
- 非法 `fileId` `400`
- MIME 拒绝 `415`
- 显式 `file_id` 冲突 `409`
- 健康检查基础可用性
- `MinIO` provider 的上传、读取、删除和自动建桶验证

## 开源协议

本项目采用 [MIT License](./LICENSE)。

## 当前状态

- 设计边界已确认
- 上传语义已确定为整单成功 / 整单失败
- 上传、查询、下载、删除主链路已接通
- 已有首批集成测试和 migration 基础设施
