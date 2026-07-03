package org.sat_tool.domain.maneuver.model;

import org.orekit.time.AbsoluteDate;

/**
 * Shape-focused orbit values used by altitude maintenance and orbit-raising logic.
 */
public record OrbitShapeSnapshot(
        AbsoluteDate date,
        double semiMajorAxisM,
        double eccentricity,
        double perigeeAltitudeM,
        double apogeeAltitudeM,
        double meanAltitudeM
) {
}
