package org.sat_tool.domain.propagation.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.springframework.stereotype.Service;

@Service
public class TabularEphemerisWriter {

    public void writeTabularFile(List<EphemerisVector> ephemerisVector, Path path) {
        createParentDirectory(path);

        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (EphemerisVector vector : ephemerisVector) {
                writer.write(TimeConverter.localDateTimeToString(vector.getTime()));
                writer.write('\t');
                writer.write(toPlain(vector.getPos().getX()));
                writer.write('\t');
                writer.write(toPlain(vector.getPos().getY()));
                writer.write('\t');
                writer.write(toPlain(vector.getPos().getZ()));
                writer.write('\t');
                writer.write(toPlain(vector.getVel().getX()));
                writer.write('\t');
                writer.write(toPlain(vector.getVel().getY()));
                writer.write('\t');
                writer.write(toPlain(vector.getVel().getZ()));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write tabular ephemeris file: " + path, e);
        }
    }

    private static String toPlain(double value) {
        return BigDecimal.valueOf(value).toPlainString();
    }

    private void createParentDirectory(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create directory: " + parent, e);
        }
    }
}
