package org.sat_tool.domain.common.service;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DataUpdateService {

    @Value("${orekit.data-path}")
    private String orekitDataPath;

    @Value("${orekit-update.bash:/bin/bash}")
    private String bashPath;

    @Value("${orekit-update.enabled:true}")
    private boolean enabled;

    public String runUpdateSh(Duration timeout) throws Exception {

        Path dataDir = Paths.get(orekitDataPath).toAbsolutePath().normalize();
        Path script = dataDir.resolve("update.sh");

        if (!Files.isDirectory(dataDir)) {
            throw new FileNotFoundException("orekit-data dir not found: " + dataDir);
        }
        if (!Files.exists(script)) {
            throw new FileNotFoundException("update.sh not found: " + script);
        }
        if (!Files.exists(Paths.get(bashPath))) {
            throw new FileNotFoundException("bash.exe not found: " + bashPath);
        }

        // ✅ 핵심: bash로 실행 (Windows에서 .sh 직접 실행 금지)
        // Git Bash는 -lc 로 커맨드를 실행하는 게 가장 안정적입니다.
        ProcessBuilder pb = new ProcessBuilder(bashPath, "-lc", "./update.sh");
        pb.directory(dataDir.toFile());
        pb.redirectErrorStream(true);

        // (선택) Git Bash 경로변환 꼬임 방지용
        pb.environment().put("MSYS2_ARG_CONV_EXCL", "*");

        Process p = pb.start();

        String output;
        try (InputStream is = p.getInputStream()) {
            output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("update.sh timeout: " + timeout);
        }

        int exit = p.exitValue();
        if (exit != 0) {
            throw new IllegalStateException("update.sh failed (exit=" + exit + ")\n" + output);
        }

        return output;
    }
}
