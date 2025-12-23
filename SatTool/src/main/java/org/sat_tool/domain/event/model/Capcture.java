package org.sat_tool.domain.event.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;

@Data
public class Capcture {
    AbsoluteDate capctureTime;
    double roll;
    double pitch;
    double yaw;
}
