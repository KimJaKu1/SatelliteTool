# SGP4, SGP4-XP, DSST Concept and Algorithm Notes

This document summarizes SGP4, SGP4-XP, and DSST from an orbit propagation
algorithm perspective. It focuses on the meaning of the input elements, the
perturbation treatment, and when each model is appropriate.

## Summary

| Algorithm | Type | Main input | Propagation concept | Typical use |
| --- | --- | --- | --- | --- |
| SGP4 | Analytical TLE propagator | TLE mean elements, BSTAR | Fast propagation through pre-derived corrections | Public TLE catalog propagation, large satellite sets |
| SGP4-XP | Extended SGP4 variant | Type 4 TLE, SGP4-XP mean elements, AGOM | SGP4-family propagation with extended perturbations | MEO, GEO, HEO, longer-term TLE prediction |
| DSST | Semi-analytical propagator | Initial state, force models, integrator | Integrates mean elements and restores short-periodic terms when needed | Long-term trends, repeated analysis, mean-orbit interpretation |

## SGP4

SGP4 means `Simplified General Perturbations 4`. It is the standard analytical
propagator used with TLE data.

The most important point is that TLE elements are not ordinary osculating
Keplerian elements. They are mean elements fitted for use with the SGP4 model.
If the same TLE values are interpreted as a normal Keplerian state and inserted
directly into an HPOP or numerical propagator, the propagated orbit will not
match the TLE/SGP4 result.

SGP4 is fast because its perturbation behavior is embedded in closed-form or
semi-closed-form corrections rather than evaluated as detailed physical force
models at each integration step.

### SGP4 Algorithm Flow

1. Parse the TLE.
   The propagator reads epoch, inclination, RAAN, eccentricity, argument of
   perigee, mean anomaly, mean motion, BSTAR, and operational identifiers such
   as revolution number.

2. Interpret BSTAR.
   `BSTAR` is not a direct physical Cd, area, or mass value. It is a fitted
   SGP4 model parameter. In LEO it is usually read as a drag-like term, but it
   should not be treated as a conventional drag coefficient.

3. Recover internal mean motion and semi-major-axis-like quantities.
   The TLE mean motion is adjusted using SGP4 constants and Earth oblateness
   terms. J2 effects are already embedded in this interpretation.

4. Select near-Earth or deep-space branch.
   In the SGP4/SDP4 family, `deep-space` does not mean interplanetary deep
   space. It generally means an Earth-orbiting object with an orbital period of
   at least 225 minutes. In a circular-orbit approximation, that is roughly
   above 5,878 km altitude. This includes MEO, GPS-like orbits, GEO, Molniya,
   and some HEO cases.

5. Apply secular corrections.
   These are slowly accumulating effects such as RAAN drift, argument of
   perigee drift, mean anomaly change, and drag-driven mean-motion decay.

6. Apply periodic corrections.
   SGP4 also applies shorter-period corrections within the orbit. Therefore,
   SGP4 is not simply Keplerian propagation of mean elements.

7. Produce position and velocity.
   The output is usually interpreted in the TEME frame. Further frame
   conversion is required for ECEF, geodetic coordinates, azimuth/elevation, or
   ground-station visibility.

### SGP4 Limitations

- Accuracy degrades as the propagation date moves away from the TLE epoch.
- TLE values are model-fit values, not direct physical parameters.
- Maneuvers, attitude changes, changing Cd/Cr/area, and detailed spacecraft
  geometry are not modeled directly.
- For high-altitude and long-period Earth orbits, third-body gravity, solar
  radiation pressure, and resonance effects become more important than the
  classical SGP4 simplifications can fully represent.

## SGP4-XP

SGP4-XP is an `extended perturbations` version of the SGP4 family. Public
descriptions indicate that it keeps the general TLE/GP propagation concept but
uses improved perturbation modeling.

The full SGP4-XP algorithm is not publicly documented at source-code level in
the same way as classical SGP4. Operational implementations are provided through
USSF Astro Standards / Sgp4Prop binaries. Java integrations typically call those
binaries through JNA or JNI wrappers.

SGP4-XP TLEs are commonly identified by `ephemeris type = 4`, also called Type 4
TLEs.

### Extended Perturbations in SGP4-XP

Publicly described SGP4-XP improvements include:

- improved lunar perturbation modeling;
- additional resonance modeling for different orbit regimes;
- solar radiation pressure, or SRP, modeling for all orbit regimes;
- J5 zonal term in the geopotential model;
- EGM-96 terms replacing legacy WGS-72 terms;
- Jacchia-70 atmospheric density model replacing the older static atmosphere
  model;
- AGOM, an SRP-related term, in addition to BSTAR.

