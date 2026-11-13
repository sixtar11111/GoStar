# Aerodynamic reports / 气动力报告

GoStar 0.6.1 adds two complete native actions: `create_aerodynamic_report` and `configure_aerodynamic_report`. The selected model can discover their live schemas through `action_help`; users normally describe the requirement in natural language.

| Report / 报告 | Required information / 必要信息 |
| --- | --- |
| Force / 力 | Actual wall selection; direction / 实际壁面、方向 |
| Moment / 力矩 | Wall selection; direction; origin in m / 壁面、方向、原点 |
| Force coefficient / 力系数 | Wall selection; direction; density, speed and area / 壁面、方向、密度、速度、面积 |
| Moment coefficient / 力矩系数 | Above coefficient references; origin; reference length / 系数参考量、原点、参考长度 |

Common optional settings: Lab/Cartesian coordinate system, finite-volume representation, report reference pressure, pressure/shear/total component, idealization, monitor and history plot. Every supplied field is validated before creation. Exact duplicate names, empty/unbound selections, invalid vectors and nonpositive reference values are rejected with a reason.

New reports default to the current SIM's default finite-volume representation, or STAR's latest-mesh proxy before volume meshing, with Lab coordinates, **pressure plus shear**, and **idealization disabled**. The proxy binding supports report setup before meshing and does not imply that a solution exists. They integrate the actual selected surfaces. Report creation neither doubles a half-model force nor guesses whether the reference area should be half or full. Changing coordinates requires a new direction and, for moments, an origin. Partial updates preserve unspecified fields. Existing dynamic selections are not silently converted to static selections.

系数采用 STAR 报告的参考密度、参考速度和参考面积；力矩系数的长度对应 STAR 的 **Reference Radius**。方向和力矩原点必须与所选坐标系一致，升阻力方向应由实际来流决定。半模的选面、面积和理想化映射要保持一致。默认关闭理想化映射，不能把这个约定理解成适用于所有项目的归一化设置。

## What is verified / 核验内容

The native implementation reads back report type, actual selection, direction, origin, references, coordinate system, representation, force component and idealization. Optional monitor/plot names and bindings are also read back. Changes use the normal preflight → recovery save → native write → readback → save → task-report transaction. A failed write retains partial-state evidence.

Creating/configuring a report returns **configuration evidence**, not aerodynamic results. It does not solve CFD or manufacture a coefficient value. Evaluate only after a valid solution exists; convergence must be assessed separately. 本版本的报告验收没有执行 CFD，不宣称验证了物理收敛或某个案例的气动精度。
