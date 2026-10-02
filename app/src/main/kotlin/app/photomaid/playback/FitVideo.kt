package app.photomaid.playback

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW

/**
 * Video immer im eigenen Seitenverhältnis einpassen – nie strecken oder stauchen (Grundsatz: „entweder es passt oder
 * es passt nicht“). Die Fläche darf größer sein; der Rest bleibt leer. Gilt für Feed, Betrachter, Diashow und Editor.
 */
@OptIn(UnstableApi::class)
@Composable
fun FitVideo(player: Player, modifier: Modifier = Modifier, surfaceType: Int = SURFACE_TYPE_SURFACE_VIEW) {
    ContentFrame(player = player, modifier = modifier, surfaceType = surfaceType, contentScale = ContentScale.Fit, keepContentOnReset = true)
}
