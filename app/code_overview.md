# Notable Code Overview

This is android app for writing notes for e-ink devices like Boox
Target Android SDK is 33


## Core Application Classes
- **MainActivity.kt**: Main entry point for the Android application
- **NotableApp.kt**: Application class that initializes core components
- **EditorControlTower.kt**: Manages editor state and interactions
- **LambdaService.kt**: Handles AWS API Gateway service integration
- **SnackBar.kt**: Manages snackbar notifications
- **PageView.kt**: Handles page content rendering, stroke management, scroll position, and caching of page previews
- **DrawCanvas.kt**: Manages the drawing surface, handles touch input, and coordinates drawing tool operations (pen, eraser, select)

## UI Components
- **BreadCrumb.kt**: Navigation breadcrumb component
- **EditorGestureReceiver.kt**: Handles touch gestures in the editor
- **EditorSurface.kt**: Main drawing surface implementation
- **PageMenu.kt**: Page management menu component
- **PagePreview.kt**: Thumbnail preview component for pages
- **Toolbar.kt**: Main toolbar implementation
- **Topbar.kt**: Top navigation bar component

## Database
- **Db.kt**: Main database access class
- **Folder.kt**: Folder entity and database operations
- **Notebook.kt**: Notebook entity and database operations
- **Page.kt**: Page entity and database operations
- **Stroke.kt**: Stroke entity and database operations
- **Migrations.kt**: Database migration logic

## Utilities
- **draw.kt**: Drawing-related utility functions
- **eraser.kt**: Eraser tool implementation
- **history.kt**: Undo/redo history management
- **pen.kt**: Pen tool implementation
- **utils.kt**: General utility functions
- **versionChecker.kt**: Version checking utilities
- **TextRecognizer.kt**: Handwriting recognition utils

## Theme & UI
- **Color.kt**: Color theme definitions
- **Shape.kt**: Shape theme definitions
- **Theme.kt**: Main theme configuration
- **Type.kt**: Typography definitions

## Views
- **EditorView.kt**: Main editor view implementation
- **HomeView.kt**: Home screen view
- **PagesView.kt**: Pages management view
- **Router.kt**: Navigation router implementation

## Modals
- **AppSettings.kt**: Application settings modal
- **FolderConfig.kt**: Folder configuration modal
- **NotebookConfig.kt**: Notebook configuration modal
- **PageSettings.kt**: Page settings modal
