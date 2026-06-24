package org.sat_tool.domain.propagation.service;

import org.hipparchus.ode.ODEIntegrator;
import org.orekit.data.DataSource;
import org.orekit.files.ccsds.ndm.ParserBuilder;
import org.orekit.files.ccsds.ndm.odm.omm.Omm;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.semianalytical.dsst.DSSTPropagator;
import org.orekit.propagation.semianalytical.dsst.forces.DSSTForceModel;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEConstants;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.sat_tool.domain.propagation.factory.DsstPropagatorFactory;
import org.sat_tool.domain.propagation.factory.Sgp4XpPropagatorFactory;
import org.sat_tool.domain.propagation.factory.StandardTlePropagatorFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;

@DependsOn("orekitInitializer")
@Service
public class PropagatorService {

    /**
     * OMM에서 MEAN_ELEMENT_THEORY = DSST로 선언된 경우 필요한 입력 설정이다.
     *
     * @param integrator Orekit DSST 전파에 사용되는 ODE 적분기.
     * @param forceModels 전파기에 적용할 비어 있지 않은 DSST 힘 모델 집합.
     */
    public record DsstPropagationConfig(
            ODEIntegrator integrator,
            Collection<DSSTForceModel> forceModels
    ) {
        public DsstPropagationConfig {
            Objects.requireNonNull(integrator, "integrator");
            if (forceModels == null || forceModels.isEmpty()) {
                throw new IllegalArgumentException("forceModels must not be empty");
            }
        }
    }

    /**
     * OMM 텍스트를 파싱하고 MEAN_ELEMENT_THEORY에 따라 적절한 전파기를 생성한다.
     *
     * 입력 데이터:
     * - sourceName은 Orekit DataSource 이름으로만 사용되며 null 또는 공백일 수 있다.
     * - ommText는 Orekit이 파싱할 수 있는 유효한 OMM KVN/XML 메시지를 포함해야 한다.
     * - dsstConfig의 기본값은 null이다. DSST OMM 데이터는 설정된 오버로드를 사용하지 않으면 실패한다.
     *
     * @param sourceName 파서 진단용 논리적 소스 이름.
     * @param ommText 원본 OMM 메시지 텍스트.
     * @return OMM 평균요소 이론에 따라 선택된 전파기.
     */
    public Propagator createPropagatorFromOmmText(String sourceName, String ommText) {
        return createPropagatorFromOmmText(sourceName, ommText, null);
    }

    /**
     * OMM 텍스트를 파싱하고 DSST 설정을 포함하여 적절한 전파기를 생성한다.
     *
     * 입력 데이터:
     * - sourceName은 Orekit DataSource 이름으로만 사용되며 null 또는 공백일 수 있다.
     * - ommText는 Orekit이 파싱할 수 있는 유효한 OMM KVN/XML 메시지를 포함해야 한다.
     * - dsstConfig는 null일 수 있다. MEAN_ELEMENT_THEORY가 DSST로 해석되는 경우에만 non-null 값이 필요하다.
     *
     * @param sourceName 파서 진단용 논리적 소스 이름.
     * @param ommText 원본 OMM 메시지 텍스트.
     * @param dsstConfig DSST 전파에 사용할 적분기와 힘 모델 설정.
     * @return OMM 평균요소 이론에 따라 선택된 전파기.
     */
    public Propagator createPropagatorFromOmmText(String sourceName,
                                                  String ommText,
                                                  DsstPropagationConfig dsstConfig) {
        Objects.requireNonNull(ommText, "ommText");
        return createPropagatorFromOmmDataSource(toOmmTextDataSource(sourceName, ommText), dsstConfig);
    }

    /**
     * Orekit DataSource를 OMM으로 파싱하고 적절한 전파기를 생성한다.
     *
     * 입력 데이터:
     * - source는 Orekit DataSource opener를 통해 유효한 OMM 내용을 제공해야 한다.
     * - dsstConfig는 null일 수 있다.
     * - dsstConfig가 null이고 MEAN_ELEMENT_THEORY가 SGP4 또는 SGP4-XP로 해석되는 경우,
     *   해당 전파기는 DSST 힘 모델을 필요로 하지 않으므로 이 값은 무시된다.
     * - dsstConfig가 null이고 MEAN_ELEMENT_THEORY가 DSST로 해석되는 경우,
     *   DSST는 적분기와 힘 모델이 필요하므로 이 메서드는 UnsupportedOperationException을 발생시킨다.
     *
     * @param source OMM KVN/XML 데이터를 포함하는 Orekit DataSource.
     * @param dsstConfig DSST 전파에 사용할 적분기와 힘 모델 설정.
     * @return OMM 평균요소 이론에 따라 선택된 전파기.
     */
    public Propagator createPropagatorFromOmmDataSource(DataSource source, DsstPropagationConfig dsstConfig) {
        return createPropagatorFromOmm(parseOmm(source), dsstConfig);
    }

