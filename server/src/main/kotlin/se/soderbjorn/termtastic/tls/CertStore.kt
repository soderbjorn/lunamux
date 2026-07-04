/**
 * Self-signed TLS keystore lifecycle for the Termtastic server.
 *
 * On first boot, generates an RSA-2048 self-signed certificate with SAN entries
 * for `localhost`, `127.0.0.1`, and `::1`, persists it as a PKCS12 keystore at
 * [AppPaths.tlsDir]`/server.p12`, stores the random keystore password next to
 * it (`keystore.pass`, file mode 0600 on POSIX), and writes a hex SHA-256
 * fingerprint sidecar (`server.p12.fingerprint`) that the Electron loopback
 * `certificate-error` handler reads at launch. On subsequent boots, the
 * existing keystore is loaded verbatim — rotation is manual (delete the files
 * and restart, which forces every pinned client to re-pair via the existing
 * `DeviceAuth.ApprovalDialog`).
 *
 * Called by [se.soderbjorn.termtastic.main] immediately before the Ktor
 * `embeddedServer` is built; the returned bundle is fed into `sslConnector`.
 *
 * @see AppPaths.tlsDir
 * @see se.soderbjorn.termtastic.auth.DeviceAuth
 */
package se.soderbjorn.termtastic.tls

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.AppPaths
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Bundle returned by [CertStore.ensureKeystore] containing everything the
 * Ktor `sslConnector` needs to bind a TLS listener, plus the SHA-256
 * fingerprint of the leaf certificate (logged at startup and written to a
 * sidecar file so operators and Electron can verify what pinned clients
 * should be locked to).
 *
 * @property keystore the loaded PKCS12 [KeyStore] containing the cert and
 *   private key entry under [alias].
 * @property alias the alias under which the keypair is stored.
 * @property password the in-memory password chars used for both the keystore
 *   and the private key entry. Treated as a secret; do not log.
 * @property sha256Fingerprint lowercase hex SHA-256 of the leaf certificate's
 *   DER encoding (64 chars, no separators).
 */
data class KeyStoreBundle(
    val keystore: KeyStore,
    val alias: String,
    val password: CharArray,
    val sha256Fingerprint: String,
)

/**
 * Loads the existing TLS keystore from [AppPaths.tlsDir] or generates a fresh
 * self-signed one if missing / unreadable.
 *
 * Called once per server boot by [se.soderbjorn.termtastic.main]; the result
 * is consumed by Ktor's `sslConnector`.
 *
 * @see KeyStoreBundle
 */
object CertStore {

    private val log = LoggerFactory.getLogger(CertStore::class.java)
    private const val ALIAS = "termtastic"
    private const val KEYSTORE_FILE = "server.p12"
    private const val PASSWORD_FILE = "keystore.pass"
    private const val FINGERPRINT_FILE = "server.p12.fingerprint"
    private const val LEAF_PEM_FILE = "server-leaf.pem"

    /**
     * Absolute path of the exported PEM copy of the TLS leaf certificate
     * (written by [ensureKeystore] on every boot). Surfaced in the settings
     * dialog's MCP section so the user can point a Node-based MCP client at
     * it via `NODE_EXTRA_CA_CERTS`, making the self-signed cert trusted.
     *
     * @return the sidecar PEM file (may not exist before the first boot).
     */
    fun leafPemFile(): java.io.File = AppPaths.tlsDir().resolve(LEAF_PEM_FILE)

