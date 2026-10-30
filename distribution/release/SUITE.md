# FileServer / Media Worker 组合包

本包引用经过协作验证的两个独立组件；组件目录与各自独立包内文件完全相同，没有二次构建。

- `components/fileserver/`：独立文件服务，Java 21；运行见其 `RUN.md`。
- `components/media-worker/`：可选媒体处理器，Python 3.10+ / FFmpeg；运行见其 `README.md`。
- `release-manifest.json`：组合身份、组件版本、源码、清单摘要及完整文件清单。

解压后先执行 `python3 scripts/release_manifest.py verify .`，并核对可信发布渠道提供的压缩包摘要。
验证后将发布目录保持只读；外置配置、FileServer 数据和 Worker 队列分别放在持久工作目录。

FileServer 可单独使用。Worker 通过受控 HTTP 文件接收协议读写原始及衍生文件，维护自己的任务队列，
不读取 FileServer 数据库或直接操作其存储目录；业务系统负责授权、提交任务及关联结果。
两个组件独立启停、可分别升级。版本号可以不同，但须满足各组件清单的 provides/requires（见 `contracts/components.json`），
并在部署清单中记录验证过的组合。当前发布流水线同时产出同版本组件包，这是分发便利，不是协议要求。

不需要媒体处理时，无须安装 Python、FFmpeg 或启动 Worker。启用处理时，配置独立服务凭据、
请求租户、FileServer 地址及 Worker 持久工作目录；详见组件文档。升级和回退先确认任务空闲并备份队列，
不能将旧程序直接指向不兼容的新队列结构，也不能把更改默认租户作为任务归属迁移。
