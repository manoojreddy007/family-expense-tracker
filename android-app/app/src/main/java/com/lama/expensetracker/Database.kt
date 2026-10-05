package com.lama.expensetracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackendConfig(val baseUrl: String, val password: String)
data class Expense(
    val id: Int = 0,
    val name: String,
    val amount: Double,
    val category: String,
    val type: String,
    val notes: String,
    val date: String,
    val addedBy: String
)
data class DashboardData(val rows: List<Expense>, val income: Double, val expense: Double, val months: List<String>)

class SecureConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("backend_config", Context.MODE_PRIVATE)
    private val alias = "lama_backend_config_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val data = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }.getOrNull()

    fun load(): BackendConfig? {
        val url = prefs.getString("base_url", null)?.let(::decrypt) ?: return null
        val password = prefs.getString("password", null)?.let(::decrypt) ?: return null
        return BackendConfig(url, password)
    }

    fun save(config: BackendConfig) {
        prefs.edit().putString("base_url", encrypt(config.baseUrl.trim().trimEnd('/')))
            .putString("password", encrypt(config.password)).apply()
    }

    fun clear() { prefs.edit().clear().apply() }
}

class ExpenseRepository(private val config: BackendConfig) {
    @Volatile private var cookie: String? = null

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, authenticated: Boolean = true): JSONObject {
        if (authenticated && cookie == null) login()
        val conn = (URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            cookie?.let { setRequestProperty("Cookie", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            conn.getHeaderField("Set-Cookie")?.substringBefore(';')?.let { cookie = it }
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED && authenticated) {
                conn.disconnect()
                cookie = null
                login()
                return request(path, method, body, authenticated = true)
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (response.isBlank()) JSONObject() else JSONObject(response)
            if (status !in 200..299) throw IllegalStateException(json.optString("error", "Request failed ($status)."))
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun login() {
        request("/api/login", "POST", JSONObject().put("password", config.password), authenticated = false)
    }

    suspend fun ping() = withContext(Dispatchers.IO) { login() }

    suspend fun dashboard(month: String): DashboardData = withContext(Dispatchers.IO) {
        val query = if (month.isBlank()) "" else "?month=${java.net.URLEncoder.encode(month, "UTF-8")}"
        val json = request("/api/dashboard$query")
        val rows = json.getJSONArray("rows").toExpenses()
        val monthsJson = json.getJSONArray("months")
        DashboardData(rows, json.optDouble("income"), json.optDouble("expense"),
            (0 until monthsJson.length()).map { monthsJson.getString(it) })
    }

    suspend fun save(expense: Expense) = withContext(Dispatchers.IO) {
        val body = expense.toJson()
        if (expense.id == 0) request("/api/transactions", "POST", body)
        else request("/api/transactions/${expense.id}", "PUT", body)
    }

    suspend fun delete(id: Int) = withContext(Dispatchers.IO) { request("/api/transactions/$id", "DELETE") }

    private fun Expense.toJson() = JSONObject().put("name", name).put("amount", amount)
        .put("category", category).put("type", type).put("notes", notes)
        .put("date", date).put("added_by", addedBy)

    private fun JSONArray.toExpenses() = (0 until length()).map { index ->
        val row = getJSONObject(index)
        Expense(row.getInt("id"), row.optString("name"), row.getDouble("amount"),
            row.optString("category"), row.optString("type"), row.optString("notes"),
            row.optString("date"), row.optString("added_by"))
    }
}

val expenseCategories = listOf("Food", "Groceries", "Rent", "Fuel", "Shopping", "Travel", "Medical", "Salary", "Misc")
val people = listOf("Manoj", "Lalitha")
val transactionTypes = listOf("Expense", "Income")
fun currentMonth() = LocalDate.now().toString().substring(0, 7)
