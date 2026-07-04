package org.sat_tool.domain.propagation.model;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.hipparchus.ode.ODEIntegrator;
import org.orekit.propagation.semianalytical.dsst.forces.DSSTForceModel;

/**
 * Required inputs for creating an Orekit DSST propagator from mean elements.
 */
public record DsstPropagationConfig(
        ODEIntegrator integrator,
        Collection<DSSTForceModel> forceModels
) {
    public DsstPropagationConfig {
        Objects.requireNonNull(integrator, "integrator");
        if (forceModels == null || forceModels.isEmpty()) {
            throw new IllegalArgumentException("forceModels must not be empty");
        }
        forceModels = List.copyOf(forceModels);
    }
}
