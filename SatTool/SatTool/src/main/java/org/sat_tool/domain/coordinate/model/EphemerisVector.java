package org.sat_tool.domain.coordinate.model;

import lombok.Data;
import org.hipparchus.geometry.euclidean.threed.Vector3D;

import java.time.LocalDateTime;
import java.util.Vector;

@Data
public class EphemerisVector {
   LocalDateTime time;
   Vector3D pos;
   Vector3D vel;
}
