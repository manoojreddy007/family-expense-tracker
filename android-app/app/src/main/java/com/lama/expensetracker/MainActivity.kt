package com.lama.expensetracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val Ink = Color(0xFF173B52)
private val Teal = Color(0xFF167C78)
private val Canvas = Color(0xFFF3F7F6)
private val Muted = Color(0xFF6D7F88)
private val Mint = Color(0xFFE3F3EF)
private val Coral = Color(0xFFB75048)
private val Gold = Color(0xFFFFD887)

private val LamaColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Ink,
    onSecondary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Coral,
    onError = Color.White,
    surfaceVariant = Mint,
    onSurfaceVariant = Ink
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureConfigStore(this)
        setContent { MaterialTheme(colorScheme = LamaColors) { TrackerApp(store) } }
    }
}

@Composable
private fun TrackerApp(store: SecureConfigStore) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(store.load()) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var month by remember { mutableStateOf(currentMonth()) }
    var editor by remember { mutableStateOf<Expense?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()
    val repo = remember(config) { config?.let { ExpenseRepository(context, it) } }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = exportBytes
        if (uri != null && bytes != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("Could not open the selected location.")
                    }
                }.onSuccess { message = "PDF saved successfully." }
                    .onFailure { message = it.message ?: "Could not save the PDF." }
            }
        }
    }

    fun refresh() {
        val active = repo ?: return
        active.cachedDashboard(month)?.let { data = it }
        busy = true
        scope.launch {
            runCatching { active.dashboard(month) }
                .onSuccess { data = it; message = active.statusMessage }
                .onFailure { message = it.message ?: "Could not load expenses." }
            busy = false
        }
    }

    fun exportPdf(share: Boolean) {
        val report = data ?: return
        runCatching {
            PdfExport.create(context, report, month)
        }.onSuccess { bytes ->
            exportBytes = bytes
            if (share) {
                runCatching {
                    val file = PdfExport.shareFile(context, bytes)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share expense report"))
                }.onFailure { message = it.message ?: "Could not share the PDF." }
            } else {
                val period = if (month.isBlank()) "all-history" else month
                savePdf.launch("LA-MA-expenses-$period.pdf")
            }
        }.onFailure { message = it.message ?: "Could not create the PDF." }
    }

    LaunchedEffect(repo, month) { if (repo != null) refresh() }

    if (config == null) {
        ConnectionScreen(onConnect = { candidate ->
            busy = true
            scope.launch {
                runCatching { ExpenseRepository(context, candidate).ping() }
                    .onSuccess { store.save(candidate); config = candidate; message = "" }
                    .onFailure { message = it.message ?: "Connection failed." }
                busy = false
            }
        }, busy = busy, message = message)
    } else if (showEditor) {
        ExpenseEditor(expense = editor, busy = busy, message = message,
            onCancel = { showEditor = false; message = "" }, onSave = { changed ->
                busy = true
                scope.launch {
                    runCatching { repo!!.save(changed) }
                        .onSuccess { showEditor = false; message = repo!!.statusMessage; refresh() }
                        .onFailure { message = it.message ?: "Could not save transaction." }
                    busy = false
                }
            })
    } else {
        DashboardScreen(
            data = data, month = month, busy = busy, message = message,
            onMonth = { month = it }, onAdd = { editor = null; message = ""; showEditor = true },
            onEdit = { editor = it; message = ""; showEditor = true },
            onDelete = { row ->
                busy = true
                scope.launch {
                    runCatching { repo!!.delete(row.id) }
                        .onSuccess { message = repo!!.statusMessage; refresh() }
                        .onFailure { message = it.message ?: "Could not delete transaction." }
                    busy = false
                }
            }, onRefresh = ::refresh,
            onSavePdf = { exportPdf(false) }, onSharePdf = { exportPdf(true) }
        )
    }
}

