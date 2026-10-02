package com.geotree.app.feature.locator.map

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.geotree.app.feature.locator.HeadingUpCamera
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.database.TreeEntity
import com.geotree.app.core.design.GeoColors
import com.geotree.app.core.location.GpsFix
import com.geotree.app.feature.navigation.GeoPoint
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlin.math.min
import org.maplibre.android.MapLibre
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Where the camera rests; restored when returning to the Map tab. */
data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

/** A one-off camera move requested by the ViewModel. [nonce] makes repeated requests distinct. */
sealed interface CameraCommand {
    val nonce: Long

    /** [zoom] null keeps the user's current zoom (used by Follow Location); [bearing] null keeps the rotation. */
    data class Center(
        val latitude: Double,
        val longitude: Double,
        val zoom: Double?,
        val durationMillis: Int = 700,
        override val nonce: Long,
        val bearing: Double? = null,
    ) : CameraCommand

    /** Shows all [points], never zooming in past [maxZoom]. */
    data class Fit(val points: List<GeoPoint>, val maxZoom: Double, override val nonce: Long) : CameraCommand
}

/**
 * All map-provider code lives in this package (MapLibre). Tree records never depend on tiles:
 * markers come from [trees] (Room), the guidance line from [route]. The basemap is whatever
 * [styleJson] describes (see [MapSourceType]); the GEO Tree layers are added on top of any style.
 */
