package sevts.terminal.networking.websocket

import com.typesafe.scalalogging.LazyLogging
import ru.CryptoPro.JCP.JCP

import java.io.FileInputStream
import java.lang.management.ManagementFactory
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URL}
import java.security.cert.X509Certificate
import java.security.{KeyStore, Security}
import javax.net.ssl.{HttpsURLConnection, KeyManagerFactory, SSLContext, TrustManagerFactory}


object SSLTest extends LazyLogging {

  private def createGostSslContext(): SSLContext = {

    val runtimeMXBean = ManagementFactory.getRuntimeMXBean
    val vmVersion = runtimeMXBean.getVmVersion
    val vmName = runtimeMXBean.getVmName
    val vmVendor = runtimeMXBean.getVmVendor

    System.out.println("VM Name: " + vmName)
    System.out.println("VM Version: " + vmVersion)
    System.out.println("VM Vendor: " + vmVendor)

    System.setProperty("com.sun.security.enableCRLDP", "true")
    System.setProperty("com.ibm.security.enableCRLDP", "true")


    Security.addProvider(new JCP())
    Security.addProvider(new ru.CryptoPro.ssl.Provider())
    Security.addProvider(new ru.CryptoPro.Crypto.CryptoProvider())
    Security.addProvider(new ru.CryptoPro.reprov.RevCheck())

    def getGostKeyManagers() = {
      val factory = KeyManagerFactory.getInstance("GostX509")
      val pfxStore = KeyStore.getInstance(JCP.HD_STORE_NAME)
      pfxStore.load(null, null)
      factory.init(pfxStore, "123saveme".toCharArray)
      factory.getKeyManagers
    }


    def getGostTrustManager() = {
      val trustStore = KeyStore.getInstance(JCP.CERT_STORE_NAME, "JCP")
      trustStore.load(new FileInputStream("D:\\trust\\new.store"), "11111111".toCharArray)
      val factory = TrustManagerFactory.getInstance("GostX509")
      factory.init(trustStore)
      factory.getTrustManagers
    }


    val sslCtx = SSLContext.getInstance("GostTLSv1.2", "JTLS"); // Защищенный контекст
    sslCtx.init(getGostKeyManagers(), getGostTrustManager(), null)


    logger.info(s"Supported GOST cipher suites: ${sslCtx.getSupportedSSLParameters.getCipherSuites.mkString(", ")}")
    sslCtx
  }

  private def testConnection(url: URL, sslCtx: SSLContext): Unit = {
    // Создаем SSL factory.
    val sslSocketFactory = sslCtx.getSocketFactory
    var connection: HttpsURLConnection = null

    try {
      // Подключаемся и выводим информацию.
      connection = url.openConnection().asInstanceOf[HttpsURLConnection]
      connection.setSSLSocketFactory(sslSocketFactory)

      println(s"Response code: ${connection.getResponseCode}")
      println(s"Response message: ${connection.getResponseMessage}")
      println(s"Cipher suite: ${connection.getCipherSuite}")

      val localCert = connection.getLocalCertificates
      val serverCert = connection.getServerCertificates

      if (localCert != null && localCert.length > 0) { // ГОСТ fixed: JCP-1649
        println(s"Local certificate: ${localCert(0).asInstanceOf[X509Certificate].getSubjectDN}")
      }

      if (serverCert != null && serverCert.length > 0) {
        println(s"Server certificate: ${serverCert(0).asInstanceOf[X509Certificate].getSubjectDN}")
      }

    } finally {
      if (connection != null) {
        connection.disconnect()
      }
    }
  }

  def connect() = {
    try {
      val sslContext = createGostSslContext()

      logger.info("start connection")

      testConnection(new URL("https://2035gost.reg.events:443"), sslContext)

      logger.info("start  NEXGT connection")

      val request = HttpRequest.newBuilder()
        .uri(new URI("https://2035gost.reg.events"))
        .build()
      val httpClient = HttpClient.newBuilder().sslContext(sslContext).build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      logger.info(response.body())
    } catch {
      case e: Throwable =>
        logger.error(e.getMessage, e)
    }

  }

}
