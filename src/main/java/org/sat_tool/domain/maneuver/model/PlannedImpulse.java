package org.sat_tool.domain.maneuver.model;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.time.AbsoluteDate;

/**
 * One planned instantaneous velocity change in the propagated inertial frame.
 */
public record PlannedImpulse(
        AbsoluteDate date,
        Vector3D deltaVInFrame,
        double deltaVMagnitudeMps
) {
}
