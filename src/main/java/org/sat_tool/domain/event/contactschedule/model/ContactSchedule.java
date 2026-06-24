package org.sat_tool.domain.event.contactschedule.model;

import org.orekit.time.AbsoluteDate;

import lombok.Data;

@Data
public class ContactSchedule {
    private long orbitnumber;
    private AbsoluteDate aos;
    private AbsoluteDate los;
    private double maxElevation;
    private double duration;

    public ContactSchedule(long orbitnumber, AbsoluteDate aos, AbsoluteDate los, double maxElevation, double duration) {
        this.orbitnumber = orbitnumber;
        this.aos = aos;
        this.los = los;
        this.maxElevation = maxElevation;
        this.duration = duration;
    }
}
