package org.sat_tool.domain.event.antennatracking.model;

import lombok.Data;

@Data
public class AntennaTracking {
    private String time;
    private double azimuth;
    private double elevation;

    // 생성자
    public AntennaTracking( String time, double azimuth, double elevation) {
        this.time = time;
        this.azimuth = azimuth;
        this.elevation = elevation;
    }
}
