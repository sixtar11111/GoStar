<div align="center">

# GoStar

**在 STAR-CCM+ 内，用自然语言操作仿真。**

读取当前模型、配置原生功能、核验并保存修改。

![Version](https://img.shields.io/badge/version-0.6.1-147D92)
![Target](https://img.shields.io/badge/STAR--CCM%2B-2406-005F73)
![Java](https://img.shields.io/badge/Java-21-617078)

[English](README.md) · [安装说明](docs/INSTALL.md) · [气动力报告](docs/REPORTS.md) · [测试范围](docs/VALIDATION_061.md)

</div>

## 可以做什么

- 读取**当前选中的 SIM**，检查几何、网格设置、物理模型和报告。
- 通过受检的原生操作，创建和修改外域、边界、网格控制、物理配置、报告、监视器和场景。
- 查询本机 Java API 签名，使用带读回核验的属性接口补充配置。
- 对话简要汇报每项修改；完整参数、原始诊断和恢复文件放入任务报告。

始终由用户选中的模型决定方案。GoStar 负责检查、执行、读回和保存，不暗中切换服务商或用另一模型规划。

## 安装使用

1. 解压 `dist/` 中的 `GoStar-0.6.1.zip`（也可使用之后发布的 Release）。
2. STAR 中打开 **工具 → 插件 → 已下载 → 添加插件**，选择 `plugin/GoStar-0.6.1.nbm`，完成安装并重启。当前本地构建包未签名，安装时会显示相应提示。
3. 打开 SIM，选择 **工具 → GoStar**，确认连接的目标仿真。
4. 在**设置**中填写服务商、API 地址、模型 ID 和密钥。可选本地资料检索需要 Python 3.10+，并在**仿真 ⋯ → 资料目录**选择解压后的 `runtime/`。
5. 输入目标。自动执行模式会在校验后执行已授权的操作；审阅模式先展示方案。

密钥由使用者在本机配置，本项目不附带密钥。兼容 API 需支持本插件的聊天消息协议，不能保证所有厂商和端点都适用；连接失败会保留所选模型并明确报错。

## 示例

> 检查当前网格控制并解释参数，不修改任何设置。

> 在 Wing 壁面上创建阻力系数报告，Lab 方向 [1,0,0]，参考密度 1.18 kg/m³、速度 210 m/s、面积 2.5 m²，包含压力和剪切，关闭理想化映射，同时创建监视器和历史图。不运行 CFD。

> 只将这个报告的参考面积改为 3.25 m²，其他配置保留。

示例数值用于说明明确输入，**不是通用气动默认值**。参考面积、力矩原点等必要信息缺失时需要补充，不能随意填 1 或猜测半模倍增方式。[报告参数及约定 →](docs/REPORTS.md)

## 构建与验证

需要已获得许可的 **STAR-CCM+ 2406.0001 / 19.04.009-R8** 和配套 Java 21。目前在 Linux 验证；Windows 和其他 STAR 版本需要单独验证。

```bash
python3 plugin/build_plugin.py --star-home /path/to/STAR-CCM+19.04.009-R8 --jdk /path/to/jdk21
python3 packaging/build_distributions.py
python3 -m unittest discover -s tests -p 'test_gostar_release.py'
```

[测试说明](docs/VALIDATION_061.md)区分真实 STAR 验证、回环模拟接口测试和未覆盖内容，不把创建报告等同于得到收敛结果。

## 使用边界

网格生成、有限步求解和物理收敛分别判断。扩展属性接口不是任意 Java 执行器，也没有覆盖全部 STAR API。

仓库只包含本项目代码与自行编写的说明，不含 Siemens 商业二进制或复制的商业帮助、用户 CAD/SIM、私人对话和凭据。可选帮助检索读取使用者本机已安装的文档。STAR-CCM+ 属于 Siemens；GoStar 为独立项目。公开代码不代表已授予开源许可证。
