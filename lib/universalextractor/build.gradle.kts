plugins {
    id("lib-android")
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:synchrony"))
    implementation(project(":lib:playlistutils"))
}
