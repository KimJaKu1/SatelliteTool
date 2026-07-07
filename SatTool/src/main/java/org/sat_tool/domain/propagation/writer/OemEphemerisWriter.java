package org.sat_tool.domain.propagation.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.files.ccsds.definitions.BodyFacade;
import org.orekit.files.ccsds.definitions.CenterName;
import org.orekit.files.ccsds.definitions.FrameFacade;
import org.orekit.files.ccsds.definitions.OrekitCcsdsFrameMapper;
import org.orekit.files.ccsds.definitions.TimeSystem;
import org.orekit.files.ccsds.ndm.WriterBuilder;
import org.orekit.files.ccsds.ndm.odm.OdmHeader;
import org.orekit.files.ccsds.ndm.odm.oem.EphemerisOemWriter;
import org.orekit.files.ccsds.ndm.odm.oem.InterpolationMethod;
import org.orekit.files.ccsds.ndm.odm.oem.OemMetadata;
import org.orekit.files.ccsds.ndm.odm.oem.OemWriter;
import org.orekit.files.ccsds.utils.FileFormat;
import org.orekit.files.general.OrekitEphemerisFile;
import org.orekit.frames.Frame;
import org.orekit.propagation.SpacecraftState;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.AbsolutePVCoordinates;
import org.sat_tool.orekit.TimeConverter;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class OemEphemerisWriter {

    public void writeOemFile(List<EphemerisVector> ephemerisVector,
                             Frame frame,
                             Path path,
                             String objectId,
                             String objectName) {
        List<SpacecraftState> states = convertToStates(ephemerisVector, frame);
        double maxRelativeOffset = states.size() < 2
                ? 0.0
                : Math.abs(states.get(states.size() - 1).getDate().durationFrom(states.get(0).getDate()));

        writeOemStates(states, frame, path, objectId, objectName, maxRelativeOffset);
    }

    private List<SpacecraftState> convertToStates(List<EphemerisVector> ephemerisVector, Frame frame) {
        Objects.requireNonNull(ephemerisVector, "ephemerisVector");
        Objects.requireNonNull(frame, "frame");

        List<SpacecraftState> result = new ArrayList<>();
        for (EphemerisVector vector : ephemerisVector) {
            Objects.requireNonNull(vector, "ephemerisVector contains null element");
            Objects.requireNonNull(vector.getTime(), "ephemerisVector contains null time");
            Objects.requireNonNull(vector.getPos(), "ephemerisVector contains null position");
            Objects.requireNonNull(vector.getVel(), "ephemerisVector contains null velocity");

            AbsoluteDate date = TimeConverter.localDateTimeUtcToAbsoluteDate(vector.getTime());
            AbsolutePVCoordinates absPv = new AbsolutePVCoordinates(frame, date, vector.getPos(), vector.getVel());
            result.add(new SpacecraftState(absPv));
        }

        return result;
    }

    private void writeOemStates(List<SpacecraftState> states,
                                Frame frame,
                                Path path,
                                String objectId,
                                String objectName,
                                double maxRelativeOffset) {
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(path, "path");

        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("objectId must not be blank");
        }
        if (states.size() < 2) {
            throw new IllegalArgumentException("OEM export requires at least 2 states");
        }

        createParentDirectory(path);

        int interpolationSamples = Math.min(7, states.size());
        OrekitEphemerisFile ephemerisFile = new OrekitEphemerisFile();
        ephemerisFile.addSatellite(objectId)
                .addNewSegment(states, CelestialBodyFactory.getEarth(), interpolationSamples, TimeScalesFactory.getUTC());

        OemWriter oemWriter = new WriterBuilder().buildOemWriter();

        OdmHeader header = new OdmHeader();
        header.setFormatVersion(OemWriter.CCSDS_OEM_VERS);
        header.setCreationDate(new AbsoluteDate(Instant.now(), TimeScalesFactory.getUTC()));
        header.setOriginator("SatTool");
        header.setMessageId(objectId + "-OEM");

        OemMetadata metadata = new OemMetadata(interpolationSamples - 1, new OrekitCcsdsFrameMapper());
        metadata.setObjectID(objectId);
        metadata.setObjectName((objectName == null || objectName.isBlank()) ? objectId : objectName);
        metadata.setCenter(BodyFacade.create(CenterName.EARTH));
        metadata.setReferenceFrame(FrameFacade.map(frame));
        metadata.setTimeSystem(TimeSystem.UTC);
        metadata.setInterpolationMethod(InterpolationMethod.HERMITE);

        String outputName = path.getFileName() == null ? OemWriter.DEFAULT_FILE_NAME : path.getFileName().toString();
        EphemerisOemWriter fileWriter =
                new EphemerisOemWriter(oemWriter, header, metadata, FileFormat.KVN, outputName, maxRelativeOffset, 0);

        try (BufferedWriter writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            fileWriter.write(writer, ephemerisFile);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write OEM file: " + path, e);
        }
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
