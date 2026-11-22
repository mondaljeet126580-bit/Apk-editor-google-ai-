package com.example.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryWarm

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
  onBack: () -> Unit,
) {
  var urlInput by remember { mutableStateOf("https://developer.android.com/guide/topics/manifest/manifest-intro") }
  var progress by remember { mutableIntStateOf(0) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  BackHandler(enabled = true) {
    val wv = webViewRef
    if (wv != null && wv.canGoBack()) {
      wv.goBack()
    } else {
      onBack()
    }
  }

  fun loadUrl(raw: String) {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return
    val target =
      if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
      else "https://$trimmed"
    urlInput = target
    webViewRef?.loadUrl(target)
  }

  Scaffold(
    containerColor = PureWhite,
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = PureWhite,
            titleContentColor = TextPrimaryDark,
            navigationIconContentColor = TextPrimaryDark,
            actionIconContentColor = TextPrimaryDark,
          ),
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("webview_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.title_webview),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
          )
        },
        actions = {
          IconButton(onClick = { webViewRef?.goBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Browser back")
          }
          IconButton(onClick = { webViewRef?.goForward() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Browser forward")
          }
          IconButton(onClick = { webViewRef?.reload() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Reload page")
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding).navigationBarsPadding(),
    ) {
      Surface(
        shape = RoundedCornerShape(22.dp),
        color = SoftPeachSurface,
        modifier =
          Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).height(44.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
              value = urlInput,
              onValueChange = { urlInput = it },
              singleLine = true,
              textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimaryDark),
              cursorBrush = SolidColor(TerracottaPrimary),
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
              keyboardActions = KeyboardActions(onGo = { loadUrl(urlInput) }),
              modifier = Modifier.fillMaxWidth().testTag("webview_url_input"),
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          androidx.compose.material3.TextButton(
            onClick = { loadUrl(urlInput) },
            modifier = Modifier.testTag("webview_go_button"),
          ) {
            Text(
              text = "Go",
              style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
              color = TerracottaPrimary,
            )
          }
        }
      }

      if (progress in 1..99) {
        LinearProgressIndicator(
          progress = { progress / 100f },
          color = TerracottaPrimary,
          trackColor = SoftPeachSurface,
          modifier = Modifier.fillMaxWidth().height(3.dp),
        )
      }

      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient =
              object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                  if (!url.isNullOrBlank()) {
                    urlInput = url
                  }
                }
              }
            webChromeClient =
              object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                  progress = newProgress
                }
              }
            loadUrl(urlInput)
            webViewRef = this
          }
        },
        modifier = Modifier.fillMaxSize().testTag("webview_container"),
      )
    }
  }
}
