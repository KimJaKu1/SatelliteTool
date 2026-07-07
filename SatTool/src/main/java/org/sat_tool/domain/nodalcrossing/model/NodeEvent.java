package org.sat_tool.domain.nodalcrossing.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.nodalcrossing.type.NodeType;

@Data
public class NodeEvent {
    final AbsoluteDate t;
    final NodeType type;
    public NodeEvent(AbsoluteDate t, NodeType type) { this.t = t; this.type = type; }
}
