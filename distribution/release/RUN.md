# Run MuYunFileServer

本目录面向 release 包使用者，不依赖源码仓库。

## 目录说明

- `quarkus-app/`：可直接运行的 Quarkus 应用
- `application.yml`：默认配置
- `application.local.example.yml`：本地文件系统示例配置
- `application.minio.example.yml`：MinIO 示例配置
- `compose.yaml`：单容器 Docker 运行模板

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

取统一 view descriptor：

```sh
curl http://127.0.0.1:8080/api/v1/files/<fileId>/view \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

说明：

- 默认支持的文件类型由服务版本决定，而不是由 release 配置枚举
- 若未来版本扩展更多图片、文本或媒体别名，通常只需升级 release 包

## 仓库维护者本地 demo

如果你是在源码仓库里维护项目，并且已经准备好了 `demo-files/`，可以直接运行：

```sh
./scripts/demo-view.sh
```

这个脚本会：

- 启动一个独立的本地 demo 环境
- 自动开启 token 模式
- 使用本机 `soffice` 做 Office 预览
- 批量上传 `demo-files/` 里的非隐藏文件
- 输出每个文件对应的 `/view/public/...` 链接

如果 `soffice` 不在 `PATH`，可以显式指定：

```sh
SOFFICE_COMMAND=/opt/homebrew/bin/soffice ./scripts/demo-view.sh
```

停止 demo 环境：

```sh
./scripts/demo-view-stop.sh
```

如果你在源码仓库里需要实时调试 viewer 前端样式和交互，建议再开一个终端运行：

```sh
cd frontend/viewer
npm run dev
```

然后把 `demo-view.sh` 输出链接中的 host 改成 `http://127.0.0.1:5173` 后再打开，例如：

```text
http://127.0.0.1:5173/view/public/files/{fileId}?access_token=...
```

这样浏览器加载的是最新前端源码，而 `/api/...` 请求会自动代理到本地后端 `http://127.0.0.1:8080`。

## Docker 运行

当前 Docker 交付采用单容器模式，镜像内已包含：

- Quarkus 应用
- 内置 viewer 静态资源
- `LibreOffice`
- 中文字体 `fonts-noto-cjk`

推荐为以下目录挂载数据卷：

- `/app/var/data`
- `/app/var/storage`
- `/app/var/tmp`

构建镜像：

```sh
./gradlew quarkusBuild -x test
docker build -f src/main/docker/Dockerfile.jvm -t muyun-fileserver:latest .
```

运行容器：

```sh
docker run -d \
  --name muyun-fileserver \
  -p 8080:8080 \
  -e QUARKUS_CONFIG_LOCATIONS=/app/application.yml \
  -v $(pwd)/var/data:/app/var/data \
  -v $(pwd)/var/storage:/app/var/storage \
  -v $(pwd)/var/tmp:/app/var/tmp \
  -v $(pwd)/application.yml:/app/application.yml:ro \
  muyun-fileserver:latest
```

说明：

- 容器内默认工作目录为 `/app`
- 若继续使用 SQLite 与本地存储，建议保持单实例部署
- 容器内已包含 `soffice`，适合直接启用 `Office -> PDF` 预览
- 中文 Office 文档依赖镜像内置的 `fonts-noto-cjk` 进行基础字体兜底

仓库维护者也可以直接使用脚本：

```sh
./scripts/docker-build.sh
./scripts/docker-run.sh
./scripts/docker-verify.sh
```

其中 `docker-verify.sh` 会覆盖：

- readiness
- `soffice` 可执行检查
- 文本 viewer
- `docx -> pdf` 预览链路

如果你希望用 compose 启动，可以使用：

```sh
docker compose -f distribution/release/compose.yaml up -d
```
