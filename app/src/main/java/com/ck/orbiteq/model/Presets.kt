package com.ck.orbiteq.model

enum class ProfileKind { SOUND, HEADPHONE, CUSTOM }

class Profile(val name: String, val settings: EqSettings, val kind: ProfileKind)

object Presets {

    private fun eq(vararg db: Int, bass: Float = 0f, spatial: Float = 0f): EqSettings =
        EqSettings(
            gains = FloatArray(EqSettings.BANDS) { i -> db.getOrElse(i) { 0 }.toFloat() },
            bass = bass,
            spatial = spatial,
        )

    private fun sound(name: String, s: EqSettings) = Profile(name, s, ProfileKind.SOUND)
    private fun phone(name: String, s: EqSettings) = Profile(name, s, ProfileKind.HEADPHONE)

    /** Genre presets — these change only the EQ curve. */
    val sound: List<Profile> = listOf(
        sound("Flat", eq(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        sound("Bass", eq(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)),
        sound("Treble", eq(0, 0, 0, 0, 0, 1, 3, 5, 6, 6)),
        sound("Vocal", eq(-2, -2, -1, 1, 3, 4, 4, 3, 1, 0)),
        sound("Rock", eq(5, 4, 2, -1, -2, -1, 2, 4, 5, 5)),
        sound("Pop", eq(-1, 1, 3, 4, 3, 1, -1, -1, 0, 1)),
        sound("EDM", eq(6, 5, 2, 0, -1, 1, 2, 3, 4, 4)),
        sound("Hip-Hop", eq(6, 5, 3, 1, -1, -1, 1, 1, 2, 3)),
        sound("Bollywood", eq(4, 3, 1, 0, 1, 2, 3, 3, 2, 2)),
        sound("Classical", eq(4, 3, 2, 1, -1, -1, 0, 2, 3, 4)),
        sound("Podcast", eq(-4, -3, -1, 1, 3, 4, 3, 1, -1, -3)),
    )

    /** General starting points by headphone type (not measured curves). */
    val headphones: List<Profile> = listOf(
        phone("Earbuds (generic)", eq(4, 3, 1, 0, -1, 0, 1, 2, 1, -1, bass = 0.15f)),
        phone("Budget TWS", eq(2, 1, 0, -1, -1, 0, 2, 3, 2, 0)),
        phone("Over-ear closed", eq(1, 1, 0, -1, -1, 0, 1, 2, 2, 1)),
        phone("Open-back", eq(5, 4, 2, 0, 0, 0, 0, -1, -1, 0)),
        phone("Neckband", eq(3, 2, 1, 0, 0, 1, 2, 2, 1, 0, bass = 0.1f)),
        phone("Gaming headset", eq(2, 1, -1, -2, 0, 2, 4, 4, 2, 0, spatial = 0.5f)),
    )
}
