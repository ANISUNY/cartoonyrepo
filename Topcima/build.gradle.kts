dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.mozilla:rhino:1.7.13")
}

version = 1

cloudstream {
    description = "Movies and series from web.topcinema.io (Topcima)"
    authors = listOf("Mehdi Marsaman")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://web.topcinema.io/wp-content/uploads/2023/05/cropped-icon-32x32.png"
}