`AGOM` is commonly described as an area-times-gamma-over-mass style parameter.
The practical interpretation is that SGP4-XP can represent drag and solar
radiation pressure more distinctly than classical SGP4, where BSTAR carries too
much of the fitted non-conservative behavior.

### Meaning of High-Altitude or Long-Period

In this context, high-altitude or long-period does not mean a deep-space probe
outside Earth's orbital environment. It means long-period Earth orbit.

Examples include:

- MEO;
- GPS-like orbits;
- GEO;
- Molniya;
- HEO.

GEO is a representative case where SGP4-XP can be valuable. Atmospheric drag is
small there, while lunar/solar gravity, solar radiation pressure, and resonance
effects are relatively more important. GEO also has a 1:1 resonance with Earth's
rotation.

In LEO, atmospheric density uncertainty often dominates the error budget, so the
improvement from SGP4-XP may be smaller than in MEO/GEO.

## DSST

DSST means `Draper Semi-analytical Satellite Theory`. It is not a TLE-only
model. It is a semi-analytical propagator that separates the orbital motion into
mean behavior and short-periodic behavior.

The core idea is:

```text
osculating orbit = mean orbit + short-periodic terms
```

DSST does not directly integrate Cartesian position and velocity at every step
in the same way as HPOP. Instead, it integrates mean orbital elements and
computes short-periodic corrections when an osculating state is needed.

### DSST Algorithm Flow

1. Receive the initial state.
   The initial state can be mean or osculating. If the input is osculating,
   DSST must compute a DSST-consistent mean state using the configured force
   models.

2. Configure force models.
   DSST force models are not merely instantaneous acceleration providers. They
   describe how a physical perturbation contributes to mean element rates and
   short-periodic corrections.

3. Integrate mean elements.
   DSST builds differential equations for the mean orbital elements and
   integrates them using a numerical ODE integrator.

4. Compute short-periodic terms.
   If the requested output is mean, the propagated mean state can be returned.
   If the requested output is osculating, DSST adds short-periodic terms to the
   mean state.

5. Use for long-term analysis.
   Because short-periodic motion is separated from mean motion, DSST is useful
   for long-term trends and repeated propagation studies.

## DSST vs HPOP

| Aspect | HPOP / numerical propagation | DSST |
| --- | --- | --- |
| State concept | Usually Cartesian position/velocity or osculating orbit | Mean elements with optional short-periodic reconstruction |
| Force model role | Computes instantaneous acceleration | Computes mean element rates and short-periodic corrections |
| Integration target | Position/velocity equations of motion | Mean orbital-element equations |
| Strength | Precision events, maneuvers, detailed force modeling | Long-term trends and efficient repeated analysis |
| Cost | Higher | Often lower for long-duration studies |

In short:

```text
HPOP force model = acceleration acting now
DSST force model = contribution to mean dynamics and short-periodic mapping
```

## Practical Selection

- Use SGP4 when the input is a public TLE and many objects must be propagated
  quickly.
- Use SGP4-XP when Type 4 TLEs or SGP4-XP mean elements are available and
  improved MEO/GEO/HEO behavior is needed.
- Use DSST when long-term mean-orbit behavior and efficient repeated analysis
  are more important than high-fidelity step-by-step event timing.
- Use HPOP or a numerical propagator when the input is a precise state and the
  analysis requires detailed gravity, drag, SRP, third-body effects, maneuvers,
  attitude, geometry, or precise event timing.

## Common Pitfalls

- `SGP4 deep-space` means long-period Earth orbit, not interplanetary deep
  space.
- TLE mean elements are not ordinary osculating Keplerian elements.
- SGP4-XP improves perturbation modeling, but its complete internal algorithm is
  not fully available as public source.
- DSST uses numerical integration, but it is not the same as HPOP. DSST
  integrates mean elements, while HPOP directly integrates the physical equations
  of motion.
- A DSST force model and an HPOP force model may represent the same physical
  effect, but they do not play the same mathematical role.

## References

- Orekit TLEPropagator API: https://www.orekit.org/site-orekit-latest/apidocs/org/orekit/propagation/analytical/tle/TLEPropagator.html
- Orekit DSSTPropagator API: https://www.orekit.org/site-orekit-13.1/apidocs/org/orekit/propagation/semianalytical/dsst/DSSTPropagator.html
- Orekit propagation package: https://orekit.org/static/apidocs/org/orekit/propagation/package-summary.html
- AMOS SGP4-XP paper: https://ai-solutions.com/wp-content/uploads/2023/01/1.AMOS_2022_SGP4XP.pdf
- AMOS SGP4-XP abstract: https://amostech.space/year/2022/assessing-performance-characteristics-of-the-sgp4-xp-propagation-algorithm/
- aholinch/orekit-sgp4-xp: https://github.com/aholinch/orekit-sgp4-xp
