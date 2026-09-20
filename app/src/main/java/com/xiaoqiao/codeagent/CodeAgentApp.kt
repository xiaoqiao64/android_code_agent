package com.xiaoqiao.codeagent

import android.app.Application

class CodeAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CodeAgentApp
            private set
    }
}