@Composable
fun TreeMap(
    styleJson: String,
    trees: List<TreeEntity>,
    selectedTreeId: String?,
    destinationTreeId: String?,
    route: List<GeoPoint>?,
    currentFix: GpsFix?,
    /** Phone heading (degrees from true north): turns the location dot into a rotating chevron. */
    deviceHeading: Double?,
    cameraCommand: CameraCommand?,
    /** Non-null in Heading Up: keeps the camera on the user with map bearing = phone heading. */
    headingUp: HeadingUpCamera?,
    initialCamera: MapCamera,
    minZoom: Double,
    topInset: Dp,
    bottomInset: Dp,
    fitBottomInset: Dp,
    onTreeClick: (String) -> Unit,
    onMapClick: () -> Unit,
    onUserGesture: () -> Unit,
    onCameraIdle: (MapCamera) -> Unit,
    onBearingChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val latestTreeClick by rememberUpdatedState(onTreeClick)
    val latestMapClick by rememberUpdatedState(onMapClick)
    val latestGesture by rememberUpdatedState(onUserGesture)
    val latestCameraIdle by rememberUpdatedState(onCameraIdle)
    val latestBearing by rememberUpdatedState(onBearingChanged)
    val latestFix by rememberUpdatedState(currentFix)
    val latestTopInset by rememberUpdatedState(topInset)
    val latestFitBottomInset by rememberUpdatedState(fitBottomInset)

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(initialCamera.latitude, initialCamera.longitude))
                .zoom(initialCamera.zoom)
                .build()
            // GEO Tree draws its own compass/orientation control; MapLibre's would duplicate it.
            m.uiSettings.isCompassEnabled = false
            m.addOnMapClickListener { point ->
                val screen = m.projection.toScreenLocation(point)
                val slop = with(density) { 22.dp.toPx() }
                val hit = m.queryRenderedFeatures(RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop), TREE_LAYER)
                val id = hit.firstOrNull()?.getStringProperty("id")
                if (id != null) latestTreeClick(id) else latestMapClick()
                true
            }
            // A finger panning or rotating the map ends Follow / Heading Up; pinch-zoom keeps following.
            // MapLibre's gesture callbacks fire only for touches, never for our own camera animations
            // (the constant Heading Up eases would otherwise hide a drag that started mid-animation).
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) = latestGesture()
                override fun onMove(detector: MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            })
            m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: RotateGestureDetector) = latestGesture()
                override fun onRotate(detector: RotateGestureDetector) = Unit
                override fun onRotateEnd(detector: RotateGestureDetector) = Unit
            })
            m.addOnCameraMoveListener {
                style?.let { updateAccuracyHalo(m, it, latestFix, density.density) }
                latestBearing(m.cameraPosition.bearing)
            }
            m.addOnCameraIdleListener {
                val target = m.cameraPosition.target ?: return@addOnCameraIdleListener
                latestCameraIdle(MapCamera(target.latitude, target.longitude, m.cameraPosition.zoom))
            }
            map = m
        }
    }

    // (Re)load the basemap style. GEO Tree layers are re-added to every style.
    LaunchedEffect(map, styleJson) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(styleJson)) { loaded ->
            installLayers(loaded, chevronBitmap(density.density))
            style = loaded
        }
    }

    // Keeps the offline map from zooming out to an empty world; online mode has no floor.
    LaunchedEffect(map, minZoom) {
        map?.setMinZoomPreference(minZoom)
    }

    LaunchedEffect(map, topInset, bottomInset) {
        val m = map ?: return@LaunchedEffect
        with(density) {
            val top = topInset.roundToPx()
            val bottom = bottomInset.roundToPx()
            val side = 16.dp.roundToPx()
            m.uiSettings.setLogoMargins(side, 0, 0, bottom)
            m.uiSettings.setAttributionMargins(side + 96.dp.roundToPx(), 0, 0, bottom)
        }
    }

    LaunchedEffect(style, trees, selectedTreeId, destinationTreeId) {
        val s = style ?: return@LaunchedEffect
        // Selected and destination markers last so they render on top.
        val features = trees.sortedBy { (it.id == selectedTreeId) || (it.id == destinationTreeId) }.map { tree ->
            Feature.fromGeometry(Point.fromLngLat(tree.longitude, tree.latitude)).apply {
                addStringProperty("id", tree.id)
                addStringProperty("sync", tree.syncStatus.name)
                addBooleanProperty("selected", tree.id == selectedTreeId)
                addBooleanProperty("destination", tree.id == destinationTreeId)
            }
        }
        s.getSourceAs<GeoJsonSource>(TREE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, route) {
        val s = style ?: return@LaunchedEffect
        val line = route?.takeIf { it.size >= 2 }
        val collection = if (line == null) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.longitude, it.latitude) })))
        s.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(collection)
    }

    LaunchedEffect(style, currentFix, deviceHeading) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val fix = currentFix
        val collection = if (fix == null) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude)).apply {
                deviceHeading?.let { addNumberProperty("heading", it) }
            },
        )
        s.getSourceAs<GeoJsonSource>(DEVICE_SOURCE)?.setGeoJson(collection)
        updateAccuracyHalo(m, s, fix, density.density)
    }

    LaunchedEffect(map, cameraCommand) {
        val m = map ?: return@LaunchedEffect
        when (val command = cameraCommand ?: return@LaunchedEffect) {
            is CameraCommand.Center -> {
                val position = CameraPosition.Builder()
                    .target(LatLng(command.latitude, command.longitude))
                    .zoom(command.zoom ?: m.cameraPosition.zoom)
                    .bearing(command.bearing ?: m.cameraPosition.bearing)
                    .tilt(m.cameraPosition.tilt)
                    .build()
                m.animateCamera(CameraUpdateFactory.newCameraPosition(position), command.durationMillis)
            }
            is CameraCommand.Fit -> {
                // A Fit usually comes with UI that changes the bottom overlay (the navigation panel
                // appears). Let that layout settle so the padding covers what will actually hide the map.
                delay(FIT_SETTLE_MILLIS)
                val distinct = command.points.distinct()
                if (distinct.size < 2) {
                    distinct.firstOrNull()?.let { m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), command.maxZoom), 700) }
                } else {
                    val bounds = LatLngBounds.Builder().includes(distinct.map { LatLng(it.latitude, it.longitude) }).build()
                    val padding = with(density) {
                        val side = 48.dp.roundToPx()
                        intArrayOf(side, (latestTopInset + 24.dp).roundToPx(), side, (latestFitBottomInset + 24.dp).roundToPx())
                    }
                    val fitted = m.getCameraForLatLngBounds(bounds, padding)
                    if (fitted?.target != null) {
                        val camera = CameraPosition.Builder(fitted).zoom(min(fitted.zoom, command.maxZoom)).bearing(m.cameraPosition.bearing).build()
                        m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 800)
                    }
                }
            }
        }
    }

    // Heading Up: short eases keep the user centred and the map turning with the phone without
    // queueing long animations (each new sensor reading replaces the previous ease).
    LaunchedEffect(map, headingUp) {
        val m = map ?: return@LaunchedEffect
        val target = headingUp ?: return@LaunchedEffect
        val position = CameraPosition.Builder()
            .target(LatLng(target.latitude, target.longitude))
            .zoom(m.cameraPosition.zoom)
            .bearing(target.bearing)
            .tilt(m.cameraPosition.tilt)
            .build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(position), HEADING_UP_EASE_MILLIS)
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.semantics { contentDescription = "Field map with ${trees.size} tree markers" },
    )
}

