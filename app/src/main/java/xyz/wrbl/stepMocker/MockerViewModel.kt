package xyz.wrbl.stepMocker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MockerViewModel(app: Application) : AndroidViewModel(app) {
    val repository = HealthRepository(app)
    var period by mutableStateOf(Period.today()); private set
    var bars by mutableStateOf(emptyList<Bar>()); private set
    var records by mutableStateOf(emptyList<StepsRecord>()); private set
    var ready by mutableStateOf(false); private set
    var history by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var preview by mutableStateOf<List<StepBatch>?>(null); private set
    var selected by mutableStateOf(setOf<String>()); private set
    var status by mutableStateOf(repository.status); private set

    fun clearMessage() { message = null }
    fun dismissPreview() { preview = null }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun selectOwn() { selected = records.filter { it.metadata.dataOrigin.packageName == getApplication<Application>().packageName }.map { it.metadata.id }.toSet() }
    fun clearSelection() { selected = emptySet() }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Health Connect operation failed. Please try again." }
            finally { busy = false }
        }
    }
    fun refresh() = work {
        status = repository.status
        if (status != HealthConnectClient.SDK_AVAILABLE) { ready = false; return@work }
        val granted = repository.granted()
        ready = granted.containsAll(repository.permissions)
        history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted
        if (ready) load() else { bars = emptyList(); records = emptyList(); selected = emptySet() }
    }
    private suspend fun load() {
        bars = emptyList(); records = emptyList(); selected = emptySet()
        // Keep views consistent: only publish once both requests succeed.
        val newBars = repository.bars(period)
        val newRecords = repository.records(period)
        bars = newBars; records = newRecords
    }
    fun changePeriod(value: Period) {
        if (busy) return
        try { value.validate(); period = value; preview = null; bars = emptyList(); records = emptyList(); selected = emptySet(); refresh() }
        catch (e: IllegalArgumentException) { message = e.message }
    }
    fun prepare(count: String, portionsInput: String, deviceCount: Int) = work {
        val steps = count.toLongOrNull() ?: throw IllegalArgumentException("Enter a whole number of steps.")
        val portions = if (portionsInput.isBlank()) null else portionsInput.trim().toIntOrNull()
            ?: throw IllegalArgumentException("Enter a whole number of portions, or leave blank for automatic.")
        preview = withContext(Dispatchers.Default) { StepGenerator.generate(period, steps, ZoneId.systemDefault(), portions = portions, deviceCount = deviceCount) }
    }
    fun insert() {
        val batches = preview ?: return
        preview = null
        work {
            var written = 0
            try {
                repository.insert(batches) { written = it }
                message = "Added ${batches.sumOf { it.count }} steps in $written records."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "$written of ${batches.size} records confirmed written. ${e.message}. Refresh and review the timeline before generating again." }
            try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "${message.orEmpty()} Refresh failed: ${e.message}" }
        }
    }
    fun deleteSelected() = work {
        val targets = records.filter { it.metadata.id in selected }
        var deleted = 0
        try { repository.delete(targets) { deleted = it }; message = "Deleted $deleted records." }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "$deleted deletions confirmed. ${e.message}" }
        try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "${message.orEmpty()} Refresh failed: ${e.message}" }
    }
}
