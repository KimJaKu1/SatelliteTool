package org.sat_tool.domain.event.service;

import org.hipparchus.geometry.euclidean.threed.Line;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.Transform;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.visuallizse.model.FovParams;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@DependsOn("orekitInitializer")
public class CaptureService {

    public record GeoLL(double latDeg, double lonDeg) {}

    public record ImagingOpportunity(
            AbsoluteDate startUtc,
            AbsoluteDate endUtc,
            AbsoluteDate midUtc,
            double usedRollDegAtMid,
            List<GeoLL> footprintAtMid
    ) {}

    private record CamAxes(Vector3D x, Vector3D y, Vector3D z) {}


    public List<ImagingOpportunity> computeScheduleWithFootprints(
            String tleLine1, String tleLine2,
            double targetLatDeg, double targetLonDeg, double targetH_m,
            AbsoluteDate t0, AbsoluteDate t1,
            double stepSec,
            FovParams fov,
            double rollLimitDeg) {

        // Frames & Earth
        Frame teme = FramesFactory.getTEME();
        Frame itrs = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        OneAxisEllipsoid earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING, itrs);

        // Propagator
        TLE tle = new TLE(tleLine1, tleLine2);
        TLEPropagator prop = TLEPropagator.selectExtrapolator(tle);

        // Target on ellipsoid
        GeodeticPoint targetGeo = new GeodeticPoint(
                Math.toRadians(targetLatDeg),
                Math.toRadians(targetLonDeg),
                targetH_m);
        Vector3D targetEcef = earth.transform(targetGeo);

        // FOV half-angles (radians)
        double Wx = fov.getWpx() * fov.getPixelPitch_m();
        double Hy = fov.getHpx() * fov.getPixelPitch_m();
        double crossHalf = Math.atan((Wx / 2.0) / fov.getFocalLength_m());
        double alongHalf = Math.atan((Hy / 2.0) / fov.getFocalLength_m());

        // Loop
        double h = Math.max(0.5, stepSec);

        boolean inside = false;
        AbsoluteDate winStart = null;
        List<ImagingOpportunity> out = new ArrayList<>();

        for (AbsoluteDate t = t0;
             t.compareTo(t1) <= 0;
             t = t.shiftedBy(h)) {

            // PV in TEME → ITRF
            PVCoordinates pvTeme = prop.getPVCoordinates(t, teme);
            Transform toItrf = teme.getTransformTo(itrs, t);
            Vector3D rEcef = toItrf.transformPosition(pvTeme.getPosition());
            Vector3D vEcef = toItrf.transformVector(pvTeme.getVelocity());

            // 1) LOS(가시성) 체크: 위성→타깃으로 쏜 직선이 지표와 먼저 교차하면 불가시
            if (!hasLineOfSight(earth, itrs, t, rEcef, targetEcef)) {
                if (inside) { // 창 닫기
                    AbsoluteDate end = t.shiftedBy(-h);
                    if (end.compareTo(winStart) >= 0) {
                        out.add(makeOpportunity(earth, itrs, prop, teme,
                                targetEcef, fov, rollLimitDeg,
                                winStart, end));
                    }
                    inside = false;
                }
                continue;
            }

            // 2) 카메라 축 구성
            CamAxes cam = buildCameraAxes(rEcef, vEcef);

            // 3) 타깃 방향의 카메라 성분
            Vector3D u = targetEcef.subtract(rEcef).normalize();
            double ux = u.dotProduct(cam.x);
            double uy = u.dotProduct(cam.y);
            double uz = u.dotProduct(cam.z);

            // 4) 요구 롤(about y) → 제한
            double rollReq = Math.atan2(ux, uz); // rad
            double rollUse = clamp(rollReq, Math.toRadians(-rollLimitDeg), Math.toRadians(rollLimitDeg));

            // 롤 적용 좌표(카메라 기준)에서 각도 계산
            double c = Math.cos(rollUse), s = Math.sin(rollUse);
            double uxR = c * ux - s * uz;
            double uyR = uy;
            double uzR = s * ux + c * uz;

            double crossAng = Math.atan2(uxR, uzR);
            double alongAng = Math.atan2(uyR, uzR);
            boolean withinFov = Math.abs(crossAng) <= crossHalf &&
                    Math.abs(alongAng) <= alongHalf &&
                    uzR > 0;

            if (withinFov) {
                if (!inside) { inside = true; winStart = t; }
            } else {
                if (inside) {
                    AbsoluteDate end = t.shiftedBy(-h);
                    if (end.compareTo(winStart) >= 0) {
                        out.add(makeOpportunity(earth, itrs, prop, teme,
                                targetEcef, fov, rollLimitDeg,
                                winStart, end));
                    }
                    inside = false;
                }
            }
        }

