package com.tidalmp3.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
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
        // Fire TV client - works with device code flow
        private const val CLIENT_ID = "7m7Ap0JC9j1cOM3n"
        private const val CLIENT_SECRET = "vRAdA108tlvkJpTsGZS8rGZ7xTlbJ0qaZ2K9saEzsgY="
        private const val PREFS_NAME = "tidal_auth"
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "TIDAL_ANDROID/1124 okhttp/4.12.0")
                .header("Accept", "application/json")
                .header("X-Tidal-Token", CLIENT_ID)
                .build()
            chain.proceed(req)
        }
        .build()
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

    private var countryCode: String?
        get() = prefs.getString("country_code", null)
        set(value) = prefs.edit().putString("country_code", value).apply()

    private var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = prefs.edit().putString("user_id", value).apply()

    val isLoggedIn: Boolean
        get() = accessToken != null

    val loginStatus: String
        get() {
            if (!isLoggedIn) return "Niezalogowany"
            val uid = userId ?: "?"
            val cc = countryCode ?: "?"
            return "Zalogowano (ID: $uid, kraj: $cc)"
        }

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
        // Clear old tokens first (may be from different client_id)
        logout()

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
            Log.d(TAG, "Token response: $json")
            val tokenResponse = gson.fromJson(json, TokenResponse::class.java)
            if (tokenResponse.accessToken != null) {
                saveTokens(tokenResponse)
                // Fetch session info to validate and get country code
                fetchSessionInfo()
                Log.d(TAG, "Zalogowano! user=$userId, country=$countryCode")
                true
            } else {
                false
            }
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (msg.contains("400") || msg.contains("authorization_pending")) {
                false
            } else {
                throw e
            }
        }
    }

    private fun saveTokens(response: TokenResponse) {
        accessToken = response.accessToken
        if (response.refreshToken != null) {
            refreshToken = response.refreshToken
        }
        tokenExpiry = System.currentTimeMillis() + (response.expiresIn * 1000L)
    }

    private suspend fun fetchSessionInfo() {
        val token = accessToken ?: return
        try {
            val request = Request.Builder()
                .url("$API_BASE/sessions")
                .header("Authorization", "Bearer $token")
                .build()
            val json = executeRequest(request)
            Log.d(TAG, "Session info: $json")
            val map = gson.fromJson(json, Map::class.java)
            countryCode = (map["countryCode"] as? String) ?: "PL"
            val uid = map["userId"]
            userId = when (uid) {
                is Double -> uid.toLong().toString()
                is String -> uid
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session info failed: ${e.message}")
            // If sessions endpoint fails, token might be invalid
        }
    }

    private suspend fun forceRefreshToken(): Boolean {
        val refresh = refreshToken ?: return false
        Log.d(TAG, "Wymuszam odswiezenie tokenu...")

        val body = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&refresh_token=$refresh&grant_type=refresh_token&scope=r_usr+w_usr+w_sub"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url("$AUTH_BASE/token")
            .post(body)
            .build()

        return try {
            val json = executeRequest(request)
            Log.d(TAG, "Refresh response: $json")
            val tokenResponse = gson.fromJson(json, TokenResponse::class.java)
            if (tokenResponse.accessToken != null) {
                saveTokens(tokenResponse)
                fetchSessionInfo()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed: ${e.message}")
            false
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    private fun parseErrorMessage(code: Int, body: String): String {
        try {
            val obj = gson.fromJson(body, Map::class.java)
            val sub = (obj["subStatus"] as? Double)?.toInt()
            val msg = obj["userMessage"] ?: obj["error_description"] ?: obj["error"] ?: obj["message"]
            if (msg != null) {
                return if (sub != null) "$msg (kod: $sub)" else "$msg"
            }
        } catch (_: Exception) {}

        return when (code) {
            401 -> "Sesja wygasla - wyloguj i zaloguj ponownie"
            403 -> "Brak dostepu - sprawdz subskrypcje Tidal"
            404 -> "Nie znaleziono - sprawdz link"
            429 -> "Za duzo zapytan - poczekaj"
            else -> "Blad serwera ($code)"
        }
    }

    // --- Tidal API ---

    private fun cc(): String = countryCode ?: "PL"

    suspend fun getPlaylist(playlistId: String): TidalPlaylist {
        val json = makeAuthRequest("$API_BASE/playlists/$playlistId?countryCode=${cc()}")
        return gson.fromJson(json, TidalPlaylist::class.java)
    }

    suspend fun getPlaylistTracks(playlistId: String): List<TidalTrack> {
        val tracks = mutableListOf<TidalTrack>()
        var offset = 0
        val limit = 100

        while (true) {
            val url = "$API_BASE/playlists/$playlistId/tracks?countryCode=${cc()}&limit=$limit&offset=$offset"
            val json = makeAuthRequest(url)
            val response = gson.fromJson(json, TidalTracksResponse::class.java)
            tracks.addAll(response.items)
            if (tracks.size >= response.totalNumberOfItems || response.items.isEmpty()) break
            offset += limit
        }
        return tracks
    }

    /**
     * Get download URL for a track using playbackinfopostpaywall endpoint.
     * Returns the direct audio URL.
     */
    suspend fun getStreamUrl(trackId: Int, quality: String = "HIGH"): String {
        val url = "$API_BASE/tracks/$trackId/playbackinfopostpaywall?audioquality=$quality&playbackmode=STREAM&assetpresentation=FULL"
        val json = makeAuthRequest(url)
        Log.d(TAG, "Playback info for track $trackId: ${json.take(200)}")

        val info = gson.fromJson(json, PlaybackInfo::class.java)
        if (info.manifestMimeType == "application/vnd.tidal.bts") {
            // BTS manifest - base64 encoded JSON with URLs
            val decoded = String(Base64.decode(info.manifest, Base64.DEFAULT))
            Log.d(TAG, "BTS manifest: $decoded")
            val manifest = gson.fromJson(decoded, BtsManifest::class.java)
            if (manifest.urls.isNotEmpty()) {
                return manifest.urls[0]
            }
            throw IOException("Brak URL w manifescie BTS")
        } else if (info.manifestMimeType == "application/dash+xml") {
            // DASH manifest - need to parse MPD
            val decoded = String(Base64.decode(info.manifest, Base64.DEFAULT))
            Log.d(TAG, "DASH manifest: ${decoded.take(300)}")
            // Extract BaseURL from MPD
            val baseUrlRegex = Regex("<BaseURL>(https?://[^<]+)</BaseURL>")
            val match = baseUrlRegex.find(decoded)
            if (match != null) {
                return match.groupValues[1]
            }
            throw IOException("Nie mozna wydobyc URL z manifestu DASH")
        } else {
            throw IOException("Nieznany format manifestu: ${info.manifestMimeType}")
        }
    }

    /**
     * Auth request with auto-retry on 401.
     */
    private suspend fun makeAuthRequest(url: String): String {
        val token = accessToken ?: throw IOException("Niezalogowany - zaloguj sie do Tidal")

        // Attempt 1
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        val result = executeRawRequest(request)
        if (result != null) return result

        // Attempt 2: refresh and retry
        Log.d(TAG, "401 - odswiezam token i ponawiam...")
        val refreshed = forceRefreshToken()
        if (!refreshed) {
            logout()
            throw IOException("Sesja wygasla - zaloguj sie ponownie")
        }

        val newToken = accessToken ?: throw IOException("Brak tokenu po odswiezeniu")
        val retryRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $newToken")
            .build()

        return executeRequest(retryRequest)
    }

    /**
     * Returns body or null on 401.
     */
    private suspend fun executeRawRequest(request: Request): String? = suspendCoroutine { cont ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string() ?: ""
                    when {
                        it.isSuccessful -> cont.resume(body)
                        it.code == 401 -> {
                            Log.w(TAG, "401 for ${request.url}: ${body.take(200)}")
                            cont.resume(null)
                        }
                        else -> {
                            Log.e(TAG, "HTTP ${it.code} for ${request.url}: ${body.take(300)}")
                            cont.resumeWithException(IOException(parseErrorMessage(it.code, body)))
                        }
                    }
                }
            }
        })
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
                        Log.e(TAG, "HTTP ${it.code} for ${request.url}: ${body.take(300)}")
                        cont.resumeWithException(IOException(parseErrorMessage(it.code, body)))
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

data class PlaybackInfo(
    @SerializedName("trackId") val trackId: Int = 0,
    @SerializedName("assetPresentation") val assetPresentation: String = "",
    @SerializedName("audioMode") val audioMode: String = "",
    @SerializedName("audioQuality") val audioQuality: String = "",
    @SerializedName("manifestMimeType") val manifestMimeType: String = "",
    @SerializedName("manifest") val manifest: String = ""
)

data class BtsManifest(
    @SerializedName("mimeType") val mimeType: String = "",
    @SerializedName("codecs") val codecs: String = "",
    @SerializedName("encryptionType") val encryptionType: String = "",
    @SerializedName("urls") val urls: List<String> = emptyList()
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
