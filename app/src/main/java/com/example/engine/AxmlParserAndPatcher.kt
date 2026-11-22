package com.example.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed attributes and components extracted from binary AndroidManifest.xml (AXML).
 */
data class ParsedManifestInfo(
  val packageName: String = "",
  val appLabel: String = "",
  val isLabelResourceRef: Boolean = false,
  val versionCode: Int = 1,
  val versionName: String = "1.0",
  val installLocation: Int = -1, // -1 = not specified, 0 = auto, 1 = internalOnly, 2 = preferExternal
  val minSdkVersion: Int = 26,
  val targetSdkVersion: Int = 34,
  val permissions: List<String> = emptyList(),
  val activities: List<String> = emptyList(),
  val services: List<String> = emptyList(),
  val receivers: List<String> = emptyList(),
  val xmlPreview: String = "",
)

/**
 * Requested edits to apply to binary AndroidManifest.xml.
 * Null fields are left untouched.
 */
data class ManifestPatchSpec(
  val packageName: String? = null,
  val appLabel: String? = null,
  val versionCode: Int? = null,
  val versionName: String? = null,
  val installLocation: Int? = null, // -1 removes installLocation attribute
  val minSdkVersion: Int? = null,
  val targetSdkVersion: Int? = null,
)

/**
 * Production Android Binary XML (AXML) parser & binary patcher.
 *
 * Supports both UTF-8 (FLAG_UTF8 = 0x100) and UTF-16LE string pools,
 * preserves all existing string indices, extends RES_XML_RESOURCE_MAP_TYPE
 * cleanly when inserting new framework attributes, and recalculates all chunk
 * sizes, offsets, 4-byte padding, and root AXML header size accurately.
 */
object AxmlParserAndPatcher {
  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_XML_TYPE = 0x0003
  private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
  private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
  private const val RES_XML_START_ELEMENT_TYPE = 0x0102
  private const val RES_XML_END_ELEMENT_TYPE = 0x0103
  private const val RES_XML_CDATA_TYPE = 0x0104
  private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

  private const val FLAG_SORTED = 0x00000001
  private const val FLAG_UTF8 = 0x00000100

  private const val TYPE_REFERENCE = 0x01
  private const val TYPE_STRING = 0x03
  private const val TYPE_INT_DEC = 0x10
  private const val TYPE_INT_HEX = 0x11
  private const val TYPE_INT_BOOLEAN = 0x12

  private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

  // Standard Android framework attribute resource IDs
  const val ATTR_LABEL = 0x01010001
  const val ATTR_NAME = 0x01010003
  const val ATTR_MIN_SDK_VERSION = 0x0101020c
  const val ATTR_VERSION_CODE = 0x0101021b
  const val ATTR_VERSION_NAME = 0x0101021c
  const val ATTR_TARGET_SDK_VERSION = 0x01010270
  const val ATTR_INSTALL_LOCATION = 0x010102b7

  private data class AxmlAttribute(
    var ns: Int,
    var name: Int,
    var rawValue: Int,
    var valueSize: Int,
    var valueRes0: Int,
    var dataType: Int,
    var data: Int,
  )

  private data class RawChunk(
    val type: Int,
    val headerSize: Int,
    var bytes: ByteArray,
  )

