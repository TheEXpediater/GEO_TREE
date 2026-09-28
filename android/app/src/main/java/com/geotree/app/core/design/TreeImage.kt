package com.geotree.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.geotree.app.R
import com.geotree.app.appContainer
import com.geotree.app.core.database.TreeEntity
import java.io.File

/** Shows this device's photo if present, otherwise the synced server copy, otherwise a placeholder. */
@Composable
fun TreeImage(tree: TreeEntity, modifier: Modifier = Modifier) {
    val apiProvider = LocalContext.current.appContainer.apiProvider
    val model by produceState<Any?>(initialValue = null, tree.localImagePath, tree.remoteImagePath) {
        val local = tree.localImagePath?.let(::File)?.takeIf { it.exists() }
        value = local ?: tree.remoteImagePath?.let { apiProvider.imageUrl(it) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (model == null) {
            Placeholder()
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = "Photo of tree ${tree.treeCode}",
                contentScale = ContentScale.Crop,
                error = { Placeholder() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Placeholder() {
    Icon(
        painterResource(R.drawable.ic_photo_camera),
        contentDescription = "No photo",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.size(32.dp),
    )
}
