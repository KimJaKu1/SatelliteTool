package org.sat_tool.domain.common.helper;

import org.orekit.time.AbsoluteDate;

/**
 * 궤도 주기 기반 궤도 번호(orbit number) 계산.
 * 기준 시각의 궤도 번호에서 경과 시간을 주기로 나눈 몫만큼 증가시킨다.
 * (승교점 통과 기준의 엄밀한 rev 카운트가 아닌 주기 근사 — Eclipse/ContactSchedule 공통 규약)
 */
public final class OrbitNumbers {

    /** 주기를 알 수 없을 때 사용하는 대략적 LEO 주기(초) */
    public static final double DEFAULT_LEO_PERIOD_SECONDS = 5400.0;

    private OrbitNumbers() {
    }

    /**
     * @param baseOrbit baseDate 시점의 궤도 번호
     * @param baseDate  기준 시각
     * @param t         궤도 번호를 구할 시각 (baseDate 이후)
     * @param periodSec 궤도 주기(초), 0 이하이면 DEFAULT_LEO_PERIOD_SECONDS 사용
     */
    public static long at(long baseOrbit, AbsoluteDate baseDate, AbsoluteDate t, double periodSec) {
        double period = (periodSec > 0) ? periodSec : DEFAULT_LEO_PERIOD_SECONDS;
        double dt = t.durationFrom(baseDate);
        return baseOrbit + (long) Math.floor(dt / period);
    }
}
