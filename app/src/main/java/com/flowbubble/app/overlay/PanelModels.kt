package com.flowbubble.app.overlay

data class PanelAction(
    val label: String,
    val primary: Boolean = false,
    val onClick: () -> Unit
)

data class PanelContent(
    val title: String,
    val body: String = "",
    val actions: List<PanelAction> = emptyList(),
    val dismissible: Boolean = true,
    val autoDismissMs: Long? = null,
    val scrollable: Boolean = false,
    val copyText: String? = null,
    val voicePrompt: String? = null,
    val onVoiceApprove: (() -> Unit)? = null,
    val onVoiceDeny: (() -> Unit)? = null
)

interface OverlayUi {
    fun showPanel(content: PanelContent)
    fun closePanel()
    fun hideForScreenRead() {}
    fun restoreAfterScreenRead() {}

    /** Speaks a short status line when hands-free mode is active. Silent otherwise. */
    fun announce(text: String) {}
}
