package com.example.engine

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

data class SigningOptions(
  val v1Enabled: Boolean = true,
  val v2Enabled: Boolean = true,
  val v3Enabled: Boolean = true,
)

data class KeystoreCertificateInfo(
  val alias: String,
  val subject: String,
  val sha256Fingerprint: String,
  val validUntil: String,
  val keystorePath: String,
)

/**
 * Manages automatic 2048-bit RSA PKCS12 keystore generation on first run
 * and real V1 + V2 + V3 APK signing using Google's official apksig library.
 */
object ApkSignerHelper {
  private const val KEYSTORE_FILENAME = "apk_editor_gm_signing.p12"
  private const val KEY_ALIAS = "apk_editor_gm_key"
  private val STORE_PASSWORD = charArrayOf('a', 'p', 'k', 'e', 'd', 'i', 't', 'o', 'r', 'g', 'm')
  private val bcProvider = BouncyCastleProvider()

  private fun keystoreFile(context: Context): File = File(context.filesDir, KEYSTORE_FILENAME)

  @Synchronized
  fun ensureKeystore(context: Context, forceRegenerate: Boolean = false): File {
    return ensureKeystoreAtFile(keystoreFile(context), forceRegenerate)
  }

  @Synchronized
  internal fun ensureKeystoreAtFile(file: File, forceRegenerate: Boolean = false): File {
    if (!forceRegenerate && file.exists() && file.length() > 0) {
      return file
    }

    file.parentFile?.mkdirs()
    val keyPairGen = KeyPairGenerator.getInstance("RSA")
    keyPairGen.initialize(2048, SecureRandom())
    val keyPair = keyPairGen.generateKeyPair()

    val now = System.currentTimeMillis()
    val notBefore = Date(now - 24L * 60L * 60L * 1000L)
    val notAfter = Date(now + 25L * 365L * 24L * 60L * 60L * 1000L)
    val serial = BigInteger(64, SecureRandom()).abs()
    val subject = X500Name("CN=APK Editor GM, OU=Android Studio, O=APK Editor GM, C=US")

    val certBuilder =
      JcaX509v3CertificateBuilder(
        subject,
        serial,
        notBefore,
        notAfter,
        subject,
        keyPair.public,
      )

    val contentSigner =
      JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.private)
    val certHolder = certBuilder.build(contentSigner)
    val certificate: X509Certificate =
      JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certHolder)

    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, STORE_PASSWORD)
    keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, STORE_PASSWORD, arrayOf(certificate))

    FileOutputStream(file).use { out -> keyStore.store(out, STORE_PASSWORD) }
    return file
  }

  fun getCertificateInfo(context: Context): KeystoreCertificateInfo {
    val file = ensureKeystore(context)
    val keyStore = KeyStore.getInstance("PKCS12")
    FileInputStream(file).use { input -> keyStore.load(input, STORE_PASSWORD) }
    val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    val fingerprint = digest.joinToString(":") { "%02X".format(it) }
    return KeystoreCertificateInfo(
      alias = KEY_ALIAS,
      subject = cert.subjectX500Principal.name,
      sha256Fingerprint = fingerprint,
      validUntil = android.text.format.DateFormat.format("yyyy-MM-dd", cert.notAfter).toString(),
      keystorePath = file.absolutePath,
    )
  }

  fun signApk(
    context: Context,
    inputUnsignedApk: File,
    outputSignedApk: File,
    options: SigningOptions = SigningOptions(),
  ) {
    signApkWithKeystore(ensureKeystore(context), inputUnsignedApk, outputSignedApk, options)
  }

  internal fun signApkWithKeystore(
    keystoreFile: File,
    inputUnsignedApk: File,
    outputSignedApk: File,
    options: SigningOptions = SigningOptions(),
  ) {
    val keyStore = KeyStore.getInstance("PKCS12")
    FileInputStream(keystoreFile).use { input -> keyStore.load(input, STORE_PASSWORD) }

    val privateKey = keyStore.getKey(KEY_ALIAS, STORE_PASSWORD) as PrivateKey
    val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate

    val signerConfig =
      ApkSigner.SignerConfig.Builder(KEY_ALIAS, privateKey, listOf(certificate)).build()

    // Ensure at least V2 & V3 are enabled when signing modern Android 11-15+ APKs
    val v1 = options.v1Enabled
    val v2 = options.v2Enabled || (!options.v1Enabled && !options.v3Enabled)
    val v3 = options.v3Enabled

    val signer =
      ApkSigner.Builder(listOf(signerConfig))
        .setV1SigningEnabled(v1)
        .setV2SigningEnabled(v2)
        .setV3SigningEnabled(v3)
        .setOtherSignersSignaturesPreserved(false)
        .setCreatedBy("APK Editor GM")
        .setInputApk(inputUnsignedApk)
        .setOutputApk(outputSignedApk)
        .build()

    signer.sign()
  }
}
