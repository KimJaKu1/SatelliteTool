package org.sat_tool.domain.contact.api;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.api.job.JobManager;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.common.model.Station;
import org.sat_tool.domain.contact.api.dto.ContactScheduleRequest;
import org.sat_tool.domain.contact.api.dto.ContactScheduleResponse;
import org.sat_tool.domain.contact.model.ContactSchedule;
import org.sat_tool.domain.contact.service.ContactScheduleService;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.propagation.service.EphemerisService;
import org.sat_tool.domain.propagation.service.PropagatorService;
import org.sat_tool.orekit.TimeConverter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * api 하위 패키지 예시. Controller는 DTO <-> 도메인 모델 변환과 서비스 호출만 담당하고
 * 계산 로직은 두지 않는다.
 *
 * 전파 구간이 길면 계산이 오래 걸리므로, 입력 검증(TLE·시각 파싱)만 동기로 수행해
 * 잘못된 입력은 즉시 400으로 돌려주고, 본 계산은 작업 ID를 발급해 비동기로 처리한다.
 * 결과는 GET /api/jobs/{jobId} 로 조회한다.
 */
@RestController
@RequestMapping("/api/contact-schedules")
@RequiredArgsConstructor
public class ContactScheduleController {

    private final PropagatorService propagatorService;
    private final EphemerisService ephemerisService;
    private final ContactScheduleService contactScheduleService;
    private final JobManager jobManager;

    public record JobAccepted(String jobId, String statusUrl) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobAccepted generate(@Valid @RequestBody ContactScheduleRequest request) {
        // 파싱 실패는 GlobalExceptionHandler가 400으로 변환 — 여기서 동기로 걸러낸다
        AbsoluteDate start = TimeConverter.localDateTimeUtcToAbsoluteDate(
                TimeConverter.stringToLocalDateTime(request.startUtc()));
        AbsoluteDate end = TimeConverter.localDateTimeUtcToAbsoluteDate(
                TimeConverter.stringToLocalDateTime(request.endUtc()));
        if (start.compareTo(end) >= 0) {
            throw new IllegalArgumentException("startUtc must be before endUtc");
        }

        TLE tle = new TLE(request.tleLine1(), request.tleLine2());

        String jobId = jobManager.submit(() -> compute(request, tle, start, end));
        return new JobAccepted(jobId, "/api/jobs/" + jobId);
    }

    private ContactScheduleResponse compute(ContactScheduleRequest request, TLE tle,
                                            AbsoluteDate start, AbsoluteDate end) {
        Propagator propagator = propagatorService.createPropagatorFromTle(tle);
        List<EphemerisVector> ephemeris =
                ephemerisService.computeEphemerisECI(propagator, start, end, request.stepSeconds());

        Satellite satellite = new Satellite();
        satellite.setSatelliteName(request.satelliteName());
        satellite.setOrbitNumFromTle(tle, start);

        List<Station> stations = request.stations().stream()
                .map(s -> new Station(s.stationName(), s.latitudeDeg(), s.longitudeDeg(),
                        s.heightM(), s.elevationMaskAngles()))
                .toList();

        double orbitPeriodSeconds = 2.0 * Math.PI / tle.getMeanMotion();
        Map<String, List<ContactSchedule>> result =
                contactScheduleService.generateContactSchedule(satellite, stations, ephemeris, orbitPeriodSeconds).join();

        Map<String, List<ContactScheduleResponse.Pass>> passes = result.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream().map(ContactScheduleResponse.Pass::from).toList()));

        return new ContactScheduleResponse(request.satelliteName(), passes);
    }
}