        if (inside) {
            out.add(makeOpportunity(earth, itrs, prop, teme,
                    targetEcef, fov, rollLimitDeg,
                    winStart, t1));
        }

        return out;
    }

    // ====== LOS 체크 ======
    private boolean hasLineOfSight(OneAxisEllipsoid earth, Frame itrs, AbsoluteDate date,
                                   Vector3D rSat, Vector3D pTgt) {
        Vector3D dir = pTgt.subtract(rSat);
        double distT = dir.getNorm();
        dir = dir.normalize();

        GeodeticPoint firstHit = intersect(earth, itrs, date, rSat, dir);
        if (firstHit == null) return true; // 교점 없음 → 가시라고 가정(실제론 드뭄)

        Vector3D hitECEF = earth.transform(firstHit);
        double distHit = hitECEF.subtract(rSat).getNorm();
        return distHit >= distT - 1.0; // 지표를 먼저 치면 가림
    }

    // 타원체 교차 (Orekit 유틸)
    private GeodeticPoint intersect(OneAxisEllipsoid earth, Frame itrs, AbsoluteDate date,
                                    Vector3D p0, Vector3D dir) {
        Line line = new Line(p0, p0.add(dir), 1.0e-10);
        return earth.getIntersectionPoint(line, p0, itrs, date);
    }

    // ====== 윈도우 중앙시각 폴리곤/롤 산출 ======
    private ImagingOpportunity makeOpportunity(
            OneAxisEllipsoid earth, Frame itrs, TLEPropagator prop, Frame teme,
            Vector3D targetEcef, FovParams fov, double rollLimitDeg,
            AbsoluteDate start, AbsoluteDate end) {

        AbsoluteDate mid = start.shiftedBy(0.5 * end.durationFrom(start));

        PVCoordinates pvTeme = prop.getPVCoordinates(mid, teme);
        Transform toItrf = teme.getTransformTo(itrs, mid);
        Vector3D r = toItrf.transformPosition(pvTeme.getPosition());
        Vector3D v = toItrf.transformVector(pvTeme.getVelocity());

        var cam = buildCameraAxes(r, v);
        Vector3D u = targetEcef.subtract(r).normalize();
        double ux = u.dotProduct(cam.x);
        double uz = u.dotProduct(cam.z);
        double rollReq = Math.atan2(ux, uz);
        double rollUse = clamp(rollReq, Math.toRadians(-rollLimitDeg), Math.toRadians(rollLimitDeg));
        double usedRollDeg = Math.toDegrees(rollUse);

        List<GeoLL> poly = footprintAtInstant(earth, itrs, mid, r, v, fov, usedRollDeg, 0.0);
        return new ImagingOpportunity(start, end, mid, usedRollDeg, poly);
    }

    private CamAxes buildCameraAxes(Vector3D r, Vector3D v) {
        Vector3D z = r.negate().normalize();                          // 나디르
        Vector3D vHoriz = v.subtract(z.scalarMultiply(v.dotProduct(z)));
        if (vHoriz.getNormSq() < 1e-18) vHoriz = new Vector3D(-r.getY(), r.getX(), 0.0);
        Vector3D y = vHoriz.normalize();                              // 얼롱
        Vector3D x = Vector3D.crossProduct(y, z).normalize();         // 크로스
        // 정규직교 보정
        z = z.normalize();
        x = x.subtract(z.scalarMultiply(x.dotProduct(z))).normalize();
        y = Vector3D.crossProduct(z, x).normalize();
        return new CamAxes(x, y, z);
    }

    private static double clamp(double x, double lo, double hi) {
        return (x < lo) ? lo : (x > hi) ? hi : x;
    }

    private List<GeoLL> footprintAtInstant(
            OneAxisEllipsoid earth, Frame itrs, AbsoluteDate date,
            Vector3D r, Vector3D v,
            FovParams fov, double rollDeg, double pitchDeg) {

        CamAxes cam = buildCameraAxes(r, v);

        // roll → pitch 순서
        if (Math.abs(rollDeg) > 1e-12) cam = rotateAround(cam, cam.y, Math.toRadians(rollDeg));
        if (Math.abs(pitchDeg) > 1e-12) cam = rotateAround(cam, cam.x, Math.toRadians(pitchDeg));
        cam = orthonormalize(cam);

        double Wx = fov.getWpx() * fov.getPixelPitch_m();
        double Hy = fov.getHpx() * fov.getPixelPitch_m();
        double crossHalf = Math.atan((Wx / 2.0) / fov.getFocalLength_m());
        double alongHalf = Math.atan((Hy / 2.0) / fov.getFocalLength_m());
        double tx = Math.tan(crossHalf);
        double ty = Math.tan(alongHalf);

        int[][] corners = { {+1,+1}, {+1,-1}, {-1,-1}, {-1,+1} };
        List<GeoLL> out = new ArrayList<>(4);

        for (int[] c : corners) {
            int sx = c[0], sy = c[1];
            Vector3D dir = cam.z.add(cam.x.scalarMultiply(sx * tx)).add(cam.y.scalarMultiply(sy * ty)).normalize();

            // 위성 → dir 방향 직선과 타원체 교차
            GeodeticPoint gp = intersect(earth, itrs, date, r, dir);
            if (gp == null) return List.of(); // 지평선 아래 등 교차 실패 → 빈 폴리곤

            double lat = Math.toDegrees(gp.getLatitude());
            double lon = wrapLonDeg(Math.toDegrees(gp.getLongitude()));
            out.add(new GeoLL(lat, lon));
        }
        return out;
    }

    private CamAxes rotateAround(CamAxes a, Vector3D axis, double ang) {
        return new CamAxes(
                rodrigues(a.x, axis, ang),
                rodrigues(a.y, axis, ang),
                rodrigues(a.z, axis, ang)
        );
    }

    private Vector3D rodrigues(Vector3D v, Vector3D axis, double ang) {
        Vector3D k = axis.normalize();
        double c = Math.cos(ang), s = Math.sin(ang), one_c = 1.0 - c;
        double kv = k.dotProduct(v);
        Vector3D cross = Vector3D.crossProduct(k, v);
        return v.scalarMultiply(c)
                .add(cross.scalarMultiply(s))
                .add(k.scalarMultiply(kv * one_c));
    }

    private static double wrapLonDeg(double lon) {
        // [-180, 180)
        while (lon >= 180.0) lon -= 360.0;
        while (lon <  -180.0) lon += 360.0;
        return lon;
    }

    private CamAxes orthonormalize(CamAxes a) {
        Vector3D z = a.z.normalize();
        Vector3D x = a.x.subtract(z.scalarMultiply(a.x.dotProduct(z))).normalize();
        Vector3D y = Vector3D.crossProduct(z, x).normalize();
        return new CamAxes(x, y, z);
    }
}
