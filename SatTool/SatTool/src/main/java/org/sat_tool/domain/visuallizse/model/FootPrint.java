package org.sat_tool.domain.visuallizse.model;

import lombok.Data;
import org.sat_tool.domain.coordinate.model.LLA;

import java.util.List;

@Data
public class FootPrint {
    List<LLA> footPrint;
}
