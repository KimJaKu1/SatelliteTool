package org.sat_tool.domain.maneuver.model;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.time.AbsoluteDate;

/**
 * A finite burn approximation of an impulsive delta-v.
 */
public record PlannedFiniteBurn(
        AbsoluteDate startDate,
        AbsoluteDate endDate,
        double durationSeconds,
        double thrustN,
        double ispSeconds,
        Vector3D directionInLofFrame,
        double equivalentDeltaVMps,
        double massBeforeKg,
        double massAfterKg,
        double fuelUsedKg
) {
}
