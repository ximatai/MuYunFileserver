# MuYunFileServer API Design

## 1. 文档范围

本文档定义 `MuYunFileServer` 一期对外接口设计。

本文档仅覆盖一期正式能力：

- 文件上传
- 文件重命名
- 临时文件批量转正
- 单文件元数据查询
- 文件下载
- 文件 viewer 展示描述
- 文件 viewer 页面入口
- 文件删除
- 健康检查

本文档不包含以下能力：

- 分页列表
- 搜索
- 批量删除
- 批量查询
- 批量下载
- 预览
- 公开分享
- 管理端接口

---

## 2. 设计约束

- 所有业务接口统一采用 `/api/v1` 前缀。
- 所有请求必须经过统一网关或受控上游。
- 文件服务信任网关或受控上游注入的身份上下文，不接受客户端伪造同名身份头。
- `tenant_id`、`user_id` 不出现在 URL 路径中。
- 文件对外标识使用 `ULID`。
- 文件服务一期不定义独立业务错误码体系，优先使用标准 HTTP 状态码。

面向浏览器前端的补充约束：

- 浏览器前端不应直接携带 `X-Tenant-Id`、`X-User-Id` 调用文件服务。
- 推荐接入拓扑为：浏览器 -> 业务网关 / BFF -> 文件服务。
- 若浏览器需要访问统一网关域名，也应由网关在服务端补齐可信身份头后再转发给文件服务。

---

## 3. 通用请求头

业务接口统一要求以下请求头由上游透传：

| Header | 必填 | 说明 |
|---|---|---|
| `X-Tenant-Id` | 是 | 当前租户标识 |
| `X-User-Id` | 是 | 当前调用用户标识 |
| `X-Request-Id` | 否 | 请求追踪 ID |
| `X-Client-Id` | 否 | 调用客户端标识 |

约束：

- `X-Tenant-Id` 和 `X-User-Id` 缺失时，请求应被拒绝。
- 文件服务不接受调用方通过查询参数或 URL 路径传递租户和用户身份。
- 文件服务应在日志中记录上述上下文字段。
- 一期不把浏览器跨域直连作为正式默认接入方式。

---

## 4. 通用响应约定

### 4.1 JSON 接口响应

除下载接口外，一期所有业务接口均返回 `application/json`。

成功响应建议统一结构：

```json
{
  "success": true,
  "data": {}
}
```

失败响应建议统一结构：

```json
{
  "success": false,
  "message": "human readable message",
  "request_id": "01HR...."
}
```

说明：

- 一期不引入独立 `error_code` 字段作为正式契约。
- `message` 应可直接用于日志、展示和排查，但不承诺可稳定枚举。
- 客户端程序分支应优先依赖 HTTP 状态码，而不是依赖 `message` 文案。
- `request_id` 若上游已传入则原样返回；若无则可省略。

### 4.2 常用 HTTP 状态码

| 状态码 | 使用场景 |
|---|---|
| `200 OK` | 查询成功、上传成功、删除成功 |
| `400 Bad Request` | 请求格式非法、参数校验失败、ULID 非法 |
| `401 Unauthorized` | 缺少可信身份上下文 |
| `403 Forbidden` | 当前身份无权访问该文件 |
| `404 Not Found` | 文件不存在或已软删 |
| `409 Conflict` | 请求端指定的 `file_id` 已存在 |
| `413 Payload Too Large` | 文件超出大小限制 |
| `415 Unsupported Media Type` | 文件类型不允许 |
| `429 Too Many Requests` | 可选，网关或服务端限流 |
| `500 Internal Server Error` | 未分类内部错误 |
| `507 Insufficient Storage` | 可选，磁盘空间不足 |

说明：

- 若团队不希望使用 `507`，也可以统一用 `503` 表达存储资源暂不可用。
- 对已软删文件，查询和下载统一返回 `404`。

---

## 5. 数据模型

### 5.1 文件元数据响应模型

```json
{
  "id": "01JXXXXXXXXXXXXXXX",
  "tenant_id": "tenant-a",
  "original_filename": "contract.pdf",
  "extension": "pdf",
  "mime_type": "application/pdf",
  "size_bytes": 102400,
  "sha256": "abcdef123456...",
  "status": "ACTIVE",
  "temporary": false,
  "remark": "imported by crm",
  "uploaded_by": "u123",
  "uploaded_at": "2026-04-01T10:00:00Z",
  "deleted_at": null
}
```

