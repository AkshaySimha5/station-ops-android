# Station Ops

An Android application for managing station operations with role-based access control, real-time data synchronization, and secure file management. Built with Jetpack Compose and Firebase.

## Features

- **Role-Based Authentication** — Supports Admin and Employee roles with Firebase Authentication and encrypted credential storage
- **Station Management** — Create, view, toggle, and delete operational stations with real-time status tracking
- **Background File Uploads** — Uploads run via WorkManager with a foreground service, so they continue even when the app is backgrounded; progress and completion are shown as system notifications
- **Fast Preview Thumbnails** — Every new upload generates a lightweight JPEG preview (~480px, 70% quality) that is uploaded and visible instantly while the full media continues in the background
- **Station Groups** — Admins can create named groups of stations for organizational convenience; stations can be added to or removed from groups without affecting the master station list
- **Admin Station Filtering** — Filter the station list by Active Only, Locked Only, or All Stations
- **Duplicate Download Warning** — Admins are warned before re-downloading a file that already exists in local storage
- **Alphabetical Station Sorting** — Station lists are sorted alphabetically on both Admin and Employee sides
- **File Operations** — Capture photos/videos, upload documents (images, videos), and manage station files with Firebase Cloud Storage
- **Real-Time Sync** — Cloud Firestore integration for live station data and upload tracking across devices
- **Secure Storage** — Credentials encrypted locally using Android's EncryptedSharedPreferences
- **Dark Mode** — Full light and dark theme support with Material 3 dynamic colors (Android 12+)

## Screenshots

### Login Screen
![Login screen](docs/screenshots/login.jpeg)
### Admin Dashboard
![Admin Dashboard](docs/screenshots/admin.jpeg)
### Employee Dashboard
![Employee Dashboard](docs/screenshots/employee.jpeg)
### Employee Upload
![Employee Upload](docs/screenshots/emp-upload.jpeg)

## Architecture

The project follows the **MVVM (Model-View-ViewModel)** pattern:

```
com.example.stationops
├── data
│   ├── local           # SecureStorage (encrypted credentials)
│   ├── model           # Data classes (Station, StationGroup, Upload, User)
│   ├── repository      # AuthRepository, StationRepository, GroupRepository
│   ├── util            # PreviewGenerator (thumbnail creation)
│   └── worker          # FileUploadWorker (background uploads via WorkManager)
├── ui
│   ├── dashboard       # Station list with admin controls, filtering & tab navigation
│   ├── groups          # Group management (list, detail, add/remove stations)
│   ├── login           # Authentication screen
│   ├── station_detail  # File management per station
│   ├── theme           # Material 3 theming (Color, Type, Theme)
│   └── Routes.kt      # Centralized navigation routes
└── MainActivity.kt     # Single-activity entry point with NavHost
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose, Material 3, Material Icons Extended |
| Navigation | Navigation Compose |
| Backend | Firebase Authentication, Cloud Firestore, Cloud Storage |
| Background Work | WorkManager (work-runtime-ktx) |
| Image Loading | Coil |
| Security | AndroidX Security Crypto (EncryptedSharedPreferences) |
| Build | Gradle Kotlin DSL, Version Catalogs |
| Language | Kotlin |

## Requirements

- Android Studio Ladybug or newer
- JDK 11+
- Android SDK 36 (minimum SDK 24)
- A Firebase project with Authentication, Firestore, and Storage enabled

## Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/<your-username>/station-ops-android.git
   ```

2. Open the project in Android Studio.