  private class MutableStringPool(
    val strings: MutableList<String>,
    val isUtf8: Boolean,
    val originalFlags: Int,
    val styleCount: Int,
    val styleOffsets: IntArray,
    val stylesRawData: ByteArray,
  ) {
    fun getOrNull(index: Int): String? =
      if (index in strings.indices) strings[index] else null

    fun findOrAdd(value: String): Int {
      val existing = strings.indexOf(value)
      if (existing >= 0) return existing
      strings.add(value)
      return strings.lastIndex
    }

    fun appendNew(value: String): Int {
      strings.add(value)
      return strings.lastIndex
    }

    fun encode(): ByteArray {
      val stringCount = strings.size
      val offsets = IntArray(stringCount)
      val stringDataStream = ByteArrayOutputStream()

      for (i in 0 until stringCount) {
        offsets[i] = stringDataStream.size()
        val s = strings[i]
        if (isUtf8) {
          writeUtf8Length(stringDataStream, s.length)
          val utf8Bytes = s.toByteArray(Charsets.UTF_8)
          writeUtf8Length(stringDataStream, utf8Bytes.size)
          stringDataStream.write(utf8Bytes)
          stringDataStream.write(0)
        } else {
          writeUtf16Length(stringDataStream, s.length)
          val utf16Bytes = s.toByteArray(Charsets.UTF_16LE)
          stringDataStream.write(utf16Bytes)
          stringDataStream.write(0)
          stringDataStream.write(0)
        }
      }

      // Pad string data to 4-byte boundary
      while (stringDataStream.size() % 4 != 0) {
        stringDataStream.write(0)
      }
      val stringDataBytes = stringDataStream.toByteArray()

      val headerSize = 28
      val stringsStart = headerSize + stringCount * 4 + styleCount * 4
      val stylesStart = if (styleCount > 0) stringsStart + stringDataBytes.size else 0
      val unpaddedTotal =
        stringsStart + stringDataBytes.size + if (styleCount > 0) stylesRawData.size else 0
      val paddingNeeded = (4 - (unpaddedTotal % 4)) % 4
      val totalChunkSize = unpaddedTotal + paddingNeeded

      // Clear FLAG_SORTED since strings may have been appended
      val flags = (originalFlags and FLAG_SORTED.inv()) or (if (isUtf8) FLAG_UTF8 else 0)

      val buf = ByteBuffer.allocate(totalChunkSize).order(ByteOrder.LITTLE_ENDIAN)
      buf.putShort(RES_STRING_POOL_TYPE.toShort())
      buf.putShort(headerSize.toShort())
      buf.putInt(totalChunkSize)
      buf.putInt(stringCount)
      buf.putInt(styleCount)
      buf.putInt(flags)
      buf.putInt(stringsStart)
      buf.putInt(stylesStart)

      for (offset in offsets) {
        buf.putInt(offset)
      }
      for (sOffset in styleOffsets) {
        buf.putInt(sOffset)
      }
      buf.put(stringDataBytes)
      if (styleCount > 0 && stylesRawData.isNotEmpty()) {
        buf.put(stylesRawData)
      }
      repeat(paddingNeeded) { buf.put(0) }
      return buf.array()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
      if (length > 0x7F) {
        out.write(((length shr 8) and 0x7F) or 0x80)
        out.write(length and 0xFF)
      } else {
        out.write(length and 0x7F)
      }
    }

    private fun writeUtf16Length(out: ByteArrayOutputStream, length: Int) {
      if (length > 0x7FFF) {
        val high = ((length shr 16) and 0x7FFF) or 0x8000
        val low = length and 0xFFFF
        out.write(high and 0xFF)
        out.write((high shr 8) and 0xFF)
        out.write(low and 0xFF)
        out.write((low shr 8) and 0xFF)
      } else {
        out.write(length and 0xFF)
        out.write((length shr 8) and 0xFF)
      }
    }
  }

