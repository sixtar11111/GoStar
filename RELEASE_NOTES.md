# GoStar 0.6.2

- Load executable help only from the configured library directory.
- Use a private temporary directory for each plugin process.
- Bound incoming SSE lines before allocation.
- Redact credentials from errors, answers and fragmented streaming text.
- Simplify the English and Chinese README.

中文：修复帮助脚本来源、临时目录、流式响应长度和密钥回显四处问题；精简中英文 README。

[Security review](docs/SECURITY_REVIEW_062.md) · [Test results](docs/VALIDATION_062.md)

# GoStar 0.6.1

- Renamed the plugin, Tools menu entry, window, chat identity and transcript export to **GoStar**. Kept internal module/settings IDs for upgrade compatibility.
- Added complete native creation/update actions for force, moment, force coefficient and moment coefficient reports, including wall selections, directions, references, origin, representation, optional monitors and plots.
- Supports report setup before volume meshing through STAR's native latest-mesh proxy. Rejects missing references, duplicate names and overlapping boundary/surface selections before writes.
- Chat summaries list the settings actually changed. Raw diagnostics stay in task reports; report configuration is never presented as a new CFD result.
- Added a small public distribution and bilingual documentation. Optional help uses the local STAR installation; no commercial help corpus, credentials or user simulations are shipped.

中文：本版统一更名为 GoStar；补齐气动力/力矩及系数报告的一次完整配置与读回；可同时建立监视器和历史图；修正未划网格时的报告表示绑定，拒绝缺失参考量或重复积分选面。对话简要列出实际修改，完整诊断放在详情；安装包不含用户仿真、密钥或商业帮助副本。实测范围见 `docs/VALIDATION_061.md`。
