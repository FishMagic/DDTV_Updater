import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream


@Serializable
data class Dict(
  val dict: MutableMap<String, Dict> = mutableMapOf(), val file: MutableMap<String, String> = mutableMapOf()
)

const val updateHost = "https://raw.githubusercontent.com/FishMagic/DDTV_Updater/master/releases/"

val whiteListFile = listOf(".type", "config.json", "DDTV_Upgrader", "DDTV_Upgrader.bat")
val whiteListDict = listOf("lib/", "log/", "runtime/")

fun main(args: Array<String>) {
  runBlocking {
    if (args.size >= 4 && args[0] == "dev") {
      updaterDEV(args)
    } else {
      updaterUser()
    }
  }
}

suspend fun updaterUser() {
  val osType = checkPlatform()
  if (osType == "unknown") {
    println("不支持的平台")
  } else {
    println("操作系统判断为：$osType")
  }
  val typeFile = File(".type")
  val type = withContext(Dispatchers.IO) {
    val typeFIS = FileInputStream(typeFile)
    val type = String(typeFIS.readBytes())
    typeFIS.close()
    type
  }
  val httpClient = HttpClient {
    install(ContentNegotiation) {
      json()
    }
  }
  println("开始下载文件摘要信息")
  val remoteDictString: String =
    withContext(Dispatchers.IO) { httpClient.get(updateHost + type + "/sha256-${osType}.json").body() }
  val remoteDict = Json.decodeFromString<Dict>(remoteDictString)
  println("文件摘要信息下载成功")
  println("开始计算本地文件摘要信息")
  val localDict = calcSHA256()
  println("本地文件摘要信息计算成功")
  println("开始对比差异")
  val updateMap = checkUpdateFile(remoteDict, localDict)
  println("对比差异完成")
  println("共发现${updateMap.keys.size}个需要下载的文件")
  updateMap.forEach { (key, file) ->
    if (!file.exists()) {
      file.createNewFile()
    }
    val downloadPath = if (key.indexOf("app") != -1 && key.indexOf("app") < key.indexOf("/")) {
      "$updateHost$type/$key"
    } else {
      "$updateHost$type/$osType/$key"
    }
    val downloadURL = Url(downloadPath.replace(" ", "%20"))
    println("开始下载$key")
    runBlocking {
      downloadFile(httpClient, downloadURL, file)
    }
    println("${key}下载完成")
  }
}

@OptIn(ExperimentalSerializationApi::class)
suspend fun updaterDEV(args: Array<String>) {
  val typeFile = File(args[1])
  println("正在生成通用文件摘要")
  var dict = Dict()
  dict.dict["app"] = calcSHA256(File(typeFile, "app"))
  println("通用文件摘要生成完成")
  println("正在生成平台文件摘要")
  dict = calcSHA256(File(typeFile, args[2]), dict)
  println("平台文件摘要生成完成")
  println("正在生成版本文件")
  val versionFile = File(typeFile, "version")
  withContext(Dispatchers.IO) {
    if (!versionFile.exists()) {
      versionFile.createNewFile()
    }
    val versionFOS = FileOutputStream(versionFile)
    versionFOS.write(args[3].toByteArray())
    versionFOS.flush()
    versionFOS.close()
  }
  println("版本文件生成完成")
  println("正在生成摘要记录文件")
  val sha256File = File(typeFile, "sha256-${args[2]}.json")
  withContext(Dispatchers.IO) {
    if (!sha256File.exists()) {
      sha256File.createNewFile()
    }
    val sha256FOS = FileOutputStream(sha256File)
    Json.encodeToStream(dict, sha256FOS)
    sha256FOS.flush()
    sha256FOS.close()
  }
  println("摘要记录文件记录完成")
}

suspend fun downloadFile(httpClient: HttpClient, url: Url, file: File) {
  withContext(Dispatchers.IO) {
    file.delete()
    file.createNewFile()
    val fileByteArray: ByteArray = httpClient.get(url).body()
    file.appendBytes(fileByteArray)
  }
}

suspend fun checkUpdateFile(
  remoteDict: Dict, localDict: Dict, dictName: String = "", updateMap: MutableMap<String, File> = mutableMapOf()
): MutableMap<String, File> {
  withContext(Dispatchers.IO) {
    localDict.file.forEach { (key, file) ->
      val remoteFile = remoteDict.file[key]
      val fileName = "$dictName$key"
      if (remoteFile == null) {
        if (fileName !in whiteListFile) {
          File(fileName).delete()
          println("已删除$fileName")
        }
      } else {
        if (file == remoteFile) {
          remoteDict.file.remove(key)
        } else {
          updateMap[fileName] = File(fileName)
          remoteDict.file.remove(key)
        }
      }
    }
    localDict.dict.forEach { (key, dict) ->
      val childRemoteDict = remoteDict.dict[key]
      val childDictName = "$dictName$key/"
      if (childRemoteDict == null) {
        if (childDictName !in whiteListDict) {
          delDict(File(childDictName))
          println("已删除$dictName$key")
        }
      } else {
        checkUpdateFile(childRemoteDict, dict, "$dictName$key/", updateMap)
        remoteDict.dict.remove(key)
      }
    }
    remoteDict.file.forEach { (key, _) ->
      val fileName = "$dictName$key"
      updateMap[fileName] = File(fileName)
    }
    remoteDict.dict.forEach { (key, dict) ->
      val childDictName = "$dictName$key/"
      val childDict = File(childDictName)
      if (!childDict.exists()) {
        childDict.mkdir()
      }
      checkUpdateFile(dict, Dict(), "$dictName$key/", updateMap)
    }
  }
  return updateMap
}

suspend fun delDict(dict: File) {
  withContext(Dispatchers.IO) {
    dict.listFiles()?.forEach { file ->
      if (file.isFile) {
        file.delete()
      } else {
        delDict(file)
      }
    }
  }
}

suspend fun calcSHA256(path: File = File("."), pathDict: Dict = Dict()): Dict {
  println("进入目录${path.path}")
  path.listFiles()?.forEach {
    if (it.isDirectory) {
      println("发现目录${it.name}")
      pathDict.dict[it.name] = calcSHA256(it)
    } else {
      println("发现文件${it.name}")
      pathDict.file[it.name] = sha256(it)
    }
  }
  return pathDict
}

suspend fun sha256(file: File): String {
  return withContext(Dispatchers.Default) {
    val digest = MessageDigest.getInstance("SHA-256")
    val byteArray = file.readBytes()
    val filterArray = byteArray.filter { it != '\r'.code.toByte() }
    val result = digest.digest(filterArray.toByteArray())
    buildString {
      result.forEach {
        val hexString = Integer.toHexString(it.toInt() and (0xff))
        if (hexString.length == 1) {
          append("0$hexString")
        } else {
          append(hexString)
        }
      }
    }
  }
}

fun checkPlatform(): String {
  val osString = System.getProperty("os.name").lowercase()
  if (osString.indexOf("linux") != -1) return "linux"
  if (osString.indexOf("mac") != -1) return "mac"
  if (osString.indexOf("windows") != -1) return "windows"
  return "unknown"
}