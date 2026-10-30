# Run MuYunFileServer

本目录面向 release 包使用者，不依赖源码仓库。

## 目录说明

- `release-manifest.json`：统一版本、源码提交和完整文件 SHA-256 清单
- `scripts/release_manifest.py`：发布包完整性校验工具
- `quarkus-app/`：可直接运行的 Quarkus 应用
- `application.yml`：默认配置
- `application.local.example.yml`：本地文件系统示例配置
- `application.minio.example.yml`：MinIO 示例配置
- `compose.yaml`：单容器 Docker 运行模板

## 统一版本与默认部署

FileServer 与媒体 Worker 分别提供独立包，组合包引用相同组件目录。部署清单分别记录版本、
来源和路径；兼容依据是媒体处理协议，不要求组件版本号相同。组合包布局见其 README.md。地址、租户授权、存储和凭据来自外置配置，
不修改程序文件，也不把业务配置提交到开源仓库。归档标签不属于可部署正式版本。

解压后、写入环境配置或创建数据目录前校验：

```sh
python3 scripts/release_manifest.py verify .
```

此命令核对当前包内所有文件，拒绝文件缺失、变更、额外文件或符号链接；
不能代替可信发布渠道和外部压缩包 SHA-256 校验。旧版本未提供清单时继续使用该版本发布附件的摘要，
不要为旧制品伪造新的构建来源。验证后将发布目录保持只读，使用外部工作目录和配置文件。

默认使用本地文件系统与 SQLite，适合本地验证；上传类型由内建 MIME 策略管理，也支持显式配置覆盖。
普通身份头 API 只面向可信后端，不能把“零配置本地启动”理解为“可直接公开到互联网”。
签名访问、内部接收和测试控制台默认关闭；媒体 Worker 按需单独启动。
公开访问须配置签名密钥与受控路由；接收及 Worker 使用独立服务凭据，业务后端承担用户与租户授权。
Office 预览默认开启，需要安装 LibreOffice；无需此能力时显式关闭 `office-enabled`。

## 外置配置与工作目录

以下命令中 `release_dir` 指向解压后的绝对路径。先校验，再创建外部配置与工作目录；
发布目录只读，启动和配置都不能修改其中的文件。

```sh
release_dir=/opt/muyun-fileserver/releases/VERSION
python3 "$release_dir/scripts/release_manifest.py" verify "$release_dir"
mkdir -p /data/muyun-fileserver /etc/muyun-fileserver
cp "$release_dir/application.local.example.yml" /etc/muyun-fileserver/application.yml
```

按环境修改 `/etc/muyun-fileserver/application.yml`，存储、临时文件、SQLite 和 Office profile
目录使用外部绝对路径或外部工作目录。默认 Office 预览需安装 LibreOffice，无此能力时显式关闭。

```sh
cd /data/muyun-fileserver
java -Dquarkus.config.locations=/etc/muyun-fileserver/application.yml \
  -jar "$release_dir/quarkus-app/quarkus-run.jar"
```

MinIO/S3 模式改为复制 `application.minio.example.yml` 或 `application.seaweedfs.example.yml`
到外置配置目录，准备独立的存储凭据和桶；存储示例见包内 `compose.seaweedfs.yaml`。
配置与数据目录由运行账号管理，环境凭据不放入版本目录。容器部署也须保持相同的外置边界。

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
运行与全部环境变量见独立 Worker 包中的 `README.md`。文件服务普通身份头入口仅供可信后端，
接收 API 另配 Bearer，租户授权由可信业务后端负责；Worker Bearer 和接收 Bearer 分别管理，不给普通客户端。
服务经内网或受控 HTTPS 网关连接，网关约束调用方租户及总请求时长，不将身份头当作用户认证。

### 启动与验收

1. 准备独立接收桶和正式资产桶、FileServer SQLite/临时目录、Worker 持久 root；配置受限身份。
2. 启动存储、FileServer，确认 `/q/health/ready`；接收 API 默认关闭，需显式启用且桶不同。
3. 配置 Worker 固定 FileServer 地址、`FILESERVER_TENANT` 兼容默认值及两份独立服务凭证。
   HTTP 请求归属始终由 `X-Tenant-Id` 决定；升级旧队列须保留原默认租户，不能借此改变历史任务归属。
   由进程管理器保存环境，配置 CPU/内存限制、工作卷配额与 stdout 轮转，勿把密钥写进命令行或提交配置。
4. 启动 `python3 /path/to/MuYunMediaWorker/worker.py --root /data/media-worker --port 8096`，使用受信任身份
   请求 `/capabilities`、`/health/ready`；所有请求必须传 `X-Tenant-Id`，健康只代表 Worker 本地状态。
5. 经业务后端创建并确认一份合成媒体接收任务，提交 `/jobs`，轮询到 READY，检查结果归属并下载解码。
   浏览器只使用业务后端授权后的短时播放凭证；不要为了验收打开公网可信身份头接口。

### 故障处理

- 401/403：核对调用身份、任务租户归属和可信网关；不能通过改任务租户或扩大公网权限绕过。
- 503：分别检查 FileServer/存储、Worker readiness、待办数量、磁盘与最近 consumer_error。
- FAILED：按 jobId/tenantId 查 JSON 日志与任务目录 process.log，排除损坏格式、磁盘或上游问题。
  修复后由可信操作方向 `POST /jobs/{id}/retry` 提交 `{}`，保持原租户；不要直接改数据库状态。
- CONFIRMING/超时：保留冻结清单并查询同一任务；恢复会核对原派生任务，不另起 UUID 掩盖不确定结果。
- 持久任务按原租户处理；不得通过更改默认租户来转移任务归属。

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
