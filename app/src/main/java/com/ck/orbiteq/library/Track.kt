package com.ck.orbiteq.library

import org.json.JSONObject

/** One song. id > 0 = from the phone's music library; id <= 0 = a file or YouTube song. */
data class Track(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNo: Int = 0,
    val folder: String = "",
    val genre: String = "",
    val dateAdded: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("u", uri).put("t", title).put("ar", artist).put("al", album)
        .put("aid", albumId).put("d", durationMs).put("n", trackNo)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            id = o.optLong("id"),
            uri = o.optString("u"),
            title = o.optString("t", "Song"),
            artist = o.optString("ar", ""),
            album = o.optString("al", ""),
            albumId = o.optLong("aid", -1),
            durationMs = o.optLong("d"),
            trackNo = o.optInt("n"),
        )
    }
}

/** A group of songs: album, artist, genre, folder or playlist. */
data class Group(
    val key: String,
    val name: String,
    val sub: String,
    val tracks: List<Track>,
    val albumId: Long = -1,
)
