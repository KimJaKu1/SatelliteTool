package org.sat_tool.domain.capture.model;

import lombok.Data;

@Data
public class FovParams {
    private double focalLengthM;
    private double pixelPitchM;
    private int wpx;
    private int hpx;
}
