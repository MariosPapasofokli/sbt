package xsbt
package boot

import java.io.File
import scala.util.control.NonFatal
import java.net.URI
import java.io.IOException
import Pre._

/** A wrapper around 'raw' static methods to meet the sbt application interface. */
class ServerApplication private (provider: xsbti.AppProvider) extends xsbti.AppMain {
  import ServerApplication._
  
  override def run(configuration: xsbti.AppConfiguration): xsbti.MainResult = {
    val serverMain = provider.entryPoint.asSubclass(ServerMainClass).newInstance
    val server = serverMain.start(configuration)
    System.out.println(s"${SERVER_SYNCH_TEXT}${server.uri}")
    server.awaitTermination()
  }
}
/** An object that lets us detect compatible "plain" applications and launch them reflectively. */
object ServerApplication {
  val SERVER_SYNCH_TEXT = "[SERVER-URI]"
  val ServerMainClass = classOf[xsbti.ServerMain]
  // TODO - We should also adapt friendly static methods into servers, perhaps...
  def isServerApplication(clazz: Class[_]): Boolean =
    ServerMainClass.isAssignableFrom(clazz)
  def apply(provider: xsbti.AppProvider): xsbti.AppMain = 
  	new ServerApplication(provider)
  
}
object ServerLocator {
  // TODO - Probably want to drop this to reduce classfile size
  private def locked[U](file: File)(f: => U): U = {
    Locks(file, new java.util.concurrent.Callable[U] {
      def call(): U = f
    })
  }
  // We use the lock file they give us to write the server info.  However,
  // it seems we cannot both use the server info file for locking *and*
  // read from it successfully.  Locking seems to blank the file. SO, we create
  // another file near the info file to lock.a
  def makeLockFile(f: File): File =
    new File(f.getParentFile, s"${f.getName}.lock")
  // Launch the process and read the port...
  def locate(currentDirectory: File, config: LaunchConfiguration): URI = 
    config.serverConfig match {
      case None => sys.error("No server lock file configured.  Cannot locate server.")
      case Some(sc) => locked(makeLockFile(sc.lockFile)) {
        readProperties(sc.lockFile) match {
          case Some(uri) if isReachable(uri) => uri
          case _ => 
            val uri = ServerLauncher.startServer(currentDirectory, config)
            writeProperties(sc.lockFile, uri)
            uri
        }
      }
    }
  
  private val SERVER_URI_PROPERTY = "server.uri"
  def readProperties(f: File): Option[java.net.URI] = {
    try {
      val props = new java.util.Properties
      val in = new java.io.FileInputStream(f)
      try {
        props.load(in)
      } finally in.close()
      props.getProperty(SERVER_URI_PROPERTY) match {
        case null => None
        case uri  => Some(new java.net.URI(uri))
      }
    } catch {
      case e: IOException => None
    }
  }
  def writeProperties(f: File, uri: URI): Unit = {
    val props = new java.util.Properties
    props.setProperty(SERVER_URI_PROPERTY, uri.toASCIIString)
    val output = new java.io.FileOutputStream(f)
    // TODO - Better date format.
    try props.store(output, s"Server Startup at ${new java.util.Date()}")
    finally output.close()
  }
  
  def isReachable(uri: java.net.URI): Boolean = 
    try {
      // TODO - For now we assume if we can connect, it means
      // that the server is working...
      val socket = new java.net.Socket(uri.getHost, uri.getPort)
      try socket.isConnected
      finally socket.close()
    } catch {
      case e: IOException => false
    }
}
object ServerLauncher {
  import ServerApplication.SERVER_SYNCH_TEXT
  def startServer(currentDirectory: File, config: LaunchConfiguration): URI = {
    // TODO - better error message on failure.  We shouldn't make it here if this is not true...
    val serverConfig = config.serverConfig.get
    val launchConfig = java.io.File.createTempFile("sbtlaunch", "config")
    LaunchConfiguration.save(config, launchConfig)
    val pb = new java.lang.ProcessBuilder()
    val jvmArgs: List[String] = serverConfig.jvmArgs map readLines match {
      case Some(args) => args
      case None => Nil
    }
    // TODO - Figure out how we make use of -D properties. Probably loading in the server itself...
    // TODO - Handle windows path stupidity
    val cmd: List[String] = 
      ("java" :: jvmArgs) ++
      ("-jar" :: defaultLauncherLookup.getCanonicalPath :: s"@load:${launchConfig.toURI.toURL.toString}" :: Nil)
    pb.command(cmd:_*)
    pb.directory(currentDirectory)
    val process = pb.start()
    val input = process.getInputStream
    readUntilSynch(new java.io.BufferedReader(new java.io.InputStreamReader(input))) match {
      case Some(uri) => uri
      case _ =>  sys.error("Failed to start server!")
    }
  }
  
  object ServerUriLine {
    def unapply(in: String): Option[URI] =
      if(in startsWith SERVER_SYNCH_TEXT) {
        Some(new URI(in.substring(SERVER_SYNCH_TEXT.size)))
      } else None
  }
  /** Reads an input steam until it hits the server synch text and server URI. */
  def readUntilSynch(in: java.io.BufferedReader): Option[URI] = {
    def read(): Option[URI] = in.readLine match {
      case null => None
      case ServerUriLine(uri) => Some(uri)
      case line => read()
    }
    try read()
    finally in.close()
  }
  /** Reads all the lines in a file. If it doesn't exist, returns an empty list. */
  def readLines(f: File): List[String] = 
    if(!f.exists) Nil else {
      // TODO - charsets...
      val reader = new java.io.BufferedReader(new java.io.FileReader(f))
      def read(current: List[String]): List[String] = 
        reader.readLine match {
          case null => current.reverse
          case line => read(line :: current)
        }
      try read(Nil)
      finally reader.close()
    }
  
  def defaultLauncherLookup: File =
    findLauncherReflectively getOrElse sys.error("Unable to find sbt-launch.jar file.")
  def findLauncherReflectively: Option[File] =
    try {
      val classInLauncher = classOf[AppConfiguration]
      for {
        domain <- Option(classInLauncher.getProtectionDomain)
        source <- Option(domain.getCodeSource)
        location = source.getLocation
      } yield new java.io.File(location.toURI)
    } catch {
      case NonFatal(e) => None
    }
}