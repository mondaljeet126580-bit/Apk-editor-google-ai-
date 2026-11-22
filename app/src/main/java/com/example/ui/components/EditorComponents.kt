package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.SavedApkResult
import com.example.ui.theme.DividerWarm
import com.example.ui.theme.InputUnderlineColor
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachBorder
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray
import com.example.ui.theme.TextSecondaryWarm
import com.example.util.ApkInstallHelper
import com.example.viewmodel.BuildOperationState
import com.example.viewmodel.TargetLoadState

@Composable
fun AppIconView(
  bitmap: Bitmap?,
  size: Dp,
  contentDescription: String?,
  modifier: Modifier = Modifier,
) {
  val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
  if (imageBitmap != null) {
    Image(
      bitmap = imageBitmap,
      contentDescription = contentDescription,
      contentScale = ContentScale.Fit,
      modifier = modifier.size(size).clip(RoundedCornerShape(10.dp)),
    )
  } else {
    Box(
      modifier =
        modifier
          .size(size)
          .clip(RoundedCornerShape(10.dp))
          .background(SoftPeachSurface),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Outlined.Android,
        contentDescription = contentDescription,
        tint = TerracottaPrimary,
        modifier = Modifier.size(size * 0.65f),
      )
    }
  }
}

/**
 * Screen 3: Edit Mode Popup Dialog (Matches Screenshot 2 exactly).
 */
@Composable
fun EditModeDialog(
  appName: String,
  onDismiss: () -> Unit,
  onFullEdit: () -> Unit,
  onSimpleEdit: () -> Unit,
  onCommonEdit: () -> Unit,
  onXmlEdit: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(24.dp),
      color = SoftPeachSurface,
      shadowElevation = 8.dp,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("edit_mode_dialog"),
    ) {
      Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
        Text(
          text = stringResource(R.string.dialog_edit_mode_title),
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
          color = TextPrimaryDark,
        )
        if (appName.isNotBlank()) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = appName,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondaryWarm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.height(14.dp))

        EditModeOptionRow(
          icon = Icons.Outlined.Edit,
          label = stringResource(R.string.edit_mode_full),
          testTag = "edit_mode_full_option",
          onClick = onFullEdit,
        )
        EditModeOptionRow(
          icon = Icons.Outlined.FileCopy,
          label = stringResource(R.string.edit_mode_simple),
          testTag = "edit_mode_simple_option",
          onClick = onSimpleEdit,
        )
        EditModeOptionRow(
          icon = Icons.Outlined.Layers,
          label = stringResource(R.string.edit_mode_common),
          testTag = "edit_mode_common_option",
          onClick = onCommonEdit,
        )
        EditModeOptionRow(
          icon = Icons.Outlined.Description,
          label = stringResource(R.string.edit_mode_xml),
          testTag = "edit_mode_xml_option",
          onClick = onXmlEdit,
        )
      }
    }
  }
}

@Composable
private fun EditModeOptionRow(
  icon: ImageVector,
  label: String,
  testTag: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .minimumInteractiveComponentSize()
        .clickable(onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 12.dp)
        .testTag(testTag),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = TextPrimaryDark,
      modifier = Modifier.size(22.dp),
    )
    Spacer(modifier = Modifier.width(16.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
      color = TextPrimaryDark,
    )
  }
}

/**
 * Rounded soft peach card (#FCECE6, 16dp corners) used in Common Edit and settings.
 */
@Composable
fun PeachEditorCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = SoftPeachSurface,
    shadowElevation = 1.dp,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      content = content,
    )
  }
}

/**
 * Clean minimalist underlined text field matching Screenshot 1.
 */
@Composable
fun UnderlinedEditorField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  keyboardType: KeyboardType = KeyboardType.Text,
  singleLine: Boolean = true,
  testTag: String = "",
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()

  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = if (isFocused) TerracottaPrimary else TextSecondaryWarm,
    )
    Spacer(modifier = Modifier.height(6.dp))
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = singleLine,
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
      cursorBrush = SolidColor(TerracottaPrimary),
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      interactionSource = interactionSource,
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(bottom = 6.dp)
          .let { if (testTag.isNotEmpty()) it.testTag(testTag) else it },
    )
    HorizontalDivider(
      thickness = if (isFocused) 1.5.dp else 1.dp,
      color = if (isFocused) TerracottaPrimary else InputUnderlineColor,
    )
  }
}

/**
 * Fixed Bottom Bar matching Screenshot 1: Close (left) + Terracotta Save (right).
 */
@Composable
fun EditorBottomActionBar(
  onClose: () -> Unit,
  onSave: () -> Unit,
  saveEnabled: Boolean = true,
  saveLabel: String = stringResource(R.string.action_save),
) {
  Surface(
    color = PureWhite,
    shadowElevation = 4.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(
        onClick = onClose,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SoftPeachBorder),
        colors =
          ButtonDefaults.outlinedButtonColors(
            containerColor = PureWhite,
            contentColor = TextPrimaryDark,
          ),
        modifier = Modifier.weight(1f).height(48.dp).testTag("editor_close_button"),
      ) {
        Text(
          text = stringResource(R.string.action_close),
          style = MaterialTheme.typography.labelLarge,
        )
      }
      Button(
        onClick = onSave,
        enabled = saveEnabled,
        shape = RoundedCornerShape(8.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = TerracottaPrimary,
            contentColor = PureWhite,
          ),
        modifier = Modifier.weight(1f).height(48.dp).testTag("editor_save_button"),
      ) {
        Text(
          text = saveLabel,
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
      }
    }
  }
}

