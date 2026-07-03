package org.sat_tool.domain.maneuver.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.ode.nonstiff.DormandPrince853Integrator;
import org.orekit.attitudes.LofOffset;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.forces.drag.DragForce;
import org.orekit.forces.drag.IsotropicDrag;
import org.orekit.forces.gravity.HolmesFeatherstoneAttractionModel;
import org.orekit.forces.gravity.ThirdBodyAttraction;
import org.orekit.forces.gravity.potential.EGMFormatReader;
import org.orekit.forces.gravity.potential.GravityFieldFactory;
import org.orekit.forces.maneuvers.ConstantThrustManeuver;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.LOFType;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.orbits.OrbitType;
import org.orekit.models.earth.atmosphere.JB2008;
import org.orekit.models.earth.atmosphere.data.JB2008SpaceEnvironmentData;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.sat_tool.domain.maneuver.model.ConstantThrustManeuverPlan;
import org.sat_tool.domain.maneuver.model.ManeuverPlan;
import org.sat_tool.domain.maneuver.model.ManeuverPlanType;
import org.sat_tool.domain.maneuver.model.OrbitShapeSnapshot;
import org.sat_tool.domain.maneuver.model.PlannedFiniteBurn;
import org.sat_tool.domain.maneuver.model.PlannedImpulse;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@DependsOn("orekitInitializer")
@Service
public class ManeuverPlanningService {

    private static final double DEFAULT_SAMPLE_STEP_SECONDS = 60.0;
    private static final double DEFAULT_SEMI_MAJOR_AXIS_TOLERANCE_M = 10.0;
    private static final double DEFAULT_NUMERICAL_MIN_STEP_SECONDS = 0.001;
    private static final double DEFAULT_NUMERICAL_MAX_STEP_SECONDS = 300.0;
    private static final double DEFAULT_NUMERICAL_POSITION_TOLERANCE_M = 1.0;
    private static final Object GRAVITY_FIELD_FACTORY_LOCK = new Object();

    public record NumericalForceModelConfig(
            boolean useEgm96Gravity,
            int gravityDegree,
            int gravityOrder,
            String egm96SupportedNames,
            boolean useJb2008Drag,
            double dragCrossSectionM2,
            double dragCoefficient,
            String jb2008SolfsmySupportedNames,
            String jb2008DtcSupportedNames,
            boolean includeMoonThirdBody,
            boolean includeSunThirdBody,
            boolean includeJupiterThirdBody
    ) {
        public static NumericalForceModelConfig none() {
            return new NumericalForceModelConfig(
                    false, 0, 0, null,
                    false, 0.0, 0.0, null, null,
                    false, false, false
            );
        }

        public static NumericalForceModelConfig egm96Jb2008SunMoonJupiter(double dragCrossSectionM2,
                                                                          double dragCoefficient) {
            return new NumericalForceModelConfig(
                    true,
                    70,
                    70,
                    GravityFieldFactory.EGM_FILENAME,
                    true,
                    dragCrossSectionM2,
                    dragCoefficient,
                    JB2008SpaceEnvironmentData.DEFAULT_SUPPORTED_NAMES_SOLFSMY,
                    JB2008SpaceEnvironmentData.DEFAULT_SUPPORTED_NAMES_DTC,
                    true,
                    true,
                    true
            );
        }
    }

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

    public record ConstantThrustAverageAltitudeMaintenanceRequest(
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
            double thrustN,
            double finalCoastSeconds,
            double earthRadiusM,
            double numericalMinStepSeconds,
            double numericalMaxStepSeconds,
            double numericalPositionToleranceM,
            NumericalForceModelConfig forceModelConfig
    ) {
    }

