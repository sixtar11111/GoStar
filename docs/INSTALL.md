# GoStar 0.6.2 · Installation / 安装

**Target / 适用环境:** licensed STAR-CCM+ 2406.0001 / 19.04.009-R8, Java 21. Linux tested. Python 3.10+ is optional for local help; the native plugin and Java API inspection do not require Python. No Siemens JAR or license is supplied.

1. Extract the release ZIP. Install `plugin/GoStar-0.6.2.nbm` through STAR's Tools → Plugins → Downloaded → Add Plugins. The unsigned-module warning is expected for this local build. Restart STAR after upgrade.
2. Open Tools → GoStar and connect the intended SIM. This is the simulation that receives edits. For first use, work on a copy.
3. Settings → provider / endpoint / model: configure your own connection. DeepSeek's model ID is editable in Model settings; use the ID accepted by your endpoint. Explicitly switching models is supported; failures never trigger a hidden fallback provider. The provider label “Codex CLI” still refers to that separate provider, not the plugin name.
4. Optional: select `runtime/` using Simulation ⋯ → Library directory. Python reads GoStar's own notes and the installed STAR help. Set `STARCCM_HOME` for nonstandard installations, or `GOSTAR_HELP_ROOT` to the installed `doc/<language>/online/STARCCMP` directory. No commercial documentation is redistributed. If help is absent, the tool reports it; it does not pretend that implementation notes are official documentation.
5. Select automatic execution or review mode, and give instructions. Read-only requests remain read-only. Structural changes are validated against the current object catalog. A new user message can start another edit task in the same session.

中文：解压后，在 STAR 的“工具 → 插件 → 已下载”安装 NBM，重启；从“工具 → GoStar”连接正确的 SIM，在设置中填写自己的模型与 API。可选资料目录为解压后的 `runtime/`。安装升级保留原模块标识和设置节点，界面名称统一为 GoStar；内部兼容标识无需用户改动。

## Output / 输出

The selected output SIM receives saved edits. Its companion `.ai-reports/` directory contains readable Markdown task reports and structured JSON records; recovery SIM paths are recorded there. Check the reported path in Details / Task report. A failed action can have partial in-memory state: inspect the actual receipt and recovery file before continuing.

对话汇报已保存修改、缺失信息和失败原因；原始错误、参数、诊断及恢复路径在“详情 / 任务报告”。配置成功不代表网格有效或 CFD 已收敛。

## Build / 自行构建

```bash
python3 plugin/build_plugin.py --star-home /path/to/STAR-CCM+19.04.009-R8 --jdk /path/to/jdk21
python3 packaging/build_distributions.py
```

The branded NBM is `build/plugin/GoStar-0.6.2.nbm`. A byte-identical NBM with the legacy module filename is retained for build/update compatibility. Do not install both copies. Plugin ZIPs contain no test classes, keys, user simulations or vendor libraries.
