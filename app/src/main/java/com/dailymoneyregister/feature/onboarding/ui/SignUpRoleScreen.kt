package com.dailymoneyregister.feature.onboarding.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R

@Composable
fun SignUpRoleScreen(
    onBusinessAdmin: () -> Unit,
    businessAlreadyExists: Boolean,
    onGoToLogin: () -> Unit,
    onBack: () -> Unit
) {
    var showEmployeeInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (showEmployeeInfo) {
            Text(
                text = stringResource(R.string.employee_invite_info),
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            OutlinedButton(
                onClick = { showEmployeeInfo = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.back))
            }
            return@Column
        }

        Text(
            text = stringResource(R.string.choose_account_type),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )
        RoleCard(stringResource(R.string.business_admin), onBusinessAdmin)
        if (businessAlreadyExists) {
            Text(
                text = stringResource(R.string.business_already_exists),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp)
            )
            Button(
                onClick = onGoToLogin,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.go_to_login))
            }
        }
        RoleCard(
            title = stringResource(R.string.employee),
            onClick = { showEmployeeInfo = true },
            modifier = Modifier.padding(top = 12.dp)
        )
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
        ) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun RoleCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(24.dp)
        )
    }
}
