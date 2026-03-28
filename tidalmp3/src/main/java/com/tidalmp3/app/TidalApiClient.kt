package com.tidalmp3.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class TidalApiClient(context: Context) {

    companion object {
        private const val TAG = "TidalApi"
        private const val API_BASE = "https://api.tidal.com/v1"
        private const val AUTH_BASE = "https://auth.tidal.com/v1/oauth2"
        private const val CLIENT_ID = "zU4XHVVkc2tDPo4t"
        private const val CLIENT_SECRET = "VJKhDFqJPqvsPVNBV6ukXTJmwlvbttP7wlMlrc72se4="
        private const val COUNTRY_CODE = "PL"
        private const val PREFS_NAME = "tidal_auth"
    }

    private val client = OkHttpClient()
    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()

    private var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    private var tokenExpiry: Long
        get() = prefs.getLong("token_expiry", 0)
        set(value) = prefs.edit().putLong("token_expiry", value).apply()

    val isLoggedIn: Boolean
        get() = accessToken != null

    fun parsePlaylistId(url: String): String? {
        val patterns = listOf(
            Regex("""tidal\.com/(?:browse/)?playlist/([a-f0-9-]+)"""),
            Regex("""^([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$""")
        )
        for (pattern in patterns) {
            pattern.find(url.trim())?.let { return it.groupValues[1] }
        }
        return null
    }

    // --- OAuth Device Code Flow ---

    suspend fun startDeviceLogin(): DeviceAuthResponse {
        val body = "client_id=$CLIENT_ID&scope=r_usr+w_usr+w_sub"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url("$AUTH_BASE/device_authorization")
            .post(body)
            .build()

        val json = executeRequest(request)
        Log.d(TAG, "Device auth response: $json")
        return gson.fromJson(json, DeviceAuthResponse::class.java)
    }

    suspend fun pollForToken(deviceCode: String): Boolean {
        val body = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code&scope=r_usr+w_usr+w_sub"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url("$AUTH_BASE/token")
            .post(body)
            .build()

        return try {
            val json = executeRequest(request)
            val tokenResponse = gson.fromJson(json, TokenResponse::class.java)
            if (tokenResponse.accessToken != null) {
                accessToken = tokenResponse.accessToken
                refreshToken = tokenResponse.refreshToken
                tokenExpiry = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L)
                Log.d(TAG, "Zalogowano! Token wygasa za ${tokenResponse.expiresIn}s")
                true
            } else {
                false
            }
        } catch (e: IOException) {
            if (e.message?.contains("400") == true || e.message?.contains("401") == true) {
                false // Uzytkownik jeszcze nie zatwierdzil
            } else {
                throw e
            }
        }
    }

    private suspend fun refreshTokenIfNeeded() {
        val refresh = refreshToken ?: throw IOException("Brak refresh tokenu - zaloguj sie ponownie")
        if (System.currentTimeMillis() < tokenExpiry - 60000) return // token wazny

        Log.d(TAG, "Odswiezanie tokenu...")
        val body = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&refresh_token=$refresh&grant_type=refresh_token&scope=r_usr+w_usr+w_sub"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url("$AUTH_BASE/token")
            .post(body)
            .build()

        val json = executeRequest(request)
        val tokenResponse = gson.fromJson(json, TokenResponse::class.java)
        if (tokenResponse.accessToken != null) {
            accessToken = tokenResponse.accessToken
            if (tokenResponse.refreshToken != null) {
                refreshToken = tokenResponse.refreshToken
            }
            tokenExpiry = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L)
        } else {
            throw IOException("Nie udalo sie odswiezyc tokenu")
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    // --- Tidal API ---

    suspend fun getPlaylist(playlistId: String): TidalPlaylist {
        val json = makeAuthRequest("$API_BASE/playlists/$playlistId?countryCode=$COUNTRY_CODE")
        return gson.fromJson(json, TidalPlaylist::class.java)
    }

    suspend fun getPlaylistTracks(playlistId: String): List<TidalTrack> {
        val tracks = mutableListOf<TidalTrack>()
        var offset = 0
        val limit = 100

        while (true) {
            val url = "$API_BASE/playlists/$playlistId/tracks?countryCode=$COUNTRY_CODE&limit=$limit&offset=$offset"
            val json = makeAuthRequest(url)
            val response = gson.fromJson(json, TidalTracksResponse::class.java)
            tracks.addAll(response.items)
            if (tracks.size >= response.totalNumberOfItems || response.items.isEmpty()) break
            offset += limit
        }
        return tracks
    }

    suspend fun getStreamUrl(trackId: Int, quality: String = "HIGH"): String {
        val url = "$API_BASE/tracks/$trackId/streamUrl?soundQuality=$quality&countryCode=$COUNTRY_CODE"
        val json = makeAuthRequest(url)
        Log.d(TAG, "Stream response for track $trackId: $json")
        val response = gson.fromJson(json, TidalStreamResponse::class.java)
        if (response.url.isBlank()) {
            throw IOException("Brak URL streamu - sprawdz subskrypcje Tidal")
        }
        return response.url
    }

    private suspend fun makeAuthRequest(url: String): String {
        refreshTokenIfNeeded()
        val token = accessToken ?: throw IOException("Niezalogowany - zaloguj sie do Tidal")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Tidal-Token", CLIENT_ID)
            .build()

        return executeRequest(request)
    }

    private suspend fun executeRequest(request: Request): String = suspendCoroutine { cont ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string() ?: ""
                    if (!it.isSuccessful) {
                        Log.e(TAG, "HTTP ${it.code} for ${request.url}: $body")
                        cont.resumeWithException(IOException("HTTP ${it.code}: $body"))
                    } else {
                        cont.resume(body)
                    }
                }
            }
        })
    }
}

// --- Data classes ---

data class DeviceAuthResponse(
    @SerializedName("deviceCode") val deviceCode: String = "",
    @SerializedName("userCode") val userCode: String = "",
    @SerializedName("verificationUri") val verificationUri: String = "",
    @SerializedName("verificationUriComplete") val verificationUriComplete: String = "",
    @SerializedName("expiresIn") val expiresIn: Int = 300,
    @SerializedName("interval") val interval: Int = 5
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("expires_in") val expiresIn: Int = 0,
    @SerializedName("token_type") val tokenType: String? = null
)

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
