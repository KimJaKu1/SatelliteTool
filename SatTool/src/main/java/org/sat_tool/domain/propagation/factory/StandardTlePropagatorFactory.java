package org.sat_tool.domain.propagation.factory;

import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;

import java.util.Objects;

/**
 * Builds Orekit's standard TLE propagator.
 */
public final class StandardTlePropagatorFactory {

    private StandardTlePropagatorFactory() {
    }

    /**
     * Creates a standard Orekit TLE propagator.
     *
     * Input data:
     * - tle must be a parsed Orekit TLE object.
     * - Orekit selects the appropriate standard TLE extrapolator internally.
     *
     * @param tle parsed Orekit TLE object.
     * @return Orekit TLEPropagator selected from the input TLE.
     */
    public static TLEPropagator create(TLE tle) {
        Objects.requireNonNull(tle, "tle");
        return TLEPropagator.selectExtrapolator(tle);
    }
}
