package dev.fitnessapp.app.ui.auth

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import dev.fitnessapp.app.ui.common.TonalButton
import dev.fitnessapp.app.ui.theme.Fit
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** How long the widget may take to give a token before it asks for a click (D95). */
private const val TURNSTILE_TIMEOUT_MS = 30_000L

/** Turnstile site keys are `0x` plus base64url-ish characters; anything else is not put into the page. */
private val SITE_KEY = Regex("[A-Za-z0-9_-]{8,100}")

/** Called by the widget page's JavaScript (on the WebView's bridge thread). Kept by name in R8 rules. */
class TurnstileBridge(
    private val onToken: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onInteractive: () -> Unit,
) {
    @JavascriptInterface fun token(t: String) = onToken(t)
    @JavascriptInterface fun error(code: String) = onError(code)
    @JavascriptInterface fun interactive() = onInteractive()
}

/** The widget page: renders Turnstile explicitly and reports the token, an error or a challenge to [TurnstileBridge]. */
internal fun turnstilePage(siteKey: String): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}</style>
<script>
function ready(){
  try {
    turnstile.render('#w', {
      sitekey: '$siteKey', appearance: 'interaction-only', theme: 'auto',
      callback: function(t){ Bridge.token(t); },
      'error-callback': function(c){ Bridge.error(String(c)); return true; },
      'expired-callback': function(){ Bridge.error('expired'); },
      'timeout-callback': function(){ Bridge.error('timeout'); },
      'before-interactive-callback': function(){ Bridge.interactive(); }
    });
  } catch (e) { Bridge.error('render'); }
}
</script>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?onload=ready&render=explicit" async defer
  onerror="Bridge.error('load')"></script>
</head><body><div id="w"></div></body></html>
""".trimIndent()

/**
 * D95: gets a fresh Turnstile token for one password sign-in. Exactly one of [onToken], [onError] or [onCancel]
 * is called. The page is loaded with [baseUrl] as its origin, which must be a hostname in the widget's list.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TurnstileDialog(
    siteKey: String, baseUrl: String,
    onToken: (String) -> Unit, onError: (String) -> Unit, onCancel: () -> Unit,
) {
    val token by rememberUpdatedState(onToken)
    val error by rememberUpdatedState(onError)
    val cancel by rememberUpdatedState(onCancel)
    val finished = remember { AtomicBoolean(false) }
    var interactive by remember { mutableStateOf(false) }
    fun finish(block: () -> Unit) { if (finished.compareAndSet(false, true)) block() }

    LaunchedEffect(Unit) { if (!SITE_KEY.matches(siteKey)) finish { error("site_key") } }
    // Once Cloudflare shows a challenge the user decides how long it takes.
    LaunchedEffect(interactive) {
        if (!interactive) {
            delay(TURNSTILE_TIMEOUT_MS)
            finish { error("timeout") }
        }
    }

    Dialog(onDismissRequest = { finish { cancel() } }) {
        Surface(shape = RoundedCornerShape(16.dp), color = Fit.Tile) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Checking that you're not a bot…", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.width(300.dp).height(72.dp), contentAlignment = Alignment.Center) {
                    if (SITE_KEY.matches(siteKey)) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    setBackgroundColor(Color.TRANSPARENT)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    val view = this
                                    addJavascriptInterface(
                                        TurnstileBridge(
                                            onToken = { t -> view.post { finish { token(t) } } },
                                            onError = { c -> view.post { finish { error(c) } } },
                                            onInteractive = { view.post { interactive = true } },
                                        ),
                                        "Bridge",
                                    )
                                    loadDataWithBaseURL(baseUrl, turnstilePage(siteKey), "text/html", "utf-8", null)
                                }
                            },
                            onRelease = { it.destroy() },
                            modifier = Modifier.width(300.dp).height(72.dp),
                        )
                    }
                    if (!interactive) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                }
                TonalButton("Cancel", { finish { cancel() } }, Modifier.fillMaxWidth(), container = Fit.Raised)
            }
        }
    }
}
