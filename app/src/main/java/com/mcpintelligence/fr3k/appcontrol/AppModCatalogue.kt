package com.mcpintelligence.fr3k.appcontrol

/** Published closed language. Adding an adapter requires a guide and qualification evidence. */
object AppModCatalogue {
    const val version = 1
    val operators = linkedMapOf(
        "resource_string" to "mod.preparation", "resource_color" to "mod.preparation",
        "layout_attribute" to "mod.preparation", "dex_constant" to "mod.preparation",
        "helper_call" to "mod.preparation", "hook_template" to "mod.preparation",
    )
    val helpers = linkedMapOf("url.clean" to "mod.preparation", "preference.persist" to "mod.preparation", "hud.share" to "mod.preparation")
    val tools = linkedMapOf("analyze_apk" to "mod.discovery", "suggest_app_mods" to "mod.discovery",
        "preview_app_mods" to "mod.preparation", "request_app_mod_preparation" to "mod.preparation", "get_app_mod_job" to "mod.install.restore")
    val controls = linkedMapOf("choose_apks" to "mod.discovery", "choose_installed" to "mod.discovery",
        "research_requests" to "mod.discovery", "request_ai_ideas" to "mod.discovery", "search_evidence" to "mod.discovery",
        "select_suggestion" to "mod.preparation", "preview" to "mod.preparation", "approve" to "mod.preparation",
        "cancel" to "mod.preparation", "resume" to "mod.preparation", "install" to "mod.install.restore",
        "verify_installed" to "mod.install.restore", "record_observation" to "mod.install.restore", "restore_original" to "mod.install.restore")
}
