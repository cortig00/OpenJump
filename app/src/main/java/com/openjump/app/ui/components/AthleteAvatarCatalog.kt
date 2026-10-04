package com.openjump.app.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.openjump.app.R
import com.openjump.app.data.DEFAULT_ATHLETE_AVATAR_KEY

data class AthleteAvatarOption(
    val key: String,
    @DrawableRes val drawableRes: Int,
)

data class AthleteAvatarGroup(
    @StringRes val titleRes: Int,
    val options: List<AthleteAvatarOption>,
)

/** Stable Room/backup keys. The picker is curated; retired keys still display a close match. */
object AthleteAvatarCatalog {
    val groups: List<AthleteAvatarGroup> = listOf(
        AthleteAvatarGroup(R.string.athlete_avatar_group_openjump, listOf(
            AthleteAvatarOption(DEFAULT_ATHLETE_AVATAR_KEY, R.drawable.avatar_frog_jump),
            AthleteAvatarOption("avatar_frog_hero", R.drawable.avatar_frog_hero),
            AthleteAvatarOption("avatar_frog_ninja", R.drawable.avatar_frog_ninja),
            AthleteAvatarOption("avatar_frog_mech", R.drawable.avatar_frog_mech),
            AthleteAvatarOption("avatar_frog_sentai", R.drawable.avatar_frog_sentai),
            AthleteAvatarOption("avatar_frog_wizard", R.drawable.avatar_frog_wizard),
            AthleteAvatarOption("avatar_frog_playful", R.drawable.avatar_frog_playful),
            AthleteAvatarOption("avatar_frog_junior", R.drawable.avatar_frog_junior),
            AthleteAvatarOption("avatar_frog_master", R.drawable.avatar_frog_master),
            AthleteAvatarOption("avatar_frog_coach", R.drawable.avatar_frog_coach),
            AthleteAvatarOption("avatar_frog_analyst", R.drawable.avatar_frog_analyst),
        )),
        AthleteAvatarGroup(R.string.athlete_avatar_group_athletes, listOf(
            AthleteAvatarOption("avatar_jumper", R.drawable.avatar_jumper),
            AthleteAvatarOption("avatar_sprinter", R.drawable.avatar_sprinter),
            AthleteAvatarOption("avatar_weightlifter", R.drawable.avatar_weightlifter),
            AthleteAvatarOption("avatar_para_athlete", R.drawable.avatar_para_athlete),
            AthleteAvatarOption("avatar_junior_athlete", R.drawable.avatar_junior_athlete),
            AthleteAvatarOption("avatar_master_athlete", R.drawable.avatar_master_athlete),
            AthleteAvatarOption("avatar_master_male", R.drawable.avatar_master_male),
            AthleteAvatarOption("avatar_explosive_jumper", R.drawable.avatar_explosive_jumper),
            AthleteAvatarOption("avatar_single_leg_jumper", R.drawable.avatar_single_leg_jumper),
            AthleteAvatarOption("avatar_drop_jumper", R.drawable.avatar_drop_jumper),
        )),
        AthleteAvatarGroup(R.string.athlete_avatar_group_sports, listOf(
            AthleteAvatarOption("avatar_volleyball", R.drawable.avatar_volleyball),
            AthleteAvatarOption("avatar_basketball", R.drawable.avatar_basketball),
            AthleteAvatarOption("avatar_tennis", R.drawable.avatar_tennis),
            AthleteAvatarOption("avatar_goalkeeper", R.drawable.avatar_goalkeeper),
            AthleteAvatarOption("avatar_boxer", R.drawable.avatar_boxer),
            AthleteAvatarOption("avatar_swimmer", R.drawable.avatar_swimmer),
            AthleteAvatarOption("avatar_mobility", R.drawable.avatar_mobility),
        )),
        AthleteAvatarGroup(R.string.athlete_avatar_group_team, listOf(
            AthleteAvatarOption("avatar_female_coach", R.drawable.avatar_female_coach),
            AthleteAvatarOption("avatar_male_coach", R.drawable.avatar_male_coach),
            AthleteAvatarOption("avatar_analyst", R.drawable.avatar_analyst),
            AthleteAvatarOption("avatar_scientist", R.drawable.avatar_scientist),
            AthleteAvatarOption("avatar_biomechanics_mentor", R.drawable.avatar_biomechanics_mentor),
            AthleteAvatarOption("avatar_biomechanics_coach", R.drawable.avatar_biomechanics_coach),
        )),
        AthleteAvatarGroup(R.string.athlete_avatar_group_equipment, listOf(
            AthleteAvatarOption("avatar_kettlebell", R.drawable.avatar_kettlebell),
            AthleteAvatarOption("avatar_barbell", R.drawable.avatar_barbell),
            AthleteAvatarOption("avatar_stopwatch", R.drawable.avatar_stopwatch),
            AthleteAvatarOption("avatar_running_shoe", R.drawable.avatar_running_shoe),
            AthleteAvatarOption("avatar_medal", R.drawable.avatar_medal),
            AthleteAvatarOption("avatar_lightning", R.drawable.avatar_lightning),
            AthleteAvatarOption("avatar_action_camera", R.drawable.avatar_action_camera),
            AthleteAvatarOption("avatar_rotary_encoder", R.drawable.avatar_rotary_encoder),
            AthleteAvatarOption("avatar_measuring_ruler", R.drawable.avatar_measuring_ruler),
            AthleteAvatarOption("avatar_progress_chart", R.drawable.avatar_progress_chart),
            AthleteAvatarOption("avatar_record_trophy", R.drawable.avatar_record_trophy),
        )),
        AthleteAvatarGroup(R.string.athlete_avatar_group_heroes, listOf(
            AthleteAvatarOption("avatar_jump_hero", R.drawable.avatar_jump_hero),
            AthleteAvatarOption("avatar_speed_hero", R.drawable.avatar_speed_hero),
            AthleteAvatarOption("avatar_strength_hero", R.drawable.avatar_strength_hero),
            AthleteAvatarOption("avatar_athlete_robot", R.drawable.avatar_athlete_robot),
            AthleteAvatarOption("avatar_shonen_runner", R.drawable.avatar_shonen_runner),
            AthleteAvatarOption("avatar_lightning_runner", R.drawable.avatar_lightning_runner),
            AthleteAvatarOption("avatar_jump_samurai", R.drawable.avatar_jump_samurai),
            AthleteAvatarOption("avatar_sprint_ninja", R.drawable.avatar_sprint_ninja),
            AthleteAvatarOption("avatar_star_guardian", R.drawable.avatar_star_guardian),
            AthleteAvatarOption("avatar_mech_pilot", R.drawable.avatar_mech_pilot),
            AthleteAvatarOption("avatar_record_captain", R.drawable.avatar_record_captain),
            AthleteAvatarOption("avatar_cosmic_warrior", R.drawable.avatar_cosmic_warrior),
            AthleteAvatarOption("avatar_turbo_explorer", R.drawable.avatar_turbo_explorer),
            AthleteAvatarOption("avatar_ramen_hero", R.drawable.avatar_ramen_hero),
            AthleteAvatarOption("avatar_sleepy_hero", R.drawable.avatar_sleepy_hero),
            AthleteAvatarOption("avatar_gamer_hero", R.drawable.avatar_gamer_hero),
            AthleteAvatarOption("avatar_friendly_robot", R.drawable.avatar_friendly_robot),
            AthleteAvatarOption("avatar_cute_villain", R.drawable.avatar_cute_villain),
            AthleteAvatarOption("avatar_mech_guardian", R.drawable.avatar_mech_guardian),
        )),
        AthleteAvatarGroup(R.string.athlete_avatar_group_culture, listOf(
            AthleteAvatarOption("avatar_matador", R.drawable.avatar_matador),
            AthleteAvatarOption("avatar_flamenco_dancer", R.drawable.avatar_flamenco_dancer),
            AthleteAvatarOption("avatar_wandering_knight", R.drawable.avatar_wandering_knight),
            AthleteAvatarOption("avatar_running_bull", R.drawable.avatar_running_bull),
            AthleteAvatarOption("avatar_spanish_guitarist", R.drawable.avatar_spanish_guitarist),
            AthleteAvatarOption("avatar_baguette_runner", R.drawable.avatar_baguette_runner),
            AthleteAvatarOption("avatar_tricolor_muse", R.drawable.avatar_tricolor_muse),
            AthleteAvatarOption("avatar_running_rooster", R.drawable.avatar_running_rooster),
            AthleteAvatarOption("avatar_musketeer", R.drawable.avatar_musketeer),
            AthleteAvatarOption("avatar_tour_cyclist", R.drawable.avatar_tour_cyclist),
            AthleteAvatarOption("avatar_fisher", R.drawable.avatar_fisher),
            AthleteAvatarOption("avatar_fado_singer", R.drawable.avatar_fado_singer),
            AthleteAvatarOption("avatar_navigator", R.drawable.avatar_navigator),
            AthleteAvatarOption("avatar_city_tram", R.drawable.avatar_city_tram),
            AthleteAvatarOption("avatar_surfer", R.drawable.avatar_surfer),
            AthleteAvatarOption("avatar_roman_warrior", R.drawable.avatar_roman_warrior),
            AthleteAvatarOption("avatar_pizza_chef", R.drawable.avatar_pizza_chef),
            AthleteAvatarOption("avatar_gondolier", R.drawable.avatar_gondolier),
            AthleteAvatarOption("avatar_scooter_rider", R.drawable.avatar_scooter_rider),
            AthleteAvatarOption("avatar_sculptor", R.drawable.avatar_sculptor),
        )),
    )
    val options: List<AthleteAvatarOption> = groups.flatMap(AthleteAvatarGroup::options)
    private val byKey = options.associateBy(AthleteAvatarOption::key)

