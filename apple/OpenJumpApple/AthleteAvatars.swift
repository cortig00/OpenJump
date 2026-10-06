import SwiftUI

// Avatar illustrations are reused from this repository’s public Android catalog.
struct AthleteAvatarOption: Identifiable, Hashable {
    let key: String
    var id: String { key }
}
struct AthleteAvatarGroup: Identifiable {
    let id: String
    let options: [AthleteAvatarOption]
}
enum AthleteAvatarCatalog {
    static let groups: [AthleteAvatarGroup] = [
        AthleteAvatarGroup(id: "openjump", options: [
            AthleteAvatarOption(key: "avatar_frog_jump"),
            AthleteAvatarOption(key: "avatar_frog_hero"),
            AthleteAvatarOption(key: "avatar_frog_ninja"),
            AthleteAvatarOption(key: "avatar_frog_mech"),
            AthleteAvatarOption(key: "avatar_frog_sentai"),
            AthleteAvatarOption(key: "avatar_frog_wizard"),
            AthleteAvatarOption(key: "avatar_frog_playful"),
            AthleteAvatarOption(key: "avatar_frog_junior"),
            AthleteAvatarOption(key: "avatar_frog_master"),
            AthleteAvatarOption(key: "avatar_frog_coach"),
            AthleteAvatarOption(key: "avatar_frog_analyst"),
        ]),
        AthleteAvatarGroup(id: "athletes", options: [
            AthleteAvatarOption(key: "avatar_jumper"),
            AthleteAvatarOption(key: "avatar_sprinter"),
            AthleteAvatarOption(key: "avatar_weightlifter"),
            AthleteAvatarOption(key: "avatar_para_athlete"),
            AthleteAvatarOption(key: "avatar_junior_athlete"),
            AthleteAvatarOption(key: "avatar_master_athlete"),
            AthleteAvatarOption(key: "avatar_master_male"),
            AthleteAvatarOption(key: "avatar_explosive_jumper"),
            AthleteAvatarOption(key: "avatar_single_leg_jumper"),
            AthleteAvatarOption(key: "avatar_drop_jumper"),
        ]),
        AthleteAvatarGroup(id: "sports", options: [
            AthleteAvatarOption(key: "avatar_volleyball"),
            AthleteAvatarOption(key: "avatar_basketball"),
            AthleteAvatarOption(key: "avatar_tennis"),
            AthleteAvatarOption(key: "avatar_goalkeeper"),
            AthleteAvatarOption(key: "avatar_boxer"),
            AthleteAvatarOption(key: "avatar_swimmer"),
            AthleteAvatarOption(key: "avatar_mobility"),
        ]),
        AthleteAvatarGroup(id: "team", options: [
            AthleteAvatarOption(key: "avatar_female_coach"),
            AthleteAvatarOption(key: "avatar_male_coach"),
            AthleteAvatarOption(key: "avatar_analyst"),
            AthleteAvatarOption(key: "avatar_scientist"),
            AthleteAvatarOption(key: "avatar_biomechanics_mentor"),
            AthleteAvatarOption(key: "avatar_biomechanics_coach"),
        ]),
        AthleteAvatarGroup(id: "equipment", options: [
            AthleteAvatarOption(key: "avatar_kettlebell"),
            AthleteAvatarOption(key: "avatar_barbell"),
            AthleteAvatarOption(key: "avatar_stopwatch"),
            AthleteAvatarOption(key: "avatar_running_shoe"),
            AthleteAvatarOption(key: "avatar_medal"),
            AthleteAvatarOption(key: "avatar_lightning"),
            AthleteAvatarOption(key: "avatar_action_camera"),
            AthleteAvatarOption(key: "avatar_rotary_encoder"),
            AthleteAvatarOption(key: "avatar_measuring_ruler"),
            AthleteAvatarOption(key: "avatar_progress_chart"),
            AthleteAvatarOption(key: "avatar_record_trophy"),
        ]),
        AthleteAvatarGroup(id: "heroes", options: [
            AthleteAvatarOption(key: "avatar_jump_hero"),
            AthleteAvatarOption(key: "avatar_speed_hero"),
            AthleteAvatarOption(key: "avatar_strength_hero"),
            AthleteAvatarOption(key: "avatar_athlete_robot"),
            AthleteAvatarOption(key: "avatar_shonen_runner"),
            AthleteAvatarOption(key: "avatar_lightning_runner"),
            AthleteAvatarOption(key: "avatar_jump_samurai"),
            AthleteAvatarOption(key: "avatar_sprint_ninja"),
            AthleteAvatarOption(key: "avatar_star_guardian"),
            AthleteAvatarOption(key: "avatar_mech_pilot"),
            AthleteAvatarOption(key: "avatar_record_captain"),
            AthleteAvatarOption(key: "avatar_cosmic_warrior"),
            AthleteAvatarOption(key: "avatar_turbo_explorer"),
            AthleteAvatarOption(key: "avatar_ramen_hero"),
            AthleteAvatarOption(key: "avatar_sleepy_hero"),
            AthleteAvatarOption(key: "avatar_gamer_hero"),
            AthleteAvatarOption(key: "avatar_friendly_robot"),
            AthleteAvatarOption(key: "avatar_cute_villain"),
            AthleteAvatarOption(key: "avatar_mech_guardian"),
        ]),
        AthleteAvatarGroup(id: "culture", options: [
            AthleteAvatarOption(key: "avatar_matador"),
            AthleteAvatarOption(key: "avatar_flamenco_dancer"),
            AthleteAvatarOption(key: "avatar_wandering_knight"),
            AthleteAvatarOption(key: "avatar_running_bull"),
            AthleteAvatarOption(key: "avatar_spanish_guitarist"),
            AthleteAvatarOption(key: "avatar_baguette_runner"),
            AthleteAvatarOption(key: "avatar_tricolor_muse"),
            AthleteAvatarOption(key: "avatar_running_rooster"),
            AthleteAvatarOption(key: "avatar_musketeer"),
            AthleteAvatarOption(key: "avatar_tour_cyclist"),
            AthleteAvatarOption(key: "avatar_fisher"),
            AthleteAvatarOption(key: "avatar_fado_singer"),
            AthleteAvatarOption(key: "avatar_navigator"),
            AthleteAvatarOption(key: "avatar_city_tram"),
            AthleteAvatarOption(key: "avatar_surfer"),
            AthleteAvatarOption(key: "avatar_roman_warrior"),
            AthleteAvatarOption(key: "avatar_pizza_chef"),
            AthleteAvatarOption(key: "avatar_gondolier"),
            AthleteAvatarOption(key: "avatar_scooter_rider"),
            AthleteAvatarOption(key: "avatar_sculptor"),
        ]),
    ]
    static let options = groups.flatMap(\.options)
    private static let byKey = Dictionary(uniqueKeysWithValues: options.map { ($0.key, $0) })
    private static let retired: [String: String] = [
        "avatar_gymnast": "avatar_mobility",
        "avatar_cyclist": "avatar_sprinter",
        "avatar_climber": "avatar_mobility",
        "avatar_junior_runner": "avatar_sprinter",
        "avatar_water_bottle": "avatar_kettlebell",
        "avatar_resistance_band": "avatar_barbell",
        "avatar_cone": "avatar_stopwatch",
        "avatar_balance_hero": "avatar_jump_hero",
        "avatar_recovery_hero": "avatar_strength_hero",
        "avatar_explosive_athlete": "avatar_jumper",
        "avatar_rocket": "avatar_lightning",
        "avatar_biomech_guardian": "avatar_athlete_robot",
    ]
    private static let sheet02 = "junior_athlete junior_runner junior_athlete junior_runner sprinter jumper explosive_athlete sprinter explosive_athlete weightlifter boxer junior_athlete mobility junior_athlete junior_runner explosive_athlete junior_athlete mobility junior_athlete weightlifter swimmer junior_runner cyclist junior_athlete jumper".split(separator: " ").map(String.init)
    private static let sheet03 = "junior_athlete junior_runner junior_athlete explosive_athlete sprinter sprinter junior_runner junior_athlete junior_runner weightlifter junior_runner jumper junior_runner sprinter junior_runner junior_athlete junior_runner junior_athlete junior_runner junior_athlete jumper junior_runner junior_athlete junior_runner junior_athlete".split(separator: " ").map(String.init)
    private static let legacy: [String: String] = {
        var result = ["avatar_img01_r2_c2": "avatar_swimmer", "avatar_img05_r5_c4": "avatar_swimmer"]
        for (sheet, targets) in [("02", sheet02), ("03", sheet03)] {
            for (index, target) in targets.enumerated() { result["avatar_img\(sheet)_r\(index / 5 + 1)_c\(index % 5 + 1)"] = "avatar_\(target)" }
        }
        return result
    }()
    static func resolve(_ key: String?) -> AthleteAvatarOption? {
        guard let key else { return nil }
        let mapped = legacy[key] ?? key
        return byKey[retired[mapped] ?? mapped]
    }
}
