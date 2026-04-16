# MuYunFileServer File Viewer Product Plan

本文档定义 `MuYunFileServer` 内置文件查看器能力的中长期产品化方案，并将其拆解为可执行、可勾选、可验收的实施清单。

相关文档：

- [API Design](../design/api.md)
- [Technical Solution](./technical-solution.md)
- [Development Plan](./development-plan.md)
- [Risks And Next Steps](./risks-and-next-steps.md)

---

## 1. 目标

建设一套内置于 `MuYunFileServer` 的文件查看器能力，使文件服务不仅能提供“文件流”，还能提供“面向 Web 的统一展示体验”。

目标分为两层：

- 后端提供统一的“可展示产物”生成与访问协议
- 前端提供统一的 viewer shell，并按文件类型选择渲染器

一期起点明确为：

- 先把 `PDF.js` 内化进项目
- 统一承接当前 `PDF / Office -> PDF` 预览能力
- 为未来图片、视频、音频、文本等展示预留稳定扩展位

---

## 2. 产品定位

这项能力不是“补一个 PDF 页面”，而是建设一个内置文件查看器平台。

统一对外能力应包含：

- 文件展示入口
- 文件下载入口
- 文件预览产物访问入口
- 基于文件类型的渲染器选择
- token 模式与可信身份头模式下的一致体验

不应采用的做法：

- 让业务前端自己猜 MIME 后直接拼各种预览 URL
- 把 `PDF.js` 当成产品架构中心
- 先做 PDF 专页，后续再往里面硬塞图片和视频逻辑

---

## 3. 范围边界

### 3.1 本方案包含

- 统一 viewer shell
- `PDF.js` 前端内化
- 后端 view descriptor 协议
- 统一 viewer 路由
- `PDF / Office` 的查看体验
- 后续图片、视频、音频、文本展示的扩展设计

### 3.2 本方案暂不包含

- 在线编辑
- PDF 批注
- 视频转码平台
- 音视频 DRM
- 全文检索
- OCR
- 浏览器本地离线缓存策略

### 3.3 一期默认约束

- 当前服务仍是单体部署
- viewer 前端随服务一起交付
- 不引入独立前端站点
- 不引入额外预览服务
- `Office` 继续走本机 `LibreOffice -> PDF`

---

## 4. 总体架构

### 4.1 两层模型

文件查看器能力拆为两层：

1. 预览解析层
   负责把源文件归一成可展示产物。

2. 展示渲染层
   负责根据展示描述加载对应 viewer。

### 4.2 统一展示入口

建议引入统一 viewer 页面入口：

- `GET /view/files/{fileId}`
- `GET /view/public/files/{fileId}?access_token=...`

该页面职责：

- 获取展示描述
- 根据 `viewerType` 选择渲染器
- 承载下载入口
- 承载加载中、失败、无权限等统一状态页

### 4.3 统一展示描述协议

建议新增 JSON 协议接口：

- `GET /api/v1/files/{fileId}/view`
- `GET /api/v1/public/files/{fileId}/view?access_token=...`

目标返回结构：

```json
{
  "fileId": "01J...",
  "displayName": "contract.docx",
  "viewerType": "pdf",
  "sourceMimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "contentMimeType": "application/pdf",
  "contentUrl": "/api/v1/public/files/01J.../view/content/<accessToken>",
  "downloadUrl": "/api/v1/public/files/01J.../download?access_token=...",
  "capabilities": {
    "download": true,
    "zoom": true,
    "pageNavigate": true,
    "rotate": false,
    "thumbnail": false
  }
}
```

说明：

- viewer shell 只消费该协议，不直接感知底层转换细节
- 后端后续若增加封面图、缩略图、视频海报、文本内容接口，可在该协议上增量扩展

---

## 5. 产品能力模型

### 5.1 Viewer Type 规划

首批统一 viewer 类型建议如下：

- `pdf`
- `image`
- `video`
- `audio`
- `text`
- `fallback`

### 5.2 文件类型与渲染器映射

建议默认映射如下：

