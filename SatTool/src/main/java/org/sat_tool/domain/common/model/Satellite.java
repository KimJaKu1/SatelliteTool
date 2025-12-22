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

@Data
public class Satellite {

    public String satelliteName;
    public TLE tle;
    public Long OrbitNumber;

    public final OneAxisEllipsoid earth = new OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING,
            FramesFactory.getITRF(IERSConventions.IERS_2010, true));

    public long orbitAt(TLE tle, AbsoluteDate t) {
        final long rev0 = tle.getRevolutionNumberAtEpoch();   // epoch revol number :contentReference[oaicite:9]{index=9}
        final double n = tle.getMeanMotion();                 // rad/s
        final double dt = t.durationFrom(tle.getDate());      // sec
        final double revSince = (n / (2.0 * Math.PI)) * dt;
        return rev0 + (long) Math.floor(revSince);
    }

    
}
