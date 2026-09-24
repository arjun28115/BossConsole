package ai.rever.boss.components.dialogs

import ai.rever.boss.components.home.LocalPluginStates
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
internal fun mcpPolicyPluginNames(): Map<String, String> {
    val flow = LocalPluginStates.current
    val states = if (flow == null) emptyMap() else flow.collectAsState().value
    return states.mapValues { it.value.manifest.displayName }
}

internal fun policySectionName(
    providerId: String,
    names: Map<String, String>,
): String =
    names[providerId]?.takeIf { it.isNotBlank() }
        ?: providerId
            .substringAfterLast('.')
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { it.titlecase() }
