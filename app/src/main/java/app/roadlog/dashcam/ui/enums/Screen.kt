package app.roadlog.dashcam.ui.enums

sealed class Screen(val route: String) {
    data object Recorder : Screen("recorder")
    data object Settings : Screen("settings")
    data object Welcome : Screen("welcome")
    data object About : Screen("about")
}