    /**
     * Ensure a usable keystore exists on disk and return its in-memory form.
     *
     * If `tls/server.p12` and `tls/keystore.pass` both exist and the keystore
     * loads, returns it as-is. Otherwise generates a fresh RSA-2048 self-signed
     * cert (SANs `localhost`, `127.0.0.1`, `::1`; validity 3650 days), writes
     * both files with POSIX `0600` permissions, refreshes the fingerprint
     * sidecar, and returns the new bundle. The sidecar is rewritten on every
     * load so manual edits of the keystore are reflected after a restart.
     *
     * Regeneration is the only "rotation" mechanism: deleting the keystore and
     * restarting the server forces every pinned client to re-pair via the
     * existing `DeviceAuth.ApprovalDialog`. This is by design — pinning detects
     * the change precisely because the cert is new.
     *
     * @return the loaded or freshly generated [KeyStoreBundle].
     */
    fun ensureKeystore(): KeyStoreBundle {
        val dir = AppPaths.tlsDir().apply { mkdirs() }
        val ksFile = dir.resolve(KEYSTORE_FILE)
        val pwFile = dir.resolve(PASSWORD_FILE)
        val fpFile = dir.resolve(FINGERPRINT_FILE)

        if (ksFile.isFile && pwFile.isFile) {
            runCatching {
                val pw = pwFile.readText().trim().toCharArray()
                val ks = KeyStore.getInstance("PKCS12").apply {
                    ksFile.inputStream().use { load(it, pw) }
                }
                val bundle = KeyStoreBundle(ks, ALIAS, pw, computeFingerprint(ks))
                writeFingerprintSidecar(fpFile, bundle.sha256Fingerprint)
                writeLeafPemSidecar(ks)
                logFingerprint(bundle, regenerated = false)
                return bundle
            }.onFailure {
                log.warn("Failed to load existing keystore at ${ksFile.absolutePath}; regenerating.", it)
            }
        }

        val pw = randomPasswordChars()
        val ks = buildKeyStore {
            certificate(ALIAS) {
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                keySizeInBits = 2048
                password = String(pw)
                domains = listOf("localhost", "127.0.0.1", "::1")
                daysValid = 3650
            }
        }

        FileOutputStream(ksFile).use { ks.store(it, pw) }
        pwFile.writeText(String(pw))
        applyPosixSecretMode(pwFile.toPath())
        applyPosixSecretMode(ksFile.toPath())

        val bundle = KeyStoreBundle(ks, ALIAS, pw, computeFingerprint(ks))
        writeFingerprintSidecar(fpFile, bundle.sha256Fingerprint)
        writeLeafPemSidecar(ks)
        logFingerprint(bundle, regenerated = true)
        return bundle
    }

    /**
     * Export the leaf certificate as a PEM sidecar (`server-leaf.pem`) next
     * to the keystore. Rewritten on every boot so it always matches the
     * live keystore. The PEM holds only the public certificate — no key
     * material — so default file permissions are fine; it exists so MCP /
     * Node clients can trust the self-signed cert via `NODE_EXTRA_CA_CERTS`.
     *
     * @param ks the loaded keystore whose [ALIAS] leaf gets exported.
     * @see leafPemFile
     */
    private fun writeLeafPemSidecar(ks: KeyStore) {
        runCatching {
            val cert = ks.getCertificate(ALIAS)
                ?: error("Keystore is missing alias '$ALIAS' — cannot export PEM.")
            val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(cert.encoded)
            leafPemFile().writeText(
                "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
            )
        }.onFailure { log.warn("Failed to write leaf PEM sidecar", it) }
    }

    /**
     * Compute the SHA-256 fingerprint of the leaf certificate stored under
     * [ALIAS] in [ks].
     *
     * @param ks the loaded keystore.
     * @return lowercase hex string, 64 chars, no separators.
     */
    private fun computeFingerprint(ks: KeyStore): String {
        val cert = ks.getCertificate(ALIAS)
            ?: error("Keystore is missing alias '$ALIAS' — cannot derive fingerprint.")
        val der = cert.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate a 32-byte random hex string suitable as a keystore password.
     *
     * @return 64 hex characters drawn from [SecureRandom].
     */
    private fun randomPasswordChars(): CharArray {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
    }

    /**
     * Lock [path] to owner read/write only on POSIX filesystems. Silently
     * ignored on Windows where filesystem ACLs are best-effort.
     */
    private fun applyPosixSecretMode(path: java.nio.file.Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    /**
     * Write the hex fingerprint sidecar that the Electron loopback
     * `certificate-error` handler reads at launch so the bundled app can
     * silently accept the self-signed cert without going through the
     * `ApprovalDialog`.
     */
    private fun writeFingerprintSidecar(file: java.io.File, hex: String) {
        runCatching { file.writeText(hex) }
            .onFailure { log.warn("Failed to write fingerprint sidecar at ${file.absolutePath}", it) }
    }

    /**
     * Log the loaded fingerprint at INFO so operators / scripts can verify
     * what pinned clients should be locked to.
     */
    private fun logFingerprint(bundle: KeyStoreBundle, regenerated: Boolean) {
        val verb = if (regenerated) "Generated" else "Loaded"
        log.info("$verb TLS keystore. SHA-256 fingerprint: ${bundle.sha256Fingerprint}")
    }
}