@Composable
private fun ConnectionScreen(onConnect: (BackendConfig) -> Unit, busy: Boolean, message: String) {
    var baseUrl by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Canvas).imePadding(), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().padding(22.dp), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.mipmap.ic_launcher), contentDescription = "LA-MA Expense Tracker logo", modifier = Modifier.size(92.dp).clip(RoundedCornerShape(22.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Welcome to LA-MA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Your household spending, together.", color = Muted)
                }
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Flask server URL") }, placeholder = { Text("https://your-service.onrender.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(password, { password = it }, label = { Text("App password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Text("Use the HTTPS address and APP_PASSWORD from your Render service settings.", style = MaterialTheme.typography.bodySmall, color = Muted)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onConnect(BackendConfig(baseUrl.trim().trimEnd('/'), password)) }, enabled = !busy && baseUrl.startsWith("https://") && password.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("Connect securely")
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    data: DashboardData?, month: String, busy: Boolean, message: String,
    onMonth: (String) -> Unit, onAdd: () -> Unit, onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit, onRefresh: () -> Unit,
    onSavePdf: () -> Unit, onSharePdf: () -> Unit
) {
    var monthsExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Expense?>(null) }
    Column(Modifier.fillMaxSize().background(Canvas).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.mipmap.ic_launcher), contentDescription = "LA-MA logo", modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("LA-MA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text("EXPENSE TRACKER", style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.4.sp)
            }
            TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "Loading" else "Refresh") }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Text("⋮", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Save PDF") }, onClick = { menuExpanded = false; onSavePdf() }, enabled = data != null && !busy)
                    DropdownMenuItem(text = { Text("Share PDF") }, onClick = { menuExpanded = false; onSharePdf() }, enabled = data != null && !busy)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                FilledTonalButton(onClick = { monthsExpanded = true }, shape = RoundedCornerShape(12.dp)) {
                    Text(if (month.isBlank()) "All history  ▾" else "${monthLabel(month)}  ▾")
                }
                DropdownMenu(expanded = monthsExpanded, onDismissRequest = { monthsExpanded = false }) {
                    DropdownMenuItem(text = { Text("All history") }, onClick = { onMonth(""); monthsExpanded = false })
                    (data?.months ?: emptyList()).forEach { m -> DropdownMenuItem(text = { Text(monthLabel(m)) }, onClick = { onMonth(m); monthsExpanded = false }) }
                }
            }
            Button(onClick = onAdd, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)) { Text("+  Add") }
        }

        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Ink)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(if (month.isBlank()) "TOTAL SAVINGS" else "SAVINGS · ${monthLabel(month).uppercase()}", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC6DDDC), letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("Rs %.2f".format((data?.income ?: 0.0) - (data?.expense ?: 0.0)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF29576A)), contentAlignment = Alignment.Center) { Text("↗", style = MaterialTheme.typography.headlineSmall, color = Gold) }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoneySummary("Income", data?.income ?: 0.0, Color(0xFF9AE0C7), Modifier.weight(1f))
                    MoneySummary("Expenses", data?.expense ?: 0.0, Color(0xFFFFB2A6), Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 6.dp), color = Teal, trackColor = Mint)
        if (message.isNotBlank()) Text(message, color = if (message.contains("saved", true) || message.contains("offline", true) || message.contains("waiting", true) || message.contains("deleted", true)) Teal else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Transactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${data?.rows?.size ?: 0} records", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text("Recent first", style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
            items(data?.rows ?: emptyList(), key = { it.id }) { row ->
                TransactionCard(row, onEdit = { onEdit(row) }, onDelete = { confirmDelete = row })
            }
            if (data?.rows.isNullOrEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Add your first transaction to start tracking this period.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    confirmDelete?.let { row ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, shape = RoundedCornerShape(22.dp),
            title = { Text("Delete transaction?") },
            text = { Text("${row.name} · Rs %.2f".format(row.amount)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(row) }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } })
    }
}

@Composable
private fun MoneySummary(label: String, amount: Double, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFFCAD9DD))
        Spacer(Modifier.height(3.dp))
        Text("Rs %.2f".format(amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TransactionCard(expense: Expense, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (expense.type == "Income") Mint else Color(0xFFFFEFEC)), contentAlignment = Alignment.Center) {
                    Text(expense.category.take(1).uppercase(), color = if (expense.type == "Income") Teal else Coral, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(expense.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${expense.category}  ·  ${expense.date}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Rs %.2f".format(expense.amount), color = if (expense.type == "Income") Teal else Coral, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(expense.type.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("Edit") }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("Delete", color = Coral) }
            }
            if (expense.notes.isNotBlank()) Text(expense.notes, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ExpenseEditor(expense: Expense?, busy: Boolean, message: String, onCancel: () -> Unit, onSave: (Expense) -> Unit) {
    var name by remember(expense) { mutableStateOf(expense?.name ?: "") }
    var amount by remember(expense) { mutableStateOf(expense?.amount?.toString() ?: "") }
    var category by remember(expense) { mutableStateOf(expense?.category ?: expenseCategories.first()) }
    var type by remember(expense) { mutableStateOf(expense?.type ?: "Expense") }
    var date by remember(expense) { mutableStateOf(expense?.date ?: LocalDate.now().toString()) }
    var addedBy by remember(expense) { mutableStateOf(expense?.addedBy ?: people.first()) }
    var notes by remember(expense) { mutableStateOf(expense?.notes ?: "") }
    var formError by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Canvas).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) { Text("←  Back to transactions") }
        Text(if (expense == null) "New transaction" else "Edit transaction", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Keep the details clear so your monthly reports stay useful.", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Description") }, placeholder = { Text("e.g. Weekly groceries") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount (Rs)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                ChoiceField("Category", category, expenseCategories, categoryExpanded, { categoryExpanded = it }, { category = it })
                ChoiceField("Type", type, transactionTypes, typeExpanded, { typeExpanded = it }, { type = it })
                OutlinedTextField(date, { date = it }, label = { Text("Date") }, supportingText = { Text("Use YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                OutlinedTextField(notes, { notes = it.take(200) }, label = { Text("Notes") }, placeholder = { Text("Optional · up to 200 characters") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, shape = RoundedCornerShape(13.dp))
            }
        }
        if (formError.isNotBlank()) Text(formError, color = Coral)
        if (message.isNotBlank()) Text(message, color = if (message.contains("saved", true) || message.contains("waiting", true)) Teal else Coral)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
            Button(onClick = {
                val parsed = amount.toDoubleOrNull()
                if (name.isBlank() || parsed == null || parsed <= 0 || runCatching { LocalDate.parse(date) }.isFailure) formError = "Enter a description, positive amount, and valid date."
                else onSave(Expense(expense?.id ?: 0, name.trim(), parsed, category, type, notes.trim(), date.trim(), addedBy))
            }, enabled = !busy, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("Save transaction")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, expanded: Boolean, setExpanded: (Boolean) -> Unit, onChoose: (String) -> Unit) {
    Box {
        OutlinedButton(onClick = { setExpanded(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = Ink)
                }
                Text("▾", color = Teal)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChoose(option); setExpanded(false) }) }
        }
    }
}

private fun monthLabel(month: String): String = runCatching {
    val date = LocalDate.parse("$month-01")
    date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + date.year
}.getOrDefault(month)