3. Create a Firebase project at [Firebase Console](https://console.firebase.google.com/) and enable:
   - **Authentication** (Email/Password sign-in method)
   - **Cloud Firestore** (create a database)
   - **Cloud Storage** (set up default bucket)

4. Register an Android app in Firebase with your chosen application ID (e.g., `com.example.stationops`).

5. Download `google-services.json` from your Firebase project and place it in the `app/` directory.

6. *(Optional)* Customize local build values by adding the following to `local.properties`:
   ```properties
   app.name=My Custom Name
   app.id=com.example.myapp
   app.email.domain=myapp.example.com
   ```
   These override the defaults at build time. If omitted, the app uses `Station Ops` / `com.example.stationops` / `stationops.app`.

7. Build and run:
   ```bash
   ./gradlew assembleDebug
   ```

## Firebase Configuration

### Authentication

The app constructs login emails as `<username>@<EMAIL_DOMAIN>`. Create users in Firebase Authentication using this format. The email domain is configured via `local.properties` or defaults to `stationops.app`.

### Firestore Collections

Create the following collections in Cloud Firestore:

**`users`** — User profiles (document ID = Firebase Auth UID)
| Field | Type | Description |
|-------|------|-------------|
| `uid` | string | Firebase Auth UID |
| `email` | string | User email address |
| `role` | string | `"admin"` or `"employee"` |

**`stations`** — Managed stations
| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Station display name |
| `isUploadEnabled` | boolean | Whether file uploads are allowed |
| `createdBy` | string | UID of the admin who created it |

**`station_groups`** — Station groups (admin organizational tool)
| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Auto-generated document ID |
| `name` | string | Group display name |
| `stationIds` | array of strings | IDs of stations belonging to this group |
| `createdBy` | string | UID of the admin who created it |

**`uploads`** — Files uploaded to stations
| Field | Type | Description |
|-------|------|-------------|
| `url` | string | Firebase Storage download URL of the full media |
| `previewUrl` | string | Firebase Storage download URL of the JPEG preview thumbnail |
| `type` | string | `"image"` or `"video"` |
| `uploadStatus` | string | `"PENDING"`, `"UPLOADING"`, `"COMPLETED"`, or `"FAILED"` |
| `uploaderId` | string | UID of the uploader |
| `stationId` | string | ID of the parent station |
| `timestamp` | timestamp | Upload time |

> **Backward compatibility:** Existing documents without `previewUrl` or `uploadStatus` continue to work. The app defaults to the full `url` for previews and treats missing status as `"COMPLETED"`.

### Storage

Files are stored in Firebase Cloud Storage under paths structured by station and user:

```
stations/
  {stationId}/
    admin_docs/
      {mediaUUID}                    # Full media (admin uploads)
      previews/{mediaUUID}.jpg       # Preview thumbnail (admin uploads)
    employee_uploads/
      {userId}/
        {mediaUUID}                  # Full media (employee uploads)
        previews/{mediaUUID}.jpg     # Preview thumbnail (employee uploads)
```

Ensure your Firebase Storage security rules allow authenticated users to read/write under these paths.

### Android Permissions

The app declares the following permissions in `AndroidManifest.xml`:

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Network access for Firebase |
| `CAMERA` | Photo and video capture |
| `FOREGROUND_SERVICE` | Background upload service |
| `FOREGROUND_SERVICE_DATA_SYNC` | Data sync foreground service type (Android 10+) |
| `POST_NOTIFICATIONS` | Upload progress notifications (Android 13+) |
| `MANAGE_EXTERNAL_STORAGE` | File downloads to shared storage |

## Background Upload Flow

When an employee or admin initiates an upload:

1. Content URIs are copied to temp files in the app cache
2. A `FileUploadWorker` is enqueued via WorkManager with a network-connected constraint
3. For each file, the worker:
   - Generates a JPEG preview thumbnail locally (480px wide, 70% quality)
   - Uploads the preview to Firebase Storage immediately
   - Creates a Firestore document with the `previewUrl` and `uploadStatus = "UPLOADING"`
   - Uploads the full media file in the background with progress notifications
   - Updates the Firestore document with the full `url` and `uploadStatus = "COMPLETED"`
4. A system notification shows real-time upload progress and completion/failure status
5. The uploads list auto-refreshes when the background work completes

## Admin Features

- **Station Filter** — A filter icon in the top bar lets admins toggle between All Stations, Active Only, and Locked Only
- **Duplicate Download Warning** — When downloading a file that already exists locally (in `Downloads/Work_Photos_Videos/{station}/`), a confirmation dialog asks whether to download again
- **Station Groups** — A "Groups" tab in the admin dashboard lets admins create, view, and delete groups. Tapping a group shows only its member stations with full station controls (enable/disable, delete, create new). Deleting a group does not delete its stations. Stations can belong to multiple groups. The master station list in the "Stations" tab remains the single source of truth — groups only hold references (station IDs)
- **Preview-Optimized Grid** — The admin file grid uses preview thumbnails exclusively; full media is only loaded on explicit user action (tap to view or download)


