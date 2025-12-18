package org.sat_tool.domain.common.service;

import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.orekit.data.ZipJarCrawler;
import org.orekit.frames.FramesFactory;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.IERSConventions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component("orekitInitializer")
@RequiredArgsConstructor
public class OrekitInitService {

    // Orekit Data initialize
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    @Value("${orekit.data-path}")
    private String orekitDataPath;

    @PostConstruct
    public void init() throws IOException {
        // 여러 빈/테스트에서 중복 초기화 방지
        if (!INITIALIZED.compareAndSet(false, true)) return;

        File f = new File(orekitDataPath);
        if (!f.exists()) throw new IOException("Orekit data not found: " + f.getAbsolutePath());

        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.clearProviders(); // 중복 add 방지(원하면 제거 가능)

        if (f.isDirectory()) {
            manager.addProvider(new DirectoryCrawler(f));
        } else {
            // orekit-data.zip 같은 경우
            manager.addProvider(new ZipJarCrawler(f));
        }

        // 필수 팩토리들을 한 번 호출해 캐시/로드를 유도 (선택이지만 실전에서 안정적)
        TimeScalesFactory.getUTC();
        FramesFactory.getITRF(IERSConventions.IERS_2010, true);
    }
}