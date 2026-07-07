package org.sat_tool.domain.propagation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.Propagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class EphemerisService {

    public List<EphemerisVector> computeOrbitDataWithFrame(Propagator propagator,
                                                           AbsoluteDate startDate,
                                                           AbsoluteDate endDate,
                                                           double intervalSeconds,
                                                           Frame targetFrame) {
        Objects.requireNonNull(propagator, "propagator");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(targetFrame, "targetFrame");
        if (intervalSeconds <= 0.0) {
            throw new IllegalArgumentException("intervalSeconds must be positive");
        }
        if (startDate.compareTo(endDate) > 0) {
            throw new IllegalArgumentException("startDate must be before or equal to endDate");
        }

        List<EphemerisVector> result = new ArrayList<>();
        // shiftedBy 반복 누적 대신 시작 시각 기준 오프셋으로 부동소수 오차 누적 방지
        for (long i = 0; ; i++) {
            AbsoluteDate date = startDate.shiftedBy(i * intervalSeconds);
            if (date.compareTo(endDate) > 0) {
                break;
            }
            PVCoordinates pv = propagator.getPVCoordinates(date, targetFrame);

            EphemerisVector item = new EphemerisVector();
            item.setTime(TimeConverter.absoluteDateToLocalDateTimeUtc(date));
            item.setPos(pv.getPosition());
            item.setVel(pv.getVelocity());
            result.add(item);
        }

        return result;
    }

    public List<EphemerisVector> computeEphemerisECI(Propagator propagator,
                                                     AbsoluteDate startDate,
                                                     AbsoluteDate endDate,
                                                     double intervalSeconds) {
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, FramesFactory.getGCRF());
    }

    public List<EphemerisVector> computeEphemerisECEF(Propagator propagator,
                                                      AbsoluteDate startDate,
                                                      AbsoluteDate endDate,
                                                      double intervalSeconds) {
        Frame ecefFrame = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, ecefFrame);
    }
}
