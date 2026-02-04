package org.sat_tool.domain.event.eclipse.model;

import lombok.Data;

@Data
public class EclipseReportRow {
    private final long orbitNumber;
    private final FieldValue penEntry;
    private final FieldValue umbEntry;
    private final FieldValue umbExit;
    private final FieldValue penExit;

    public EclipseReportRow(long orbitNumber,
                            FieldValue penEntry,
                            FieldValue umbEntry,
                            FieldValue umbExit,
                            FieldValue penExit) {
        this.orbitNumber = orbitNumber;
        this.penEntry = penEntry;
        this.umbEntry = umbEntry;
        this.umbExit = umbExit;
        this.penExit = penExit;
    }
}
