package org.sat_tool.domain.event.service;

import org.hipparchus.ode.events.Action;
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.EclipseDetector;
import org.orekit.propagation.events.EventDetector;
import org.orekit.propagation.events.handlers.EventHandler;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.event.model.Eclipse;
import org.sat_tool.domain.event.model.NodalCrossing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;


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

@Service
@DependsOn("orekitInitializer")
public class EclipseService {

    @Autowired private TimeConverter timeConverter;

    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    public List<Eclipse> computeEclipses(Satellite satellite, AbsoluteDate start, AbsoluteDate end, double maxCheckSec) {

        // 1) Propagator
        final TLEPropagator propagator = TLEPropagator.selectExtrapolator(satellite.getTle());

        // 2) Sun + Earth model (Earth는 OneAxisEllipsoid 사용) :contentReference[oaicite:4]{index=4}
        final CelestialBody sun = CelestialBodyFactory.getSun();
        final OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                FramesFactory.getITRF(IERSConventions.IERS_2010, true)
        );

        // 3) 결과 누적기
        final List<Eclipse> out = new ArrayList<>();
        final Holder holder = new Holder();

        // 4) Penumbra detector
        EclipseDetector pen = new EclipseDetector(sun, Constants.SUN_RADIUS, earth)
                .withPenumbra()
                .withMaxCheck(maxCheckSec)
                .withHandler(new EventHandler() {
                    @Override
                    public Action eventOccurred(SpacecraftState s, EventDetector detector, boolean increasing) {

                        // detector를 EclipseDetector로 쓰고 싶으면 캐스팅
                        // EclipseDetector ed = (EclipseDetector) detector;

                        if (!increasing) { // entry
                            holder.ensureCurrent(satellite.getTle(), s.getDate());
                            holder.current.setPenumbraEntry(s.getDate());
                        } else {           // exit
                            if (holder.current == null) {
                                holder.ensureCurrent(satellite.getTle(), start);
                            }
                            holder.current.setPenumbraExit(s.getDate());
                            out.add(holder.current);
                            holder.current = null;
                        }
                        return Action.CONTINUE;
                    }
                });

        // 5) Umbra detector

        EclipseDetector umb = new EclipseDetector(sun, Constants.SUN_RADIUS, earth)
                .withUmbra()
                .withMaxCheck(maxCheckSec)
                .withHandler(new EventHandler() {
                    @Override
                    public Action eventOccurred(SpacecraftState s, EventDetector detector, boolean increasing) {
                        holder.ensureCurrent(satellite.getTle(), s.getDate());
                        if (!increasing) {
                            holder.current.setUmbraEntry(s.getDate());
                        } else {
                            holder.current.setUmbraExit(s.getDate());
                        }
                        return Action.CONTINUE;
                    }
                });

        // 6) attach + propagate
        propagator.addEventDetector(pen);
        propagator.addEventDetector(umb);
        propagator.propagate(start, end);

        return out;
    }

    public void generateNCFile(List<Eclipse> passes, String satName, Path path) {
        try {
            Files.createDirectories(path);
            Path file = path.resolve(satName+"_Eclips" + ".txt");
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

                    w.write( "Pass Number    Penumbra Entry (UTCG)        Umbra Entry (UTCG)            Umbra Exit (UTCG)           Penumbra Exit (UTCG)");
                    w.newLine();
                    w.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");  w.newLine();
                }

                for (Eclipse p : passes)
                {
                    String penEntryStr = (p.getPenumbraEntry() == null)
                            ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getPenumbraEntry());

                    String umbEntryStr = (p.getUmbraEntry() == null)
                            ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getUmbraEntry());

                    String umbExitStr  = (p.getUmbraExit() == null)
                            ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getUmbraExit());

                    String penExitStr  = (p.getPenumbraExit() == null)   // ✅ 여기 체크 대상 수정
                            ? "             Not in Pass"
                            : timeConverter.toUtcAbbrMSec(p.getPenumbraExit());

                    w.write(String.format(Locale.US, "%11d    %-25s    %-26s    %-24s    %-24s%n",
                            p.getOrbitNumber(), penEntryStr, umbEntryStr, umbExitStr, penExitStr));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- helper ----
    private static class Holder {
        Eclipse current;

        void ensureCurrent(TLE tle, AbsoluteDate t) {
            if (current == null) {
                current = new Eclipse();
                current.setOrbitNumber(orbitAt(tle, t));
            }
        }

        long orbitAt(TLE tle, AbsoluteDate t) {
            final long rev0 = tle.getRevolutionNumberAtEpoch();   // epoch revol number :contentReference[oaicite:9]{index=9}
            final double n = tle.getMeanMotion();                 // rad/s
            final double dt = t.durationFrom(tle.getDate());      // sec
            final double revSince = (n / (2.0 * Math.PI)) * dt;
            return rev0 + (long) Math.floor(revSince);
        }
    }
}
