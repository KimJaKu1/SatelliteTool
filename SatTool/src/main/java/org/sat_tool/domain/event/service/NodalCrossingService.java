package org.sat_tool.domain.event.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import org.hipparchus.ode.events.Action;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.events.LatitudeCrossingDetector;
import org.orekit.propagation.events.LatitudeExtremumDetector;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.event.model.NodalCrossing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("orekitInitializer")
public class NodalCrossingService {

    @Autowired
    private TimeConverter timeConverter;

    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    private static final double threshold = 1.0e-6;

    public List<NodalCrossing> computeNodalCrossing(
            AbsoluteDate startDate, AbsoluteDate endDate, double intervalSeconds,
            Propagator propagator, long initialOrbitNumber, boolean isAscending) {

        List<NodalCrossing> totalNodalCrossing = new ArrayList<>();

        OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                FramesFactory.getITRF(IERSConventions.IERS_2010, true));

        // Ascending(inc==true) & mode==1
        // Descending(inc==false) & mode==0
        Predicate<Boolean> isOrbitIncrementEvent = inc -> (isAscending == true && inc)
                || (isAscending == false && !inc);

        // TLE propagator (SGP4) 설정
        // TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);


        // 초기 OrbitNumber 계산
        long[] orbitNumber = { initialOrbitNumber };

        List<NodalCrossing> nodalCrossings = new ArrayList<>();

        LatitudeCrossingDetector nodeDetector = new LatitudeCrossingDetector(intervalSeconds, threshold, earth, 0.0) // maxCheck (s), inertial frame
                .withHandler((state, detector, increasing) -> {
                    if (isOrbitIncrementEvent.test(increasing)) {
                        orbitNumber[0]++;

                        nodalCrossings.add(new NodalCrossing(
                                orbitNumber[0],
                                increasing ? timeConverter.toUtcAbbrMSec(state.getDate()) : null, // Asc or Desc 시각
                                increasing ? null : timeConverter.toUtcAbbrMSec(state.getDate()),
                                null, null));

                        return Action.CONTINUE; // ★ 여기서 끝! (채우기 완료)
                    }

                    /* ② orbit++ 가 아닌 경우 → ‘현재 패스’에 Asc/Desc 시각만 채운다 ---- */
                    if (nodalCrossings.isEmpty()) { // 안전 방어
                        nodalCrossings.add(new NodalCrossing(
                                orbitNumber[0], null, null, null, null));
                    }

                    NodalCrossing cur = nodalCrossings.get(nodalCrossings.size() - 1);
                    if (increasing && cur.getAscendingNodeTime() == null)
                        cur.setAscendingNodeTime(timeConverter.toUtcAbbrMSec(state.getDate()));
                    if (!increasing && cur.getDescendingNodeTime() == null)
                        cur.setDescendingNodeTime(timeConverter.toUtcAbbrMSec(state.getDate()));
                    return Action.CONTINUE;
                });

        LatitudeExtremumDetector latExtDetector = new LatitudeExtremumDetector(intervalSeconds, threshold, earth)
                .withHandler((state, detector, increasing) -> {

                    if (nodalCrossings.isEmpty()) { // 패스 보장
                        nodalCrossings.add(new NodalCrossing(
                                orbitNumber[0], null, null, null, null));
                    }

                    double lat = earth.transform(
                            state.getPVCoordinates().getPosition(),
                            state.getFrame(), state.getDate()).getLatitude();

                    NodalCrossing cur = nodalCrossings.get(nodalCrossings.size() - 1);
                    if (lat > 0 && cur.getMaxLatTime() == null)
                        cur.setMaxLatTime(timeConverter.toUtcAbbrMSec(state.getDate()));
                    if (lat < 0 && cur.getMinLatTime() == null)
                        cur.setMinLatTime(timeConverter.toUtcAbbrMSec(state.getDate()));

                    return Action.CONTINUE;
                });

        propagator.addEventDetector(nodeDetector);
        propagator.addEventDetector(latExtDetector);

        propagator.propagate(startDate, endDate);

        return totalNodalCrossing;
    }

    private void writeNC(List<NodalCrossing> passes, Integer satNum, Path path) {
        try {
            Files.createDirectories(path);
            Path file = path.resolve(satNum+"_Nodal_Crossing" + ".dat");
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
                    w.write("Satellite-" + satNum);
                    w.newLine(); w.newLine();
                    w.newLine();

                    w.write( "Pass Number    Time of Ascen Node (UTCG)    Time of Descen Node (UTCG)     Time of Min Lat (UTCG)      Time of Max Lat (UTCG) ");
                    w.newLine();
                    w.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");  w.newLine();
                }

                for (NodalCrossing p : passes)
                {
                    String asc  = p.getAscendingNodeTime() == null ? "             Not in Pass"
                            : p.getAscendingNodeTime();
                    String desc = p.getDescendingNodeTime() == null ? "             Not in Pass"
                            : p.getDescendingNodeTime();
                    String minL = p.getMinLatTime()   == null ? "             Not in Pass"
                            : p.getMinLatTime();
                    String maxL = p.getMaxLatTime()   == null ? "             Not in Pass"
                            : p.getMaxLatTime();

                    w.write(String.format(Locale.US,  "%11d    %-25s    %-26s    %-24s    %-24s%n",
                            p.getOrbitNumber(), asc, desc, minL, maxL));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeNodalCrossingFile(List<NodalCrossing> nc, int satelliteNumber, Path of) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'writeNodalCrossingFile'");
    }

}
