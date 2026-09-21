package com.xiaoqiao.codeagent

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xiaoqiao.codeagent.runtime.SshdServer
import com.xiaoqiao.codeagent.ui.AppNav
import com.xiaoqiao.codeagent.ui.theme.CodeAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (SshdServer.isWanted(this) && !SshdServer.isRunning()) {
            SshdServer.start(this)
        }
        enableEdgeToEdge()
        setContent {
            CodeAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav()
                }
            }
        }
    }
}
