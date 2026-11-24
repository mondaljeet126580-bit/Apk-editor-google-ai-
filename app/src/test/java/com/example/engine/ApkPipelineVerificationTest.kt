package com.example.engine

import com.android.apksig.ApkVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
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
  fun testSizeOptimizedSplitMergeCloneAndV1V2V3Sign() {
    val baseApk = tempFolder.newFile("base.apk")
    val arm64SplitApk = tempFolder.newFile("split_config.arm64_v8a.apk")
    val x86SplitApk = tempFolder.newFile("split_config.x86_64.apk")
    val dpiSplitApk = tempFolder.newFile("split_config.xxhdpi.apk")
    val unsignedOptimizedApk = tempFolder.newFile("unsigned_optimized.apk")
    val signedApk = tempFolder.newFile("signed_final.apk")
    val keystoreFile = File(tempFolder.root, "test_signing.p12")

    // Highly compressible payloads simulating bloated STORED DEX, JSON assets, and native .so
    val fakeDexBytes = ("dex\n039\u0000" + "Lcom/microsoft/bing/MainActivity;".repeat(2000)).toByteArray()
    val fakeAssetJson = ("{\"feature\":\"search\",\"enabled\":true},".repeat(1500)).toByteArray()
    val fakeMediaMp4 = ByteArray(1024) { ((it * 31) and 0xFF).toByte() }
    val fakeArm64So = ByteArray(64 * 1024) { (it % 16).toByte() }
    val fakeArmeabiSo = ByteArray(32 * 1024) { (it % 13).toByte() }

    // 1. Build synthetic base.apk (like Bing Target SDK 36 Split APK with STORED DEX, old signatures, and build metadata)
    val baseManifest = buildBingLikeSplitManifest()
    ZipOutputStream(FileOutputStream(baseApk)).use { zos ->
      zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zos.write(baseManifest)
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("resources.arsc"))
      zos.write(ByteArray(256) { (it and 0xFF).toByte() })
      zos.closeEntry()

      // Store classes.dex uncompressed in source APK to verify we recompress it at level 9
      writeRawStored(zos, "classes.dex", fakeDexBytes)
      writeRawStored(zos, "assets/config.json", fakeAssetJson)
      writeRawStored(zos, "assets/intro.mp4", fakeMediaMp4)
      // Redundant non-arm64 ABI inside base APK
      writeRawStored(zos, "lib/armeabi-v7a/liblegacy.so", fakeArmeabiSo)
      writeRawStored(zos, "lib/x86/libsim.so", fakeArmeabiSo)

      // Old signatures & Gradle build metadata that MUST be stripped
      zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
      zos.write("Manifest-Version: 1.0\n".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("META-INF/OLDKEY.SF"))
      zos.write("Signature-Version: 1.0\n".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("META-INF/OLDKEY.RSA"))
      zos.write(ByteArray(64) { 0x42 })
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("META-INF/com/android/build/gradle/app-metadata.properties"))
      zos.write("appMetadataVersion=1.1\n".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("stamp-cert-sha256"))
      zos.write(ByteArray(32) { 0x11 })
      zos.closeEntry()
    }

    // 2. Build synthetic arm64-v8a split APK
    ZipOutputStream(FileOutputStream(arm64SplitApk)).use { zos ->
      zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zos.write("split_manifest_must_be_ignored".toByteArray())
      zos.closeEntry()
      writeRawStored(zos, "lib/arm64-v8a/libbing_native.so", fakeArm64So)
    }

    // 3. Build unused x86_64 split APK (must be excluded completely)
    ZipOutputStream(FileOutputStream(x86SplitApk)).use { zos ->
      writeRawStored(zos, "lib/x86_64/libbing_native.so", fakeArm64So)
    }

    // 4. Build synthetic DPI/feature split APK
    ZipOutputStream(FileOutputStream(dpiSplitApk)).use { zos ->
      writeRawStored(zos, "res/drawable-xxhdpi-v4/banner.webp", ByteArray(512) { 0x55 })
      writeRawStored(zos, "classes.dex", fakeDexBytes)
    }

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
      splitApkFiles = listOf(dpiSplitApk, x86SplitApk, arm64SplitApk),
      outputUnsignedApk = unsignedOptimizedApk,
      request = request,
    )

    // Sign AFTER repacking/alignment with V1 + V2 + V3
    ApkSignerHelper.ensureKeystoreAtFile(keystoreFile)
    ApkSignerHelper.signApkWithKeystore(
      keystoreFile = keystoreFile,
      inputUnsignedApk = unsignedOptimizedApk,
      outputSignedApk = signedApk,
      options = SigningOptions(v1Enabled = true, v2Enabled = true, v3Enabled = true),
    )

    // Verify V1 + V2 + V3 signatures with Google's official ApkVerifier
    val verifyResult = ApkVerifier.Builder(signedApk).setMinCheckedPlatformVersion(23).build().verify()
    assertTrue("APK verification failed: ${verifyResult.errors}", verifyResult.isVerified)
    assertTrue("V1 signature missing", verifyResult.isVerifiedUsingV1Scheme)
    assertTrue("V2 signature missing", verifyResult.isVerifiedUsingV2Scheme)
    assertTrue("V3 signature missing", verifyResult.isVerifiedUsingV3Scheme)

    // Verify size reduction (uncompressed inputs > 250 KB, signed optimized APK should be tiny!)
    val combinedInputBytes = baseApk.length() + arm64SplitApk.length() + dpiSplitApk.length()
    assertTrue(
      "Expected huge size reduction! Combined inputs=$combinedInputBytes, signed=${signedApk.length()}",
      signedApk.length() < combinedInputBytes / 4,
    )

    // Verify ZIP entries, compression methods, stripped ABIs, and patched AXML
    ZipFile(signedApk).use { zip ->
      assertNull("Old signature OLDKEY.RSA not stripped", zip.getEntry("META-INF/OLDKEY.RSA"))
      assertNull("Old signature OLDKEY.SF not stripped", zip.getEntry("META-INF/OLDKEY.SF"))
      assertNull("Play stamp-cert-sha256 not stripped", zip.getEntry("stamp-cert-sha256"))
      assertNull(
        "Gradle build metadata not stripped",
        zip.getEntry("META-INF/com/android/build/gradle/app-metadata.properties"),
      )

      // Unused architectures must be stripped
      assertNull("armeabi-v7a should be stripped when arm64-v8a exists", zip.getEntry("lib/armeabi-v7a/liblegacy.so"))
      assertNull("x86 should be stripped", zip.getEntry("lib/x86/libsim.so"))
      assertNull("x86_64 split should be stripped", zip.getEntry("lib/x86_64/libbing_native.so"))

      // resources.arsc MUST stay STORED
      val arscEntry = zip.getEntry("resources.arsc")
      assertNotNull(arscEntry)
      assertEquals("resources.arsc must remain STORED", ZipEntry.STORED, arscEntry!!.method)

      // Already-compressed media MUST stay STORED
      val mp4Entry = zip.getEntry("assets/intro.mp4")
      assertNotNull(mp4Entry)
      assertEquals("mp4 media must stay STORED", ZipEntry.STORED, mp4Entry!!.method)
      val webpEntry = zip.getEntry("res/drawable-xxhdpi-v4/banner.webp")
      assertNotNull(webpEntry)
      assertEquals("webp media must stay STORED", ZipEntry.STORED, webpEntry!!.method)

      // DEX, JSON assets, and arm64-v8a .so MUST be DEFLATED (level 9)
      val dex1 = zip.getEntry("classes.dex")!!
      val dex2 = zip.getEntry("classes2.dex")!!
      val jsonEntry = zip.getEntry("assets/config.json")!!
      val arm64So = zip.getEntry("lib/arm64-v8a/libbing_native.so")!!

      assertEquals("classes.dex must be DEFLATED", ZipEntry.DEFLATED, dex1.method)
      assertEquals("classes2.dex must be DEFLATED", ZipEntry.DEFLATED, dex2.method)
      assertEquals("assets/config.json must be DEFLATED", ZipEntry.DEFLATED, jsonEntry.method)
      assertEquals("arm64-v8a .so must be DEFLATED when extractNativeLibs=true", ZipEntry.DEFLATED, arm64So.method)
      assertTrue("Expected strong .so compression", arm64So.compressedSize < arm64So.size / 10)

      // Verify binary AndroidManifest.xml integrity, cloning, split-stripping, and extractNativeLibs=true
      val patchedManifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
      val parsed = AxmlParserAndPatcher.parseManifestInfo(patchedManifestBytes)
      assertEquals("com.microsoft.bing1", parsed.packageName)
      assertEquals("Bing 1", parsed.appLabel)
      assertEquals(430611026, parsed.versionCode)
      assertEquals("31.9.430611026", parsed.versionName)
      assertEquals(30, parsed.minSdkVersion)
      assertEquals(36, parsed.targetSdkVersion)
      assertEquals("extractNativeLibs must be true!", true, parsed.extractNativeLibs)
      assertFalse("isSplitRequired should be stripped", parsed.isSplitRequired)

      val xml = parsed.xmlPreview
      assertTrue(xml.contains("com.microsoft.bing1.fileprovider;com.microsoft.bing1.androidx-startup"))
      assertTrue(xml.contains("com.microsoft.bing1.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"))
      assertTrue(xml.contains("com.microsoft.bing.MainActivity"))
      assertFalse(xml.contains("isSplitRequired"))
      assertTrue(xml.contains("extractNativeLibs=\"true\""))
    }

    // Mathematically verify resources.arsc 4-byte alignment in the FINAL SIGNED APK
    val offsets = inspectZipDataOffsets(signedApk)
    val arscOffset = offsets["resources.arsc"]
    assertNotNull("Could not locate resources.arsc LFH", arscOffset)
    assertEquals("resources.arsc must be 4-byte aligned in final signed APK", 0L, arscOffset!! % 4L)
  }

  private fun writeRawStored(zos: ZipOutputStream, name: String, data: ByteArray) {
    val crc = CRC32().apply { update(data) }.value
    val entry =
      ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        this.crc = crc
      }
    zos.putNextEntry(entry)
    zos.write(data)
    zos.closeEntry()
  }

  private fun inspectZipDataOffsets(apkFile: File): Map<String, Long> {
    val bytes = apkFile.readBytes()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val result = mutableMapOf<String, Long>()
    var pos = 0
    while (pos + 30 <= bytes.size) {
      val sig = buf.getInt(pos)
      if (sig != 0x04034b50) break
      val flags = buf.getShort(pos + 6).toInt() and 0xFFFF
      val compSize = buf.getInt(pos + 18).toLong() and 0xFFFFFFFFL
      val nameLen = buf.getShort(pos + 26).toInt() and 0xFFFF
      val extraLen = buf.getShort(pos + 28).toInt() and 0xFFFF
      val name = String(bytes, pos + 30, nameLen, Charsets.UTF_8)
      val dataStart = pos + 30 + nameLen + extraLen
      result[name] = dataStart.toLong()

      if ((flags and 0x0008) != 0) {
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
    // Includes extractNativeLibs=false to verify it gets cleanly flipped to true
    val strings =
      listOf(
        "label", // 0
        "name", // 1
        "authorities", // 2
        "value", // 3
        "minSdkVersion", // 4
        "versionCode", // 5
        "versionName", // 6
        "targetSdkVersion", // 7
        "extractNativeLibs", // 8
        "isSplitRequired", // 9
        "requiredSplitTypes", // 10
        "http://schemas.android.com/apk/res/android", // 11
        "manifest", // 12
        "package", // 13
        "com.microsoft.bing", // 14
        "31.9.430611026", // 15
        "arm64_v8a", // 16
        "uses-sdk", // 17
        "permission", // 18
        "com.microsoft.bing.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", // 19
        "uses-permission", // 20
        "application", // 21
        "Bing", // 22
        "activity", // 23
        ".MainActivity", // 24
        "provider", // 25
        "com.microsoft.bing.BingFileProvider", // 26
        "com.microsoft.bing.fileprovider;com.microsoft.bing.androidx-startup", // 27
        "meta-data", // 28
        "com.android.vending.splits.required", // 29
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
        AxmlParserAndPatcher.ATTR_EXTRACT_NATIVE_LIBS,
        AxmlParserAndPatcher.ATTR_IS_SPLIT_REQUIRED,
        AxmlParserAndPatcher.ATTR_REQUIRED_SPLIT_TYPES,
      )

    val chunks =
      listOf(
        encodeSp(strings),
        encodeRm(resIds),
        encodeStart(
          12,
          listOf(
            intArrayOf(-1, 13, 14, 0x03, 14),
            intArrayOf(11, 5, -1, 0x10, 430611026),
            intArrayOf(11, 6, 15, 0x03, 15),
            intArrayOf(11, 10, 16, 0x03, 16),
          ),
        ),
        encodeStart(
          17,
          listOf(
            intArrayOf(11, 4, -1, 0x10, 30),
            intArrayOf(11, 7, -1, 0x10, 36),
          ),
        ),
        encodeEnd(17),
        encodeStart(18, listOf(intArrayOf(11, 1, 19, 0x03, 19))),
        encodeEnd(18),
        encodeStart(20, listOf(intArrayOf(11, 1, 19, 0x03, 19))),
        encodeEnd(20),
        encodeStart(
          21,
          listOf(
            intArrayOf(11, 0, 22, 0x03, 22),
            intArrayOf(11, 8, -1, 0x12, 0), // extractNativeLibs="false"
            intArrayOf(11, 9, -1, 0x12, -1), // isSplitRequired="true"
          ),
        ),
        encodeStart(23, listOf(intArrayOf(11, 1, 24, 0x03, 24))),
        encodeEnd(23),
        encodeStart(
          25,
          listOf(
            intArrayOf(11, 1, 26, 0x03, 26),
            intArrayOf(11, 2, 27, 0x03, 27),
          ),
        ),
        encodeEnd(25),
        encodeStart(
          28,
          listOf(
            intArrayOf(11, 1, 29, 0x03, 29),
            intArrayOf(11, 3, -1, 0x12, -1),
          ),
        ),
        encodeEnd(28),
        encodeEnd(21),
        encodeEnd(12),
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
