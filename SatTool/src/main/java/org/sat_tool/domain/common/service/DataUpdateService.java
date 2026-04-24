package org.sat_tool.domain.common.service;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        Objects.requireNonNull(timeout, "timeout");

        if (!enabled) {
            throw new IllegalStateException("orekit update is disabled");
        }

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
        ExecutorService outputReader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "orekit-update-output");
            t.setDaemon(true);
            return t;
        });
        Future<String> outputFuture = outputReader.submit(() -> {
            try (InputStream is = p.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        });

        try {
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);

                String partialOutput = consumeOutputQuietly(outputFuture);
                throw new IllegalStateException("update.sh timeout: " + timeout +
                        (partialOutput.isBlank() ? "" : "\n" + partialOutput));
            }

            String output = readOutput(outputFuture);
            int exit = p.exitValue();
            if (exit != 0) {
                throw new IllegalStateException("update.sh failed (exit=" + exit + ")\n" + output);
            }

            return output;
        } finally {
            outputReader.shutdownNow();
        }
    }

    private String readOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException("failed to read update.sh output", cause);
        }
    }

    private String consumeOutputQuietly(Future<String> outputFuture) {
        try {
            return outputFuture.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            return "";
        }
    }
}
