package com.geotree.app.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.geotree.app.R
import com.geotree.app.core.session.Session

/** Admin tools appear only for the ADMIN role; the server enforces the role on every admin call. */
fun adminSectionVisible(session: Session?): Boolean = session?.isAdmin == true

@Composable
fun AdminSection(onOpenClassifiers: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth().testTag("admin_section")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Administration", style = MaterialTheme.typography.titleMedium)
            Text(
                "Manage the tamarind leaf classes and training images used by the DINOv2 leaf classifier on the local AI server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onOpenClassifiers, modifier = Modifier.fillMaxWidth().testTag("open_classifiers")) {
                Icon(painterResource(R.drawable.ic_leaf), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Leaf Classifiers")
            }
        }
    }
}
