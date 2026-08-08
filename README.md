# MuYunFileServer

`MuYunFileServer` 是一个基于 `Quarkus` 的轻量文件资产服务，当前提供文件上传、元数据查询、统一查看、下载、删除和健康检查能力。

它适合这类场景：

- 业务系统需要一个独立的文件服务
- 需要基础的多租户隔离
- 需要本地文件系统或 `MinIO` 对象存储
- 需要清晰、可控的 `SQLite + JDBC` 实现，而不是重型基础设施

## 能力边界

当前已经支持：

- 单次多文件上传
- 临时文件上传与批量转正
- 整单成功 / 整单失败语义
- 单文件元数据查询、下载和统一查看
- 文件软删
- 定时物理清理
- `liveness / readiness` 健康检查
- `local` 和 `minio` 两种存储模式

当前明确不包含：

- 分片上传
- 断点续传
- 列表搜索
- 业务对象引用治理
- 对象存储直传

查看能力当前覆盖 `PDF`、`Office -> PDF`、主流图片、纯文本、原始音频和原始视频。Office 预览依赖服务端 `LibreOffice`。

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

### 4. 验证服务

健康检查：

```sh
curl http://127.0.0.1:8080/q/health/ready
```

期望看到的关键结果是：

```json
{"status":"UP"}
```

上传文件并取回 `fileId`：

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

取统一 view descriptor：

```sh
curl http://127.0.0.1:8080/api/v1/files/<fileId>/view \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

打开内置 viewer 页面：

```sh
open http://127.0.0.1:8080/view/files/<fileId>
```

### 部署验收台

服务提供一个轻量的部署验收页面，用于手工验证健康检查、上传、下载、预览和限时下载链接。它不提供服务端文件列表或管理能力，浏览器只保留本次会话上传的文件记录。

默认关闭。若在受控环境中需要启用，在 `application.yml` 中设置：

```yaml
mfs:
  token:
    enabled: true
    secret: a-long-random-secret
  test-console:
    enabled: true
```

然后访问 `http://127.0.0.1:8080/test-console`。页面生成的下载链接有期限（默认 15 分钟、最长 24 小时）；持有链接者可在有效期内直接下载，因此不要在不受控渠道或长期场景中使用它。

### 5. Docker 镜像

当前 Docker 交付采用单容器模式，镜像内已包含：

- Quarkus 应用
- viewer 静态资源
- `LibreOffice`
- 中文字体 `fonts-noto-cjk`

推荐构建路径：

```sh
./gradlew quarkusBuild -x test
docker build -f src/main/docker/Dockerfile.jvm -t muyun-fileserver:latest .
```

推荐验收路径：

```sh
./scripts/docker-verify.sh muyun-fileserver:latest
```

这条路径会验证：

- 容器 readiness
- `soffice` 可执行
- 文本 viewer
- `docx -> pdf` 查看链路

如果你只想快速验证项目，到这里就够了。接口接入、Office 预览和排障说明见后文。

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

### 本地 demo 浏览

如果仓库根目录下有 `demo-files/`，可以直接用脚本拉起一个独立的本地 demo 环境，并批量输出每个文件的 `/view/public/...` 链接：

```sh
./scripts/demo-view.sh
```

默认行为：

- 使用独立 demo 数据目录，不影响常规本地库和存储
- 自动打开 token 模式
- 自动使用本机 `soffice` 做 Office PDF 渲染
- 批量上传 `demo-files/` 里的非隐藏文件
- 输出 `filename / mimeType / viewerType / viewUrl`

如果 demo 目录不在默认位置，可以显式传路径：

```sh
./scripts/demo-view.sh /absolute/path/to/demo-files
```

如果本机 `soffice` 不在 `PATH` 中，可以显式指定：

```sh
SOFFICE_COMMAND=/opt/homebrew/bin/soffice ./scripts/demo-view.sh
```

停止这个 demo 环境：

```sh
./scripts/demo-view-stop.sh
```

如果需要实时调试 viewer 前端样式和交互，推荐双终端方式：

终端 A：

```sh
./scripts/demo-view.sh
```

终端 B：

```sh
cd frontend/viewer
npm run dev
```

然后将 `demo-view.sh` 输出的链接中的 host 改成 `http://127.0.0.1:5173` 再打开，例如：

```text
http://127.0.0.1:5173/view/public/files/{fileId}?access_token=...
```

此时页面始终使用最新前端源码，`/api/...` 请求会自动代理到本地后端 `http://127.0.0.1:8080`。

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

首次运行通常只需要关注这些配置：

