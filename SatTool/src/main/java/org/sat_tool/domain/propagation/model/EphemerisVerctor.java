package org.sat_tool.domain.propagation.model;

import lombok.Data;

@Data
public class EphemerisVerctor {
    private String time;
    private double x;
    private double y;
    private double z;
    private double vx;
    private double vy;
    private double vz;
}