/**
 * Global Build / Sign / Export progress, success, and error dialogs.
 */
@Composable
fun BuildOperationDialogs(
  buildState: BuildOperationState,
  onDismiss: () -> Unit,
  onInstall: (SavedApkResult) -> Unit,
  onShare: (SavedApkResult) -> Unit,
) {
  when (buildState) {
    BuildOperationState.Idle -> Unit
    is BuildOperationState.InProgress -> {
      Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
      ) {
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = SoftPeachSurface,
          shadowElevation = 8.dp,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            CircularProgressIndicator(color = TerracottaPrimary, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = "Building & Signing APK",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
              color = TextPrimaryDark,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = buildState.statusMessage,
              style = MaterialTheme.typography.bodySmall,
              color = TextSecondaryWarm,
            )
          }
        }
      }
    }
    is BuildOperationState.Success -> {
      val result = buildState.result
      Dialog(onDismissRequest = onDismiss) {
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = SoftPeachSurface,
          shadowElevation = 8.dp,
          modifier = Modifier.fillMaxWidth().testTag("build_success_dialog"),
        ) {
          Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = "Success",
                tint = SuccessGreen,
                modifier = Modifier.size(28.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = "APK Saved Successfully",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimaryDark,
              )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = PureWhite,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = "File name",
                  style = MaterialTheme.typography.labelSmall,
                  color = TextSecondaryWarm,
                )
                Text(
                  text = result.fileName,
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                  color = TextPrimaryDark,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text = "Saved for ZArchiver at",
                  style = MaterialTheme.typography.labelSmall,
                  color = TextSecondaryWarm,
                )
                Text(
                  text = result.displayPath,
                  style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                  color = TerracottaPrimary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Size: ${ApkInstallHelper.formatFileSize(result.fileSizeBytes)} • Signed (V1/V2/V3)",
                  style = MaterialTheme.typography.labelSmall,
                  color = TextSecondaryGray,
                )
              }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Button(
              onClick = { onInstall(result) },
              shape = RoundedCornerShape(10.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = TerracottaPrimary,
                  contentColor = PureWhite,
                ),
              modifier = Modifier.fillMaxWidth().height(48.dp).testTag("success_install_button"),
            ) {
              Text(
                text = stringResource(R.string.action_install_apk),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
              )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedButton(
                onClick = { onShare(result) },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DividerWarm),
                modifier = Modifier.weight(1f).height(44.dp).testTag("success_share_button"),
              ) {
                Text(stringResource(R.string.action_share), color = TextPrimaryDark)
              }
              TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(44.dp).testTag("success_close_button"),
              ) {
                Text(stringResource(R.string.action_close), color = TerracottaPrimary)
              }
            }
          }
        }
      }
    }
    is BuildOperationState.Error -> {
      Dialog(onDismissRequest = onDismiss) {
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = SoftPeachSurface,
          shadowElevation = 8.dp,
          modifier = Modifier.fillMaxWidth().testTag("build_error_dialog"),
        ) {
          Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(26.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = "Build Failed",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimaryDark,
              )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = buildState.message,
              style = MaterialTheme.typography.bodyMedium,
              color = TextPrimaryDark,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
              onClick = onDismiss,
              shape = RoundedCornerShape(10.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = TerracottaPrimary,
                  contentColor = PureWhite,
                ),
              modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
              Text(stringResource(R.string.action_close))
            }
          }
        }
      }
    }
  }
}

@Composable
fun TargetLoadOverlay(
  state: TargetLoadState,
  onDismissError: () -> Unit,
) {
  when (state) {
    TargetLoadState.Idle -> Unit
    TargetLoadState.Loading -> {
      Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
      ) {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = SoftPeachSurface,
          shadowElevation = 6.dp,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(
              color = TerracottaPrimary,
              modifier = Modifier.size(28.dp),
              strokeWidth = 3.dp,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
              text = "Inspecting APK manifest...",
              style = MaterialTheme.typography.bodyLarge,
              color = TextPrimaryDark,
            )
          }
        }
      }
    }
    is TargetLoadState.Error -> {
      Dialog(onDismissRequest = onDismissError) {
        Surface(
          shape = RoundedCornerShape(24.dp),
          color = SoftPeachSurface,
          shadowElevation = 8.dp,
        ) {
          Column(modifier = Modifier.padding(24.dp)) {
            Text(
              text = "Unable to Open APK",
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
              color = TextPrimaryDark,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = state.message,
              style = MaterialTheme.typography.bodyMedium,
              color = TextPrimaryDark,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
              onClick = onDismissError,
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = TerracottaPrimary,
                  contentColor = PureWhite,
                ),
              modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
              Text(stringResource(R.string.action_close))
            }
          }
        }
      }
    }
  }
}
