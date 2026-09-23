package com.xiaoqiao.codeagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xiaoqiao.codeagent.ui.chat.ChatScreen
import com.xiaoqiao.codeagent.ui.files.FileBrowserScreen
import com.xiaoqiao.codeagent.ui.setup.SetupScreen
import com.xiaoqiao.codeagent.ui.shell.TerminalScreen
import com.xiaoqiao.codeagent.ui.session.NewSessionScreen

object Routes {
    const val SETUP = "setup"
    const val CHAT = "chat"
    const val NEW_SESSION = "new_session"
    const val TERMINAL = "terminal"
    const val FILES = "files"
}

@Composable
fun AppNav(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val ready by vm.environmentReady.collectAsState()
    val start = if (ready) Routes.CHAT else Routes.SETUP

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.SETUP) {
            SetupScreen(
                onReady = {
                    vm.refreshReady()
                    if (!nav.popBackStack(Routes.CHAT, inclusive = false)) {
                        nav.navigate(Routes.CHAT) {
                            popUpTo(Routes.SETUP) { inclusive = true }
                        }
                    }
                },
                onOpenShell = { nav.navigate(Routes.TERMINAL) },
            )
        }
        composable(Routes.CHAT) {
            ChatScreen(
                onOpenShell = { nav.navigate(Routes.TERMINAL) },
                onOpenFiles = { nav.navigate(Routes.FILES) },
                onNewSession = { nav.navigate(Routes.NEW_SESSION) },
                onReinitSetup = { nav.navigate(Routes.SETUP) },
            )
        }
        composable(Routes.NEW_SESSION) {
            NewSessionScreen(
                onCreated = {
                    nav.navigate(Routes.CHAT) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.TERMINAL) {
            TerminalScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FILES) {
            FileBrowserScreen(onBack = { nav.popBackStack() })
        }
    }
}
