package org.sat_tool.domain.propagation.service;

import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.Propagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
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

    public List<EphemerisVector> computeOrbitDataWithFrame(Propagator propagator,
                                                           AbsoluteDate startDate, AbsoluteDate endDate,
                                                           double intervalSeconds,
                                                           Frame targetFrame) {

        List<EphemerisVector> result = new ArrayList<>();

        for (AbsoluteDate date = startDate; date.compareTo(endDate) <= 0; date = date.shiftedBy(intervalSeconds)) {
            // 원하는 프레임(targetFrame)에서의 PV
            PVCoordinates pv = propagator.getPVCoordinates(date, targetFrame);

            EphemerisVector item = new EphemerisVector();
            item.setTime(timeConverter.absoluteDateToLocalDateTimeUtc(date));
            item.setPos(pv.getPosition());
            item.setVel(pv.getVelocity());

            result.add(item);
        }

        return result;
    }

    public List<EphemerisVector> computeEphemerisECI(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {
        // ECI(GCRF)
        Frame eciFrame = FramesFactory.getGCRF();

        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, eciFrame);
    }

    public List<EphemerisVector> computeEphemerisECEF(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {

        // ECEF(ITRF)
        Frame ecefFrame = FramesFactory.getITRF(IERSConventions.IERS_2010, true);

        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, ecefFrame);
    }

    public List<EphemerisVector> computeOrbitData2TOD(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {

        // TOD(True-of-Date, 관성 프레임)
        Frame todFrame = FramesFactory.getTOD(IERSConventions.IERS_2010, true);
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, todFrame);
    }

    public List<EphemerisVector> computeOrbitData2TEME(Propagator propagator, AbsoluteDate startDate,
            AbsoluteDate endDate, double intervalSeconds) {
        Frame temeFrame = FramesFactory.getTEME();
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, temeFrame);
    }

    private static String toPlain(double v) {
        // 과학적 표기(E-5 등) 없이, 로케일 영향 없이 '.'로 출력
        return BigDecimal.valueOf(v).toPlainString();
    }

    public void writeFile(List<EphemerisVector> ephemerisVector, Path parnet) {
        try (BufferedWriter w = Files.newBufferedWriter(
                parnet, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (var t : ephemerisVector) {
                w.write(timeConverter.localDateTimeToString(t.getTime()));
                w.write('\t');
                w.write(toPlain(t.getPos().getX()));
                w.write('\t');
                w.write(toPlain(t.getPos().getY()));
                w.write('\t');
                w.write(toPlain(t.getPos().getZ()));
                w.write('\t');
                w.write(toPlain(t.getVel().getX()));
                w.write('\t');
                w.write(toPlain(t.getVel().getY()));
                w.write('\t');
                w.write(toPlain(t.getVel().getZ()));
                w.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}