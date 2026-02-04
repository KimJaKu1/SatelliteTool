package org.sat_tool.domain.event.nodalcrossing.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.event.nodalcrossing._enum.NodeType;

@Data
public class NodeEvent {
    final AbsoluteDate t;
    final NodeType type;
    public NodeEvent(AbsoluteDate t, NodeType type) { this.t = t; this.type = type; }
}
