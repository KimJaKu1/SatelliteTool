package org.sat_tool.domain.propagation.factory;

import org.orekit.files.ccsds.ndm.odm.omm.Omm;
import org.orekit.propagation.PropagationType;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.semianalytical.dsst.DSSTPropagator;
import org.orekit.propagation.semianalytical.dsst.forces.DSSTForceModel;
import org.sat_tool.domain.propagation.service.PropagatorService;

import java.util.Objects;

/**
 * Builds Orekit DSST propagators from parsed OMM data.
 */
public final class DsstPropagatorFactory {

    private DsstPropagatorFactory() {
    }

    /**
     * Creates a mean-state DSST propagator.
     *
     * Input data:
     * - omm must generate a valid initial SpacecraftState.
     * - config must provide an ODE integrator and non-empty DSST force model collection.
     *
     * @param omm parsed Orekit OMM object.
     * @param config DSST integrator and force models.
     * @return configured DSST propagator.
     */
    public static DSSTPropagator create(Omm omm, PropagatorService.DsstPropagationConfig config) {
        Objects.requireNonNull(omm, "omm");
        Objects.requireNonNull(config, "config");

        SpacecraftState initialState = omm.generateSpacecraftState();
        DSSTPropagator propagator = new DSSTPropagator(config.integrator(), PropagationType.MEAN);
        propagator.setInitialState(initialState, PropagationType.MEAN);
        for (DSSTForceModel forceModel : config.forceModels()) {
            propagator.addForceModel(Objects.requireNonNull(forceModel, "forceModel"));
        }
        return propagator;
    }
}