- `mfs.storage.type`
- `mfs.storage.root-dir`
- `mfs.storage.temp-dir`
- `mfs.database.path`
- `mfs.upload.max-file-size-bytes`
- `mfs.viewer.pdf-rendering.enabled`
- `mfs.viewer.pdf-rendering.office-enabled`

切换到 `minio` 时，再额外配置：

- `mfs.storage.minio.endpoint`
- `mfs.storage.minio.access-key`
- `mfs.storage.minio.secret-key`
- `mfs.storage.minio.bucket`
- `mfs.storage.minio.auto-create-bucket`

完整默认值见 [application.yml](./src/main/resources/application.yml)。

### 零配置模式说明

文件上传与统一查看的类型支持矩阵由系统内建维护，运行时不需要配置 MIME 白名单。常见 MIME 别名也由系统内部兼容；后续新增文件类型支持时，通常通过升级服务版本获得。

## 接口接入

服务端接入默认使用可信身份头模式。调用 `/api/v1/files...` 接口时需要传：

- `X-Tenant-Id`
- `X-User-Id`
- `X-Request-Id` 可选
- `X-Client-Id` 可选

浏览器前端不应直接暴露这些身份头。推荐链路是：浏览器 -> 业务网关 / BFF -> `MuYunFileServer`，由网关、BFF 或受控后端注入身份头。

### 访问模式

同一套文件能力支持两种访问模式：

| 能力 | 可信身份头模式 | 短时 token 模式 |
|---|---|---|
| 上传 | `POST /api/v1/files` | `POST /api/v1/public/files?access_token=...` |
| 转正 | `POST /api/v1/files/promote` | `POST /api/v1/public/files/{fileId}/promote?access_token=...` |
| 元数据 | `GET /api/v1/files/{fileId}` | `GET /api/v1/public/files/{fileId}?access_token=...` |
| 下载 | `GET /api/v1/files/{fileId}/download` | `GET /api/v1/public/files/{fileId}/download?access_token=...` |
| 展示描述 | `GET /api/v1/files/{fileId}/view` | `GET /api/v1/public/files/{fileId}/view?access_token=...` |
| viewer 内容 | `GET /api/v1/files/{fileId}/view/content` | `GET /api/v1/public/files/{fileId}/view/content/{accessToken}` |
| 删除 | `DELETE /api/v1/files/{fileId}` | `DELETE /api/v1/public/files/{fileId}?access_token=...` |

公共上传 token 仅用于浏览器暂存：`POST /api/v1/public/files` 写入的文件始终是临时文件。业务服务完成校验并确认保存后，再通过转正接口使其成为正式文件。

短时 token 模式适合业务后端先完成权限校验，再给前端一个临时上传、查看、下载、转正或删除地址。token 模式默认关闭，需要显式开启 `mfs.token.enabled=true`。

token 约束：

- 当前只支持 `HMAC-SHA256`
- 上传 token 至少携带 `tenant_id`、`sub`、`purpose=upload`、`exp`
- 查询 / 下载 / 查看 token 至少携带 `tenant_id`、`file_id`、`exp`；新签发的 token 应分别使用 `purpose=metadata`、`purpose=download`、`purpose=view`
- 删除 token 必须单独签发，并携带 `purpose=delete`
- 转正 token 必须单独签发，并携带 `purpose=promote`
- 公开 token 上传支持多文件和 `remark`，不支持显式 `file_ids`

为平滑迁移，缺少 `purpose` 的既有只读 token 暂时仍可用于查询、下载和查看；显式携带错误 `purpose` 的 token 会返回 `403`。新接入方应始终签发带用途的 token。

### 常用调用

上传并拿到 `fileId`：

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

查询、下载、删除：

```bash
curl http://127.0.0.1:8080/api/v1/files/$FILE_ID \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'

curl -OJ http://127.0.0.1:8080/api/v1/files/$FILE_ID/download \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'

curl -X DELETE http://127.0.0.1:8080/api/v1/files/$FILE_ID \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

临时文件转正：

```sh
curl -X POST http://127.0.0.1:8080/api/v1/files/promote \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123' \
  -H 'Content-Type: application/json' \
  -d '{"fileIds":["01...","01..."]}'
