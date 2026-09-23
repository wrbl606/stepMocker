package xyz.wrbl.stepMocker

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF142D33)
private val Teal = Color(0xFF007F73)
private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm z")
private fun stamp(time: Instant) = dateFormat.format(time.atZone(ZoneId.systemDefault()))
private fun number(value: Long) = String.format(Locale.getDefault(), "%,d", value)

@Composable
fun MockerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White,
        background = Color(0xFFF3F6F5), surface = Color.White, onSurface = Ink,
        secondaryContainer = Color(0xFFD6EEE7)), content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MockerTheme { MockerApp() } }
    }
}

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MockerTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Your health data stays on your device", style = MaterialTheme.typography.headlineMedium)
                Text("Step Mocker reads steps to display charts and individual records. It writes synthetic step records only after you confirm a preview, and deletes its own records only after you confirm a selection.")
                Text("Historical access is optional and lets you inspect records older than Health Connect’s default read window. No health data is uploaded or shared by this app. Other apps with Health Connect access may read the mock records you create.")
                Text("Device names and recording methods on generated records are simulated test metadata. All generated records belong to Step Mocker.")
                Button(onClick = { finish() }) { Text("Done") }
            }
        } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockerApp(vm: MockerViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var count by rememberSaveable { mutableStateOf("8000") }
    var portions by rememberSaveable { mutableStateOf("1001") }
    var deviceCount by rememberSaveable { mutableIntStateOf(5) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refresh() }
    fun openHealth() {
        try { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
        catch (_: Exception) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycle.lifecycle.addObserver(observer)
        vm.refresh()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    Scaffold(
        topBar = { TopAppBar(title = { Column {
            Text("Step Mocker", fontWeight = FontWeight.Bold)
            Text("HEALTH CONNECT · TEST DATA", style = MaterialTheme.typography.labelSmall, color = Teal)
        } }, actions = {
            IconButton(onClick = { vm.refresh() }, enabled = !vm.busy) { Icon(Icons.Outlined.Refresh, "Refresh data") }
            IconButton(onClick = { openHealth() }) { Icon(Icons.Outlined.Settings, "Open Health Connect") }
        }) },
        bottomBar = { Column {
            if (tab == 0 && vm.ready) Surface {
                Button(onClick = { vm.prepare(count, portions, deviceCount) }, enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Add steps")
                }
            }
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Outlined.BarChart, null) }, label = { Text("Steps") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Outlined.Timeline, null) }, label = { Text("Timeline") })
            }
        } }, snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(if (tab == 1) 4.dp else 16.dp)) {
            if (tab == 1) item {
                Text("Explore every record.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Inspect source devices, recording methods and individual intervals.")
            }
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { PeriodCard(vm.period, !vm.busy, vm::changePeriod) }
            if (!vm.ready) item {
                Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect your step data", style = MaterialTheme.typography.titleLarge)
                    Text(when (vm.status) {
                        HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect is unavailable on this device. Use a supported Android 9+ device with Google Play services."
                        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Install or update Health Connect to continue."
                        else -> "Allow step reading and writing to chart existing activity and add mock records."
                    })
                    if (vm.status == HealthConnectClient.SDK_AVAILABLE) Button(onClick = { launcher.launch(vm.repository.permissions) }, enabled = !vm.busy) { Text("Grant step access") }
                    else if (vm.status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))) }) { Text("Install / update") }
                } }
            } else {
                if (!vm.history && vm.period.start < Instant.now().minus(Duration.ofDays(30))) item {
                    Column {
                        Text("Older records may be unavailable without historical access.", style = MaterialTheme.typography.bodySmall)
                        if (vm.repository.supportsHistory) TextButton(onClick = { launcher.launch(vm.repository.permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY) }, enabled = !vm.busy) { Text("Allow historical data") }
                    }
                }
                if (tab == 0) {
                    item { StepsChart(vm.bars, vm.period.bucketHours) }
                    item {
                        GenerationSettings(count, portions, deviceCount, settingsExpanded, !vm.busy,
                            onCountChange = { count = it }, onPortionsChange = { portions = it },
                            onDevicesChange = { deviceCount = it }, onToggle = { settingsExpanded = !settingsExpanded })
                    }
                } else {
                    item {
                        Text("${number(vm.records.size.toLong())} records · ${vm.selected.size} selected", style = MaterialTheme.typography.titleMedium)
                        Text("Only Step Mocker records can be deleted here. Manage other sources in Health Connect.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = vm::selectOwn, enabled = !vm.busy) { Text("Select this app’s records") }
                            TextButton(onClick = vm::clearSelection, enabled = !vm.busy) { Text("Clear") }
                        }
                        if (vm.selected.isNotEmpty()) Button(onClick = { deleteDialog = true }, enabled = !vm.busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete ${vm.selected.size} selected") }
                    }
                    if (vm.records.isEmpty() && !vm.busy) item { Text("No readable step records in this period.") }
                    items(vm.records, key = { it.metadata.id }) { record -> RecordCard(record, record.metadata.id in vm.selected, record.metadata.dataOrigin.packageName == context.packageName && !vm.busy) { vm.toggle(record.metadata.id) } }
                }
            }
            item { TextButton(onClick = { context.startActivity(Intent(context, PrivacyActivity::class.java)) }) { Text("Privacy & data usage") } }
        }
    }
    vm.preview?.let { batches ->
        AlertDialog(onDismissRequest = vm::dismissPreview, title = { Text("Add ${number(batches.sumOf { it.count })} steps?") },
            text = { Text("${batches.size} records across ${batches.map { it.device }.distinct().size} simulated devices.\n\n${stamp(vm.period.start)}\nto ${stamp(vm.period.end)}\n\nBatch sizes: ${batches.minOf { it.count }}–${batches.maxOf { it.count }} steps. Durations: ${batches.minOf { Duration.between(it.start, it.end).seconds }}–${batches.maxOf { Duration.between(it.start, it.end).seconds }} seconds.\n\nThese are synthetic test records attributed to Step Mocker.") },
            confirmButton = { Button(onClick = vm::insert) { Text("Add to Health Connect") } }, dismissButton = { TextButton(onClick = vm::dismissPreview) { Text("Cancel") } })
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Delete ${vm.selected.size} records?") }, text = { Text("This permanently removes the selected Step Mocker records from Health Connect. This cannot be undone.") }, confirmButton = { TextButton(onClick = { deleteDialog = false; vm.deleteSelected() }) { Text("Delete records") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } })
}

