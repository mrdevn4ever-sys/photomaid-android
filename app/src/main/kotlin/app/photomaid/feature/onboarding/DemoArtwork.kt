package app.photomaid.feature.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.photomaid.R

/**
 * Demo-Motive für Onboarding und Angebot: eigene Fotos des Inhabers (Natur, Hunde; keine erkennbaren Gesichter),
 * verkleinert auf 800 px und ohne Metadaten (kein GPS, keine Kamera-/Datumsangaben) als WebP eingebunden.
 * Nur Illustration – nie mit den Medien der Nutzer vermischt, nie hochgeladen.
 */
object DemoArt {
    /** Stapel (Üben, Angebot): die schönsten Motive zuerst. */
    @DrawableRes val deck = listOf(
        R.drawable.demo_lake, R.drawable.demo_dogs_bench, R.drawable.demo_cow,
        R.drawable.demo_alp, R.drawable.demo_dog_flowers, R.drawable.demo_dog_lap,
    )

    /** Raster der Geschichte (3×3): an den Stellen 1, 5, 6 die „Aussortier“-Kandidaten (dunkel, klein, voll). */
    @DrawableRes val grid = listOf(
        R.drawable.demo_lake, R.drawable.demo_night_beach, R.drawable.demo_cow,
        R.drawable.demo_alp, R.drawable.demo_dogs_bench, R.drawable.demo_dog_ball,
        R.drawable.demo_beach, R.drawable.demo_dog_flowers, R.drawable.demo_dog_lap,
    )

    /** Nach dem Hinausfliegen rücken an diesen Stellen frische Motive nach. */
    @DrawableRes val gridRefill = listOf(R.drawable.demo_dog_flowers, R.drawable.demo_lake, R.drawable.demo_dogs_bench)

    val COUNT: Int get() = deck.size
}

/** Ein Stapel-Motiv (Index läuft endlos über [DemoArt.deck]). */
@Composable
fun DemoArtwork(variant: Int, modifier: Modifier = Modifier) {
    val n = DemoArt.deck.size
    DemoPhoto(DemoArt.deck[((variant % n) + n) % n], modifier)
}

@Composable
fun DemoPhoto(@DrawableRes id: Int, modifier: Modifier = Modifier) {
    Image(painterResource(id), contentDescription = null, modifier = modifier.clearAndSetSemantics {}, contentScale = ContentScale.Crop)
}
