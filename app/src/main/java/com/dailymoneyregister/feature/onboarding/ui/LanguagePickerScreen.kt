package com.dailymoneyregister.feature.onboarding.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailymoneyregister.R
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.i18n.LanguageRegistry

@Composable
fun LanguagePickerScreen(
    languageManager: LanguageManager,
    onContinue: () -> Unit
) {
    val selectedLanguage by languageManager.currentLanguage.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.choose_language),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LanguageRegistry.allLanguages.forEach { language ->
            val isSelected = language.code == selectedLanguage.code
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { languageManager.setLanguage(language.code) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(language.nativeName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            language.englishName,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                languageManager.markLanguageChosen()
                onContinue()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(stringResource(R.string.continue_btn))
        }
    }
}
