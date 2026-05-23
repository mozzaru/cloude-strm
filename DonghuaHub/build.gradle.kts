// use an integer for version numbers
version = 9

cloudstream {
    description = "All-in-one Donghua aggregator (Anichin, Donghub, YunshanID, Animexin, LuciferDonghua)"
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
    iconUrl = "https://donghub.vip/favicon.ico"

    isCrossPlatform = true
}
