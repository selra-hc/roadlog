package app.roadlog.dashcam.ui.components.RecorderScreen.molecules

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.CutFeedButton
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.DeleteButton
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.PauseResumeButton
import app.roadlog.dashcam.ui.components.RecorderScreen.atoms.SaveButton
import app.roadlog.dashcam.ui.utils.RandomStack
import kotlinx.coroutines.delay

@Composable
fun RecordingControl(
    modifier: Modifier = Modifier,
    orientation: Int = LocalConfiguration.current.orientation,
    initialDelay: Long = 0L,
    isPaused: Boolean,
    // Whether the initial reveal-the-buttons-one-by-one animation should run — derived
    // once from `recordingTime` by the caller (`rememberInitialRecordingAnimation`) rather
    // than recomputed here from the raw, per-second-ticking `recordingTime` itself, so this
    // composable's parameters stay unchanged (and it stays skippable) across ticks.
    animateIn: Boolean,
    isCut: Boolean,
    // Off when "Process Video" (§9.1/§11) is disabled — discarding an unsaved recording
    // wholesale doesn't fit once saving no longer produces one combined file the user
    // could otherwise lose; the remaining buttons re-center to fill the gap.
    showDeleteButton: Boolean,
    onDelete: () -> Unit,
    onPauseResume: () -> Unit,
    onSaveAndStop: () -> Unit,
    onSaveCurrent: () -> Unit,
    onCutFeed: () -> Unit,
) {
    var deleteButtonAlphaIsIn by rememberSaveable {
        mutableStateOf(false)
    }
    val deleteButtonAlpha by animateFloatAsState(
        if (deleteButtonAlphaIsIn) 1f else 0f,
        label = "deleteButtonAlpha",
        animationSpec = tween(durationMillis = 500)
    )

    var pauseButtonAlphaIsIn by rememberSaveable {
        mutableStateOf(false)
    }
    val pauseButtonAlpha by animateFloatAsState(
        if (pauseButtonAlphaIsIn) 1f else 0f,
        label = "pauseButtonAlpha",
        animationSpec = tween(durationMillis = 500)
    )

    var saveButtonAlphaIsIn by rememberSaveable {
        mutableStateOf(false)
    }
    val saveButtonAlpha by animateFloatAsState(
        if (saveButtonAlphaIsIn) 1f else 0f,
        label = "saveButtonAlpha",
        animationSpec = tween(durationMillis = 500)
    )

    var cutFeedButtonAlphaIsIn by rememberSaveable {
        mutableStateOf(false)
    }
    val cutFeedButtonAlpha by animateFloatAsState(
        if (cutFeedButtonAlphaIsIn) 1f else 0f,
        label = "cutFeedButtonAlpha",
        animationSpec = tween(durationMillis = 500)
    )

    LaunchedEffect(animateIn) {
        if (animateIn) {
            delay(initialDelay)

            val stack = RandomStack.of(arrayOf(1, 2, 3, 4).asIterable())

            while (!stack.isEmpty()) {
                when (stack.popRandom()) {
                    1 -> {
                        deleteButtonAlphaIsIn = true
                    }

                    2 -> {
                        pauseButtonAlphaIsIn = true
                    }

                    3 -> {
                        saveButtonAlphaIsIn = true
                    }

                    4 -> {
                        cutFeedButtonAlphaIsIn = true
                    }
                }

                delay(250)
            }
        }
    }

    when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> {
            if (showDeleteButton) {
                // A 2x2 square rather than a single-column stack of all 4 buttons:
                // stacked one-per-line, the column's total height (Save 48dp + Pause's
                // ~56dp FAB + CutFeed 48dp + Delete 48dp + 3x16dp gaps ~= 248dp) could
                // exceed the available height in landscape (short axis), pushing the
                // last button (Delete) off-screen/invisible — a real reported bug. Two
                // rows of two halve that height (~120dp) regardless of how tall the
                // panel hosting this is allowed to get.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = modifier,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    ) {
                        Box(
                            modifier = Modifier.alpha(saveButtonAlpha),
                            contentAlignment = Alignment.Center,
                        ) {
                            SaveButton(
                                onSave = onSaveAndStop,
                                onLongClick = onSaveCurrent,
                            )
                        }

                        Box(
                            modifier = Modifier.alpha(pauseButtonAlpha),
                            contentAlignment = Alignment.Center,
                        ) {
                            PauseResumeButton(
                                isPaused = isPaused,
                                onChange = onPauseResume,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    ) {
                        Box(
                            modifier = Modifier.alpha(cutFeedButtonAlpha),
                            contentAlignment = Alignment.Center,
                        ) {
                            CutFeedButton(
                                isCut = isCut,
                                onCutFeed = onCutFeed,
                            )
                        }

                        Box(
                            modifier = Modifier.alpha(deleteButtonAlpha),
                            contentAlignment = Alignment.Center,
                        ) {
                            DeleteButton(onDelete = onDelete)
                        }
                    }
                }
            } else {
                // Only 3 buttons left without Delete — no more height-overflow risk
                // (that was specifically the 4-button case above), so a single row,
                // same style as portrait's, replaces the 2x2 grid entirely.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    modifier = modifier,
                ) {
                    Box(
                        modifier = Modifier.alpha(pauseButtonAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        PauseResumeButton(
                            isPaused = isPaused,
                            onChange = onPauseResume,
                        )
                    }

                    Box(
                        modifier = Modifier.alpha(cutFeedButtonAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        CutFeedButton(
                            isCut = isCut,
                            onCutFeed = onCutFeed,
                        )
                    }

                    Box(
                        modifier = Modifier.alpha(saveButtonAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        SaveButton(
                            onSave = onSaveAndStop,
                            onLongClick = onSaveCurrent,
                        )
                    }
                }
            }
        }

        else -> {
            // `Arrangement.spacedBy(_, CenterHorizontally)` — not a two-`weight(1f)`-box
            // "centering" trick, which only worked cleanly with 3 buttons of similar
            // size. With 4 buttons of visibly different sizes (Pause is a full-size
            // `FloatingActionButton`, larger than the 48dp Delete/CutFeed/Save squares),
            // that approach packed Pause and CutFeed edge-to-edge in the middle with no
            // gap while Delete/Save floated centered in their own half — uneven and
            // asymmetric. Fixed-size gaps between every button (rather than gaps that
            // only appear from leftover space around two of them) reads as evenly
            // distributed regardless of how many buttons are shown or any one button's
            // own size — conditionally omitting Delete here needs no other change, the
            // remaining 3 simply re-center as a group.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                modifier = modifier.fillMaxWidth(),
            ) {
                if (showDeleteButton) {
                    Box(
                        modifier = Modifier.alpha(deleteButtonAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        DeleteButton(onDelete = onDelete)
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .alpha(pauseButtonAlpha),
                ) {
                    PauseResumeButton(
                        isPaused = isPaused,
                        onChange = onPauseResume,
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .alpha(cutFeedButtonAlpha),
                ) {
                    CutFeedButton(
                        isCut = isCut,
                        onCutFeed = onCutFeed,
                    )
                }

                Box(
                    modifier = Modifier.alpha(saveButtonAlpha),
                    contentAlignment = Alignment.Center,
                ) {
                    SaveButton(
                        onSave = onSaveAndStop,
                        onLongClick = onSaveCurrent,
                    )
                }
            }
        }
    }
}