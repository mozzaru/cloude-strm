// use an integer for version numbers
version = 22

cloudstream {
    description = "Anime and Movies"
    language    = "id"
    authors = listOf("mozzaru")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf("AnimeMovie","Anime")
    iconUrl = "https://donghuaarena.site/logo.png"
    isCrossPlatform = true
}
