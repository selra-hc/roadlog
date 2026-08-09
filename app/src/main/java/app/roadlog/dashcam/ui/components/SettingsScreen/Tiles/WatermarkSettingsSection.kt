package app.roadlog.dashcam.ui.components.SettingsScreen.Tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.db.AppSettings
import app.roadlog.dashcam.db.WatermarkSettings
import app.roadlog.dashcam.ui.components.SettingsScreen.atoms.WatermarkPreview
import kotlinx.coroutines.launch

// §9.3/§9.4's "Watermark" settings group. The live preview (§9.4) has to update
// instantly as the user toggles enable/moves the corner/switches km<->mph, even before
// those changes are persisted — so this section owns a local, in-memory *draft* copy of
// `WatermarkSettings` (`remember { mutableStateOf(...) }`), seeded once from the
// persisted value. The preview below reads that draft directly. Every tile's
// `onChange` callback goes through `updateDraft`, which does both things the draft is
// for: (1) updates the local `draft` var so the preview recomposes immediately, and
// (2) writes the same value through to the DataStore-backed `AppSettings`, so the change
// is actually persisted. The draft is intentionally never re-synced from the persisted
// `AppSettings` Flow after the initial seed — every write to that Flow while this screen
// is open originates from `updateDraft` itself, so the two never actually disagree.
@Composable
fun WatermarkSettingsSection(
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val dataStore = LocalContext.current.dataStore

    var draft by remember { mutableStateOf(settings.watermark) }

    fun updateDraft(newValue: WatermarkSettings) {
        draft = newValue

        scope.launch {
            dataStore.updateData {
                it.setWatermarkSettings(newValue)
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DividerTitle(
            title = stringResource(R.string.ui_settings_sections_watermark_title),
            description = stringResource(R.string.ui_settings_sections_watermark_description),
        )
        WatermarkPreview(
            watermark = draft,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        WatermarkEnabledTile(
            enabled = draft.enabled,
            onChange = { updateDraft(draft.copy(enabled = it)) },
        )
        WatermarkCornerTile(
            corner = draft.corner,
            onChange = { updateDraft(draft.copy(corner = it)) },
        )
        WatermarkSpeedUnitTile(
            speedUnit = draft.speedUnit,
            onChange = { updateDraft(draft.copy(speedUnit = it)) },
        )
    }
}
