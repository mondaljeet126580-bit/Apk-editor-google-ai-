package com.example.engine

import com.android.apksig.ApkVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkPipelineVerificationTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun testSplitMergeClone16KbAlignAndV1V2V3Sign() {
    val baseApk = tempFolder.newFile("base.apk")
    val arm64SplitApk = tempFolder.newFile("split_config.arm64_v8a.apk")
    val dpiSplitApk = tempFolder.newFile("split_config.xxhdpi.apk")
    val unsignedAlignedApk = tempFolder.newFile("unsigned_aligned.apk")
    val signedApk = tempFolder.newFile("signed_final.apk")
    val keystoreFile = File(tempFolder.root, "test_signing.p12")

    // 1. Build synthetic base.apk (like Bing Target SDK 36 Split APK with old signatures)
    val baseManifest = buildBingLikeSplitManifest()
    ZipOutputStream(FileOutputStream(baseApk)).use { zos ->
      zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zos.write(baseManifest)
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("resources.arsc"))
      zos.write(ByteArray(256) { (it and 0xFF).toByte() })
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("classes.dex"))
      zos.write("dex\n039\u0000dummy_dex_payload".toByteArray())
      zos.closeEntry()

      // Old signatures that MUST be stripped
      zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
      zos.write("Manifest-Version: 1.0\n".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("META-INF/OLDKEY.SF"))
      zos.write("Signature-Version: 1.0\n".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("META-INF/OLDKEY.RSA"))
      zos.write(ByteArray(64) { 0x42 })
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("stamp-cert-sha256"))
      zos.write(ByteArray(32) { 0x11 })
      zos.closeEntry()
    }

    // 2. Build synthetic arm64-v8a split APK (with compressed .so to verify forced STORED + 16KB align)
    ZipOutputStream(FileOutputStream(arm64SplitApk)).use { zos ->
      zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zos.write("split_manifest_must_be_ignored".toByteArray())
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("lib/arm64-v8a/libbing_native.so"))
      zos.write(ByteArray(4096) { (it % 127).toByte() })
      zos.closeEntry()
    }

    // 3. Build synthetic DPI/feature split APK
    ZipOutputStream(FileOutputStream(dpiSplitApk)).use { zos ->
      zos.putNextEntry(ZipEntry("res/drawable-xxhdpi-v4/banner.png"))
      zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("classes.dex"))
      zos.write("dex\n039\u0000feature_split_dex".toByteArray())
      zos.closeEntry()
    }

    // 4. Run full repack + split merge + clone manifest patch + 4B/16KB alignment
    val request =
      ApkEditRequest(
        manifestPatch =
          ManifestPatchSpec(
            packageName = "com.microsoft.bing1",
            appLabel = "Bing 1",
            versionCode = 430611026,
            versionName = "31.9.430611026",
            minSdkVersion = 30,
            targetSdkVersion = 36,
          ),
        outputBaseName = "Bing 1",
      )

    ApkEditorEngine.repackAndAlignApk(
      sourceApkFile = baseApk,
      splitApkFiles = listOf(dpiSplitApk, arm64SplitApk),
      outputUnsignedApk = unsignedAlignedApk,
      request = request,
    )

    // 5. Sign AFTER alignment with V1 + V2 + V3 using auto-generated 2048-bit RSA PKCS12 keystore
    ApkSignerHelper.ensureKeystoreAtFile(keystoreFile)
    ApkSignerHelper.signApkWithKeystore(
      keystoreFile = keystoreFile,
      inputUnsignedApk = unsignedAlignedApk,
      outputSignedApk = signedApk,
      options = SigningOptions(v1Enabled = true, v2Enabled = true, v3Enabled = true),
    )

    // 6. Verify V1 + V2 + V3 signatures with Google's official ApkVerifier!
    val verifyResult = ApkVerifier.Builder(signedApk).setMinCheckedPlatformVersion(23).build().verify()
    assertTrue("APK verification failed: ${verifyResult.errors}", verifyResult.isVerified)
    assertTrue("V1 signature missing", verifyResult.isVerifiedUsingV1Scheme)
    assertTrue("V2 signature missing", verifyResult.isVerifiedUsingV2Scheme)
    assertTrue("V3 signature missing", verifyResult.isVerifiedUsingV3Scheme)

    // 7. Verify merged entries, stripped old signatures, and patched binary AndroidManifest.xml
    ZipFile(signedApk).use { zip ->
      assertNull("Old signature OLDKEY.RSA was not stripped!", zip.getEntry("META-INF/OLDKEY.RSA"))
      assertNull("Old signature OLDKEY.SF was not stripped!", zip.getEntry("META-INF/OLDKEY.SF"))
      assertNull("Play stamp-cert-sha256 was not stripped!", zip.getEntry("stamp-cert-sha256"))

      val arscEntry = zip.getEntry("resources.arsc")
      assertNotNull("resources.arsc missing", arscEntry)
      assertEquals("resources.arsc must be STORED", ZipEntry.STORED, arscEntry!!.method)

      val soEntry = zip.getEntry("lib/arm64-v8a/libbing_native.so")
      assertNotNull("arm64-v8a split native library was not merged!", soEntry)
      assertEquals(".so native lib must be STORED", ZipEntry.STORED, soEntry!!.method)

      assertNotNull("DPI split resource was not merged!", zip.getEntry("res/drawable-xxhdpi-v4/banner.png"))
      assertNotNull("Feature split dex was not merged as classes2.dex!", zip.getEntry("classes2.dex"))

      val patchedManifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
      val parsed = AxmlParserAndPatcher.parseManifestInfo(patchedManifestBytes)
      assertEquals("com.microsoft.bing1", parsed.packageName)
      assertEquals("Bing 1", parsed.appLabel)
      assertEquals(430611026, parsed.versionCode)
      assertEquals("31.9.430611026", parsed.versionName)
      assertEquals(30, parsed.minSdkVersion)
      assertEquals(36, parsed.targetSdkVersion)
      assertFalse("isSplitRequired should be stripped!", parsed.isSplitRequired)

      // Check cloning rewrites in decoded XML
      val xml = parsed.xmlPreview
      assertTrue(
        "Provider authorities were not renamed on clone! XML:\n$xml",
        xml.contains("com.microsoft.bing1.fileprovider;com.microsoft.bing1.androidx-startup"),
      )
      assertTrue(
        "Custom permission was not renamed on clone! XML:\n$xml",
        xml.contains("com.microsoft.bing1.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
      )
      assertTrue(
        "Relative activity class name should expand to original package! XML:\n$xml",
        xml.contains("com.microsoft.bing.MainActivity"),
      )
      assertFalse(
        "android:isSplitRequired must be removed! XML:\n$xml",
        xml.contains("isSplitRequired"),
      )
      assertTrue(
        "com.android.vending.splits.required should be false! XML:\n$xml",
        xml.contains("com.android.vending.splits.required") && xml.contains("value=\"false\""),
      )
    }

    // 8. Mathematically verify raw ZIP Local File Header data offsets in the FINAL SIGNED APK!
    val offsets = inspectZipDataOffsets(signedApk)
    val arscOffset = offsets["resources.arsc"]
    val soOffset = offsets["lib/arm64-v8a/libbing_native.so"]
    assertNotNull("Could not locate resources.arsc LFH", arscOffset)
    assertNotNull("Could not locate .so LFH", soOffset)
    assertEquals(
      "resources.arsc offset $arscOffset is not 4-byte aligned in final signed APK!",
      0L,
      arscOffset!! % 4L,
    )
    assertEquals(
      "lib/arm64-v8a/libbing_native.so offset $soOffset is not 16384-byte (16KB) aligned in final signed APK!",
      0L,
      soOffset!! % 16384L,
    )
  }

  /**
   * Walks ZIP Local File Headers directly from raw bytes to compute the exact
   * byte offset of each entry's payload data inside the APK.
   */
  private fun inspectZipDataOffsets(apkFile: File): Map<String, Long> {
    val bytes = apkFile.readBytes()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val result = mutableMapOf<String, Long>()
    var pos = 0
    while (pos + 30 <= bytes.size) {
      val sig = buf.getInt(pos)
      if (sig != 0x04034b50) break // End of Local File Headers
      val flags = buf.getShort(pos + 6).toInt() and 0xFFFF
      val compSize = buf.getInt(pos + 18).toLong() and 0xFFFFFFFFL
      val nameLen = buf.getShort(pos + 26).toInt() and 0xFFFF
      val extraLen = buf.getShort(pos + 28).toInt() and 0xFFFF
      val name = String(bytes, pos + 30, nameLen, Charsets.UTF_8)
      val dataStart = pos + 30 + nameLen + extraLen
      result[name] = dataStart.toLong()

      if ((flags and 0x0008) != 0) {
        // Data descriptor present (DEFLATED): scan forward for next LFH or APK Sig / CD header
        var scan = dataStart
        while (scan + 4 <= bytes.size) {
          val nextSig = buf.getInt(scan)
          if (nextSig == 0x04034b50 || nextSig == 0x02014b50) break
          scan++
        }
        pos = scan
      } else {
        pos = (dataStart + compSize).toInt()
      }
    }
    return result
  }

  private fun buildBingLikeSplitManifest(): ByteArray {
    // 0: label, 1: name, 2: authorities, 3: value, 4: minSdkVersion, 5: versionCode,
    // 6: versionName, 7: targetSdkVersion, 8: isSplitRequired, 9: requiredSplitTypes
    val strings =
      listOf(
        "label",
        "name",
        "authorities",
        "value",
        "minSdkVersion",
        "versionCode",
        "versionName",
        "targetSdkVersion",
        "isSplitRequired",
        "requiredSplitTypes",
        "http://schemas.android.com/apk/res/android", // 10
        "manifest", // 11
        "package", // 12
        "com.microsoft.bing", // 13
        "31.9.430611026", // 14
        "arm64_v8a", // 15
        "uses-sdk", // 16
        "permission", // 17
        "com.microsoft.bing.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", // 18
        "uses-permission", // 19
        "application", // 20
        "Bing", // 21
        "activity", // 22
        ".MainActivity", // 23
        "provider", // 24
        "com.microsoft.bing.BingFileProvider", // 25
        "com.microsoft.bing.fileprovider;com.microsoft.bing.androidx-startup", // 26
        "meta-data", // 27
        "com.android.vending.splits.required", // 28
      )
    val resIds =
      intArrayOf(
        AxmlParserAndPatcher.ATTR_LABEL,
        AxmlParserAndPatcher.ATTR_NAME,
        AxmlParserAndPatcher.ATTR_AUTHORITIES,
        AxmlParserAndPatcher.ATTR_VALUE,
        AxmlParserAndPatcher.ATTR_MIN_SDK_VERSION,
        AxmlParserAndPatcher.ATTR_VERSION_CODE,
        AxmlParserAndPatcher.ATTR_VERSION_NAME,
        AxmlParserAndPatcher.ATTR_TARGET_SDK_VERSION,
        AxmlParserAndPatcher.ATTR_IS_SPLIT_REQUIRED,
        AxmlParserAndPatcher.ATTR_REQUIRED_SPLIT_TYPES,
      )

    val chunks =
      listOf(
        encodeSp(strings),
        encodeRm(resIds),
        encodeStart(
          11,
          listOf(
            intArrayOf(-1, 12, 13, 0x03, 13), // package
            intArrayOf(10, 5, -1, 0x10, 430611026), // versionCode
            intArrayOf(10, 6, 14, 0x03, 14), // versionName
            intArrayOf(10, 9, 15, 0x03, 15), // requiredSplitTypes
          ),
        ),
        encodeStart(
          16,
          listOf(
            intArrayOf(10, 4, -1, 0x10, 30), // minSdkVersion=30
            intArrayOf(10, 7, -1, 0x10, 36), // targetSdkVersion=36
          ),
        ),
        encodeEnd(16),
        encodeStart(17, listOf(intArrayOf(10, 1, 18, 0x03, 18))), // <permission>
        encodeEnd(17),
        encodeStart(19, listOf(intArrayOf(10, 1, 18, 0x03, 18))), // <uses-permission>
        encodeEnd(19),
        encodeStart(
          20,
          listOf(
            intArrayOf(10, 0, 21, 0x03, 21), // label="Bing"
            intArrayOf(10, 8, -1, 0x12, -1), // isSplitRequired=true
          ),
        ),
        encodeStart(22, listOf(intArrayOf(10, 1, 23, 0x03, 23))), // <activity android:name=".MainActivity">
        encodeEnd(22),
        encodeStart(
          24,
          listOf(
            intArrayOf(10, 1, 25, 0x03, 25),
            intArrayOf(10, 2, 26, 0x03, 26),
          ),
        ), // <provider>
        encodeEnd(24),
        encodeStart(
          27,
          listOf(
            intArrayOf(10, 1, 28, 0x03, 28),
            intArrayOf(10, 3, -1, 0x12, -1), // value=true
          ),
        ), // <meta-data com.android.vending.splits.required=true>
        encodeEnd(27),
        encodeEnd(20),
        encodeEnd(11),
      )

    val total = 8 + chunks.sumOf { it.size }
    val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    out.putShort(0x0003)
    out.putShort(8)
    out.putInt(total)
    chunks.forEach { out.put(it) }
    return out.array()
  }

  private fun encodeSp(strings: List<String>): ByteArray {
    val offsets = IntArray(strings.size)
    val data = ByteArrayOutputStream()
    for (i in strings.indices) {
      offsets[i] = data.size()
      val s = strings[i]
      data.write(s.length)
      val b = s.toByteArray(Charsets.UTF_8)
      data.write(b.size)
      data.write(b)
      data.write(0)
    }
    while (data.size() % 4 != 0) data.write(0)
    val bytes = data.toByteArray()
    val stringsStart = 28 + strings.size * 4
    val total = stringsStart + bytes.size
    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0x0001)
    buf.putShort(28)
    buf.putInt(total)
    buf.putInt(strings.size)
    buf.putInt(0)
    buf.putInt(0x100)
    buf.putInt(stringsStart)
    buf.putInt(0)
    offsets.forEach { buf.putInt(it) }
    buf.put(bytes)
    return buf.array()
  }

  private fun encodeRm(resIds: IntArray): ByteArray {
    val total = 8 + resIds.size * 4
    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0x0180)
    buf.putShort(8)
    buf.putInt(total)
    resIds.forEach { buf.putInt(it) }
    return buf.array()
  }

  private fun encodeStart(nameIdx: Int, attrs: List<IntArray>): ByteArray {
    val total = 16 + 20 + attrs.size * 20
    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0x0102)
    buf.putShort(16)
    buf.putInt(total)
    buf.putInt(1)
    buf.putInt(-1)
    buf.putInt(-1)
    buf.putInt(nameIdx)
    buf.putShort(20)
    buf.putShort(20)
    buf.putShort(attrs.size.toShort())
    buf.putShort(0)
    buf.putShort(0)
    buf.putShort(0)
    for (a in attrs) {
      buf.putInt(a[0])
      buf.putInt(a[1])
      buf.putInt(a[2])
      buf.putShort(8)
      buf.put(0)
      buf.put(a[3].toByte())
      buf.putInt(a[4])
    }
    return buf.array()
  }

  private fun encodeEnd(nameIdx: Int): ByteArray {
    val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0x0103)
    buf.putShort(16)
    buf.putInt(24)
    buf.putInt(1)
    buf.putInt(-1)
    buf.putInt(-1)
    buf.putInt(nameIdx)
    return buf.array()
  }
}
