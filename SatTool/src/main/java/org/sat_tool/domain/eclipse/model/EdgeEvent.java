package org.sat_tool.domain.eclipse.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.eclipse.type.EdgeType;

@Data
public class EdgeEvent {
    final AbsoluteDate t;
    final EdgeType type;
    public EdgeEvent(AbsoluteDate t, EdgeType type) { this.t = t; this.type = type; }
}
