package com.geotree.app.feature.locator.map

import android.graphics.PointF
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
import kotlin.math.hypot
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** A camera target. [nonce] makes repeated requests for the same place distinct. */
data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double, val nonce: Long = 0)

/**
 * All map-provider code lives in this file (MapLibre + OpenStreetMap raster tiles, no API key).
 * Tree records never depend on tiles: markers come from the [trees] list, which comes from Room.
 * Swapping to an offline tile source later only changes [STYLE_JSON].
 */
@Composable
fun TreeMap(
    trees: List<TreeEntity>,
    selectedTreeId: String?,
    currentFix: GpsFix?,
    cameraCommand: MapCamera?,
    initialCamera: MapCamera?,
    topInset: Dp,
    bottomInset: Dp,
    onTreeClick: (String) -> Unit,
    onMapClick: () -> Unit,
    onCameraIdle: (MapCamera) -> Unit,
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
    val latestCameraIdle by rememberUpdatedState(onCameraIdle)
    val latestFix by rememberUpdatedState(currentFix)

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
            val start = initialCamera ?: DEFAULT_CAMERA
            m.cameraPosition = CameraPosition.Builder().target(LatLng(start.latitude, start.longitude)).zoom(start.zoom).build()
            with(density) {
                val top = topInset.roundToPx()
                val bottom = bottomInset.roundToPx()
                m.uiSettings.setCompassMargins(0, top, 16.dp.roundToPx(), 0)
                m.uiSettings.setLogoMargins(16.dp.roundToPx(), 0, 0, bottom)
                m.uiSettings.setAttributionMargins(16.dp.roundToPx() + 96.dp.roundToPx(), 0, 0, bottom)
            }
            m.setStyle(Style.Builder().fromJson(STYLE_JSON)) { loaded ->
                installLayers(loaded)
                style = loaded
            }
            m.addOnMapClickListener { point ->
                val screen = m.projection.toScreenLocation(point)
                val slop = with(density) { 22.dp.toPx() }
                val hit = m.queryRenderedFeatures(RectF(screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop), TREE_LAYER)
                val id = hit.firstOrNull()?.getStringProperty("id")
                if (id != null) latestTreeClick(id) else latestMapClick()
                true
            }
            m.addOnCameraMoveListener { style?.let { updateAccuracyHalo(m, it, latestFix, density.density) } }
            m.addOnCameraIdleListener {
                val target = m.cameraPosition.target ?: return@addOnCameraIdleListener
                latestCameraIdle(MapCamera(target.latitude, target.longitude, m.cameraPosition.zoom))
            }
            map = m
        }
    }

    LaunchedEffect(style, trees, selectedTreeId) {
        val s = style ?: return@LaunchedEffect
        // Selected marker last so it renders on top.
        val features = trees.sortedBy { it.id == selectedTreeId }.map { tree ->
            Feature.fromGeometry(Point.fromLngLat(tree.longitude, tree.latitude)).apply {
                addStringProperty("id", tree.id)
                addStringProperty("sync", tree.syncStatus.name)
                addBooleanProperty("selected", tree.id == selectedTreeId)
            }
        }
        s.getSourceAs<GeoJsonSource>(TREE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, currentFix) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val fix = currentFix
        val collection = if (fix == null) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(fix.longitude, fix.latitude)))
        s.getSourceAs<GeoJsonSource>(DEVICE_SOURCE)?.setGeoJson(collection)
        updateAccuracyHalo(m, s, fix, density.density)
    }

    LaunchedEffect(map, cameraCommand) {
        val m = map ?: return@LaunchedEffect
        val command = cameraCommand ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(command.latitude, command.longitude), command.zoom), 700)
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.semantics { contentDescription = "Field map with ${trees.size} tree markers" },
    )
}

private fun installLayers(style: Style) {
    style.addSource(GeoJsonSource(TREE_SOURCE))
    style.addSource(GeoJsonSource(DEVICE_SOURCE))
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
    style.addLayer(
        CircleLayer(DEVICE_DOT_LAYER, DEVICE_SOURCE).withProperties(
            PropertyFactory.circleColor(DEVICE_COLOR),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    style.addLayer(
        CircleLayer(TREE_LAYER, TREE_SOURCE).withProperties(
            PropertyFactory.circleRadius(
                Expression.switchCase(Expression.eq(Expression.get("selected"), Expression.literal(true)), Expression.literal(13f), Expression.literal(9f)),
            ),
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("sync"),
                    Expression.color(GeoColors.GpsAmber.toArgb()),
                    Expression.stop(SyncStatus.SYNCED.name, Expression.color(GeoColors.Forest.toArgb())),
                    Expression.stop(SyncStatus.FAILED.name, Expression.color(GeoColors.Error.toArgb())),
                ),
            ),
            PropertyFactory.circleStrokeColor(
                Expression.switchCase(
                    Expression.eq(Expression.get("selected"), Expression.literal(true)),
                    Expression.color(GeoColors.Clay.toArgb()),
                    Expression.color(android.graphics.Color.WHITE),
                ),
            ),
            PropertyFactory.circleStrokeWidth(3f),
        ),
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

private const val TREE_SOURCE = "geo-trees"
private const val TREE_LAYER = "geo-trees-circles"
private const val DEVICE_SOURCE = "geo-device"
private const val DEVICE_HALO_LAYER = "geo-device-halo"
private const val DEVICE_DOT_LAYER = "geo-device-dot"
private val DEVICE_COLOR = android.graphics.Color.rgb(0x2B, 0x6C, 0xB0)

/** Initial view before any GPS fix: the Philippines. Not a tree location. */
private val DEFAULT_CAMERA = MapCamera(12.8797, 121.7740, 5.0)

private const val STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#EDE6D6" } },
    { "id": "osm", "type": "raster", "source": "osm" }
  ]
}
"""
