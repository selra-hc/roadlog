package app.roadlog.dashcam.ui.components.WelcomeScreen.pages

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.R
import app.roadlog.dashcam.ui.BIG_PRIMARY_BUTTON_SIZE
import app.roadlog.dashcam.ui.components.atoms.PermissionRequester

// §6.1/§14 step 10: requests ACCESS_FINE_LOCATION with rationale during onboarding —
// this is the only place in the app that ever actively requests it (LocationTracker
// itself only ever passively checks/degrades, never prompts), so without this page real
// users would never see a system permission dialog for it at all and the watermark's
// speed line would always read "-- km/h". Uses the same `PermissionRequester` component
// camera/mic/storage requests use elsewhere, for a consistent request/rationale/
// permanently-denied flow — location just gets its own dedicated explanatory page here
// (rather than being folded into an existing page) since, unlike camera/mic, it's
// entirely optional and deserves its own "why" before asking.
@Composable
fun LocationPermissionPage(
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(128.dp),
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                stringResource(R.string.ui_welcome_location_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.ui_welcome_location_message),
            )
        }
        Spacer(modifier = Modifier.weight(1f))

        PermissionRequester(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            icon = Icons.Default.LocationOn,
            onPermissionAvailable = onContinue,
        ) { trigger ->
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) {
                Button(
                    onClick = trigger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BIG_PRIMARY_BUTTON_SIZE),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.ui_welcome_location_grant_label))
                }
                // Always reachable, even after declining/permanently-denying the system
                // dialog above (§6.1: this permission is optional, never blocks
                // recording) — there is no dead end on this page.
                TextButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ui_welcome_location_skip_label))
                }
            }
        }
    }
}
