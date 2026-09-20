package com.xiaoqiao.codeagent.ui.session

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoqiao.codeagent.agent.Agents
import com.xiaoqiao.codeagent.session.SessionStore
import kotlinx.coroutines.launch

class NewSessionViewModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(app)
}

@Composable
fun NewSessionScreen(
    onCreated: () -> Unit,
    onBack: () -> Unit,
    vm: NewSessionViewModel = viewModel(),
) {
    var workspaces by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var agentId by remember { mutableStateOf(Agents.claude.id) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        workspaces = vm.store.listWorkspaces()
        selected = workspaces.firstOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("New session", style = MaterialTheme.typography.headlineSmall)
        Text("Agent", style = MaterialTheme.typography.titleMedium)
        Agents.all.forEach { agent ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { agentId = agent.id },
            ) {
                RadioButton(selected = agentId == agent.id, onClick = { agentId = agent.id })
                Text(agent.label)
            }
        }
        Text("Workspace", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(workspaces) { ws ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = ws }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = selected == ws, onClick = { selected = ws })
                    Text(ws)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New folder name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                val ws = vm.store.createWorkspace(newName)
                workspaces = vm.store.listWorkspaces()
                selected = ws
                newName = ""
            }, enabled = newName.isNotBlank()) {
                Text("Create")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Cancel") }
            Button(
                onClick = {
                    val ws = selected ?: return@Button
                    scope.launch {
                        vm.store.create(agentId, ws)
                        onCreated()
                    }
                },
                enabled = selected != null,
            ) { Text("Start") }
        }
    }
}
