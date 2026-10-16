package app.rewapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    /** Se incrementa en onResume para releer el estado del permiso de notificaciones. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReWappTheme {
                App(resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
fun ReWappTheme(content: @Composable () -> Unit) {
    val green = Color(0xFF128C7E)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF5BD3C3))
    } else {
        lightColorScheme(primary = green)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(tick: Int) {
    var tab by remember { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }
    val bump: () -> Unit = { rev++ }

    if (editing != null || creating) {
        BackHandler {
            editing = null
            creating = false
        }
        RuleEditor(
            initial = editing,
            allRules = Store.rules(),
            onSave = { saved ->
                val list = Store.rules().toMutableList()
                val idx = list.indexOfFirst { it.id == saved.id }
                if (idx >= 0) list[idx] = saved else list.add(saved)
                Store.saveRules(list)
                editing = null
                creating = false
                bump()
            },
            onDelete = { gone ->
                // Las reglas que dependían de esta quedan como reglas de inicio.
                val list = Store.rules().filter { it.id != gone.id }
                    .map { if (it.afterRuleId == gone.id) it.copy(afterRuleId = null) else it }
                Store.saveRules(list)
                editing = null
                creating = false
                bump()
            },
            onCancel = {
                editing = null
                creating = false
            },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ReWapp") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("💬") }, label = { Text("Reglas") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("📋") }, label = { Text("Actividad") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙️") }, label = { Text("Ajustes") })
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (tab) {
            0 -> RulesTab(mod, rev, tick, bump, onEdit = { r ->
                if (r == null) creating = true else editing = r
            })
            1 -> ActivityTab(mod, rev, bump)
            else -> SettingsTab(mod, rev, bump)
        }
    }
}
