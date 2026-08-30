package com.sighmon.spacextv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sighmon.spacextv.ui.SpaceXTvApp
import com.sighmon.spacextv.ui.SpaceXTvViewModel
import com.sighmon.spacextv.ui.SpaceXTvViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: SpaceXTvViewModel = viewModel(factory = SpaceXTvViewModelFactory(applicationContext))
            SpaceXTvApp(model)
        }
    }
}
