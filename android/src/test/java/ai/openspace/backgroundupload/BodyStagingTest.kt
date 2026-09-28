package ai.openspace.backgroundupload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BodyStagingTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun source(name: String, bytes: ByteArray) = File(tmp.newFolder(), name).apply { writeBytes(bytes) }

  @Test
  fun `json bytes are the data text, with a default content type`() {
    val dir = tmp.newFolder()
    val staged = BodyStaging.stage(desc(dataJson = """{"n":1,"t":"é"}""", headers = mapOf()), dir, 1)
    assertEquals(StagedBody.JSON, staged.body.kind)
    assertEquals("body-1.json", staged.body.fileName)
    assertEquals("""{"n":1,"t":"é"}""", File(dir, "body-1.json").readText())
    assertEquals(File(dir, "body-1.json").length(), staged.body.totalBytes)
    assertEquals(mapOf("Content-Type" to "application/json"), staged.headers)
  }

  @Test
  fun `a caller content type wins for json, in any case`() {
    val staged = BodyStaging.stage(desc(dataJson = "{}", headers = mapOf("content-type" to "application/vnd+json")), tmp.newFolder(), 1)
    assertEquals(mapOf("content-type" to "application/vnd+json"), staged.headers)
  }

  @Test
  fun `multipart bytes follow RFC 7578`() {
    val photo = source("photo.jpg", byteArrayOf(1, 2, 3))
    val form = listOf(
      FormPart("meta", "application/json", "{\"a\":\"b\"}", null, null),
      FormPart("pho\"to", "image/jpeg", null, photo.path, null),
      FormPart("named", "image/jpeg", null, photo.path, "new\nname.jpg"),
    )
    val target = File(tmp.newFolder(), "body.multipart")
    BodyStaging.writeMultipart(form, "BOUND", target)
    val expected = ("--BOUND\r\n" +
      "Content-Disposition: form-data; name=\"meta\"\r\n" +
      "Content-Type: application/json\r\n\r\n" +
      "{\"a\":\"b\"}\r\n" +
      "--BOUND\r\n" +
      "Content-Disposition: form-data; name=\"pho%22to\"; filename=\"photo.jpg\"\r\n" +
      "Content-Type: image/jpeg\r\n\r\n").toByteArray() + byteArrayOf(1, 2, 3) + ("\r\n" +
      "--BOUND\r\n" +
      "Content-Disposition: form-data; name=\"named\"; filename=\"new%0Aname.jpg\"\r\n" +
      "Content-Type: image/jpeg\r\n\r\n").toByteArray() + byteArrayOf(1, 2, 3) + ("\r\n" +
      "--BOUND--\r\n").toByteArray()
    assertArrayEquals(expected, target.readBytes())
  }

  @Test
  fun `form staging always sets the library content type with its boundary`() {
    val dir = tmp.newFolder()
    val staged = BodyStaging.stage(
      desc(form = listOf(FormPart("a", "text/plain", "x", null, null)), headers = mapOf("CONTENT-TYPE" to "text/plain")),
      dir, 2,
    )
    val boundary = staged.body.boundary!!
    assertTrue(boundary.startsWith("----RNBGU"))
    assertEquals(mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"), staged.headers)
    assertTrue(File(dir, "body-2.multipart").readText().startsWith("--$boundary\r\n"))
  }

  @Test
  fun `a file body is copied and the source stays`() {
    val src = source("a.bin", ByteArray(1000) { it.toByte() })
    val dir = tmp.newFolder()
    val staged = BodyStaging.stage(desc(file = src.path), dir, 3)
    assertTrue(src.exists())
    assertArrayEquals(src.readBytes(), File(dir, "file-3").readBytes())
    assertEquals(1000, staged.body.totalBytes)
    assertEquals(mapOf("Authorization" to "Bearer old"), staged.headers) // no content type added
    src.delete()
    assertTrue(File(dir, "file-3").exists())
  }

  @Test
  fun `a chunked file is moved and must tile the parts`() {
    val src = source("video.bin", ByteArray(20))
    val dir = tmp.newFolder()
    val staged = BodyStaging.stage(desc(url = null, file = src.path, parts = listOf(part(0, 10), part(10, 20))), dir, 1)
    assertFalse(src.exists())
    assertEquals(20, File(dir, "blob").length())
    assertEquals(StagedBody.CHUNKED, staged.body.kind)
    assertEquals(20, staged.body.totalBytes)
  }

  @Test
  fun `an orphan blob is adopted when the source is gone`() {
    val dir = tmp.newFolder()
    File(dir, "blob").writeBytes(ByteArray(20))
    val staged = BodyStaging.stage(desc(url = null, file = "/gone.bin", parts = listOf(part(0, 20))), dir, 1)
    assertEquals(StagedBody.CHUNKED, staged.body.kind)
  }

  @Test
  fun `a tiling mismatch rejects E_INVALID before the move, so the source stays`() {
    val src = source("video.bin", ByteArray(20))
    val dir = tmp.newFolder()
    val e = assertThrows(QueueException::class.java) {
      BodyStaging.stage(desc(url = null, file = src.path, parts = listOf(part(0, 15))), dir, 1)
    }
    assertEquals(QueueException.E_INVALID, e.code)
    assertTrue(src.exists())
    assertFalse(File(dir, "blob").exists())
  }

  @Test
  fun `keepOwned runs over the owned blob and ignores the path`() {
    val dir = tmp.newFolder()
    val owned = File(dir, "blob").apply { writeBytes(ByteArray(20)) }
    val other = source("other.bin", ByteArray(5))
    val staged = BodyStaging.stage(
      desc(url = null, file = other.path, parts = listOf(part(0, 20))), dir, 2, owned, keepOwned = true,
    )
    assertTrue(other.exists())
    assertEquals("blob", staged.body.fileName)
    assertEquals(20, owned.length())
  }

  @Test
  fun `a present source wins over the owned blob and moves to this generation's name`() {
    val dir = tmp.newFolder()
    val owned = File(dir, "blob").apply { writeBytes(ByteArray(20)) }
    val bytes = ByteArray(20) { (it + 1).toByte() }
    val src = source("new.bin", bytes)
    val staged = BodyStaging.stage(desc(url = null, file = src.path, parts = listOf(part(0, 5), part(5, 20))), dir, 3, owned)
    assertEquals("blob-3", staged.body.fileName)
    assertArrayEquals(bytes, File(dir, "blob-3").readBytes())
    assertFalse(src.exists())
    // The old entry's blob is untouched until the new entry is saved and prunes it.
    assertArrayEquals(ByteArray(20), owned.readBytes())
  }

  @Test
  fun `a present source of another size is checked against its own length`() {
    val dir = tmp.newFolder()
    val owned = File(dir, "blob").apply { writeBytes(ByteArray(20)) }
    val src = source("new.bin", ByteArray(30))
    val staged = BodyStaging.stage(desc(url = null, file = src.path, parts = listOf(part(0, 30))), dir, 2, owned)
    assertEquals("blob-2", staged.body.fileName)
    assertEquals(30, staged.body.totalBytes)
    assertEquals(20, owned.length())
  }

  @Test
  fun `with the source gone, the owned blob is the fallback`() {
    val dir = tmp.newFolder()
    File(dir, "blob").writeBytes(ByteArray(20))
    val staged = BodyStaging.stage(
      desc(url = null, file = "/gone.bin", parts = listOf(part(0, 5), part(5, 20))), dir, 2, File(dir, "blob"),
    )
    assertEquals("blob", staged.body.fileName)
  }

  @Test
  fun `with the source gone, this generation's crash leftover wins over the owned blob`() {
    val dir = tmp.newFolder()
    File(dir, "blob").writeBytes(ByteArray(20))
    File(dir, "blob-2").writeBytes(ByteArray(30))
    val staged = BodyStaging.stage(desc(url = null, file = "/gone.bin", parts = listOf(part(0, 30))), dir, 2, File(dir, "blob"))
    assertEquals("blob-2", staged.body.fileName)
  }

  @Test
  fun `a chunked body with no source and no blob rejects E_FILE_MISSING`() {
    val e = assertThrows(QueueException::class.java) {
      BodyStaging.stage(desc(url = null, file = "/gone.bin", parts = listOf(part(0, 20))), tmp.newFolder(), 2, null)
    }
    assertEquals(QueueException.E_FILE_MISSING, e.code)
  }

  @Test
  fun `a missing source rejects E_FILE_MISSING and writes nothing`() {
    val dir = tmp.newFolder()
    val form = listOf(
      FormPart("a", "text/plain", "x", null, null),
      FormPart("b", "image/jpeg", null, "/missing.jpg", null),
    )
    val e = assertThrows(QueueException::class.java) { BodyStaging.stage(desc(form = form), dir, 1) }
    assertEquals(QueueException.E_FILE_MISSING, e.code)
    assertEquals(0, dir.list()!!.size)
    val f = assertThrows(QueueException::class.java) { BodyStaging.stage(desc(file = "/missing.bin"), dir, 1) }
    assertEquals(QueueException.E_FILE_MISSING, f.code)
    val c = assertThrows(QueueException::class.java) {
      BodyStaging.stage(desc(url = null, file = "/missing.bin", parts = listOf(part(0, 1))), dir, 1)
    }
    assertEquals(QueueException.E_FILE_MISSING, c.code)
  }

  @Test
  fun `no body stages nothing`() {
    val dir = tmp.newFolder()
    val staged = BodyStaging.stage(desc(method = "DELETE"), dir, 1)
    assertEquals(StagedBody(StagedBody.NONE, null, null, 0), staged.body)
    assertEquals(0, dir.list()!!.size)
  }
}
