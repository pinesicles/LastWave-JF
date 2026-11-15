package com.lastwave.app.ui.shell

import androidx.lifecycle.ViewModel
import com.lastwave.app.ui.library.JellyNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class JellyNavHolder @Inject constructor(val navigator: JellyNavigator) : ViewModel()
