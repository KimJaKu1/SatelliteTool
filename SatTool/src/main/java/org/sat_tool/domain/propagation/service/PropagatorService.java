package org.sat_tool.domain.propagation.service;

import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class PropagatorService {

    // TLE & Propagator
    public TLEPropagator sgp4Propagator(String line1, String line2) {
        TLE tle = new TLE(line1, line2);
        return TLEPropagator.selectExtrapolator(tle);
    }

}
