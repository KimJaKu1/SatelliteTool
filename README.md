# SatTool

> Orekit 기반 위성 궤도 전파, 좌표 변환, 임무 이벤트 산출물 생성 도구

<div align="left">
  <img src="https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-4.0.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white"/>
  <img src="https://img.shields.io/badge/Gradle-Build-02303A?style=for-the-badge&logo=gradle&logoColor=white"/>
  <img src="https://img.shields.io/badge/Orekit-13.0.3-1f6feb?style=for-the-badge"/>
</div>

## Project Overview

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

## Tech Stack

### Backend

<div align="left">
  <img src="https://img.shields.io/badge/Java-21-white?style=for-the-badge&logo=openjdk&logoColor=000000"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-4.0.0-white?style=for-the-badge&logo=springboot&logoColor=6DB33F"/>
  <img src="https://img.shields.io/badge/Gradle-white?style=for-the-badge&logo=gradle&logoColor=02303A"/>
</div>

### Orbit & Math

<div align="left">
  <img src="https://img.shields.io/badge/Orekit-13.0.3-white?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/Hipparchus-white?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/CCSDS%20OEM-white?style=for-the-badge"/>
</div>

### Utilities

<div align="left">
  <img src="https://img.shields.io/badge/Lombok-white?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/MapStruct-white?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/FTP%2FSFTP-white?style=for-the-badge"/>
</div>

## Main Features

| 기능 | 설명 |
| --- | --- |
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
