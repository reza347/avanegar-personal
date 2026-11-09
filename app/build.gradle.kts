plugins { id("com.android.application") }

android {
  namespace = "com.avanegar.personal"
  compileSdk = 35
  defaultConfig {
    applicationId = "com.avanegar.personal"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
