package org.sat_tool.domain.event.eclipse.service;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.Transform;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.events.EclipseDetector;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.*;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.common.model.Satellite;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.event.eclipse._enum.EdgeType;
import org.sat_tool.domain.event.eclipse._enum.FieldState;
import org.sat_tool.domain.event.eclipse.model.*;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@DependsOn("orekitInitializer")
public class EclipseSevice {

    @Autowired
    private TimeConverter timeConverter;

    private final ConcurrentMap<Path, ReentrantLock> fileLock = new ConcurrentHashMap<>();

    // =========================================================
    // Public API #1: report rows (recommended, keeps "No Umbra" distinct)
    // =========================================================
    public List<EclipseReportRow> computeEclipseReportRowsFromEcef_NoPropagator(
            Satellite sat,
            List<EphemerisVector> vectorsEcef,
            AbsoluteDate start,
            AbsoluteDate end,
            double subStepSec,
            double rootTolSec,
            int rootMaxIter
    ) {
        if (vectorsEcef == null || vectorsEcef.size() < 3) return List.of();
        if (subStepSec <= 0) subStepSec = 0.25;
        if (rootTolSec <= 0) rootTolSec = 1e-3;
        if (rootMaxIter <= 0) rootMaxIter = 60;

        // 1) sort
        List<EphemerisVector> v = new ArrayList<>(vectorsEcef);
        v.sort(Comparator.comparing(EphemerisVector::getTime));

        // 2) frames/models
        final Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        final Frame inertial = FramesFactory.getEME2000();
        final OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrf
        );
        final CelestialBody sun = CelestialBodyFactory.getSun();

        final EclipseDetector penDet = new EclipseDetector(sun, Constants.SUN_RADIUS, earth).withPenumbra();
        final EclipseDetector umbDet = new EclipseDetector(sun, Constants.SUN_RADIUS, earth).withUmbra();

        // 3) samples -> inertial arrays
        final int n = v.size();
        final AbsoluteDate[] tAbs = new AbsoluteDate[n];
        final Vector3D[] rInert = new Vector3D[n];
        final Vector3D[] vInert = new Vector3D[n];

        for (int i = 0; i < n; i++) {
            AbsoluteDate t = timeConverter.localDateTimeUtcToAbsoluteDate(v.get(i).getTime());
            tAbs[i] = t;

            PVCoordinates pvEcef = new PVCoordinates(v.get(i).getPos(), v.get(i).getVel());
            Transform tr = itrf.getTransformTo(inertial, t);
            PVCoordinates pvI = tr.transformPVCoordinates(pvEcef);

            rInert[i] = pvI.getPosition();
            vInert[i] = pvI.getVelocity();
        }

        // 4) window clamp for output
        AbsoluteDate minT = tAbs[0];
        AbsoluteDate maxT = tAbs[n - 1];
        AbsoluteDate winStart = (start.compareTo(minT) < 0) ? minT : start;
        AbsoluteDate winEnd   = (end.compareTo(maxT) > 0) ? maxT : end;
        if (winStart.compareTo(winEnd) >= 0) return List.of();

        // =========================================================
        // IMPORTANT: detect on FULL range (minT~maxT)
        // =========================================================
        List<EdgeEvent> penEdgesAll = collectEdgesNoProp(penDet, tAbs, rInert, vInert, minT, maxT,
                subStepSec, rootTolSec, rootMaxIter, inertial);
        List<EdgeEvent> umbEdgesAll = collectEdgesNoProp(umbDet, tAbs, rInert, vInert, minT, maxT,
                subStepSec, rootTolSec, rootMaxIter, inertial);

        PVCoordinates pvAtMin = pvAtTimeNoProp(minT, tAbs, rInert, vInert);
        List<IntervalEx> penIntervalsAll = buildIntervalsFromEdges(penDet, minT, maxT, pvAtMin, inertial, penEdgesAll);

        pvAtMin = pvAtTimeNoProp(minT, tAbs, rInert, vInert);
        List<IntervalEx> umbIntervalsAll = buildIntervalsFromEdges(umbDet, minT, maxT, pvAtMin, inertial, umbEdgesAll);

        long baseOrbit = (sat.getOrbitNumber() != null) ? sat.getOrbitNumber() : 0L;

        List<EclipseReportRow> out = new ArrayList<>();
        int j = 0;

