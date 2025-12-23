package org.example.sattool;

import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.sat_tool.SatToolApplication;
import org.sat_tool.domain.event.service.CaptureService;
import org.sat_tool.domain.visuallizse.model.FovParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;



@SpringBootTest(classes = SatToolApplication.class)
public class footprintTest_20250915 {

    @Autowired private CaptureService captureService;

    // 2) 사각 FOV 정의 (카메라 스펙→반시야각)
    double f = 2.500;       // m
    double pitch = 3.45e-6; // m
    int Wpx = 11664, Hpx = 8750;

    String line1;

    String line2;

    @Test
    void test202251103()
    {
        line1 = "1 40536U 15014A   25307.89866822  .00020269  00000-0  47720-3 0  9997";
        line2 = "2 40536  97.6740 280.1700 0001499 346.9781  13.1426 15.41987886586960";

        TLE tle = new TLE(line1,line2);

        AbsoluteDate t0 = new AbsoluteDate(2025,11,4,0,0,0, TimeScalesFactory.getUTC());
//        AbsoluteDate t1 = t0.shiftedBy(0.0); // 순간 촬영만
        AbsoluteDate t1 = new AbsoluteDate(2025,11,11,0,0,0, TimeScalesFactory.getUTC());

        FovParams fov = new FovParams();
        fov.setFocalLength_m(2.500);   // focal length [m]
        fov.setFocalLength_m(3.45e-6); // pixel pitch [m]
        fov.setWpx(11664);             // W px
        fov.setWpx(8750);              // H px

        double rollLimitDeg = 20.0;

        var temp = captureService.computeScheduleWithFootprints(
                line1, line2,
                36.350389, 127.386260, 0,
                t0,t1,
                1,
                fov,
                rollLimitDeg
        );

        var end = 1;
    }


//    @Test
//    void testFootprint(TLE tle, AbsoluteDate t0, AbsoluteDate t1)
//    {
//        orekitDataService.initializeOrekitData();
//
//        TLEPropagator prop = TLEPropagator.selectExtrapolator(tle);
//
//        // 1) 지구/프레임/전파기/자세
//        OneAxisEllipsoid earth = new OneAxisEllipsoid(
//                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
//                Constants.WGS84_EARTH_FLATTENING,
//                FramesFactory.getITRF(IERSConventions.IERS_2010, true));
//        Frame eme2000 = FramesFactory.getEME2000();
//
//        // 1) 지구/프레임/전파기/자세
//        double a   = Constants.WGS84_EARTH_EQUATORIAL_RADIUS + 500_000.0; // 500 km circular
//        double e   = 0.0;
//        double inc = FastMath.toRadians(97.0);
////        AbsoluteDate t0 = new AbsoluteDate(2025, 1, 1, 12, 0, 0.0, TimeScalesFactory.getUTC());
////        AbsoluteDate t1 = t0.shiftedBy(0.0); // 순간 촬영만
////        KeplerianOrbit orb = new KeplerianOrbit(a, e, inc, 0, 0, 0,
////                PositionAngleType.TRUE, eme2000, t0, Constants.EGM96_EARTH_MU);
////        KeplerianPropagator prop = new KeplerianPropagator(orb);
//
//        AttitudeProvider nadir = new NadirPointing(eme2000, earth);
//        prop.setAttitudeProvider(nadir);
//
//        // 2) 카메라 스펙 → 반시야각
//        double f = 2.500;       // m
//        double pitch = 3.45e-6; // m
//        int Wpx = 11664, Hpx = 8750;
//        double Wx = Wpx * pitch, Hy = Hpx * pitch;
//        double crossHalf = FastMath.atan((Wx/2)/f);
//        double alongHalf = FastMath.atan((Hy/2)/f);
//
//        FieldOfView fov = new DoubleDihedraFieldOfView(
//                Vector3D.PLUS_K,           // boresight
//                Vector3D.PLUS_J, alongHalf,// 세로(얼롱)
//                Vector3D.PLUS_I, crossHalf,// 가로(크로스)
//                0.0);
//
//        // 3) 시간 루프 설정 (순간 촬영이므로 t1=t0, 아래 루프는 1회 실행)
//        double dt = 0.5; // [s] (여기서는 의미 없음)
//        double angularStep = FastMath.toRadians(0.02); // ✅ 오타 수정
//
//        // 4) 스와스 합집합 누적 (구면 2D 상의 합집합) — 필요 시 사용
//        RegionFactory<Sphere2D, S2Point, Circle, SubCircle> factory = new RegionFactory<>();
//        Region<Sphere2D, S2Point, Circle, SubCircle> swath = null;
//        final double tol = 1.0e-10;
//
//        // (선택) 즉시 사용을 위해 footprint의 위·경도(deg) 리스트로 보관
//        List<List<double[]>> footprintDegPolygons = new ArrayList<>();
//
//        for (AbsoluteDate t = t0; t.compareTo(t1) <= 0; t = t.shiftedBy(dt)) {
//            SpacecraftState s = prop.propagate(t);
//
//            // 센서/바디→ECEF 변환(footprint 계산에 필요)
//            Transform inertToBody = s.getFrame().getTransformTo(earth.getBodyFrame(), t);
//            Transform fovToBody   = new Transform(t, s.toTransform().getInverse(), inertToBody);
//
//            // 순간 footprint(지상 루프들) — 각 꼭짓점이 위도/경도(rad)인 GeodeticPoint
//            List<List<GeodeticPoint>> loops = fov.getFootprint(fovToBody, earth, angularStep);
//            if (loops.isEmpty()) continue;
//
//            String geojson = loopsToGeoJSON(loops, true, false);
//
//            for (int li = 0; li < loops.size(); li++) {
//                List<GeodeticPoint> loop = loops.get(li);
//
//                // (A) 위경도(deg)로 출력/수집 — 이게 “지구에 투영되는 촬영 폴리곤”
//                System.out.println("Footprint loop #" + li);
//                List<double[]> polygonDeg = new ArrayList<>();
//                for (GeodeticPoint gp : loop) {
//                    double latDeg = FastMath.toDegrees(gp.getLatitude());
//                    double lonDeg = FastMath.toDegrees(gp.getLongitude());
//                    // (선택) 경도 [-180,180)로 정규화
//                    if (lonDeg >= 180.0) lonDeg -= 360.0;
//                    System.out.printf("   %.6f, %.6f%n", latDeg, lonDeg);
//                    polygonDeg.add(new double[] { latDeg, lonDeg });
//                }
//                footprintDegPolygons.add(polygonDeg);
//
//                // (B) 스와스 합집합 누적이 필요하면 구면다각형으로 변환
//                S2Point[] pts = new S2Point[loop.size()];
//                for (int i = 0; i < loop.size(); ++i) {
//                    // 지리→ECEF 변환 후 단위구로 정규화
//                    Vector3D rEcf = earth.transform(loop.get(i));
//                    Vector3D u    = rEcf.normalize();
//                    pts[i] = new S2Point(u);
//                }
//                Region<Sphere2D, S2Point, Circle, SubCircle> poly = new SphericalPolygonsSet(tol, pts);
//                swath = (swath == null) ? poly : factory.union(swath, poly);
//            }
//        }
//
//        // (선택) 합집합 면적/경계 — t1>t0로 누적할 때 사용
//        if (swath != null && !swath.isEmpty()) {
//            double steradians = swath.getSize(); // [sr]
//            double Re = Constants.WGS84_EARTH_EQUATORIAL_RADIUS; // [m]
//            double areaKm2 = steradians * (Re * Re) / 1.0e6;     // [km^2] 근사
//            double boundaryRad = swath.getBoundarySize();        // [rad]
//            System.out.printf("누적 스와스 면적(근사): %.2f km², 경계 길이: %.4f rad%n", areaKm2, boundaryRad);
//        }
//
//        // 여기서 footprintDegPolygons 를 WKT/GeoJSON 등으로 저장하거나, 지도에 표시하면 됩니다.
//    }

//    public void clacFootprint2LLA(SensorOptics sensorOptics, Propagator prop, AbsoluteDate from, Float time)
//    {
//        orekitDataService.initializeOrekitData();
//
//        // 1) 지구/프레임/전파기/자세
//        OneAxisEllipsoid earth = new OneAxisEllipsoid(
//                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
//                Constants.WGS84_EARTH_FLATTENING,
//                FramesFactory.getITRF(IERSConventions.IERS_2010, true));
//        Frame eme2000 = FramesFactory.getEME2000();
//
//        AbsoluteDate to = from.shiftedBy(time); // 10분 패스 예시
//
//        AttitudeProvider nadir = new NadirPointing(eme2000, earth);
//        prop.setAttitudeProvider(nadir);
//
//        FieldOfView fov = new DoubleDihedraFieldOfView(
//                Vector3D.PLUS_K,           // boresight
//                Vector3D.PLUS_J, sensorOptics.halfFovAlong(),// 세로(얼롱)
//                Vector3D.PLUS_I, sensorOptics.halfFovCross(),// 가로(크로스)
//                0.0);
//
//        // 3) 시간 루프 설정 (fps=2 Hz 예시 → Δt=0.5 s)
//        double dt = 0.1; // [s]
//        double angularStep = FastMath.toRadians(0.02); // ✅ 오타 수정
//
//        // 4) 스와스 합집합 누적 (구면 2D 상의 합집합) — 필요 시 사용
//        RegionFactory<Sphere2D, S2Point, Circle, SubCircle> factory = new RegionFactory<>();
//        Region<Sphere2D, S2Point, Circle, SubCircle> swath = null;
//        final double tol = 1.0e-10;
//
//        // (선택) 즉시 사용을 위해 footprint의 위·경도(deg) 리스트로 보관
//        List<List<double[]>> footprintDegPolygons = new ArrayList<>();
//
//        for (AbsoluteDate t = from; t.compareTo(to) <= 0; t = t.shiftedBy(dt)) {
//            SpacecraftState s = prop.propagate(t);
//
//            // 센서/바디→ECEF 변환(footprint 계산에 필요)
//            Transform inertToBody = s.getFrame().getTransformTo(earth.getBodyFrame(), t);
//            Transform fovToBody   = new Transform(t, s.toTransform().getInverse(), inertToBody);
//
//            // 순간 footprint(지상 루프들) — 각 꼭짓점이 위도/경도(rad)인 GeodeticPoint
//            List<List<GeodeticPoint>> loops = fov.getFootprint(fovToBody, earth, angularStep);
//            if (loops.isEmpty()) continue;
//
//
//            for (int li = 0; li < loops.size(); li++) {
//                List<GeodeticPoint> loop = loops.get(li);
//
//                // (A) 위경도(deg)로 출력/수집 — 이게 “지구에 투영되는 촬영 폴리곤”
//                System.out.println("Footprint loop #" + li);
//                List<double[]> polygonDeg = new ArrayList<>();
//                for (GeodeticPoint gp : loop) {
//                    double latDeg = FastMath.toDegrees(gp.getLatitude());
//                    double lonDeg = FastMath.toDegrees(gp.getLongitude());
//                    // (선택) 경도 [-180,180)로 정규화
//                    if (lonDeg >= 180.0) lonDeg -= 360.0;
//                    System.out.printf("   %.6f, %.6f%n", latDeg, lonDeg);
//                    polygonDeg.add(new double[] { latDeg, lonDeg });
//                }
//                footprintDegPolygons.add(polygonDeg);
//
//                // (B) 스와스 합집합 누적이 필요하면 구면다각형으로 변환
//                S2Point[] pts = new S2Point[loop.size()];
//                for (int i = 0; i < loop.size(); ++i) {
//                    // 지리→ECEF 변환 후 단위구로 정규화
//                    Vector3D rEcf = earth.transform(loop.get(i));
//                    Vector3D u    = rEcf.normalize();
//                    pts[i] = new S2Point(u);
//                }
//                Region<Sphere2D, S2Point, Circle, SubCircle> poly = new SphericalPolygonsSet(tol, pts);
//                swath = (swath == null) ? poly : factory.union(swath, poly);
//            }
//        }
//    }
//
//    /** 경도 차(라디안)의 ±π 래핑 보정 */
//    public record SensorOptics(
//            double f_m,         // 초점거리 [m]
//            double pitch_m,     // 픽셀피치 [m]
//            int width_px,       // 센서 가로 픽셀수
//            int height_px       // 센서 세로 픽셀수
//    ) {
//        public SensorOptics {
//            if (f_m <= 0 || pitch_m <= 0) throw new IllegalArgumentException("f, pitch must be > 0");
//            if (width_px <= 0 || height_px <= 0) throw new IllegalArgumentException("px must be > 0");
//        }
//
//        // ---- 센서 치수 [m]
//        public double sensorWidth()  { return width_px  * pitch_m; }
//        public double sensorHeight() { return height_px * pitch_m; }
//
//        // ---- Half-FOV [rad]
//        public double halfFovCross() { return Math.atan(sensorWidth()  / (2.0 * f_m)); }
//        public double halfFovAlong() { return Math.atan(sensorHeight() / (2.0 * f_m)); }
//
//        // ---- FOV [rad]
//        public double fovCross()     { return 2.0 * halfFovCross(); }
//        public double fovAlong()     { return 2.0 * halfFovAlong(); }
//        public double fovDiagonal()  {
//            double diag = Math.hypot(sensorWidth(), sensorHeight());
//            return 2.0 * Math.atan(diag / (2.0 * f_m));
//        }
//
//        // ---- IFOV(픽셀당 각도) [rad/px]
//        public double ifovCross()    { return fovCross()  / width_px; }
//        public double ifovAlong()    { return fovAlong()  / height_px; }
//
//        // ---- 고도에서의 지상폭/해상도(나딜 근사)
//        public double groundSwathCross(double altitude_m) { return 2.0 * altitude_m * Math.tan(halfFovCross()); }
//        public double groundSwathAlong(double altitude_m) { return 2.0 * altitude_m * Math.tan(halfFovAlong()); }
//        public double gsdNadirApprox(double altitude_m)   { return altitude_m * ifovCross(); } // 소각근사
//
//        // ---- 팩토리
//        public static SensorOptics ofMeters(double f_m, double pitch_m, int width_px, int height_px) {
//            return new SensorOptics(f_m, pitch_m, width_px, height_px);
//        }
//        public static SensorOptics ofMM(double f_mm, double pitch_um, int width_px, int height_px) {
//            return new SensorOptics(f_mm * 1e-3, pitch_um * 1e-6, width_px, height_px);
//        }
//    }
//
//    void tempSwath()
//    {
//        orekitDataService.initializeOrekitData();
//
//        // 1) 지구/프레임/전파기/자세
//        OneAxisEllipsoid earth = new OneAxisEllipsoid(
//                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
//                Constants.WGS84_EARTH_FLATTENING,
//                FramesFactory.getITRF(IERSConventions.IERS_2010, true));
//        Frame eme2000 = FramesFactory.getEME2000();
//
//        // 1) 지구/프레임/전파기/자세
//        double a   = Constants.WGS84_EARTH_EQUATORIAL_RADIUS + 500_000.0; // 500 km circular
//        double e   = 0.0;
//        double inc = FastMath.toRadians(97.0);
//        AbsoluteDate t0 = new AbsoluteDate(2025, 1, 1, 12, 0, 0.0, TimeScalesFactory.getUTC());
//        AbsoluteDate t1 = t0.shiftedBy(0.0); // 순간 촬영만
//        KeplerianOrbit orb = new KeplerianOrbit(a, e, inc, 0, 0, 0,
//                PositionAngleType.TRUE, eme2000, t0, Constants.EGM96_EARTH_MU);
//        KeplerianPropagator prop = new KeplerianPropagator(orb);
//
//        AttitudeProvider nadir = new NadirPointing(eme2000, earth);
//        prop.setAttitudeProvider(nadir);
//
//        // 2) 카메라 스펙 → 반시야각
//        double f = 2.500;       // m
//        double pitch = 3.45e-6; // m
//        int Wpx = 11664, Hpx = 8750;
//        double Wx = Wpx * pitch, Hy = Hpx * pitch;
//        double crossHalf = FastMath.atan((Wx/2)/f);
//        double alongHalf = FastMath.atan((Hy/2)/f);
//
//        FieldOfView fov = new DoubleDihedraFieldOfView(
//                Vector3D.PLUS_K,           // boresight
//                Vector3D.PLUS_J, alongHalf,// 세로(얼롱)
//                Vector3D.PLUS_I, crossHalf,// 가로(크로스)
//                0.0);
//
//        // 3) 시간 루프 설정 (순간 촬영이므로 t1=t0, 아래 루프는 1회 실행)
//        double dt = 0.5; // [s] (여기서는 의미 없음)
//        double angularStep = FastMath.toRadians(0.02); // ✅ 오타 수정
//
//        // 4) 스와스 합집합 누적 (구면 2D 상의 합집합) — 필요 시 사용
//        RegionFactory<Sphere2D, S2Point, Circle, SubCircle> factory = new RegionFactory<>();
//        Region<Sphere2D, S2Point, Circle, SubCircle> swath = null;
//        final double tol = 1.0e-10;
//
//        // (선택) 즉시 사용을 위해 footprint의 위·경도(deg) 리스트로 보관
//        List<List<double[]>> footprintDegPolygons = new ArrayList<>();
//
//        for (AbsoluteDate t = t0; t.compareTo(t1) <= 0; t = t.shiftedBy(dt)) {
//            SpacecraftState s = prop.propagate(t);
//
//            // 센서/바디→ECEF 변환(footprint 계산에 필요)
//            Transform inertToBody = s.getFrame().getTransformTo(earth.getBodyFrame(), t);
//            Transform fovToBody   = new Transform(t, s.toTransform().getInverse(), inertToBody);
//
//            // 순간 footprint(지상 루프들) — 각 꼭짓점이 위도/경도(rad)인 GeodeticPoint
//            List<List<GeodeticPoint>> loops = fov.getFootprint(fovToBody, earth, angularStep);
//            if (loops.isEmpty()) continue;
//
//
//            for (int li = 0; li < loops.size(); li++) {
//                List<GeodeticPoint> loop = loops.get(li);
//
//                // (A) 위경도(deg)로 출력/수집 — 이게 “지구에 투영되는 촬영 폴리곤”
//                System.out.println("Footprint loop #" + li);
//                List<double[]> polygonDeg = new ArrayList<>();
//                for (GeodeticPoint gp : loop) {
//                    double latDeg = FastMath.toDegrees(gp.getLatitude());
//                    double lonDeg = FastMath.toDegrees(gp.getLongitude());
//                    // (선택) 경도 [-180,180)로 정규화
//                    if (lonDeg >= 180.0) lonDeg -= 360.0;
//                    System.out.printf("   %.6f, %.6f%n", latDeg, lonDeg);
//                    polygonDeg.add(new double[] { latDeg, lonDeg });
//                }
//                footprintDegPolygons.add(polygonDeg);
//
//                // (B) 스와스 합집합 누적이 필요하면 구면다각형으로 변환
//                S2Point[] pts = new S2Point[loop.size()];
//                for (int i = 0; i < loop.size(); ++i) {
//                    // 지리→ECEF 변환 후 단위구로 정규화
//                    Vector3D rEcf = earth.transform(loop.get(i));
//                    Vector3D u    = rEcf.normalize();
//                    pts[i] = new S2Point(u);
//                }
//                Region<Sphere2D, S2Point, Circle, SubCircle> poly = new SphericalPolygonsSet(tol, pts);
//                swath = (swath == null) ? poly : factory.union(swath, poly);
//            }
//        }
//
//        // (선택) 합집합 면적/경계 — t1>t0로 누적할 때 사용
//        if (swath != null && !swath.isEmpty()) {
//            double steradians = swath.getSize(); // [sr]
//            double Re = Constants.WGS84_EARTH_EQUATORIAL_RADIUS; // [m]
//            double areaKm2 = steradians * (Re * Re) / 1.0e6;     // [km^2] 근사
//            double boundaryRad = swath.getBoundarySize();        // [rad]
//            System.out.printf("누적 스와스 면적(근사): %.2f km², 경계 길이: %.4f rad%n", areaKm2, boundaryRad);
//        }
//    }
//
//    // 경도 [-180,180) 정규화 (원하면 사용)
//    static double normLonDeg(double lonDeg) {
//        double x = lonDeg % 360.0;
//        if (x >= 180.0) x -= 360.0;
//        if (x < -180.0) x += 360.0;
//        return x;
//    }
//
//    /** loops(= List<List<GeodeticPoint>>) 를 GeoJSON FeatureCollection(Polygon들) 문자열로 변환
//     *  - 좌표 순서: [lon, lat]  (필요시 includeAlt=true 로 [lon, lat, alt])
//     *  - 각 루프는 닫힌 링(첫/끝 동일 점)으로 만들어줌
//     */
//    static String loopsToGeoJSON(List<List<GeodeticPoint>> loops,
//                                 boolean normalizeLonTo180,
//                                 boolean includeAltMeters) {
//        StringBuilder sb = new StringBuilder(4096);
//        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
//        boolean firstFeature = true;
//
//        for (int li = 0; li < loops.size(); li++) {
//            List<GeodeticPoint> loop = loops.get(li);
//            if (loop.size() < 3) continue;
//
//            // 좌표 링 구성 (첫 점을 마지막에 한 번 더 추가해 닫기)
//            StringBuilder ring = new StringBuilder(2048);
//            ring.append("[");
//            for (int i = 0; i < loop.size(); i++) {
//                GeodeticPoint gp = loop.get(i);
//                double latDeg = FastMath.toDegrees(gp.getLatitude());
//                double lonDeg = FastMath.toDegrees(gp.getLongitude());
//                if (normalizeLonTo180) lonDeg = normLonDeg(lonDeg);
//
//                if (includeAltMeters) {
//                    ring.append("[").append(lonDeg).append(",").append(latDeg).append(",").append(gp.getAltitude()).append("]");
//                } else {
//                    ring.append("[").append(lonDeg).append(",").append(latDeg).append("]");
//                }
//                if (i < loop.size() - 1) ring.append(",");
//            }
//            // 닫기용 첫 점 재추가
//            GeodeticPoint g0 = loop.get(0);
//            double lat0 = FastMath.toDegrees(g0.getLatitude());
//            double lon0 = FastMath.toDegrees(g0.getLongitude());
//            if (normalizeLonTo180) lon0 = normLonDeg(lon0);
//            ring.append(",");
//            if (includeAltMeters) {
//                ring.append("[").append(lon0).append(",").append(lat0).append(",").append(g0.getAltitude()).append("]");
//            } else {
//                ring.append("[").append(lon0).append(",").append(lat0).append("]");
//            }
//            ring.append("]");
//
//            // Feature 하나 추가
//            if (!firstFeature) sb.append(",");
//            firstFeature = false;
//            sb.append("{\"type\":\"Feature\",\"properties\":{\"loop\":").append(li).append("},")
//                    .append("\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[")
//                    .append(ring).append("]}}");
//        }
//
//        sb.append("]}");
//        return sb.toString();
//    }
//
////-----------------------------------------------------------------------------------------------------
//    public record GeoLL(double latDeg, double lonDeg) {}
//
//    public record ImagingOpportunity(
//            AbsoluteDate startUtc,
//            AbsoluteDate endUtc,
//            AbsoluteDate midUtc,
//            double usedRollDegAtMid,
//            List<GeoLL> footprintAtMid
//    ) {}
//
//
//
//    // ====== 윈도우 중앙시각 폴리곤/롤 산출 ======
//    private ImagingOpportunity makeOpportunity(
//            OneAxisEllipsoid earth, Frame itrs, TLEPropagator prop, Frame teme,
//            Vector3D targetEcef, FovParams fov, double rollLimitDeg,
//            AbsoluteDate start, AbsoluteDate end) {
//
//        AbsoluteDate mid = start.shiftedBy(0.5 * end.durationFrom(start));
//
//        PVCoordinates pvTeme = prop.getPVCoordinates(mid, teme);
//        Transform toItrf = teme.getTransformTo(itrs, mid);
//        Vector3D r = toItrf.transformPosition(pvTeme.getPosition());
//        Vector3D v = toItrf.transformVector(pvTeme.getVelocity());
//
//        var cam = buildCameraAxes(r, v);
//        Vector3D u = targetEcef.subtract(r).normalize();
//        double ux = u.dotProduct(cam.x);
//        double uz = u.dotProduct(cam.z);
//        double rollReq = Math.atan2(ux, uz);
//        double rollUse = clamp(rollReq, Math.toRadians(-rollLimitDeg), Math.toRadians(rollLimitDeg));
//        double usedRollDeg = Math.toDegrees(rollUse);
//
//        List<GeoLL> poly = footprintAtInstant(earth, itrs, mid, r, v, fov, usedRollDeg, 0.0);
//        return new ImagingOpportunity(start, end, mid, usedRollDeg, poly);
//    }
//
//    // ====== 폴리곤(4코너) ======
//    private List<GeoLL> footprintAtInstant(
//            OneAxisEllipsoid earth, Frame itrs, AbsoluteDate date,
//            Vector3D r, Vector3D v,
//            FovParams fov, double rollDeg, double pitchDeg) {
//
//        CamAxes cam = buildCameraAxes(r, v);
//
//        // roll → pitch 순서
//        if (Math.abs(rollDeg) > 1e-12) cam = rotateAround(cam, cam.y, Math.toRadians(rollDeg));
//        if (Math.abs(pitchDeg) > 1e-12) cam = rotateAround(cam, cam.x, Math.toRadians(pitchDeg));
//        cam = orthonormalize(cam);
//
//        double Wx = fov.getWpx() * fov.getPixelPitch_m();
//        double Hy = fov.getHpx() * fov.getPixelPitch_m();
//        double crossHalf = Math.atan((Wx / 2.0) / fov.getFocalLength_m());
//        double alongHalf = Math.atan((Hy / 2.0) / fov.getFocalLength_m());
//        double tx = Math.tan(crossHalf);
//        double ty = Math.tan(alongHalf);
//
//        int[][] corners = { {+1,+1}, {+1,-1}, {-1,-1}, {-1,+1} };
//        List<GeoLL> out = new ArrayList<>(4);
//
//        for (int[] c : corners) {
//            int sx = c[0], sy = c[1];
//            Vector3D dir = cam.z.add(cam.x.scalarMultiply(sx * tx)).add(cam.y.scalarMultiply(sy * ty)).normalize();
//
//            // 위성 → dir 방향 직선과 타원체 교차
//            GeodeticPoint gp = intersect(earth, itrs, date, r, dir);
//            if (gp == null) return List.of(); // 지평선 아래 등 교차 실패 → 빈 폴리곤
//
//            double lat = Math.toDegrees(gp.getLatitude());
//            double lon = wrapLonDeg(Math.toDegrees(gp.getLongitude()));
//            out.add(new GeoLL(lat, lon));
//        }
//        return out;
//    }
//
//    // ====== LOS 체크 ======
//    private boolean hasLineOfSight(OneAxisEllipsoid earth, Frame itrs, AbsoluteDate date,
//                                   Vector3D rSat, Vector3D pTgt) {
//        Vector3D dir = pTgt.subtract(rSat);
//        double distT = dir.getNorm();
//        dir = dir.normalize();
//
//        GeodeticPoint firstHit = intersect(earth, itrs, date, rSat, dir);
//        if (firstHit == null) return true; // 교점 없음 → 가시라고 가정(실제론 드뭄)
//
//        Vector3D hitECEF = earth.transform(firstHit);
//        double distHit = hitECEF.subtract(rSat).getNorm();
//        return distHit >= distT - 1.0; // 지표를 먼저 치면 가림
//    }
//
//    // 타원체 교차 (Orekit 유틸)
//    private GeodeticPoint intersect(OneAxisEllipsoid earth, Frame itrs, AbsoluteDate date,
//                                    Vector3D p0, Vector3D dir) {
//        Line line = new Line(p0, p0.add(dir), 1.0e-10);
//        return earth.getIntersectionPoint(line, p0, itrs, date);
//    }
//
//    // ====== 카메라 축 & 보조 수학 ======
//    private record CamAxes(Vector3D x, Vector3D y, Vector3D z) {}
//
//    private CamAxes buildCameraAxes(Vector3D r, Vector3D v) {
//        Vector3D z = r.negate().normalize();                          // 나디르
//        Vector3D vHoriz = v.subtract(z.scalarMultiply(v.dotProduct(z)));
//        if (vHoriz.getNormSq() < 1e-18) vHoriz = new Vector3D(-r.getY(), r.getX(), 0.0);
//        Vector3D y = vHoriz.normalize();                              // 얼롱
//        Vector3D x = Vector3D.crossProduct(y, z).normalize();         // 크로스
//        // 정규직교 보정
//        z = z.normalize();
//        x = x.subtract(z.scalarMultiply(x.dotProduct(z))).normalize();
//        y = Vector3D.crossProduct(z, x).normalize();
//        return new CamAxes(x, y, z);
//    }
//
//    private CamAxes rotateAround(CamAxes a, Vector3D axis, double ang) {
//        return new CamAxes(
//                rodrigues(a.x, axis, ang),
//                rodrigues(a.y, axis, ang),
//                rodrigues(a.z, axis, ang)
//        );
//    }
//
//    private CamAxes orthonormalize(CamAxes a) {
//        Vector3D z = a.z.normalize();
//        Vector3D x = a.x.subtract(z.scalarMultiply(a.x.dotProduct(z))).normalize();
//        Vector3D y = Vector3D.crossProduct(z, x).normalize();
//        return new CamAxes(x, y, z);
//    }
//
//    private Vector3D rodrigues(Vector3D v, Vector3D axis, double ang) {
//        Vector3D k = axis.normalize();
//        double c = Math.cos(ang), s = Math.sin(ang), one_c = 1.0 - c;
//        double kv = k.dotProduct(v);
//        Vector3D cross = Vector3D.crossProduct(k, v);
//        return v.scalarMultiply(c)
//                .add(cross.scalarMultiply(s))
//                .add(k.scalarMultiply(kv * one_c));
//    }
//
//    private static double clamp(double x, double lo, double hi) {
//        return (x < lo) ? lo : (x > hi) ? hi : x;
//    }
//
//    private static double wrapLonDeg(double lon) {
//        // [-180, 180)
//        while (lon >= 180.0) lon -= 360.0;
//        while (lon <  -180.0) lon += 360.0;
//        return lon;
//    }

}


