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

- 文件上传与统一查看的类型支持矩阵由服务内部固定维护
- 常见 `MIME` 别名兼容也由服务内部处理
- 默认支持的文件类型会随 release 版本演进而扩展

大多数部署场景下，你只需要关注：

- 存储模式和目录
- 数据库路径
- 上传大小限制
- Office PDF 渲染是否启用

## Local 模式

1. 准备目录：

```sh
mkdir -p var/storage var/tmp var/data
```

2. 按需参考 `application.local.example.yml` 调整 `application.yml`

如果你要启用 `mfs.viewer.pdf-rendering.office-enabled`，请先安装 `LibreOffice`，并确保 `soffice` 可执行。

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

如果你要启用 `mfs.viewer.pdf-rendering.office-enabled`，请同时确保运行环境里可执行 `soffice`。

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
- 使用本机 `soffice` 做 Office PDF 渲染
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
- 容器内已包含 `soffice`，适合直接启用 `Office -> PDF` 查看
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
- `docx -> pdf` 查看链路

如果你希望用 compose 启动，可以使用：

```sh
docker compose -f distribution/release/compose.yaml up -d
```
## SeaweedFS 存储

可用 `compose.seaweedfs.yaml` 启动自有 S3 存储，配置参考
`application.seaweedfs.example.yml`，详细步骤和生产边界见
`docs/design/seaweed-storage.md`。需要自行生成环境凭证；示例入口仅绑定回环地址。
FileServer 使用既有 `minio` 客户端适配，不自动迁移历史文件。
若 FileServer 与存储均在容器中运行，须加入同一内部网络并使用服务地址，
不能使用 `127.0.0.1` 互访。


## FileServer / Worker 链路运维

Worker 是独立 Python 进程，需另装 Python 3.10+ 与支持 libopus/H.264/AAC 的 ffmpeg/ffprobe；
运行与全部环境变量见 `workers/media-worker/README.md`。文件服务普通身份头入口仅供可信后端，
接收 API 另配 Bearer 与租户允许列表；Worker Bearer 和接收 Bearer 分别管理，不给普通客户端。
服务经内网或受控 HTTPS 网关连接，网关约束调用方租户及总请求时长，不将身份头当作用户认证。

### 启动与验收

1. 准备独立接收桶和正式资产桶、FileServer SQLite/临时目录、Worker 持久 root；配置受限身份。
2. 启动存储、FileServer，确认 `/q/health/ready`；接收 API 默认关闭，需显式启用且桶不同。
3. 配置 Worker 固定 FileServer 地址、原默认租户、两份独立服务凭证；共享实例另配租户允许列表。
   由进程管理器保存环境，配置 CPU/内存限制、工作卷配额与 stdout 轮转，勿把密钥写进命令行或提交配置。
4. 启动 `python3 workers/media-worker/worker.py --root var/media-worker --port 8096`，使用受信任身份
   请求 `/capabilities`、`/health/ready`；共享实例必须传 `X-Tenant-Id`，健康只代表 Worker 本地状态。
5. 经业务后端创建并确认一份合成媒体接收任务，提交 `/jobs`，轮询到 READY，检查结果归属并下载解码。
   浏览器只使用业务后端授权后的短时播放凭证；不要为了验收打开公网可信身份头接口。

### 故障处理

- 401/403：核对调用身份、租户允许范围和可信网关；不能通过改任务租户或扩大公网权限绕过。
- 503：分别检查 FileServer/存储、Worker readiness、待办数量、磁盘与最近 consumer_error。
- FAILED：按 jobId/tenantId 查 JSON 日志与任务目录 process.log，排除损坏格式、磁盘或上游问题。
  修复后由可信操作方向 `POST /jobs/{id}/retry` 提交 `{}`，保持原租户；不要直接改数据库状态。
- CONFIRMING/超时：保留冻结清单并查询同一任务；恢复会核对原派生任务，不另起 UUID 掩盖不确定结果。
- 队列中的移除租户仍占容量：先排空或恢复原范围处理，不得用另一个默认租户接管。

### 备份、升级与回退

1. 阻止业务创建新接收/处理任务及录制身份继续写接收区，等待在途请求结束；停止 Worker（SIGTERM），
   确认退出，再停止 FileServer 和其他写入方。不能只停 Worker 后复制正在写的 FileServer 数据库。
2. 在同一停写窗口备份 FileServer 数据库及关联本地资产/预览目录，或接收桶和正式桶的一致对象快照；
   备份 Worker **完整 root**（jobs.db、可能存在的 journal/WAL、素材、冻结清单），另保存版本和配置。
   密钥单独加密保管；用备份副本验证 SQLite integrity_check，不在运行中的原库做手工修复。
3. 第一次升级旧 Worker 队列时，必须保留旧 FILESERVER_TENANT，升级会固定旧任务归属。
   先启动新 FileServer，再启动新 Worker，核验 capabilities、健康、旧任务归属/资产 ID；
   完整链路健康确认后再恢复业务及录制写入。
4. 回退前再次停写并备份当前状态。旧 Worker 不得直接读取新 schema；恢复升级前的程序、配置和
   **同一批次**数据库/工作目录/存储快照。恢复前必须盘点升级后新文件和对外已返回的 fileId，
   禁止直接回滚快照丢弃已经使用的资产；存在新增数据时优先向前修复或制定单独的数据迁移。
5. 恢复演练使用隔离端口与副本存储，验证源文件、衍生文件哈希和同一任务恢复后 fileId 一致，
   再确定恢复点和实际恢复时间。当前自动验收验证 Worker 队列重启，不等同于全栈灾备演练。

自动清理只涵盖 READY 本地残留与既有 FileServer 生命周期；失败现场和数据库历史不自动删除。
需要清理失败素材时先停止 Worker、备份并确认不再恢复该任务；不可删除排队/处理中的素材或冻结清单。
