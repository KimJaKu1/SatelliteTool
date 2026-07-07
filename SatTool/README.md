# SatTool

> Orekit 기반 위성 궤도 전파, 좌표 변환, 임무 이벤트 산출물 생성 도구

<div align="left">
  <img src="https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-4.0.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white"/>
  <img src="https://img.shields.io/badge/Gradle-Build-02303A?style=for-the-badge&logo=gradle&logoColor=white"/>
<<<<<<< HEAD
  <img src="https://img.shields.io/badge/Orekit-13.0.3-1f6feb?style=for-the-badge"/>
=======
  <img src="https://img.shields.io/badge/Orekit-13.1.5-1f6feb?style=for-the-badge"/>
>>>>>>> origin/main
</div>

## Project Overview

<<<<<<< HEAD
SatTool은 위성 TLE 데이터를 기반으로 궤도를 전파하고, 위성 운용 및 분석에 필요한 이벤트 데이터를 생성하기 위한 Spring Boot 프로젝트입니다.

Orekit을 사용하여 ECI/ECEF/TOD/TEME 좌표계의 위성 위치와 속도를 계산하고, 지상국 접촉 시간, 안테나 추적각, 승교점/강교점, 식(Eclipse), 촬영 가능 구간 및 Footprint 산출을 지원합니다.

현재 프로젝트는 REST API보다는 도메인 서비스와 테스트 기반 실행 예제를 중심으로 구성되어 있으며, 산출물은 텍스트 파일 또는 CCSDS OEM 형식으로 생성됩니다.

## Project Objectives

1. TLE 기반 위성 궤도 전파 기능 구현
2. ECI, ECEF, TOD, TEME 좌표계별 Ephemeris 생성
3. 지상국 기준 Contact Schedule 및 Antenna Tracking 산출
4. Nodal Crossing, Eclipse, Capture Opportunity 이벤트 계산
5. Orekit 데이터 초기화 및 갱신 자동화
6. FTP/SFTP 기반 원격 파일 송수신 유틸리티 제공
=======
SatTool은 위성 TLE/OMM 데이터를 기반으로 궤도를 전파하고, 위성 운용 및 분석에 필요한 이벤트 데이터를 생성하는 Spring Boot 프로젝트다.

Orekit을 사용해 ECI/ECEF 좌표계의 위성 위치·속도를 계산하고, 지상국 교신 스케줄, 안테나 추적각, 승·강교점, 식(Eclipse), 촬영 기회 및 footprint, 궤도 기동 계획 산출을 지원한다. 산출물은 텍스트/CCSDS OEM 파일 또는 REST API 응답으로 제공된다.
>>>>>>> origin/main

## Tech Stack

### Backend

<div align="left">
  <img src="https://img.shields.io/badge/Java-21-white?style=for-the-badge&logo=openjdk&logoColor=000000"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-4.0.0-white?style=for-the-badge&logo=springboot&logoColor=6DB33F"/>
  <img src="https://img.shields.io/badge/Gradle-white?style=for-the-badge&logo=gradle&logoColor=02303A"/>
</div>

### Orbit & Math

<div align="left">
<<<<<<< HEAD
  <img src="https://img.shields.io/badge/Orekit-13.0.3-white?style=for-the-badge"/>
=======
  <img src="https://img.shields.io/badge/Orekit-13.1.5-white?style=for-the-badge"/>
>>>>>>> origin/main
  <img src="https://img.shields.io/badge/Hipparchus-white?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/CCSDS%20OEM-white?style=for-the-badge"/>
</div>

<<<<<<< HEAD
### Utilities

<div align="left">
  <img src="https://img.shields.io/badge/Lombok-white?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/MapStruct-white?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/FTP%2FSFTP-white?style=for-the-badge"/>
</div>

=======
>>>>>>> origin/main
## Main Features