```

多文件上传采用整单成功 / 整单失败语义，不会返回部分成功结果。当前默认限制是单次最多 `10` 个文件，单文件最大 `500 MB`。

前端如果要从响应里读取下载文件名，网关需要转发并暴露这些响应头：

```text
Content-Disposition, Content-Length, Content-Type
```

错误处理建议按 HTTP 状态码分支，`message` 用于展示或日志，`request_id` 用于关联服务端日志。

## 文件查看

内置 viewer 页面入口：

- `GET /view/files/{fileId}`
- `GET /view/public/files/{fileId}?access_token=...`

viewer 页面支持这些 URL 参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `showHeader` | `true` | 是否展示 MuYun viewer 自带顶部 header 区域 |
| `showDownloadButton` | `true` | 是否展示下载按钮 |
| `watermark` | 空 | 若非空，则在预览主区域叠加重复斜向水印 |

布尔值推荐使用 `true/false`，同时兼容 `on/off`、`1/0`、`yes/no`。这些参数只作用于 `/view/...` 页面，不改变 `/api/v1/.../view` 返回的 descriptor。

当前 viewer 支持：

- `application/pdf` 直接 inline 查看
- `doc/docx/xls/xlsx/ppt/pptx/odt/ods/odp` 转 PDF 后查看
- 主流图片直接查看
- `txt / md / json / xml / csv / log` 等 UTF-8 文本内联查看
- 音频和视频使用浏览器原生能力播放原始媒体流

Office 预览采用 `Office -> LibreOffice -> PDF -> PDF.js` 链路。首次访问时懒生成 PDF，成功后缓存预览产物。

### Office 预览运维

默认配置已经开启 Office 预览：

```yaml
mfs:
  viewer:
    pdf-rendering:
      enabled: true
      office-enabled: true
      renderer: libreoffice
      libreoffice:
        command: soffice
        timeout: 60S
        max-concurrency: 1
        retry-failure-after: 5M
        profile-root: ${user.dir}/var/tmp/libreoffice-profile
```

关键配置：

| 配置 | 说明 |
|---|---|
| `mfs.viewer.pdf-rendering.enabled` | 控制 PDF viewer；关闭后 PDF 和 Office 都不走内置 PDF 预览链路 |
| `mfs.viewer.pdf-rendering.office-enabled` | 控制 Office 转 PDF；关闭后 Office 文件只能下载 |
| `mfs.viewer.pdf-rendering.libreoffice.command` | `soffice` 可执行文件名或绝对路径 |
| `mfs.viewer.pdf-rendering.libreoffice.timeout` | 单个 Office 文件转换超时时间 |
| `mfs.viewer.pdf-rendering.libreoffice.max-concurrency` | 单实例同时转换的 Office 文件数 |
| `mfs.viewer.pdf-rendering.libreoffice.profile-root` | LibreOffice 临时用户配置目录，运行用户必须可写 |

部署注意事项：

- JVM Docker 镜像内已包含 `LibreOffice` 和中文字体 `fonts-noto-cjk`。
- release 包或裸机部署需要自行安装 `LibreOffice`，并确保服务进程能执行 `soffice`。
- 如果 `soffice` 不在 `PATH` 中，把 `libreoffice.command` 配成绝对路径。
- `mfs.storage.temp-dir` 和 `profile-root` 都需要给服务运行用户写权限。
- 生产环境先保持 `max-concurrency=1`，再结合 CPU、内存和转换耗时逐步调大。

上线前验证：

```sh
soffice --headless --version
curl http://127.0.0.1:8080/q/health/ready
```

上传一个 `docx/xlsx/pptx` 文件后访问：

```sh
curl http://127.0.0.1:8080/api/v1/files/<fileId>/view \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-User-Id: u123'
```

期望响应中的 `viewerType` 为 `pdf`，`contentMimeType` 为 `application/pdf`。浏览器打开 `http://127.0.0.1:8080/view/files/<fileId>` 应能看到转换后的 PDF 预览。

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

Office 预览常见错误：

- readiness `DOWN` 且包含 `pdfRenderer`：检查 `soffice` 是否存在、是否可执行、`profile-root` 是否可写
- `415`：通常是 `office-enabled=false` 或文件 MIME 不在当前支持列表中
- `503`：通常是 `soffice` 不可用或路径配置错误
- `504`：转换超时，可以先调大 `timeout`，同时排查文件大小、字体缺失或机器负载
- `422`：LibreOffice 没有产出有效 PDF，优先在同一台机器上用 `soffice` 手工转换该文件复现

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
- `storageKey` 规则统一为 `{tenantId}/yyyy/MM/{ulid}`

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
- [Overview Design](./docs/design/overview.md)
- [API Design](./docs/design/api.md)
- [Server Integration Guide](./docs/design/server-integration.md)
- [Technical Solution](./docs/project/technical-solution.md)
- [Development Plan](./docs/project/development-plan.md)
- [Risks And Next Steps](./docs/project/risks-and-next-steps.md)

### 当前状态

- 设计边界已确认
- 主链路已接通
- `MinIO` 存储支持已落地
- 配置文件已切换为 `application.yml`
- 仓库已配置 GitHub Actions，在 `main` 和 PR 上执行构建与测试

## 开源协议

本项目采用 [MIT License](./LICENSE)。