  private fun parseStringPool(chunkBytes: ByteArray): MutableStringPool {
    val buf = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
    val type = buf.short.toInt() and 0xFFFF
    require(type == RES_STRING_POOL_TYPE) { "Invalid string pool chunk type: 0x${type.toString(16)}" }
    val headerSize = buf.short.toInt() and 0xFFFF
    val chunkSize = buf.int
    val stringCount = buf.int
    val styleCount = buf.int
    val flags = buf.int
    val stringsStart = buf.int
    val stylesStart = buf.int

    val isUtf8 = (flags and FLAG_UTF8) != 0

    buf.position(headerSize)
    val stringOffsets = IntArray(stringCount) { buf.int }
    val styleOffsets = IntArray(styleCount) { buf.int }

    val strings = ArrayList<String>(stringCount)
    for (i in 0 until stringCount) {
      val startPos = stringsStart + stringOffsets[i]
      if (startPos < 0 || startPos >= chunkBytes.size) {
        strings.add("")
        continue
      }
      if (isUtf8) {
        var p = startPos
        val u16First = chunkBytes[p++].toInt() and 0xFF
        if ((u16First and 0x80) != 0) p++
        var u8Len = chunkBytes[p++].toInt() and 0xFF
        if ((u8Len and 0x80) != 0) {
          u8Len = ((u8Len and 0x7F) shl 8) or (chunkBytes[p++].toInt() and 0xFF)
        }
        val safeLen = u8Len.coerceIn(0, (chunkBytes.size - p).coerceAtLeast(0))
        strings.add(String(chunkBytes, p, safeLen, Charsets.UTF_8))
      } else {
        var p = startPos
        var u16Len = readUInt16LE(chunkBytes, p)
        p += 2
        if ((u16Len and 0x8000) != 0) {
          val low = readUInt16LE(chunkBytes, p)
          p += 2
          u16Len = ((u16Len and 0x7FFF) shl 16) or low
        }
        val byteLen = (u16Len * 2).coerceIn(0, (chunkBytes.size - p).coerceAtLeast(0))
        strings.add(String(chunkBytes, p, byteLen, Charsets.UTF_16LE))
      }
    }

    val stylesRawData =
      if (styleCount > 0 && stylesStart in 1 until chunkSize) {
        chunkBytes.copyOfRange(stylesStart, chunkSize.coerceAtMost(chunkBytes.size))
      } else {
        ByteArray(0)
      }

    return MutableStringPool(
      strings = strings,
      isUtf8 = isUtf8,
      originalFlags = flags,
      styleCount = styleCount,
      styleOffsets = styleOffsets,
      stylesRawData = stylesRawData,
    )
  }

