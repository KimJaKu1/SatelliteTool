package org.sat_tool.domain.common.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.orekit.data.ZipJarCrawler;
import org.orekit.frames.FramesFactory;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.IERSConventions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

@Component("orekitInitializer")
@RequiredArgsConstructor
public class OrekitInitService {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    @Value("${orekit.data-path}")
    private String orekitDataPath;

    @PostConstruct
    public void init() throws IOException {
        if (!INITIALIZED.compareAndSet(false, true)) return;

        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.clearProviders();

        if (!registerConfiguredProvider(manager) && !registerClasspathProvider(manager)) {
            throw new IOException("Orekit data not found. Checked filesystem path '" + orekitDataPath +
                    "' and classpath resources 'orekit-data'/'orekit-data.zip'.");
        }

        TimeScalesFactory.getUTC();
        FramesFactory.getITRF(IERSConventions.IERS_2010, true);
    }

    private boolean registerConfiguredProvider(DataProvidersManager manager) {
        if (orekitDataPath == null || orekitDataPath.isBlank()) {
            return false;
        }

        File source = new File(orekitDataPath);
        if (!source.exists()) {
            return false;
        }

        addProvider(manager, source);
        return true;
    }

    private boolean registerClasspathProvider(DataProvidersManager manager) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        URL zipUrl = classLoader.getResource("orekit-data.zip");
        if (zipUrl != null) {
            manager.addProvider(new ZipJarCrawler(zipUrl));
            return true;
        }

        URL dirUrl = classLoader.getResource("orekit-data");
        if (dirUrl != null && "file".equalsIgnoreCase(dirUrl.getProtocol())) {
            try {
                addProvider(manager, new File(dirUrl.toURI()));
                return true;
            } catch (URISyntaxException e) {
                throw new IOException("Invalid orekit-data classpath URI: " + dirUrl, e);
            }
        }

        return false;
    }

    private void addProvider(DataProvidersManager manager, File source) {
        if (source.isDirectory()) {
            manager.addProvider(new DirectoryCrawler(source));
        } else {
            manager.addProvider(new ZipJarCrawler(source));
        }
    }
}
