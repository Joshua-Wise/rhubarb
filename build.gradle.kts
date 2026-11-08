plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

// ~/Documents syncs to iCloud Drive, which drops "file 2.class" copies into build output
// mid-build and breaks R8. Keep all build output in ~/Library/Caches instead.
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/Library/Caches/rexAndroid/${project.name}"))
}