说明：

- 一期不对外暴露 `storage_key`
- 一期不对外暴露 `storage_provider`
- 一期不对外暴露本地路径
- 一期不返回预览相关字段

### 5.2 多文件上传结果模型

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "01JXXXXXXXXXXXXXXX",
        "original_filename": "a.pdf",
        "mime_type": "application/pdf",
        "size_bytes": 12345,
        "uploaded_at": "2026-04-01T10:00:00Z",
        "temporary": false
      },
      {
        "id": "01JYYYYYYYYYYYYYYY",
        "original_filename": "b.png",
        "mime_type": "image/png",
        "size_bytes": 23456,
        "uploaded_at": "2026-04-01T10:00:01Z",
        "temporary": false
      }
    ]
  }
}
```

说明：

- `items` 仅在整次上传成功时返回。
- 一期多文件上传采用整单成功 / 整单失败语义，不返回部分成功结果。

---

## 6. 接口列表

### 6.1 能力矩阵

同一套文件能力按访问模式划分如下：

| 能力 | 可信身份头模式 | 短时 token 模式 |
|---|---|---|
| 上传 | `POST /api/v1/files` | `POST /api/v1/public/files?access_token=...` |
| 重命名 | `PUT /api/v1/files/{fileId}/name` | - |
| 临时文件批量转正 | `POST /api/v1/files/promote` | - |
| 单文件元数据查询 | `GET /api/v1/files/{fileId}` | `GET /api/v1/public/files/{fileId}?access_token=...` |
| 下载 | `GET /api/v1/files/{fileId}/download` | `GET /api/v1/public/files/{fileId}/download?access_token=...` |
| 展示描述 | `GET /api/v1/files/{fileId}/view` | `GET /api/v1/public/files/{fileId}/view?access_token=...` |
| viewer 内容 | `GET /api/v1/files/{fileId}/view/content` | `GET /api/v1/public/files/{fileId}/view/content/{accessToken}` |
| 删除 | `DELETE /api/v1/files/{fileId}` | `DELETE /api/v1/public/files/{fileId}?access_token=...` |

viewer 页面入口：

- `GET /view/files/{fileId}`
- `GET /view/public/files/{fileId}?access_token=...`

说明：

- 可信身份头模式依赖统一网关或受控上游注入身份上下文
- 短时 token 模式当前覆盖上传、查询、下载、预览、删除
- 当前内置 viewer 已正式支持 `PDF`、`Office -> PDF`、主流图片、纯文本、原始音频和原始视频在线展示
- 纯文本 viewer 首版覆盖 `text/plain`、`text/markdown`、`text/csv`、`text/xml`、`application/json`、`application/xml`
- 纯文本 viewer 首版对超大文本返回明确错误并引导下载，不做分页和流式 tail
- 音频与视频 viewer 首版直接返回原始媒体流，交给浏览器原生控件播放，不做转码和流媒体分发

### 6.2 接口明细

一期接口列表如下：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/files` | 上传一个或多个文件 |
| `POST` | `/api/v1/public/files?access_token=...` | 使用短时上传 token 上传一个或多个文件 |
| `PUT` | `/api/v1/files/{fileId}/name` | 重命名文件 |
| `POST` | `/api/v1/files/promote` | 批量转正临时文件 |
| `GET` | `/api/v1/files/{fileId}` | 查询单文件元数据 |
| `GET` | `/api/v1/files/{fileId}/download` | 下载文件 |
| `GET` | `/api/v1/files/{fileId}/view` | 返回 viewer 展示描述 |
| `GET` | `/api/v1/public/files/{fileId}?access_token=...` | 使用短时只读 token 查询单文件元数据 |
| `GET` | `/api/v1/public/files/{fileId}/download?access_token=...` | 使用短时下载 token 下载文件 |
| `GET` | `/api/v1/public/files/{fileId}/view?access_token=...` | 使用短时只读 token 获取 viewer 展示描述 |
| `DELETE` | `/api/v1/files/{fileId}` | 软删文件 |
| `DELETE` | `/api/v1/public/files/{fileId}?access_token=...` | 使用短时删除 token 软删文件 |
| `GET` | `/view/files/{fileId}` | 内置 viewer 页面 |
| `GET` | `/view/public/files/{fileId}?access_token=...` | 内置 public viewer 页面 |
| `GET` | `/q/health/live` | 存活检查 |
| `GET` | `/q/health/ready` | 就绪检查 |

