package org.sat_tool.domain.contact.api.dto;

import java.util.List;
import java.util.Map;

import org.sat_tool.domain.contact.model.ContactSchedule;
import org.sat_tool.orekit.TimeConverter;

/**
 * 교신 스케줄 응답. Orekit 타입(AbsoluteDate)은 외부에 노출하지 않고
 * UTC 문자열로 변환해 내려준다.
 */
public record ContactScheduleResponse(
        String satelliteName,
        Map<String, List<Pass>> passesByStation) {

    public record Pass(
            long orbitNumber,
            String aosUtc,
            String losUtc,
            double maxElevationDeg,
            double durationSeconds) {

        public static Pass from(ContactSchedule cs) {
            return new Pass(
                    cs.getOrbitNumber(),
                    TimeConverter.toStdMs(cs.getAos()),
                    TimeConverter.toStdMs(cs.getLos()),
                    cs.getMaxElevation(),
                    cs.getDuration());
        }
    }
}
