# GoStar 0.6.2 · Test results

**Result: PASS.** Linux, STAR-CCM+ 2406.0001 / 19.04.009-R8, Java 21.

| Test | Result |
| --- | --- |
| Security regressions | 12 checks passed: explicit helper selection, private temporary directory, bounded streaming reads, response/stream credential redaction |
| Native report transactions | 51 checks passed: four report kinds, wall binding, directions, references, monitors/plots, partial updates and invalid-input rejection |
| Independent native reopen | 48 checks passed; saved SIM hash unchanged |
| Report controller and branding | 15 checks passed |
| API controller and summaries | 28 + 30 checks passed |
| Existing multi-turn controller | 371 checks passed |
| API documentation and property policy | 39 + 41 + 34 checks passed; typed-action validation passed |
| Package, relocation and README | Python release tests passed |

The native tests used a new synthetic SIM. Iteration count remained zero. Controller and transport tests used local loopback responses and synthetic credentials.

**NBM SHA-256:** `8880411bf52496d387050ecc97e38e051c9a22885214b389a90de080c5cec075`.

**Saved synthetic SIM SHA-256:** `015383e269e243a37f33bc4ae2eb8dd7bb7626d6f64586a2ec3a56ad8993011c` before and after independent reopen.

中文：安全回归、连续对话、报告创建与修改、保存及独立重开均通过。测试使用隔离仿真、本地模拟服务和合成密钥，未运行网格或 CFD。

## Reproduce

```bash
python3 plugin/build_plugin.py
bash tests/run_security062.sh /tmp/gostar-security
bash tests/run_controllers_061.sh /tmp/gostar-controllers
bash tests/run_controller_059.sh /tmp/gostar-regression
bash tests/run_api_policy_060.sh /tmp/gostar-api-policy
bash tests/run_reports061_native.sh /tmp/gostar-native build/plugin/GoStar-0.6.2.nbm
python3 packaging/build_distributions.py
python3 -m unittest discover -s tests -p 'test_gostar_release.py'
```

The shell runners honor `STARCCM_HOME` and `STARCCM_JDK`. Native tests use an isolated profile and clean up their own process groups.