---

## 7. 上传接口

### 7.1 接口定义

- 方法：`POST`
- 路径：`/api/v1/files`
- Content-Type：`multipart/form-data`

### 7.2 表单字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `files` | 是 | 文件内容，可重复出现，支持多文件 |
| `file_ids` | 否 | 与文件顺序对应的可选 `ULID` 列表 |
| `remark` | 否 | 统一备注 |
| `temporary` | 否 | 是否按临时文件写入，默认 `false` |

说明：

- 一期允许单次请求上传多个文件。
- 单次请求最多 10 个文件。
- 默认单文件大小上限为 `524288000` 字节，也就是 `500 MB`。
- 默认允许的 MIME 类型为 `application/pdf`、`image/png`、`image/jpeg`、`text/plain`。
- `temporary=true` 时，文件会进入临时文件清理窗口；默认上传为正式文件。
- 若提供 `file_ids`，则每个值必须为合法 `ULID`。
- 若部分文件未提供 `file_id`，则由服务端生成。
- `file_ids` 按出现顺序与 `files` 一一对应。
- `file_ids` 数量不得大于 `files` 数量，否则返回 `400 Bad Request`。
- 若某个显式指定的 `file_id` 已存在，则整次上传返回 `409 Conflict`。

### 7.3 请求示例

```bash
curl -X POST "http://localhost:8080/api/v1/files" \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-User-Id: u123" \
  -F "files=@contract.pdf" \
  -F "files=@image.png" \
  -F "file_ids=01JABCDEF1234567890ABCDEF" \
  -F "temporary=true" \
  -F "remark=crm upload"
```

### 7.4 成功响应

- 状态码：`200 OK`

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "01JABCDEF1234567890ABCDEF",
        "original_filename": "contract.pdf",
        "mime_type": "application/pdf",
        "size_bytes": 102400,
        "uploaded_at": "2026-04-01T10:00:00Z",
        "temporary": true
      },
      {
        "id": "01JQWERTY1234567890QWERTY",
        "original_filename": "image.png",
        "mime_type": "image/png",
        "size_bytes": 20480,
        "uploaded_at": "2026-04-01T10:00:01Z",
        "temporary": true
      }
    ]
  }
}
```

### 7.5 失败场景

| 场景 | 状态码 |
|---|---|
| 无身份上下文 | `401` |
| multipart 非法 | `400` |
| `file_id` 非法 | `400` |
| `file_id` 冲突 | `409` |
| 文件数量超限 | `400` |
| 单文件过大 | `413` |
| 文件类型不允许 | `415` |
| 磁盘空间不足 | `507` 或 `503` |
| 内部写入失败 | `500` |

说明：

- 一期多文件上传采用整单成功 / 整单失败语义。
- 任一文件校验失败、写入失败或回滚失败，整次上传均视为失败。
- 若请求整体就不成立，例如缺少 `files` 字段、multipart 结构错误或 `file_ids` 数量非法，应直接返回 `400`。

### 7.6 短时 token 上传接口

- 方法：`POST`
- 路径：`/api/v1/public/files`
- Query 参数：`access_token`
- Content-Type：`multipart/form-data`

表单字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `files` | 是 | 文件内容，可重复出现，支持多文件 |
| `remark` | 否 | 统一备注 |
| `temporary` | 否 | 是否按临时文件写入，默认 `false` |

说明：

- 该入口不要求 `X-Tenant-Id`、`X-User-Id`
- 业务后端负责业务授权和上传 token 签发
- 文件服务只校验 token 是否允许在目标租户下执行本次上传
- 上传 token 至少应包含 `tenant_id`、`sub`、`purpose=upload`、`exp`
- 上传 token 必须单独签发，并带 `purpose=upload`
- token 上传仍经过文件服务，不是对象存储直传
- token 上传不支持 `file_ids`，文件 ID 统一由服务端生成
- token 上传支持 `temporary`，用于草稿类临时资源
- 上传成功后的元数据查询、下载、删除语义与普通上传一致

### 7.7 短时 token 上传请求示例

```bash
curl -X POST "http://localhost:8080/api/v1/public/files?access_token=eyJ..." \
  -F "files=@contract.pdf" \
  -F "files=@image.png" \
  -F "remark=crm upload"
