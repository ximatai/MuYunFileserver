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

## 本地开发

开发模式运行：

```sh
./gradlew quarkusDev
```

构建：

```sh
./gradlew build
```

打包后的运行入口：

```sh
java -jar build/quarkus-app/quarkus-run.jar
```

## 当前状态

- 设计边界已确认
- 上传语义已确定为整单成功 / 整单失败
- 代码仍是 starter 基础状态，尚未完成一期业务骨架
