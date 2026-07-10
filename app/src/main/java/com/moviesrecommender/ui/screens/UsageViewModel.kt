package com.moviesrecommender.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moviesrecommender.MoviesRecommenderApp
import com.moviesrecommender.data.local.UsageAverage
import com.moviesrecommender.data.local.UsageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UsageViewModel : ViewModel() {

    private val usageStatsService = MoviesRecommenderApp.instance.usageStatsService

    private val _averages = MutableStateFlow<List<UsageAverage>>(emptyList())
    val averages = _averages.asStateFlow()

    private val _entries = MutableStateFlow<List<UsageEntity>>(emptyList())
    val entries = _entries.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _averages.value = usageStatsService.getAverages()
            _entries.value = usageStatsService.getAll()
            _isLoading.value = false
        }
    }
}
