# Run MuYunFileServer

本目录面向 release 包使用者，不依赖源码仓库。

## 目录说明

- `quarkus-app/`：可直接运行的 Quarkus 应用
- `application.yml`：默认配置
- `application.local.example.yml`：本地文件系统示例配置
- `application.minio.example.yml`：MinIO 示例配置
- `compose.yaml`：本地开发用 MinIO

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
