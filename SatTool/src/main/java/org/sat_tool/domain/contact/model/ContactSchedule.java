package org.sat_tool.domain.contact.model;

import org.orekit.time.AbsoluteDate;

import lombok.Data;

@Data
public class ContactSchedule {
    private long orbitNumber;
    private AbsoluteDate aos;
    private AbsoluteDate los;
    private double maxElevation;
    private double duration;

    public ContactSchedule(long orbitNumber, AbsoluteDate aos, AbsoluteDate los, double maxElevation, double duration) {
        this.orbitNumber = orbitNumber;
        this.aos = aos;
        this.los = los;
        this.maxElevation = maxElevation;
        this.duration = duration;
    }
}
