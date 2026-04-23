/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * This file is part of piko.
 *
 * Any modifications, derivatives, or substantial rewrites of this file
 * must retain this copyright notice and the piko attribution
 * in the source code and version control history.
 */

package app.crimera.patches.instagram.misc.storyRingScale

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Hooks `GradientSpinner.onDraw`. The home story tray on modern IG builds
 * uses Facebook Litho — Litho positions child Views via
 * `setLeftTopRightBottom` and bypasses standard `View.onMeasure`, so
 * hooking onMeasure never fires. `onDraw` IS invoked every frame the ring
 * is drawn, so it's the reliable trigger across legacy View and Litho
 * rendering paths.
 *
 * The injection uses `move-object/from16 v2, p0` because `onDraw` has
 * `.registers 36` — `p0` (this) maps to v34, which is out of the 4-bit
 * range used by non-range `invoke-static`.
 */
internal object GradientSpinnerOnMeasureFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/ui/widget/gradientspinner/GradientSpinner;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/graphics/Canvas;"),
    name = "onDraw",
)

@Suppress("unused")
val storyRingScalePatch =
    bytecodePatch(
        name = "Story ring scale",
        description = "Scales the home-feed story tray rings by a user-selected factor.",
    ) {
        dependsOn(settingsPatch)

        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            GradientSpinnerOnMeasureFingerprint.method.apply {
                // Inject at start of onDraw, dispatching to the extension
                // unconditionally. GradientSpinner.onDraw has .registers 36
                // so p0 (this) maps to v34 — out of range for non-range
                // invoke (4-bit fields, max v15). Move p0 to v2 first via
                // move-object/from16, then call from there. The dummy
                // if-eqz on v2 (never null since p0 is `this`) provides
                // the label-bearing instruction the smali compiler needs.
                val firstInstruction = getInstruction(0)
                addInstructionsWithLabels(
                    0,
                    """
                    move-object/from16 v2, p0
                    if-eqz v2, :piko_skip
                    invoke-static {v2}, ${PATCHES_DESCRIPTOR}/story/StoryRingScale;->onSeenStateMeasured(Landroid/view/View;)V
                    """.trimIndent(),
                    ExternalLabel("piko_skip", firstInstruction),
                )

                enableSettings("storyRingScale")
            }
        }
    }
