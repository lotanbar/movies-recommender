package com.moviesrecommender

import android.app.Application
import com.moviesrecommender.data.local.AppDatabase
import com.moviesrecommender.data.local.LocalStorageService
import com.moviesrecommender.data.local.SharedPrefsTokenStore
import com.moviesrecommender.data.remote.anthropic.AnthropicAuthManager
import com.moviesrecommender.data.remote.anthropic.AnthropicService
import com.moviesrecommender.data.remote.anthropic.OkHttpAnthropicApiClient
import com.moviesrecommender.data.remote.dropbox.DropboxAuthManager
import com.moviesrecommender.data.remote.dropbox.DropboxService
import com.moviesrecommender.data.remote.dropbox.OkHttpDropboxApiClient
import com.moviesrecommender.data.remote.dropbox.TokenStore
import com.moviesrecommender.data.remote.tmdb.OkHttpTmdbApiClient
import com.moviesrecommender.data.remote.tmdb.Title
import com.moviesrecommender.data.remote.tmdb.TmdbService
import com.moviesrecommender.data.remote.wikidata.OkHttpWikidataApiClient
import com.moviesrecommender.util.ToastManager

class MoviesRecommenderApp : Application() {

    val tokenStore: TokenStore by lazy {
        SharedPrefsTokenStore(getSharedPreferences("app_prefs", MODE_PRIVATE))
    }

    val dropboxService: DropboxService by lazy {
        DropboxService(DropboxAuthManager(tokenStore), OkHttpDropboxApiClient())
    }

    val anthropicService: AnthropicService by lazy {
        AnthropicService(AnthropicAuthManager(tokenStore), OkHttpAnthropicApiClient())
    }

    val tmdbService: TmdbService by lazy {
        TmdbService(tokenStore, OkHttpTmdbApiClient())
    }

    val wikidataApiClient: OkHttpWikidataApiClient by lazy {
        OkHttpWikidataApiClient()
    }

    val localStorageService: LocalStorageService by lazy {
        LocalStorageService(AppDatabase.getInstance(this).starDao())
    }

    /** In-memory list cache shared between flows and PreviewScreen. */
    var cachedListContent: String? = null

    /** Titles shown during the current recommend session that the user skipped — cleared on fresh entry. */
    val recommendSkippedTitles: MutableSet<String> = mutableSetOf()

    /** Ordered queue of (tmdbId, mediaType) for the current recommend batch. */
    var recommendQueue: List<Pair<Int, String>> = emptyList()

    /** Index of the title currently being shown in the recommend flow. */
    var recommendQueueIndex: Int = 0

    /** Pre-fetched Title objects keyed by TMDB ID for instant PreviewScreen loading. */
    val cachedTitles: MutableMap<Int, Title> = java.util.concurrent.ConcurrentHashMap()

    companion object {
        lateinit var instance: MoviesRecommenderApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ToastManager.init(this)
    }
}
