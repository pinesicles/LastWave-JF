package com.lastwave.app.ui.auth

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.jellyfin.DiscoveredServer
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.theme.ExpressivePillShape

private const val LAST_FM_CREATE_KEY_PAGE = "https://www.last.fm/api/account/create"

/**
 * First-run screen.
 *
 *  1. Jellyfin: server address + username + password. A saved Jellyfin session
 *     is what unlocks the app (see LaunchGateViewModel); the caller's gate
 *     then navigates to MainShell by itself.
 *  2. Last.fm (optional): paste your own API key + secret, then approve in the
 *     browser. Connecting Last.fm alone does not leave this screen.
 *  3. "Use local files only": persisted guest mode for playing device files.
 */
@Composable
fun LoginScreen(
    onContinueAsGuest: () -> Unit,
    onRestoreBackupAndSignIn: ((String) -> Unit)? = null,
    onDismissError: (() -> Unit)? = null,
    errorMessage: String? = null,
    isBusy: Boolean = false,
    onOpenDownloads: (() -> Unit)? = null,
    jellyfinViewModel: JellyfinLoginViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val jfState by jellyfinViewModel.state.collectAsStateWithLifecycle()
    val webAuth by authViewModel.webAuthState.collectAsStateWithLifecycle()
    val hasApiKey by authViewModel.hasApiKey.collectAsStateWithLifecycle()
    val lastFmUser by authViewModel.lastFmUsername.collectAsStateWithLifecycle()

    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var apiSecret by rememberSaveable { mutableStateOf("") }

    var restoreReadError by remember {
        mutableStateOf(
            if (com.lastwave.app.di.DataStoreModule.consumePrefsReset()) context.getString(R.string.login_prefs_reset) else null,
        )
    }
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && onRestoreBackupAndSignIn != null) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read this backup")
            }
            content.onSuccess {
                restoreReadError = null
                onRestoreBackupAndSignIn(it)
            }.onFailure {
                restoreReadError = it.message ?: "Could not read this backup"
            }
        }
    }

    // Last.fm's approval page opens in the browser; the app's callback link brings the user back.
    LaunchedEffect(webAuth) {
        (webAuth as? WebAuthState.AwaitingApproval)?.let { awaiting ->
            runCatching {
                androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                    .launchUrl(context, Uri.parse(awaiting.authUrl))
            }
        }
    }

    // One server on the network and nothing typed yet: fill it in.
    LaunchedEffect(jfState.servers) {
        if (server.isBlank() && jfState.servers.size == 1) server = jfState.servers[0].address
    }

    val busy = isBusy || jfState.busy
    val connectJellyfin = { jellyfinViewModel.connect(server, username, password) }

    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .adaptiveContentWidth(maxWidth = 480.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(16.dp).size(32.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.login_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                // ---- Jellyfin ------------------------------------------------
                SectionCard(title = stringResource(R.string.jf_login_section)) {
                    ServerScanRow(
                        scanning = jfState.scanning,
                        scanned = jfState.scanned,
                        servers = jfState.servers,
                        enabled = !busy,
                        onScan = jellyfinViewModel::scan,
                        onPick = { server = it.address },
                    )
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(R.string.jf_login_server)) },
                        placeholder = { Text(stringResource(R.string.jf_login_server_hint)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.jf_login_username)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.jf_login_password)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { connectJellyfin() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { connectJellyfin() },
                        enabled = !busy,
                        shape = ExpressivePillShape,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(
                            stringResource(
                                if (jfState.busy) R.string.jf_login_connecting else R.string.jf_login_connect,
                            ),
                        )
                    }
                    jfState.error?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- Last.fm (optional) -----------------------------------------
                SectionCard(title = stringResource(R.string.jf_lastfm_section)) {
                    when {
                        lastFmUser.isNotBlank() -> Text(
                            stringResource(R.string.jf_lastfm_connected, lastFmUser),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        webAuth is WebAuthState.AwaitingApproval || webAuth is WebAuthState.CompletingSignIn -> {
                            Text(
                                stringResource(R.string.jf_lastfm_waiting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = authViewModel::cancelSignIn) {
                                Text(stringResource(R.string.jf_lastfm_cancel))
                            }
                        }
                        hasApiKey -> {
                            Text(
                                stringResource(R.string.set_lfm_key_saved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = authViewModel::beginSignIn,
                                enabled = !busy,
                                shape = ExpressivePillShape,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) { Text(stringResource(R.string.set_lfm_connect)) }
                        }
                        else -> {
                            Text(
                                stringResource(R.string.set_lfm_key_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text(stringResource(R.string.set_lfm_apikey)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = apiSecret,
                                onValueChange = { apiSecret = it },
                                label = { Text(stringResource(R.string.set_lfm_secret)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = {
                                        authViewModel.saveApiCredentials(apiKey, apiSecret)
                                        apiKey = ""
                                        apiSecret = ""
                                    },
                                    enabled = apiKey.trim().length >= 16 && apiSecret.trim().length >= 16,
                                    shape = ExpressivePillShape,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.set_lfm_save)) }
                            }
                            TextButton(onClick = {
                                runCatching {
                                    androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                                        .launchUrl(context, Uri.parse(LAST_FM_CREATE_KEY_PAGE))
                                }
                            }) { Text(stringResource(R.string.set_lfm_get)) }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- Local files only -------------------------------------------
                OutlinedButton(
                    onClick = onContinueAsGuest,
                    enabled = !busy,
                    shape = ExpressivePillShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(
                        Icons.Filled.PersonOutline,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.jf_login_local_only))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.jf_login_local_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (onRestoreBackupAndSignIn != null) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { restoreBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        enabled = !busy,
                        shape = ExpressivePillShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(R.string.login_restore))
                    }
                }

                if (onOpenDownloads != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenDownloads) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(stringResource(R.string.login_downloads))
                    }
                }

                val visibleError = errorMessage ?: restoreReadError
                if (visibleError != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(visibleError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    if (onDismissError != null) {
                        TextButton(onClick = {
                            restoreReadError = null
                            onDismissError()
                        }) { Text(stringResource(R.string.gen_dismiss)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ServerScanRow(
    scanning: Boolean,
    scanned: Boolean,
    servers: List<DiscoveredServer>,
    enabled: Boolean,
    onScan: () -> Unit,
    onPick: (DiscoveredServer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.jf_scan_looking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            servers.forEach { found ->
                OutlinedButton(
                    onClick = { onPick(found) },
                    enabled = enabled,
                    shape = ExpressivePillShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(found.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            found.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (scanned && servers.isEmpty()) {
                Text(
                    stringResource(R.string.jf_scan_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onScan, enabled = enabled) { Text(stringResource(R.string.jf_scan_again)) }
        }
    }
}
