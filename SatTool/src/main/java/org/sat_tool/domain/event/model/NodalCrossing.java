package org.sat_tool.domain.event.model;

import lombok.Data;

@Data
public class NodalCrossing {

    private long orbitNumber;
    private String ascendingNodeTime;
    private String descendingNodeTime;
    private String minLatTime;
    private String maxLatTime;

    // 날짜 형식 지정 (예: "yyyy-MM-dd HH:mm:ss")
    public NodalCrossing(long orbitNumber, String ascendingNodeTime, String descendingNodeTime, String minLatTime, String maxLatTime) {
        this.orbitNumber = orbitNumber;
        this.ascendingNodeTime = ascendingNodeTime;
        this.descendingNodeTime = descendingNodeTime;
        this.minLatTime = minLatTime;
        this.maxLatTime = maxLatTime;
    }
}
