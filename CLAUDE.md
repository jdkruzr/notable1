# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Building the App
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build and install debug to connected device
./gradlew installDebug

# Clean build artifacts
./gradlew clean
```

### Testing
```bash
# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run specific test class
./gradlew test --tests="com.ethran.notable.ExampleUnitTest"
```

### Development Setup
- Edit `DEBUG_STORE_FILE` in `/app/gradle.properties` to point to your local keystore file (typically in `.android` directory)
- For BOOX device debugging, enable developer mode following the device-specific guide
- Use Android Studio with Kotlin plugin for development

## Core Architecture

### Application Structure
Notable is an Android note-taking app optimized for e-ink devices, built with:
- **Jetpack Compose** for modern UI
- **Room Database** with comprehensive entity relationships
- **Onyx SDK** integration for e-ink device optimization
- **MVVM architecture** with LiveData and state management

### Key Components

**Core Application Classes:**
- `NotableApp.kt` - Application entry point, initializes Onyx SDK
- `MainActivity.kt` - Primary activity with full-screen UI and navigation
- `FloatingEditorActivity.kt` - Overlay editing functionality
- `AppRepository.kt` - Central data access layer aggregating all repositories

**Drawing Engine:**
- `DrawCanvas.kt` - Core drawing engine with stylus input handling
- `EditorControlTower.kt` - Coordinates editor interactions and state
- `PageDataManager.kt` - Manages page data lifecycle and memory

**Database Entities:**
- `Notebook.kt`, `Page.kt`, `Stroke.kt`, `Image.kt`, `Folder.kt`, `Kv.kt`
- Room database with migration support (versions 19-31)
- Foreign key relationships and cascade operations

**UI Components:**
- `EditorView.kt` - Main editor interface
- `HomeView.kt` - Library and folder browsing
- `Toolbar.kt` - Main editing toolbar
- `EditorSurface.kt` - Drawing surface component

### Key Utilities
- `utils/draw.kt` - Core drawing operations and rendering
- `utils/pen.kt` - Pen handling and stroke processing
- `utils/einkHelper.kt` - E-ink display optimizations
- `utils/export.kt` - Content export functionality

### E-ink Integration
- Deep integration with Onyx SDK for stylus input and display optimization
- Custom drawing engine optimized for e-ink displays
- Gesture handling specifically designed for e-ink responsiveness

## Package Structure
- `classes/` - Core business logic and data management
- `components/` - Reusable UI components
- `db/` - Database entities and migrations
- `utils/` - Utility functions and helpers
- `views/` - Main application views and navigation

## Development Notes
- The app targets e-ink devices (primarily BOOX devices)
- Uses custom drawing engine for optimal performance on e-ink displays
- Implements comprehensive undo/redo functionality
- Supports notebook organization with folders and pages
- Includes image embedding and selection tools