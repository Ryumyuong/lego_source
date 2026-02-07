plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.main.lego"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.main.lego"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ML Kit Text Recognition (Korean)
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")

    // Apache POI for Excel
    implementation("org.apache.poi:poi:5.2.5") {
        exclude(group = "org.apache.logging.log4j")
    }
    implementation("org.apache.poi:poi-ooxml:5.2.5") {
        exclude(group = "org.apache.logging.log4j")
    }

    // HWP file parser
    implementation("kr.dogfoot:hwplib:1.1.1")
    implementation("androidx.activity:activity:1.12.2")

    // PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}