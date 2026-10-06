# Screen Distance

Android app that uses the front camera and Google ML Kit face detection to estimate face-to-phone distance.

## How it works
1. Start the app and allow camera permission.
2. Put the phone at exactly 35 cm from your face.
3. Tap **Calibrate at 35 cm**.
4. The app estimates distance from the detected face width.
5. Below 35 cm it shows a warning and vibrates, with a 3-second alert cooldown.

## Build
Open this folder in Android Studio (Ladybug or newer), let Gradle sync, then Run on an Android device.

## Important
This is an estimate, not a medical-grade distance sensor. Accuracy varies with camera, face angle, lighting, and calibration. Keep the phone approximately centered on your face for best results.
