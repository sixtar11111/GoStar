# GoStar 0.6.2 security review

Base repository: `sixtar11111/GoStar`, commit `a1500a0e64da6d76d257e197b6356b61db9c3717`.

Four confirmed findings in 0.6.1 were reproduced locally and corrected in 0.6.2:

| Finding | Impact | Correction |
| --- | --- | --- |
| Working-directory helper discovery | A help script placed in the launch directory could be selected for execution without choosing it in the library settings. | Help executables are resolved only from the selected library or the explicit environment configuration. |
| Shared predictable temporary directory | CLI temporary files used a shared `/tmp/gostar` directory that another local account could pre-create. | A fresh, process-private temporary directory is created with platform secure defaults. |
| Unbounded SSE line allocation | An oversized streaming line was allocated before its length was checked. | Incoming lines are capped while reading, before large allocation. |
| Incomplete response redaction | HTTP-200 error objects and text/SSE responses could echo the credential into diagnostics or chat. | Credentials are redacted from errors and output, including keys split across SSE fragments. |

The baseline reproducer showed all four findings with synthetic data. The regression suite uses an in-process loopback server, harmless file markers and synthetic credentials. It makes no external API calls and writes no production SIM.

The review also checked the selected-model route, HTTPS and redirect policy, native API restrictions, process invocation, publication allowlists, archive contents and credential patterns. The published 0.6.1 tree and nested release archives contained no detected credentials. JSON parsing already enforces a depth bound and rejects duplicate keys. Model-derived operations use structured plans and checked native dispatch.

中文：已修复当前目录帮助脚本自动执行、共享临时目录、流式超长行内存占用、响应中密钥回显四处问题。回归测试在本机使用模拟服务和合成密钥完成。
