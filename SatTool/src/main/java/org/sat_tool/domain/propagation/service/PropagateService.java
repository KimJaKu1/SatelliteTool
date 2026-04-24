package org.sat_tool.domain.propagation.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
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
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.SpacecraftState;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.AbsolutePVCoordinates;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.common.converter.TimeConverter;
import org.sat_tool.domain.coordinate.model.EphemerisVector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class PropagateService {

    @Autowired
    private TimeConverter timeConverter;

    public List<EphemerisVector> computeOrbitDataWithFrame(Propagator propagator,
                                                           AbsoluteDate startDate, AbsoluteDate endDate,
                                                           double intervalSeconds,
                                                           Frame targetFrame) {

        List<EphemerisVector> result = new ArrayList<>();

        for (AbsoluteDate date = startDate; date.compareTo(endDate) <= 0; date = date.shiftedBy(intervalSeconds)) {
            PVCoordinates pv = propagator.getPVCoordinates(date, targetFrame);

            EphemerisVector item = new EphemerisVector();
            item.setTime(timeConverter.absoluteDateToLocalDateTimeUtc(date));
            item.setPos(pv.getPosition());
            item.setVel(pv.getVelocity());

            result.add(item);
        }

        return result;
    }

    public List<EphemerisVector> computeEphemerisECI(Propagator propagator, AbsoluteDate startDate,
                                                     AbsoluteDate endDate, double intervalSeconds) {
        Frame eciFrame = FramesFactory.getGCRF();
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, eciFrame);
    }

    public List<EphemerisVector> computeEphemerisECEF(Propagator propagator, AbsoluteDate startDate,
                                                      AbsoluteDate endDate, double intervalSeconds) {
        Frame ecefFrame = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, ecefFrame);
    }

    public List<EphemerisVector> computeOrbitData2TOD(Propagator propagator, AbsoluteDate startDate,
                                                      AbsoluteDate endDate, double intervalSeconds) {
        Frame todFrame = FramesFactory.getTOD(IERSConventions.IERS_2010, true);
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, todFrame);
    }

    public List<EphemerisVector> computeOrbitData2TEME(Propagator propagator, AbsoluteDate startDate,
                                                       AbsoluteDate endDate, double intervalSeconds) {
        Frame temeFrame = FramesFactory.getTEME();
        return computeOrbitDataWithFrame(propagator, startDate, endDate, intervalSeconds, temeFrame);
    }

    private List<SpacecraftState> computeStatesWithFrame(Propagator propagator,
                                                         AbsoluteDate startDate, AbsoluteDate endDate,
                                                         double intervalSeconds,
                                                         Frame targetFrame) {

        List<SpacecraftState> result = new ArrayList<>();

        for (AbsoluteDate date = startDate; date.compareTo(endDate) <= 0; date = date.shiftedBy(intervalSeconds)) {
            PVCoordinates pv = propagator.getPVCoordinates(date, targetFrame);
            result.add(new SpacecraftState(new AbsolutePVCoordinates(targetFrame, date, pv)));
        }

        return result;
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

            AbsoluteDate date = timeConverter.localDateTimeUtcToAbsoluteDate(vector.getTime());
            AbsolutePVCoordinates absPv = new AbsolutePVCoordinates(frame, date, vector.getPos(), vector.getVel());
            result.add(new SpacecraftState(absPv));
        }

        return result;
    }

    private static String toPlain(double value) {
        return BigDecimal.valueOf(value).toPlainString();
    }

    private void writeOemStates(List<SpacecraftState> states,
                                Frame frame,
                                Path parnet,
                                String objectId,
                                String objectName,
                                double maxRelativeOffset) {

        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(parnet, "parnet");

        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("objectId must not be blank");
        }
        if (states.size() < 2) {
            throw new IllegalArgumentException("OEM export requires at least 2 states");
        }

        Path parent = parnet.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

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

        OemMetadata metadata = new OemMetadata(interpolationSamples - 1);
        metadata.setObjectID(objectId);
        metadata.setObjectName((objectName == null || objectName.isBlank()) ? objectId : objectName);
        metadata.setCenter(BodyFacade.create(CenterName.EARTH));
        metadata.setReferenceFrame(FrameFacade.map(frame));
        metadata.setTimeSystem(TimeSystem.UTC);
        metadata.setInterpolationMethod(InterpolationMethod.HERMITE);

        String outputName = parnet.getFileName() == null ? OemWriter.DEFAULT_FILE_NAME : parnet.getFileName().toString();
        EphemerisOemWriter fileWriter =
                new EphemerisOemWriter(oemWriter, header, metadata, FileFormat.KVN, outputName, maxRelativeOffset, 0);

        try (BufferedWriter writer = Files.newBufferedWriter(
                parnet, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            fileWriter.write(writer, ephemerisFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeOemFile(List<EphemerisVector> ephemerisVector,
                             Frame frame,
                             Path parnet,
                             String objectId,
                             String objectName) {

        List<SpacecraftState> states = convertToStates(ephemerisVector, frame);
        double maxRelativeOffset = states.size() < 2 ? 0.0 :
                Math.abs(states.get(states.size() - 1).getDate().durationFrom(states.get(0).getDate()));
        writeOemStates(states, frame, parnet, objectId, objectName, maxRelativeOffset);
    }

    public void writeFile(List<EphemerisVector> ephemerisVector, Path parnet) {
        Path parent = parnet.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                parnet, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (EphemerisVector vector : ephemerisVector) {
                writer.write(timeConverter.localDateTimeToString(vector.getTime()));
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
            throw new RuntimeException(e);
        }
    }
}