- `application/pdf` -> `pdf`
- `doc/docx/xls/xlsx/ppt/pptx` -> `pdf`
- `image/png/jpeg/webp/gif/svg` -> `image`
- `video/mp4/webm/ogg` -> `video`
- `audio/mp3/wav/ogg/m4a` -> `audio`
- `text/plain/json/xml/csv/markdown/log` -> `text`
- 其他 -> `fallback`

### 5.3 后端产物模型演进方向

当前 `view artifact` 已可支撑 `PDF` 单产物，但后续应向多产物演进，目标包括：

- `preview_pdf`
- `thumbnail_small`
- `thumbnail_large`
- `poster_image`
- `transcoded_video_mp4`
- `text_extract`

当前阶段不要求一次做完，但后续设计和实现必须避免把模型锁死在“只有一个 PDF 产物”。

---

## 6. 前端承载方式决策

### 6.1 推荐方案

采用“独立小前端构建后嵌入 Quarkus 静态资源”的方式。

建议结构：

- `viewer-web/` 或 `frontend/viewer/` 保存前端源码
- 构建产物输出到 `src/main/resources/META-INF/resources/viewer/`

### 6.2 这样做的理由

- 可使用现代前端工程能力
- 仍保持单服务交付体验
- 不需要额外前端部署
- 后续图片、视频、文本 viewer 扩展成本更低

### 6.3 不推荐方案

- 纯手写一个 `viewer.html` 塞进资源目录
  只适合作为临时验证，不适合中长期演进。

- 独立部署一个 viewer 站点
  当前阶段过重，会显著增加 token、跨域、部署复杂度。

---

## 7. 分阶段实施计划

以下阶段按顺序推进，每个阶段都定义了输入、输出、勾选项与验收标准。

### 阶段 A：统一协议与壳层设计

目标：

- 先把 viewer 的“产品边界”定住
- 不直接进入 `PDF.js` 集成

交付物：

- viewer 路由协议
- view descriptor 接口定义
- viewerType 映射规则
- 前端目录结构方案

执行项：

- [x] A1. 定义统一 viewer 页面路由
- [x] A2. 定义 `GET /api/v1/files/{fileId}/view` 与 token 对应接口
- [x] A3. 定义 `viewerType` 枚举与默认 MIME 映射
- [x] A4. 定义 viewer shell 的状态模型：`loading / ready / unauthorized / unsupported / failed`
- [x] A5. 明确 viewer 页面与下载能力的交互边界
- [x] A6. 明确 token 模式在 viewer 页面的传递方式与安全约束
- [x] A7. 明确前端工程目录、构建方式与产物落点

验收标准：

- 文档中能明确回答“一个文件该如何被展示”
- 文档中能明确回答“业务前端应该接哪个页面和哪个协议”
- 文档中能明确回答“未来新增图片、视频时是否要改业务接入方式”

停点判断：

- 若以上问题仍需要口头解释，说明阶段 A 没完成

### 阶段 B：Viewer Shell 落地

目标：

- 建立可承载多种 renderer 的统一前端壳

交付物：

- viewer 前端工程
- viewer 静态资源挂载路径
- 基础页面框架

执行项：

- [x] B1. 初始化前端工程
- [x] B2. 配置构建产物输出到 Quarkus 静态资源目录
- [x] B3. 实现统一 viewer shell
- [x] B4. 实现路由参数解析：`fileId / access_token / mode`
- [x] B5. 实现通用页面布局：标题区、内容区、工具栏、错误态区
- [x] B6. 实现基础加载态、失败态、无权限态
- [x] B7. 提供统一下载按钮与回退行为

验收标准：

- 访问 viewer 页面时可以稳定渲染框架
- 即使还没接 PDF.js，也能通过 mock descriptor 渲染出正确状态
- 前端构建产物可以随服务一起发布

停点判断：

- 若当前只能跑单个 HTML 页，且无法扩展多 renderer，则不算通过

### 阶段 C：PDF Renderer 集成

目标：

- 将 `PDF.js` 集成为正式 renderer，而不是 demo 组件

交付物：

