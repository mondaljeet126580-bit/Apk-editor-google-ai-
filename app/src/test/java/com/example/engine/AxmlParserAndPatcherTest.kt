package com.example.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AxmlParserAndPatcherTest {

  @Test
  fun testUtf8AxmlParseAndPatchRoundtrip() {
    val sampleAxml = buildSyntheticAxml(useUtf8 = true)
    val initial = AxmlParserAndPatcher.parseManifestInfo(sampleAxml)
    assertEquals("com.demo.original", initial.packageName)
    assertEquals("OriginalApp", initial.appLabel)
    assertEquals(10, initial.versionCode)
    assertEquals("1.0.0", initial.versionName)
    assertEquals(26, initial.minSdkVersion)
    assertEquals(34, initial.targetSdkVersion)

    val patchedBytes =
      AxmlParserAndPatcher.patch(
        sampleAxml,
        ManifestPatchSpec(
          packageName = "com.demo.cloned",
          appLabel = "Cloned App GM",
          versionCode = 42,
          versionName = "2.5.0",
          installLocation = 2,
          minSdkVersion = 28,
          targetSdkVersion = 35,
        ),
      )

    val reparsed = AxmlParserAndPatcher.parseManifestInfo(patchedBytes)
    assertEquals("com.demo.cloned", reparsed.packageName)
    assertEquals("Cloned App GM", reparsed.appLabel)
    assertEquals(42, reparsed.versionCode)
    assertEquals("2.5.0", reparsed.versionName)
    assertEquals(2, reparsed.installLocation)
    assertEquals(28, reparsed.minSdkVersion)
    assertEquals(35, reparsed.targetSdkVersion)
    assertTrue(reparsed.xmlPreview.contains("com.demo.cloned"))
  }

  @Test
  fun testUtf16AxmlParseAndPatchRoundtrip() {
    val sampleAxml = buildSyntheticAxml(useUtf8 = false)
    val patchedBytes =
      AxmlParserAndPatcher.patch(
        sampleAxml,
        ManifestPatchSpec(
          packageName = "com.utf16.edited",
          appLabel = "Edited UTF16",
          versionCode = 99,
          versionName = "9.9",
          installLocation = 1,
          minSdkVersion = 24,
          targetSdkVersion = 34,
        ),
      )

    val reparsed = AxmlParserAndPatcher.parseManifestInfo(patchedBytes)
    assertEquals("com.utf16.edited", reparsed.packageName)
    assertEquals("Edited UTF16", reparsed.appLabel)
    assertEquals(99, reparsed.versionCode)
    assertEquals("9.9", reparsed.versionName)
    assertEquals(1, reparsed.installLocation)
    assertEquals(24, reparsed.minSdkVersion)
    assertEquals(34, reparsed.targetSdkVersion)
  }

  private fun buildSyntheticAxml(useUtf8: Boolean): ByteArray {
    // String pool entries:
    // 0: label, 1: minSdkVersion, 2: versionCode, 3: versionName, 4: targetSdkVersion
    // 5: http://schemas.android.com/apk/res/android
    // 6: manifest, 7: package, 8: com.demo.original, 9: 1.0.0
    // 10: uses-sdk, 11: application, 12: OriginalApp
    val strings =
      listOf(
        "label",
        "minSdkVersion",
        "versionCode",
        "versionName",
        "targetSdkVersion",
        "http://schemas.android.com/apk/res/android",
        "manifest",
        "package",
        "com.demo.original",
        "1.0.0",
        "uses-sdk",
        "application",
        "OriginalApp",
      )
    val resIds =
      intArrayOf(
        AxmlParserAndPatcher.ATTR_LABEL,
        AxmlParserAndPatcher.ATTR_MIN_SDK_VERSION,
        AxmlParserAndPatcher.ATTR_VERSION_CODE,
        AxmlParserAndPatcher.ATTR_VERSION_NAME,
        AxmlParserAndPatcher.ATTR_TARGET_SDK_VERSION,
      )

    val spChunk = encodeTestStringPool(strings, useUtf8)
    val rmChunk = encodeTestResourceMap(resIds)

    val manifestStart =
      encodeTestStartElement(
        nameIdx = 6,
        attrs =
          listOf(
            intArrayOf(-1, 7, 8, 0x03, 8), // package="com.demo.original"
            intArrayOf(5, 2, -1, 0x10, 10), // android:versionCode=10
            intArrayOf(5, 3, 9, 0x03, 9), // android:versionName="1.0.0"
          ),
      )
    val usesSdkStart =
      encodeTestStartElement(
        nameIdx = 10,
        attrs =
          listOf(
            intArrayOf(5, 1, -1, 0x10, 26), // android:minSdkVersion=26
            intArrayOf(5, 4, -1, 0x10, 34), // android:targetSdkVersion=34
          ),
      )
    val usesSdkEnd = encodeTestEndElement(10)
    val appStart =
      encodeTestStartElement(
        nameIdx = 11,
        attrs =
          listOf(
            intArrayOf(5, 0, 12, 0x03, 12), // android:label="OriginalApp"
          ),
      )
    val appEnd = encodeTestEndElement(11)
    val manifestEnd = encodeTestEndElement(6)

    val body =
      listOf(spChunk, rmChunk, manifestStart, usesSdkStart, usesSdkEnd, appStart, appEnd, manifestEnd)
    val totalSize = 8 + body.sumOf { it.size }
    val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    out.putShort(0x0003)
    out.putShort(8)
    out.putInt(totalSize)
    body.forEach { out.put(it) }
    return out.array()
  }

  private fun encodeTestStringPool(strings: List<String>, useUtf8: Boolean): ByteArray {
    val offsets = IntArray(strings.size)
    val data = ByteArrayOutputStream()
    for (i in strings.indices) {
      offsets[i] = data.size()
      val s = strings[i]
      if (useUtf8) {
        data.write(s.length)
        val b = s.toByteArray(Charsets.UTF_8)
        data.write(b.size)
        data.write(b)
        data.write(0)
      } else {
        data.write(s.length and 0xFF)
        data.write((s.length shr 8) and 0xFF)
        data.write(s.toByteArray(Charsets.UTF_16LE))
        data.write(0)
        data.write(0)
      }
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
    buf.putInt(if (useUtf8) 0x100 else 0)
    buf.putInt(stringsStart)
    buf.putInt(0)
    offsets.forEach { buf.putInt(it) }
    buf.put(bytes)
    return buf.array()
  }

  private fun encodeTestResourceMap(resIds: IntArray): ByteArray {
    val total = 8 + resIds.size * 4
    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0x0180)
    buf.putShort(8)
    buf.putInt(total)
    resIds.forEach { buf.putInt(it) }
    return buf.array()
  }

  private fun encodeTestStartElement(nameIdx: Int, attrs: List<IntArray>): ByteArray {
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
      buf.putInt(a[0]) // ns
      buf.putInt(a[1]) // name
      buf.putInt(a[2]) // rawValue
      buf.putShort(8)
      buf.put(0)
      buf.put(a[3].toByte()) // type
      buf.putInt(a[4]) // data
    }
    return buf.array()
  }

  private fun encodeTestEndElement(nameIdx: Int): ByteArray {
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
