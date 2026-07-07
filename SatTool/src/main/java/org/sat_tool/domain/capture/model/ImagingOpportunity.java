package org.sat_tool.domain.capture.model;

import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.coordinate.model.LLA;

import java.util.List;

public record ImagingOpportunity(
        AbsoluteDate startUtc,
        AbsoluteDate endUtc,
        AbsoluteDate captureUtc,
        double usedRollDegAtCapture,
        double boresightErrorDegAtCapture,
        List<LLA> footprintAtCapture
) {
}
