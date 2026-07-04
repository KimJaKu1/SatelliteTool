package org.sat_tool.domain.coordinate.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.orekit.bodies.GeodeticPoint;

/**
 * Geodetic latitude, longitude, and altitude in the project-standard WGS84 datum.
 *
 * <p>Latitude and longitude are stored in degrees. Altitude is stored in meters.
 * The datum is fixed to WGS84 so LLA values cannot silently carry another datum.</p>
 */
public final class LLA {

    public static final GeodeticDatum PROJECT_DATUM = GeodeticDatum.WGS84;

    private final double latitude;
    private final double longitude;
    private final double altitude;
    private final GeodeticDatum datum;

    public LLA(double latitude, double longitude, double altitude) {
        this(latitude, longitude, altitude, PROJECT_DATUM);
    }

    @JsonCreator
    public LLA(
            @JsonProperty("latitude") double latitude,
            @JsonProperty("longitude") double longitude,
            @JsonProperty("altitude") double altitude,
            @JsonProperty("datum") GeodeticDatum datum) {
        if (datum != null && datum != PROJECT_DATUM) {
            throw new IllegalArgumentException("LLA datum must be WGS84.");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.datum = PROJECT_DATUM;
    }

    public static LLA ofWgs84Degrees(double latitudeDeg, double longitudeDeg, double altitudeM) {
        return new LLA(latitudeDeg, longitudeDeg, altitudeM);
    }

    public static LLA fromWgs84GeodeticPoint(GeodeticPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Geodetic point must not be null.");
        }
        return ofWgs84Degrees(
                Math.toDegrees(point.getLatitude()),
                Math.toDegrees(point.getLongitude()),
                point.getAltitude());
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getAltitude() {
        return altitude;
    }

    public GeodeticDatum getDatum() {
        return datum;
    }
}
