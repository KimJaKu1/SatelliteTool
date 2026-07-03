package org.sat_tool.domain.event.nodalcrossing.model;

import java.time.LocalDateTime;

import org.orekit.time.AbsoluteDate;

import lombok.Data;

@Data
public class NodalCrossing {

    private Long orbitNumber;
    private AbsoluteDate ascendingNodeTime;
    private AbsoluteDate descendingNodeTime;
    private AbsoluteDate minLatTime;
    private AbsoluteDate maxLatTime;

    // 날짜 형식 지정 (예: "yyyy-MM-dd HH:mm:ss")
    public NodalCrossing(Long orbitNumber, AbsoluteDate ascendingNodeTime, AbsoluteDate descendingNodeTime, AbsoluteDate minLatTime, AbsoluteDate maxLatTime) {
        this.orbitNumber = orbitNumber;
        this.ascendingNodeTime = ascendingNodeTime;
        this.descendingNodeTime = descendingNodeTime;
        this.minLatTime = minLatTime;
        this.maxLatTime = maxLatTime;
    }
}
