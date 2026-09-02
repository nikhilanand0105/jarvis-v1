package com.jarvispoc.flows

import android.content.Intent
import android.net.Uri
import com.jarvispoc.core.AgentLog
import com.jarvispoc.core.FlowResult
import com.jarvispoc.core.Selector
import com.jarvispoc.core.UiNode
import com.jarvispoc.core.query
import com.jarvispoc.service.ActionExecutor
import kotlinx.coroutines.delay

/**
 * Hand a photo to Instagram's composer, type the caption, share.
 *
 * The photo arrives via ACTION_SEND, which Meta documents as the supported way
 * to push an image into the feed composer. That skips the single most brittle
 * thing we could attempt — identifying one specific thumbnail inside
 * Instagram's media grid — and leaves accessibility automation responsible for
 * only the caption field and the Share button.
 *
 * [imageUri] must be a FileProvider URI owned by this app so the read grant
 * actually transfers to Instagram.
 */
class InstagramPostFlow(
    private val imageUri: Uri,
    private val caption: String,
) : Flow {

    override val name: String = "Instagram post"

    override suspend fun run(x: ActionExecutor, autoConfirm: Boolean): FlowResult {
        if (!x.isInstalled(PACKAGE)) {
            return FlowResult.Failed("launch", "Instagram is not installed")
        }

        // Pre-load the clipboard while we still own the screen. setText() will
        // try again later, but by then Instagram is foreground and some OEM
        // builds refuse clipboard writes from a backgrounded app — so this
        // early attempt is often the one that actually lands.
        if (!x.copyToClipboard(caption)) {
            AgentLog.warn("clipboard pre-load failed — the paste fallback may be unavailable")
        }

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, caption)
            component = android.content.ComponentName(
                PACKAGE,
                "com.instagram.share.handleractivity.ShareHandlerActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!x.startActivity(share)) {
            share.component = null
            share.setPackage(PACKAGE)
            if (!x.startActivity(share)) {
                return FlowResult.Failed("share", "Instagram rejected the share intent")
            }
        }
        delay(2_000)

        // Advance through intermediate screens (Share sheet chooser, Next button on filter screen)
        // until the real editable caption field is reached.
        var captionField: UiNode? = null
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            captionField = x.awaitNode(CAPTION_FIELD, timeoutMs = 2_000)
            if (captionField != null) break

            // 1. Check for Feed / Post option in share chooser
            val feed = x.awaitNode(FEED_OPTION, timeoutMs = 1_000)
            if (feed != null) {
                x.tap(feed)
                delay(600)
                // If "Just once" button appears in system resolver, tap it
                x.awaitNode(JUST_ONCE, timeoutMs = 1_500)?.let { once ->
                    x.tap(once)
                }
                delay(2_000)
                continue
            }

            // 2. Check for "Next" button on media preview / filter screen
            val next = x.awaitNode(NEXT_BUTTON, timeoutMs = 1_000)
            if (next != null) {
                x.tap(next)
                delay(2_000)
                continue
            }

            delay(500)
        }

        if (captionField == null) {
            return FlowResult.Failed(
                "caption",
                "caption field not found in the composer. Dump this screen and fix CAPTION_FIELD.",
            )
        }
        if (!x.setText(captionField, caption)) {
            return FlowResult.Failed("caption", "could not enter the caption")
        }
        delay(1_000)

        // After entering the caption, Instagram displays an "OK" button (id: next_button_textview)
        // on the top right action bar while the soft keyboard is open.
        // Tap "OK" via physical touch gesture and ACTION_CLICK to ensure the keyboard is dismissed
        // and the Share button appears on every image.
        var shareButton: UiNode? = null
        val shareDeadline = System.currentTimeMillis() + 16_000
        while (System.currentTimeMillis() < shareDeadline) {
            // First check if Share button is already visible
            shareButton = x.awaitNode(SHARE_BUTTON, timeoutMs = 1_500)
            if (shareButton != null) break

            // Check if OK button is visible on top-right and tap it with touch gesture
            val okButton = x.awaitNode(OK_BUTTON, timeoutMs = 1_500)
            if (okButton != null) {
                AgentLog.info("Tapping OK on top right (@${okButton.centerX}, ${okButton.centerY})...")
                x.tapAt(okButton.centerX, okButton.centerY)
                x.tap(okButton)
                delay(1_500)
                continue
            }

            // Also check for Next button in multi-screen preview
            val nextButton = x.awaitNode(NEXT_BUTTON, timeoutMs = 1_000)
            if (nextButton != null) {
                AgentLog.info("Tapping Next (@${nextButton.centerX}, ${nextButton.centerY})...")
                x.tapAt(nextButton.centerX, nextButton.centerY)
                x.tap(nextButton)
                delay(1_500)
                continue
            }

            // If keyboard is still holding focus, dismiss via back
            x.back()
            delay(1_000)
        }

        if (shareButton == null) {
            return FlowResult.Failed("share", "Share button not found")
        }

        if (!autoConfirm) {
            AgentLog.halt(
                "STOPPED before '${shareButton.label}'. Caption is in place — tap Share yourself."
            )
            return FlowResult.AwaitingUser("Composer ready with the caption. Nothing was posted.")
        }
        AgentLog.info("Tapping '${shareButton.label}' (@${shareButton.centerX}, ${shareButton.centerY})...")
        if (!x.tapAt(shareButton.centerX, shareButton.centerY) && !x.tap(shareButton)) {
            return FlowResult.Failed("share", "could not tap Share")
        }

        return if (x.awaitGone(CAPTION_FIELD, timeoutMs = 12_000)) {
            FlowResult.Success("Shared to Instagram")
        } else {
            FlowResult.Failed(
                "verify",
                "tapped Share but the composer is still open after 12s — the post " +
                    "most likely did not go through.",
            )
        }
    }

    private companion object {
        const val PACKAGE = "com.instagram.android"

        val FEED_OPTION = query(
            "Feed option",
            Selector(id = "resolver_first_item"),
            Selector(text = "Feed"),
            Selector(desc = "Feed"),
            Selector(text = "Post"),
            Selector(desc = "Post"),
        )

        val JUST_ONCE = query(
            "Just once",
            Selector(id = "button_once"),
            Selector(text = "Just once"),
            Selector(desc = "Just once"),
        )

        val NEXT_BUTTON = query(
            "Next button",
            Selector(id = "media_thumbnail_tray_button"),
            Selector(id = "media_thumbnail_tray_next_buttons_layout"),
            Selector(id = "next_button_textview"),
            Selector(text = "Next"),
            Selector(desc = "Next"),
        )

        val OK_BUTTON = query(
            "OK button",
            Selector(id = "next_button_textview", text = "OK"),
            Selector(id = "next_button_textview", desc = "OK"),
            Selector(id = "next_button_textview"),
            Selector(id = "action_bar_button_action", text = "OK"),
            Selector(id = "action_bar_button_action"),
            Selector(text = "OK"),
            Selector(desc = "OK"),
            Selector(text = "Done"),
            Selector(desc = "Done"),
        )

        /** All alternatives require editable=true so non-editable containers never match. */
        val CAPTION_FIELD = query(
            "caption field",
            Selector(editable = true, id = "caption_input_text_view"),
            Selector(editable = true, id = "caption_text_view"),
            Selector(editable = true, textContains = "Write a caption"),
            Selector(editable = true, desc = "Write a caption"),
            Selector(editable = true, desc = "caption"),
            Selector(editable = true, id = "caption"),
            Selector(editable = true, clazz = "EditText"),
            Selector(editable = true),
        )

        val SHARE_BUTTON = query(
            "Share button",
            Selector(id = "share_footer_button"),
            Selector(id = "share_button"),
            Selector(id = "next_button_textview", text = "Share"),
            Selector(id = "next_button_textview", desc = "Share"),
            Selector(id = "action_bar_button_action", text = "Share"),
            Selector(id = "action_bar_button_action", desc = "Share"),
            Selector(text = "Share"),
            Selector(desc = "Share"),
        )
    }
}
