package org.sat_tool.domain.contact.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

/**
 * 교신 스케줄 생성 요청.
 * 시각 문자열은 UTC, "uuuu-MM-dd HH:mm:ss.SSS" 포맷(TimeConverter.TS_STD_MS)을 따른다.
 */
public record ContactScheduleRequest(
        @NotBlank String satelliteName,
        @NotBlank String tleLine1,
        @NotBlank String tleLine2,
        @NotBlank String startUtc,
        @NotBlank String endUtc,
        @Positive double stepSeconds,
        @NotEmpty List<@Valid StationRequest> stations) {

    public record StationRequest(
            @NotBlank String stationName,
            double latitudeDeg,
            double longitudeDeg,
            double heightM,
            List<Integer> elevationMaskAngles) {
    }
}
