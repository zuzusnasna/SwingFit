# ⛳ SwingFit

> **AI 기반 골프 스윙 영상 분석 Android 애플리케이션**

한신대학교 **캡스톤디자인 팀 프로젝트**로 개발한 Android 기반 골프 스윙 분석 애플리케이션입니다.

스마트폰으로 촬영한 골프 스윙 영상을 입력하면 TensorFlow Lite 기반 객체 탐지 모델을 이용해 골프공을 검출하고, 프레임별 위치를 추적하여 **공의 이동 궤적, 임팩트 시점, 초기 속도, 발사각, 예상 캐리 거리**를 분석합니다.

---

## 📌 프로젝트 소개

골프 스윙을 분석하기 위해서는 전문 장비나 골프 시뮬레이터가 필요한 경우가 많습니다.

SwingFit은 스마트폰으로 촬영한 영상과 AI 기술을 활용하여 골프공의 움직임을 분석하고 주요 비행 데이터를 시각적으로 제공하는 것을 목표로 개발했습니다.

사용자가 골프 스윙 영상을 선택하고 공이 위치한 영역(ROI)을 지정하면 영상 프레임을 분석하여 골프공을 검출하고 이동 경로를 추적합니다. 이후 임팩트 시점을 탐색하고 공의 비행 데이터를 계산하여 영상 위에 표시합니다.

---

## 🎯 주요 기능

### 1. 골프 스윙 영상 선택
- 스마트폰에 저장된 골프 스윙 영상 선택
- Media3 ExoPlayer를 이용한 영상 재생
- 영상 재생과 분석 결과를 하나의 화면에서 확인

### 2. ROI(Region of Interest) 설정
- 사용자가 화면을 드래그하여 분석 영역 직접 지정
- 정규화 좌표 기반 ROI 관리
- 골프공이 존재할 가능성이 높은 영역 중심으로 분석

### 3. AI 기반 골프공 검출
TensorFlow Lite 모델을 Android 기기에서 실행하여 영상 프레임의 골프공을 탐지합니다.

- `ball-fp16.tflite`
- `ball-int8.tflite`
- 640 × 640 입력 기반 분석
- Confidence Threshold 기반 Detection
- GPU Delegate 지원
- GPU 사용이 불가능하거나 실패할 경우 CPU로 자동 전환

### 4. 골프공 위치 추적
각 프레임에서 검출된 골프공의 위치를 저장하여 이동 경로를 구성합니다.

- Timestamp
- Confidence
- Center 좌표
- Bounding Box
- 이동 궤적(Trail)

### 5. 임팩트 시점 탐색
골프공의 검출 상태와 위치 변화를 분석하여 임팩트 시점을 추정합니다.

넓은 시간 간격으로 후보 구간을 찾은 뒤 해당 구간을 세밀하게 재탐색하는 방식으로 임팩트 시점을 보정합니다.

```text
Coarse Search
      ↓
Impact Candidate
      ↓
Fine Search
      ↓
Refined Impact Time
```

### 6. 비행 데이터 분석
공의 검출 및 추적 데이터를 이용하여 다음 데이터를 추정합니다.

- 초기 속도
- 발사각
- 예상 캐리 거리
- 예상 비행 궤적

### 7. 분석 결과 시각화
Android Canvas 기반 `OverlayView`를 이용하여 분석 결과를 영상 위에 표시합니다.

- ROI 영역
- 현재 골프공 위치
- 골프공 이동 궤적
- 예상 비행 궤적
- 초기 속도
- 발사각
- 예상 캐리 거리

---

## 🏗 시스템 구조

```text
MainActivity
    │
    ├── ExoPlayer ───── 영상 재생
    │
    ├── OverlayView ─── 분석 결과 시각화
    │
    └── BallAnalyzer ── 영상 분석
            │
            └── TensorFlow Lite
                    └── 골프공 Detection
```

---

## 🔄 분석 처리 과정

```text
골프 스윙 영상 선택
        ↓
영상 메타데이터 확인
        ↓
ROI 설정
        ↓
영상 프레임 추출
        ↓
640 × 640 전처리
        ↓
TensorFlow Lite 추론
        ↓
골프공 Detection
        ↓
프레임별 위치 Tracking
        ↓
임팩트 시점 탐색
        ↓
공의 비행 데이터 계산
        ↓
속도 / 발사각 / 캐리거리 추정
        ↓
Canvas Overlay로 결과 표시
```

---

## 🛠 기술 스택

### Android
- Kotlin
- Android SDK
- AndroidX
- Material
- ConstraintLayout
- Gradle Kotlin DSL

### AI / Computer Vision
- TensorFlow Lite
- TensorFlow Lite GPU Delegate
- XNNPACK
- FP16 / INT8 Model

### Video
- Media3 ExoPlayer
- MediaMetadataRetriever
- CameraX

### Development
- Android Studio
- Git
- GitHub

---

## 📂 주요 코드 구조

```text
app/src/main
├── assets
│   ├── ball-fp16.tflite
│   └── ball-int8.tflite
│
└── java/com/example/swingfit
    ├── MainActivity.kt
    ├── BallAnalyzer.kt
    ├── OverlayView.kt
    ├── FastFrameDecoder.kt
    ├── SimpleVideoReader.kt
    └── model.kt
```

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 영상 선택, 재생, 분석 실행 및 전체 흐름 제어 |
| `BallAnalyzer.kt` | 영상 프레임 분석, AI 추론, 공 추적, 임팩트 및 비행 데이터 계산 |
| `OverlayView.kt` | ROI, 공 위치, 이동 궤적, 분석 결과 시각화 |
| `model.kt` | Detection, RoiConfig, FlightData 등의 데이터 모델 정의 |
| `FastFrameDecoder.kt` | 영상 프레임을 빠르게 디코딩하기 위한 기능 |
| `SimpleVideoReader.kt` | 영상 프레임 접근 및 처리 지원 |

---

## ⚡ 성능 최적화

모바일 환경에서 AI 영상 분석을 수행하기 위해 다음과 같은 최적화를 적용했습니다.

- TensorFlow Lite GPU Delegate 활용
- GPU 미지원 또는 초기화 실패 시 CPU 자동 전환
- XNNPACK 기반 CPU 추론 최적화
- Coroutine을 활용한 비동기 영상 분석
- ROI 기반 분석 범위 제한
- 영상 프레임을 필요한 구간 중심으로 단계적으로 탐색

---

## 🎓 프로젝트 정보

| 항목 | 내용 |
|---|---|
| 프로젝트명 | SwingFit |
| 프로젝트 유형 | 한신대학교 캡스톤디자인 팀 프로젝트 |
| 플랫폼 | Android |
| 개발 언어 | Kotlin |
| 주요 기술 | TensorFlow Lite, Computer Vision, Video Processing |

---

## 👥 프로젝트 형태

한신대학교 캡스톤디자인에서 진행한 **팀 프로젝트**로, Android 애플리케이션 개발과 AI 기반 골프공 영상 분석 기능을 중심으로 구현했습니다.

---

## 📄 License

This project was developed as a university capstone design team project.
