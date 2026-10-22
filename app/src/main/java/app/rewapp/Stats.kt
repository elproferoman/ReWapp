package app.rewapp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsTab(modifier: Modifier, rev: Int) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val data = remember(rev, month) { Store.dailyContacts() }

    val daysInMonth = month.lengthOfMonth()
    val counts = (1..daysInMonth).map { d -> data[month.atDay(d).toString()]?.size ?: 0 }
    val today = LocalDate.now()
    val current = YearMonth.from(today)
    val daysElapsed = when {
        month.isBefore(current) -> daysInMonth
        month == current -> today.dayOfMonth
        else -> 0
    }
    val sum = counts.take(daysElapsed).sum()
    val average = if (daysElapsed > 0) sum.toDouble() / daysElapsed else 0.0
    val distinctInMonth = (1..daysInMonth).flatMap { d -> data[month.atDay(d).toString()] ?: emptySet() }.toSet().size
    val maxCount = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        .replaceFirstChar { it.uppercase() } + " " + month.year

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
            Text(monthName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { month = month.plusMonths(1) }, enabled = month.isBefore(current)) { Text("▶") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Promedio diario", String.format(Locale.getDefault(), "%.1f", average), Modifier.weight(1f))
            StatCard("Contactos distintos del mes", distinctInMonth.toString(), Modifier.weight(1f))
        }

        Text("Contactos distintos que escribieron cada día", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom,
        ) {
            counts.forEachIndexed { index, c ->
                Column(Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (c > 0) c.toString() else "", fontSize = 11.sp)
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .width(18.dp)
                            .height((150.dp * c / maxCount).coerceAtLeast(if (c > 0) 3.dp else 1.dp))
                            .background(
                                if (c > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                            ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text((index + 1).toString(), fontSize = 11.sp)
                }
            }
        }

        Text(
            "El promedio divide la suma de los contactos de cada día por los días transcurridos" +
                (if (month == current) " (incluye hoy). " else ". ") +
                "Un contacto que escribe en dos días distintos cuenta en ambos días, pero una sola vez en «Contactos distintos del mes». " +
                "Solo se cuentan mensajes que la app llegó a ver con el respondedor activo.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(title, style = MaterialTheme.typography.bodySmall)
        }
    }
}