    // No database rewrite: a saved key can still be displayed and edited even when
    // its former drawable is no longer shipped. Backups retain the original key.
    private val retiredKeys = mapOf(
        "avatar_gymnast" to "avatar_mobility",
        "avatar_cyclist" to "avatar_sprinter",
        "avatar_climber" to "avatar_mobility",
        "avatar_junior_runner" to "avatar_sprinter",
        "avatar_water_bottle" to "avatar_kettlebell",
        "avatar_resistance_band" to "avatar_barbell",
        "avatar_cone" to "avatar_stopwatch",
        "avatar_balance_hero" to "avatar_jump_hero",
        "avatar_recovery_hero" to "avatar_strength_hero",
        "avatar_explosive_athlete" to "avatar_jumper",
        "avatar_rocket" to "avatar_lightning",
        "avatar_biomech_guardian" to "avatar_athlete_robot",
    )

    // Older pre-illustration avatars also remain valid in existing profiles/backups.
    private val oldSheet02 = listOf(
        "junior_athlete", "junior_runner", "junior_athlete", "junior_runner", "sprinter",
        "jumper", "explosive_athlete", "sprinter", "explosive_athlete", "weightlifter",
        "boxer", "junior_athlete", "mobility", "junior_athlete", "junior_runner",
        "explosive_athlete", "junior_athlete", "mobility", "junior_athlete", "weightlifter",
        "swimmer", "junior_runner", "cyclist", "junior_athlete", "jumper",
    )
    private val oldSheet03 = listOf(
        "junior_athlete", "junior_runner", "junior_athlete", "explosive_athlete", "sprinter",
        "sprinter", "junior_runner", "junior_athlete", "junior_runner", "weightlifter",
        "junior_runner", "jumper", "junior_runner", "sprinter", "junior_runner",
        "junior_athlete", "junior_runner", "junior_athlete", "junior_runner", "junior_athlete",
        "jumper", "junior_runner", "junior_athlete", "junior_runner", "junior_athlete",
    )
    private val legacyKeys: Map<String, String> = buildMap {
        put("avatar_img01_r2_c2", "avatar_swimmer")
        put("avatar_img05_r5_c4", "avatar_swimmer")
        for ((sheet, targets) in listOf("02" to oldSheet02, "03" to oldSheet03)) {
            targets.forEachIndexed { index, target ->
                put("avatar_img${sheet}_r${index / 5 + 1}_c${index % 5 + 1}", "avatar_$target")
            }
        }
    }

    fun find(key: String?): AthleteAvatarOption? = key?.let {
        val normalized = legacyKeys[it] ?: it
        byKey[retiredKeys[normalized] ?: normalized]
    }
    fun isValid(key: String?): Boolean = key == null || find(key) != null
}
