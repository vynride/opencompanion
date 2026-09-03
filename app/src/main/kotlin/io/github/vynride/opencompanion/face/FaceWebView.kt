// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.face

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler

private const val FACE_URL = "https://appassets.androidplatform.net/assets/face/index.html"

/** Builds the WebView that hosts the face UI, bridged to core events via [push]. */
fun buildFaceWebView(context: Context): WebView {
    val assetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetsPathHandler(context))
            .build()
    return WebView(context).apply {
        // Without explicit params the Compose host can measure the layout viewport
        // to zero height, collapsing every vh unit in the page.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
        loadUrl(FACE_URL)
    }
}

/** Pushes a JSON-encoded core event into `window.face.onMessage`. */
fun WebView.push(json: String) {
    post { evaluateJavascript("window.face && window.face.onMessage($json)", null) }
}