- `pdf` renderer
- 与后端 `view/content` 的联通能力

执行项：

- [x] C1. 引入 `PDF.js` 或 `pdfjs-dist`
- [x] C2. 封装 `pdf` renderer，作为 viewer shell 的一个插件
- [x] C3. 支持基础能力：翻页、缩放、适配宽度、加载失败提示
- [x] C4. 处理 token 模式下的 PDF 请求
- [ ] C5. 处理大 PDF 的初次加载体验
- [x] C6. 处理浏览器刷新、二次打开、深链接访问
- [x] C7. 补齐 Office 转 PDF 与原始 PDF 的统一展示体验

验收标准：

- 原始 PDF 能稳定展示
- `docx/xlsx/pptx` 经后端转换后能稳定展示
- viewer 页面不要求业务前端关心是“原始 PDF”还是“转换 PDF”

停点判断：

- 若前端仍需要区分 `pdf` 与 `office` 两种接法，则阶段 C 未完成

### 阶段 D：后端 View Descriptor 正式化

目标：

- 从“流接口可用”升级到“展示协议稳定”

交付物：

- view descriptor 接口
- 后端 viewerType 判定逻辑
- 统一下载 URL 与内容 URL 生成逻辑

执行项：

- [x] D1. 实现可信身份头模式 `view` 接口
- [x] D2. 实现 token 模式 `view` 接口
- [x] D3. 将现有预览能力接入 `viewerType=pdf`
- [x] D4. 定义 `fallback` 行为
- [x] D5. 补齐接口测试与 token 测试
- [x] D6. 明确 descriptor 字段的稳定契约

验收标准：

- 前端 viewer shell 不再直接拼 `/view/content` 或 `/download` URL
- 所有 URL 都由 descriptor 返回
- 后端能对“支持展示”和“不支持展示”给出清晰分流

停点判断：

- 若前端仍散落着 URL 拼接逻辑，阶段 D 不通过

### 阶段 E：图片展示能力

目标：

- 完成第二种正式 renderer，验证平台设计不是只适用于 PDF

交付物：

- `image` renderer
- 图片格式映射与展示体验

执行项：

- [x] E1. 定义图片类型映射规则
- [x] E2. 实现 `image` renderer
- [x] E3. 支持图片缩放、适配容器、失败回退
- [x] E4. 统一图片展示与下载按钮
- [x] E5. 验证 token 模式与可信身份头模式

验收标准：

- `png/jpg/webp/gif/svg` 至少覆盖主流浏览器展示
- viewer shell 不需要改架构，只增加 renderer 即可

停点判断：

- 若图片展示需要单独再造一套页面，说明前面的架构设计失败

### 阶段 F：视频与音频展示能力

目标：

- 增加媒体展示能力，验证壳层可扩展性

交付物：

- `video` renderer
- `audio` renderer

执行项：

- [x] F1. 定义视频与音频的支持格式清单
- [x] F2. 实现基础 `video` renderer
- [x] F3. 实现基础 `audio` renderer
- [x] F4. 明确不支持转码时的回退策略
- [ ] F5. 评估是否需要封面图与时长元数据
- [ ] F6. 明确大文件媒体展示的体验边界

验收标准：

- 原生支持格式可直接播放
- 不支持格式能正确回退下载
- viewer shell 不因媒体类型增加而出现协议分叉

停点判断：

- 若为了视频新增另一套 URL 体系或鉴权方式，阶段 F 不通过

### 阶段 G：文本展示能力

目标：

- 提供轻量文本查看器，覆盖日志、Markdown、JSON、CSV 等需求

交付物：

- `text` renderer

执行项：

- [x] G1. 定义文本类内置支持矩阵
- [x] G2. 实现文本 renderer
- [x] G3. 明确超大文本的长度与分页策略
- [x] G4. 处理编码问题与错误提示
- [x] G5. 明确代码高亮是否属于当前阶段范围

验收标准：

- 主流文本文件可在线查看
- 超大文本不会把页面直接卡死

---

## 8. 非功能要求

### 8.1 可维护性

