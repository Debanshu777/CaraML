package com.debanshu777.caraml.core.theme

import com.materialkolor.PaletteStyle

/**
 * App-owned wrapper around materialkolor's [PaletteStyle].
 *
 * The wrapper exists so the persisted/domain layer never references the upstream
 * type directly — if materialkolor renames or removes a value we can map it here
 * instead of migrating every stored preference.
 */
enum class ThemePaletteStyle {
    TONAL_SPOT,
    NEUTRAL,
    VIBRANT,
    EXPRESSIVE,
    CONTENT,
    FIDELITY,
    MONOCHROME,
    RAINBOW,
    FRUIT_SALAD,
}

internal fun ThemePaletteStyle.toMaterialKolor(): PaletteStyle = when (this) {
    ThemePaletteStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    ThemePaletteStyle.NEUTRAL -> PaletteStyle.Neutral
    ThemePaletteStyle.VIBRANT -> PaletteStyle.Vibrant
    ThemePaletteStyle.EXPRESSIVE -> PaletteStyle.Expressive
    ThemePaletteStyle.CONTENT -> PaletteStyle.Content
    ThemePaletteStyle.FIDELITY -> PaletteStyle.Fidelity
    ThemePaletteStyle.MONOCHROME -> PaletteStyle.Monochrome
    ThemePaletteStyle.RAINBOW -> PaletteStyle.Rainbow
    ThemePaletteStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
}
