package org.sat_tool.domain.propagation.type;

import org.orekit.files.ccsds.ndm.odm.omm.Omm;
import org.orekit.files.ccsds.ndm.odm.omm.OmmMetadata;

/**
 * Normalized OMM MEAN_ELEMENT_THEORY values supported by the propagation domain.
 */
public enum MeanElementTheory {
    SGP4,
    SGP4_XP,
    DSST,
    UNSUPPORTED;

    public static MeanElementTheory from(Omm omm) {
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

    public static String rawValue(Omm omm) {
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