| 기능 | 설명 |
| --- | --- |
<<<<<<< HEAD
| Orekit 초기화 | `orekit-data` 디렉터리 또는 zip 리소스를 통해 Orekit 데이터 로드 |
| 궤도 전파 | TLE와 SGP4 기반 위성 위치/속도 계산 |
| 좌표 변환 | ECI, ECEF, TOD, TEME 좌표계 Ephemeris 생성 |
| OEM 생성 | CCSDS OEM KVN 형식의 Ephemeris 파일 출력 |
| Contact Schedule | 지상국 AOS/LOS, 최대 고각, 통신 지속시간 산출 |
| Antenna Tracking | 지상국 기준 Azimuth/Elevation 추적 테이블 생성 |
| Nodal Crossing | 궤도별 Ascending/Descending Node 및 위도 극값 시각 계산 |
| Eclipse Report | Penumbra/Umbra 진입 및 이탈 시각 산출 |
| Capture Opportunity | 목표 지점 촬영 가능 구간과 Footprint 계산 |
| File Transfer | FTP/SFTP 다운로드, 업로드, 삭제, 이동, 복사 기능 제공 |

## Project Structure

```text
SatTool
├── src/main/java/org/sat_tool
│   ├── SatToolApplication.java
│   ├── domain
│   │   ├── common          # Orekit 초기화, 시간 변환, 공통 모델
│   │   ├── propagation     # 궤도 전파 및 Ephemeris/OEM 생성
│   │   ├── coordinate      # 좌표 변환 및 지상국 기준 좌표 계산
│   │   ├── event           # AT, CS, NC, Eclipse, Capture 이벤트 산출
│   │   └── visuallizse     # FOV, Footprint 관련 모델
│   └── infra               # 설정, 검증, 파일, FTP/SFTP 유틸리티
├── src/main/resources
│   ├── application.yml
│   ├── orekit-data
│   └── orekit-data.zip
├── src/test/java/org/example/sattool
├── gradle
├── build.gradle
└── settings.gradle
```

## Getting Started

### Requirements

- JDK 21
- Gradle Wrapper
- Orekit data
- Windows에서 Orekit 데이터 갱신 시 Git Bash

### Configuration

`src/main/resources/application.yml`

```yaml
orekit:
  data-path: src/main/resources/orekit-data

orekit-update:
  bash: "C:/Program Files/Git/bin/bash.exe"

spring:
  application:
    name: SatTool
```

### Run Tests

```bash
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew test
```

### Run Application

```bash
./gradlew bootRun
```

## Example Workflows

| Test Class | 역할 |
| --- | --- |
| `Propagetion` | ECI/ECEF Ephemeris 및 OEM 파일 생성 예제 |
| `GenerateNCEvent` | Antenna Tracking, Contact Schedule, Nodal Crossing, Eclipse 파일 생성 예제 |
| `footprintTest_20250915` | 촬영 가능 구간 및 Footprint 계산 예제 |
| `PatchData` | Orekit 데이터 업데이트 스크립트 실행 예제 |

## Output Examples

생성되는 주요 산출물은 다음과 같습니다.

- 위성 위치/속도 Ephemeris text file
- CCSDS OEM file
- Antenna Tracking table
- Contact Schedule file
- Nodal Crossing report
- Eclipse report
- Capture Opportunity schedule

## Future

- REST API 또는 CLI 기반 실행 진입점 추가
- 산출물별 입력/출력 스키마 문서화
- 이벤트 경계 보간 로직 테스트 강화
- 생성 파일명과 출력 디렉터리 규칙 표준화
- 촬영 기하 및 Footprint 결과 시각화 기능 추가
=======
| Orekit 초기화 | `orekit-data` 디렉터리 또는 classpath의 `orekit-data.zip`에서 데이터 로드 |
| 궤도 전파 | TLE(SGP4/SGP4-XP) 및 OMM(DSST) 기반 위성 위치/속도 계산 |
| 좌표 변환 | ECI/ECEF Ephemeris 생성, LLA·topocentric 변환 |
| OEM/표 출력 | CCSDS OEM(KVN) 및 탭 구분 텍스트 Ephemeris 파일 출력 |
| Contact Schedule | 지상국 AOS/LOS, 최대 앙각, 지속시간 산출 (완전히 관측된 패스만 생성) |
| Antenna Tracking | 지상국 기준 Azimuth/Elevation 추적 시계열 생성 |
| Nodal Crossing | 승/강교점 통과 시각 계산 |
| Eclipse Report | Penumbra/Umbra 진입·이탈 시각 산출 |
| Capture Opportunity | 목표 지점 촬영 가능 구간과 footprint 계산 |
| Maneuver Planning | 목표 궤도 형상에 대한 임펄스 기동 계획 산출 |

