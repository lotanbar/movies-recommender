package com.moviesrecommender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.moviesrecommender.AppViewModel
import com.moviesrecommender.navigation.Screen
import com.moviesrecommender.ui.components.CheckButton
import com.moviesrecommender.ui.components.FolderPickerDialog
import com.moviesrecommender.util.ToastManager

@Composable
fun SetupScreen(
    navController: NavHostController,
    appViewModel: AppViewModel,
    showContinueAnyway: Boolean,
    setupViewModel: SetupViewModel = viewModel()
) {
    val dropboxAuthenticated by setupViewModel.dropboxAuthenticated.collectAsState()
    val listPath by setupViewModel.listPath.collectAsState()
    val listPathSet by setupViewModel.listPathSet.collectAsState()
    val isLoading by setupViewModel.isLoading.collectAsState()

    var showListPathDialog by remember { mutableStateOf(false) }
    var pendingClearLabel by remember { mutableStateOf("") }
    var pendingClearAction: (() -> Unit)? by remember { mutableStateOf(null) }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        appViewModel.dropboxAuthCode.collect { code ->
            setupViewModel.handleDropboxCallback(code)
        }
    }

    if (showListPathDialog) {
        FolderPickerDialog(
            onFileSelected = { path ->
                showListPathDialog = false
                setupViewModel.saveListPath(path)
            },
            onDismiss = { showListPathDialog = false }
        )
    }

    if (pendingClearAction != null) {
        ConfirmClearDialog(
            label = pendingClearLabel,
            onConfirm = {
                pendingClearAction?.invoke()
                pendingClearAction = null
            },
            onDismiss = { pendingClearAction = null }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(32.dp))

            CheckButton(
                label = "Authenticate to Dropbox",
                completed = dropboxAuthenticated,
                onClick = { uriHandler.openUri(setupViewModel.getDropboxAuthUrl()) },
                onClear = {
                    pendingClearLabel = "Dropbox authentication"
                    pendingClearAction = { setupViewModel.clearDropboxAuth() }
                }
            )

            Spacer(Modifier.height(12.dp))

            CheckButton(
                label = "Pick list file",
                completed = listPathSet,
                enabled = dropboxAuthenticated,
                subtitle = listPath,
                onClick = {
                    if (!dropboxAuthenticated) ToastManager.show("Authenticate to Dropbox first.")
                    else showListPathDialog = true
                },
                onClear = {
                    pendingClearLabel = "list file path"
                    pendingClearAction = { setupViewModel.clearListPath() }
                }
            )

            if (showContinueAnyway) {
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = {
                        navController.navigate(Screen.Actions.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue anyway")
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ConfirmClearDialog(
    label: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $label?") },
        text = { Text("This will remove the saved $label. You will need to set it up again.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun InputDialog(
    title: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
