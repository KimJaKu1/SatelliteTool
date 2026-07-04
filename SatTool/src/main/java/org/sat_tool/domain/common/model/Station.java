package org.sat_tool.domain.common.model;

import java.util.ArrayList;
import java.util.List;

import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.TopocentricFrame;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

import lombok.Data;

@Data
public class Station {
    private String stationName;
    private double latitude;
    private double longitude;
    private double height;
    private List<Integer> angles = new ArrayList<>();

    private static final OneAxisEllipsoid EARTH = new OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING,
            FramesFactory.getITRF(IERSConventions.IERS_2010, true));

    public TopocentricFrame getStationFrame() {
        GeodeticPoint groundStation = new GeodeticPoint(Math.toRadians(this.latitude), Math.toRadians(this.longitude), this.height);
        return new TopocentricFrame(EARTH, groundStation, this.stationName);
    }

    public Station(String stationName, double latitude, double longitude, double height, List<Integer> angles) {
        this.stationName = stationName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.height = height;
        this.angles = angles;
    }
}
