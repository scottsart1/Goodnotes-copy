package com.inkwell.notes.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sports
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Folder appearance: the icon set (stored by name in [com.inkwell.notes.core.model.Folder.icon])
 * and the colour swatches (stored as ARGB, 0 = default). Unknown icon names fall back to the
 * plain folder so data from other versions always renders.
 */
object FolderStyle {
    const val DEFAULT_ICON = "folder"

    /** Icon names in picker order (Material Symbols names). */
    val iconNames: List<String> = listOf(
        "folder", "school", "work", "science", "calculate", "menu_book", "gavel", "attach_money",
        "trending_up", "biotech", "lightbulb", "palette", "music_note", "sports", "flight", "favorite",
        "home", "groups", "code", "psychology", "public", "edit_note", "inventory", "star",
    )

    fun icon(name: String?): ImageVector = when (name) {
        "school" -> Icons.Outlined.School
        "work" -> Icons.Outlined.Work
        "science" -> Icons.Outlined.Science
        "calculate" -> Icons.Outlined.Calculate
        "menu_book" -> Icons.AutoMirrored.Outlined.MenuBook
        "gavel" -> Icons.Outlined.Gavel
        "attach_money" -> Icons.Outlined.AttachMoney
        "trending_up" -> Icons.AutoMirrored.Outlined.TrendingUp
        "biotech" -> Icons.Outlined.Biotech
        "lightbulb" -> Icons.Outlined.Lightbulb
        "palette" -> Icons.Outlined.Palette
        "music_note" -> Icons.Outlined.MusicNote
        "sports" -> Icons.Outlined.Sports
        "flight" -> Icons.Outlined.Flight
        "favorite" -> Icons.Outlined.FavoriteBorder
        "home" -> Icons.Outlined.Home
        "groups" -> Icons.Outlined.Groups
        "code" -> Icons.Outlined.Code
        "psychology" -> Icons.Outlined.Psychology
        "public" -> Icons.Outlined.Public
        "edit_note" -> Icons.Outlined.EditNote
        "inventory" -> Icons.Outlined.Inventory2
        "star" -> Icons.Outlined.StarOutline
        else -> Icons.Outlined.Folder
    }

    /** True when the folder shows its icon rather than a colour dot in the rail. */
    fun hasCustomIcon(name: String?): Boolean = name != null && name != DEFAULT_ICON && name in iconNames

    /** Spoken name for an icon ("menu_book" -> "Menu book"). */
    fun iconLabel(name: String): String = name.replace('_', ' ').replaceFirstChar { it.titlecase() }

    /**
     * Twelve folder colours, muted enough for both the light and the dark theme. The first
     * entry (0) means "no colour" and renders with the theme's muted ink.
     */
    val swatches: List<Int> = listOf(
        0,
        0xFF2952CC.toInt(), // ink blue
        0xFF1F8A9E.toInt(), // teal
        0xFF2E7D4F.toInt(), // green
        0xFF7A8F2A.toInt(), // olive
        0xFFC28A12.toInt(), // amber
        0xFFD0661F.toInt(), // orange
        0xFFB5483B.toInt(), // brick red
        0xFFC2416E.toInt(), // rose
        0xFF8A4FC7.toInt(), // violet
        0xFF5C6BC0.toInt(), // indigo
        0xFF7D6E5D.toInt(), // taupe
    )

    val swatchNames: List<String> = listOf(
        "Default", "Blue", "Teal", "Green", "Olive", "Amber", "Orange", "Red", "Rose", "Violet", "Indigo", "Taupe",
    )

    /** The colour to draw for [argb], or [fallback] for the default (0). */
    fun color(argb: Int, fallback: Color): Color = if (argb == 0) fallback else Color(argb)
}
