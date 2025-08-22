package sevts.terminal.networking.ssl

import com.typesafe.scalalogging.LazyLogging
import ru.CryptoPro.JCP.JCP
import sevts.terminal.Injector

import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.{Certificate, CertificateFactory}
import java.util
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.jdk.CollectionConverters._

/**
  * Factory to create SSLContext configured for GOST (CryptoPro) stack.
  * The logic was extracted from WsClient to a separate, reusable class.
  */
case class GostSslContextFactory(injector: Injector)extends LazyLogging {

  def create(): SSLContext = {
    // Keep behavior identical to the original implementation
    System.setProperty("tls_prohibit_disabled_validation", "false")
    val crldpEnabled = injector.settings.ssl.enableCRLDP
    System.setProperty("com.sun.security.enableCRLDP", crldpEnabled.toString)
    System.setProperty("com.ibm.security.enableCRLDP", crldpEnabled.toString)

    // Add CryptoPro providers
    java.security.Security.addProvider(new JCP())
    java.security.Security.addProvider(new ru.CryptoPro.ssl.Provider())
    java.security.Security.addProvider(new ru.CryptoPro.Crypto.CryptoProvider())
    java.security.Security.addProvider(new ru.CryptoPro.reprov.RevCheck())

    val sslCtx = SSLContext.getInstance("GostTLSv1.2", "JTLS") // Secured context
    sslCtx.init(getGostKeyManagers(), getGostTrustManagers(), null)

    logger.info(s"Supported GOST cipher suites: ${sslCtx.getSupportedSSLParameters.getCipherSuites.mkString(", ")}")
    sslCtx
  }

  private def getGostKeyManagers() = {
    val factory = KeyManagerFactory.getInstance("GostX509")
    val pfxStore = KeyStore.getInstance(JCP.HD_STORE_NAME)
    pfxStore.load(null, null)
    val keyPassword = injector.settings.ssl.keystorePassword
    factory.init(pfxStore, keyPassword.toCharArray)
    factory.getKeyManagers
  }

  private def getGostTrustManagers() = {
    // Build in-memory trust store from PKCS#7 (gost.p7b) loaded either from classpath resource or from file system
    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(null, null)

    val (sourceDesc, is) = openGostP7b()

    try {
      val cf = CertificateFactory.getInstance("X.509")
      val certs = cf.generateCertificates(is).asInstanceOf[util.Collection[Certificate]]
      certs.asScala.zipWithIndex.foreach { case (c, i) =>
        val alias = c match {
          case xc: java.security.cert.X509Certificate => f"cert-$i%02d-${xc.getSubjectX500Principal.getName}"
          case _ => f"cert-$i%02d"
        }
        trustStore.setCertificateEntry(alias, c)
      }
      logger.info(s"[TLS] Loaded certificates from PKCS#7 (${sourceDesc}): ${certs.size()}")
    } finally {
      try is.close() catch {
        case _: Throwable =>
      }
    }

    val factory = TrustManagerFactory.getInstance("GostX509")
    factory.init(trustStore)
    factory.getTrustManagers
  }

  private def openGostP7b(): (String, java.io.InputStream) = {
    if (injector.settings.ssl.gostP7bFromResource) {
      val res = Option(classOf[GostSslContextFactory.type].getResourceAsStream("/certs/gost.p7b"))
      ("resource:/certs/gost.p7b", res.getOrElse(throw new IllegalStateException("Resource /certs/gost.p7b not found")))
    } else {
      val path = injector.settings.ssl.gostP7bFilePath
      if (path == null || path.trim.isEmpty)
        throw new IllegalArgumentException("platform5.server.remote.ssl.gostP7b.filePath is empty while fromResource=false")
      (s"file:" + path, new FileInputStream(path))
    }
  }
}