```

### 7.8 短时 token 上传失败场景

| 场景 | 状态码 |
|---|---|
| 缺少 `access_token` | `401` |
| token 非法或验签失败 | `401` |
| token 已过期 | `401` |
| token 不允许上传 | `403` |
| multipart 非法 | `400` |
| 传入 `file_ids` | `400` |
| 文件数量超限 | `400` |
| 单文件过大 | `413` |
| 文件类型不允许 | `415` |
| 磁盘空间不足 | `507` 或 `503` |
| 内部写入失败 | `500` |

---

## 8. 单文件元数据查询接口

### 8.1 接口定义

- 方法：`GET`
- 路径：`/api/v1/files/{fileId}`

### 8.2 路径参数

| 参数 | 说明 |
|---|---|
| `fileId` | 文件 ID，必须为合法 `ULID` |

### 8.3 请求示例

```bash
curl "http://localhost:8080/api/v1/files/01JABCDEF1234567890ABCDEF" \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-User-Id: u123"
```

### 8.4 成功响应

- 状态码：`200 OK`

```json
{
  "success": true,
  "data": {
    "id": "01JABCDEF1234567890ABCDEF",
    "tenant_id": "tenant-a",
    "original_filename": "contract.pdf",
    "extension": "pdf",
    "mime_type": "application/pdf",
    "size_bytes": 102400,
    "sha256": "abcdef123456...",
    "status": "ACTIVE",
    "temporary": false,
    "remark": "crm upload",
    "uploaded_by": "u123",
    "uploaded_at": "2026-04-01T10:00:00Z",
    "deleted_at": null
  }
}
```

### 8.5 失败场景

| 场景 | 状态码 |
|---|---|
| `fileId` 非法 | `400` |
| 无身份上下文 | `401` |
| 租户不匹配或无权访问 | `403` |
| 文件不存在或已删除 | `404` |
| 内部错误 | `500` |

### 8.6 短时 token 查询接口

- 方法：`GET`
- 路径：`/api/v1/public/files/{fileId}`
- Query 参数：`access_token`

说明：

- 该入口不要求 `X-Tenant-Id`、`X-User-Id`
- 文件服务依赖 `access_token` 做只读放行
- 业务后端负责业务授权和 token 签发
- 文件服务只校验 token 是否允许读取目标文件

### 8.7 短时 token 查询请求示例

```bash
curl "http://localhost:8080/api/v1/public/files/01JABCDEF1234567890ABCDEF?access_token=eyJ..."
```

### 8.8 短时 token 查询失败场景

| 场景 | 状态码 |
|---|---|
| 缺少 `access_token` | `401` |
| token 非法或验签失败 | `401` |
| token 已过期 | `401` |
| `fileId` 非法 | `400` |
| token 与目标文件不匹配 | `403` |
| token 与文件租户不匹配 | `403` |
| 文件不存在或已删除 | `404` |
| 内部错误 | `500` |

说明：

- 当前最小实现默认关闭，需通过配置显式开启
- 第一版仅支持 `HMAC-SHA256`
- token 至少应包含 `tenant_id`、`file_id`、`exp`
- 第一版不实现严格一次性消费

---

## 8.9 文件重命名接口

### 8.9.1 接口定义

- 方法：`PUT`
- 路径：`/api/v1/files/{fileId}/name`
- Content-Type：`application/json`

请求体：

```json
{
  "originalFilename": "renamed-contract.txt"
}
```

说明：

- 仅更新展示文件名和推导扩展名，不改变存储 key
- 空文件名或空白文件名返回 `400`

### 8.9.2 成功响应

- 状态码：`200 OK`
- 返回最新文件元数据响应模型

### 8.10 临时文件批量转正接口

### 8.10.1 接口定义

- 方法：`POST`
- 路径：`/api/v1/files/promote`
- Content-Type：`application/json`

请求体：

```json
{
  "fileIds": [
    "01JABCDEF1234567890ABCDEF",
    "01JQWERTY1234567890QWERTY"
  ]
}
```

说明：

- 仅对当前租户下的文件生效
- 已经是正式文件时按幂等成功处理
- 空列表、空白值或非法 `ULID` 返回 `400`

### 8.10.2 成功响应

- 状态码：`200 OK`

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "01JABCDEF1234567890ABCDEF",
        "temporary": false
      },
      {
        "id": "01JQWERTY1234567890QWERTY",
        "temporary": false
      }
    ]
  }
}
```

