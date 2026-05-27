package org.sat_tool.domain.event.capture.service;

import org.hipparchus.geometry.euclidean.threed.Line;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.common.helper.HermiteEventUtils;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.sat_tool.domain.propagation.service.PropagatorService;
import org.sat_tool.domain.visuallizse.model.FovParams;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@DependsOn("orekitInitializer")
public class CaptureService {

    private static final ConcurrentMap<Path, ReentrantLock> FILE_LOCK = new ConcurrentHashMap<>();
    private static final double MIN_STEP_SEC = 0.5;
    private static final double BOUNDARY_TOL_SEC = 1.0e-3;
    private static final int BOUNDARY_MAX_ITER = 48;
    private static final int BEST_TIME_MAX_SAMPLES = 256;

    public record GeoLL(double latDeg, double lonDeg) {}

    public record ImagingOpportunity(
            AbsoluteDate startUtc,
            AbsoluteDate endUtc,
            AbsoluteDate captureUtc,
            double usedRollDegAtCapture,
            double boresightErrorDegAtCapture,
            List<GeoLL> footprintAtCapture
    ) {}

    private record CamAxes(Vector3D x, Vector3D y, Vector3D z) {}

    private record FovGeometry(double crossHalfRad, double alongHalfRad) {}

    private record EcefSeries(
            AbsoluteDate[] timesUtc,
            Vector3D[] positionsEcef,
            Vector3D[] velocitiesEcef
    ) {}

    private record CaptureState(
            AbsoluteDate timeUtc,
            Vector3D satEcef,
            Vector3D velEcef,
            double usedRollRad,
            double crossAngleRad,
            double alongAngleRad,
            double boresightErrorRad,
            double feasibilityMarginRad,
            boolean hasLineOfSight
    ) {
        boolean isFeasible() {
            return hasLineOfSight && feasibilityMarginRad >= 0.0;
        }

        double targetingScore() {
            if (!isFeasible()) {
                return Double.POSITIVE_INFINITY;
            }
            return boresightErrorRad * boresightErrorRad + (1.0e-6 * usedRollRad * usedRollRad);
        }
    }

    @FunctionalInterface
    private interface CaptureStateProvider {
        CaptureState at(AbsoluteDate date);
    }

    private final PropagatorService propagatorService;

    public CaptureService(PropagatorService propagatorService) {
        this.propagatorService = propagatorService;
    }

    public List<ImagingOpportunity> computeScheduleWithFootprintsAndWriteFile(
            String tleLine1, String tleLine2,
            double targetLatDeg, double targetLonDeg, double targetH_m,
            AbsoluteDate t0, AbsoluteDate t1,
            double stepSec,
            FovParams fov,
            double rollLimitDeg,
            String satName,
            Path outputDir) {

        List<ImagingOpportunity> opportunities = computeScheduleWithFootprints(
                tleLine1, tleLine2,
                targetLatDeg, targetLonDeg, targetH_m,
                t0, t1,
                stepSec,
                fov,
                rollLimitDeg
        );

        generateCaptureFile(opportunities, satName, outputDir);
        return opportunities;
    }

    public List<ImagingOpportunity> computeScheduleWithFootprints(
            String tleLine1, String tleLine2,
            double targetLatDeg, double targetLonDeg, double targetH_m,
            AbsoluteDate t0, AbsoluteDate t1,
            double stepSec,
            FovParams fov,
            double rollLimitDeg) {

        if (t0 == null || t1 == null || t0.compareTo(t1) > 0) {
            return List.of();
        }

        validateFov(fov);

        Frame itrs = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrs
        );

        TLE tle = new TLE(tleLine1, tleLine2);
        TLEPropagator propagator = propagatorService.createPropagatorFromTle(tle);

        GeodeticPoint targetGeo = new GeodeticPoint(
                Math.toRadians(targetLatDeg),
                Math.toRadians(targetLonDeg),
                targetH_m
        );
        Vector3D targetEcef = earth.transform(targetGeo);

        FovGeometry fovGeometry = buildFovGeometry(fov);
        double rollLimitRad = Math.toRadians(Math.abs(rollLimitDeg));

        CaptureStateProvider provider = date -> evaluateCaptureState(
                date, earth, itrs, propagator, targetEcef, fovGeometry, rollLimitRad
        );

