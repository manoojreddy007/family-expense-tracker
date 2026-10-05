# LA-MA Expense Tracker for Android

The Android app talks to the existing Flask expense tracker over HTTPS. The Flask server connects to PostgreSQL; database credentials stay on the server and are never entered into the Android app.

## Configure and run

Set `DATABASE_URL`, `SECRET_KEY`, and `APP_PASSWORD` in the Flask deployment environment. Use a long random `SECRET_KEY` and change the default `APP_PASSWORD` before deployment. The app password is shared with the Android client and must be sent only over HTTPS.

Open this directory in Android Studio, run the app, and enter the deployed Flask base URL (for example `https://expenses.example.com`) and the configured app password. Do not include a path such as `/login` or a trailing slash. The URL must use HTTPS.

The Android client uses the authenticated `/api` routes in the Flask app for login, dashboard data, and transaction create/update/delete. Its saved app password is encrypted in Android Keystore-backed private storage. No PostgreSQL driver or database credentials are included in the APK.

## Build an APK

Install Android Studio with Android SDK 35 and JDK 17, open this directory, let Gradle sync, then use **Build > Build APK(s)**. The debug APK is suitable for private testing. For sharing, create a signed release APK and keep its signing key backed up. Updates must use the same signing key.
