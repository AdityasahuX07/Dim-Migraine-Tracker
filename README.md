# Dim

A modern, privacy-focused Android application built with Jetpack Compose for tracking migraines and headaches. Dim helps users log episodes, monitor pain locations, identify triggers, correlate with health metrics, and generate doctor-ready reports.

## Features

- **Comprehensive Episode Logging**: Track migraine severity, onset, duration, and ongoing sessions.
- **Visual Pain Mapping**: Interactive head and back pain mapping.
- **Trigger & Symptom Tracking**: Log sensory sensitivities, aura, nausea, hydration, caffeine, meals, sleep, and weather factors.
- **Health Connect Integration**: Correlate episodes with passive health data including sleep stages, resting heart rate, and step count.
- **AI Insights**: Intelligent pattern detection and insights powered by Gemini.
- **Doctor Reports**: Export detailed episode summaries and clinical PDF reports to share with healthcare providers.
- **Local-First & Private**: Data stays on your device with encrypted backup/restore support.

## Tech Stack

- **UI**: Android Jetpack Compose (100% Kotlin)
- **Architecture**: Single-activity, modern Android architecture
- **Persistence**: Room Database (SQLite) with Kotlin Coroutines & Flow
- **Health**: Android Health Connect API
- **Build System**: Gradle Kotlin DSL (`.gradle.kts`)

## Getting Started

### Prerequisites

- Android Studio Ladybug | 2024.2.1 or newer
- JDK 17
- Android SDK 36 (minSdk 26, targetSdk 35)

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/AdityasahuX07/Dim.git
   cd Dim
   ```

2. Open the project in Android Studio or build using the Gradle wrapper:
   ```bash
   ./gradlew assembleDebug
   ```

## License

This project is licensed under the terms of the license included in the repository.