        return computeSchedule(provider, earth, itrs, t0, t1, stepSec, fov);
    }

    public List<ImagingOpportunity> computeScheduleWithFootprintsFromEcefAndWriteFile_NoPropagator(
            List<EphemerisVector> vectorsEcef,
            double targetLatDeg, double targetLonDeg, double targetH_m,
            AbsoluteDate start,
            AbsoluteDate end,
            double stepSec,
            FovParams fov,
            double rollLimitDeg,
            String satName,
            Path outputDir) {

        List<ImagingOpportunity> opportunities = computeScheduleWithFootprintsFromEcef_NoPropagator(
                vectorsEcef,
                targetLatDeg, targetLonDeg, targetH_m,
                start, end,
                stepSec,
                fov,
                rollLimitDeg
        );

        generateCaptureFile(opportunities, satName, outputDir);
        return opportunities;
    }

    public List<ImagingOpportunity> computeScheduleWithFootprintsFromEcef_NoPropagator(
            List<EphemerisVector> vectorsEcef,
            double targetLatDeg, double targetLonDeg, double targetH_m,
            AbsoluteDate start,
            AbsoluteDate end,
            double stepSec,
            FovParams fov,
            double rollLimitDeg) {

        if (start == null || end == null || start.compareTo(end) > 0) {
            return List.of();
        }

        validateFov(fov);

        EcefSeries series = buildEcefSeries(vectorsEcef);
        if (series == null || series.timesUtc().length < 2) {
            return List.of();
        }

        Frame itrs = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                itrs
        );

        GeodeticPoint targetGeo = new GeodeticPoint(
                Math.toRadians(targetLatDeg),
                Math.toRadians(targetLonDeg),
                targetH_m
        );
        Vector3D targetEcef = earth.transform(targetGeo);

        AbsoluteDate minT = series.timesUtc()[0];
        AbsoluteDate maxT = series.timesUtc()[series.timesUtc().length - 1];
        AbsoluteDate winStart = (start.compareTo(minT) < 0) ? minT : start;
        AbsoluteDate winEnd = (end.compareTo(maxT) > 0) ? maxT : end;
        // Allow single-instant evaluation when the requested search window
        // touches the last available ECEF sample exactly.
        if (winStart.compareTo(winEnd) > 0) {
            return List.of();
        }

        FovGeometry fovGeometry = buildFovGeometry(fov);
        double rollLimitRad = Math.toRadians(Math.abs(rollLimitDeg));

        CaptureStateProvider provider = date -> {
            PVCoordinates pv = pvAtTimeNoPropEcef(
                    date,
                    series.timesUtc(),
                    series.positionsEcef(),
                    series.velocitiesEcef()
            );
            return evaluateCaptureState(
                    date,
                    earth,
                    itrs,
                    pv.getPosition(),
                    pv.getVelocity(),
                    targetEcef,
                    fovGeometry,
                    rollLimitRad
            );
        };

        return computeSchedule(provider, earth, itrs, winStart, winEnd, stepSec, fov);
    }

    public void generateCaptureFile(List<ImagingOpportunity> opportunities, String satName, Path outputDir) {
        try {
            Files.createDirectories(outputDir);

            String normalizedSatName = normalizeSatName(satName);
            Path file = outputDir.resolve(normalizedSatName + "_Capture.txt");

            ReentrantLock lock = FILE_LOCK.computeIfAbsent(file, key -> new ReentrantLock());
            lock.lock();
            try {
                if (Files.exists(file)) {
                    Files.delete(file);
                }

                try (BufferedWriter writer = Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE)) {

                    writer.write(String.format("%141s%n",
                            TimeConverter.UTC_DT_HDR_ABBR.format(ZonedDateTime.now(ZoneOffset.UTC))));
                    writer.write("Satellite-" + normalizedSatName + ":  Roll-Only Capture Opportunity Schedule");
                    writer.newLine();
                    writer.newLine();
                    writer.newLine();

                    writer.write("Capture #      Start Time (UTCG)           Capture Time (UTCG)         Stop Time (UTCG)            Roll (deg)    Center Err (deg)");
                    writer.newLine();
                    writer.write("---------      ------------------------    ------------------------    ------------------------    ----------    ----------------");
                    writer.newLine();

                    List<ImagingOpportunity> sorted = new ArrayList<>();
                    if (opportunities != null) {
                        sorted.addAll(opportunities);
                    }
                    sorted.sort(Comparator.comparing(
                            ImagingOpportunity::captureUtc,
                            Comparator.nullsLast(AbsoluteDate::compareTo)
                    ));

                    for (int i = 0; i < sorted.size(); i++) {
                        ImagingOpportunity opportunity = sorted.get(i);
                        writer.write(String.format(Locale.US,
                                "%-9d      %-24s    %-24s    %-24s    %10.3f    %16.6f%n",
                                i + 1,
                                formatTime(opportunity.startUtc()),
                                formatTime(opportunity.captureUtc()),
                                formatTime(opportunity.endUtc()),
                                opportunity.usedRollDegAtCapture(),
                                opportunity.boresightErrorDegAtCapture()));
                        writer.write(String.format("%-9s      %s%n",
                                "Footprint",
                                formatFootprint(opportunity.footprintAtCapture())));
                    }

                    writer.flush();
                }
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<ImagingOpportunity> computeSchedule(
            CaptureStateProvider provider,
            OneAxisEllipsoid earth,
            Frame itrs,
            AbsoluteDate start,
            AbsoluteDate end,
            double stepSec,
            FovParams fov) {

        double sampleStepSec = Math.max(MIN_STEP_SEC, stepSec);
        List<ImagingOpportunity> opportunities = new ArrayList<>();

        AbsoluteDate cursor = start;
        CaptureState current = provider.at(cursor);
        AbsoluteDate windowStart = current.isFeasible() ? cursor : null;

        while (cursor.compareTo(end) < 0) {
            double dt = Math.min(sampleStepSec, end.durationFrom(cursor));
            AbsoluteDate next = cursor.shiftedBy(dt);

            CaptureState nextState = provider.at(next);

            if (!current.isFeasible() && nextState.isFeasible()) {
                windowStart = refineBoundary(provider, cursor, next, false);
            } else if (current.isFeasible() && !nextState.isFeasible()) {
                AbsoluteDate windowEnd = refineBoundary(provider, cursor, next, true);
                if (windowStart == null) {
                    windowStart = cursor;
                }
                opportunities.add(buildOpportunity(provider, earth, itrs, fov, windowStart, windowEnd));
                windowStart = null;
            }

            cursor = next;
            current = nextState;
        }

        if (current.isFeasible() && windowStart != null) {
            opportunities.add(buildOpportunity(provider, earth, itrs, fov, windowStart, end));
        }

        opportunities.sort(Comparator.comparing(
                ImagingOpportunity::captureUtc,
                Comparator.nullsLast(AbsoluteDate::compareTo)
        ));
        return opportunities;
    }

    private ImagingOpportunity buildOpportunity(
            CaptureStateProvider provider,
            OneAxisEllipsoid earth,
            Frame itrs,
            FovParams fov,
            AbsoluteDate start,
            AbsoluteDate end) {

        AbsoluteDate captureTime = findBestCaptureTime(provider, start, end);
        CaptureState captureState = provider.at(captureTime);

        if (!captureState.isFeasible()) {
            captureTime = midpoint(start, end);
            captureState = provider.at(captureTime);
        }

        List<GeoLL> footprint = footprintAtInstant(
                earth,
                itrs,
                captureTime,
                captureState.satEcef(),
                captureState.velEcef(),
                fov,
                Math.toDegrees(captureState.usedRollRad())
        );

        return new ImagingOpportunity(
                start,
                end,
                captureTime,
                Math.toDegrees(captureState.usedRollRad()),
                Math.toDegrees(captureState.boresightErrorRad()),
                footprint
        );
    }

    private AbsoluteDate findBestCaptureTime(
            CaptureStateProvider provider,
            AbsoluteDate start,
            AbsoluteDate end) {

        double durationSec = end.durationFrom(start);
        if (durationSec <= BOUNDARY_TOL_SEC) {
            return start;
        }

        int sampleCount = (int) Math.ceil(durationSec / 0.25);
        sampleCount = Math.max(16, Math.min(sampleCount, BEST_TIME_MAX_SAMPLES));

        AbsoluteDate bestTime = midpoint(start, end);
        double bestScore = Double.POSITIVE_INFINITY;

        for (int i = 0; i <= sampleCount; i++) {
            double frac = (double) i / (double) sampleCount;
            AbsoluteDate sample = start.shiftedBy(durationSec * frac);
            CaptureState state = provider.at(sample);
            double score = state.targetingScore();
            if (score < bestScore) {
                bestScore = score;
                bestTime = sample;
            }
        }

        double localStepSec = durationSec / sampleCount;
        while (localStepSec > BOUNDARY_TOL_SEC) {
            AbsoluteDate left = clamp(bestTime.shiftedBy(-localStepSec), start, end);
            AbsoluteDate right = clamp(bestTime.shiftedBy(localStepSec), start, end);

            double leftScore = provider.at(left).targetingScore();
            double centerScore = provider.at(bestTime).targetingScore();
            double rightScore = provider.at(right).targetingScore();

            if (leftScore < centerScore && leftScore <= rightScore) {
                bestTime = left;
            } else if (rightScore < centerScore && rightScore < leftScore) {
                bestTime = right;
            } else {
                localStepSec *= 0.5;
            }
        }

        return bestTime;
    }

    private AbsoluteDate refineBoundary(
            CaptureStateProvider provider,
            AbsoluteDate low,
            AbsoluteDate high,
            boolean lowIsFeasible) {

        AbsoluteDate left = low;
        AbsoluteDate right = high;
        boolean leftFeasible = lowIsFeasible;

        for (int i = 0; i < BOUNDARY_MAX_ITER; i++) {
            if (right.durationFrom(left) <= BOUNDARY_TOL_SEC) {
                break;
            }

            AbsoluteDate mid = midpoint(left, right);
            CaptureState midState = provider.at(mid);
            if (midState.isFeasible() == leftFeasible) {
                left = mid;
            } else {
                right = mid;
            }
        }

        return leftFeasible ? left : right;
    }

    private CaptureState evaluateCaptureState(
            AbsoluteDate date,
            OneAxisEllipsoid earth,
            Frame itrs,
            TLEPropagator propagator,
            Vector3D targetEcef,
            FovGeometry fovGeometry,
            double rollLimitRad) {

        PVCoordinates pvEcef = propagator.getPVCoordinates(date, itrs);
        Vector3D satEcef = pvEcef.getPosition();
        Vector3D velEcef = pvEcef.getVelocity();

        return evaluateCaptureState(
                date, earth, itrs, satEcef, velEcef, targetEcef, fovGeometry, rollLimitRad
        );
    }

    private CaptureState evaluateCaptureState(
            AbsoluteDate date,
            OneAxisEllipsoid earth,
            Frame itrs,
            Vector3D satEcef,
            Vector3D velEcef,
            Vector3D targetEcef,
            FovGeometry fovGeometry,
            double rollLimitRad) {

        boolean hasLineOfSight = hasLineOfSight(earth, itrs, date, satEcef, targetEcef);
        if (!hasLineOfSight) {
            return new CaptureState(date, satEcef, velEcef, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, -1.0, false);
        }

        CamAxes cam = buildCameraAxes(satEcef, velEcef);
        Vector3D look = targetEcef.subtract(satEcef).normalize();

        double ux = look.dotProduct(cam.x());
        double uy = look.dotProduct(cam.y());
        double uz = look.dotProduct(cam.z());

        double requiredRollRad = Math.atan2(ux, uz);
        double usedRollRad = clamp(requiredRollRad, -rollLimitRad, rollLimitRad);

        double c = Math.cos(usedRollRad);
        double s = Math.sin(usedRollRad);

        double uxAfterRoll = c * ux - s * uz;
        double uyAfterRoll = uy;
        double uzAfterRoll = s * ux + c * uz;

        if (uzAfterRoll <= 0.0) {
            return new CaptureState(date, satEcef, velEcef, usedRollRad, 0.0, 0.0, Double.POSITIVE_INFINITY, -1.0, true);
        }

        double crossAngleRad = Math.atan2(uxAfterRoll, uzAfterRoll);
        double alongAngleRad = Math.atan2(uyAfterRoll, uzAfterRoll);
        double boresightErrorRad = Math.atan2(Math.hypot(uxAfterRoll, uyAfterRoll), uzAfterRoll);

        double feasibilityMarginRad = Math.min(
                fovGeometry.crossHalfRad() - Math.abs(crossAngleRad),
                fovGeometry.alongHalfRad() - Math.abs(alongAngleRad)
        );

        return new CaptureState(
                date,
                satEcef,
                velEcef,
                usedRollRad,
                crossAngleRad,
                alongAngleRad,
                boresightErrorRad,
                feasibilityMarginRad,
                true
        );
    }

    private boolean hasLineOfSight(
            OneAxisEllipsoid earth,
            Frame itrs,
            AbsoluteDate date,
            Vector3D satEcef,
            Vector3D targetEcef) {

        Vector3D direction = targetEcef.subtract(satEcef);
        double targetDistance = direction.getNorm();
        direction = direction.normalize();

        GeodeticPoint firstHit = intersect(earth, itrs, date, satEcef, direction);
        if (firstHit == null) {
            return true;
        }

        Vector3D hitEcef = earth.transform(firstHit);
        double hitDistance = hitEcef.subtract(satEcef).getNorm();
        return hitDistance >= targetDistance - 1.0;
    }

    private GeodeticPoint intersect(
            OneAxisEllipsoid earth,
            Frame itrs,
            AbsoluteDate date,
            Vector3D point,
            Vector3D direction) {
        Line line = new Line(point, point.add(direction), 1.0e-10);
        return earth.getIntersectionPoint(line, point, itrs, date);
    }

    private CamAxes buildCameraAxes(Vector3D satEcef, Vector3D velEcef) {
        Vector3D z = satEcef.negate().normalize();
        Vector3D horizontalVelocity = velEcef.subtract(z.scalarMultiply(velEcef.dotProduct(z)));
        if (horizontalVelocity.getNormSq() < 1e-18) {
            horizontalVelocity = new Vector3D(-satEcef.getY(), satEcef.getX(), 0.0);
        }

        Vector3D y = horizontalVelocity.normalize();
        Vector3D x = Vector3D.crossProduct(y, z).normalize();
        z = z.normalize();
        x = x.subtract(z.scalarMultiply(x.dotProduct(z))).normalize();
        y = Vector3D.crossProduct(z, x).normalize();
        return new CamAxes(x, y, z);
    }

    private List<GeoLL> footprintAtInstant(
            OneAxisEllipsoid earth,
            Frame itrs,
            AbsoluteDate date,
            Vector3D satEcef,
            Vector3D velEcef,
            FovParams fov,
            double rollDeg) {

        CamAxes cam = buildCameraAxes(satEcef, velEcef);
        if (Math.abs(rollDeg) > 1e-12) {
            cam = rotateAround(cam, cam.y(), Math.toRadians(rollDeg));
        }
        cam = orthonormalize(cam);

        FovGeometry fovGeometry = buildFovGeometry(fov);
        double tanCross = Math.tan(fovGeometry.crossHalfRad());
        double tanAlong = Math.tan(fovGeometry.alongHalfRad());

        int[][] corners = {{+1, +1}, {+1, -1}, {-1, -1}, {-1, +1}};
        List<GeoLL> polygon = new ArrayList<>(4);

        for (int[] corner : corners) {
            Vector3D direction = cam.z()
                    .add(cam.x().scalarMultiply(corner[0] * tanCross))
                    .add(cam.y().scalarMultiply(corner[1] * tanAlong))
                    .normalize();

            GeodeticPoint hit = intersect(earth, itrs, date, satEcef, direction);
            if (hit == null) {
                return List.of();
            }

            polygon.add(new GeoLL(
                    Math.toDegrees(hit.getLatitude()),
                    wrapLonDeg(Math.toDegrees(hit.getLongitude()))
            ));
        }

        return polygon;
    }

    private CamAxes rotateAround(CamAxes axes, Vector3D axis, double angleRad) {
        return new CamAxes(
                rodrigues(axes.x(), axis, angleRad),
                rodrigues(axes.y(), axis, angleRad),
                rodrigues(axes.z(), axis, angleRad)
        );
    }

    private Vector3D rodrigues(Vector3D vector, Vector3D axis, double angleRad) {
        Vector3D k = axis.normalize();
        double c = Math.cos(angleRad);
        double s = Math.sin(angleRad);
        double oneMinusC = 1.0 - c;
        double kv = k.dotProduct(vector);
        Vector3D cross = Vector3D.crossProduct(k, vector);
        return vector.scalarMultiply(c)
                .add(cross.scalarMultiply(s))
                .add(k.scalarMultiply(kv * oneMinusC));
    }

    private CamAxes orthonormalize(CamAxes axes) {
        Vector3D z = axes.z().normalize();
        Vector3D x = axes.x().subtract(z.scalarMultiply(axes.x().dotProduct(z))).normalize();
        Vector3D y = Vector3D.crossProduct(z, x).normalize();
        return new CamAxes(x, y, z);
    }

    private EcefSeries buildEcefSeries(List<EphemerisVector> vectorsEcef) {
        if (vectorsEcef == null) {
            return null;
        }

        List<EphemerisVector> samples = new ArrayList<>();
        for (EphemerisVector vector : vectorsEcef) {
            if (vector == null || vector.getTime() == null || vector.getPos() == null || vector.getVel() == null) {
                continue;
            }
            samples.add(vector);
        }

        if (samples.size() < 2) {
            return null;
        }

        samples.sort(Comparator.comparing(EphemerisVector::getTime));

        int n = samples.size();
        AbsoluteDate[] times = new AbsoluteDate[n];
        Vector3D[] positions = new Vector3D[n];
        Vector3D[] velocities = new Vector3D[n];

        for (int i = 0; i < n; i++) {
            EphemerisVector sample = samples.get(i);
            times[i] = TimeConverter.localDateTimeUtcToAbsoluteDate(sample.getTime());
            positions[i] = sample.getPos();
            velocities[i] = sample.getVel();
        }

        return new EcefSeries(times, positions, velocities);
    }

    private PVCoordinates pvAtTimeNoPropEcef(
            AbsoluteDate date,
            AbsoluteDate[] timesUtc,
            Vector3D[] positionsEcef,
            Vector3D[] velocitiesEcef) {

        int idx = lowerBound(timesUtc, date);
        if (idx <= 0) {
            return new PVCoordinates(positionsEcef[0], velocitiesEcef[0]);
        }
        if (idx >= timesUtc.length) {
            int last = timesUtc.length - 1;
            return new PVCoordinates(positionsEcef[last], velocitiesEcef[last]);
        }

        AbsoluteDate t0 = timesUtc[idx - 1];
        AbsoluteDate t1 = timesUtc[idx];
        Vector3D r0 = positionsEcef[idx - 1];
        Vector3D v0 = velocitiesEcef[idx - 1];
        Vector3D r1 = positionsEcef[idx];
        Vector3D v1 = velocitiesEcef[idx];

        double dt = t1.durationFrom(t0);
        if (dt <= 0.0) {
            return new PVCoordinates(r0, v0);
        }

        double tau = date.durationFrom(t0) / dt;
        HermiteEventUtils.PV pv = HermiteEventUtils.hermitePV(r0, v0, r1, v1, dt, tau);
        return new PVCoordinates(pv.pos(), pv.vel());
    }

    private int lowerBound(AbsoluteDate[] arr, AbsoluteDate x) {
        int lo = 0;
        int hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid].compareTo(x) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private FovGeometry buildFovGeometry(FovParams fov) {
        double widthMeters = fov.getWpx() * fov.getPixelPitch_m();
        double heightMeters = fov.getHpx() * fov.getPixelPitch_m();
        return new FovGeometry(
                Math.atan((widthMeters / 2.0) / fov.getFocalLength_m()),
                Math.atan((heightMeters / 2.0) / fov.getFocalLength_m())
        );
    }

    private void validateFov(FovParams fov) {
        if (fov == null) {
            throw new IllegalArgumentException("FovParams must not be null.");
        }
        if (fov.getFocalLength_m() <= 0.0) {
            throw new IllegalArgumentException("Focal length must be greater than zero.");
        }
        if (fov.getPixelPitch_m() <= 0.0) {
            throw new IllegalArgumentException("Pixel pitch must be greater than zero.");
        }
        if (fov.getWpx() <= 0 || fov.getHpx() <= 0) {
            throw new IllegalArgumentException("Image width and height must be greater than zero.");
        }
    }

    private String formatTime(AbsoluteDate date) {
        return (date == null) ? "                     N/A" : TimeConverter.toUtcAbbrMSec(date);
    }

    private String formatFootprint(List<GeoLL> footprint) {
        if (footprint == null || footprint.isEmpty()) {
            return "No footprint at capture time";
        }

        StringJoiner joiner = new StringJoiner(" | ");
        for (int i = 0; i < footprint.size(); i++) {
            GeoLL point = footprint.get(i);
            joiner.add(String.format(Locale.US,
                    "P%d (%.6f, %.6f)",
                    i + 1,
                    point.latDeg(),
                    point.lonDeg()));
        }
        return joiner.toString();
    }

    private String normalizeSatName(String satName) {
        if (satName == null || satName.isBlank()) {
            return "Capture";
        }
        return satName.trim();
    }

    private AbsoluteDate midpoint(AbsoluteDate start, AbsoluteDate end) {
        return start.shiftedBy(0.5 * end.durationFrom(start));
    }

    private AbsoluteDate clamp(AbsoluteDate date, AbsoluteDate min, AbsoluteDate max) {
        if (date.compareTo(min) < 0) {
            return min;
        }
        if (date.compareTo(max) > 0) {
            return max;
        }
        return date;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double wrapLonDeg(double lonDeg) {
        while (lonDeg >= 180.0) {
            lonDeg -= 360.0;
        }
        while (lonDeg < -180.0) {
            lonDeg += 360.0;
        }
        return lonDeg;
    }
}
