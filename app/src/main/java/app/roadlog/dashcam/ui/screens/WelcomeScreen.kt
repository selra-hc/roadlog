package app.roadlog.dashcam.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.roadlog.dashcam.dataStore
import app.roadlog.dashcam.ui.components.WelcomeScreen.pages.ExplanationPage
import app.roadlog.dashcam.ui.components.WelcomeScreen.pages.LocationPermissionPage
import app.roadlog.dashcam.ui.components.WelcomeScreen.pages.MaxDurationSettingsPage
import app.roadlog.dashcam.ui.components.WelcomeScreen.pages.ReadyPage
import app.roadlog.dashcam.ui.components.WelcomeScreen.pages.ResponsibilityPage
import app.roadlog.dashcam.ui.components.WelcomeScreen.pages.SaveFolderPage
import app.roadlog.dashcam.ui.effects.rememberSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(
    onNavigateToRecorderScreen: () -> Unit
) {
    val context = LocalContext.current
    val dataStore = context.dataStore
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = 0,
        initialPageOffsetFraction = 0f,
        pageCount = { 6 }
    )

    fun finishTutorial() {
        scope.launch {
            dataStore.updateData {
                settings.setHasSeenOnboarding(true)
            }
            onNavigateToRecorderScreen()
        }
    }

    Scaffold() { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),

            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
            ) { position ->
                when (position) {
                    0 -> ExplanationPage(
                        onContinue = {
                            scope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        }
                    )

                    1 -> ResponsibilityPage {
                        scope.launch {
                            pagerState.animateScrollToPage(2)
                        }
                    }

                    // §6.1/§14 step 10 — requests ACCESS_FINE_LOCATION with rationale
                    // during onboarding, the only place in the app that ever actively
                    // does so (skippable — location is optional, §6.1).
                    2 -> LocationPermissionPage {
                        scope.launch {
                            pagerState.animateScrollToPage(3)
                        }
                    }

                    3 -> MaxDurationSettingsPage {
                        scope.launch {
                            pagerState.animateScrollToPage(4)
                        }
                    }

                    4 -> SaveFolderPage(
                        onBack = {
                            scope.launch {
                                pagerState.animateScrollToPage(3)
                            }
                        },
                        onContinue = { saveFolder ->
                            scope.launch {
                                dataStore.updateData {
                                    settings.setSaveFolder(saveFolder)
                                }

                                pagerState.animateScrollToPage(5)
                            }
                        },
                        appSettings = settings
                    )

                    5 -> ReadyPage {
                        finishTutorial()
                    }
                }
            }
        }
    }
}