package org.sat_tool.domain.event.model;

import org.orekit.time.AbsoluteDate;

import lombok.Data;

@Data
public class ContactSchedule {
    private long orbitnumber;
    private String aos;
    private String los;
    private double maxElevation;
    private double duration;

    public ContactSchedule(long orbitnumber, String aos, String los, double maxElevation, double duration) {
        this.orbitnumber = orbitnumber;
        this.aos = aos;
        this.los = los;
        this.maxElevation = maxElevation;
        this.duration = duration;
    }
}
