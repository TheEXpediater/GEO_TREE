package com.geotree.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.geotree.app.core.ai.LeafClassifier
import com.geotree.app.core.ai.LeafModelStore
import com.geotree.app.core.ai.LeafModelUpdater
import com.geotree.app.core.ai.OnDeviceLeafClassifier
import com.geotree.app.core.camera.ContentImageReader
import com.geotree.app.core.camera.TreeImageStore
import com.geotree.app.core.database.GeoTreeDatabase
import com.geotree.app.core.location.LocationClient
import com.geotree.app.core.orientation.DeviceHeadingProvider
import com.geotree.app.core.orientation.HeadingSource
import com.geotree.app.core.network.ApiProvider
import com.geotree.app.core.network.BackendConfig
import com.geotree.app.core.network.BackendConnectionManager
import com.geotree.app.core.network.NetworkMonitor
import com.geotree.app.core.session.SessionStore
import com.geotree.app.core.sync.SyncEngine
import com.geotree.app.core.sync.SyncPreferences
import com.geotree.app.core.sync.SyncScheduler
import com.geotree.app.core.sync.SyncStatusTracker
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.data.repository.LeafAssessmentRepository
import com.geotree.app.data.repository.LocalImageReader
import com.geotree.app.data.repository.TreeRepository
import com.geotree.app.feature.locator.map.Basemap
import com.geotree.app.feature.locator.map.MapSourceSettings
import com.geotree.app.feature.locator.map.OfflineMapInstaller
import com.geotree.app.feature.locator.map.OfflineMapLocator
import com.geotree.app.feature.locator.map.resolveBasemap
import com.geotree.app.feature.locator.map.toPackage
import com.geotree.app.feature.locator.map.unavailableReason
import com.geotree.app.feature.navigation.DirectRouteProvider
import com.geotree.app.feature.navigation.NavigationConfig
import com.geotree.app.feature.navigation.RouteProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

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
    val networkMonitor = NetworkMonitor(appContext)

    val treeRepository: TreeRepository by lazy { TreeRepository(database.treeDao()) }
    val authRepository = AuthRepository(apiProvider::api, sessionStore)

    val locationClient = LocationClient(appContext)

    /** Phone compass (rotation-vector sensor); registered only while guidance or Heading Up uses it. */
    val headingSource: HeadingSource = DeviceHeadingProvider(appContext)
    val imageStore = TreeImageStore(appContext)

    /** Leaf assessment photos. Records and photos stay on the device; only sync sends them. */
    val leafImageStore = TreeImageStore(appContext, "leaf_images")
    val localImageReader: LocalImageReader = ContentImageReader(appContext)
    /** Work that must outlive a screen, e.g. a leaf assessment request after navigating to its result. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The DINOv2 leaf model bundled in assets/leaf_model, installed to filesDir/leaf_model. */
    val leafModelStore = LeafModelStore(
        openAsset = { path -> appContext.assets.open(path) },
        directory = File(appContext.noBackupFilesDir, "leaf_model"),
    )

    /** Leaf assessment runs on this phone: no network is needed to get a result. */
    val leafClassifier: LeafClassifier by lazy { OnDeviceLeafClassifier(leafModelStore) }

    /** Fetches newer classifier heads from the server during sync (best effort). */
    val leafModelUpdater = LeafModelUpdater(leafModelStore, apiProvider::aiApi)

    /** Leaf assessment history: every scan is its own Room record, synced like trees. */
    val leafAssessmentRepository: LeafAssessmentRepository by lazy {
        LeafAssessmentRepository(
            dao = database.leafAssessmentDao(),
            classifier = leafClassifier,
            scope = applicationScope,
            onFinished = { syncScheduler.requestSync(replace = false) },
        )
    }


    val syncStatusTracker = SyncStatusTracker()
    val syncPreferences = SyncPreferences(preferences)
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            treeRepository, apiProvider::api, sessionStore, syncPreferences, syncStatusTracker,
            leafAssessments = leafAssessmentRepository,
            modelUpdate = { leafModelUpdater.refresh() },
        )
    }
    val syncScheduler = SyncScheduler(appContext)

    /** Field guidance. Swap [routeProvider] for a road-routing provider later; the UI does not change. */
    val navigationConfig = NavigationConfig.Default
    val routeProvider: RouteProvider = DirectRouteProvider(navigationConfig)

    val mapSourceSettings = MapSourceSettings(preferences)

    /** The PSAU/Magalang field map bundled in assets/offline_map, installed to filesDir/offline_map. */
    val offlineMapInstaller = OfflineMapInstaller(
        openAsset = { path -> appContext.assets.open(path) },
        installDir = File(appContext.filesDir, "offline_map"),
    )

    /** Developer override: a package side-loaded into <external files>/maps replaces the bundled one. */
    val offlineMapLocator = OfflineMapLocator(listOfNotNull(appContext.getExternalFilesDir(null)).map { File(it, "maps") })

    private val offlineMapRescans = MutableStateFlow(0)

    /** The basemap to draw, re-resolved when the source setting, install state or side-loaded packages change. */
    fun basemap(): Flow<Basemap> =
        combine(mapSourceSettings.requested, offlineMapInstaller.state, offlineMapRescans) { requested, installState, _ ->
            resolveBasemap(requested, offlineMapLocator.find() ?: installState.toPackage(), installState.unavailableReason())
        }

    /** Call after copying a package into the maps folder. */
    fun rescanOfflineMaps() = offlineMapRescans.update { it + 1 }
}
