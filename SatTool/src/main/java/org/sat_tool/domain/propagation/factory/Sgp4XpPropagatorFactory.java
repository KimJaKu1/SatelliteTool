package org.sat_tool.domain.propagation.factory;

import org.orekit.attitudes.AttitudeProvider;
import org.orekit.attitudes.FrameAlignedProvider;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Loads the optional SGP4-XP adapter without making it a compile-time dependency.
 */
public final class Sgp4XpPropagatorFactory {

    private static final List<String> PROPAGATOR_CLASSES = List.of(
            "com.odutils.ephem.USSFJnaTLEPropagator",
            "com.odutils.ephem.USSFJniTLEPropagator",
            "odutils.ephem.USSFJnaTLEPropagator",
            "odutils.ephem.USSFJniTLEPropagator"
    );

    private Sgp4XpPropagatorFactory() {
    }

    /**
     * Creates an SGP4-XP TLE propagator.
     *
     * Input data:
     * - tle is the parsed TLE to propagate, normally with ephemeris type 4.
     * - one supported aholinch/orekit-sgp4-xp adapter class must be on the classpath.
     * - USSF Sgp4Prop native binaries must be visible through PATH or LD_LIBRARY_PATH.
     *
     * @param tle parsed Orekit TLE object.
     * @return TLEPropagator backed by the SGP4-XP adapter.
     */
    public static TLEPropagator create(TLE tle) {
        Objects.requireNonNull(tle, "tle");

        Throwable lastFailure = null;
        for (String className : PROPAGATOR_CLASSES) {
            try {
                Class<?> propagatorClass = Class.forName(className);
                TLEPropagator selected = invokeStaticSelectExtrapolator(propagatorClass, tle);
                if (selected != null) {
                    return selected;
                }
                return instantiate(propagatorClass, tle);
            } catch (ReflectiveOperationException | LinkageError | ClassCastException | IllegalArgumentException e) {
                lastFailure = rootCause(e);
            }
        }

        throw new UnsupportedOperationException(
                "SGP4-XP requires aholinch/orekit-sgp4-xp on the runtime classpath and USSF Sgp4Prop binaries "
                        + "available through PATH or LD_LIBRARY_PATH. No compatible USSFJnaTLEPropagator or "
                        + "USSFJniTLEPropagator class could be loaded.",
                lastFailure
        );
    }

    private static TLEPropagator invokeStaticSelectExtrapolator(Class<?> propagatorClass, TLE tle)
            throws ReflectiveOperationException {
        try {
            Method method = propagatorClass.getMethod("selectExtrapolator", TLE.class);
            Object result = method.invoke(null, tle);
            return (TLEPropagator) result;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (InvocationTargetException e) {
            throw rethrowTarget(e);
        }
    }

    private static TLEPropagator instantiate(Class<?> propagatorClass, TLE tle)
            throws ReflectiveOperationException {
        Frame teme = FramesFactory.getTEME();
        AttitudeProvider attitudeProvider = FrameAlignedProvider.of(teme);
        Constructor<?> constructor = propagatorClass.getDeclaredConstructor(
                TLE.class,
                AttitudeProvider.class,
                double.class,
                Frame.class
        );
        constructor.setAccessible(true);
        Object result = constructor.newInstance(tle, attitudeProvider, Propagator.DEFAULT_MASS, teme);
        return (TLEPropagator) result;
    }

    private static ReflectiveOperationException rethrowTarget(InvocationTargetException e)
            throws ReflectiveOperationException {
        Throwable cause = e.getCause();
        if (cause instanceof ReflectiveOperationException reflectiveCause) {
            throw reflectiveCause;
        }
        if (cause instanceof RuntimeException runtimeCause) {
            throw runtimeCause;
        }
        if (cause instanceof Error errorCause) {
            throw errorCause;
        }
        throw e;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
