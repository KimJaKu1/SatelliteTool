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
    public Long OrbitNumber;
}
