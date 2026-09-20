package com.xiaoqiao.codeagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoqiao.codeagent.runtime.Bootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _environmentReady = MutableStateFlow(false)
    val environmentReady: StateFlow<Boolean> = _environmentReady.asStateFlow()

    init {
        refreshReady()
    }

    fun refreshReady() {
        viewModelScope.launch {
            _environmentReady.value = Bootstrap.isReady(getApplication())
        }
    }
}