private fun installLayers(style: Style, chevron: Bitmap) {
    style.addImage(CHEVRON_IMAGE, chevron)
    style.addSource(GeoJsonSource(ROUTE_SOURCE))
    style.addSource(GeoJsonSource(TREE_SOURCE))
    style.addSource(GeoJsonSource(DEVICE_SOURCE))

    // Field-guidance line: dark casing under a red core, rounded so it reads as a route, not a debug line.
    style.addLayer(
        LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(ROUTE_CASING_COLOR),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(ROUTE_COLOR),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        CircleLayer(DEVICE_HALO_LAYER, DEVICE_SOURCE).withProperties(
            PropertyFactory.circleColor(DEVICE_COLOR),
            PropertyFactory.circleOpacity(0.16f),
            PropertyFactory.circleStrokeColor(DEVICE_COLOR),
            PropertyFactory.circleStrokeOpacity(0.5f),
            PropertyFactory.circleStrokeWidth(1f),
            PropertyFactory.circleRadius(12f),
        ),
    )
    val isDestination = Expression.eq(Expression.get("destination"), Expression.literal(true))
    val isSelected = Expression.eq(Expression.get("selected"), Expression.literal(true))
    style.addLayer(
        CircleLayer(DESTINATION_HALO_LAYER, TREE_SOURCE).withProperties(
            PropertyFactory.circleRadius(24f),
            PropertyFactory.circleColor(ROUTE_COLOR),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleStrokeColor(ROUTE_COLOR),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleStrokeOpacity(0.7f),
        ).withFilter(isDestination),
    )
    style.addLayer(
        CircleLayer(TREE_LAYER, TREE_SOURCE).withProperties(
            PropertyFactory.circleRadius(
                Expression.switchCase(isDestination, Expression.literal(13f), isSelected, Expression.literal(12f), Expression.literal(9f)),
            ),
            // Trees are green; the destination is red. Sync state shows as a thin ring, not a new fill colour.
            PropertyFactory.circleColor(
                Expression.switchCase(
                    isDestination, Expression.color(ROUTE_COLOR),
                    Expression.eq(Expression.get("sync"), Expression.literal(SyncStatus.SYNCED.name)), Expression.color(GeoColors.Forest.toArgb()),
                    Expression.color(GeoColors.Moss.toArgb()),
                ),
            ),
            PropertyFactory.circleStrokeColor(
                Expression.switchCase(
                    isDestination, Expression.color(android.graphics.Color.WHITE),
                    isSelected, Expression.color(GeoColors.Clay.toArgb()),
                    Expression.eq(Expression.get("sync"), Expression.literal(SyncStatus.FAILED.name)), Expression.color(GeoColors.Error.toArgb()),
                    Expression.eq(Expression.get("sync"), Expression.literal(SyncStatus.SYNCED.name)), Expression.color(android.graphics.Color.WHITE),
                    Expression.color(GeoColors.GpsAmber.toArgb()),
                ),
            ),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    // Without a compass heading the position is a dot; with one it is a chevron pointing where the
    // phone faces (rotation is relative to map north, so it stays correct in Heading Up too).
    style.addLayer(
        CircleLayer(DEVICE_DOT_LAYER, DEVICE_SOURCE).withProperties(
            PropertyFactory.circleColor(DEVICE_COLOR),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ).withFilter(Expression.not(Expression.has("heading"))),
    )
    style.addLayer(
        SymbolLayer(DEVICE_CHEVRON_LAYER, DEVICE_SOURCE).withProperties(
            PropertyFactory.iconImage(CHEVRON_IMAGE),
            PropertyFactory.iconRotate(Expression.get("heading")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ).withFilter(Expression.has("heading")),
    )
}

/** Sizes the halo to the reported GPS accuracy by projecting a point [accuracy] meters north. */
private fun updateAccuracyHalo(map: MapLibreMap, style: Style, fix: GpsFix?, density: Float) {
    val layer = style.getLayerAs<CircleLayer>(DEVICE_HALO_LAYER) ?: return
    if (fix == null) return
    val center = map.projection.toScreenLocation(LatLng(fix.latitude, fix.longitude))
    val north = map.projection.toScreenLocation(LatLng(fix.latitude + fix.accuracyMeters / 111_320.0, fix.longitude))
    val radiusDp = (distance(center, north) / density).coerceIn(10f, 600f)
    layer.setProperties(PropertyFactory.circleRadius(radiusDp))
}

private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

private const val FIT_SETTLE_MILLIS = 250L
private const val TREE_SOURCE = "geo-trees"
private const val TREE_LAYER = "geo-trees-circles"
private const val DESTINATION_HALO_LAYER = "geo-destination-halo"
private const val DEVICE_SOURCE = "geo-device"
private const val DEVICE_HALO_LAYER = "geo-device-halo"
private const val DEVICE_DOT_LAYER = "geo-device-dot"
private const val DEVICE_CHEVRON_LAYER = "geo-device-chevron"
private const val CHEVRON_IMAGE = "geo-device-chevron-image"
private const val HEADING_UP_EASE_MILLIS = 180
private const val ROUTE_SOURCE = "geo-route"
private const val ROUTE_CASING_LAYER = "geo-route-casing"
private const val ROUTE_LAYER = "geo-route-line"
private val DEVICE_COLOR = android.graphics.Color.rgb(0x2B, 0x6C, 0xB0)
private val ROUTE_COLOR = android.graphics.Color.rgb(0xD6, 0x2F, 0x26)
private val ROUTE_CASING_COLOR = android.graphics.Color.rgb(0x6E, 0x14, 0x10)

/** Blue location chevron with a white outline, pointing up (north) before rotation. */
private fun chevronBitmap(density: Float): Bitmap {
    val size = (34 * density).toInt().coerceAtLeast(24)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val c = size / 2f
    val path = Path().apply {
        moveTo(c, size * 0.08f)
        lineTo(size * 0.86f, size * 0.88f)
        lineTo(c, size * 0.66f)
        lineTo(size * 0.14f, size * 0.88f)
        close()
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DEVICE_COLOR; style = Paint.Style.FILL }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, outline)
    return bitmap
}
