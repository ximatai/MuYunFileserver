# Run MuYunFileServer

本目录面向 release 包使用者，不依赖源码仓库。

## 目录说明

- `quarkus-app/`：可直接运行的 Quarkus 应用
- `application.yml`：默认配置
- `application.local.example.yml`：本地文件系统示例配置
- `application.minio.example.yml`：MinIO 示例配置
- `compose.yaml`：本地开发用 MinIO

## 零配置模式

当前 release 包默认采用“零配置文件类型策略”：

- 文件上传、预览和 viewer 的类型支持矩阵由服务内部固定维护
- 常见 `MIME` 别名兼容也由服务内部处理
- 默认支持的文件类型会随 release 版本演进而扩展

大多数部署场景下，你只需要关注：

- 存储模式和目录
- 数据库路径
- 上传大小限制
- Office 预览是否启用

## Local 模式

1. 准备目录：

```sh
mkdir -p var/storage var/tmp var/data
```

2. 按需参考 `application.local.example.yml` 调整 `application.yml`

如果你要启用 Office 预览，请先安装 `LibreOffice`，并确保 `soffice` 可执行。

3. 启动：

```sh
java -jar quarkus-app/quarkus-run.jar
```

## MinIO 模式

1. 启动 MinIO：

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

2. 准备临时目录和数据库目录：

```sh
mkdir -p var/tmp var/data
```

3. 按需参考 `application.minio.example.yml` 调整 `application.yml`

如果你要启用 Office 预览，请同时确保运行环境里可执行 `soffice`。

4. 启动：

```sh
java -jar quarkus-app/quarkus-run.jar
```

## 验证

Readiness：

```sh
curl http://127.0.0.1:8080/q/health/ready
```

上传：

```sh
curl -X POST http://127.0.0.1:8080/api/v1/files \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' \
  -F 'files=@/path/to/contract.pdf'
```

预览跳转：

```sh
curl -I http://127.0.0.1:8080/api/v1/files/<fileId>/preview \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

说明：

- 默认支持的文件类型由服务版本决定，而不是由 release 配置枚举
- 若未来版本扩展更多图片、文本或媒体别名，通常只需升级 release 包