        for (IntervalEx pen : penIntervalsAll) {

            // only pen intervals overlapping window
            if (!(pen.getEnd().compareTo(winStart) > 0 && pen.getStart().compareTo(winEnd) < 0)) continue;

            while (j < umbIntervalsAll.size() && umbIntervalsAll.get(j).getEnd().compareTo(pen.getStart()) <= 0) j++;

            boolean umbraExists = false;

            AbsoluteDate umbEntryFull = null;
            AbsoluteDate umbExitFull  = null;

            boolean umbEntryOpenStart = false; // umbra ENTRY 미관측?
            boolean umbExitOpenEnd    = false; // umbra EXIT 미관측?

            int k = j;
            while (k < umbIntervalsAll.size() && umbIntervalsAll.get(k).getStart().compareTo(pen.getEnd()) < 0) {
                IntervalEx u = umbIntervalsAll.get(k);

                AbsoluteDate s = (u.getStart().compareTo(pen.getStart()) < 0) ? pen.getStart() : u.getStart();
                AbsoluteDate e = (u.getEnd().compareTo(pen.getEnd()) > 0) ? pen.getEnd() : u.getEnd();

                if (s.compareTo(e) < 0) {
                    umbraExists = true;

                    // entry
                    if (umbEntryFull == null || s.compareTo(umbEntryFull) < 0) {
                        umbEntryFull = s;
                        // entry가 u.start에서 왔고 u.openStart면 "진짜 ENTRY 미관측"
                        umbEntryOpenStart = u.isOpenStart() && s.equals(u.getStart());
                    } else if (s.equals(umbEntryFull)) {
                        umbEntryOpenStart |= (u.isOpenStart() && s.equals(u.getStart()));
                    }

                    // exit
                    if (umbExitFull == null || e.compareTo(umbExitFull) > 0) {
                        umbExitFull = e;
                        // exit가 u.end에서 왔고 u.openEnd면 "진짜 EXIT 미관측"
                        umbExitOpenEnd = u.isOpenEnd() && e.equals(u.getEnd());
                    } else if (e.equals(umbExitFull)) {
                        umbExitOpenEnd |= (u.isOpenEnd() && e.equals(u.getEnd()));
                    }
                }
                k++;
            }

            // -------------------------
            // Not in Pass 반영 규칙
            // - openStart/openEnd면 해당 경계는 "관측된 이벤트가 아님" => Not in Pass
            // - 그 외에는 window 밖이면 Not in Pass
            // -------------------------
            FieldValue penEntryOut = pen.isOpenStart() ? FieldValue.notInPass() : fieldByWindow(pen.getStart(), winStart, winEnd);
            FieldValue penExitOut  = pen.isOpenEnd()   ? FieldValue.notInPass() : fieldByWindow(pen.getEnd(),   winStart, winEnd);

            FieldValue umbEntryOut;
            FieldValue umbExitOut;

            if (!umbraExists) {
                umbEntryOut = FieldValue.noUmbra();
                umbExitOut  = FieldValue.noUmbra();
            } else {
                umbEntryOut = umbEntryOpenStart ? FieldValue.notInPass() : fieldByWindow(umbEntryFull, winStart, winEnd);
                umbExitOut  = umbExitOpenEnd    ? FieldValue.notInPass() : fieldByWindow(umbExitFull,  winStart, winEnd);
            }

            // orbit number reference inside window
            AbsoluteDate orbitRef = (pen.getStart().compareTo(winStart) < 0) ? winStart : pen.getStart();
            long orbitNum = orbitAtByPeriodFallback(baseOrbit, winStart, orbitRef, 5400.0);

            out.add(new EclipseReportRow(orbitNum, penEntryOut, umbEntryOut, umbExitOut, penExitOut));
        }

        out.sort(Comparator.comparing(row -> {
            AbsoluteDate t = row.getPenEntry().getTime();
            return (t != null) ? t : winStart;
        }));

