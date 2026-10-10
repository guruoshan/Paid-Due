---
name: premium-android-ux
description: Modern Material 3 UI/UX design, Jetpack Compose layouts, smooth animations, touch target accessibility, and responsive Android interfaces. Use when creating UI components, fixing visual Polish/UX issues, implementing dark/light themes, or auditing accessibility.
---

# Premium Android UX

## Activation Criteria
- Designing or implementing user interfaces, screens, or Jetpack Compose components.
- Resolving visual bugs, layout clipping, touch responsiveness, or dark mode defects.
- Improving accessibility (a11y), haptic feedback, or micro-animations.

## Engineering Standards
- **Material 3 Compliance**: Use standard `MaterialTheme.colorScheme` and `MaterialTheme.typography`. Support dynamic color and dark/light mode seamlessly.
- **Accessibility & Touch**: Ensure all interactive elements have minimum touch targets of `48dp x 48dp`. Provide explicit `contentDescription` for non-text components.
- **Dynamic Layouts**: Avoid hardcoded pixel dimensions. Use flexible Compose layout modifiers (`Modifier.weight()`, `fillMaxWidth()`, window size classes) to handle diverse screen aspect ratios.
- **Performance & Polish**: Target stable 60/120 FPS frame rates. Keep composable functions lightweight, hoisting state appropriately to avoid unnecessary recompositions.
- **Tactile Feedback**: Provide consistent, subtle haptic feedback (`LocalHapticFeedback.current`) for critical user confirmation actions (e.g., successful payment entry).

## Verification Steps
1. Verify Compose layout rendering across multiple screen configurations (phone, fold, tablet).
2. Validate accessibility contrast ratio (min 4.5:1) and TalkBack navigation semantics.
3. Test dark theme toggle and ensure zero color inconsistency across all screens.
