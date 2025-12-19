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

    public double getAzimuth(Vector3D vector, TopocentricFrame stationFrame, AbsoluteDate t) {
        return Math.toDegrees(stationFrame.getAzimuth(vector, itrf, t));
    }

    public double getElevation(Vector3D vector, TopocentricFrame stationFrame, AbsoluteDate t) {
        return Math.toDegrees(stationFrame.getElevation(vector, itrf, t));
    }
}
