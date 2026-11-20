package app.sucareto.hidora.profile.keyboardmouse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.sucareto.hidora.R
import app.sucareto.hidora.profile.keyboardmouse.MouseSpeed
import app.sucareto.hidora.ui.components.PressHoldButton
import app.sucareto.hidora.ui.components.hapticClick
import app.sucareto.hidora.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val SCROLL_REPEAT_START_MS = 350L
private const val SCROLL_REPEAT_INTERVAL_MS = 100L

@Composable
private fun ScrollHoldButton(
    label: String,
    modifier: Modifier = Modifier,
    haptics: Boolean,
    onScroll: () -> Unit
) {
    var held by remember { mutableStateOf(false) }
    val scroll by rememberUpdatedState(onScroll)

    // A tap sends one tick. Repetition is cancelled on release or disposal.
    LaunchedEffect(held) {
        if (held) {
            delay(SCROLL_REPEAT_START_MS)
            while (isActive) {
                scroll()
                delay(SCROLL_REPEAT_INTERVAL_MS)
            }
        }
    }

    PressHoldButton(
        label = label,
        modifier = modifier,
        functionKey = true,
        haptic = haptics,
        onPress = { scroll(); held = true },
        onRelease = { held = false }
    )
}

@Composable
fun MouseButtonColumn(
    modifier: Modifier = Modifier,
    speed: MouseSpeed,
    haptics: Boolean,
    onSpeedCycle: () -> Unit,
    onSwapHand: () -> Unit,
    onSwitchToKeyboard: () -> Unit,
    onScrollVertical: (delta: Int) -> Unit,
    onScrollHorizontal: (delta: Int) -> Unit,
    onLeftButtonChange: (pressed: Boolean) -> Unit,
    onMiddleButtonChange: (pressed: Boolean) -> Unit,
    onRightButtonChange: (pressed: Boolean) -> Unit
) {
    Column(
        modifier = modifier.fillMaxHeight().padding(Dimens.ColumnPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ButtonSpacing)
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PressHoldButton(
                label = stringResource(R.string.mouse_button_left),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                haptic = haptics,
                onPress = { onLeftButtonChange(true) },
                onRelease = { onLeftButtonChange(false) }
            )
            PressHoldButton(
                label = stringResource(R.string.mouse_button_middle),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                haptic = haptics,
                onPress = { onMiddleButtonChange(true) },
                onRelease = { onMiddleButtonChange(false) }
            )
            PressHoldButton(
                label = stringResource(R.string.mouse_button_right),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                haptic = haptics,
                onPress = { onRightButtonChange(true) },
                onRelease = { onRightButtonChange(false) }
            )
        }

        Column(modifier = Modifier.weight(2.25f).fillMaxWidth()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ScrollHoldButton(
                    label = stringResource(R.string.mouse_scroll_up),
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                    haptics = haptics,
                    onScroll = { onScrollVertical(1) }
                )
                Spacer(Modifier.weight(1f))
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ScrollHoldButton(
                    label = stringResource(R.string.mouse_scroll_left),
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                    haptics = haptics,
                    onScroll = { onScrollHorizontal(-1) }
                )
                Spacer(Modifier.weight(1f))
                ScrollHoldButton(
                    label = stringResource(R.string.mouse_scroll_right),
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                    haptics = haptics,
                    onScroll = { onScrollHorizontal(1) }
                )
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ScrollHoldButton(
                    label = stringResource(R.string.mouse_scroll_down),
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                    haptics = haptics,
                    onScroll = { onScrollVertical(-1) }
                )
                Spacer(Modifier.weight(1f))
            }
        }

        Row(modifier = Modifier.weight(0.75f).fillMaxWidth()) {
            FilledTonalButton(
                onClick = hapticClick(haptics, onSpeedCycle),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                contentPadding = PaddingValues(horizontal = Dimens.ButtonSpacing)
            ) {
                Text(stringResource(speed.labelRes), maxLines = 1, fontSize = Dimens.KeyFontSize)
            }
            FilledTonalButton(
                onClick = hapticClick(haptics, onSwapHand),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                contentPadding = PaddingValues(horizontal = Dimens.ButtonSpacing)
            ) {
                Text(stringResource(R.string.mouse_swap_hand), maxLines = 1, fontSize = Dimens.KeyFontSize)
            }
            FilledTonalButton(
                onClick = hapticClick(haptics, onSwitchToKeyboard),
                modifier = Modifier.weight(1f).fillMaxHeight().padding(Dimens.ButtonSpacing),
                contentPadding = PaddingValues(horizontal = Dimens.ButtonSpacing)
            ) {
                Text(stringResource(R.string.mouse_switch_keyboard), maxLines = 1, fontSize = Dimens.KeyFontSize)
            }
        }
    }
}
