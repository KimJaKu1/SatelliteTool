package org.sat_tool.domain.event.eclipse.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.event.eclipse._enum.EdgeType;

@Data
public class EdgeEvent {
    final AbsoluteDate t;
    final EdgeType type;
    public EdgeEvent(AbsoluteDate t, EdgeType type) { this.t = t; this.type = type; }
}
