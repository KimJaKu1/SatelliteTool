package org.sat_tool.domain.maneuver.model;

import java.util.List;

/**
 * Result of validating an impulsive maneuver seed as finite constant-thrust burns.
 */
public record ConstantThrustManeuverPlan(
        ManeuverPlanType type,
        boolean feasible,
        String status,
        ManeuverPlan seedImpulsePlan,
        double thrustN,
        double totalBurnDurationSeconds,
        double finalCoastSeconds,
        OrbitShapeSnapshot afterNumericalPropagation,
        double semiMajorAxisErrorM,
        double eccentricityLimit,
        double massBeforeKg,
        double massAfterKg,
        double fuelUsedKg,
        List<PlannedFiniteBurn> burns
) {
}
