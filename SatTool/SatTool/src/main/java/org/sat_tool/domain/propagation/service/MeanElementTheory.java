package org.sat_tool.domain.propagation.service;

import org.orekit.files.ccsds.ndm.odm.omm.Omm;
import org.orekit.files.ccsds.ndm.odm.omm.OmmMetadata;

/**
 * Internal normalized representation of OMM MEAN_ELEMENT_THEORY values.
 */
enum MeanElementTheory {
    SGP4,
    SGP4_XP,
    DSST,
    UNSUPPORTED;

    /**
     * Resolves the propagator family declared by an OMM metadata block.
     *
     * Input data:
     * - omm must be a parsed Orekit OMM object.
     * - metadata.MEAN_ELEMENT_THEORY may use equivalent spellings such as SGP4-XP,
     *   SGP4XP, or SGP4_XP.
     *
     * @param omm parsed Orekit OMM object.
     * @return normalized theory used by PropagatorService.
     */
    static MeanElementTheory from(Omm omm) {
        if (omm.getMetadata() == null) {
            return UNSUPPORTED;
        }

        OmmMetadata metadata = omm.getMetadata();
        String theory = metadata.getMeanElementTheory();

        if (matches(theory, OmmMetadata.SGP4_XP_THEORY)
                || matches(theory, "SGP4-XP")
                || matches(theory, "SGP4XP")) {
            return SGP4_XP;
        }

        if (metadata.theoryIsSgpSdp()
                || matches(theory, OmmMetadata.SGP_SGP4_THEORY)
                || matches(theory, "SGP4")
                || matches(theory, "SDP4")) {
            return SGP4;
        }

        if (matches(theory, OmmMetadata.DSST_THEORY)) {
            return DSST;
        }

        return UNSUPPORTED;
    }

    /**
     * Returns the raw OMM MEAN_ELEMENT_THEORY value for diagnostics.
     *
     * @param omm parsed Orekit OMM object.
     * @return raw theory string, or null when metadata is missing.
     */
    static String rawValue(Omm omm) {
        return omm.getMetadata() == null ? null : omm.getMetadata().getMeanElementTheory();
    }

    private static boolean matches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return normalize(actual).equals(normalize(expected));
    }

    private static String normalize(String value) {
        return value.replace("_", "")
                .replace("-", "")
                .trim()
                .toUpperCase();
    }
}
