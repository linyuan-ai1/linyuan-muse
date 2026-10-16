# 素材来源与致谢 · Credits & Attribution

本文件说明 App 内置「群聊角色提示词库」的来源、版权状态与处理方式。

## 一句话说明

App 里用于群聊讨论的**专家 / 开发角色 / 名人思维 / 工程师人格等提示词，大部分并非本项目原创**——它们是过去一段时间从 GitHub 上各类开源提示词项目收集整理而来，一部分还在原提示词基础上经过 AI 蒸馏、改写与融合。App 的代码与工程实现（群聊调度、记忆系统、语音链路、工具系统等）为本项目原创。

## 角色提示词库的形成过程

1. **收集**：从 GitHub 开源提示词项目中搜集可用的角色（各类 `xxx-skill`、`agency-agents` / `agency-orchestrator` 等）；
2. **蒸馏改编**：一部分在上游提示词的基础上，经 AI 参与蒸馏、改写、融合，以统一结构、中文化、并适配本 App 的群聊协同机制；
3. **工程化整合**（本项目原创部分）：统一档案格式、接入群聊调度与角色分配、按角色配置模型与思考档位等。

由于收集过程跨越较长时间、当时的来源笔记不完整，**部分素材的原始出处已无法准确追溯**。

## 已知来源（许可全文随 App 分发，见 `app/src/main/assets/licenses/`）

### 1. 软件开发全流程角色（23 个）

来源项目：
- [jnMetaCode/agency-orchestrator](https://github.com/jnMetaCode/agency-orchestrator)（Apache-2.0，中文版提示词）
- 上游：[msitarzewski/agency-agents](https://github.com/msitarzewski/agency-agents)（MIT，Copyright © 2025 AgentLand Contributors）

涉及角色：产品经理、趋势研究员、软件架构师、后端架构师、前端开发、UI 设计师、UX 架构师、UX 研究员、代码审查员、安全工程师、SRE、技术文档工程师、微信小程序开发、移动应用开发、API 测试、现实校验、性能基准测试、可访问性审计、合规审计、法务合规、政务数字化售前、中国市场本地化、智能体编排。

许可全文：[`app/src/main/assets/licenses/devroles-Apache2.txt`](app/src/main/assets/licenses/devroles-Apache2.txt)

### 2. 名人思维 / 工程师人格档案（16 个）

来源项目（[女娲 / 花叔](https://github.com/alchaincyf) 生态）：

- `alchaincyf`：steve-jobs-skill、munger-skill、feynman-skill、elon-musk-skill、taleb-skill、karpathy-skill
- [nuwa-skills](https://github.com/nuwa-skills)：buffett-skill、inamori-skill、bezos-skill、torvalds-skill、knuth-skill、turing-skill、zhangxiaolong-skill、wujun-skill、shannon-skill、vonneumann-skill

许可：MIT（Copyright © 2026 Huashu 花叔；其中 `torvalds-skill` 为 © 2026 Panmax）。

许可全文：[`app/src/main/assets/licenses/personas-MIT.txt`](app/src/main/assets/licenses/personas-MIT.txt)

## 无法追溯来源的部分（诚实说明）

以下内容**未能确认原始出处**（多为补齐角色班底时收集或蒸馏生成，散落自多方）：

- **开发角色 17 个**：容量与并发规划师、破坏性测试员、成本分析师、创意专家、数据架构师、发散思维专家、环境适配工程师、防呆设计师、增长数据分析师、人性行为设计师、逻辑架构师、开源调研员、支付与订单专家、项目统筹专家、提示词工程师、风险定级专家、阈值与容量守护者；
- **名人思维 2 个**：苏格拉底、德鲁克。

如你发现其中某段提示词出自你的项目，非常欢迎告诉我们。

## 如果你是原作者

- **希望补充署名**，或**不希望你的内容被本项目使用**：请在仓库开一个 Issue 说明来源与诉求，我们会尽快**补充署名**或**移除相关内容**；
- 也欢迎任何人提供线索，帮助完善这份清单。

## 本项目原创的部分

- 全部 App 代码（Java / Android 原生，零 androidx 依赖）；
- 群聊协同机制（角色调度、收敛、预算守卫、目标模式）、记忆系统、打字通道、端侧声纹、语音链路、80+ 工具等工程实现；
- 角色库的组织方式与接入方式。

---

> 说明：本仓库整体以 AGPL-3.0 发布；上表所列第三方素材**保留其原始许可**（MIT / Apache-2.0），许可全文随 App 分发（`app/src/main/assets/licenses/`）。
