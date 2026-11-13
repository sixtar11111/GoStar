<div align="center">

# GoStar

**在 STAR-CCM+ 内，用自然语言完成仿真配置。**

![Version](https://img.shields.io/badge/version-0.6.2-147D92)
![STAR](https://img.shields.io/badge/STAR--CCM%2B-2406-005F73)
![Java](https://img.shields.io/badge/Java-21-617078)

[English](README.md) · [安装说明](docs/INSTALL.md) · [气动力报告](docs/REPORTS.md) · [更新记录](RELEASE_NOTES.md)

</div>

GoStar 将大模型接入 STAR 窗口，读取当前 SIM，执行配置修改，并保存结果和任务报告。

## 功能

- **几何与网格**：CAD 导入与修复、流体域、布尔操作、局部加密、棱柱层和网格生成。
- **物理与求解**：区域与边界配置、来流工况、SA／SST 湍流模型和指定步数求解。
- **报告与后处理**：力、力矩及系数报告，监视器、历史图、场函数、截面和场景。
- **Java API**：检索本机文档、查询运行时方法、读取和修改对象属性。

内置 **50 个执行入口、21 个查询入口**。每次修改均经过参数检查、实际读回和保存，对话中列出修改结果，详情记录完整参数和恢复文件。

## 安装

1. 下载并解压 `dist/GoStar-0.6.2.zip`。
2. 在 STAR 中打开 **工具 → 插件 → 已下载 → 添加插件**，选择 `plugin/GoStar-0.6.2.nbm`，安装后重启。
3. 打开 SIM，从 **工具 → GoStar** 连接目标仿真。
4. 在**设置**中填写服务商、API 地址、模型 ID 和密钥。
5. 选择**自动执行**或**审阅模式**，输入操作要求。

环境：**Linux · STAR-CCM+ 2406.0001／19.04.009-R8 · Java 21**。

可选资料检索使用 Python 3.10+。在**仿真 ⋯ → 资料目录**中选择解压后的 `runtime/`；Java API 检索与仿真修改直接在插件内运行。

## 示例

> 检查当前网格控制，列出表面尺寸和棱柱层设置。

> 在 Wing 壁面创建阻力系数报告：Lab 方向 [1,0,0]，参考密度 1.18 kg/m³、速度 210 m/s、面积 2.5 m²，包含压力和剪切，关闭理想化映射，同时创建监视器和历史图。

> 将这个报告的参考面积改为 3.25 m²，其余设置保留。

## 构建与测试

```bash
python3 plugin/build_plugin.py --star-home /path/to/STAR-CCM+19.04.009-R8 --jdk /path/to/jdk21
python3 packaging/build_distributions.py
python3 -m unittest discover -s tests -p 'test_gostar_release.py'
```

[测试结果](docs/VALIDATION_062.md) · [安全检查](docs/SECURITY_REVIEW_062.md)
