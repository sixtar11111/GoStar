# GoStar guidance: aerodynamic reports

These are GoStar implementation notes, not copied Siemens documentation or universal defaults.

Create force/moment/force-coefficient/moment-coefficient reports with `create_aerodynamic_report`; modify existing reports with `configure_aerodynamic_report`. Read the live action help and object catalog first. Bind actual wall boundaries or their attached part surfaces, a finite-volume representation (or the native latest-mesh proxy for pre-mesh setup), a Cartesian coordinate system and a nonzero direction. Pressure plus shear is the default total force.

For coefficients provide verified reference density, speed and area; a moment coefficient also needs the reference length (STAR Reference Radius). A moment needs an explicit origin. Never infer an arbitrary reference area, CG, quarter chord, factor of two or full-model area for a half model. The default integrates the actual selection with idealization disabled. Confirm any different convention with the user. Coordinate changes require complete direction/origin information.

The direction is a vector expressed in the chosen Cartesian coordinate system. Lift and drag directions depend on actual flow axes and angle of attack; they need not be global Z and X. A signed moment also depends on its axis and origin. Creating a report or monitor does not calculate converged coefficients. Report values require a valid solution and an explicit evaluation/read request. All changes must be verified from the live SIM and saved; summaries must distinguish configuration from CFD results.
