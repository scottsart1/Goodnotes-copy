package com.inkwell.notes.settings.logic

/** An open-source (or third-party) component bundled in the app. */
data class LicenseEntry(
    val name: String,
    val detail: String,
    val license: String,
    val url: String,
)

/** The licences shown in Settings > About. */
object Licenses {
    const val APACHE_2 = "Apache License 2.0"
    const val OFL = "SIL Open Font License 1.1"
    const val GOOGLE_APIS = "Google APIs Terms of Service and ML Kit Terms"

    private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

    val entries: List<LicenseEntry> = listOf(
        LicenseEntry("AndroidX", "Core, AppCompat, Activity, Fragment, Lifecycle, Navigation, Room, WorkManager, DataStore, Biometric, Window, Graphics, Motion prediction", APACHE_2, APACHE_URL),
        LicenseEntry("Jetpack Compose", "UI, Foundation, Material 3, Material icons, Animation", APACHE_2, APACHE_URL),
        LicenseEntry("Kotlin", "Standard library, kotlinx.coroutines, kotlinx.serialization", APACHE_2, APACHE_URL),
        LicenseEntry("OkHttp", "HTTP client by Square", APACHE_2, APACHE_URL),
        LicenseEntry("Coil", "Image loading", APACHE_2, APACHE_URL),
        LicenseEntry("PdfBox-Android", "PDF reading and writing (Apache PDFBox port)", APACHE_2, APACHE_URL),
        LicenseEntry("Media3 ExoPlayer", "Audio playback", APACHE_2, APACHE_URL),
        LicenseEntry("ML Kit", "Text recognition, digital ink recognition, document scanner", GOOGLE_APIS, "https://developers.google.com/ml-kit/terms"),
        LicenseEntry("Figtree", "Typeface by Erik Kennedy", OFL, "https://openfontlicense.org"),
    )
}