    public record ConstantThrustSemiMajorAxisRaiseEccentricityLimitedRequest(
            Propagator baselinePropagator,
            AbsoluteDate searchStartDate,
            AbsoluteDate searchEndDate,
            double sampleStepSeconds,
            double deltaSemiMajorAxisM,
            double eccentricityLimit,
            double semiMajorAxisToleranceM,
            double ispSeconds,
            double spacecraftMassKg,
            double thrustN,
            double finalCoastSeconds,
            double earthRadiusM,
            double numericalMinStepSeconds,
            double numericalMaxStepSeconds,
            double numericalPositionToleranceM,
            NumericalForceModelConfig forceModelConfig
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

    public ConstantThrustManeuverPlan planAverageAltitudeMaintenanceWithConstantThrust(
            ConstantThrustAverageAltitudeMaintenanceRequest request
    ) {
        validateConstantThrustAverageAltitudeRequest(request);

        ManeuverPlan seedImpulsePlan = planAverageAltitudeMaintenance(
                new AverageAltitudeMaintenanceRequest(
                        request.baselinePropagator(),
                        request.searchStartDate(),
                        request.searchEndDate(),
                        request.sampleStepSeconds(),
                        request.targetMeanAltitudeM(),
                        request.lowerBandMeanAltitudeM(),
                        request.eccentricityLimit(),
                        request.semiMajorAxisToleranceM(),
                        request.ispSeconds(),
                        request.spacecraftMassKg(),
                        request.earthRadiusM()
                )
        );

        return validateAsConstantThrustPlan(
                seedImpulsePlan,
                request.baselinePropagator(),
                request.thrustN(),
                resolveFinalCoast(request.finalCoastSeconds()),
                resolveEarthRadius(request.earthRadiusM()),
                resolveNumericalMinStep(request.numericalMinStepSeconds()),
                resolveNumericalMaxStep(request.numericalMaxStepSeconds()),
                resolveNumericalPositionTolerance(request.numericalPositionToleranceM()),
                resolveSemiMajorAxisTolerance(request.semiMajorAxisToleranceM()),
                resolveForceModelConfig(request.forceModelConfig())
        );
    }

    public ConstantThrustManeuverPlan planSemiMajorAxisRaiseEccentricityLimitedWithConstantThrust(
            ConstantThrustSemiMajorAxisRaiseEccentricityLimitedRequest request
    ) {
        validateConstantThrustSemiMajorAxisRaiseRequest(request);

        ManeuverPlan seedImpulsePlan = planSemiMajorAxisRaiseEccentricityLimited(
                new SemiMajorAxisRaiseEccentricityLimitedRequest(
                        request.baselinePropagator(),
                        request.searchStartDate(),
                        request.searchEndDate(),
                        request.sampleStepSeconds(),
                        request.deltaSemiMajorAxisM(),
                        request.eccentricityLimit(),
                        request.semiMajorAxisToleranceM(),
                        request.ispSeconds(),
                        request.spacecraftMassKg(),
                        request.earthRadiusM()
                )
        );

        return validateAsConstantThrustPlan(
                seedImpulsePlan,
                request.baselinePropagator(),
                request.thrustN(),
                resolveFinalCoast(request.finalCoastSeconds()),
                resolveEarthRadius(request.earthRadiusM()),
                resolveNumericalMinStep(request.numericalMinStepSeconds()),
                resolveNumericalMaxStep(request.numericalMaxStepSeconds()),
                resolveNumericalPositionTolerance(request.numericalPositionToleranceM()),
                resolveSemiMajorAxisTolerance(request.semiMajorAxisToleranceM()),
                resolveForceModelConfig(request.forceModelConfig())
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

    private ConstantThrustManeuverPlan validateAsConstantThrustPlan(ManeuverPlan seedImpulsePlan,
                                                                    Propagator baselinePropagator,
                                                                    double thrustN,
                                                                    double finalCoastSeconds,
                                                                    double earthRadiusM,
                                                                    double numericalMinStepSeconds,
                                                                    double numericalMaxStepSeconds,
                                                                    double numericalPositionToleranceM,
                                                                    double semiMajorAxisToleranceM,
                                                                    NumericalForceModelConfig forceModelConfig) {
        if (!seedImpulsePlan.feasible()) {
            return constantThrustPlan(seedImpulsePlan, false,
                    "Impulse seed plan is infeasible; constant-thrust validation was not attempted.",
                    thrustN, finalCoastSeconds, seedImpulsePlan.after(), List.of());
        }

        if (seedImpulsePlan.impulses().isEmpty()) {
            return constantThrustPlan(seedImpulsePlan, true,
                    "No finite burn is required because the seed plan has no impulse.",
                    thrustN, finalCoastSeconds, seedImpulsePlan.after(), List.of());
        }

        List<PlannedFiniteBurn> burns = finiteBurnsFromImpulses(
                seedImpulsePlan.impulses(),
                seedImpulsePlan.massBeforeKg(),
                thrustN,
                seedImpulsePlan.ispSeconds()
        );

        Optional<String> overlap = firstBurnOverlap(burns);
        if (overlap.isPresent()) {
            return constantThrustPlan(seedImpulsePlan, false, overlap.get(), thrustN,
                    finalCoastSeconds, seedImpulsePlan.after(), burns);
        }

        SpacecraftState numericalInitialState = baselinePropagator
                .propagate(burns.get(0).startDate())
                .withMass(seedImpulsePlan.massBeforeKg());
        NumericalPropagator propagator = constantThrustPropagator(
                numericalInitialState,
                burns,
                numericalMinStepSeconds,
                numericalMaxStepSeconds,
                numericalPositionToleranceM,
                earthRadiusM,
                forceModelConfig
        );

        AbsoluteDate finalDate = burns.get(burns.size() - 1).endDate().shiftedBy(finalCoastSeconds);
        SpacecraftState finalState = propagator.propagate(finalDate);
        OrbitShapeSnapshot afterNumerical = snapshot(finalState, earthRadiusM);
        boolean feasible = satisfiesTarget(afterNumerical, seedImpulsePlan.targetSemiMajorAxisM(),
                seedImpulsePlan.eccentricityLimit(), semiMajorAxisToleranceM);

        String status = feasible
                ? "ConstantThrustManeuver numerical validation satisfies target semi-major axis and eccentricity limit."
                : "ConstantThrustManeuver numerical validation does not satisfy target semi-major axis or eccentricity limit.";

        return constantThrustPlan(seedImpulsePlan, feasible, status, thrustN, finalCoastSeconds,
                afterNumerical, burns);
    }

    private NumericalPropagator constantThrustPropagator(SpacecraftState initialState,
                                                        List<PlannedFiniteBurn> burns,
                                                        double minStepSeconds,
                                                        double maxStepSeconds,
                                                        double positionToleranceM,
                                                        double earthRadiusM,
                                                        NumericalForceModelConfig forceModelConfig) {
        double[][] tolerances = NumericalPropagator.tolerances(
                positionToleranceM,
                initialState.getOrbit(),
                OrbitType.CARTESIAN
        );
        DormandPrince853Integrator integrator = new DormandPrince853Integrator(
                minStepSeconds,
                maxStepSeconds,
                tolerances[0],
                tolerances[1]
        );

        LofOffset progradeAttitude = new LofOffset(initialState.getFrame(), LOFType.TNW);
        NumericalPropagator propagator = new NumericalPropagator(integrator, progradeAttitude);
        propagator.setOrbitType(OrbitType.CARTESIAN);
        propagator.setMu(initialState.getOrbit().getMu());
        propagator.setInitialState(initialState);
        addConfiguredForceModels(propagator, forceModelConfig, earthRadiusM);

        for (PlannedFiniteBurn burn : burns) {
            propagator.addForceModel(new ConstantThrustManeuver(
                    burn.startDate(),
                    burn.durationSeconds(),
                    burn.thrustN(),
                    burn.ispSeconds(),
                    progradeAttitude,
                    burn.directionInLofFrame()
            ));
        }

        return propagator;
    }

    private void addConfiguredForceModels(NumericalPropagator propagator,
                                          NumericalForceModelConfig config,
                                          double earthRadiusM) {
        if (config.useEgm96Gravity()) {
            propagator.setIgnoreCentralAttraction(true);
            propagator.addForceModel(new HolmesFeatherstoneAttractionModel(
                    FramesFactory.getITRF(IERSConventions.IERS_2010, true),
                    egm96Provider(config.gravityDegree(), config.gravityOrder(), config.egm96SupportedNames())
            ));
        }

        if (config.useJb2008Drag()) {
            OneAxisEllipsoid earth = new OneAxisEllipsoid(
                    earthRadiusM,
                    Constants.WGS84_EARTH_FLATTENING,
                    FramesFactory.getITRF(IERSConventions.IERS_2010, true)
            );
            JB2008SpaceEnvironmentData data = new JB2008SpaceEnvironmentData(
                    nonBlankOrDefault(config.jb2008SolfsmySupportedNames(),
                            JB2008SpaceEnvironmentData.DEFAULT_SUPPORTED_NAMES_SOLFSMY),
                    nonBlankOrDefault(config.jb2008DtcSupportedNames(),
                            JB2008SpaceEnvironmentData.DEFAULT_SUPPORTED_NAMES_DTC)
            );
            propagator.addForceModel(new DragForce(
                    new JB2008(data, CelestialBodyFactory.getSun(), earth),
                    new IsotropicDrag(config.dragCrossSectionM2(), config.dragCoefficient())
            ));
        }

        if (config.includeMoonThirdBody()) {
            propagator.addForceModel(new ThirdBodyAttraction(CelestialBodyFactory.getMoon()));
        }
        if (config.includeSunThirdBody()) {
            propagator.addForceModel(new ThirdBodyAttraction(CelestialBodyFactory.getSun()));
        }
        if (config.includeJupiterThirdBody()) {
            propagator.addForceModel(new ThirdBodyAttraction(CelestialBodyFactory.getJupiter()));
        }
    }

    private org.orekit.forces.gravity.potential.NormalizedSphericalHarmonicsProvider egm96Provider(
            int degree,
            int order,
            String supportedNames
    ) {
        synchronized (GRAVITY_FIELD_FACTORY_LOCK) {
            GravityFieldFactory.clearPotentialCoefficientsReaders();
            try {
                GravityFieldFactory.addPotentialCoefficientsReader(
                        new EGMFormatReader(nonBlankOrDefault(supportedNames, GravityFieldFactory.EGM_FILENAME), false)
                );
                return GravityFieldFactory.getNormalizedProvider(degree, order);
            } finally {
                GravityFieldFactory.clearPotentialCoefficientsReaders();
                GravityFieldFactory.addDefaultPotentialCoefficientsReaders();
            }
        }
    }

    private List<PlannedFiniteBurn> finiteBurnsFromImpulses(List<PlannedImpulse> impulses,
                                                            double initialMassKg,
                                                            double thrustN,
                                                            double ispSeconds) {
        List<PlannedFiniteBurn> burns = new ArrayList<>();
        double massBefore = initialMassKg;
        for (PlannedImpulse impulse : impulses) {
            double massAfter = massBefore * Math.exp(
                    -impulse.deltaVMagnitudeMps() / (ispSeconds * Constants.G0_STANDARD_GRAVITY)
            );
            double fuelUsed = massBefore - massAfter;
            double flowRateKgPerSecond = thrustN / (ispSeconds * Constants.G0_STANDARD_GRAVITY);
            double durationSeconds = fuelUsed / flowRateKgPerSecond;
            AbsoluteDate endDate = impulse.date().shiftedBy(durationSeconds);

            burns.add(new PlannedFiniteBurn(
                    impulse.date(),
                    endDate,
                    durationSeconds,
                    thrustN,
                    ispSeconds,
                    Vector3D.PLUS_I,
                    impulse.deltaVMagnitudeMps(),
                    massBefore,
                    massAfter,
                    fuelUsed
            ));
            massBefore = massAfter;
        }
        return burns;
    }

    private Optional<String> firstBurnOverlap(List<PlannedFiniteBurn> burns) {
        for (int i = 1; i < burns.size(); i++) {
            PlannedFiniteBurn previous = burns.get(i - 1);
            PlannedFiniteBurn current = burns.get(i);
            if (previous.endDate().compareTo(current.startDate()) > 0) {
                return Optional.of("Finite burns overlap. Increase thrust, relax the target, or solve a dedicated low-thrust schedule.");
            }
        }
        return Optional.empty();
    }

    private ConstantThrustManeuverPlan constantThrustPlan(ManeuverPlan seedImpulsePlan,
                                                          boolean feasible,
                                                          String status,
                                                          double thrustN,
                                                          double finalCoastSeconds,
                                                          OrbitShapeSnapshot afterNumerical,
                                                          List<PlannedFiniteBurn> burns) {
        double totalBurnDuration = burns.stream().mapToDouble(PlannedFiniteBurn::durationSeconds).sum();
        double massAfter = burns.isEmpty()
                ? seedImpulsePlan.massBeforeKg()
                : burns.get(burns.size() - 1).massAfterKg();
        return new ConstantThrustManeuverPlan(
                seedImpulsePlan.type(),
                feasible,
                status,
                seedImpulsePlan,
                thrustN,
                totalBurnDuration,
                finalCoastSeconds,
                afterNumerical,
                afterNumerical.semiMajorAxisM() - seedImpulsePlan.targetSemiMajorAxisM(),
                seedImpulsePlan.eccentricityLimit(),
                seedImpulsePlan.massBeforeKg(),
                massAfter,
                seedImpulsePlan.massBeforeKg() - massAfter,
                burns
        );
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

    private double resolveFinalCoast(double finalCoastSeconds) {
        return Math.max(0.0, finalCoastSeconds);
    }

    private double resolveNumericalMinStep(double numericalMinStepSeconds) {
        return numericalMinStepSeconds > 0.0 ? numericalMinStepSeconds : DEFAULT_NUMERICAL_MIN_STEP_SECONDS;
    }

    private double resolveNumericalMaxStep(double numericalMaxStepSeconds) {
        return numericalMaxStepSeconds > 0.0 ? numericalMaxStepSeconds : DEFAULT_NUMERICAL_MAX_STEP_SECONDS;
    }

    private double resolveNumericalPositionTolerance(double numericalPositionToleranceM) {
        return numericalPositionToleranceM > 0.0 ? numericalPositionToleranceM : DEFAULT_NUMERICAL_POSITION_TOLERANCE_M;
    }

    private NumericalForceModelConfig resolveForceModelConfig(NumericalForceModelConfig config) {
        return config == null ? NumericalForceModelConfig.none() : config;
    }

    private String nonBlankOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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

    private void validateConstantThrustAverageAltitudeRequest(ConstantThrustAverageAltitudeMaintenanceRequest request) {
        Objects.requireNonNull(request, "request");
        validateAverageAltitudeRequest(new AverageAltitudeMaintenanceRequest(
                request.baselinePropagator(),
                request.searchStartDate(),
                request.searchEndDate(),
                request.sampleStepSeconds(),
                request.targetMeanAltitudeM(),
                request.lowerBandMeanAltitudeM(),
                request.eccentricityLimit(),
                request.semiMajorAxisToleranceM(),
                request.ispSeconds(),
                request.spacecraftMassKg(),
                request.earthRadiusM()
        ));
        validateConstantThrust(request.thrustN(), request.numericalMinStepSeconds(), request.numericalMaxStepSeconds());
        validateForceModelConfig(resolveForceModelConfig(request.forceModelConfig()));
    }

    private void validateConstantThrustSemiMajorAxisRaiseRequest(
            ConstantThrustSemiMajorAxisRaiseEccentricityLimitedRequest request
    ) {
        Objects.requireNonNull(request, "request");
        validateSemiMajorAxisRaiseRequest(new SemiMajorAxisRaiseEccentricityLimitedRequest(
                request.baselinePropagator(),
                request.searchStartDate(),
                request.searchEndDate(),
                request.sampleStepSeconds(),
                request.deltaSemiMajorAxisM(),
                request.eccentricityLimit(),
                request.semiMajorAxisToleranceM(),
                request.ispSeconds(),
                request.spacecraftMassKg(),
                request.earthRadiusM()
        ));
        validateConstantThrust(request.thrustN(), request.numericalMinStepSeconds(), request.numericalMaxStepSeconds());
        validateForceModelConfig(resolveForceModelConfig(request.forceModelConfig()));
    }

    private void validateForceModelConfig(NumericalForceModelConfig config) {
        if (config.useEgm96Gravity()) {
            if (config.gravityDegree() < 0 || config.gravityOrder() < 0) {
                throw new IllegalArgumentException("gravityDegree and gravityOrder must not be negative");
            }
            if (config.gravityOrder() > config.gravityDegree()) {
                throw new IllegalArgumentException("gravityOrder must be less than or equal to gravityDegree");
            }
        }
        if (config.useJb2008Drag()) {
            if (config.dragCrossSectionM2() <= 0.0) {
                throw new IllegalArgumentException("dragCrossSectionM2 must be positive when JB2008 drag is enabled");
            }
            if (config.dragCoefficient() <= 0.0) {
                throw new IllegalArgumentException("dragCoefficient must be positive when JB2008 drag is enabled");
            }
        }
    }

    private void validateConstantThrust(double thrustN,
                                        double numericalMinStepSeconds,
                                        double numericalMaxStepSeconds) {
        if (thrustN <= 0.0) {
            throw new IllegalArgumentException("thrustN must be positive");
        }
        double minStep = resolveNumericalMinStep(numericalMinStepSeconds);
        double maxStep = resolveNumericalMaxStep(numericalMaxStepSeconds);
        if (maxStep < minStep) {
            throw new IllegalArgumentException("numericalMaxStepSeconds must be greater than or equal to numericalMinStepSeconds");
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