### 8.10.3 失败场景

| 场景 | 状态码 |
|---|---|
| `fileIds` 为空 | `400` |
| `fileIds` 含空白值 | `400` |
| `fileId` 非法 | `400` |
| 无身份上下文 | `401` |
| 租户不匹配或无权访问 | `403` |
| 文件不存在或已删除 | `404` |
| 内部错误 | `500` |

---

## 9. 下载接口

### 9.1 接口定义

一期保留两类文件访问模式：

- 可信身份头读取：
  - `GET /api/v1/files/{fileId}`
  - `GET /api/v1/files/{fileId}/download`
  - `DELETE /api/v1/files/{fileId}`
- 短时 token 访问：
  - `GET /api/v1/public/files/{fileId}?access_token=...`
  - `GET /api/v1/public/files/{fileId}/download?access_token=...`
  - `DELETE /api/v1/public/files/{fileId}?access_token=...`

其中：

- 可信身份头访问适用于统一网关或受控上游已经注入身份头的场景
- 短时 token 访问适用于业务后端先完成业务授权，再签发短时访问 URL 的场景

### 9.2 可信身份头下载接口定义

- 方法：`GET`
- 路径：`/api/v1/files/{fileId}/download`

### 9.3 可信身份头下载响应特征

- 返回文件二进制流
- 成功时不是 JSON
- 默认以附件方式下载

### 9.4 成功响应头

建议至少包含：

| Header | 说明 |
|---|---|
| `Content-Type` | 服务端最终判定的 MIME type |
| `Content-Length` | 文件大小 |
| `Content-Disposition` | `attachment; filename=...; filename*=UTF-8''...` |

### 9.5 可信身份头下载请求示例

```bash
curl -L "http://localhost:8080/api/v1/files/01JABCDEF1234567890ABCDEF/download" \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-User-Id: u123" \
  -o contract.pdf
```

### 9.6 可信身份头下载失败场景

| 场景 | 状态码 |
|---|---|
| `fileId` 非法 | `400` |
| 无身份上下文 | `401` |
| 租户不匹配或无权访问 | `403` |
| 文件不存在或已删除 | `404` |
| 文件物理内容缺失 | `500` |
| 内部错误 | `500` |

说明：

- 一期不支持 `Range` 请求。
- 一期不支持 `inline` 下载模式切换。
- 若浏览器通过网关跨域下载，网关应暴露 `Content-Disposition`、`Content-Length`、`Content-Type` 给前端读取。
- 推荐网关配置 `Access-Control-Expose-Headers: Content-Disposition, Content-Length, Content-Type`。

### 9.7 短时 token 下载接口定义

- 方法：`GET`
- 路径：`/api/v1/public/files/{fileId}/download`
- Query 参数：`access_token`

说明：

- 该入口不要求 `X-Tenant-Id`、`X-User-Id`
- 文件服务依赖 `access_token` 做下载放行
- 业务后端负责业务授权和 token 签发
- 文件服务只校验 token 是否允许读取目标文件

### 9.8 短时 token 下载请求示例

```bash
curl -L "http://localhost:8080/api/v1/public/files/01JABCDEF1234567890ABCDEF/download?access_token=eyJ..." \
  -o contract.pdf
```

### 9.9 短时 token 下载失败场景

| 场景 | 状态码 |
|---|---|
| 缺少 `access_token` | `401` |
| token 非法或验签失败 | `401` |
| token 已过期 | `401` |
| `fileId` 非法 | `400` |
| token 与目标文件不匹配 | `403` |
| token 与文件租户不匹配 | `403` |
| 文件不存在或已删除 | `404` |
| 文件物理内容缺失 | `500` |
| 内部错误 | `500` |

说明：

- 当前最小实现默认关闭，需通过配置显式开启
- 第一版仅支持 `HMAC-SHA256`
- token 至少应包含 `tenant_id`、`file_id`、`exp`
- 第一版不实现严格一次性消费

---

## 10. 删除接口

