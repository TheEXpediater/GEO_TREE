package com.geotree.app.testing

import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.database.SyncStatusCount
import com.geotree.app.core.database.TreeDao
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.location.GpsAccuracyPolicy
import com.geotree.app.core.location.GpsFix
import com.geotree.app.core.location.LocationPermission
import com.geotree.app.core.location.LocationSource
import com.geotree.app.core.location.LocationUpdateProfile
import com.geotree.app.data.remote.GeoTreeApi
import com.geotree.app.data.remote.HealthDto
import com.geotree.app.data.remote.LoginRequestDto
import com.geotree.app.data.remote.LoginResponseDto
import com.geotree.app.data.remote.TreeChangesDto
import com.geotree.app.data.remote.TreeDto
import com.geotree.app.data.remote.TreeSyncRequestDto
import com.geotree.app.data.remote.TreeSyncResponseDto
import com.geotree.app.data.remote.UserDto
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** In-memory TreeDao with the same unique constraints as the Room schema (id, treeCode). */
class FakeTreeDao : TreeDao {
    val rows = MutableStateFlow<Map<String, TreeEntity>>(emptyMap())
    /** Every status written, in order, per tree id. */
    val statusHistory = mutableMapOf<String, MutableList<SyncStatus>>()

    private fun record(tree: TreeEntity) {
        statusHistory.getOrPut(tree.id) { mutableListOf() }.let { if (it.lastOrNull() != tree.syncStatus) it += tree.syncStatus }
    }

    private fun put(tree: TreeEntity) {
        val holder = rows.value.values.firstOrNull { it.treeCode == tree.treeCode && it.id != tree.id }
        if (holder != null) throw SQLiteConstraintException("UNIQUE constraint failed: trees.treeCode")
        rows.value = rows.value + (tree.id to tree)
        record(tree)
    }

    override fun observeAll(): Flow<List<TreeEntity>> = rows.map { it.values.sortedByDescending(TreeEntity::createdAt) }
    override fun observeById(id: String): Flow<TreeEntity?> = rows.map { it[id] }
    override fun observeStatusCounts(): Flow<List<SyncStatusCount>> =
        rows.map { m -> m.values.groupingBy { it.syncStatus }.eachCount().map { SyncStatusCount(it.key, it.value) } }
    override suspend fun count(): Int = rows.value.size
    override suspend fun getById(id: String): TreeEntity? = rows.value[id]
    override suspend fun getByTreeCode(treeCode: String): TreeEntity? = rows.value.values.firstOrNull { it.treeCode == treeCode }
    override suspend fun getUnsynced(): List<TreeEntity> =
        rows.value.values.filter { it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.FAILED }.sortedBy { it.createdAt }

    override suspend fun insert(tree: TreeEntity) {
        if (rows.value.containsKey(tree.id)) throw SQLiteConstraintException("UNIQUE constraint failed: trees.id")
        put(tree)
    }

    override suspend fun update(tree: TreeEntity) {
        if (rows.value.containsKey(tree.id)) put(tree)
    }

    override suspend fun upsert(tree: TreeEntity) = put(tree)

    override suspend fun setSyncStatus(id: String, status: SyncStatus, error: String?) {
        rows.value[id]?.let { put(it.copy(syncStatus = status, lastSyncError = error)) }
    }

    override suspend fun markSynced(id: String, expectedUpdatedAt: Long, serverVersion: Long, remoteImagePath: String?): Int {
        val tree = rows.value[id]?.takeIf { it.updatedAt == expectedUpdatedAt } ?: return 0
        put(tree.copy(syncStatus = SyncStatus.SYNCED, serverVersion = serverVersion, remoteImagePath = remoteImagePath, lastSyncError = null))
        return 1
    }

    override suspend fun resetInterruptedSyncs(): Int {
        val syncing = rows.value.values.filter { it.syncStatus == SyncStatus.SYNCING }
        syncing.forEach { put(it.copy(syncStatus = SyncStatus.PENDING)) }
        return syncing.size
    }
}

/**
 * A small in-memory model of the FastAPI contract: UUID upsert, Tree Code uniqueness (409),
 * monotonic server_version, image attachment, and the version-filtered change feed.
 */
class FakeGeoTreeServer : GeoTreeApi {
    val trees = linkedMapOf<String, TreeDto>()
    private var version = 0L
    var healthy = true
    var failNextSyncWithIo = false
    var failNextUploadWithIo = false
    var loginFailure: Exception? = null
    var syncCalls = 0
    var uploadCalls = 0
    var loginCalls = 0

    override suspend fun health(): HealthDto {
        if (!healthy) throw IOException("Failed to connect to /10.0.2.2:8000")
        return HealthDto(status = "ok", service = GeoTreeApi.SERVICE_NAME, version = "0.1.0", database = "ok")
    }

    override suspend fun login(body: LoginRequestDto): LoginResponseDto {
        loginCalls++
        loginFailure?.let { throw it }
        if (body.email != "admin@gmail.com" || body.password != "admin123") throw httpError(401, "INVALID_CREDENTIALS", "Incorrect email or password.")
        return LoginResponseDto("token-123", "bearer", "2026-10-05T00:00:00Z", UserDto(body.email, "Development Admin"))
    }