        return out;
    }

    // =========================================================
    // File output (report rows)
    // =========================================================
    public void generateEclipseFileReport(List<EclipseReportRow> rows, String satName, Path path) {
        try {
            Files.createDirectories(path);
            Path file = path.resolve(satName + "_Eclips" + ".txt");
            if (Files.exists(file)) Files.delete(file);

            ReentrantLock lock = fileLock.computeIfAbsent(file, k -> new ReentrantLock());
            lock.lock();

            try (BufferedWriter w = Files.newBufferedWriter(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE
            )) {
                w.write(String.format("%101s%n",
                        TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                w.write("Satellite-" + satName);
                w.newLine(); w.newLine(); w.newLine();

                w.write("Pass Number    Penumbra Entry (UTCG)        Umbra Entry (UTCG)            Umbra Exit (UTCG)           Penumbra Exit (UTCG)");
                w.newLine();
                w.write("-----------    -------------------------    --------------------------    ------------------------    ------------------------");
                w.newLine();

                for (EclipseReportRow r : rows) {
                    String penEntryStr = formatField(r.getPenEntry());
                    String umbEntryStr = formatField(r.getUmbEntry());
                    String umbExitStr  = formatField(r.getUmbExit());
                    String penExitStr  = formatField(r.getPenExit());

                    w.write(String.format(Locale.US, "%11d    %-25s    %-26s    %-24s    %-24s%n",
                            r.getOrbitNumber(), penEntryStr, umbEntryStr, umbExitStr, penExitStr));
                }
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================
    // Interval build: openStart/openEnd 반영
    // =========================================================
    private List<IntervalEx> buildIntervalsFromEdges(
            EclipseDetector det,
            AbsoluteDate start,
            AbsoluteDate end,
            PVCoordinates pvAtStart,
            Frame inertial,
            List<EdgeEvent> edges
    ) {
        List<IntervalEx> intervals = new ArrayList<>();

        boolean inShadow = gAt(det, start, pvAtStart.getPosition(), pvAtStart.getVelocity(), inertial) < 0.0;

        // start 시점에 이미 inShadow라면 ENTRY는 관측 못한 상태(openStart)
        AbsoluteDate curEntry = inShadow ? start : null;
        boolean curOpenStart  = inShadow;

        for (EdgeEvent e : edges) {
            if (e.getT().compareTo(start) < 0) continue;
            if (e.getT().compareTo(end) > 0) break;

            if (e.getType() == EdgeType.ENTRY) {
                if (!inShadow) {
                    inShadow = true;
                    curEntry = e.getT();
                    curOpenStart = false; // ENTRY 관측됨
                }
            } else { // EXIT
                if (inShadow) {
                    inShadow = false;
                    AbsoluteDate exit = e.getT();
                    if (curEntry != null && curEntry.compareTo(exit) < 0) {
                        intervals.add(new IntervalEx(curEntry, exit, curOpenStart, false /*openEnd*/));
                    }
                    curEntry = null;
                    curOpenStart = false;
                }
            }
        }

        // end 시점까지 계속 inShadow면 EXIT 미관측(openEnd)
        if (inShadow && curEntry != null && curEntry.compareTo(end) < 0) {
            intervals.add(new IntervalEx(curEntry, end, curOpenStart, true /*openEnd*/));
        }

        intervals.sort(Comparator.comparing(x -> x.getStart()));
        return intervals;
    }

    // =========================================================
    // Edge detection (HermiteEventUtils 사용)
    // =========================================================
    private List<EdgeEvent> collectEdgesNoProp(
            EclipseDetector det,
            AbsoluteDate[] tAbs,
            Vector3D[] rInert,
            Vector3D[] vInert,
            AbsoluteDate start,
            AbsoluteDate end,
            double subStepSec,
            double rootTolSec,
            int rootMaxIter,
            Frame inertial
    ) {
        List<EdgeEvent> edges = new ArrayList<>();

        int i0 = lowerBound(tAbs, start);
        if (i0 > 0) i0--;
        int i1 = upperBound(tAbs, end);
        if (i1 < tAbs.length - 1) i1++;

        final double dedupSec = Math.max(rootTolSec * 5.0, 1e-3);
        final double epsClass = Math.max(rootTolSec * 2.0, 1e-3);

        HermiteEventUtils.ScalarFunction gFunc = (tt, pos, vel) -> gAt(det, tt, pos, vel, inertial);

        for (int i = Math.max(1, i0 + 1); i <= Math.min(tAbs.length - 1, i1); i++) {

            AbsoluteDate a = tAbs[i - 1];
            AbsoluteDate b = tAbs[i];
            if (b.compareTo(start) <= 0) continue;
            if (a.compareTo(end) >= 0) break;

            AbsoluteDate t0 = tAbs[i - 1];
            AbsoluteDate t1 = tAbs[i];
            Vector3D r0 = rInert[i - 1], vv0 = vInert[i - 1];
            Vector3D r1 = rInert[i],     vv1 = vInert[i];

            AbsoluteDate segStart = (a.compareTo(start) < 0) ? start : a;
            AbsoluteDate segEnd   = (b.compareTo(end)   > 0) ? end   : b;

            double dtSeg = segEnd.durationFrom(segStart);
            if (dtSeg <= 0) continue;

            double baseSampleStep = Math.max(1e-9, b.durationFrom(a));
            double effStep = Math.min(subStepSec, baseSampleStep);
            int m = Math.max(1, (int) Math.ceil(dtSeg / effStep));

            AbsoluteDate prevT = segStart;
            HermiteEventUtils.PV pvPrev = pvInterpolateHermite(prevT, t0, r0, vv0, t1, r1, vv1);
            double prevG = gFunc.value(prevT, pvPrev.pos(), pvPrev.vel());

            for (int s = 1; s <= m; s++) {
                double frac = (double) s / (double) m;
                AbsoluteDate curT = segStart.shiftedBy(dtSeg * frac);

                HermiteEventUtils.PV pvCur = pvInterpolateHermite(curT, t0, r0, vv0, t1, r1, vv1);
                double curG = gFunc.value(curT, pvCur.pos(), pvCur.vel());

                if (prevG == 0.0 || curG == 0.0 || prevG * curG < 0.0) {

                    AbsoluteDate tRoot = HermiteEventUtils.refineRootTimeHermiteBisection(
                            prevT, pvPrev.pos(), pvPrev.vel(),
                            curT,  pvCur.pos(),  pvCur.vel(),
                            gFunc,
                            0.0,
                            rootTolSec,
                            rootMaxIter
                    );

                    EdgeType type = classifyEdgeBySign(det, tRoot,
                            prevT, pvPrev.pos(), pvPrev.vel(),
                            curT,  pvCur.pos(),  pvCur.vel(),
                            epsClass, inertial
                    );

                    if (edges.isEmpty()) {
                        edges.add(new EdgeEvent(tRoot, type));
                    } else {
                        EdgeEvent last = edges.get(edges.size() - 1);
                        if (Math.abs(tRoot.durationFrom(last.getT())) > dedupSec) {
                            edges.add(new EdgeEvent(tRoot, type));
                        } else {
                            edges.set(edges.size() - 1, new EdgeEvent(tRoot, type));
                        }
                    }
                }

                prevT = curT;
                pvPrev = pvCur;
                prevG = curG;
            }
        }

        edges.sort(Comparator.comparing(EdgeEvent::getT));
        return sanitizeEdges(edges, start, end, Math.max(rootTolSec * 2.0, 1e-3));
    }

    private EdgeType classifyEdgeBySign(
            EclipseDetector det,
            AbsoluteDate tRoot,
            AbsoluteDate ta, Vector3D ra, Vector3D va,
            AbsoluteDate tb, Vector3D rb, Vector3D vb,
            double epsSec,
            Frame inertial
    ) {
        AbsoluteDate tBefore = clampDate(tRoot.shiftedBy(-epsSec), ta, tb);
        AbsoluteDate tAfter  = clampDate(tRoot.shiftedBy(+epsSec), ta, tb);

        HermiteEventUtils.PV pvBef = pvInterpolateHermite(tBefore, ta, ra, va, tb, rb, vb);
        HermiteEventUtils.PV pvAft = pvInterpolateHermite(tAfter,  ta, ra, va, tb, rb, vb);

        double gBefore = gAt(det, tBefore, pvBef.pos(), pvBef.vel(), inertial);
        double gAfter  = gAt(det, tAfter,  pvAft.pos(), pvAft.vel(), inertial);

        if (gBefore > 0.0 && gAfter < 0.0) return EdgeType.ENTRY;
        if (gBefore < 0.0 && gAfter > 0.0) return EdgeType.EXIT;

        return (gBefore > gAfter) ? EdgeType.ENTRY : EdgeType.EXIT;
    }

    // =========================================================
    // g evaluation
    // =========================================================
    private double gAt(EclipseDetector det, AbsoluteDate t, Vector3D pos, Vector3D vel, Frame inertial) {
        PVCoordinates pv = new PVCoordinates(pos, vel);
        Orbit o = new CartesianOrbit(pv, inertial, t, Constants.EGM96_EARTH_MU);
        SpacecraftState s = new SpacecraftState(o);
        return det.g(s);
    }

    // =========================================================
    // Hermite interpolation + PV at time
    // =========================================================
    private HermiteEventUtils.PV pvInterpolateHermite(
            AbsoluteDate t,
            AbsoluteDate t0, Vector3D r0, Vector3D v0,
            AbsoluteDate t1, Vector3D r1, Vector3D v1
    ) {
        double dt = t1.durationFrom(t0);
        if (dt <= 0) return new HermiteEventUtils.PV(r0, v0);
        double tau = t.durationFrom(t0) / dt;
        return HermiteEventUtils.hermitePV(r0, v0, r1, v1, dt, tau);
    }

    private PVCoordinates pvAtTimeNoProp(
            AbsoluteDate t,
            AbsoluteDate[] tAbs,
            Vector3D[] rInert,
            Vector3D[] vInert
    ) {
        int idx = lowerBound(tAbs, t);
        if (idx <= 0) return new PVCoordinates(rInert[0], vInert[0]);
        if (idx >= tAbs.length) {
            int last = tAbs.length - 1;
            return new PVCoordinates(rInert[last], vInert[last]);
        }

        AbsoluteDate t0 = tAbs[idx - 1];
        AbsoluteDate t1 = tAbs[idx];

        HermiteEventUtils.PV pv = pvInterpolateHermite(
                t,
                t0, rInert[idx - 1], vInert[idx - 1],
                t1, rInert[idx],     vInert[idx]
        );
        return new PVCoordinates(pv.pos(), pv.vel());
    }

    // =========================================================
    // Output helpers
    // =========================================================
    private FieldValue fieldByWindow(AbsoluteDate t, AbsoluteDate winStart, AbsoluteDate winEnd) {
        if (t == null) return FieldValue.notInPass();
        if (t.compareTo(winStart) < 0) return FieldValue.notInPass();
        if (t.compareTo(winEnd) > 0) return FieldValue.notInPass();
        return FieldValue.time(t);
    }

    private String formatField(FieldValue f) {
        if (f == null) return "             Not in Pass";
        if (f.getState() == FieldState.TIME && f.getTime() != null) {
            return timeConverter.toUtcAbbrMSec(f.getTime());
        }
        if (f.getState() == FieldState.NO_UMBRA) return "             No Umbra";
        return "             Not in Pass";
    }

    private long orbitAtByPeriodFallback(long baseOrbit, AbsoluteDate baseDate, AbsoluteDate t, double periodSec) {
        double dt = t.durationFrom(baseDate);
        long inc = (long) Math.floor(dt / periodSec);
        return baseOrbit + inc;
    }

    // =========================================================
    // Edge post-processing
    // =========================================================
    private List<EdgeEvent> sanitizeEdges(List<EdgeEvent> edges, AbsoluteDate start, AbsoluteDate end, double minGapSec) {
        if (edges.isEmpty()) return edges;

        List<EdgeEvent> out = new ArrayList<>();
        for (EdgeEvent e : edges) {
            if (e.getT().compareTo(start) < 0 || e.getT().compareTo(end) > 0) continue;

            if (out.isEmpty()) {
                out.add(e);
                continue;
            }

            EdgeEvent last = out.get(out.size() - 1);

            if (Math.abs(e.getT().durationFrom(last.getT())) < minGapSec) {
                out.set(out.size() - 1, e);
                continue;
            }

            if (last.getType() == e.getType()) {
                out.set(out.size() - 1, e);
                continue;
            }

            out.add(e);
        }
        return out;
    }

    private AbsoluteDate clampDate(AbsoluteDate t, AbsoluteDate min, AbsoluteDate max) {
        if (t.compareTo(min) < 0) return min;
        if (t.compareTo(max) > 0) return max;
        return t;
    }

    // =========================================================
    // Binary search
    // =========================================================
    private int lowerBound(AbsoluteDate[] arr, AbsoluteDate x) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].compareTo(x) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private int upperBound(AbsoluteDate[] arr, AbsoluteDate x) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].compareTo(x) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}