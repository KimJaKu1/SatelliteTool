package org.sat_tool.domain.coordinate.service;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class TopocentricService {

    private final Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);

    /** @param positionEcef ITRF(ECEF) 기준 위성 위치 벡터 — ECI 벡터를 넘기면 잘못된 값이 나온다 */
    public double getAzimuth(Vector3D positionEcef, TopocentricFrame stationFrame, AbsoluteDate t) {
        return Math.toDegrees(stationFrame.getAzimuth(positionEcef, itrf, t));
    }

    /** @param positionEcef ITRF(ECEF) 기준 위성 위치 벡터 — ECI 벡터를 넘기면 잘못된 값이 나온다 */
    public double getElevation(Vector3D positionEcef, TopocentricFrame stationFrame, AbsoluteDate t) {
        return Math.toDegrees(stationFrame.getElevation(positionEcef, itrf, t));
    }
}
