# MuYunFileServer Docs

项目文档统一放在 `docs/` 目录下，并按职责拆分为两类：

- `design/`：正式设计契约，面向能力边界、接口协议和长期稳定约束
- `project/`：项目实施文档，面向技术选型、开发计划、风险和当前状态

## Design

- [01 Overview Design](./design/01-overview.md)
- [02 API Design](./design/02-api.md)

## Project

- [03 Technical Solution](./project/03-technical-solution.md)
- [04 Development Plan](./project/04-development-plan.md)
- [05 Risks And Next Steps](./project/05-risks-and-next-steps.md)

## Document Rules

- 根目录只保留面向仓库入口的 `README.md` 和通用文件
- 正式设计契约优先放在 `docs/design/`
- 项目推进、风险、任务拆解优先放在 `docs/project/`
- 后续新增文档优先遵循 `NN-name.md` 的稳定命名方式，避免随意命名和重复职责