### 10.1 接口定义

- 方法：`DELETE`
- 路径：`/api/v1/files/{fileId}`

### 10.2 行为语义

- 执行软删
- 成功后文件立即不可访问
- 不进行同步物理删除
- 物理清理由内部定时任务完成

### 10.3 请求示例

```bash
curl -X DELETE "http://localhost:8080/api/v1/files/01JABCDEF1234567890ABCDEF" \
  -H "X-Tenant-Id: tenant-a" \
  -H "X-User-Id: u123"
```

### 10.4 成功响应

- 状态码：`200 OK`

```json
{
  "success": true,
  "data": {
    "id": "01JABCDEF1234567890ABCDEF",
    "status": "DELETED",
    "deleted_at": "2026-04-01T10:30:00Z"
  }
}
```

### 10.5 失败场景

| 场景 | 状态码 |
|---|---|
| `fileId` 非法 | `400` |
| 无身份上下文 | `401` |
| 租户不匹配或无权访问 | `403` |
| 文件不存在或已删除 | `404` |
| 内部错误 | `500` |

说明：

- 成功删除后，同一文件的查询和下载接口都应返回 `404`。
### 10.6 短时 token 删除接口

- 方法：`DELETE`
- 路径：`/api/v1/public/files/{fileId}`
- Query 参数：`access_token`

说明：

- 该入口不要求 `X-Tenant-Id`、`X-User-Id`
- 文件服务依赖 `access_token` 做删除放行
- 业务后端负责业务授权和删除 token 签发
- 删除 token 必须单独签发，并带 `purpose=delete`

### 10.7 短时 token 删除请求示例

```bash
curl -X DELETE "http://localhost:8080/api/v1/public/files/01JABCDEF1234567890ABCDEF?access_token=eyJ..."
```

### 10.8 短时 token 删除失败场景

| 场景 | 状态码 |
|---|---|
| 缺少 `access_token` | `401` |
| token 非法或验签失败 | `401` |
| token 已过期 | `401` |
| `fileId` 非法 | `400` |
| token `purpose` 不为 `delete` | `403` |
| token 与目标文件不匹配 | `403` |
| token 与文件租户不匹配 | `403` |
| 文件不存在或已删除 | `404` |
| 内部错误 | `500` |

说明：

- 当前最小实现默认关闭，需通过配置显式开启
- 删除 token 第一版不做严格一次性消费
- 同一个 token 在有效期内再次调用时，若文件已删除，则返回 `404`

- 一期不提供恢复接口。
- 一期不提供对外 purge 接口。

---

## 11. 健康检查接口

### 11.1 存活检查

- 方法：`GET`
- 路径：`/q/health/live`

成功响应：

- `200 OK`

### 11.2 就绪检查

- 方法：`GET`
- 路径：`/q/health/ready`

建议检查：

- SQLite 是否可访问
- 本地文件根目录是否可写
- 临时目录是否可写

成功响应：

- `200 OK`

失败响应：

- `503 Service Unavailable`

---

## 12. 关键接口规则汇总

- 可信身份头模式下，业务请求必须带可信的 `X-Tenant-Id` 和 `X-User-Id`
- 短时 token 模式下，公开上传、查询、下载、预览、删除接口通过 `access_token` 完成授权，不要求身份头
- 一期只支持单文件查询，不支持列表和搜索
- 一期支持多文件上传，但单次请求最多 10 个文件
- 一期支持临时文件上传，默认上传为正式文件
- 一期支持批量转正临时文件
- 一期允许请求端可选指定 `ULID` 格式的 `file_id`
- 短时 token 上传接口不支持请求端显式指定 `file_id`
- 已存在的 `file_id` 返回 `409 Conflict`
- 下载统一返回附件流，不支持 `Range`
- 预览统一返回 `302` 跳转或 `inline pdf`
- viewer 页面推荐作为前端正式接入入口
- viewer shell 只依赖 `GET /api/v1/.../view` 描述协议
- 删除统一为软删，默认保留 7 天后内部物理清理

---

## 13. 后续可扩展但不属于一期的接口方向

以下仅作为后续方向记录，不属于一期接口承诺：

- `/api/v1/files:search`
- `/api/v1/files/batch-delete`
- `/api/v1/files/{fileId}/share`
- `/api/v1/admin/...`
