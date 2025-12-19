package org.sat_tool.domain.coordinate.service;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.frames.Transform;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class CoordinateService {

    private final Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);

    private final Frame gcrf = FramesFactory.getGCRF();

    public  final OneAxisEllipsoid earth = new OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING,
            FramesFactory.getITRF(IERSConventions.IERS_2010, true)
    );

    public PVCoordinates toECEF(AbsoluteDate date, PVCoordinates pvECI)
    {
        Transform transform = gcrf.getTransformTo(itrf, date);
        return transform.transformPVCoordinates(pvECI);
    }



}
