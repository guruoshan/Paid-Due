package com.dailymoneyregister.feature.register.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class RegisterWidthClass {
    COMPACT,
    STANDARD,
    EXPANDED
}

enum class RegisterHeightClass {
    SHORT,
    REGULAR,
    TALL
}

enum class RegisterLayoutMode {
    SINGLE_COLUMN,
    TWO_PANE
}

data class RegisterResponsiveSizing(
    val actionHeight: Dp,
    val keypadButtonHeight: Dp,
    val keypadSpacing: Dp,
    val horizontalPadding: Dp,
    val totalTextSize: Int,
    val recentMaxHeight: Dp,
    val compactRecentMaxHeight: Dp,
    val widthClass: RegisterWidthClass,
    val heightClass: RegisterHeightClass,
    val layoutMode: RegisterLayoutMode
) {
    companion object {

        fun forScreen(
            widthDp: Int,
            heightDp: Int
        ): RegisterResponsiveSizing {

            val widthClass =
                when {
                    widthDp >= 600 ->
                        RegisterWidthClass.EXPANDED

                    widthDp >= 360 ->
                        RegisterWidthClass.STANDARD

                    else ->
                        RegisterWidthClass.COMPACT
                }

            val heightClass =
                when {
                    heightDp >= 900 ->
                        RegisterHeightClass.TALL

                    heightDp >= 700 ->
                        RegisterHeightClass.REGULAR

                    else ->
                        RegisterHeightClass.SHORT
                }

            val actionHeight =
                when (heightClass) {
                    RegisterHeightClass.SHORT ->
                        44.dp

                    RegisterHeightClass.REGULAR ->
                        48.dp

                    RegisterHeightClass.TALL ->
                        56.dp
                }

            val isShortExpandedDensity =
                widthClass == RegisterWidthClass.EXPANDED &&
                    heightClass == RegisterHeightClass.SHORT

            val keypadButtonHeight =
                when {
                    isShortExpandedDensity ->
                        48.dp

                    heightClass == RegisterHeightClass.SHORT ->
                        56.dp

                    heightClass == RegisterHeightClass.REGULAR ->
                        64.dp

                    else ->
                        72.dp
                }

            val keypadSpacing =
                when {
                    isShortExpandedDensity ->
                        6.dp

                    heightClass == RegisterHeightClass.SHORT ->
                        8.dp

                    heightClass == RegisterHeightClass.REGULAR ->
                        10.dp

                    else ->
                        12.dp
                }

            val recentMaxHeight =
                when (heightClass) {
                    RegisterHeightClass.SHORT ->
                        180.dp

                    RegisterHeightClass.REGULAR ->
                        260.dp

                    RegisterHeightClass.TALL ->
                        360.dp
                }

            val compactRecentMaxHeight =
                when (heightClass) {
                    RegisterHeightClass.SHORT ->
                        100.dp

                    RegisterHeightClass.REGULAR ->
                        150.dp

                    RegisterHeightClass.TALL ->
                        200.dp
                }

            val horizontalPadding =
                when (widthClass) {
                    RegisterWidthClass.COMPACT ->
                        12.dp

                    RegisterWidthClass.STANDARD ->
                        16.dp

                    RegisterWidthClass.EXPANDED ->
                        24.dp
                }

            val totalTextSize =
                when (widthClass) {
                    RegisterWidthClass.COMPACT ->
                        29

                    RegisterWidthClass.STANDARD ->
                        32

                    RegisterWidthClass.EXPANDED ->
                        36
                }

            val layoutMode =
                when (widthClass) {
                    RegisterWidthClass.EXPANDED ->
                        RegisterLayoutMode.TWO_PANE

                    RegisterWidthClass.COMPACT,
                    RegisterWidthClass.STANDARD ->
                        RegisterLayoutMode.SINGLE_COLUMN
                }

            return RegisterResponsiveSizing(
                actionHeight = actionHeight,
                keypadButtonHeight = keypadButtonHeight,
                keypadSpacing = keypadSpacing,
                horizontalPadding = horizontalPadding,
                totalTextSize = totalTextSize,
                recentMaxHeight = recentMaxHeight,
                compactRecentMaxHeight = compactRecentMaxHeight,
                widthClass = widthClass,
                heightClass = heightClass,
                layoutMode = layoutMode
            )
        }
    }
}