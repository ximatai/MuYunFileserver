# MuYunFileServer Docs

项目文档统一放在 `docs/` 目录下，并按职责拆分为两类：

- `design/`：正式设计契约，面向能力边界、接口协议和长期稳定约束
- `project/`：项目实施文档，面向技术选型、开发计划、风险和当前状态

## Design

- [Overview Design](./design/overview.md)
- [API Design](./design/api.md)
- [Server Integration Guide](./design/server-integration.md)

## Project

- [Technical Solution](./project/technical-solution.md)
- [Development Plan](./project/development-plan.md)
- [Risks And Next Steps](./project/risks-and-next-steps.md)
- [File Viewer Product Plan](./project/file-viewer-product-plan.md)
- [Signed Download Token Design Draft](./project/signed-download-token-design.md)
- [Delete Token Design Draft](./project/delete-token-design.md)

## Document Rules

- 根目录只保留面向仓库入口的 `README.md` 和通用文件
- 正式设计契约优先放在 `docs/design/`
- 项目推进、风险、任务拆解优先放在 `docs/project/`
- 后续新增文档优先使用语义化文件名，避免编号驱动命名和重复职责