- renderer 必须以插件方式注册，不允许把所有类型判断写死在一个组件里
- viewer shell 不直接依赖具体后端转换实现
- token 处理逻辑不应散落到各 renderer 中

### 8.2 安全性

- viewer 页面不直接暴露可信身份头模式的浏览器直连建议
- token 模式下前端不做验签，只做透明透传
- 所有真实内容访问仍以服务端鉴权结果为准

### 8.3 兼容性

- 允许在无现代前端构建链的情况下回退到静态资源发布
- viewer 能在主流桌面浏览器下稳定工作
- 首阶段移动端先保证可读，不追求完整交互体验

### 8.4 可观测性

- viewer descriptor 请求应有结构化日志
- 预览产物生成、命中缓存、失败分支应可区分
- 前端 viewer 的关键失败类型需要有统一错误码或分类文案

---

## 9. 验收矩阵

### 9.1 功能验收

- [x] PDF 原文件可在线查看
- [x] Office 文件可在线查看
- [x] 图片文件可在线查看
- [x] 视频文件可在线查看
- [x] 音频文件可在线播放
- [x] 文本文件可在线查看
- [x] 不支持类型可稳定回退下载

### 9.2 协议验收

- [x] viewer shell 只依赖统一 descriptor
- [x] token 模式与可信身份头模式有一致的页面体验
- [x] 业务前端接入时无需分别处理 PDF、Office、图片、视频

### 9.3 交付验收

- [x] viewer 前端可随 release 包一同发布
- [x] Rocky 部署流程已覆盖 viewer 产物
- [x] Docker 交付已覆盖 viewer 静态资源

### 9.4 回归验收

- [x] 下载能力未因 viewer 引入而退化
- [x] 现有 token 下载能力未被破坏
- [x] `Office -> PDF` 预览能力未被 viewer 改造破坏

---

## 10. 当前建议的推进顺序

viewer 一期主线已经完成，当前不再建议继续按阶段顺推新 renderer，而应进入“能力收口与媒体增强”阶段。

当前建议顺序：

1. 先补齐 Docker 交付中的 viewer 静态资源与 `LibreOffice` 验收说明
2. 再评估视频封面图、时长元数据与更强媒体展示边界
3. 暂不进入视频转码，除非明确出现业务刚需
4. 持续收口 PDF.js 文案、工具栏与浏览器级 smoke test

原因：

- 协议、壳层和五种正式 renderer 已经落地，继续加类型的收益明显下降
- 当前真实缺口集中在交付说明和媒体增强，而不是基础 viewer 架构
- 视频转码会显著增加复杂度，应后置到业务需求足够明确之后

---

## 11. 当前阶段的明确停点

当前版本的一期停点已经达到，具体包括：

- 完成统一 viewer 路由
- 完成 view descriptor 协议
- 完成前端 viewer shell
- 完成 `PDF.js` 集成
- 打通 `PDF / Office -> PDF`
- 完成 `image` renderer
- 完成 `text` renderer
- 完成 `video` 与 `audio` renderer

达到该停点后，产品已经形成了统一文件展示入口，并具备对外接入和线上交付价值。

此时不建议继续堆以下增强项：

- 视频转码
- 视频封面与截图编辑
- 文本高亮
- 批注
- 水印
- 搜索

这些都属于第二阶段之后按业务需求评估的增强项。

---

## 12. 下一步执行建议

基于当前项目状态，viewer 已完成 `pdf + image + text + video + audio` 五种正式 renderer。视频与音频当前仅使用浏览器原生能力播放原始媒体流，尚未进入转码、封面图与时长元数据链路：

- [x] 1. 完成图片 renderer
- [x] 2. 完成纯文本 renderer 一期
- [x] 3. 收敛 PDF.js 文案与工具栏裁剪策略，并将本地适配从 vendor 挪到同步脚本
- [x] 4. 评估并补齐 Docker 交付中的 viewer 静态资源验证
- [ ] 5. 评估视频封面图、时长元数据和大文件媒体展示边界

接下来优先处理 4 和 5，再决定是否进入视频转码和更强的多媒体能力。
