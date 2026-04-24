/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * This file is part of piko.
 *
 * Any modifications, derivatives, or substantial rewrites of this file
 * must retain this copyright notice and the piko attribution
 * in the source code and version control history.
 */

package app.morphe.extension.instagram.patches.story;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.utils.Pref;

/**
 * Shrinks the home-feed story tray.
 *
 * Triggered by {@code GradientSpinner.onDraw}. Walks up the view tree to
 * the {@code LithoView} whose parent is the home feed RV
 * ({@code MainFeedRecyclerView}); that LithoView holds the entire story
 * tray row.
 *
 * The tray cells are Litho components — their sizes are computed by
 * Litho's own layout pipeline, so modifying child Views' lp.width has
 * no effect. To get TIGHTER packing (more cells per row), expand the
 * tray's lp.width by 1/scale so Litho lays out more cells in the wider
 * space, then {@code setScaleX/Y(scale)} renders the wider tray back to
 * the original visible width — net effect: same screen footprint, more
 * cells per row, smaller rings/avatars/labels.
 *
 * lp.height is intentionally left alone: shrinking it would clip the
 * cell labels because Litho lays out content for the original height.
 *
 * Scale: SharedPreferences key {@code "story_ring_scale"},
 * values {@code "0.5"}..{@code "1.0"}; {@code "1.0"} is a no-op.
 */
public class StoryRingScale {
    private static final String FEED_RV_CLASS_HINT = "MainFeedRecyclerView";
    private static final int MAX_PARENT_WALK = 16;

    /** Captured natural rendered {width, height} per tray view; WeakHashMap so views GC freely. */
    private static final Map<View, int[]> ORIGINAL_SIZES =
            Collections.synchronizedMap(new WeakHashMap<View, int[]>());

    /** Bytecode patch entry point — called from GradientSpinner.onDraw. */
    public static void onSeenStateMeasured(View gradientSpinner) {
        float scale = resolveScale();
        if (scale >= 1.0f && ORIGINAL_SIZES.isEmpty()) return;

        View tray = findTrayContainer(gradientSpinner);
        if (tray == null) return;

        applyScale(tray, scale);
    }

    /**
     * Walks up from {@code v} until the parent is the home-feed RV. Returns
     * that immediate child — the LithoView holding the entire story tray row.
     */
    private static View findTrayContainer(View v) {
        ViewParent p = v.getParent();
        for (int i = 0; i < MAX_PARENT_WALK && p instanceof View; i++) {
            View pv = (View) p;
            ViewParent pp = pv.getParent();
            if (pp != null && pp.getClass().getName().contains(FEED_RV_CLASS_HINT)) {
                return pv;
            }
            p = pp;
        }
        return null;
    }

    private static void applyScale(final View tray, final float scale) {
        ViewGroup.LayoutParams lp = tray.getLayoutParams();
        if (lp == null) return;

        int[] orig = ORIGINAL_SIZES.get(tray);
        if (orig == null) {
            if (scale >= 1.0f) return;
            // Use rendered size — lp.width/height are often MATCH_PARENT or
            // WRAP_CONTENT for Litho-driven trays.
            int rw = tray.getWidth();
            int rh = tray.getHeight();
            if (rw <= 0 || rh <= 0) return; // not measured yet — retry next frame
            orig = new int[] { rw, rh };
            ORIGINAL_SIZES.put(tray, orig);
            attachReapplyGuard(tray);
        }

        // Expand layout width by 1/scale so Litho lays out MORE cells.
        // setScaleX(scale) then renders the wider tray back to the
        // original visible width — same screen footprint, more cells
        // packed in. Leave lp.height alone so labels don't clip.
        final int targetW = Math.round(orig[0] / scale);

        boolean needsLayoutChange = (lp.width != targetW);
        boolean needsScaleChange = (tray.getScaleX() != scale || tray.getScaleY() != scale);

        if (!needsLayoutChange && !needsScaleChange) return;

        // Pivot at top-left so the scaled-down render starts at (0, 0)
        // within the row's allocated space.
        tray.setPivotX(0f);
        tray.setPivotY(0f);
        tray.setScaleX(scale);
        tray.setScaleY(scale);

        if (needsLayoutChange) {
            tray.post(new Runnable() {
                @Override
                public void run() {
                    ViewGroup.LayoutParams cur = tray.getLayoutParams();
                    if (cur == null) return;
                    cur.width = targetW;
                    tray.setLayoutParams(cur);
                }
            });
        }
    }

    /** Re-applies if Litho/the RV resets the tray's lp.width or scale. */
    private static void attachReapplyGuard(final View tray) {
        tray.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int[] orig = ORIGINAL_SIZES.get(view);
                if (orig == null) return;
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp == null) return;
                float scale = resolveScale();
                int targetW = Math.round(orig[0] / scale);
                if (lp.width != targetW) {
                    lp.width = targetW;
                    view.setLayoutParams(lp);
                }
                if (view.getScaleX() != scale) view.setScaleX(scale);
                if (view.getScaleY() != scale) view.setScaleY(scale);
            }
        });
    }

    private static float resolveScale() {
        String raw = Pref.storyRingScale();
        if (raw == null) return 1.0f;
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }
}
