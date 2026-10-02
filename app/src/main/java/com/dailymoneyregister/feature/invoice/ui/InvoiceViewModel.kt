package com.dailymoneyregister.feature.invoice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailymoneyregister.R
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.identity.InvalidPhoneNumberException
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.SessionManager
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.core.ui.UiText
import com.dailymoneyregister.feature.invoice.data.GenerateInvoiceCommand
import com.dailymoneyregister.feature.invoice.data.InvoiceRepository
import com.dailymoneyregister.feature.invoice.data.IssuedInvoice
import com.dailymoneyregister.feature.invoice.pdf.InvoicePdfRenderer
import com.dailymoneyregister.feature.invoice.pdf.InvoiceSharing
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.dailymoneyregister.feature.invoice.domain.InvoiceBusinessSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceCustomerSnapshot
import com.dailymoneyregister.feature.invoice.domain.InvoiceSnapshot
import com.dailymoneyregister.feature.register.domain.DraftState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InvoiceUiState(
    val isFormOpen: Boolean = false,
    /** Stable code stored in the snapshot: MR, MRS, MS, DR or null. */
    val title: String? = null,
    val name: String = "",
    val phone: PhoneNumberInputValue? = null,
    val address: String = "",
    val existingCustomerName: String? = null,
    val showValidation: Boolean = false,
    val isIssuing: Boolean = false,
    val issuedNumber: String? = null,
    /** Set once the just-issued invoice's PDF is ready to share. */
    val issuedDocumentId: String? = null,
    val isPreparingShare: Boolean = false,
    val error: UiText? = null
)

/**
 * Owns the invoice form only. It never touches the register draft:
 * the screen passes the current DraftState when the invoice is saved.
 * An invoice is a customer document - saving it does not clear the sale.
 */