## 요구 사항

- JDK 21 (Gradle 9.x는 JVM 17+ 필요)
- Orekit 데이터: `src/main/resources/orekit-data` (없으면 classpath의 `orekit-data.zip` 폴백)

## 빌드 / 테스트

```bash
./gradlew build          # 컴파일 + 테스트
./gradlew test           # 테스트만 (약 30초, 힙 4g)
./gradlew bootRun        # 서버 기동
```

네트워크 의존 테스트(`OrekitDataUpdateIntegrationTest`)는 `@Disabled`로 기본 제외 —
수동 실행 시 어노테이션을 제거하고 `OREKIT_UPDATE_BASH` 환경변수로 bash 경로를 지정한다.

## 패키지 구조와 배치 컨벤션

```
org.sat_tool
├── api          # REST 공통 (전역 예외 처리, 작업(Job) 관리)
├── config       # SatTool 전용 Spring 설정 (스레드풀 등)
├── domain       # 기능 도메인 — 하위에 model / service / worker / writer / api(dto)
│   ├── antenna        # 안테나 추적 (az/el 시계열)
│   ├── capture        # 촬영 기회 + footprint
│   ├── contact        # 교신 스케줄 (AOS/LOS)
│   ├── coordinate     # 좌표 변환 (LLA, topocentric)
│   ├── eclipse        # 식 진입/이탈
│   ├── maneuver       # 기동(임펄스) 계획
│   ├── nodalcrossing  # 승·강교점 통과
│   ├── propagation    # 궤도 전파 (SGP4/SGP4-XP/DSST), OEM/표 출력
│   └── common         # 공유 도메인 모델(Satellite, Station)과 수치 헬퍼
├── infra        # ★ 다른 프로젝트에서도 재사용 가능한 범용 코드만 (현재 비어 있음)
└── orekit       # SatTool에서 Orekit을 쓰기 위한 코드 (데이터 로딩/갱신, 시간 변환)
```

**배치 기준**: "다른 프로젝트에 그대로 가져갈 수 있는가?"가 `infra` 여부를 가른다.
SatTool 전용 설정은 `config`, Orekit 연동 코드는 `orekit`, 업무 개념은 `domain`.

## REST API (예시: 교신 스케줄)

장시간 계산은 작업 ID 기반 비동기로 처리한다.

```
POST /api/contact-schedules      # 202 Accepted + { jobId, statusUrl }
GET  /api/jobs/{jobId}           # RUNNING | COMPLETED(result 포함) | FAILED
```

입력 오류(잘못된 TLE, 시각 포맷)는 400 + 필드별 메시지로 즉시 반환된다.
다른 도메인의 API도 `domain/<기능>/api` + `dto` 구조로 동일하게 확장한다.

## 도메인 규칙

- **시간**: 모든 시각은 UTC. 문자열 포맷은 `uuuu-MM-dd HH:mm:ss.SSS` (`TimeConverter.TS_STD_MS`).
  Orekit `AbsoluteDate` ↔ `LocalDateTime` 변환은 `org.sat_tool.orekit.TimeConverter`(static 유틸)만 사용하고,
  Orekit 타입은 REST API 경계를 넘기지 않는다(DTO에서 문자열로 변환).
- **궤도 번호**: 교신 스케줄·식 보고서의 궤도 번호는 이벤트 일련번호가 아니라 실제 궤도 회전 번호다.
  AOS(또는 이벤트 시작) 시각과 궤도 주기로 계산한다(`OrbitNumbers`).
- **교신 스케줄 생성 조건**: AOS·LOS가 모두 분석 윈도우 내에서 관측된 완전한 패스만 이벤트로 생성한다.
  윈도우 경계에 걸쳐 시작 또는 종료가 관측되지 않은 패스는 생성하지 않는다.

## Future

- 다른 도메인(eclipse, capture, nodalcrossing 등)에도 REST API 계층 확장
- jpackage+React 또는 Electron+React+Spring Boot sidecar 중 데스크톱 배포 방식 결정
- 이벤트 경계 보간 로직 테스트 강화
>>>>>>> origin/main
