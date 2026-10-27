package de.lltntplanet.adhsapp.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lltntplanet.adhsapp.data.settings.AppSettings
import de.lltntplanet.adhsapp.ui.Tab

/** Seiten, die laut Einstellungen in der unteren Leiste stehen (leer = alle). */
fun AppSettings.barTabs(): List<Tab> =
    if (barTabs.isEmpty()) Tab.entries else Tab.entries.filter { it.route in barTabs }

/** Seiten, die nicht in der Leiste stehen und unter „Weitere“ erreichbar sind. */
fun AppSettings.moreTabs(): List<Tab> = Tab.entries - barTabs().toSet()

const val MORE_ROUTE = "more"

/**
 * Untere Leiste: die gewählten Seiten plus – wenn nicht alle gewählt sind – ein Eintrag „Weitere“,
 * der eine kleine Liste mit den übrigen Seiten öffnet. Dort lässt sich die Leiste auch anpassen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomBar(
    settings: AppSettings,
    currentRoute: String?,
    onOpenTab: (Tab) -> Unit,
    onSaveBarTabs: (Set<String>) -> Unit,
) {
    val inBar = settings.barTabs()
    val more = settings.moreTabs()
    var showMore by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    NavigationBar {
        inBar.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onOpenTab(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { BarLabel(tab.label) },
                modifier = Modifier.testTag("tab-${tab.route}"),
            )
        }
        if (more.isNotEmpty()) {
            NavigationBarItem(
                selected = more.any { it.route == currentRoute },
                onClick = { showMore = true },
                icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = null) },
                label = { BarLabel("Weitere") },
                modifier = Modifier.testTag("tab-$MORE_ROUTE"),
            )
        }
    }

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(bottom = 24.dp)) {
                more.forEach { tab ->
                    MoreRow(tab.label, tab.icon, selected = currentRoute == tab.route) {
                        showMore = false
                        onOpenTab(tab)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MoreRow("Leiste anpassen", Icons.Filled.Tune, selected = false) {
                    showMore = false
                    showEditor = true
                }
            }
        }
    }
    if (showEditor) {
        BarEditorDialog(settings, onDismiss = { showEditor = false }) { showEditor = false; onSaveBarTabs(it) }
    }
}

@Composable
private fun BarLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false, fontSize = 9.sp, letterSpacing = 0.sp)
}

@Composable
private fun MoreRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** Auswahl, welche Seiten in der unteren Leiste stehen; mindestens eine muss gewählt bleiben. */
@Composable
fun BarEditorDialog(settings: AppSettings, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(settings.barTabs().map { it.route }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Untere Leiste anpassen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Diese Seiten stehen unten in der Leiste. Alle anderen findest du unter „Weitere“.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Tab.entries.forEach { tab ->
                    val checked = tab.route in chosen
                    val canUncheck = chosen.size > 1
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !checked || canUncheck) {
                                chosen = if (checked) chosen - tab.route else chosen + tab.route
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !checked || canUncheck,
                            onCheckedChange = { on -> chosen = if (on) chosen + tab.route else chosen - tab.route },
                        )
                        Icon(tab.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(tab.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Alle gewählt = Vorgabe; leer speichern, damit neue Seiten später automatisch erscheinen.
                onSave(if (chosen.size == Tab.entries.size) emptySet() else chosen)
            }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
