package org.sat_tool.domain.common.model;

import java.util.ArrayList;
import java.util.List;

import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

import lombok.Data;
import org.springframework.context.annotation.DependsOn;

@Data
@DependsOn("orekitInitializer")
public class Satellite {

    public String satelliteName;
    public Long orbitNumber;

    public void setOrbitNumFromTle(TLE tle, AbsoluteDate t) {
        final AbsoluteDate epoch = tle.getDate();
        final double n = tle.getMeanMotion();          // rad/s :contentReference[oaicite:2]{index=2}
        final double period = 2.0 * Math.PI / n;
        final double dt = t.durationFrom(epoch);
        final long dRev = (long) Math.floor(dt / period);
        this.orbitNumber = tle.getRevolutionNumberAtEpoch() + dRev;
    }
}
