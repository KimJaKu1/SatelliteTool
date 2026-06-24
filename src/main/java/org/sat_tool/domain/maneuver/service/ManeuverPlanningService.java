package org.sat_tool.domain.maneuver.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.SpacecraftState;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.maneuver.model.ManeuverPlan;
import org.sat_tool.domain.maneuver.model.ManeuverPlanType;
import org.sat_tool.domain.maneuver.model.OrbitShapeSnapshot;
import org.sat_tool.domain.maneuver.model.PlannedImpulse;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class ManeuverPlanningService {

    private static final double DEFAULT_SAMPLE_STEP_SECONDS = 60.0;
    private static final double DEFAULT_SEMI_MAJOR_AXIS_TOLERANCE_M = 10.0;

    public record AverageAltitudeMaintenanceRequest(
            Propagator baselinePropagator,
            AbsoluteDate searchStartDate,
            AbsoluteDate searchEndDate,
            double sampleStepSeconds,
            double targetMeanAltitudeM,
            double lowerBandMeanAltitudeM,
            double eccentricityLimit,
            double semiMajorAxisToleranceM,
            double ispSeconds,
            double spacecraftMassKg,
            double earthRadiusM
    ) {
    }

    public record SemiMajorAxisRaiseEccentricityLimitedRequest(
            Propagator baselinePropagator,
            AbsoluteDate searchStartDate,
            AbsoluteDate searchEndDate,
            double sampleStepSeconds,
            double deltaSemiMajorAxisM,
            double eccentricityLimit,
            double semiMajorAxisToleranceM,
            double ispSeconds,
            double spacecraftMassKg,
            double earthRadiusM
    ) {
    }

    public ManeuverPlan planAverageAltitudeMaintenance(AverageAltitudeMaintenanceRequest request) {
        validateAverageAltitudeRequest(request);

        double earthRadius = resolveEarthRadius(request.earthRadiusM());
        Optional<SpacecraftState> triggerState = firstStateBelowMeanAltitude(
                request.baselinePropagator(),
                request.searchStartDate(),
                request.searchEndDate(),
                resolveSampleStep(request.sampleStepSeconds()),
                request.lowerBandMeanAltitudeM(),
                earthRadius
        );

        if (triggerState.isEmpty()) {
            SpacecraftState startState = request.baselinePropagator().propagate(request.searchStartDate());
            OrbitShapeSnapshot startShape = snapshot(startState, earthRadius);
            return noManeuverRequired(
                    ManeuverPlanType.AVERAGE_ALTITUDE_MAINTENANCE,
                    "Mean altitude is still above the lower maintenance band.",
                    startShape,
                    earthRadius + request.targetMeanAltitudeM(),
                    request.eccentricityLimit(),
                    request.ispSeconds(),
                    request.spacecraftMassKg()
            );
        }

        SpacecraftState maneuverStart = triggerState.get();
        double targetSemiMajorAxisM = earthRadius + request.targetMeanAltitudeM();
        return findBestPlan(
                ManeuverPlanType.AVERAGE_ALTITUDE_MAINTENANCE,
                request.baselinePropagator(),
                maneuverStart.getDate(),
                request.searchEndDate(),
                resolveSampleStep(request.sampleStepSeconds()),
                targetSemiMajorAxisM,
                request.eccentricityLimit(),
                resolveSemiMajorAxisTolerance(request.semiMajorAxisToleranceM()),
                request.ispSeconds(),
                request.spacecraftMassKg(),
                earthRadius
        );
    }

    public ManeuverPlan planSemiMajorAxisRaiseEccentricityLimited(
            SemiMajorAxisRaiseEccentricityLimitedRequest request
    ) {
        validateSemiMajorAxisRaiseRequest(request);

        double earthRadius = resolveEarthRadius(request.earthRadiusM());
        SpacecraftState startState = request.baselinePropagator().propagate(request.searchStartDate());
        double targetSemiMajorAxisM = toKeplerian(startState.getOrbit()).getA() + request.deltaSemiMajorAxisM();

        return findBestPlan(
                ManeuverPlanType.SEMI_MAJOR_AXIS_RAISE_ECCENTRICITY_LIMITED,
                request.baselinePropagator(),
                request.searchStartDate(),
                request.searchEndDate(),
                resolveSampleStep(request.sampleStepSeconds()),
                targetSemiMajorAxisM,
                request.eccentricityLimit(),
                resolveSemiMajorAxisTolerance(request.semiMajorAxisToleranceM()),
                request.ispSeconds(),
                request.spacecraftMassKg(),
                earthRadius
        );
    }

    private ManeuverPlan findBestPlan(ManeuverPlanType type,
                                      Propagator baselinePropagator,
                                      AbsoluteDate searchStartDate,
                                      AbsoluteDate searchEndDate,
                                      double sampleStepSeconds,
                                      double targetSemiMajorAxisM,
                                      double eccentricityLimit,
                                      double semiMajorAxisToleranceM,
                                      double ispSeconds,
                                      double spacecraftMassKg,
                                      double earthRadiusM) {

        List<ManeuverPlan> candidates = new ArrayList<>();
        for (AbsoluteDate date = searchStartDate;
             date.compareTo(searchEndDate) <= 0;
             date = date.shiftedBy(sampleStepSeconds)) {

            SpacecraftState state = baselinePropagator.propagate(date);
            planSingleProgradeImpulse(type, state, targetSemiMajorAxisM, eccentricityLimit,
                            semiMajorAxisToleranceM, ispSeconds, spacecraftMassKg, earthRadiusM)
                    .ifPresent(candidates::add);

            planTwoImpulseCircularizingTransfer(type, state, targetSemiMajorAxisM, eccentricityLimit,
                            semiMajorAxisToleranceM, ispSeconds, spacecraftMassKg, earthRadiusM)
                    .ifPresent(candidates::add);
        }

        return candidates.stream()
                .min(Comparator.comparingDouble(ManeuverPlan::fuelUsedKg)
                        .thenComparingDouble(ManeuverPlan::totalDeltaVMps))
                .orElseGet(() -> infeasiblePlan(type, baselinePropagator.propagate(searchStartDate),
                        targetSemiMajorAxisM, eccentricityLimit, ispSeconds, spacecraftMassKg, earthRadiusM));
    }

    private Optional<ManeuverPlan> planSingleProgradeImpulse(ManeuverPlanType type,
                                                             SpacecraftState beforeState,
                                                             double targetSemiMajorAxisM,
                                                             double eccentricityLimit,
                                                             double semiMajorAxisToleranceM,
                                                             double ispSeconds,
                                                             double spacecraftMassKg,
                                                             double earthRadiusM) {
        PVCoordinates pv = beforeState.getPVCoordinates();
        Vector3D position = pv.getPosition();
        Vector3D velocity = pv.getVelocity();
        double radius = position.getNorm();
        double currentSpeed = velocity.getNorm();
        double mu = beforeState.getOrbit().getMu();
        double targetSpeedSquared = mu * (2.0 / radius - 1.0 / targetSemiMajorAxisM);

        if (targetSpeedSquared <= 0.0) {
            return Optional.empty();
        }

        double targetSpeed = Math.sqrt(targetSpeedSquared);
        double deltaV = targetSpeed - currentSpeed;
        if (deltaV <= 0.0) {
            return Optional.empty();
        }

        Vector3D prograde = velocity.normalize();
        Vector3D afterVelocity = velocity.add(deltaV, prograde);
        KeplerianOrbit afterOrbit = new KeplerianOrbit(
                new PVCoordinates(position, afterVelocity),
                beforeState.getFrame(),
                beforeState.getDate(),
                mu
        );

        OrbitShapeSnapshot afterShape = snapshot(afterOrbit, earthRadiusM);
        if (!satisfiesTarget(afterShape, targetSemiMajorAxisM, eccentricityLimit, semiMajorAxisToleranceM)) {
            return Optional.empty();
        }

        Vector3D deltaVVector = prograde.scalarMultiply(deltaV);
        return Optional.of(buildPlan(type, "Single prograde impulse.", targetSemiMajorAxisM, eccentricityLimit,
                ispSeconds, spacecraftMassKg, snapshot(beforeState, earthRadiusM), afterShape,
                List.of(new PlannedImpulse(beforeState.getDate(), deltaVVector, deltaV))));
    }

    private Optional<ManeuverPlan> planTwoImpulseCircularizingTransfer(ManeuverPlanType type,
                                                                       SpacecraftState beforeState,
                                                                       double targetSemiMajorAxisM,
                                                                       double eccentricityLimit,
                                                                       double semiMajorAxisToleranceM,
                                                                       double ispSeconds,
                                                                       double spacecraftMassKg,
                                                                       double earthRadiusM) {
        PVCoordinates pv = beforeState.getPVCoordinates();
        Vector3D position = pv.getPosition();
        Vector3D velocity = pv.getVelocity();
        double radius1 = position.getNorm();
        double radius2 = targetSemiMajorAxisM;
        if (radius2 <= radius1) {
            return Optional.empty();
        }

        double mu = beforeState.getOrbit().getMu();
        double transferA = 0.5 * (radius1 + radius2);
        double transferSpeed1 = Math.sqrt(mu * (2.0 / radius1 - 1.0 / transferA));
        double deltaV1 = transferSpeed1 - velocity.getNorm();
        if (deltaV1 <= 0.0) {
            return Optional.empty();
        }

        Vector3D prograde1 = velocity.normalize();
        Vector3D transferVelocity1 = velocity.add(deltaV1, prograde1);
        KeplerianOrbit transferOrbit = new KeplerianOrbit(
                new PVCoordinates(position, transferVelocity1),
                beforeState.getFrame(),
                beforeState.getDate(),
                mu
        );

        double transferHalfPeriod = Math.PI * Math.sqrt(transferA * transferA * transferA / mu);
        KeplerianOrbit apogeeOrbit = transferOrbit.shiftedBy(transferHalfPeriod);
        PVCoordinates apogeePv = apogeeOrbit.getPVCoordinates();
        double radiusAtSecondBurn = apogeePv.getPosition().getNorm();
        double circularSpeed = Math.sqrt(mu / radiusAtSecondBurn);
        double deltaV2 = circularSpeed - apogeePv.getVelocity().getNorm();
        if (deltaV2 <= 0.0) {
            return Optional.empty();
        }

        Vector3D prograde2 = apogeePv.getVelocity().normalize();
        Vector3D finalVelocity = apogeePv.getVelocity().add(deltaV2, prograde2);
        KeplerianOrbit afterOrbit = new KeplerianOrbit(
                new PVCoordinates(apogeePv.getPosition(), finalVelocity),
                beforeState.getFrame(),
                apogeeOrbit.getDate(),
                mu
        );

        OrbitShapeSnapshot afterShape = snapshot(afterOrbit, earthRadiusM);
        if (!satisfiesTarget(afterShape, targetSemiMajorAxisM, eccentricityLimit, semiMajorAxisToleranceM)) {
            return Optional.empty();
        }

        List<PlannedImpulse> impulses = List.of(
                new PlannedImpulse(beforeState.getDate(), prograde1.scalarMultiply(deltaV1), deltaV1),
                new PlannedImpulse(apogeeOrbit.getDate(), prograde2.scalarMultiply(deltaV2), deltaV2)
        );
        return Optional.of(buildPlan(type, "Two-impulse circularizing transfer.", targetSemiMajorAxisM,
                eccentricityLimit, ispSeconds, spacecraftMassKg, snapshot(beforeState, earthRadiusM),
                afterShape, impulses));
    }

    private Optional<SpacecraftState> firstStateBelowMeanAltitude(Propagator propagator,
                                                                  AbsoluteDate searchStartDate,
                                                                  AbsoluteDate searchEndDate,
                                                                  double sampleStepSeconds,
                                                                  double lowerBandMeanAltitudeM,
                                                                  double earthRadiusM) {
        for (AbsoluteDate date = searchStartDate;
             date.compareTo(searchEndDate) <= 0;
             date = date.shiftedBy(sampleStepSeconds)) {
            SpacecraftState state = propagator.propagate(date);
            if (snapshot(state, earthRadiusM).meanAltitudeM() < lowerBandMeanAltitudeM) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    private ManeuverPlan buildPlan(ManeuverPlanType type,
                                   String status,
                                   double targetSemiMajorAxisM,
                                   double eccentricityLimit,
                                   double ispSeconds,
                                   double massBeforeKg,
                                   OrbitShapeSnapshot before,
                                   OrbitShapeSnapshot after,
                                   List<PlannedImpulse> impulses) {
        double totalDeltaV = impulses.stream().mapToDouble(PlannedImpulse::deltaVMagnitudeMps).sum();
        double massAfter = massAfterImpulses(massBeforeKg, ispSeconds, impulses);
        return new ManeuverPlan(
                type,
                true,
                status,
                targetSemiMajorAxisM,
                after.semiMajorAxisM() - targetSemiMajorAxisM,
                eccentricityLimit,
                totalDeltaV,
                ispSeconds,
                massBeforeKg,
                massAfter,
                massBeforeKg - massAfter,
                before,
                after,
                impulses
        );
    }

    private ManeuverPlan noManeuverRequired(ManeuverPlanType type,
                                            String status,
                                            OrbitShapeSnapshot startShape,
                                            double targetSemiMajorAxisM,
                                            double eccentricityLimit,
                                            double ispSeconds,
                                            double massKg) {
        return new ManeuverPlan(
                type,
                true,
                status,
                targetSemiMajorAxisM,
                startShape.semiMajorAxisM() - targetSemiMajorAxisM,
                eccentricityLimit,
                0.0,
                ispSeconds,
                massKg,
                massKg,
                0.0,
                startShape,
                startShape,
                List.of()
        );
    }

    private ManeuverPlan infeasiblePlan(ManeuverPlanType type,
                                        SpacecraftState startState,
                                        double targetSemiMajorAxisM,
                                        double eccentricityLimit,
                                        double ispSeconds,
                                        double massKg,
                                        double earthRadiusM) {
        OrbitShapeSnapshot shape = snapshot(startState, earthRadiusM);
        return new ManeuverPlan(
                type,
                false,
                "No sampled prograde impulse plan satisfies target semi-major axis and eccentricity limit.",
                targetSemiMajorAxisM,
                shape.semiMajorAxisM() - targetSemiMajorAxisM,
                eccentricityLimit,
                0.0,
                ispSeconds,
                massKg,
                massKg,
                0.0,
                shape,
                shape,
                List.of()
        );
    }

    private boolean satisfiesTarget(OrbitShapeSnapshot shape,
                                    double targetSemiMajorAxisM,
                                    double eccentricityLimit,
                                    double semiMajorAxisToleranceM) {
        return Math.abs(shape.semiMajorAxisM() - targetSemiMajorAxisM) <= semiMajorAxisToleranceM
                && shape.eccentricity() <= eccentricityLimit;
    }

    private OrbitShapeSnapshot snapshot(SpacecraftState state, double earthRadiusM) {
        return snapshot(state.getOrbit(), earthRadiusM);
    }

    private OrbitShapeSnapshot snapshot(Orbit orbit, double earthRadiusM) {
        KeplerianOrbit keplerian = toKeplerian(orbit);
        double a = keplerian.getA();
        double e = keplerian.getE();
        double perigeeAltitude = a * (1.0 - e) - earthRadiusM;
        double apogeeAltitude = a * (1.0 + e) - earthRadiusM;
        return new OrbitShapeSnapshot(orbit.getDate(), a, e, perigeeAltitude, apogeeAltitude, a - earthRadiusM);
    }

    private KeplerianOrbit toKeplerian(Orbit orbit) {
        if (orbit instanceof KeplerianOrbit keplerian) {
            return keplerian;
        }
        return new KeplerianOrbit(orbit);
    }

    private double massAfterImpulses(double massBeforeKg, double ispSeconds, List<PlannedImpulse> impulses) {
        double mass = massBeforeKg;
        for (PlannedImpulse impulse : impulses) {
            mass *= Math.exp(-impulse.deltaVMagnitudeMps() / (ispSeconds * Constants.G0_STANDARD_GRAVITY));
        }
        return mass;
    }

    private double resolveEarthRadius(double earthRadiusM) {
        return earthRadiusM > 0.0 ? earthRadiusM : Constants.WGS84_EARTH_EQUATORIAL_RADIUS;
    }

    private double resolveSampleStep(double sampleStepSeconds) {
        return sampleStepSeconds > 0.0 ? sampleStepSeconds : DEFAULT_SAMPLE_STEP_SECONDS;
    }

    private double resolveSemiMajorAxisTolerance(double semiMajorAxisToleranceM) {
        return semiMajorAxisToleranceM > 0.0 ? semiMajorAxisToleranceM : DEFAULT_SEMI_MAJOR_AXIS_TOLERANCE_M;
    }

    private void validateAverageAltitudeRequest(AverageAltitudeMaintenanceRequest request) {
        Objects.requireNonNull(request, "request");
        validateCommon(request.baselinePropagator(), request.searchStartDate(), request.searchEndDate(),
                request.eccentricityLimit(), request.ispSeconds(), request.spacecraftMassKg());
        if (request.targetMeanAltitudeM() <= 0.0) {
            throw new IllegalArgumentException("targetMeanAltitudeM must be positive");
        }
        if (request.lowerBandMeanAltitudeM() <= 0.0) {
            throw new IllegalArgumentException("lowerBandMeanAltitudeM must be positive");
        }
        if (request.lowerBandMeanAltitudeM() >= request.targetMeanAltitudeM()) {
            throw new IllegalArgumentException("lowerBandMeanAltitudeM must be below targetMeanAltitudeM");
        }
    }

    private void validateSemiMajorAxisRaiseRequest(SemiMajorAxisRaiseEccentricityLimitedRequest request) {
        Objects.requireNonNull(request, "request");
        validateCommon(request.baselinePropagator(), request.searchStartDate(), request.searchEndDate(),
                request.eccentricityLimit(), request.ispSeconds(), request.spacecraftMassKg());
        if (request.deltaSemiMajorAxisM() <= 0.0) {
            throw new IllegalArgumentException("deltaSemiMajorAxisM must be positive");
        }
    }

    private void validateCommon(Propagator propagator,
                                AbsoluteDate searchStartDate,
                                AbsoluteDate searchEndDate,
                                double eccentricityLimit,
                                double ispSeconds,
                                double spacecraftMassKg) {
        Objects.requireNonNull(propagator, "baselinePropagator");
        Objects.requireNonNull(searchStartDate, "searchStartDate");
        Objects.requireNonNull(searchEndDate, "searchEndDate");
        if (searchEndDate.compareTo(searchStartDate) < 0) {
            throw new IllegalArgumentException("searchEndDate must be after or equal to searchStartDate");
        }
        if (eccentricityLimit < 0.0) {
            throw new IllegalArgumentException("eccentricityLimit must not be negative");
        }
        if (ispSeconds <= 0.0) {
            throw new IllegalArgumentException("ispSeconds must be positive");
        }
        if (spacecraftMassKg <= 0.0) {
            throw new IllegalArgumentException("spacecraftMassKg must be positive");
        }
    }
}
