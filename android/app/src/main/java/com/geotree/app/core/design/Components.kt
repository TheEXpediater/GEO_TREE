package com.geotree.app.core.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geotree.app.R
import com.geotree.app.core.database.SyncStatus
import com.geotree.app.core.location.GpsQuality

@Composable
fun GeoTreeLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Image(
        painter = painterResource(R.drawable.ic_geo_tree_logo),
        contentDescription = "GEO Tree logo",
        modifier = modifier.size(size),
    )
}

enum class PillTone { Neutral, Amber, Good, Warning, Error }

@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier, description: String = text) {
    val (container, content) = when (tone) {
        PillTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        PillTone.Amber -> GeoColors.GpsAmberLight to Color(0xFF6B4300)
        PillTone.Good -> GeoColors.SuccessLight to Color(0xFF14492A)
        PillTone.Warning -> GeoColors.ClayLight to Color(0xFF5A2A0E)
        PillTone.Error -> GeoColors.ErrorLight to Color(0xFF601410)
    }
    val dot = when (tone) {
        PillTone.Neutral -> MaterialTheme.colorScheme.outline
        PillTone.Amber -> GeoColors.GpsAmber
        PillTone.Good -> GeoColors.Success
        PillTone.Warning -> GeoColors.Clay
        PillTone.Error -> GeoColors.Error
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Spacer(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun GpsQuality.tone(): PillTone = when (this) {
    GpsQuality.GOOD -> PillTone.Good
    GpsQuality.ACCEPTABLE -> PillTone.Amber
    GpsQuality.LOW -> PillTone.Warning
}

fun GpsQuality.label(): String = when (this) {
    GpsQuality.GOOD -> "Good"
    GpsQuality.ACCEPTABLE -> "Acceptable"
    GpsQuality.LOW -> "Low accuracy"
}

fun SyncStatus.tone(): PillTone = when (this) {
    SyncStatus.PENDING -> PillTone.Amber
    SyncStatus.SYNCING -> PillTone.Neutral
    SyncStatus.SYNCED -> PillTone.Good
    SyncStatus.FAILED -> PillTone.Error
}

fun SyncStatus.label(): String = when (this) {
    SyncStatus.PENDING -> "Pending sync"
    SyncStatus.SYNCING -> "Syncing"
    SyncStatus.SYNCED -> "Synced"
    SyncStatus.FAILED -> "Sync failed"
}
