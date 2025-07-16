# Notable WebDAV Sync Implementation Plan

## JSON Sync Format Design

### Key Features
- **Page-level sync**: Each page becomes a separate JSON file
- **Lossless**: Preserves all Notable database fields and relationships
- **Conflict resolution**: Timestamps and device IDs for sync coordination
- **Asset handling**: Base64 embeds images for complete portability
- **Hierarchical context**: Includes notebook and folder path information

### File Naming Convention
```
<notebook-id>_<page-id>_<timestamp>.json
```
Example: `notebook-uuid-456_page-uuid-abc_20240115T103000Z.json`

### Sync Directory Structure
```
WebDAV/notable-sync/
├── devices/
│   ├── device-uuid-123.json        # Device registry
│   └── device-uuid-456.json
├── notebooks/
│   ├── notebook-uuid-456_20240115T103000Z.json    # Notebook metadata
│   └── notebook-uuid-789_20240114T142000Z.json
└── pages/
    ├── notebook-uuid-456_page-uuid-abc_20240115T103000Z.json
    ├── notebook-uuid-456_page-uuid-def_20240115T095000Z.json
    └── notebook-uuid-789_page-uuid-ghi_20240114T142000Z.json
```

## Phase 1: Notable Android App Changes

### 1. Add WebDAV Dependencies
Add to `app/build.gradle`:
```gradle
implementation 'com.github.thegrizzlylabs:sardine-android:0.8'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
```

### 2. Create Sync Data Models
Create `app/src/main/java/com/ethran/notable/sync/`:
- `SyncModels.kt` - Data classes matching JSON schema
- `SyncSerializer.kt` - JSON serialization/deserialization
- `WebDAVClient.kt` - WebDAV communication
- `SyncManager.kt` - Sync orchestration

### 3. Export Functionality
Add to `AppRepository.kt`:
```kotlin
suspend fun exportPageToSync(pageId: String): SyncPageData {
    // Gather all data for a page
    // Convert to sync format
    // Handle image serialization
}
```

### 4. Import Functionality
Add to `AppRepository.kt`:
```kotlin
suspend fun importPageFromSync(syncData: SyncPageData): String {
    // Create/update notebook if needed
    // Create/update page
    // Recreate strokes and images
    // Handle conflict resolution
}
```

### 5. WebDAV Sync Service
Create `SyncService.kt`:
- Background sync worker
- Conflict detection and resolution
- Delta sync (only changed pages)
- Device registration

### 6. UI Integration
Add to existing settings:
- WebDAV server configuration
- Sync enable/disable
- Sync status indicator
- Manual sync trigger

## Phase 2: RectangularFile Integration

### 1. Database Schema Extension
Add to RF database:
```sql
-- Notable document source tracking
ALTER TABLE documents ADD COLUMN source_type VARCHAR(20) DEFAULT 'pdf';
ALTER TABLE documents ADD COLUMN notable_notebook_id VARCHAR(255);
ALTER TABLE documents ADD COLUMN notable_page_id VARCHAR(255);
ALTER TABLE documents ADD COLUMN notable_sync_timestamp DATETIME;

-- Notable vector data storage (optional, for future features)
CREATE TABLE notable_strokes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER REFERENCES documents(id),
    stroke_id VARCHAR(255),
    pen_type VARCHAR(20),
    color INTEGER,
    size REAL,
    points TEXT,  -- JSON array of stroke points
    created_at DATETIME,
    updated_at DATETIME
);
```

### 2. NotableProcessor Class
Create `processors/notable_processor.py`:
```python
class NotableProcessor:
    def __init__(self, db_path, watch_dir):
        self.db = Database(db_path)
        self.watch_dir = watch_dir
        self.renderer = NotableRenderer()
    
    def process_sync_file(self, json_file_path):
        # Parse JSON sync file
        # Check if document exists (update vs create)
        # Render page to JPG
        # Extract text with AI
        # Update database
        # Clean up old versions
```

### 3. NotableRenderer Class
Create `renderers/notable_renderer.py`:
```python
class NotableRenderer:
    def render_page_to_jpg(self, sync_data, output_path, dpi=150):
        # Create PIL Image with page background
        # Render strokes as vector paths
        # Composite embedded images
        # Save as JPG for RF processing
        # Return image metadata
```

### 4. Vector Stroke Rendering
Core rendering logic:
- Convert stroke points to smooth Bezier curves
- Handle pressure-sensitive line width
- Render pen types (ballpen, pencil, brush effects)
- Composite multiple strokes with proper blending

### 5. WebDAV Monitor Integration
Extend existing file watcher:
```python
# Monitor notable-sync directory
# Process new/changed JSON files
# Trigger NotableProcessor
# Handle deletions
```

### 6. UI Updates
- Add "Notable" source filter to document list
- Show notebook/page hierarchy in UI
- Vector data visualization (future enhancement)
- Sync status indicators

## Phase 3: Sync Coordination

### 1. Conflict Resolution Strategy
- **Timestamp-based**: Latest updatedAt wins
- **Device coordination**: WebDAV file locking during sync
- **User intervention**: UI for manual conflict resolution
- **Backup preservation**: Keep conflicting versions

### 2. Delta Sync Implementation
- Track last sync timestamp per device
- Only sync pages modified since last sync
- Incremental updates for efficiency
- Batch operations for multiple changes

### 3. Device Registration
Each device creates/updates:
```json
{
  "deviceId": "notable-device-uuid-123",
  "deviceName": "Jarett's Boox",
  "lastSyncTime": "2024-01-15T10:30:00Z",
  "capabilities": ["sync", "render"],
  "version": "1.0"
}
```

## Phase 4: Testing & Optimization

### 1. Test Cases
- Single device sync (basic functionality)
- Multi-device sync with conflicts
- Large notebook sync performance
- Network interruption handling
- Image-heavy page sync

### 2. Performance Optimizations
- Incremental sync (only changed pages)
- Compressed JSON (gzip)
- Lazy image loading
- Background sync scheduling

### 3. Error Handling
- Network connectivity issues
- WebDAV server errors
- JSON parsing failures
- Conflict resolution failures

## Implementation Timeline

### Week 1-2: Foundation
- JSON schema finalization
- Notable sync models and serialization
- Basic WebDAV client

### Week 3-4: Notable Integration
- Export/import functionality
- WebDAV sync service
- UI integration

### Week 5-6: RectangularFile Integration
- NotableProcessor implementation
- Database schema updates
- Vector rendering engine

### Week 7-8: Integration & Testing
- End-to-end sync testing
- Conflict resolution
- Performance optimization
- Documentation

## Benefits of This Approach

1. **Lossless Sync**: Preserves all vector data, pressure, timing
2. **Efficient**: Page-level sync reduces bandwidth
3. **Conflict-Safe**: Timestamp-based resolution with manual override
4. **Extensible**: JSON format easy to extend for new features
5. **Cross-Platform**: WebDAV works across all platforms
6. **Unified Experience**: Same search/annotation functions across document types
7. **Future-Proof**: Vector data enables advanced features (search, analysis)

This implementation provides a robust foundation for Notable-RectangularFile sync while maintaining data integrity and enabling future enhancements.