package com.streamsleep.app

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class TidalApiClient {

    companion object {
        private const val API_BASE = "https://api.tidal.com/v1"
        // Public client token for read-only access
        private const val CLIENT_TOKEN = "CzET4vdadNUFQ5JU"
        private const val COUNTRY_CODE = "PL"
    }

    private val client = OkHttpClient()
    private val gson = Gson()

    fun parsePlaylistId(url: String): String? {
        // Handles URLs like:
        // https://tidal.com/browse/playlist/uuid
        // https://listen.tidal.com/playlist/uuid
        // or just the raw UUID
        val patterns = listOf(
            Regex("""tidal\.com/(?:browse/)?playlist/([a-f0-9-]+)"""),
            Regex("""^([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$""")
        )
        for (pattern in patterns) {
            pattern.find(url.trim())?.let { return it.groupValues[1] }
        }
        return null
    }

    suspend fun getPlaylist(playlistId: String): TidalPlaylist {
        val url = "$API_BASE/playlists/$playlistId?countryCode=$COUNTRY_CODE"
        val json = makeRequest(url)
        return gson.fromJson(json, TidalPlaylist::class.java)
    }

    suspend fun getPlaylistTracks(playlistId: String): List<TidalTrack> {
        val tracks = mutableListOf<TidalTrack>()
        var offset = 0
        val limit = 100

        while (true) {
            val url = "$API_BASE/playlists/$playlistId/tracks?countryCode=$COUNTRY_CODE&limit=$limit&offset=$offset"
            val json = makeRequest(url)
            val response = gson.fromJson(json, TidalTracksResponse::class.java)
            tracks.addAll(response.items)
            if (tracks.size >= response.totalNumberOfItems || response.items.isEmpty()) break
            offset += limit
        }
        return tracks
    }

    suspend fun getStreamUrl(trackId: Int, quality: String = "HIGH"): String {
        val url = "$API_BASE/tracks/$trackId/streamUrl?soundQuality=$quality&countryCode=$COUNTRY_CODE"
        val json = makeRequest(url)
        val response = gson.fromJson(json, TidalStreamResponse::class.java)
        return response.url
    }

    private suspend fun makeRequest(url: String): String = suspendCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header("X-Tidal-Token", CLIENT_TOKEN)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        cont.resumeWithException(IOException("HTTP ${it.code}: ${it.message}"))
                    } else {
                        cont.resume(it.body?.string() ?: "")
                    }
                }
            }
        })
    }
}

data class TidalPlaylist(
    val uuid: String = "",
    val title: String = "",
    val numberOfTracks: Int = 0,
    val description: String? = null,
    val creator: TidalCreator? = null
)

data class TidalCreator(
    val name: String? = null
)

data class TidalTracksResponse(
    val items: List<TidalTrack> = emptyList(),
    val totalNumberOfItems: Int = 0
)

data class TidalTrack(
    val id: Int = 0,
    val title: String = "",
    val duration: Int = 0,
    val trackNumber: Int = 0,
    val artist: TidalArtist? = null,
    val artists: List<TidalArtist>? = null,
    val album: TidalAlbum? = null
) {
    val artistName: String
        get() = artists?.joinToString(", ") { it.name } ?: artist?.name ?: "Nieznany"

    val albumTitle: String
        get() = album?.title ?: ""

    val durationFormatted: String
        get() {
            val m = duration / 60
            val s = duration % 60
            return "%d:%02d".format(m, s)
        }
}

data class TidalArtist(
    val id: Int = 0,
    val name: String = ""
)

data class TidalAlbum(
    val id: Int = 0,
    val title: String = ""
)

data class TidalStreamResponse(
    val url: String = "",
    val codec: String = "",
    @SerializedName("encryptionKey") val encryptionKey: String? = null
)
