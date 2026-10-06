# Screen Distance App — Build APK with GitHub (No Android Studio)

## 1. Create a GitHub repository
Create a new repository on GitHub, for example `ScreenDistanceApp`.

## 2. Upload this project
Upload **all files and folders** from this project, including `.github/workflows/build-apk.yml`.

The repository root must contain:

- `settings.gradle.kts`
- `build.gradle.kts`
- `app/`
- `.github/workflows/build-apk.yml`

## 3. Build the APK
After uploading, open the repository's **Actions** tab.

Choose **Build Screen Distance APK** and click **Run workflow**.

Wait for the green checkmark.

## 4. Download the APK
Open the completed workflow run. At the bottom, under **Artifacts**, download:

`ScreenDistance-debug-apk`

Extract the downloaded artifact and you will have:

`app-debug.apk`

Copy `app-debug.apk` to your Android phone and install it.

If Android blocks the installation, allow your file manager/browser to install unknown apps when Android asks.
