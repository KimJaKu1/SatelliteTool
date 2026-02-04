package org.sat_tool.domain.event.eclipse.model;

import lombok.Data;
import org.orekit.time.AbsoluteDate;
import org.sat_tool.domain.event.eclipse._enum.FieldState;

@Data
public class FieldValue {
    private final AbsoluteDate time;     // may be null
    private final FieldState state;

    public FieldValue(AbsoluteDate time, FieldState state) {
        this.time = time;
        this.state = state;
    }
    public static FieldValue time(AbsoluteDate t) { return new FieldValue(t, FieldState.TIME); }
    public static FieldValue notInPass()          { return new FieldValue(null, FieldState.NOT_IN_PASS); }
    public static FieldValue noUmbra()            { return new FieldValue(null, FieldState.NO_UMBRA); }
}