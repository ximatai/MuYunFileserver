# MuYunFileServer

`MuYunFileServer` 一期定位为独立的文件资产服务，聚焦以下正式能力：

- 文件上传
- 单文件元数据查询
- 文件下载
- 文件删除
- 健康检查

当前仓库仍处于一期骨架搭建阶段，正式业务实现将以设计文档和开发大纲为准。

## 项目文档

- [整体设计](./整体设计.md)
- [接口设计](./接口设计.md)
- [一期开发大纲](./docs/一期开发大纲.md)
- [技术方案](./docs/技术方案.md)
- [当前风险与后续事项](./docs/当前风险与后续事项.md)

## 本地开发

默认配置见 [application.properties](./src/main/resources/application.properties)，一期关键配置包括：

- `mfs.storage.root-dir`：正式文件根目录
- `mfs.storage.temp-dir`：上传临时目录
- `mfs.database.path`：SQLite 文件路径
- `mfs.upload.max-file-size-bytes`：单文件大小上限，默认 `500MB`
- `mfs.upload.max-file-count`：单次上传最大文件数，默认 `10`
- `mfs.upload.min-free-space-bytes`：最小剩余磁盘阈值
- `mfs.cleanup.deleted-retention`：软删保留期，默认 `7D`
- `mfs.cleanup.deleted-sweep-interval`：物理清理扫描周期，默认 `1H`
- `mfs.security.allowed-mime-types`：MIME 白名单

启动前需要保证：

- `SQLite` 文件所在目录可写
- 正式文件目录和临时目录可写

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

## 当前状态

- 设计边界已确认
- 上传语义已确定为整单成功 / 整单失败
- 上传、查询、下载、删除主链路已接通
- 已有首批集成测试和 migration 基础设施
