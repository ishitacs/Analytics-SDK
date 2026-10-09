package com.example.analyticslib

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.analytics.Analytics
import com.example.analytics.compose.TrackScreen
import com.example.analytics.compose.rememberTrackedClick
import com.example.analytics.compose.trackImpression
import com.example.analyticslib.demo.BackendMode
import com.example.analyticslib.demo.DebugState
import com.example.analyticslib.demo.MockBackend
import com.example.analyticslib.demo.RemoteMode
import com.example.analyticslib.ui.theme.AnalyticsLibTheme

data class Product(val id: String, val title: String, val category: String, val price: Double)

private val categories = listOf("Shoes", "Electronics", "Books", "Grocery", "Toys")

internal val mockProducts = List(100) { index ->
    Product(
        id = "sku_${1000 + index}",
        title = "Product #${index + 1}",
        category = categories[index % categories.size],
        price = 99.0 + (index * 37) % 900,
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnalyticsLibTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ProductListScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ProductListScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // One screen visit per ON_START..ON_STOP; impressions inside are deduped per visit.
    TrackScreen("product_list", properties = mapOf("item_count" to mockProducts.size)) {
        Column(modifier.fillMaxSize()) {
            DebugPanel(
                onTrackFromRemote = { context.startService(Intent(context, RemoteTrackingService::class.java)) },
            )
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(mockProducts) { position, product ->
                    val properties = mapOf(
                        "position" to position,
                        "category" to product.category,
                        "price" to product.price,
                    )
                    val onClick = rememberTrackedClick("product_click", key = product.id, properties = properties) {
                        Toast.makeText(context, "Clicked ${product.title}", Toast.LENGTH_SHORT).show()
                    }
                    ProductRow(
                        product,
                        Modifier.trackImpression(key = product.id, name = "product_impression", properties = properties),
                        onClick,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    products: List<Product>,
    content: @Composable (Int, Product) -> Unit,
) = items(products.size, key = { products[it].id }) { content(it, products[it]) }

@Composable
private fun ProductRow(product: Product, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(product.title, style = MaterialTheme.typography.titleMedium)
                Text(product.category, style = MaterialTheme.typography.bodySmall)
            }
            Text("₹${product.price.toInt()}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DebugPanel(onTrackFromRemote: () -> Unit) {
    val state by DebugState.state.collectAsState()
    var mode by remember { mutableStateOf(MockBackend.mode) }
    var remoteMode by remember { mutableStateOf(MockBackend.remoteMode) }
    val context = LocalContext.current
    var loggedIn by rememberSaveable { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        // Covers the list: impressions pause until it's dismissed.
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Close") } },
            title = { Text("Impressions paused") },
            text = { Text("While this dialog has focus, nothing behind it counts as seen.") },
        )
    }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mock backend", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackendMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = {
                            mode = option
                            MockBackend.mode = option
                            DebugState.log("backend → ${option.label}")
                        },
                        label = { Text(option.label) },
                    )
                }
            }
            Text("Remote config", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RemoteMode.entries.forEach { option ->
                    FilterChip(
                        selected = remoteMode == option,
                        onClick = {
                            remoteMode = option
                            MockBackend.remoteMode = option
                            Analytics.refreshRemoteConfig()
                        },
                        label = { Text(option.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Queued", state.queued)
                Stat("Delivered", state.delivered)
                Stat("Dropped", state.dropped)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { Analytics.flush() }) { Text("Flush") }
                OutlinedButton(onClick = {
                    if (loggedIn) {
                        Analytics.reset()
                        DebugState.log("logout → new session")
                    } else {
                        Analytics.identify("user_42", mapOf("plan" to "pro", "city" to "Bengaluru"))
                        DebugState.log("identify(user_42)")
                    }
                    loggedIn = !loggedIn
                }) { Text(if (loggedIn) "Logout" else "Login") }
                OutlinedButton(onClick = { showDialog = true }) { Text("Dialog") }
                OutlinedButton(onClick = onTrackFromRemote) { Text(":remote") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(context, ViewsDemoActivity::class.java))
                }) { Text("Views demo") }
            }
            state.log.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
