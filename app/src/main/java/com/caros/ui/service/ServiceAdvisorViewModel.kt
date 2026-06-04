package com.caros.ui.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caros.db.CarOSDatabase
import com.caros.service.ServiceAdvisor
import com.caros.service.ServiceItem
import com.caros.service.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ServiceAdvisorViewModel @Inject constructor(
    private val serviceAdvisor: ServiceAdvisor,
    private val db: CarOSDatabase
) : ViewModel() {

    private val _serviceItems = MutableStateFlow<List<ServiceItem>>(emptyList())
    val serviceItems: StateFlow<List<ServiceItem>> = _serviceItems.asStateFlow()

    init {
        viewModelScope.launch {
            val currentKm = db.tripDao().totalDistanceKm()?.toInt() ?: 0
            loadItems(currentKm)
        }
    }

    fun loadItems(currentKm: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _serviceItems.value = serviceAdvisor.getAllServiceItems(currentKm)
            } catch (e: Exception) {
                Timber.e(e, "ServiceAdvisorViewModel: loadItems failed")
            }
        }
    }

    fun recordService(type: ServiceType, km: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                serviceAdvisor.recordServiceDone(type, km)
                loadItems(km)
            } catch (e: Exception) {
                Timber.e(e, "ServiceAdvisorViewModel: recordService failed")
            }
        }
    }
}
