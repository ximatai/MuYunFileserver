# MuYunFileServer PDF.js Upgrade Guide

本文档说明 `MuYunFileServer` 内置 PDF viewer 所使用的官方 `PDF.js` vendor 资源如何维护和升级。

## 当前策略

- 外层 viewer shell 继续使用 `frontend/viewer/` 下的 `Vite`
- 内层 PDF viewer 使用 **官方 PDF.js release 产物**
- 官方产物以 **vendor 版本目录** 形式固定在仓库内
- 运行时路径保持稳定为 `/viewer/pdfjs/...`
- 构建前通过脚本把当前锁定版本同步到 `frontend/viewer/public/pdfjs/`
- 当前启用版本由 `frontend/viewer/pdfjs-version.json` 单点维护

## 当前锁定版本

- `PDF.js 5.6.205`
- 当前版本文件：`frontend/viewer/pdfjs-version.json`
- vendor 源目录：`frontend/viewer/vendor/pdfjs/5.6.205/`
- 构建同步脚本：`frontend/viewer/scripts/sync-pdfjs-vendor.mjs`
- 升级脚本：`scripts/upgrade-pdfjs.sh`
- 验证脚本：`scripts/verify-pdfjs.sh`

## 为什么不用二次封装包

本项目不再使用社区 wrapper 或额外 webpack 打包层，原因如下：

- wrapper 容易引入 `worker` 与运行时版本不一致的问题
- 外层 shell 已经有自己的产品逻辑，不需要再套一层第三方集成框架
- 直接 vendor 官方 release，升级链路更短，排障更直接

## 升级步骤

1. 运行升级脚本：

```bash
./scripts/upgrade-pdfjs.sh 5.7.000
```

该脚本会自动完成：

- 下载目标版本官方 release zip
- 解压 release 产物
- 写入 `frontend/viewer/vendor/pdfjs/<version>/`
- 更新 `frontend/viewer/pdfjs-version.json`

2. 运行验证脚本：

```bash
./scripts/verify-pdfjs.sh
```

3. 如需本地浏览器联调，再启动服务并验证 `/view/...`

## 升级回归清单

每次升级 PDF.js 后，至少回归以下场景：

- 原始 `PDF` 文件能在 `/view/...` 正常打开
- `docx -> pdf` 预览能在 `/view/...` 正常打开
- `pptx -> pdf` 预览能在 `/view/...` 正常打开
- token 模式的 `contentUrl` 能被官方 viewer 正常读取
- 反向代理前缀场景下，`/fileserver/view/...` 能正常打开
- 官方 viewer 内的下载按钮仍能下载 PDF 预览内容
- 外层 MuYun viewer 的“下载原文件”按钮仍能下载源文件

## 允许修改与禁止修改

允许的修改：

- 调整外层 MuYun viewer shell
- 调整 iframe 嵌入方式
- 调整 vendor 同步脚本
- 调整 PDF.js 版本号与 vendor 目录

不建议的修改：

- 直接手改 vendor 目录中的官方源码
- 重新把官方 viewer 再打包进自定义 webpack 流水线
- 再次引入社区 wrapper 作为官方 viewer 的中间层

## 维护原则

- 官方 PDF.js 资源尽量保持原样
- 项目只维护“薄适配层”，不维护一套自研 PDF viewer
- 升级时优先替换 vendor 版本，不优先打补丁