    override suspend fun me(): UserDto = UserDto("admin@gmail.com", "Development Admin")

    override suspend fun syncTree(body: TreeSyncRequestDto): TreeSyncResponseDto {
        syncCalls++
        if (failNextSyncWithIo) {
            failNextSyncWithIo = false
            throw IOException("unexpected end of stream")
        }
        val code = body.treeCode.uppercase()
        trees.values.firstOrNull { it.treeCode == code && it.id != body.id }?.let {
            throw httpError(409, "TREE_CODE_CONFLICT", "Tree Code $code is already registered to another tree.")
        }
        val existing = trees[body.id]
        if (existing != null && Instant.parse(existing.updatedAt) >= Instant.parse(body.updatedAt)) {
            return TreeSyncResponseDto("unchanged", existing)
        }
        val dto = TreeDto(
            id = body.id, treeCode = code, latitude = body.latitude, longitude = body.longitude,
            accuracyMeters = body.accuracyMeters, altitudeMeters = body.altitudeMeters,
            locationCapturedAt = body.locationCapturedAt, age = body.age, tasteCategory = body.tasteCategory,
            yearlyYield = body.yearlyYield, fruitQuality = body.fruitQuality, notes = body.notes,
            imagePath = existing?.imagePath, createdAt = body.createdAt, updatedAt = body.updatedAt,
            serverVersion = ++version,
        )
        trees[body.id] = dto
        return TreeSyncResponseDto(if (existing == null) "created" else "updated", dto)
    }

    override suspend fun changes(afterVersion: Long, limit: Int): TreeChangesDto {
        val newer = trees.values.filter { it.serverVersion > afterVersion }.sortedBy { it.serverVersion }
        val page = newer.take(limit)
        return TreeChangesDto(page, page.lastOrNull()?.serverVersion ?: afterVersion, newer.size > limit)
    }

    override suspend fun tree(id: String): TreeDto = trees[id] ?: throw httpError(404, "TREE_NOT_FOUND", "Tree not found.")

    override suspend fun uploadImage(id: String, file: MultipartBody.Part): TreeDto {
        uploadCalls++
        if (failNextUploadWithIo) {
            failNextUploadWithIo = false
            throw IOException("Connection reset")
        }
        val existing = trees[id] ?: throw httpError(404, "TREE_NOT_FOUND", "Tree not found.")
        val updated = existing.copy(imagePath = "tree_images/$id-abc.jpg", serverVersion = ++version)
        trees[id] = updated
        return updated
    }

    /** Simulates another device having synced a tree. */
    fun addRemoteTree(id: String, code: String, latitude: Double = 15.2, longitude: Double = 120.6): TreeDto {
        val dto = TreeDto(
            id = id, treeCode = code, latitude = latitude, longitude = longitude, accuracyMeters = 3.5f,
            locationCapturedAt = "2026-09-28T08:00:00Z", imagePath = "tree_images/$id-remote.jpg",
            createdAt = "2026-09-28T08:00:01Z", updatedAt = "2026-09-28T08:00:01Z", serverVersion = ++version,
        )
        trees[id] = dto
        return dto
    }

    companion object {
        fun httpError(status: Int, code: String, message: String): HttpException {
            val body = """{"error":{"code":"$code","message":"$message","details":{}}}"""
            return HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
        }
    }
}

fun testDataStore(directory: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "test.preferences_pb") })

fun gpsFix(
    latitude: Double = 15.144900,
    longitude: Double = 120.588700,
    accuracy: Float = 4.2f,
    capturedAt: Long = 1_790_000_000_000,
    speedMps: Float? = null,
    speedAccuracyMps: Float? = null,
) = GpsFix(
    latitude, longitude, accuracy, altitudeMeters = null, capturedAt = capturedAt,
    quality = GpsAccuracyPolicy.Default.classify(accuracy), speedMps = speedMps, speedAccuracyMps = speedAccuracyMps,
)

/** A point [north] and [east] meters away from ([latitude], [longitude]); fine for field-scale offsets. */
fun offsetMeters(latitude: Double, longitude: Double, north: Double, east: Double): Pair<Double, Double> {
    val metersPerDegree = Math.PI * 6_371_008.8 / 180.0
    return (latitude + north / metersPerDegree) to (longitude + east / (metersPerDegree * Math.cos(Math.toRadians(latitude))))
}

/**
 * Scriptable [LocationSource]. Tracks how many update streams are open so tests can prove
 * GPS is released (no location-update leak) and which update profile was requested.
 */
class FakeLocationSource(
    var permission: LocationPermission = LocationPermission.PRECISE,
    var locationEnabled: Boolean = true,
) : LocationSource {
    override val lastFix = MutableStateFlow<GpsFix?>(null)
    val fixes = MutableSharedFlow<GpsFix>(extraBufferCapacity = 64)
    val requestedProfiles = mutableListOf<LocationUpdateProfile>()
    var openStreams = 0
        private set

    override fun permission() = permission
    override fun isLocationEnabled() = locationEnabled
    override fun updates(profile: LocationUpdateProfile): Flow<GpsFix> = flow {
        requestedProfiles += profile
        openStreams++
        try {
            fixes.collect { emit(it) }
        } finally {
            openStreams--
        }
    }
}
