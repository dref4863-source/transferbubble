plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * FIRMA ESTABLE
 * -------------
 * Antes se firmaba con signingConfigs.debug y cada ejecucion de GitHub Actions
 * generaba una clave nueva -> INSTALL_FAILED_UPDATE_INCOMPATIBLE y habia que
 * desinstalar para actualizar.
 *
 * Ahora, si el workflow aporta las propiedades KEYSTORE_* (desde los Secrets),
 * se firma con el keystore propio y todos los builds son actualizables entre si.
 * Si no hay keystore (compilacion local), se cae a la clave de debug.
 *
 * Las propiedades se leen fuera del bloque android() a proposito: leerlas dentro
 * de signingConfigs puede fallar porque el bloque se evalua antes de que el
 * proyecto tenga las propiedades cargadas.
 */
val ksPath: String? = findProperty("KEYSTORE_PATH") as String?
val ksPassword: String? = findProperty("KEYSTORE_PASSWORD") as String?
val ksAlias: String? = findProperty("KEY_ALIAS") as String?
val ksKeyPassword: String? = findProperty("KEY_PASSWORD") as String?
val hasKeystore = ksPath != null && file(ksPath).exists()

android {
    namespace = "com.dref.transferbubble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dref.transferbubble"
        minSdk = 24
        targetSdk = 34
        versionCode = 13
        versionName = "0.7.0"
    }

    if (hasKeystore) {
        signingConfigs {
            create("firma") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) signingConfigs.getByName("firma")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    debugImplementation("androidx.compose.ui:ui-tooling")
}