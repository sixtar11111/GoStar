# GoStar 0.6.1 validation / 验证范围

**Status: PASS for the scope below.** Tested on Linux with licensed STAR-CCM+ 2406.0001 / 19.04.009-R8 and Java 21. No production user SIM was opened or changed. No external model API or user key was used in this release's tests.

| Layer / 层次 | Measured result / 实测结果 |
| --- | --- |
| Native STAR report transactions | 51 checks: four report kinds, real geometric wall binding, normalized directions, coefficient references, moment origins, default pressure+shear and idealization, monitors/plots, partial updates, validation rejection |
| Independent STAR reopen | 48 checks: persisted types, walls, references, directions/origins, force components, report pressure, representation, monitor/plot bindings; saved SIM hash unchanged |
| Report chat controller and branding | 15 checks: model-selected creation, schema/catalog feedback, follow-up edit in the same conversation, exact endpoint/model route, GoStar menu and chat header; synthetic executor and loopback model responses |
| Existing API chat controller / summaries | 28 + 30 checks |
| Existing multi-turn controller regression | 371 checks; loopback responses, synthetic native bridge |
| Java documentation and property policy | 39 offline documentation + 41 runtime signatures + 34 guarded-property checks; typed-action validation smoke passed |
| Public package and help | 6 Python test cases: local documentation fixtures, missing-help disclosure, relocation, branding/integration, archive integrity, README links |

The native test created a new synthetic box and attached flow physics, then exercised the same `ConfigurationEngine` inspect → preflight → recovery-save → apply → readback → save transaction used by the UI. It saved four reports, updated two in new tasks, and rejected invalid/missing references, zero direction, invalid selection/keys, duplicate names, overlapping boundary/surface selections and monitor-name collision before new reports were created. Wall part-surface validation was also checked.

Before volume meshing, the report binds STAR's real `LatestMeshProxyRepresentation`, rather than assuming a literal “Volume Mesh” object exists. The report is configured but not numerically evaluated. Both native processes remained at **iteration 0**; **no mesh or CFD calculation was run**. Reopen did not save over or change the input SIM.

Native NBM SHA-256: `615b3cb5d009a1f48cf65103fe240f36d3adcd2e0d07e6d1ac2afc7e67502c28`.

Synthetic saved SIM SHA-256 before and after independent reopen: `c5a1d9e2afbfc4dae68bf59a4b0333a8972455ca503917fb11b8e454a130333a`. The synthetic SIM and internal logs are retained in local maintainer evidence and are not included in the public package.

## Limits / 未覆盖内容

This is not a new real DeepSeek or other external-provider acceptance test. Loopback tests verify controller routing and repeated edits, not a model's reasoning quality. No all-provider, all-command or all-version guarantee is made. Windows was not tested. The native report tests validate configuration and persistence, not CFD convergence or aerodynamic accuracy. Existing geometry/mesh/solver capabilities are retained; this release does not repeat earlier aircraft CFD cases.

中文：真实 STAR 中完成四类报告的创建、配置保存和独立重开核验；连续对话及品牌名称通过回环测试；未使用外部 API，没有修改用户仿真，也没有运行网格或 CFD。报告设置成功与获得可信的气动力结果分别判断。初次验收发现冷启动无体网格表示的问题，已用原生最新网格代理修正并重新验收；未把失败尝试计为通过。

## Reproduce / 复现

Build the plugin first. Portable checks require Python 3.10+:

```bash
python3 packaging/build_distributions.py
python3 -m unittest discover -s tests -p 'test_gostar_release.py'
```

For the following Linux tests, set `STARCCM_HOME` / `STARCCM_JDK` if needed. Native report acceptance honors these environment variables and targets the documented STAR release. Use fresh output directories; native tests need Xvfb and a valid STAR license:

```bash
bash tests/run_reports061_native.sh /tmp/gostar-native-evidence build/plugin/GoStar-0.6.1.nbm
bash tests/run_controllers_061.sh /tmp/gostar-controller-evidence
bash tests/run_controller_059.sh /tmp/gostar-previous-controller-evidence
bash tests/run_api_policy_060.sh /tmp/gostar-api-policy-evidence
```

The native runner installs the release NBM in a temporary profile and injects test fixtures into that profile only. The distributed NBM is never modified and contains no test fixtures. Each owned test process group is cleaned up; no user STAR process is stopped.
