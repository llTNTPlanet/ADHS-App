package de.lltntplanet.adhsapp.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.lltntplanet.adhsapp.ui.birthdays.BirthdaysScreen
import de.lltntplanet.adhsapp.ui.calendar.CalendarScreen
import de.lltntplanet.adhsapp.ui.calendar.EventEditorScreen
import de.lltntplanet.adhsapp.ui.components.appContainer
import de.lltntplanet.adhsapp.ui.health.HealthScreen
import de.lltntplanet.adhsapp.ui.navigation.AppBottomBar
import de.lltntplanet.adhsapp.ui.nutrition.NutritionScreen
import de.lltntplanet.adhsapp.ui.routines.RoutinesScreen
import de.lltntplanet.adhsapp.ui.settings.SettingsScreen
import de.lltntplanet.adhsapp.ui.settings.SetupScreen
import de.lltntplanet.adhsapp.ui.tasks.TasksScreen
import de.lltntplanet.adhsapp.ui.today.TodayScreen
import de.lltntplanet.adhsapp.ui.update.UpdateDialogHost
import kotlinx.coroutines.launch
import java.time.LocalDateTime

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Heute", Icons.Filled.WbSunny),
    CALENDAR("calendar", "Kalender", Icons.Filled.CalendarMonth),
    TASKS("tasks", "Aufgaben", Icons.Filled.TaskAlt),
    ROUTINES("routines", "Routinen", Icons.Filled.Medication),
    BIRTHDAYS("birthdays", "Geburtstage", Icons.Filled.Cake),
    HEALTH("health", "Gesundheit", Icons.Filled.Favorite),
    NUTRITION("nutrition", "Essen", Icons.Filled.Restaurant),
}

private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot(requestedTab: Tab?, onTabHandled: () -> Unit) {
    val nav = rememberNavController()
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    // Welche Seiten in der Leiste stehen, kommt aus den Einstellungen; bis sie geladen sind, keine Leiste zeichnen.
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)

    // Erst navigieren, wenn der NavHost seinen Graphen gesetzt hat (sonst Absturz beim ersten Start).
    val graphReady = backStack != null
    var onboardingChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(graphReady) {
        if (graphReady && !onboardingChecked) {
            onboardingChecked = true
            if (!container.settings.current().onboardingDone) nav.navigate("setup")
        }
    }
    LaunchedEffect(requestedTab, graphReady) {
        if (requestedTab != null && graphReady) {
            nav.openTab(requestedTab)
            onTabHandled()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val s = settings
            if (showBar && s != null) {
                AppBottomBar(
                    settings = s,
                    currentRoute = route,
                    onOpenTab = { nav.openTab(it) },
                    onSaveBarTabs = { chosen -> scope.launch { container.settings.update { it.copy(barTabs = chosen) } } },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.TODAY.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Tab.TODAY.route) {
                TodayScreen(
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenSetup = { nav.navigate("setup") },
                    onOpenCalendar = { nav.openTab(Tab.CALENDAR) },
                    onOpenTasks = { nav.openTab(Tab.TASKS) },
                    onOpenRoutines = { nav.openTab(Tab.ROUTINES) },
                    onOpenBirthdays = { nav.openTab(Tab.BIRTHDAYS) },
                    onOpenHealth = { nav.openTab(Tab.HEALTH) },
                    onEditEvent = { nav.navigate("event/edit/$it") },
                )
            }
            composable(Tab.CALENDAR.route) {
                CalendarScreen(
                    onCreateEvent = { nav.navigate("event/new?start=$it") },
                    onEditEvent = { nav.navigate("event/edit/$it") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable(Tab.TASKS.route) { TasksScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable(Tab.ROUTINES.route) { RoutinesScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable(Tab.BIRTHDAYS.route) { BirthdaysScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable(Tab.HEALTH.route) { HealthScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable(Tab.NUTRITION.route) { NutritionScreen(onOpenSettings = { nav.navigate("settings") }) }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() }, onOpenSetup = { nav.navigate("setup") })
            }
            composable("setup") {
                SetupScreen(
                    onDone = {
                        scope.launch { container.settings.update { it.copy(onboardingDone = true) } }
                        if (!nav.popBackStack()) nav.openTab(Tab.TODAY)
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                "event/new?start={start}",
                arguments = listOf(navArgument("start") { type = NavType.StringType; nullable = true }),
            ) { entry ->
                val start = entry.arguments?.getString("start")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                EventEditorScreen(eventId = null, initialStart = start, onClose = { nav.popBackStack() })
            }
            composable("event/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                EventEditorScreen(eventId = entry.arguments?.getLong("id"), initialStart = null, onClose = { nav.popBackStack() })
            }
        }
    }
    UpdateDialogHost()
}
