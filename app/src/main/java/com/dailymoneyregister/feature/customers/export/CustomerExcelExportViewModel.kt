package com.dailymoneyregister.feature.customers.export

import com.dailymoneyregister.R

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
        Toast.makeText(context, context.getString(R.string.audit_customer_export_preparing), Toast.LENGTH_SHORT).show()
        viewModelScope.launch {
            val result = exporter.export()
            result.onSuccess { uri ->
                Toast.makeText(context, context.getString(R.string.audit_customer_export_saved), Toast.LENGTH_LONG).show()
                exporter.share(uri)
            }.onFailure { error ->
                Toast.makeText(context, context.getString(R.string.audit_customer_export_failed, error.message ?: context.getString(R.string.audit_unknown_error)), Toast.LENGTH_LONG).show()
            }
        }
    }
}
