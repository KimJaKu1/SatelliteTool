package org.sat_tool.domain.nodalcrossing.model;

import org.orekit.time.AbsoluteDate;

import lombok.Data;

@Data
public class NodalCrossing {

    private Long orbitNumber;
    private AbsoluteDate ascendingNodeTime;
    private AbsoluteDate descendingNodeTime;
    private AbsoluteDate minLatTime;
    private AbsoluteDate maxLatTime;

    public NodalCrossing(Long orbitNumber, AbsoluteDate ascendingNodeTime, AbsoluteDate descendingNodeTime, AbsoluteDate minLatTime, AbsoluteDate maxLatTime) {
        this.orbitNumber = orbitNumber;
        this.ascendingNodeTime = ascendingNodeTime;
        this.descendingNodeTime = descendingNodeTime;
        this.minLatTime = minLatTime;
        this.maxLatTime = maxLatTime;
    }
}
