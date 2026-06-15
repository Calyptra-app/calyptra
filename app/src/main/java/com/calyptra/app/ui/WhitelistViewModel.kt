package com.calyptra.app.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calyptra.app.CalyptraApp
import com.calyptra.app.data.WhitelistedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class AppItem(
    val packageName: String,
    val label: String,
    val isWhitelisted: Boolean
)

class WhitelistViewModel(application: Application) : AndroidViewModel(application) {
    
    private val whitelistDao = (application as CalyptraApp).database.whitelistDao()
    private val packageManager = application.packageManager
    
    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())
    val apps: StateFlow<List<AppItem>> = _apps.asStateFlow()
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _isLoading.value = true
            val installedApps = withContext(Dispatchers.IO) {
                packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                    .filter { 
                        // Filter out system apps? Maybe not all. 
                        // Usually filter out apps that don't have launch intent or are system
                        val isSystem = (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        !isSystem || packageManager.getLaunchIntentForPackage(it.packageName) != null
                    }
                    .map { 
                        it.packageName to it.applicationInfo.loadLabel(packageManager).toString() 
                    }
            }
            
            // Observe DB
            whitelistDao.getAll().collect { whitelisted ->
                val whitelistedSet = whitelisted.map { it.packageName }.toSet()
                
                val items = installedApps.map { (pkg, label) ->
                    AppItem(
                        packageName = pkg,
                        label = label,
                        isWhitelisted = whitelistedSet.contains(pkg)
                    )
                }.sortedBy { it.label }
                
                _apps.value = items
                _isLoading.value = false
            }
        }
    }

    fun toggleWhitelist(app: AppItem) {
        viewModelScope.launch {
            if (app.isWhitelisted) {
                // Remove
                val entity = WhitelistedApp(app.packageName, app.label, Instant.now()) // timestamp doesn't matter for delete
                whitelistDao.delete(entity)
            } else {
                // Add
                val entity = WhitelistedApp(app.packageName, app.label, Instant.now())
                whitelistDao.insert(entity)
            }
        }
    }
}