@HiltViewModel
class InvoiceViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val customerDao: CustomerDao,
    private val deviceIdentityProvider: DeviceIdentityProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(InvoiceUiState())
    val uiState: StateFlow<InvoiceUiState> = _uiState.asStateFlow()

    private var lookupJob: Job? = null

    /** UI gate only. The repository enforces the real permission check again. */
    fun canIssueInvoice(): Boolean {
        val session = SessionManager.currentSession.value ?: return false
        if (session.requiresPinChange) return false
        return PermissionChecker.isPermitted(
            session.user,
            session.membership,
            PermissionAction.ISSUE_INVOICE
        )
    }

    fun openForm() {
        val branch = SessionManager.activeBranch ?: return
        val phone = try {
            PhoneNumberInputValue(countryCode = branch.countryCode.uppercase())
        } catch (_: IllegalArgumentException) {
            PhoneNumberInputValue(countryCode = "OM")
        }
        lookupJob?.cancel()
        _uiState.value = InvoiceUiState(isFormOpen = true, phone = phone)
    }

    fun dismissForm() {
        if (_uiState.value.isIssuing) return
        lookupJob?.cancel()
        _uiState.value = InvoiceUiState()
    }

    fun onTitleChange(title: String?) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun onNameChange(name: String) {
        val state = _uiState.value
        if (state.existingCustomerName != null || name.length > 120) return
        _uiState.value = state.copy(name = name)
    }

    fun onAddressChange(address: String) {
        if (address.length > 300) return
        _uiState.value = _uiState.value.copy(address = address)
    }

    fun onPhoneChange(phone: PhoneNumberInputValue) {
        _uiState.value = _uiState.value.copy(phone = phone)
        lookupJob?.cancel()

        val normalized = try {
            phone.normalizedNumber()
        } catch (_: InvalidPhoneNumberException) {
            null
        }
        val businessId = SessionManager.activeBusiness?.id

        if (normalized == null || businessId == null) {
            _uiState.value = _uiState.value.copy(existingCustomerName = null)
            return
        }

        lookupJob = viewModelScope.launch {
            val customer = try {
                customerDao.getCustomerByPhone(businessId, normalized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val current = _uiState.value
            _uiState.value = if (customer != null) {
                current.copy(existingCustomerName = customer.name, name = customer.name)
            } else {
                current.copy(existingCustomerName = null)
            }
        }
    }

    fun issue(draft: DraftState) {
        val current = _uiState.value
        if (current.isIssuing) return

        val session = SessionManager.currentSession.value
        if (session == null) {
            fail(UiText.Resource(R.string.inv_error_session))
            return
        }
        if (!draft.isItemized) {
            fail(UiText.Resource(R.string.inv_error_no_items))
            return
        }

        val name = current.name.trim()
        val normalizedPhone = try {
            current.phone?.normalizedNumber()
        } catch (_: InvalidPhoneNumberException) {
            null
        }
        if (name.isBlank() || normalizedPhone == null) {
            _uiState.value = current.copy(showValidation = true)
            return
        }

        val now = System.currentTimeMillis()
        val branch = session.branch

        val snapshot = InvoiceSnapshot.fromDraft(
            documentId = IdGenerator.newId(),
            draft = draft,
            customer = InvoiceCustomerSnapshot(
                title = current.title,
                name = name,
                phone = normalizedPhone,
                address = current.address.trim().ifEmpty { null }
            ),
            business = InvoiceBusinessSnapshot(
                name = session.business.name,
                registrationNumber = null,
                taxId = branch.taxRegistrationNumber,
                address = branch.address,
                phone = branch.phone
            ),
            currencyCode = branch.currencyCode,
            currencyDecimalPlaces = branch.currencyDecimalPlaces,
            note = draft.manualDescription,
            createdAt = now
        )

        val command = GenerateInvoiceCommand(
            operationId = OperationId.generate(),
            businessId = session.business.id,
            branchId = branch.id,
            actorUserId = session.user.id,
            snapshot = snapshot,
            deviceId = deviceIdentityProvider.deviceId,
            occurredAt = now
        )

        _uiState.value = current.copy(isIssuing = true, error = null)

        viewModelScope.launch {
            val result: CommandResult<*> = try {
                invoiceRepository.executeGenerateInvoice(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CommandResult.Error(error)
            }

            when (result) {
                is CommandResult.Success -> {
                    val document = result.data as com.dailymoneyregister.core.database.entity.DocumentEntity
                    _uiState.value = InvoiceUiState(
                        issuedNumber = document.displayNumber,
                        issuedDocumentId = document.id
                    )
                }
                is CommandResult.Conflict -> fail(generic(result.message))
                is CommandResult.PermissionDenied -> fail(generic(result.message))
                is CommandResult.Error -> fail(generic(result.exception.message ?: "unknown error"))
            }
        }
    }

    fun dismissIssued() {
        _uiState.value = _uiState.value.copy(issuedNumber = null, issuedDocumentId = null)
    }

    /**
     * Renders the just-issued invoice to a PDF and opens the Android share
     * sheet. Safe to call multiple times; each call re-renders from the
     * frozen snapshot, so the PDF always matches what was actually saved.
     */
    fun sharePdf(context: Context) {
        launchShare(context) { file -> InvoiceSharing.sharePdf(context, file) }
    }

    fun shareWhatsApp(context: Context) {
        launchShare(context) { file ->
            val invoice = renderedInvoiceCache ?: return@launchShare
            InvoiceSharing.shareWhatsApp(context, invoice, file)
        }
    }

    private var renderedInvoiceCache: IssuedInvoice? = null

    private fun launchShare(context: Context, onReady: (File) -> Unit) {
        val documentId = _uiState.value.issuedDocumentId ?: return
        if (_uiState.value.isPreparingShare) return

        _uiState.value = _uiState.value.copy(isPreparingShare = true)
        viewModelScope.launch {
            try {
                val invoice = renderedInvoiceCache?.takeIf { it.document.id == documentId }
                    ?: invoiceRepository.getInvoice(documentId)?.also { renderedInvoiceCache = it }

                if (invoice == null) {
                    fail(UiText.Resource(R.string.inv_error_generic, listOf("invoice not found")))
                    return@launch
                }

                val file = withContext(Dispatchers.IO) {
                    InvoicePdfRenderer.render(context.applicationContext, invoice)
                }
                onReady(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(UiText.Resource(R.string.inv_error_generic, listOf(error.message ?: "pdf render failed")))
            } finally {
                _uiState.value = _uiState.value.copy(isPreparingShare = false)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun generic(message: String) = UiText.Resource(R.string.inv_error_generic, listOf(message))

    private fun fail(error: UiText) {
        _uiState.value = _uiState.value.copy(isIssuing = false, error = error)
    }
}
