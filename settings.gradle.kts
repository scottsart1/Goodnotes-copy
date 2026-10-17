// Inkwell — S Pen-first notes for large Android tablets.
pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: the primary host rate-limits CI bursts.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // sherpa-onnx (on-device speaker diarization) is published only through JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

rootProject.name = "Inkwell"
include(":app")
