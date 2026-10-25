package de.lltntplanet.adhsapp.ui.nutrition

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.lltntplanet.adhsapp.ui.theme.AdhsTheme

/** Wird von Health Connect geöffnet, wenn der Nutzer wissen will, wofür die App Ernährungsdaten schreibt. */
class HealthConnectRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdhsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Ernährungsdaten und Health Connect", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Die ADHS App schreibt die Mahlzeiten, die du auf der Seite „Essen“ einträgst (Name, Kilokalorien, Mahlzeit, " +
                                "Uhrzeit), in Health Connect. So erscheinen sie auch in Google Health und anderen Gesundheits-Apps, " +
                                "denen du Zugriff gibst.",
                        )
                        Text(
                            "Die App liest keine Daten aus Health Connect. Fotos werden nicht gespeichert und nicht an Health Connect übergeben. " +
                                "Alles bleibt auf deinem Handy; die App hat keinen eigenen Server. Du kannst die Berechtigung jederzeit in " +
                                "Health Connect entziehen – dann werden keine weiteren Einträge geschrieben.",
                        )
                        Button(onClick = { finish() }) { Text("Verstanden") }
                    }
                }
            }
        }
    }
}