  private fun parseChunks(axmlBytes: ByteArray): List<RawChunk> {
    require(axmlBytes.size >= 8) { "Invalid binary AndroidManifest.xml: too short" }
    val header = ByteBuffer.wrap(axmlBytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
    val rootType = header.short.toInt() and 0xFFFF
    val rootHeaderSize = header.short.toInt() and 0xFFFF
    val rootSize = header.int
    require(rootType == RES_XML_TYPE) {
      "Not a valid Android binary XML file (header type: 0x${rootType.toString(16)})"
    }

    val limit = rootSize.coerceAtMost(axmlBytes.size)
    val chunks = mutableListOf<RawChunk>()
    var offset = rootHeaderSize
    while (offset + 8 <= limit) {
      val chunkBuf = ByteBuffer.wrap(axmlBytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
      val type = chunkBuf.short.toInt() and 0xFFFF
      val headerSize = chunkBuf.short.toInt() and 0xFFFF
      val size = chunkBuf.int
      if (size < 8 || offset + size > axmlBytes.size) break
      chunks.add(RawChunk(type, headerSize, axmlBytes.copyOfRange(offset, offset + size)))
      offset += size
    }
    return chunks
  }

  private fun parseResourceMap(chunkBytes: ByteArray): MutableList<Int> {
    val buf = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
    val type = buf.short.toInt() and 0xFFFF
    val headerSize = buf.short.toInt() and 0xFFFF
    val size = buf.int
    if (type != RES_XML_RESOURCE_MAP_TYPE || size < headerSize) return mutableListOf()
    val count = (size - headerSize) / 4
    buf.position(headerSize)
    return MutableList(count) { buf.int }
  }

  private fun encodeResourceMap(resIds: List<Int>): ByteArray {
    val headerSize = 8
    val totalSize = headerSize + resIds.size * 4
    val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
    buf.putShort(headerSize.toShort())
    buf.putInt(totalSize)
    for (id in resIds) {
      buf.putInt(id)
    }
    return buf.array()
  }

  private data class ParsedStartElement(
    val headerSize: Int,
    val lineNumber: Int,
    val comment: Int,
    val ns: Int,
    val name: Int,
    val attributeStart: Int,
    val attributeSize: Int,
    var idIndex: Int,
    var classIndex: Int,
    var styleIndex: Int,
    val attributes: MutableList<AxmlAttribute>,
  )

  private fun parseStartElement(chunkBytes: ByteArray): ParsedStartElement {
    val buf = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
    val type = buf.short.toInt() and 0xFFFF
    require(type == RES_XML_START_ELEMENT_TYPE)
    val headerSize = buf.short.toInt() and 0xFFFF
    buf.int // chunk size
    val lineNumber = buf.int
    val comment = buf.int

    buf.position(headerSize)
    val ns = buf.int
    val name = buf.int
    val attrStart = buf.short.toInt() and 0xFFFF
    val attrSize = buf.short.toInt() and 0xFFFF
    val attrCount = buf.short.toInt() and 0xFFFF
    val idIndex = buf.short.toInt() and 0xFFFF
    val classIndex = buf.short.toInt() and 0xFFFF
    val styleIndex = buf.short.toInt() and 0xFFFF

    val attrs = ArrayList<AxmlAttribute>(attrCount)
    val attrsBase = headerSize + attrStart
    val step = if (attrSize >= 20) attrSize else 20
    for (i in 0 until attrCount) {
      val pos = attrsBase + i * step
      if (pos + 20 > chunkBytes.size) break
      buf.position(pos)
      val aNs = buf.int
      val aName = buf.int
      val aRawValue = buf.int
      val tvSize = buf.short.toInt() and 0xFFFF
      val tvRes0 = buf.get().toInt() and 0xFF
      val tvType = buf.get().toInt() and 0xFF
      val tvData = buf.int
      attrs.add(AxmlAttribute(aNs, aName, aRawValue, tvSize, tvRes0, tvType, tvData))
    }

    return ParsedStartElement(
      headerSize = headerSize,
      lineNumber = lineNumber,
      comment = comment,
      ns = ns,
      name = name,
      attributeStart = 0x0014,
      attributeSize = 0x0014,
      idIndex = idIndex,
      classIndex = classIndex,
      styleIndex = styleIndex,
      attributes = attrs,
    )
  }

  private fun encodeStartElement(el: ParsedStartElement): ByteArray {
    val headerSize = 16
    val extSize = 20
    val attrSize = 20
    val totalSize = headerSize + extSize + el.attributes.size * attrSize
    val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
    buf.putShort(headerSize.toShort())
    buf.putInt(totalSize)
    buf.putInt(el.lineNumber)
    buf.putInt(el.comment)

    buf.putInt(el.ns)
    buf.putInt(el.name)
    buf.putShort(extSize.toShort())
    buf.putShort(attrSize.toShort())
    buf.putShort(el.attributes.size.toShort())
    buf.putShort(el.idIndex.toShort())
    buf.putShort(el.classIndex.toShort())
    buf.putShort(el.styleIndex.toShort())

    for (a in el.attributes) {
      buf.putInt(a.ns)
      buf.putInt(a.name)
      buf.putInt(a.rawValue)
      buf.putShort(8.toShort())
      buf.put(0.toByte())
      buf.put(a.dataType.toByte())
      buf.putInt(a.data)
    }
    return buf.array()
  }

  private fun encodeEndElement(lineNumber: Int, ns: Int, name: Int): ByteArray {
    val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(RES_XML_END_ELEMENT_TYPE.toShort())
    buf.putShort(16.toShort())
    buf.putInt(24)
    buf.putInt(lineNumber)
    buf.putInt(-1)
    buf.putInt(ns)
    buf.putInt(name)
    return buf.array()
  }

  /**
   * Parses binary AndroidManifest.xml into structured metadata and a formatted XML preview.
   */
  fun parseManifestInfo(axmlBytes: ByteArray): ParsedManifestInfo {
    val chunks = parseChunks(axmlBytes)
    val stringPoolChunk =
      chunks.firstOrNull { it.type == RES_STRING_POOL_TYPE }
        ?: throw IllegalArgumentException("Missing string pool in AndroidManifest.xml")
    val pool = parseStringPool(stringPoolChunk.bytes)
    val resMapChunk = chunks.firstOrNull { it.type == RES_XML_RESOURCE_MAP_TYPE }
    val resIds = if (resMapChunk != null) parseResourceMap(resMapChunk.bytes) else emptyList()

    var packageName = ""
    var appLabel = ""
    var isLabelResourceRef = false
    var versionCode = 1
    var versionName = "1.0"
    var installLocation = -1
    var minSdkVersion = 26
    var targetSdkVersion = 34
    val permissions = mutableListOf<String>()
    val activities = mutableListOf<String>()
    val services = mutableListOf<String>()
    val receivers = mutableListOf<String>()

    val xmlBuilder = StringBuilder()
    xmlBuilder.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
    var indent = 0

    for (chunk in chunks) {
      when (chunk.type) {
        RES_XML_START_ELEMENT_TYPE -> {
          val el = parseStartElement(chunk.bytes)
          val tagName = pool.getOrNull(el.name).orEmpty()
          val pad = "  ".repeat(indent.coerceAtLeast(0))
          xmlBuilder.append("$pad<$tagName")

          if (tagName == "manifest") {
            xmlBuilder.append("\n$pad  xmlns:android=\"$ANDROID_NAMESPACE\"")
          }

          for (attr in el.attributes) {
            val attrName = pool.getOrNull(attr.name).orEmpty()
            val attrResId = if (attr.name in resIds.indices) resIds[attr.name] else 0
            val nsUri = pool.getOrNull(attr.ns)
            val prefix = if (nsUri == ANDROID_NAMESPACE) "android:" else ""
            val displayVal = formatAttrValue(attr, pool)
            xmlBuilder.append("\n$pad  $prefix$attrName=\"$displayVal\"")

            when (tagName) {
              "manifest" -> {
                when {
                  attrName == "package" && attr.ns == -1 -> {
                    packageName = resolveStringValue(attr, pool)
                  }
                  attrResId == ATTR_VERSION_CODE || attrName == "versionCode" -> {
                    versionCode = attr.data
                  }
                  attrResId == ATTR_VERSION_NAME || attrName == "versionName" -> {
                    versionName = resolveStringValue(attr, pool)
                  }
                  attrResId == ATTR_INSTALL_LOCATION || attrName == "installLocation" -> {
                    installLocation = attr.data
                  }
                }
              }
              "application" -> {
                if (attrResId == ATTR_LABEL || attrName == "label") {
                  if (attr.dataType == TYPE_REFERENCE) {
                    isLabelResourceRef = true
                    appLabel = "@0x${attr.data.toUInt().toString(16)}"
                  } else {
                    isLabelResourceRef = false
                    appLabel = resolveStringValue(attr, pool)
                  }
                }
              }
              "uses-sdk" -> {
                when {
                  attrResId == ATTR_MIN_SDK_VERSION || attrName == "minSdkVersion" -> {
                    minSdkVersion = attr.data
                  }
                  attrResId == ATTR_TARGET_SDK_VERSION || attrName == "targetSdkVersion" -> {
                    targetSdkVersion = attr.data
                  }
                }
              }
              "uses-permission" -> {
                if (attrResId == ATTR_NAME || attrName == "name") {
                  val pName = resolveStringValue(attr, pool)
                  if (pName.isNotBlank()) permissions.add(pName)
                }
              }
              "activity", "activity-alias" -> {
                if (attrResId == ATTR_NAME || attrName == "name") {
                  val aName = resolveStringValue(attr, pool)
                  if (aName.isNotBlank()) activities.add(aName)
                }
              }
              "service" -> {
                if (attrResId == ATTR_NAME || attrName == "name") {
                  val sName = resolveStringValue(attr, pool)
                  if (sName.isNotBlank()) services.add(sName)
                }
              }
              "receiver" -> {
                if (attrResId == ATTR_NAME || attrName == "name") {
                  val rName = resolveStringValue(attr, pool)
                  if (rName.isNotBlank()) receivers.add(rName)
                }
              }
            }
          }
          xmlBuilder.appendLine(">")
          indent++
        }
        RES_XML_END_ELEMENT_TYPE -> {
          indent = (indent - 1).coerceAtLeast(0)
          val buf = ByteBuffer.wrap(chunk.bytes).order(ByteOrder.LITTLE_ENDIAN)
          if (chunk.bytes.size >= 24) {
            buf.position(20)
            val nameIdx = buf.int
            val tagName = pool.getOrNull(nameIdx).orEmpty()
            val pad = "  ".repeat(indent)
            xmlBuilder.appendLine("$pad</$tagName>")
          }
        }
      }
    }

    return ParsedManifestInfo(
      packageName = packageName,
      appLabel = appLabel,
      isLabelResourceRef = isLabelResourceRef,
      versionCode = versionCode,
      versionName = versionName,
      installLocation = installLocation,
      minSdkVersion = minSdkVersion,
      targetSdkVersion = targetSdkVersion,
      permissions = permissions.distinct(),
      activities = activities.distinct(),
      services = services.distinct(),
      receivers = receivers.distinct(),
      xmlPreview = xmlBuilder.toString(),
    )
  }

  /**
   * Patches the binary AndroidManifest.xml according to [patch] and returns a valid
   * rebuilt AXML byte array with updated string pool, resource map, chunk sizes, and padding.
   */
  fun patch(axmlBytes: ByteArray, patch: ManifestPatchSpec): ByteArray {
    val chunks = parseChunks(axmlBytes).toMutableList()
    val stringPoolIndex = chunks.indexOfFirst { it.type == RES_STRING_POOL_TYPE }
    require(stringPoolIndex >= 0) { "Binary AndroidManifest.xml missing string pool" }

    val pool = parseStringPool(chunks[stringPoolIndex].bytes)
    val resMapIndex = chunks.indexOfFirst { it.type == RES_XML_RESOURCE_MAP_TYPE }
    val resIds =
      if (resMapIndex >= 0) parseResourceMap(chunks[resMapIndex].bytes) else mutableListOf()

    val androidNsIdx = pool.findOrAdd(ANDROID_NAMESPACE)

    fun ensureAttrNameIndex(attrName: String, attrResId: Int): Int {
      val existingByResId = resIds.indexOf(attrResId)
      if (existingByResId >= 0) return existingByResId

      // Check if name already exists within resIds range with matching or zero ID
      for (i in resIds.indices) {
        if (pool.getOrNull(i) == attrName && (resIds[i] == attrResId || resIds[i] == 0)) {
          resIds[i] = attrResId
          return i
        }
      }

      // Append new string and extend resIds with 0 padding so existing string indices never shift
      val newIdx = pool.appendNew(attrName)
      while (resIds.size <= newIdx) {
        resIds.add(0)
      }
      resIds[newIdx] = attrResId
      return newIdx
    }

    fun setOrAddStringAttr(
      el: ParsedStartElement,
      nsIdx: Int,
      attrName: String,
      attrResId: Int?,
      newValue: String,
    ) {
      val newValIdx = pool.appendNew(newValue)
      val target =
        el.attributes.firstOrNull { a ->
          val aResId = if (a.name in resIds.indices) resIds[a.name] else 0
          val aName = pool.getOrNull(a.name)
          if (attrResId != null) {
            aResId == attrResId || (a.ns == nsIdx && aName == attrName)
          } else {
            a.ns == nsIdx && aName == attrName
          }
        }
      if (target != null) {
        target.rawValue = newValIdx
        target.dataType = TYPE_STRING
        target.data = newValIdx
      } else {
        val nameIdx =
          if (attrResId != null) ensureAttrNameIndex(attrName, attrResId)
          else pool.findOrAdd(attrName)
        el.attributes.add(
          AxmlAttribute(
            ns = nsIdx,
            name = nameIdx,
            rawValue = newValIdx,
            valueSize = 8,
            valueRes0 = 0,
            dataType = TYPE_STRING,
            data = newValIdx,
          )
        )
      }
    }

    fun setOrAddIntAttr(
      el: ParsedStartElement,
      nsIdx: Int,
      attrName: String,
      attrResId: Int,
      intValue: Int,
    ) {
      val target =
        el.attributes.firstOrNull { a ->
          val aResId = if (a.name in resIds.indices) resIds[a.name] else 0
          val aName = pool.getOrNull(a.name)
          aResId == attrResId || (a.ns == nsIdx && aName == attrName)
        }
      if (target != null) {
        target.rawValue = -1
        target.dataType = TYPE_INT_DEC
        target.data = intValue
      } else {
        val nameIdx = ensureAttrNameIndex(attrName, attrResId)
        el.attributes.add(
          AxmlAttribute(
            ns = nsIdx,
            name = nameIdx,
            rawValue = -1,
            valueSize = 8,
            valueRes0 = 0,
            dataType = TYPE_INT_DEC,
            data = intValue,
          )
        )
      }
    }

    fun removeAttr(el: ParsedStartElement, attrName: String, attrResId: Int) {
      val idx =
        el.attributes.indexOfFirst { a ->
          val aResId = if (a.name in resIds.indices) resIds[a.name] else 0
          val aName = pool.getOrNull(a.name)
          aResId == attrResId || aName == attrName
        }
      if (idx >= 0) {
        el.attributes.removeAt(idx)
        val oneBased = idx + 1
        if (el.idIndex == oneBased) el.idIndex = 0 else if (el.idIndex > oneBased) el.idIndex--
        if (el.classIndex == oneBased) el.classIndex = 0
        else if (el.classIndex > oneBased) el.classIndex--
        if (el.styleIndex == oneBased) el.styleIndex = 0
        else if (el.styleIndex > oneBased) el.styleIndex--
      }
    }

    var foundUsesSdk = false
    var manifestChunkIdx = -1

    for (i in chunks.indices) {
      val chunk = chunks[i]
      if (chunk.type != RES_XML_START_ELEMENT_TYPE) continue
      val el = parseStartElement(chunk.bytes)
      val tagName = pool.getOrNull(el.name) ?: continue
      var modified = false

      when (tagName) {
        "manifest" -> {
          manifestChunkIdx = i
          if (!patch.packageName.isNullOrBlank()) {
            setOrAddStringAttr(el, -1, "package", null, patch.packageName.trim())
            modified = true
          }
          if (patch.versionCode != null) {
            setOrAddIntAttr(el, androidNsIdx, "versionCode", ATTR_VERSION_CODE, patch.versionCode)
            modified = true
          }
          if (!patch.versionName.isNullOrBlank()) {
            setOrAddStringAttr(
              el,
              androidNsIdx,
              "versionName",
              ATTR_VERSION_NAME,
              patch.versionName.trim(),
            )
            modified = true
          }
          if (patch.installLocation != null) {
            if (patch.installLocation < 0) {
              removeAttr(el, "installLocation", ATTR_INSTALL_LOCATION)
            } else {
              setOrAddIntAttr(
                el,
                androidNsIdx,
                "installLocation",
                ATTR_INSTALL_LOCATION,
                patch.installLocation,
              )
            }
            modified = true
          }
        }
        "application" -> {
          if (!patch.appLabel.isNullOrBlank()) {
            setOrAddStringAttr(el, androidNsIdx, "label", ATTR_LABEL, patch.appLabel.trim())
            modified = true
          }
        }
        "uses-sdk" -> {
          foundUsesSdk = true
          if (patch.minSdkVersion != null) {
            setOrAddIntAttr(
              el,
              androidNsIdx,
              "minSdkVersion",
              ATTR_MIN_SDK_VERSION,
              patch.minSdkVersion,
            )
            modified = true
          }
          if (patch.targetSdkVersion != null) {
            setOrAddIntAttr(
              el,
              androidNsIdx,
              "targetSdkVersion",
              ATTR_TARGET_SDK_VERSION,
              patch.targetSdkVersion,
            )
            modified = true
          }
        }
      }

      if (modified) {
        chunk.bytes = encodeStartElement(el)
      }
    }

    // If the APK had no <uses-sdk> element and the user edited minSdk/targetSdk, insert one cleanly
    if (!foundUsesSdk && manifestChunkIdx >= 0 && (patch.minSdkVersion != null || patch.targetSdkVersion != null)) {
      val usesSdkTagIdx = pool.findOrAdd("uses-sdk")
      val newEl =
        ParsedStartElement(
          headerSize = 16,
          lineNumber = 2,
          comment = -1,
          ns = -1,
          name = usesSdkTagIdx,
          attributeStart = 0x0014,
          attributeSize = 0x0014,
          idIndex = 0,
          classIndex = 0,
          styleIndex = 0,
          attributes = mutableListOf(),
        )
      if (patch.minSdkVersion != null) {
        setOrAddIntAttr(
          newEl,
          androidNsIdx,
          "minSdkVersion",
          ATTR_MIN_SDK_VERSION,
          patch.minSdkVersion,
        )
      }
      if (patch.targetSdkVersion != null) {
        setOrAddIntAttr(
          newEl,
          androidNsIdx,
          "targetSdkVersion",
          ATTR_TARGET_SDK_VERSION,
          patch.targetSdkVersion,
        )
      }
      val startChunk = RawChunk(RES_XML_START_ELEMENT_TYPE, 16, encodeStartElement(newEl))
      val endChunk = RawChunk(RES_XML_END_ELEMENT_TYPE, 16, encodeEndElement(2, -1, usesSdkTagIdx))
      chunks.add(manifestChunkIdx + 1, startChunk)
      chunks.add(manifestChunkIdx + 2, endChunk)
    }

    // Rebuild StringPool and ResourceMap chunks
    chunks[stringPoolIndex].bytes = pool.encode()
    if (resMapIndex >= 0) {
      chunks[resMapIndex].bytes = encodeResourceMap(resIds)
    } else if (resIds.isNotEmpty()) {
      chunks.add(stringPoolIndex + 1, RawChunk(RES_XML_RESOURCE_MAP_TYPE, 8, encodeResourceMap(resIds)))
    }

    // Assemble complete binary XML with accurate total file size in 8-byte root header
    val totalBodySize = chunks.sumOf { it.bytes.size }
    val totalFileSize = 8 + totalBodySize
    val out = ByteBuffer.allocate(totalFileSize).order(ByteOrder.LITTLE_ENDIAN)
    out.putShort(RES_XML_TYPE.toShort())
    out.putShort(8.toShort())
    out.putInt(totalFileSize)
    for (chunk in chunks) {
      out.put(chunk.bytes)
    }
    return out.array()
  }

  private fun resolveStringValue(attr: AxmlAttribute, pool: MutableStringPool): String {
    if (attr.dataType == TYPE_STRING) {
      return pool.getOrNull(attr.data) ?: pool.getOrNull(attr.rawValue).orEmpty()
    }
    return pool.getOrNull(attr.rawValue).orEmpty()
  }

  private fun formatAttrValue(attr: AxmlAttribute, pool: MutableStringPool): String {
    return when (attr.dataType) {
      TYPE_STRING -> resolveStringValue(attr, pool)
      TYPE_REFERENCE -> "@0x${attr.data.toUInt().toString(16)}"
      TYPE_INT_DEC -> attr.data.toString()
      TYPE_INT_HEX -> "0x${attr.data.toUInt().toString(16)}"
      TYPE_INT_BOOLEAN -> (attr.data != 0).toString()
      else -> pool.getOrNull(attr.rawValue) ?: "0x${attr.data.toUInt().toString(16)}"
    }
  }

  private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
    if (offset + 1 >= bytes.size) return 0
    return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
  }
}
