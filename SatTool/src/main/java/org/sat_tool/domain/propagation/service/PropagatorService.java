package org.sat_tool.domain.propagation.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.orekit.data.DataSource;
import org.orekit.files.ccsds.ndm.ParserBuilder;
import org.orekit.files.ccsds.ndm.odm.omm.Omm;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEConstants;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.semianalytical.dsst.DSSTPropagator;
import org.sat_tool.domain.propagation.factory.DsstPropagatorFactory;
import org.sat_tool.domain.propagation.factory.Sgp4XpPropagatorFactory;
import org.sat_tool.domain.propagation.factory.StandardTlePropagatorFactory;
import org.sat_tool.domain.propagation.model.DsstPropagationConfig;
import org.sat_tool.domain.propagation.type.MeanElementTheory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class PropagatorService {

    public Propagator createPropagatorFromOmmText(String sourceName, String ommText) {
        return createPropagatorFromOmmText(sourceName, ommText, null);
    }

    public Propagator createPropagatorFromOmmText(String sourceName,
                                                  String ommText,
                                                  DsstPropagationConfig dsstConfig) {
        Objects.requireNonNull(ommText, "ommText");
        return createPropagatorFromOmmDataSource(toOmmTextDataSource(sourceName, ommText), dsstConfig);
    }

    public Propagator createPropagatorFromOmmDataSource(DataSource source, DsstPropagationConfig dsstConfig) {
        return createPropagatorFromOmm(parseOmm(source), dsstConfig);
    }

    public Propagator createPropagatorFromOmm(Omm omm, DsstPropagationConfig dsstConfig) {
        Objects.requireNonNull(omm, "omm");
        MeanElementTheory resolvedTheory = MeanElementTheory.from(omm);

        if (resolvedTheory == MeanElementTheory.SGP4) {
            TLE tle = omm.generateTLE();
            return createPropagatorFromTle(tle);
        }

        if (resolvedTheory == MeanElementTheory.SGP4_XP) {
            TLE tle = omm.generateTLE();
            return createSgp4XpPropagator(tle);
        }

        if (resolvedTheory == MeanElementTheory.DSST) {
            if (dsstConfig == null) {
                throw new UnsupportedOperationException(
                        "DSST OMM requires a DsstPropagationConfig. "
                                + "Pass a non-null DsstPropagationConfig with an integrator and force models."
                );
            }
            return createDsstPropagator(omm, dsstConfig);
        }

        String theory = MeanElementTheory.rawValue(omm);
        throw new UnsupportedOperationException("Unsupported OMM MEAN_ELEMENT_THEORY: " + theory);
    }

    public Omm parseOmm(DataSource source) {
        Objects.requireNonNull(source, "source");
        return new ParserBuilder()
                .withMu(TLEConstants.MU)
                .withDefaultMass(Propagator.DEFAULT_MASS)
                .buildOmmParser()
                .parseMessage(source);
    }

    /** TLE ephemeris type 4 = SGP4-XP (18 SDS 표기) */
    private static final int EPHEMERIS_TYPE_SGP4_XP = 4;

    public TLEPropagator createPropagatorFromTle(TLE tle) {
        Objects.requireNonNull(tle, "tle");
        if (tle.getEphemerisType() == EPHEMERIS_TYPE_SGP4_XP) {
            return createSgp4XpPropagator(tle);
        }
        return StandardTlePropagatorFactory.create(tle);
    }

    public DSSTPropagator createDsstPropagator(Omm omm, DsstPropagationConfig config) {
        return DsstPropagatorFactory.create(omm, config);
    }

    public TLEPropagator createSgp4XpPropagator(TLE tle) {
        return Sgp4XpPropagatorFactory.create(tle);
    }

    private DataSource toOmmTextDataSource(String sourceName, String ommText) {
        String resolvedName = (sourceName == null || sourceName.isBlank()) ? "omm.kvn" : sourceName;
        return new DataSource(
                resolvedName,
                () -> new ByteArrayInputStream(ommText.getBytes(StandardCharsets.UTF_8))
        );
    }
}