    /**
     * 파싱된 OMM 객체로부터 전파기를 생성한다.
     *
     * 입력 데이터:
     * - omm은 Orekit으로 이미 파싱되어 있어야 하며, MEAN_ELEMENT_THEORY가 포함된 metadata를 가져야 한다.
     * - SGP4/SDP4 OMM 데이터는 TLE로 변환한 뒤 Orekit TLE 전파기로 처리한다.
     * - SGP4-XP OMM 데이터는 TLE로 변환한 뒤 SGP4-XP 런타임 어댑터로 처리한다.
     * - DSST OMM 데이터는 전달된 DSST 적분기와 힘 모델을 사용한다.
     * - dsstConfig는 SGP4/SDP4 및 SGP4-XP에 대해서는 null일 수 있다.
     * - dsstConfig는 DSST에 대해서는 반드시 non-null이어야 하며,
     *   그렇지 않으면 이 메서드는 UnsupportedOperationException을 발생시킨다.
     *
     * @param omm 파싱된 Orekit OMM 객체.
     * @param dsstConfig DSST 전파에 사용할 적분기와 힘 모델 설정.
     * @return OMM 평균요소 이론에 따라 선택된 전파기.
     */
    public Propagator createPropagatorFromOmm(Omm omm, DsstPropagationConfig dsstConfig) {
        Objects.requireNonNull(omm, "omm");
        MeanElementTheory resolvedTheory = MeanElementTheory.from(omm);

        if (resolvedTheory == MeanElementTheory.SGP4) {
            TLE tle = omm.generateTLE();
            return createPropagatorFromTle(tle);
        }

        if (resolvedTheory == MeanElementTheory.SGP4_XP) {
            TLE tle = omm.generateTLE();
            return createSgp4XpPropagator(tle);
        }

        if (resolvedTheory == MeanElementTheory.DSST) {
            if (dsstConfig == null) {
                throw new UnsupportedOperationException(
                        "DSST OMM requires a DsstPropagationConfig. "
                                + "Pass a non-null DsstPropagationConfig with an integrator and force models."
                );
            }
            return createDsstPropagator(omm, dsstConfig);
        }

        String theory = MeanElementTheory.rawValue(omm);
        throw new UnsupportedOperationException("Unsupported OMM MEAN_ELEMENT_THEORY: " + theory);
    }

    /**
     * Orekit을 통해 OMM 메시지를 파싱한다.
     *
     * 입력 데이터:
     * - source는 읽을 수 있는 OMM KVN/XML 메시지를 제공해야 한다.
     * - 파서는 기본값으로 TLEConstants.MU와 Propagator.DEFAULT_MASS를 사용한다.
     *
     * @param source OMM 데이터를 포함하는 Orekit DataSource.
     * @return 파싱된 Orekit OMM 객체.
     */
    public Omm parseOmm(DataSource source) {
        Objects.requireNonNull(source, "source");
        return new ParserBuilder()
                .withMu(TLEConstants.MU)
                .withDefaultMass(Propagator.DEFAULT_MASS)
                .buildOmmParser()
                .parseMessage(source);
    }

    /**
     * TLE 기반 전파기를 생성한다.
     *
     * 입력 데이터:
     * - TLE line 1과 line 2는 이미 Orekit {@link TLE} 객체로 파싱되어 있어야 한다.
     * - {@link TLE#getEphemerisType()} 값이 4이면 해당 TLE는 SGP4-XP로 처리한다.
     * - 그 외의 TLE는 Orekit의 표준 TLE 외삽기 선택 로직으로 처리한다.
     *
     * @param tle 파싱된 Orekit TLE 객체.
     * @return ephemeris type이 4이면 SGP4-XP 전파기, 그렇지 않으면 Orekit TLE 전파기.
     */
    public TLEPropagator createPropagatorFromTle(TLE tle) {
        Objects.requireNonNull(tle, "tle");
        if (tle.getEphemerisType() == 4) {
            return createSgp4XpPropagator(tle);
        }
        return StandardTlePropagatorFactory.create(tle);
    }

    /**
     * OMM 상태 데이터로부터 DSST 전파기를 생성한다.
     *
     * 입력 데이터:
     * - omm은 Orekit의 OMM spacecraft-state 생성 기능과 호환되어야 한다.
     * - config는 ODE 적분기와 최소 1개 이상의 DSST 힘 모델을 포함해야 한다.
     *
     * @param omm 파싱된 Orekit OMM 객체.
     * @param config DSST 전파 설정.
     * @return 평균 초기 상태로 설정된 DSSTPropagator.
     */
    public DSSTPropagator createDsstPropagator(Omm omm, DsstPropagationConfig config) {
        return DsstPropagatorFactory.create(omm, config);
    }

    /**
     * 선택적 aholinch/orekit-sgp4-xp 어댑터를 통해 SGP4-XP 전파기를 생성한다.
     *
     * 입력 데이터:
     * - tle는 일반적으로 ephemeris type 4인 SGP4-XP 데이터를 나타내야 한다.
     * - 호환 가능한 어댑터 jar와 USSF 네이티브 Sgp4Prop 바이너리가 런타임에 사용 가능해야 한다.
     *
     * @param tle 파싱된 Orekit TLE 객체.
     * @return SGP4-XP와 호환되는 TLEPropagator.
     */
    public TLEPropagator createSgp4XpPropagator(TLE tle) {
        return Sgp4XpPropagatorFactory.create(tle);
    }

    /**
     * 원본 OMM 텍스트를 Orekit DataSource로 래핑한다.
     *
     * @param sourceName 선택적 소스 이름. 공백이면 기본값으로 omm.kvn을 사용한다.
     * @param ommText 원본 OMM 메시지 텍스트.
     * @return UTF-8 OMM 텍스트 바이트를 기반으로 하는 DataSource.
     */
    private DataSource toOmmTextDataSource(String sourceName, String ommText) {
        String resolvedName = (sourceName == null || sourceName.isBlank()) ? "omm.kvn" : sourceName;
        return new DataSource(
                resolvedName,
                () -> new ByteArrayInputStream(ommText.getBytes(StandardCharsets.UTF_8))
        );
    }
}
