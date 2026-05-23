// use an integer for version numbers
version = 12

cloudstream {
    description = "Streaming Anime and Donghua"
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
    iconUrl = "https://v18.kuramanime.ing/assets/img/logo-full-512.png"

    isCrossPlatform = true
}
