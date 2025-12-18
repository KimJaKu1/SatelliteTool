package org.sat_tool.domain.propagation.service;

import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.propagation.model.EphemerisVerctor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

@DependsOn("orekitInitializer")
@Service
public class PropagateService {

    @Autowired
    private TimeConverter timeConverter;

    public List<EphemerisVerctor> computeOrbitDataWithFrame(Propagator propagator,
            AbsoluteDate startDate, AbsoluteDate endDate,
            double intervalSeconds,
            Frame targetFrame) {

        List<EphemerisVerctor> result = new ArrayList<>();

        for (AbsoluteDate date = startDate; date.compareTo(endDate) <= 0; date = date.shiftedBy(intervalSeconds)) {
            // 원하는 프레임(targetFrame)에서의 PV
            PVCoordinates pv = propagator.getPVCoordinates(date, targetFrame);

            EphemerisVerctor item = new EphemerisVerctor();
            item.setTime(timeConverter.toCompactUtcString(date));
            item.setX(pv.getPosition().getX() / 1000.0);
            item.setY(pv.getPosition().getY() / 1000.0);
            item.setZ(pv.getPosition().getZ() / 1000.0);
            item.setVx(pv.getVelocity().getX() / 1000.0);
            item.setVy(pv.getVelocity().getY() / 1000.0);
            item.setVz(pv.getVelocity().getZ() / 1000.0);

            result.add(item);
        }

        return result;
    }

    public List<EphemerisVerctor> computeEphemerisECI(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {
        // ECI(GCRF)
        Frame eciFrame = FramesFactory.getGCRF();

        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, eciFrame);
    }

    public List<EphemerisVerctor> computeEphemerisECEF(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {

        // ECEF(ITRF)
        Frame ecefFrame = FramesFactory.getITRF(IERSConventions.IERS_2010, true);

        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, ecefFrame);
    }

    public List<EphemerisVerctor> computeOrbitData2TOD(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {

        // TOD(True-of-Date, 관성 프레임)
        Frame todFrame = FramesFactory.getTOD(IERSConventions.IERS_2010, true);
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, todFrame);
    }

    public List<EphemerisVerctor> computeOrbitData2TEME(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {
        Frame temeFrame = FramesFactory.getTEME();
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, temeFrame);
    }

    private static String toPlain(double v) {
        // 과학적 표기(E-5 등) 없이, 로케일 영향 없이 '.'로 출력
        return BigDecimal.valueOf(v).toPlainString();
    }

    public void writeFile(List<EphemerisVerctor> ephemerisVector, Path parnet) {
        try (BufferedWriter w = Files.newBufferedWriter(
                parnet, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (var t : ephemerisVector) {
                w.write(t.getTime());
                w.write('\t');
                w.write(toPlain(t.getX()));
                w.write('\t');
                w.write(toPlain(t.getY()));
                w.write('\t');
                w.write(toPlain(t.getZ()));
                w.write('\t');
                w.write(toPlain(t.getVx()));
                w.write('\t');
                w.write(toPlain(t.getVy()));
                w.write('\t');
                w.write(toPlain(t.getVz()));
                w.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}