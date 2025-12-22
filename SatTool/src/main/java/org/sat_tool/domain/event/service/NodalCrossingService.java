package org.sat_tool.domain.event.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import org.hipparchus.ode.events.Action;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.events.LatitudeCrossingDetector;
import org.orekit.propagation.events.LatitudeExtremumDetector;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.event.model.NodalCrossing;
import org.sat_tool.domain.propagation.service.PropagatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class NodalCrossingService {

    @Autowired
    private TimeConverter timeConverter;
    @Autowired
    private PropagatorService propagatorService;

    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    private static final double threshold = 1.0e-6;

    public List<NodalCrossing> computeNodalCrossing(
            Satellite sat,
            AbsoluteDate startDate,
            AbsoluteDate endDate,
            double maxCheckSec,
            boolean isAscending) {

        final var propagator = propagatorService.sgp4Propagator(sat.getTle());

        final OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                FramesFactory.getITRF(IERSConventions.IERS_2010, true));

        // orbitNumber 기준을 "TLE epoch rev + floor(revSinceEpoch)"로 통일
        // -> EclipseService.Holder.orbitAt()와 동일
        final TLE tle = sat.getTle();

        // orbitNumber 별로 하나의 NodalCrossing에 누적하기 위한 맵
        final Map<Long, NodalCrossing> byOrbit = new HashMap<>();

        // Asc/Desc 중 어떤 이벤트에서 orbit을 '신규 패스 시작'으로 볼지 선택
        // (원래 코드의 의도를 유지)
        final java.util.function.Predicate<Boolean> isOrbitBoundaryEvent =
                inc -> (isAscending && inc) || (!isAscending && !inc);

        LatitudeCrossingDetector nodeDetector =
                new LatitudeCrossingDetector(maxCheckSec, threshold, earth, 0.0)
                        .withHandler((state, detector, increasing) -> {

                            // ✅ 이벤트 시각에서 바로 orbitNumber 계산
                            long orbit = orbitAt(tle, state.getDate());

                            // orbit별 레코드 확보
                            NodalCrossing cur = byOrbit.computeIfAbsent(
                                    orbit,
                                    k -> new NodalCrossing(k, null, null, null, null)
                            );

                            // Asc/Desc 시간 채우기
                            if (increasing && cur.getAscendingNodeTime() == null) {
                                cur.setAscendingNodeTime(state.getDate());
                            }
                            if (!increasing && cur.getDescendingNodeTime() == null) {
                                cur.setDescendingNodeTime(state.getDate());
                            }

                            // (원래 코드처럼 orbit boundary 이벤트에 대한 별도 처리/필터가 필요하면 여기서 사용)
                            // 지금은 "byOrbit에 누적"하는 방식이라 사실상 isOrbitBoundaryEvent는 없어도 됩니다.
                            // 하지만 유지하고 싶다면:
                            // if (!isOrbitBoundaryEvent.test(increasing)) { ... } 처럼 필터링 가능

                            return Action.CONTINUE;
                        });

        LatitudeExtremumDetector latExtDetector =
                new LatitudeExtremumDetector(maxCheckSec, threshold, earth)
                        .withHandler((state, detector, increasing) -> {

                            long orbit = orbitAt(tle, state.getDate());

                            NodalCrossing cur = byOrbit.computeIfAbsent(
                                    orbit,
                                    k -> new NodalCrossing(k, null, null, null, null)
                            );

                            double lat = earth.transform(
                                    state.getPVCoordinates().getPosition(),
                                    state.getFrame(),
                                    state.getDate()
                            ).getLatitude();

                            // 북반구 max / 남반구 min (당신 로직 유지)
                            if (lat > 0 && cur.getMaxLatTime() == null) {
                                cur.setMaxLatTime(state.getDate());
                            }
                            if (lat < 0 && cur.getMinLatTime() == null) {
                                cur.setMinLatTime(state.getDate());
                            }

                            return Action.CONTINUE;
                        });

        propagator.addEventDetector(nodeDetector);
        propagator.addEventDetector(latExtDetector);

        propagator.propagate(startDate, endDate);

        // 결과를 orbitNumber 순으로 정렬해서 List로 반환
        return byOrbit.values().stream()
                .sorted(Comparator.comparingLong(NodalCrossing::getOrbitNumber))
                .toList();
    }

    /**
     * EclipseService.Holder.orbitAt()와 동일한 정의:
     * orbit = revAtEpoch + floor( (n/(2π)) * (t - epoch) )
     */
    private static long orbitAt(TLE tle, AbsoluteDate t) {
        final long rev0 = tle.getRevolutionNumberAtEpoch();
        final double n = tle.getMeanMotion();              // rad/s
        final double dt = t.durationFrom(tle.getDate());   // sec
        final double revSince = (n / (2.0 * Math.PI)) * dt;
        return rev0 + (long) Math.floor(revSince);
    }

    public void generateNCFile(List<NodalCrossing> passes, String satName, Path path) {
        try {
            Files.createDirectories(path);
            Path file = path.resolve(satName+"_Nodal_Crossing" + ".dat");
            if (Files.exists(file)) {          // 이전 결과가 있으면
                Files.delete(file);            // 먼저 삭제
            }
            ReentrantLock lock = fileLock.computeIfAbsent(file, k -> new ReentrantLock());
            lock.lock();
            try (BufferedWriter w = Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE))
            {
                boolean newFile = Files.size(file) == 0;          // 최초 생성 여부

                if (newFile) {
                    w.write(String.format("%101s%n", TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    w.write("Satellite-" + satName);
                    w.newLine(); w.newLine();
                    w.newLine();

                    w.write( "Pass Number    Time of Ascen Node (UTCG)    Time of Descen Node (UTCG)     Time of Min Lat (UTCG)      Time of Max Lat (UTCG) ");
                    w.newLine();
                    w.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");  w.newLine();
                }

                for (NodalCrossing p : passes)
                {
                    String asc  = p.getAscendingNodeTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getAscendingNodeTime());
                    String desc = p.getDescendingNodeTime() == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getDescendingNodeTime());
                    String minL = p.getMinLatTime()   == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getMinLatTime());
                    String maxL = p.getMaxLatTime()   == null ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getMaxLatTime());

                    w.write(String.format(Locale.US,  "%11d    %-25s    %-26s    %-24s    %-24s%n",
                            p.getOrbitNumber(), asc, desc, minL, maxL));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
