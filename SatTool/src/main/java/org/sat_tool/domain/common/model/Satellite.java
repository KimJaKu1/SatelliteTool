package org.sat_tool.domain.common.model;

import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;

import lombok.Data;

@Data
public class Satellite {

    private String satelliteName;
    private Long orbitNumber;

    public void setOrbitNumFromTle(TLE tle, AbsoluteDate t) {
        final AbsoluteDate epoch = tle.getDate();
        final double n = tle.getMeanMotion();          // rad/s
        final double period = 2.0 * Math.PI / n;
        final double dt = t.durationFrom(epoch);
        final long dRev = (long) Math.floor(dt / period);
        this.orbitNumber = tle.getRevolutionNumberAtEpoch() + dRev;
    }
}
