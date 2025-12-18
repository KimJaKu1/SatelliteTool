package org.sat_tool.domain.event.model;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class AntennaTracking {
    @JsonProperty("time")
    private String formattedTime;
    private String time;
    private double azimuth;
    private double elevation;

    // 생성자
    public AntennaTracking(String formattedTime, String time, double azimuth, double elevation) {
        this.formattedTime = formattedTime; // AbsoluteDate를 형식화하여 문자열로 변환
        this.time = time;
        this.azimuth = azimuth;
        this.elevation = elevation;
    }
}
