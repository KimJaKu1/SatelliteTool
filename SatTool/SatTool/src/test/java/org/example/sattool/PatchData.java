package org.example.sattool;

import org.junit.jupiter.api.Test;
import org.sat_tool.SatToolApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.sat_tool.domain.common.service.DataUpdateService;

import java.time.Duration;

@SpringBootTest(classes = SatToolApplication.class)
public class PatchData {

    @Autowired private DataUpdateService dataUpdateService;

    @Test
    void runOrekitUpdate() throws Exception {
        String out = dataUpdateService.runUpdateSh(Duration.ofMinutes(2));
        System.out.println(out);
    }

}
