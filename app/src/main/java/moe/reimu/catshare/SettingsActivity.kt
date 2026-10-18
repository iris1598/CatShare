package moe.reimu.catshare

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.reimu.catshare.ui.DefaultCard
import moe.reimu.catshare.ui.theme.CatShareTheme
import moe.reimu.catshare.utils.RootUtils
import moe.reimu.catshare.utils.ShizukuUtils
import java.io.File

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CatShareTheme {
                SettingsActivityContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsActivityContent() {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val settings = remember(activity) { AppSettings(context) }

    var deviceNameValue by remember {
        mutableStateOf(settings.deviceName)
    }

    var verboseValue by remember {
        mutableStateOf(settings.verbose)
    }

    var macOverrideValue by remember {
        mutableStateOf(settings.macAddress.orEmpty())
    }

    var rootStatus by remember {
        mutableStateOf<RootUtils.RootStatus?>(null)
    }

    var macResult by remember {
        mutableStateOf<RootUtils.MacReadResult?>(null)
    }

    var detecting by remember {
        mutableStateOf(false)
    }

    // 0 = initial detection, > 0 = the user asked to re-detect.
    var detectRequest by remember {
        mutableStateOf(0)
    }

    LaunchedEffect(detectRequest) {
        detecting = true
        val result = withContext(Dispatchers.IO) {
            val status = RootUtils.detectRoot(force = detectRequest > 0)
            // Resolve the MAC through the same priority chain used at runtime so the UI shows the
            // value that will actually be used (manual -> root -> permission -> Shizuku -> native).
            val mac = ShizukuUtils.resolveMacAddress(context.applicationContext)
            status to mac
        }
        rootStatus = result.first
        macResult = result.second
        detecting = false
    }

    val macOverrideInvalid = macOverrideValue.trim().isNotEmpty() &&
            !RootUtils.isValidMac(macOverrideValue)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = stringResource(R.string.title_activity_settings)) },
            actions = {
                IconButton(onClick = {
                    if (macOverrideInvalid) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.mac_invalid),
                            Toast.LENGTH_LONG
                        ).show()
                        return@IconButton
                    }

                    val nameValue = deviceNameValue
                    if (nameValue.isNotBlank()) {
                        settings.deviceName = nameValue
                    }
                    settings.verbose = verboseValue
                    settings.macAddress = RootUtils.normalizeMac(macOverrideValue)

                    activity?.finish()
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Check, contentDescription = "Save"
                    )
                }
            })
    }) { innerPadding ->
        val listState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            item {
                DefaultCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = deviceNameValue,
                            onValueChange = { deviceNameValue = it },
                            label = { Text(stringResource(R.string.device_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            item {
                DefaultCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.mac_address),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.mac_address_desc),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                detecting -> stringResource(R.string.mac_detecting)
                                macResult?.mac != null ->
                                    stringResource(R.string.mac_detected, macResult!!.mac!!)

                                else -> stringResource(R.string.mac_not_detected)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        rootStatus?.let { status ->
                            Text(
                                text = if (status.available) {
                                    stringResource(
                                        R.string.root_available,
                                        status.method ?: "su"
                                    )
                                } else {
                                    stringResource(R.string.root_unavailable)
                                },
                            )
                            if (status.available) {
                                macResult?.let { result ->
                                    if (result.found) {
                                        Text(
                                            text = stringResource(
                                                R.string.mac_source,
                                                result.source
                                            ),
                                        )
                                    }
                                }
                            } else {
                                Text(text = stringResource(R.string.root_unavailable_desc))
                            }
                        }

                        if (macResult?.found == true && macResult?.reliable == false) {
                            Text(
                                text = stringResource(R.string.mac_derived_warning),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        TextButton(
                            onClick = { detectRequest += 1 },
                            enabled = !detecting,
                        ) {
                            Text(text = stringResource(R.string.redetect_mac))
                        }
                    }
                }
            }
            item {
                DefaultCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = macOverrideValue,
                            onValueChange = { macOverrideValue = it },
                            label = { Text(stringResource(R.string.mac_manual_override)) },
                            isError = macOverrideInvalid,
                            supportingText = {
                                Text(
                                    text = stringResource(
                                        if (macOverrideInvalid) {
                                            R.string.mac_invalid
                                        } else {
                                            R.string.mac_manual_override_desc
                                        }
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            item {
                DefaultCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.verbose_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.weight(1.0f))
                        Switch(checked = verboseValue, onCheckedChange = {
                            verboseValue = it
                        })
                    }
                }
            }
            item {
                DefaultCard(onClick = {
                    Thread {
                        try {
                            val context = MyApplication.getInstance()
                            val logDir = File(context.cacheDir, "logs")
                            logDir.mkdirs()
                            val logFile = File(logDir, "logcat.txt")

                            logFile.outputStream().use {
                                val proc = Runtime.getRuntime().exec("logcat -d")
                                try {
                                    proc.inputStream.copyTo(it)
                                } finally {
                                    proc.destroy()
                                }
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${BuildConfig.APPLICATION_ID}.fileProvider",
                                logFile
                            )
                            val intent = Intent(Intent.ACTION_SEND)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .setType("text/plain")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("LogcatCapture", "Failed to save logs", e)
                            Toast.makeText(
                                context,
                                context.getString(R.string.log_capture_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }.start()
                }) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.capture_logs),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.capture_logs_desc),
                            )
                        }
                    }
                }
            }
        }
    }
}
