package com.geotree.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.geotree.app.core.camera.TreeImageStore
import com.geotree.app.core.database.GeoTreeDatabase
import com.geotree.app.core.location.LocationClient
import com.geotree.app.core.network.ApiProvider
import com.geotree.app.core.network.BackendConfig
import com.geotree.app.core.network.BackendConnectionManager
import com.geotree.app.core.session.SessionStore
import com.geotree.app.core.sync.SyncEngine
import com.geotree.app.core.sync.SyncPreferences
import com.geotree.app.core.sync.SyncScheduler
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.data.repository.TreeRepository

private val Context.geoTreePreferences: DataStore<Preferences> by preferencesDataStore(name = "geo_tree_prefs")

/** Explicit, hand-wired dependencies. One instance per process, owned by [GeoTreeApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.geoTreePreferences

    val database: GeoTreeDatabase by lazy { GeoTreeDatabase.build(appContext) }
    val sessionStore = SessionStore(preferences)
    val backendConfig = BackendConfig(preferences, BuildConfig.DEFAULT_BASE_URL)
    val apiProvider = ApiProvider(backendConfig, sessionStore::cachedAccessToken)
    val backendConnection = BackendConnectionManager(backendConfig, apiProvider)

    val treeRepository: TreeRepository by lazy { TreeRepository(database.treeDao()) }
    val authRepository = AuthRepository(apiProvider::api, sessionStore)

    val locationClient = LocationClient(appContext)
    val imageStore = TreeImageStore(appContext)

    val syncStatusTracker = SyncStatusTracker()
    val syncEngine: SyncEngine by lazy {
        SyncEngine(treeRepository, apiProvider::api, sessionStore, SyncPreferences(preferences), syncStatusTracker)
    }
    val syncScheduler = SyncScheduler(appContext)
}
