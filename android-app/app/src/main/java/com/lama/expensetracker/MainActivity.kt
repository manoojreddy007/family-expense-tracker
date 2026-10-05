package com.lama.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureConfigStore(this)
        setContent { MaterialTheme { TrackerApp(store) } }
    }
}

@Composable
private fun TrackerApp(store: SecureConfigStore) {
    var config by remember { mutableStateOf(store.load()) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var month by remember { mutableStateOf(currentMonth()) }
    var editor by remember { mutableStateOf<Expense?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repo = remember(config) { config?.let(::ExpenseRepository) }

    fun refresh() {
        val active = repo ?: return
        busy = true
        scope.launch {
            runCatching { active.dashboard(month) }
                .onSuccess { data = it; error = "" }
                .onFailure { error = it.message ?: "Could not load expenses." }
            busy = false
        }
    }

    LaunchedEffect(repo, month) { if (repo != null) refresh() }

    if (config == null) {
        ConnectionScreen(onConnect = { candidate ->
            busy = true
            scope.launch {
                runCatching { ExpenseRepository(candidate).ping() }
                    .onSuccess { store.save(candidate); config = candidate; error = "" }
                    .onFailure { error = it.message ?: "Connection failed." }
                busy = false
            }
        }, busy = busy, error = error)
    } else if (showEditor) {
        ExpenseEditor(expense = editor, onCancel = { showEditor = false }, onSave = { changed ->
            busy = true
            scope.launch {
                runCatching { repo!!.save(changed) }
                    .onSuccess { showEditor = false; refresh() }
                    .onFailure { error = it.message ?: "Could not save transaction." }
                busy = false
            }
        })
    } else {
        DashboardScreen(data, month, busy, error,
            onMonth = { month = it }, onAdd = { editor = null; showEditor = true },
            onEdit = { editor = it; showEditor = true }, onDelete = { row ->
                busy = true
                scope.launch {
                    runCatching { repo!!.delete(row.id) }.onSuccess { refresh() }
                        .onFailure { error = it.message ?: "Could not delete transaction." }
                    busy = false
                }
            }, onRefresh = ::refresh, onDisconnect = { store.clear(); config = null; data = null })
    }
}

@Composable
private fun ConnectionScreen(onConnect: (BackendConfig) -> Unit, busy: Boolean, error: String) {
    var baseUrl by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect to expense server", style = MaterialTheme.typography.headlineMedium)
        Text("Enter the HTTPS URL of your deployed Flask app and its app password. The password is stored encrypted on this phone.")
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Server URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("App password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { onConnect(BackendConfig(baseUrl.trim().trimEnd('/'), password)) }, enabled = !busy && baseUrl.startsWith("https://") && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Connecting..." else "Connect") }
    }
}

@Composable
private fun DashboardScreen(data: DashboardData?, month: String, busy: Boolean, error: String,
    onMonth: (String) -> Unit, onAdd: () -> Unit, onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit, onRefresh: () -> Unit, onDisconnect: () -> Unit) {
    var monthsExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Expense?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LA-MA Expenses", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onDisconnect) { Text("Connection") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Summary("Income", data?.income ?: 0.0)
            Summary("Expenses", data?.expense ?: 0.0)
            Summary("Savings", (data?.income ?: 0.0) - (data?.expense ?: 0.0))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
            TextButton(onClick = { monthsExpanded = true }) { Text(if (month.isBlank()) "All months v" else "$month v") }
                DropdownMenu(expanded = monthsExpanded, onDismissRequest = { monthsExpanded = false }) {
                    DropdownMenuItem(text = { Text("All months") }, onClick = { onMonth(""); monthsExpanded = false })
                    (data?.months ?: emptyList()).forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { onMonth(m); monthsExpanded = false }) }
                }
            }
            Button(onClick = onAdd) { Text("+ Add") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Text("Recent transactions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(data?.rows ?: emptyList(), key = { it.id }) { row ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text("Rs %.2f".format(row.amount), color = if (row.type == "Income") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        Text("${row.category} | ${row.type} | ${row.date}")
                        Text("${row.addedBy}${if (row.notes.isBlank()) "" else " | ${row.notes}"}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onEdit(row) }) { Text("Edit") }
                            TextButton(onClick = { confirmDelete = row }) { Text("Delete") }
                        }
                    }
                }
            }
            if (data?.rows.isNullOrEmpty()) item { Text("No transactions for this month.", modifier = Modifier.padding(16.dp)) }
        }
    }
    confirmDelete?.let { row ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete transaction?") },
            text = { Text("${row.name} | Rs %.2f".format(row.amount)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(row) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RowScope.Summary(label: String, value: Double) {
    ElevatedCard(Modifier.weight(1f)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("Rs %.0f".format(value), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ExpenseEditor(expense: Expense?, onCancel: () -> Unit, onSave: (Expense) -> Unit) {
    var name by remember(expense) { mutableStateOf(expense?.name ?: "") }
    var amount by remember(expense) { mutableStateOf(expense?.amount?.toString() ?: "") }
    var category by remember(expense) { mutableStateOf(expense?.category ?: expenseCategories.first()) }
    var type by remember(expense) { mutableStateOf(expense?.type ?: "Expense") }
    var date by remember(expense) { mutableStateOf(expense?.date ?: LocalDate.now().toString()) }
    var addedBy by remember(expense) { mutableStateOf(expense?.addedBy ?: people.first()) }
    var notes by remember(expense) { mutableStateOf(expense?.notes ?: "") }
    var error by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var personExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (expense == null) "Add transaction" else "Edit transaction", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(amount, { amount = it }, label = { Text("Amount (Rs)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChoiceField("Category", category, expenseCategories, categoryExpanded, { categoryExpanded = it }, { category = it })
        ChoiceField("Type", type, transactionTypes, typeExpanded, { typeExpanded = it }, { type = it })
        OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChoiceField("Added by", addedBy, people, personExpanded, { personExpanded = it }, { addedBy = it })
        OutlinedTextField(notes, { notes = it.take(200) }, label = { Text("Notes (up to 200 characters)") }, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = {
                val parsed = amount.toDoubleOrNull()
                if (name.isBlank() || parsed == null || parsed <= 0 || runCatching { LocalDate.parse(date) }.isFailure) error = "Enter a name, positive amount, and valid date."
                else onSave(Expense(expense?.id ?: 0, name.trim(), parsed, category, type, notes.trim(), date, addedBy))
            }) { Text("Save") }
        }
    }
}

@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, expanded: Boolean,
    setExpanded: (Boolean) -> Unit, onChoose: (String) -> Unit) {
    Box {
        OutlinedButton(onClick = { setExpanded(true) }, modifier = Modifier.fillMaxWidth()) { Text("$label: $value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChoose(option); setExpanded(false) }) }
        }
    }
}