@Composable
private fun GenerationSettings(
    count: String, portions: String, deviceCount: Int, expanded: Boolean, enabled: Boolean,
    onCountChange: (String) -> Unit, onPortionsChange: (String) -> Unit,
    onDevicesChange: (Int) -> Unit, onToggle: () -> Unit,
) {
    val steps = count.toLongOrNull()?.takeIf { it in 1..1_000_000 }
    val entries = portions.trim().toIntOrNull()?.takeIf { it in 1..10_000 && steps != null && it <= steps }
    val stepsLabel = steps?.let { "${number(it)} steps" } ?: "Check step count"
    val entriesLabel = if (portions.isBlank()) "Automatic entries" else entries?.let { "${number(it.toLong())} entries" } ?: "Check entry count"
    val devicesLabel = if (entries != null) "${minOf(deviceCount, entries)} devices" else "Up to $deviceCount devices"
    Card(Modifier.fillMaxWidth()) {
        Surface(onClick = onToggle, enabled = enabled, color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Step settings", style = MaterialTheme.typography.titleMedium)
                    Text("$stepsLabel · $entriesLabel · $devicesLabel", style = MaterialTheme.typography.bodyMedium)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "Hide settings" else "Edit settings")
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Choose how much activity to add. You can review it before saving.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = count, onValueChange = onCountChange, label = { Text("Total steps to add") },
                    isError = steps == null, supportingText = { Text("Between 1 and 1,000,000 steps.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = portions, onValueChange = onPortionsChange, label = { Text("Number of entries") },
                    placeholder = { Text("Automatic") }, isError = portions.isNotBlank() && entries == null,
                    supportingText = { Text("Steps are split into this many small records. Leave blank to choose automatically. Each entry needs at least one step; maximum 10,000 entries.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                Text("Contributing devices: $deviceCount", style = MaterialTheme.typography.titleSmall)
                Slider(value = deviceCount.toFloat(), onValueChange = { onDevicesChange(it.toInt()) }, valueRange = 1f..5f,
                    steps = 3, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Number of simulated devices" })
                Text("Mix phones, watches and fitness bands. If there are fewer entries than devices, each entry uses a different device.", style = MaterialTheme.typography.bodySmall)
                Text("For pagination testing, use 1,001 or more entries. This app reads 1,000 records per page.", style = MaterialTheme.typography.bodySmall)
                Text("Devices and activity are simulated. Other connected apps may read these steps.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PeriodCard(period: Period, enabled: Boolean, onChange: (Period) -> Unit) {
    val context = LocalContext.current
    fun pick(current: Instant, isStart: Boolean) {
        val local = current.atZone(ZoneId.systemDefault())
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                val picked = java.time.LocalDateTime.of(year, month + 1, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant()
                onChange(if (isStart) period.copy(start = picked) else period.copy(end = picked))
            }, local.hour, local.minute, true).show()
        }, local.year, local.monthValue - 1, local.dayOfMonth).show()
    }
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Selected time range", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onChange(Period.today()) }, enabled = enabled) { Text("Today") }
        }
        OutlinedButton(onClick = { pick(period.start, true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("From  ${stamp(period.start)}") }
        OutlinedButton(onClick = { pick(period.end, false) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("To  ${stamp(period.end)}") }
        Text("Local time · up to 90 days", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun StepsChart(bars: List<Bar>, hours: Long) {
    Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${number(bars.sumOf { it.steps })} steps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Health Connect total · automatic ${hours}h intervals", style = MaterialTheme.typography.bodySmall)
        if (bars.isEmpty()) Text("No chart data loaded.") else {
            val max = bars.maxOf { it.steps }.coerceAtLeast(1)
            Text("Scale: 0–${number(max)} steps", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                bars.forEach { bar ->
                    val local = bar.start.atZone(ZoneId.systemDefault())
                    Column(Modifier.width(48.dp).semantics { contentDescription = "${stamp(bar.start)} to ${stamp(bar.end)}: ${bar.steps} steps" }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(number(bar.steps), style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.height(140.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.width(28.dp).height((140f * bar.steps / max).coerceAtLeast(2f).dp).background(if (bar.steps == 0L) Color(0xFFDCE7E3) else Teal, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                        }
                        Text(local.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelSmall)
                        Text(local.format(DateTimeFormatter.ofPattern("dd MMM")), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text("Scroll horizontally to explore the period. Totals follow Health Connect source priorities.", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun RecordCard(record: StepsRecord, selected: Boolean, selectable: Boolean, toggle: () -> Unit) {
    var expanded by rememberSaveable(record.metadata.id) { mutableStateOf(false) }
    val device = record.metadata.device
    val method = when (record.metadata.recordingMethod) {
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "Automatic"
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "Active"
        Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "Manual"
        else -> "Unknown method"
    }
    val type = when (device?.type) { Device.TYPE_PHONE -> "Phone"; Device.TYPE_WATCH -> "Watch"; Device.TYPE_FITNESS_BAND -> "Fitness band"; else -> "Other / unknown device" }
    Card(Modifier.fillMaxWidth()) { Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { toggle() }, enabled = selectable,
                modifier = Modifier.semantics { contentDescription = "Select ${record.count} steps at ${stamp(record.startTime)}" })
            Text("${number(record.count)} steps", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(record.startTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM HH:mm")),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "Hide record details" else "Show record details")
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider()
                Text("${number(record.count)} steps", style = MaterialTheme.typography.bodyMedium)
                Text("Starts ${stamp(record.startTime)}", style = MaterialTheme.typography.bodySmall)
                Text("Ends ${stamp(record.endTime)}", style = MaterialTheme.typography.bodySmall)
                Text(listOfNotNull(device?.manufacturer, device?.model).joinToString(" · ").ifBlank { "No device metadata" }, style = MaterialTheme.typography.bodySmall, color = Teal)
                Text("${Duration.between(record.startTime, record.endTime).seconds}s · $method · $type", style = MaterialTheme.typography.bodySmall)
                Text(record.metadata.dataOrigin.packageName, style = MaterialTheme.typography.bodySmall)
                if (record.metadata.clientRecordId?.startsWith("step-mocker:") == true) Text("SIMULATED", style = MaterialTheme.typography.labelSmall, color = Teal)
            }
        }
    } }
}
