package org.sat_tool.domain.coordinate.service;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class SatPosService {

    private final Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
    private final Frame gcrf = FramesFactory.getGCRF();

        public PVCoordinates getSatECI(TLEPropagator propagator, AbsoluteDate t)
    {
        return propagator.getPVCoordinates(t, gcrf);
    }


}
