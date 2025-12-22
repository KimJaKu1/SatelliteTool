package org.sat_tool.domain.event.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;

@Data
public class Eclipse {
    private Long orbitNumber;
    private AbsoluteDate penumbraEntry;
    private AbsoluteDate umbraEntry;
    private AbsoluteDate umbraExit;
    private AbsoluteDate penumbraExit;

}
