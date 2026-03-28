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
        private const val PREFS_NAME = "tidal_auth"
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "TIDAL_ANDROID/2.82.0")
                .header("Accept", "application/json")
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

    private var cachedCountryCode: String? = null

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
            Log.d(TAG, "Token response: $json")
            val tokenResponse = gson.fromJson(json, TokenResponse::class.java)
            if (tokenResponse.accessToken != null) {
                saveTokens(tokenResponse)
                Log.d(TAG, "Zalogowano! Token wygasa za ${tokenResponse.expiresIn}s")
                true
            } else {
                false
            }
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (msg.contains("400") || msg.contains("401") || msg.contains("authorization_pending")) {
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
        cachedCountryCode = null // Reset cache on new login
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
        cachedCountryCode = null
    }

    private fun parseErrorMessage(code: Int, body: String): String {
        try {
            val obj = gson.fromJson(body, Map::class.java)
            val msg = obj["userMessage"] ?: obj["error_description"] ?: obj["error"] ?: obj["message"]
            if (msg != null) return "$msg"
        } catch (_: Exception) {}

        return when (code) {
            401 -> "Sesja wygasla - wyloguj sie i zaloguj ponownie"
            403 -> "Dostep zabroniony - sprawdz subskrypcje Tidal"
            404 -> "Nie znaleziono - sprawdz link do playlisty"
            429 -> "Za duzo zapytan - poczekaj chwile"
            else -> "Blad serwera ($code)"
        }
    }

    // --- Tidal API ---

    private suspend fun getCountryCode(): String {
        cachedCountryCode?.let { return it }

        val token = accessToken ?: return "PL"
        try {
            val request = Request.Builder()
                .url("$API_BASE/sessions")
                .header("Authorization", "Bearer $token")
                .build()
            val json = executeRawRequest(request)
            if (json != null) {
                val map = gson.fromJson(json, Map::class.java)
                val cc = map["countryCode"] as? String
                if (cc != null) {
                    cachedCountryCode = cc
                    Log.d(TAG, "Country code: $cc")
                    return cc
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie mozna pobrac country code: ${e.message}")
        }
        return "PL"
    }

    suspend fun getPlaylist(playlistId: String): TidalPlaylist {
        val cc = getCountryCode()
        val json = makeAuthRequest("$API_BASE/playlists/$playlistId?countryCode=$cc")
        return gson.fromJson(json, TidalPlaylist::class.java)
    }

    suspend fun getPlaylistTracks(playlistId: String): List<TidalTrack> {
        val cc = getCountryCode()
        val tracks = mutableListOf<TidalTrack>()
        var offset = 0
        val limit = 100

        while (true) {
            val url = "$API_BASE/playlists/$playlistId/tracks?countryCode=$cc&limit=$limit&offset=$offset"
            val json = makeAuthRequest(url)
            val response = gson.fromJson(json, TidalTracksResponse::class.java)
            tracks.addAll(response.items)
            if (tracks.size >= response.totalNumberOfItems || response.items.isEmpty()) break
            offset += limit
        }
        return tracks
    }

    suspend fun getStreamUrl(trackId: Int, quality: String = "HIGH"): String {
        val cc = getCountryCode()
        val url = "$API_BASE/tracks/$trackId/streamUrl?soundQuality=$quality&countryCode=$cc"
        val json = makeAuthRequest(url)
        Log.d(TAG, "Stream response for track $trackId: $json")
        val response = gson.fromJson(json, TidalStreamResponse::class.java)
        if (response.url.isBlank()) {
            throw IOException("Brak URL streamu - sprawdz subskrypcje Tidal")
        }
        return response.url
    }

    /**
     * Wykonaj request z tokenem. Jesli 401, odswiez token i sprobuj ponownie.
     * Jesli refresh tez sie nie uda, wyczysc sesje i rzuc blad.
     */
    private suspend fun makeAuthRequest(url: String): String {
        val token = accessToken ?: throw IOException("Niezalogowany - zaloguj sie do Tidal")

        // Proba 1: z aktualnym tokenem
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        val result = executeRawRequest(request)
        if (result != null) return result

        // Proba 2: odswiez token i sprobuj ponownie
        Log.d(TAG, "Proba 1 nieudana (401), odswiezam token...")
        val refreshed = forceRefreshToken()
        if (!refreshed) {
            // Refresh sie nie udal - wyczysc sesje
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
     * Wykonaj request i zwroc body lub null jesli 401.
     * Inne bledy rzucaja wyjatkiem.
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
                            Log.w(TAG, "401 for ${request.url}: $body")
                            cont.resume(null) // Zwroc null, caller odswieza token
                        }
                        else -> {
                            Log.e(TAG, "HTTP ${it.code} for ${request.url}: $body")
                            val errorMsg = parseErrorMessage(it.code, body)
                            cont.resumeWithException(IOException(errorMsg))
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
                        Log.e(TAG, "HTTP ${it.code} for ${request.url}: $body")
                        val errorMsg = parseErrorMessage(it.code, body)
                        cont.resumeWithException(IOException(errorMsg))
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
