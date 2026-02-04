package org.sat_tool.domain.event.nodalcrossing.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.event.nodalcrossing._enum.NCEventType;

@Data
public  class NCEvent {
    public AbsoluteDate time;
    public NCEventType type;
    public NCEvent(AbsoluteDate time, NCEventType type) {
        this.time = time;
        this.type = type;
    }
}
