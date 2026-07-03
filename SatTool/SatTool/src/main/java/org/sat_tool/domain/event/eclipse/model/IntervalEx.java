package org.sat_tool.domain.event.eclipse.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;
import org.springframework.context.annotation.DependsOn;

@Data
@DependsOn("orekitInitializer")
public class IntervalEx {
    final AbsoluteDate start;
    final AbsoluteDate end;      // end==rangeEnd일 수 있음(강제 종료)
    final boolean openStart;     // ENTRY 미관측(구간 시작보다 이전부터 이미 inShadow)
    final boolean openEnd;       // EXIT 미관측(구간 끝 이후까지 inShadow 지속)
    public IntervalEx(AbsoluteDate start, AbsoluteDate end, boolean openStart, boolean openEnd) {
        this.start = start; this.end = end; this.openStart = openStart; this.openEnd = openEnd;
    }
}