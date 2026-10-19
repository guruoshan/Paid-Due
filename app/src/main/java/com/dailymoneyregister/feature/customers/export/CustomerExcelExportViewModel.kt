package com.dailymoneyregister.feature.customers.export

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class CustomerExcelExportViewModel @Inject constructor(
    private val exporter: CustomerExcelExporter,
    @ApplicationContext private val context: Context
) : ViewModel() {
    fun exportAndShare() {
        Toast.makeText(context, "Preparing customer Excel workbook…", Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            val result = exporter.export()
            result.onSuccess { uri ->
                Toast.makeText(context, "Saved to Downloads/PaidDue. Opening share options…", Toast.LENGTH_LONG).show()
                exporter.share(uri)
            }.onFailure { error ->
                Toast.makeText(context, "Customer export failed: " + (error.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
