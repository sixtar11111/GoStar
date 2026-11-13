<div align="center">

# GoStar

**Configure STAR-CCM+ in natural language.**

![Version](https://img.shields.io/badge/version-0.6.2-147D92)
![STAR](https://img.shields.io/badge/STAR--CCM%2B-2406-005F73)
![Java](https://img.shields.io/badge/Java-21-617078)

[简体中文](README.zh-CN.md) · [Installation](docs/INSTALL.md) · [Aerodynamic reports](docs/REPORTS.md) · [Changelog](RELEASE_NOTES.md)

</div>

GoStar connects your model to the STAR window: inspect the selected SIM, apply configuration changes, and save the results and task report.

## Features

- **Geometry and mesh** — CAD import and repair, fluid domains, Boolean operations, local refinement, prism layers and mesh generation.
- **Physics and solving** — regions, boundaries, flow conditions, SA/SST turbulence models and iteration-controlled solving.
- **Reports and visualization** — forces, moments and coefficients, monitors, history plots, field functions, sections and scenes.
- **Java API** — installed documentation search, runtime method discovery, and native property reads and writes.

Includes **50 execution entries and 21 query entries**. Changes pass parameter checks, native readback and saving. Chat lists the results; task reports contain full parameters and recovery-file paths.

## Installation

1. Download and extract `dist/GoStar-0.6.2.zip`.
2. In STAR, open **Tools → Plugins → Downloaded → Add Plugins**, select `plugin/GoStar-0.6.2.nbm`, install and restart.
3. Open a SIM and connect it through **Tools → GoStar**.
4. In **Settings**, enter your provider, API endpoint, model ID and key.
5. Select **Automatic execution** or **Review**, then describe the task.

Environment: **Linux · STAR-CCM+ 2406.0001 / 19.04.009-R8 · Java 21**.

Optional help retrieval uses Python 3.10+. Select the extracted `runtime/` under **Simulation ⋯ → Library directory**. Java API inspection and simulation editing run directly in the plugin.

## Examples

> Inspect the current mesh controls and list surface sizes and prism-layer settings.

> Create a drag coefficient report on the Wing wall: Lab direction [1,0,0], reference density 1.18 kg/m³, speed 210 m/s and area 2.5 m². Include pressure and shear, disable idealization, and create a monitor and history plot.

> Change that report's reference area to 3.25 m² and retain the other settings.

## Build and test

```bash
python3 plugin/build_plugin.py --star-home /path/to/STAR-CCM+19.04.009-R8 --jdk /path/to/jdk21
python3 packaging/build_distributions.py
python3 -m unittest discover -s tests -p 'test_gostar_release.py'
```

[Test results](docs/VALIDATION_062.md) · [Security review](docs/SECURITY_REVIEW_062.md)
