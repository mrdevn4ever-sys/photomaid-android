package app.photomaid.feature.editor

import app.photomaid.R
import app.photomaid.core.edit.Adjustments

/** Reglergruppen im Reiter „Anpassen“ (zuerst die häufigsten, feinere unter „Detail“ und „Effekte“). */
enum class AdjustGroup(val label: Int) {
    LIGHT(R.string.edit_grp_light), COLOR(R.string.edit_grp_color), MIX(R.string.edit_grp_mix), DETAIL(R.string.edit_grp_detail), EFFECTS(R.string.edit_grp_effects)
}

/** Ein Regler des Farbmischers (je Farbbereich). */
enum class MixField(val label: Int) { HUE(R.string.edit_mix_hue), SATURATION(R.string.edit_mix_saturation), LUMINANCE(R.string.edit_mix_luminance) }

/** Werkzeuge im Reiter „Retusche“: Bereiche (Masken) aufhellen/abdunkeln/färben und Rote Augen. */
enum class RetouchTool(val label: Int) { RADIAL(R.string.ret_radial), LINEAR(R.string.ret_linear), BRUSH(R.string.ret_brush), RED_EYE(R.string.ret_red_eye) }

/** Ein Regler: Gruppe, Bereich und Abbildung auf [Adjustments]. */
enum class AdjustTool(val group: AdjustGroup, val label: Int, val min: Float = -100f, val max: Float = 100f, val advanced: Boolean = false) {
    EXPOSURE(AdjustGroup.LIGHT, R.string.edit_adj_exposure),
    BRIGHTNESS(AdjustGroup.LIGHT, R.string.edit_adj_brightness),
    CONTRAST(AdjustGroup.LIGHT, R.string.edit_adj_contrast),
    HIGHLIGHTS(AdjustGroup.LIGHT, R.string.edit_adj_highlights),
    SHADOWS(AdjustGroup.LIGHT, R.string.edit_adj_shadows),
    WHITES(AdjustGroup.LIGHT, R.string.edit_adj_whites),
    BLACKS(AdjustGroup.LIGHT, R.string.edit_adj_blacks),
    WARMTH(AdjustGroup.COLOR, R.string.edit_adj_warmth),
    TINT(AdjustGroup.COLOR, R.string.edit_adj_tint),
    SATURATION(AdjustGroup.COLOR, R.string.edit_adj_saturation),
    VIBRANCE(AdjustGroup.COLOR, R.string.edit_adj_vibrance),
    CLARITY(AdjustGroup.DETAIL, R.string.edit_adj_clarity),
    SHARPNESS(AdjustGroup.DETAIL, R.string.edit_adj_sharpness, 0f, 100f),
    SHARPEN_RADIUS(AdjustGroup.DETAIL, R.string.edit_adj_sharpen_radius, 0f, 100f, advanced = true),
    SHARPEN_THRESHOLD(AdjustGroup.DETAIL, R.string.edit_adj_sharpen_threshold, 0f, 100f, advanced = true),
    VIGNETTE(AdjustGroup.EFFECTS, R.string.edit_adj_vignette),
    GRAIN(AdjustGroup.EFFECTS, R.string.edit_adj_grain, 0f, 100f);

    val bipolar: Boolean get() = min < 0f

    fun get(a: Adjustments): Float = when (this) {
        EXPOSURE -> a.exposure; BRIGHTNESS -> a.brightness; CONTRAST -> a.contrast; HIGHLIGHTS -> a.highlights; SHADOWS -> a.shadows
        WHITES -> a.whites; BLACKS -> a.blacks; WARMTH -> a.warmth; TINT -> a.tint; SATURATION -> a.saturation; VIBRANCE -> a.vibrance
        CLARITY -> a.clarity; SHARPNESS -> a.sharpness; SHARPEN_RADIUS -> a.sharpenRadius; SHARPEN_THRESHOLD -> a.sharpenThreshold
        VIGNETTE -> a.vignette; GRAIN -> a.grain
    }

    fun set(a: Adjustments, raw: Float): Adjustments {
        val v = raw.coerceIn(min, max)
        return when (this) {
            EXPOSURE -> a.copy(exposure = v); BRIGHTNESS -> a.copy(brightness = v); CONTRAST -> a.copy(contrast = v)
            HIGHLIGHTS -> a.copy(highlights = v); SHADOWS -> a.copy(shadows = v); WHITES -> a.copy(whites = v); BLACKS -> a.copy(blacks = v)
            WARMTH -> a.copy(warmth = v); TINT -> a.copy(tint = v); SATURATION -> a.copy(saturation = v); VIBRANCE -> a.copy(vibrance = v)
            CLARITY -> a.copy(clarity = v); SHARPNESS -> a.copy(sharpness = v); SHARPEN_RADIUS -> a.copy(sharpenRadius = v)
            SHARPEN_THRESHOLD -> a.copy(sharpenThreshold = v); VIGNETTE -> a.copy(vignette = v); GRAIN -> a.copy(grain = v)
        }
    }

    /** Regler, die lokal (mit Maske) wirken können – nur punktweise. */
    val local: Boolean get() = group == AdjustGroup.LIGHT || group == AdjustGroup.COLOR

    companion object {
        fun of(g: AdjustGroup) = entries.filter { it.group == g }
    }
}
