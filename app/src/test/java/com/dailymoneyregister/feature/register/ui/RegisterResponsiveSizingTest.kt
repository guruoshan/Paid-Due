package com.dailymoneyregister.feature.register.ui

import com.dailymoneyregister.feature.register.ui.components.RecentRowHeightBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterResponsiveSizingTest {

    @Test
    fun compactScreenKeepsPhoneSizedControls() {

        val sizing =
            RegisterResponsiveSizing.forScreen(
                320,
                640
            )

        assertEquals(
            RegisterWidthClass.COMPACT,
            sizing.widthClass
        )

        assertEquals(
            RegisterHeightClass.SHORT,
            sizing.heightClass
        )

        assertEquals(
            RegisterLayoutMode.SINGLE_COLUMN,
            sizing.layoutMode
        )

        assertEquals(
            44,
            sizing.actionHeight.value.toInt()
        )

        assertEquals(
            56,
            sizing.keypadButtonHeight.value.toInt()
        )

        assertEquals(
            12,
            sizing.horizontalPadding.value.toInt()
        )

        assertEquals(
            29,
            sizing.totalTextSize
        )
    }

    @Test
    fun normalPhoneUsesComfortableSizing() {

        val sizing =
            RegisterResponsiveSizing.forScreen(
                400,
                760
            )

        assertEquals(
            RegisterWidthClass.STANDARD,
            sizing.widthClass
        )

        assertEquals(
            RegisterHeightClass.REGULAR,
            sizing.heightClass
        )

        assertEquals(
            RegisterLayoutMode.SINGLE_COLUMN,
            sizing.layoutMode
        )

        assertEquals(
            48,
            sizing.actionHeight.value.toInt()
        )

        assertEquals(
            64,
            sizing.keypadButtonHeight.value.toInt()
        )

        assertEquals(
            16,
            sizing.horizontalPadding.value.toInt()
        )

        assertEquals(
            32,
            sizing.totalTextSize
        )
    }

    @Test
    fun largeScreenScalesInteractiveControlsAndRecentRows() {

        val sizing =
            RegisterResponsiveSizing.forScreen(
                800,
                1280
            )

        assertEquals(
            RegisterWidthClass.EXPANDED,
            sizing.widthClass
        )

        assertEquals(
            RegisterHeightClass.TALL,
            sizing.heightClass
        )

        assertEquals(
            RegisterLayoutMode.TWO_PANE,
            sizing.layoutMode
        )

        assertEquals(
            56,
            sizing.actionHeight.value.toInt()
        )

        assertEquals(
            72,
            sizing.keypadButtonHeight.value.toInt()
        )

        assertEquals(
            24,
            sizing.horizontalPadding.value.toInt()
        )

        assertEquals(
            36,
            sizing.totalTextSize
        )

        assertTrue(
            sizing.recentMaxHeight.value > 260f
        )
    }

    @Test
    fun widthBoundary359IsCompact() {

        assertEquals(
            RegisterWidthClass.COMPACT,
            RegisterResponsiveSizing
                .forScreen(
                    359,
                    760
                )
                .widthClass
        )
    }

    @Test
    fun widthBoundary360IsStandard() {

        assertEquals(
            RegisterWidthClass.STANDARD,
            RegisterResponsiveSizing
                .forScreen(
                    360,
                    760
                )
                .widthClass
        )
    }

    @Test
    fun widthBoundary599IsStandard() {

        assertEquals(
            RegisterWidthClass.STANDARD,
            RegisterResponsiveSizing
                .forScreen(
                    599,
                    760
                )
                .widthClass
        )
    }

    @Test
    fun widthBoundary600IsExpanded() {

        assertEquals(
            RegisterWidthClass.EXPANDED,
            RegisterResponsiveSizing
                .forScreen(
                    600,
                    760
                )
                .widthClass
        )
    }

    @Test
    fun heightBoundary699IsShort() {

        assertEquals(
            RegisterHeightClass.SHORT,
            RegisterResponsiveSizing
                .forScreen(
                    400,
                    699
                )
                .heightClass
        )
    }

    @Test
    fun heightBoundary700IsRegular() {

        assertEquals(
            RegisterHeightClass.REGULAR,
            RegisterResponsiveSizing
                .forScreen(
                    400,
                    700
                )
                .heightClass
        )
    }

    @Test
    fun heightBoundary899IsRegular() {

        assertEquals(
            RegisterHeightClass.REGULAR,
            RegisterResponsiveSizing
                .forScreen(
                    400,
                    899
                )
                .heightClass
        )
    }

    @Test
    fun heightBoundary900IsTall() {

        assertEquals(
            RegisterHeightClass.TALL,
            RegisterResponsiveSizing
                .forScreen(
                    400,
                    900
                )
                .heightClass
        )
    }

    @Test
    fun tallNarrowPhoneNeverBecomesExpandedLayout() {

        val sizing =
            RegisterResponsiveSizing.forScreen(
                320,
                1000
            )

        assertEquals(
            RegisterWidthClass.COMPACT,
            sizing.widthClass
        )

        assertEquals(
            RegisterHeightClass.TALL,
            sizing.heightClass
        )

        assertEquals(
            RegisterLayoutMode.SINGLE_COLUMN,
            sizing.layoutMode
        )

        // Width remains compact.
        assertEquals(
            12,
            sizing.horizontalPadding.value.toInt()
        )

        assertEquals(
            29,
            sizing.totalTextSize
        )

        // Vertical controls may still use tall-height density.
        assertEquals(
            56,
            sizing.actionHeight.value.toInt()
        )

        assertEquals(
            72,
            sizing.keypadButtonHeight.value.toInt()
        )
    }

    @Test
    fun shortExpandedWindowUsesExpandedWidthAndShortHeightDensity() {

        val sizing =
            RegisterResponsiveSizing.forScreen(
                915,
                412
            )

        assertEquals(
            RegisterWidthClass.EXPANDED,
            sizing.widthClass
        )

        assertEquals(
            RegisterHeightClass.SHORT,
            sizing.heightClass
        )

        assertEquals(
            RegisterLayoutMode.TWO_PANE,
            sizing.layoutMode
        )

        // Structural width profile remains expanded.
        assertEquals(
            24,
            sizing.horizontalPadding.value.toInt()
        )

        assertEquals(
            36,
            sizing.totalTextSize
        )

        // Payment and secondary actions retain the existing
        // short-height action target.
        assertEquals(
            44,
            sizing.actionHeight.value.toInt()
        )

        // C2.1: only expanded + short windows use the tighter
        // keypad density needed to fit landscape safely.
        assertEquals(
            48,
            sizing.keypadButtonHeight.value.toInt()
        )

        assertEquals(
            6,
            sizing.keypadSpacing.value.toInt()
        )
    }

    @Test
    fun compactShortPhoneDoesNotUseExpandedLandscapeDensity() {

        val sizing =
            RegisterResponsiveSizing.forScreen(
                320,
                640
            )

        assertEquals(
            RegisterWidthClass.COMPACT,
            sizing.widthClass
        )

        assertEquals(
            RegisterHeightClass.SHORT,
            sizing.heightClass
        )

        assertEquals(
            RegisterLayoutMode.SINGLE_COLUMN,
            sizing.layoutMode
        )

        assertEquals(
            56,
            sizing.keypadButtonHeight.value.toInt()
        )

        assertEquals(
            8,
            sizing.keypadSpacing.value.toInt()
        )
    }

    @Test
    fun standardWidthShortHeightSeparatesTheTwoAxes() {

        val sizing =
            RegisterResponsiveSizing.forScreen(
                400,
                640
            )

        assertEquals(
            RegisterWidthClass.STANDARD,
            sizing.widthClass
        )

        assertEquals(
            RegisterHeightClass.SHORT,
            sizing.heightClass
        )

        assertEquals(
            RegisterLayoutMode.SINGLE_COLUMN,
            sizing.layoutMode
        )

        assertEquals(
            16,
            sizing.horizontalPadding.value.toInt()
        )

        assertEquals(
            32,
            sizing.totalTextSize
        )

        assertEquals(
            44,
            sizing.actionHeight.value.toInt()
        )
    }

    @Test
    fun expectedWindowMatrixClassifiesDeterministically() {

        data class Case(
            val width: Int,
            val height: Int,
            val expectedWidth: RegisterWidthClass,
            val expectedHeight: RegisterHeightClass,
            val expectedLayout: RegisterLayoutMode
        )

        val cases =
            listOf(
                Case(
                    width = 320,
                    height = 640,
                    expectedWidth = RegisterWidthClass.COMPACT,
                    expectedHeight = RegisterHeightClass.SHORT,
                    expectedLayout = RegisterLayoutMode.SINGLE_COLUMN
                ),
                Case(
                    width = 360,
                    height = 800,
                    expectedWidth = RegisterWidthClass.STANDARD,
                    expectedHeight = RegisterHeightClass.REGULAR,
                    expectedLayout = RegisterLayoutMode.SINGLE_COLUMN
                ),
                Case(
                    width = 412,
                    height = 915,
                    expectedWidth = RegisterWidthClass.STANDARD,
                    expectedHeight = RegisterHeightClass.TALL,
                    expectedLayout = RegisterLayoutMode.SINGLE_COLUMN
                ),
                Case(
                    width = 915,
                    height = 412,
                    expectedWidth = RegisterWidthClass.EXPANDED,
                    expectedHeight = RegisterHeightClass.SHORT,
                    expectedLayout = RegisterLayoutMode.TWO_PANE
                ),
                Case(
                    width = 600,
                    height = 960,
                    expectedWidth = RegisterWidthClass.EXPANDED,
                    expectedHeight = RegisterHeightClass.TALL,
                    expectedLayout = RegisterLayoutMode.TWO_PANE
                ),
                Case(
                    width = 840,
                    height = 1280,
                    expectedWidth = RegisterWidthClass.EXPANDED,
                    expectedHeight = RegisterHeightClass.TALL,
                    expectedLayout = RegisterLayoutMode.TWO_PANE
                )
            )

        cases.forEach { case ->

            val sizing =
                RegisterResponsiveSizing.forScreen(
                    case.width,
                    case.height
                )

            assertEquals(
                case.expectedWidth,
                sizing.widthClass
            )

            assertEquals(
                case.expectedHeight,
                sizing.heightClass
            )

            assertEquals(
                case.expectedLayout,
                sizing.layoutMode
            )
        }
    }

    @Test
    fun compactHeightGrowsAcrossScreenSizesAndRemainsBelowExpandedHeight() {

        val sizes =
            listOf(
                RegisterResponsiveSizing.forScreen(
                    320,
                    640
                ),
                RegisterResponsiveSizing.forScreen(
                    400,
                    760
                ),
                RegisterResponsiveSizing.forScreen(
                    800,
                    1280
                )
            )

        assertEquals(
            listOf(
                100f,
                150f,
                200f
            ),
            sizes.map {
                it.compactRecentMaxHeight.value
            }
        )

        assertEquals(
            listOf(
                180f,
                260f,
                360f
            ),
            sizes.map {
                it.recentMaxHeight.value
            }
        )

        sizes
            .zipWithNext()
            .forEach { (smaller, larger) ->

                assertTrue(
                    smaller.compactRecentMaxHeight <
                        larger.compactRecentMaxHeight
                )
            }

        sizes.forEach {

            assertTrue(
                it.compactRecentMaxHeight <
                    it.recentMaxHeight
            )
        }
    }

    @Test
    fun heightBreakpointsIncreaseVerticalRecentBudgetAtSameWidth() {

        assertEquals(
            listOf(
                100f,
                150f,
                200f
            ),
            listOf(
                640,
                700,
                900
            ).map {

                RegisterResponsiveSizing
                    .forScreen(
                        320,
                        it
                    )
                    .compactRecentMaxHeight
                    .value
            }
        )
    }

    @Test
    fun compactCapacityDependsOnMeasuredHeightRatherThanFixedRowCount() {

        fun capacity(
            maxHeight: Int,
            rowHeight: Int
        ): Int {

            val budget =
                RecentRowHeightBudget(
                    maxHeight,
                    6
                )

            return generateSequence {
                rowHeight
            }
                .takeWhile {
                    budget.add(it)
                }
                .count()
        }

        assertEquals(
            2,
            capacity(
                100,
                40
            )
        )

        assertEquals(
            4,
            capacity(
                200,
                40
            )
        )

        assertEquals(
            3,
            capacity(
                200,
                60
            )
        )

        assertEquals(
            7,
            capacity(
                200,
                20
            )
        )
    }

    @Test
    fun compactBudgetIncludesOnlyCompleteRowsAndNoReservedSlots() {

        val budget =
            RecentRowHeightBudget(
                100,
                6
            )

        assertEquals(
            0,
            budget.usedHeight
        )

        assertTrue(
            budget.add(40)
        )

        assertTrue(
            budget.add(54)
        )

        assertEquals(
            100,
            budget.usedHeight
        )

        assertFalse(
            budget.add(1)
        )

        assertEquals(
            100,
            budget.usedHeight
        )

        val tooSmall =
            RecentRowHeightBudget(
                30,
                6
            )

        assertFalse(
            tooSmall.add(40)
        )

        assertEquals(
            0,
            tooSmall.usedHeight
        )
    }
}