# Media Worker 独立部署、升级与恢复

独立包包含 `worker.py`、`README.md`、本手册、`LICENSE`、协议声明和发布清单。
只需 Python 3.10+ 与 FFmpeg/FFprobe（libopus、H.264、AAC），不需要 Java 或源码仓库。

## 安装与启动

1. 从可信正式发布渠道核对压缩包 SHA-256，解压到独立只读版本目录。
2. 执行 `python3 /opt/muyun-worker/releases/VERSION/scripts/release_manifest.py verify /opt/muyun-worker/releases/VERSION`。
3. 在发布目录之外准备专用用户可读的环境文件和可写持久目录 `/data/muyun-worker`。
   环境文件通过进程管理器注入，不写入命令行或发布目录。必填变量：
   `MEDIA_WORKER_TOKEN`（至少 24 字符）、`FILESERVER_URL`、`FILESERVER_RECEPTION_TOKEN`、`FILESERVER_TENANT`。
   地址与两份服务凭据独立配置；HTTP 请求必须提供唯一 `X-Tenant-Id`，默认租户只作旧队列兼容。
4. 在持久工作目录启动：

```sh
cd /data/muyun-worker
python3 /opt/muyun-worker/releases/VERSION/worker.py --root /data/muyun-worker --port 8096
```

仅监听回环地址；跨主机采用受控 HTTPS 代理。Bearer 凭据只交给受信业务服务，不交给浏览器。
认证后核对 `/capabilities` 的 schemaVersion、tenantScoped、tenantId 及 `/health/ready`。
完整环境变量、资源限制、错误和重试规则见同目录 `README.md`。

## 停止、备份和升级

暂停新处理任务，等待 QUEUED/PROCESSING 清空；需要保留在途任务时先 SIGTERM 并等待退出，
不要并发启动两个实例。停止后备份完整持久 root（含 jobs.db、WAL/journal、原始素材、冻结清单和日志），
保存旧版本、配置与能力清单；在副本上运行 SQLite integrity_check。

核对新 Worker 所需 fileReception 协议与目标 FileServer 提供能力有交集；业务消费者另行核对 workerJobs 能力。
保留旧 FILESERVER_TENANT，禁止借升级改变历史队列归属。启动后核对 readiness、旧任务状态、租户及资产 ID。
本地健康不代表上游可用，需经测试租户验证一次文件接收、处理、确认与结果下载。

回退也须先停止写入并备份。若无队列结构变化，可按版本说明回退程序；有结构变化时，
旧程序不能直接读取新队列，须使用验证过的迁移或一致恢复点，并盘点升级后新任务及已返回资产。
禁止覆盖已有新业务结果。失败素材只在确认不再恢复后清理，不删除排队或正在处理的冻结清单。

只替换 Worker 且不迁移 FileServer 数据时，不需要停止 FileServer。若升级或恢复 FileServer 存储，
应联合暂停所有写入方并取得文件库及对象存储的一致恢复点；Worker root 备份不等于全栈备份。
