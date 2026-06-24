package org.sat_tool.domain.maneuver.model;

import java.util.List;

/**
 * Result of an impulsive maneuver planning attempt.
 */
public record ManeuverPlan(
        ManeuverPlanType type,
        boolean feasible,
        String status,
        double targetSemiMajorAxisM,
        double semiMajorAxisErrorM,
        double eccentricityLimit,
        double totalDeltaVMps,
        double ispSeconds,
        double massBeforeKg,
        double massAfterKg,
        double fuelUsedKg,
        OrbitShapeSnapshot before,
        OrbitShapeSnapshot after,
        List<PlannedImpulse> impulses
) {
}
