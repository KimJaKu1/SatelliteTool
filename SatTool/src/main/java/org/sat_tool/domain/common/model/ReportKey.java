package org.sat_tool.domain.common.model;

/**
 * 리포트 집계 키 (위성명|지상국명|마스크각) 공용 모델.
 * 워커의 키 생성과 라이터의 키 파싱이 동일한 규약을 공유하도록 한다.
 */
public record ReportKey(String sat, String station, int mask) {

    public static final String KEY_SEP = "|";

    /** 키 문자열 생성: sat|station|mask */
    public String format() {
        return sat + KEY_SEP + station + KEY_SEP + mask;
    }

    /** 키 문자열 파싱 (형식이 올바르지 않으면 null) */
    public static ReportKey parse(String key) {
        if (key == null) {
            return null;
        }

        int first = key.indexOf(KEY_SEP);
        int last = key.lastIndexOf(KEY_SEP);
        if (first < 0 || last <= first) {
            return null;
        }

        String satelliteName = key.substring(0, first);
        String stationName = key.substring(first + KEY_SEP.length(), last);
        String maskStr = key.substring(last + KEY_SEP.length());

        try {
            return new ReportKey(satelliteName, stationName, Integer.parseInt(maskStr));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
